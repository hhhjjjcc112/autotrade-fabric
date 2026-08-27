package com.github.sebseb7.autotrade.trade.executor;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.compat.itemscroller.ItemScrollerTradeCompat;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
import com.github.sebseb7.autotrade.trade.stats.TradeStats;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

// 抽象基类：共享全部交易流程（现状逻辑整体迁入，行为等价；uses 仅扫描时刻快照读取）
abstract class AbstractTradeStrategy implements TradeStrategy {
	/**
	 * exact-N 右键包数预算（语义扩展：每源槽独立判定，值不变 = 8）——单成本路径的 S−M 差值、双成本路径
	 * 各补充源槽差值、二分拆半收尾溢出均以该值为界；单成本超过 → 回退空间封顶 QUICK_MOVE（仍防饿死）， 双成本超过 → 撤销 +
	 * CAPACITY_SKIP（永不回退，见 exactTradeNDual/undoOrSkip）。
	 */
	private static final int EXACT_N_MAX_RIGHT_CLICKS = 8;

	/** 本次会话是否因背包空间不足/结果滞留而阻塞（供会话层决定提前结束并触发容器 IO） */
	protected boolean inventoryBlocked = false;

	// 会话内 pairs 预解码缓存：以配置串引用为失效信号（malilib getStringValue 返回字段引用；GUI 保存/加载
	// 必产生新 String → 引用不等 → 重建数组）。引用相等时每 tick 复用数组，免去 offers×pairs 循环内 Gson 解析
	private String cachedPairsJson = null;
	private ParsedPair[] cachedParsedPairs = null;

	/**
	 * 创建交易项状态对象（差异点钩子：非 use = SnapshotOfferState 快照推导，use = LiveOfferState 直接读
	 * uses）
	 */
	protected abstract OfferState createOffer(TradeOffer offer, int index, boolean starvationCandidate);

	// ---- 静态匹配辅助方法 ----

	// 点击指定槽位 QUICK_MOVE，并使用点击后的本地槽位状态继续判断结果。
	private static void quickMoveSlot(MinecraftClient mc, MerchantScreenHandler handler, Slot slot) {
		clickSlot(mc, handler, slot.id, 0, SlotActionType.QUICK_MOVE);
	}

	// 点击指定槽位，支持 PICKUP 和 QUICK_MOVE；点击异常只记录日志，不中断当前处理。
	private static void clickSlot(MinecraftClient mc, MerchantScreenHandler handler, int slotId, int button,
			SlotActionType type) {
		try {
			mc.interactionManager.clickSlot(handler.syncId, slotId, button, type, mc.player);
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] 槽 {} 点击失败 (button={}, type={})", slotId, button, type, e);
		}
	}

	// 计算装填完成后背包对交易结果的可用容量；此时槽 0/1 中的装填成本不计入背包容量。
	private static int calculateResultCapacity(MerchantScreenHandler handler, ItemStack result) {
		boolean stackable = result.getMaxCount() > 1;
		int emptySlots = 0;
		int mergeSpace = 0;
		for (int i = 3; i < 39; i++) {
			ItemStack stack = handler.getSlot(i).getStack();
			if (stack.isEmpty()) {
				// 空槽按个计数：可堆叠结果每个空槽可容纳 result.getMaxCount() 个（返回时相乘）；不可堆叠结果每笔交易需 1 个空槽
				emptySlots += 1;
			} else if (stackable && stack.isOf(result.getItem()) && ItemStack.canCombine(stack, result)) {
				// 同物品同 NBT 的未满堆叠：计入可合并空间（与 insertItem 的 canCombine 判定一致）
				mergeSpace += stack.getMaxCount() - stack.getCount();
			}
		}
		return stackable ? emptySlots * result.getMaxCount() + mergeSpace : emptySlots;
	}

	// 计算本次点击可使用的整批笔数：inputBatch = min(floor(槽0/costA), floor(槽1/costB))。
	private static int computeInputBatch(MerchantScreenHandler handler, TradeOffer offer) {
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		ItemStack costB = offer.getSecondBuyItem();
		int trades = Integer.MAX_VALUE;
		// 第一成本槽：槽内数量整除单笔成本取整
		if (!costA.isEmpty()) {
			trades = Math.min(trades, handler.getSlot(0).getStack().getCount() / costA.getCount());
		}
		// 第二成本槽（仅双成本 offer 存在；单成本时 costB 为 EMPTY，判空防 0/0 除零）
		if (!costB.isEmpty()) {
			trades = Math.min(trades, handler.getSlot(1).getStack().getCount() / costB.getCount());
		}
		// 防御：成本槽为空时整除结果为 0，clamp 到非负
		return Math.max(0, trades);
	}

	// 预留「本次点击后槽 0/1 剩余成本回背包」所需空间：cost==result 按物品数精确占用结果容量，否则按量级化比较占用 1 个空槽
	private static int calculateLeftoverReservation(MerchantScreenHandler handler, TradeOffer offer, int trades) {
		ItemStack result = offer.getSellItem();
		int reservation = 0;
		// 逐输入槽处理剩余成本（槽 0 = 第一成本，槽 1 = 第二成本）
		for (int i = 0; i < 2; i++) {
			ItemStack cost = (i == 0 ? offer.getAdjustedFirstBuyItem() : offer.getSecondBuyItem());
			if (cost.isEmpty())
				continue;
			// 本次点击消耗 trades 笔后槽内剩余的成本数量
			int leftover = handler.getSlot(i).getStack().getCount() - trades * cost.getCount();
			if (leftover <= 0)
				continue;
			if (cost.isOf(result.getItem()) && ItemStack.canCombine(cost, result)) {
				// 成本与结果同物品：剩余成本并入结果堆叠，按物品数精确扣结果容量
				reservation += leftover;
			} else {
				// 成本 ≠ 结果：统计 3-38 中可并入成本物品的未满堆叠空间（逐槽累加可合并数量，量级化）
				int costMerge = costMergeSpace(handler, cost);
				// 剩余成本无法全部并入成本堆叠 → 需占 1 个空槽（该空槽本可装 result.getMaxCount() 个结果；不可堆叠结果为 1）
				if (leftover > costMerge) {
					reservation += result.getMaxCount();
				}
			}
		}
		return reservation;
	}

	// 统计 3-38 中可并入成本物品（canCombine）的未满堆叠空间之和（量级化：剩余成本无法全部并入
	// 成本堆叠 → 调用方需占 1 个空槽）
	private static int costMergeSpace(MerchantScreenHandler handler, ItemStack cost) {
		int costMerge = 0;
		for (int j = 3; j < 39; j++) {
			ItemStack s = handler.getSlot(j).getStack();
			if (!s.isEmpty() && ItemStack.canCombine(s, cost)) {
				costMerge += s.getMaxCount() - s.getCount();
			}
		}
		return costMerge;
	}

	// 判断本次有效整批是否能放入背包：所需结果数量不超过扣除剩余成本占位后的容量。
	// 判定用 trades = effectiveBatch（有效整批，由调用方计算并传入——含 uses 剩余次数封顶）；
	// exact-N 判定预留 = 0（输入精确消耗），由调用方另行计算 affordable
	private static boolean canFitEffectiveBatch(MerchantScreenHandler handler, TradeOffer offer, int effectiveBatch) {
		// 防御性守卫：正常流程已保证槽 2 有结果 ⟹ 输入够 1 笔 ⟹ effectiveBatch≥1，双保险（等价于原 inputBatch 守卫）
		if (effectiveBatch <= 0) {
			return false;
		}
		// 有效整批全部结果所需容量（long 防溢出）——effectiveBatch 已按剩余次数封顶，不再按整批高估
		long need = (long) effectiveBatch * offer.getSellItem().getCount();
		// 结果可容纳量（无占位版，post-autofill 状态下槽 0/1 成本已移出 3-38）
		int capacity = calculateResultCapacity(handler, offer.getSellItem());
		// 预留本次点击后槽 0/1 剩余成本回背包所占用的容量。
		int reservation = calculateLeftoverReservation(handler, offer, effectiveBatch);
		// 可容纳量扣除预留后仍 ≥ 所需 → 有效整批可容纳（QUICK_MOVE；每次中间 insertItem 完整插入，无部分插入丢失）
		return capacity - reservation >= need;
	}

	// 容量不足候选门：autofillBatch × sellCount > 36 × resultMaxCount。
	// 候选表示自动装填得到的整批结果超过空背包理论容量，需要尝试 exact-N；双成本交易走 exactTradeNDual
	// （双成本 exact-N，不再回退 QUICK_MOVE——候选门公式本身不变，仅双成本路径出口变更）。
	private static boolean isStarvationCandidate(TradeOffer offer) {
		// autofill 单输入槽最大填充量对应的整批笔数：可堆叠 = maxCount/costCount（珍珠等 16、绿宝石 64），
		// 不可堆叠（maxCount=1）→ 1 笔；36 × resultMaxCount = 空背包理论最大容量（槽 3-38 共 36 槽）
		int costCount = offer.getAdjustedFirstBuyItem().getCount();
		int costMaxCount = offer.getAdjustedFirstBuyItem().getMaxCount();
		int autofillBatch = costMaxCount > 1 ? costMaxCount / costCount : 1;
		int sellCount = offer.getSellItem().getCount();
		int resultMaxCount = offer.getSellItem().getMaxCount();
		return autofillBatch * sellCount > 36 * resultMaxCount;
	}

	// 判定候选 offer 是否可由该交易对执行：匹配（doesOfferMatchPair，失败且 offer 实际有第二成本但交易对
	// 未配置 give2 时打降级警告日志）+ 单笔成本不超 limit + 背包有全部成本。
	// 所有拒绝路径均打诊断日志（不再静默——give2 严格匹配/limit/成本不足的失败此前对用户不可见）。
	// @return true = 可执行（调用方记入快照并结束内层交易对循环）
	private static boolean isOfferExecutableForPair(TradeOffer candidate, ParsedPair pair, int pairIndex,
			PlayerEntity player) {
		// 成本/产出物品与交易对不一致 → 不可执行（按原因细分日志：双成本 offer 未配 give2 / 双成本配置仍不匹配）
		if (!doesOfferMatchPair(candidate, pair)) {
			boolean offerHasSecondCost = !candidate.getSecondBuyItem().isEmpty();
			if (offerHasSecondCost && !pair.hasGive2()) {
				// 交易项有第二成本但交易对未配置 give2 时，严格匹配会拒绝该交易项并记录提示。
				AutoTrade.logger.info(
						"[AutoTrade] pair #{} no longer matches (offer has 2nd cost), configure give2 to match",
						pairIndex);
			} else if (offerHasSecondCost && pair.hasGive2()) {
				// 双成本交易对仍不匹配：give/give2/get 物品不一致，或产出 NBT 已变化
				// （如附魔书交易在村民补货后随机生成新附魔 → 需重新捕获该交易）
				AutoTrade.logger.info(
						"[AutoTrade] pair #{} no longer matches (give/give2/get mismatch or NBT changed), re-capture the trade",
						pairIndex);
			} else {
				AutoTrade.logger.info("[AutoTrade] pair #{} no longer matches offer (give/get mismatch)", pairIndex);
			}
			return false;
		}
		// 单笔成本超过交易对上限（防止大额成本交易被无限执行）→ 不可执行。
		// 用原始（未调价）第一成本对比——demand/specialPrice 波动造成的涨价不会让已捕获的交易对失效；
		// 只有「匹配到基础价格更高的其他交易」才被拒绝（修复：价格随 demand 上涨后交易对静默失效的 bug，
		// 图书管理员附魔书交易 priceMultiplier=0.2，demand≥1 即涨价 6+，超过捕获时的 limit 32）
		int baseCost = candidate.getOriginalFirstBuyItem().getCount();
		if (baseCost > pair.limit()) {
			AutoTrade.logger.info("[AutoTrade] pair #{} offer skipped: base cost {} > limit {}", pairIndex, baseCost,
					pair.limit());
			return false;
		}
		// 背包成本不足 → 不可执行（adjusted 价格随 demand 上涨时，此处按调整后价格检查实际支付能力）
		if (!playerHasMerchantCosts(player, candidate)) {
			AutoTrade.logger.info("[AutoTrade] pair #{} offer skipped: insufficient costs in inventory", pairIndex);
			return false;
		}
		return true;
	}

	// 装填指定 offer：setRecipeIndex + switchTo + select 包（顺序同现主循环；setRecipeIndex 不可省略，
	// 缺省时非 0 号交易本地结果槽可能不生成）
	private static void refillOffer(MinecraftClient mc, MerchantScreenHandler handler, OfferState target) {
		// 真实索引：setRecipeIndex（服务端当前 offer）与发包必须用真实索引；
		// switchTo 内部读 getRecipes()（ItemScroller 下为重排列表）→ 须用可见索引取到正确装填物品
		int realIndex = target.index;
		int visibleIndex = ItemScrollerTradeCompat.getVisibleIndex(handler, realIndex, target.offer);
		handler.setRecipeIndex(realIndex);
		handler.switchTo(visibleIndex);
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(realIndex));
		}
	}

	// 统计槽 3-38 中与卖品同物品同 NBT（可合并）的物品总数（同 tick 本地增量计数用快照——
	// 插入只会合并进 canCombine 堆叠或新空槽，差值即本次插入量；预存堆叠前后不变自动抵消）
	private static int countSellItemsInInventory(MerchantScreenHandler handler, ItemStack result) {
		int total = 0;
		for (int i = 3; i < 39; i++) {
			ItemStack s = handler.getSlot(i).getStack();
			if (!s.isEmpty() && s.isOf(result.getItem()) && ItemStack.canCombine(s, result)) {
				total += s.getCount();
			}
		}
		return total;
	}

	// exact-N 成本源槽选择：优先选「数量 ≥ M 且 |S−M| 最小」的堆叠（下限 M——选中 S < M 会导致
	// 实际成交 < N 的低效）；无 ≥ M 者 → 选数量最大堆叠（成交 < N 但有进展、无溢出，接受）。
	// @return 选中的源槽；无任何可合并成本堆叠时为 null（调用方走守卫回退）
	private static Slot selectCostSourceSlot(MerchantScreenHandler handler, ItemStack cost, int m) {
		Slot source = null;
		int bestDiff = Integer.MAX_VALUE;
		int maxCount = -1;
		for (int i = 3; i < 39; i++) {
			ItemStack s = handler.getSlot(i).getStack();
			if (s.isEmpty() || !s.isOf(cost.getItem()) || !ItemStack.canCombine(s, cost)) {
				continue;
			}
			if (s.getCount() >= m) {
				// 第一遍：S ≥ M 且 |S−M| 最小
				int diff = Math.abs(s.getCount() - m);
				if (diff < bestDiff) {
					bestDiff = diff;
					source = handler.getSlot(i);
				}
			} else if (source == null && s.getCount() > maxCount) {
				// 第二遍（仅当尚无 ≥M 候选）：数量最大堆叠兜底
				maxCount = s.getCount();
				source = handler.getSlot(i);
			}
		}
		return source;
	}

	// 二分拆半：把输入槽（槽 0/1）数量从 S 精确降到 keep（计划 D3）。多余 S−keep 放回背包可合并槽 B，光标净空。
	// 前置：光标为空；B = 与成本可合并的背包槽（未满，优先）或任一空槽（调用方 selectMergeOrEmptySlot 保证）。
	// 不变量：目标槽为「待拆分堆叠」，B 累积「已拆出多余」，光标空。
	// 阶段 1 每轮：光标空 + 右键槽 = 取半 ceil((cur+1)/2)（PICKUP 单笔语义，F5）→ 槽剩 floor(cur/2)；
	// 左键 B = 光标全部并入 → 光标空。轮数 ≤ log2(64) ≈ 6（S=64 时），点击数从 O(S−M) 降到 O(log S)。
	// 收尾（放回法）：溢出 ≤ 右键预算(8) → PICKUP 槽（光标 = cur、槽 = 0）→ 右键 B × overflow（光标每次
	// 放回 1 → 光标 = keep）→ PICKUP 槽（光标 keep 放回 → 槽 = keep ✓）。
	// 阶段 2 回补：取半过头（槽 < keep）→ 每轮 B 取半到光标、右键槽放回 1 个（槽 +1）、左键 B 清光标；
	// 循环上限 = keep − 当前量（每轮至少 +1，有界）。
	// 违反后果：光标残留（B 槽空间不足等极端情况）→ 后续点击语义改变（光标非空时右键 = 放回而非取半）
	// → 返回 false，调用方撤销 + CAPACITY_SKIP（防静默错交易）。
	// @return true = 槽数量精确等于 keep 且光标净空；false = 失败（调用方走 undoFill + CAPACITY_SKIP）
	private static boolean splitSlotExact(MinecraftClient mc, MerchantScreenHandler handler, int slotIndex, int keep,
			Slot b) {
		// 前置守卫：B 槽缺失 → 失败（防御——调用方 selectMergeOrEmptySlot 已保证非 null）
		if (b == null) {
			return false;
		}
		Slot target = handler.getSlot(slotIndex);
		// 已满足（含 s < keep 的情况由调用方补充源槽）→ 无需点击
		if (target.getStack().getCount() <= keep) {
			return true;
		}
		// 阶段 1：二分拆半，直到槽数量 ≤ keep 或进入收尾路径
		while (target.getStack().getCount() > keep) {
			int cur = target.getStack().getCount();
			int overflow = cur - keep;
			// 收尾（放回法）：溢出 ≤ 右键预算 → 放回 finish，返回前校验光标净空 + 槽 = keep
			if (overflow <= EXACT_N_MAX_RIGHT_CLICKS) {
				clickSlot(mc, handler, slotIndex, 0, SlotActionType.PICKUP);
				for (int i = 0; i < overflow; i++) {
					clickSlot(mc, handler, b.id, 1, SlotActionType.PICKUP);
				}
				clickSlot(mc, handler, slotIndex, 0, SlotActionType.PICKUP);
				return handler.getCursorStack().isEmpty() && target.getStack().getCount() == keep;
			}
			// 光标空 + 右键 = 取半 ceil((cur+1)/2) → 槽剩 floor(cur/2)
			clickSlot(mc, handler, slotIndex, 1, SlotActionType.PICKUP);
			// 光标全部并入 B（光标净空，维持不变量）
			clickSlot(mc, handler, b.id, 0, SlotActionType.PICKUP);
		}
		// 阶段 2：回补（取半过头：槽 < keep）——每轮从 B 取半、右键槽放回 1 个、左键 B 清光标；
		// 循环上限 = keep − 当前量（每轮至少 +1，有界；B 累积的溢出 ≥ 差值，正常一轮不缺货）
		int rounds = keep - target.getStack().getCount();
		while (target.getStack().getCount() < keep && rounds-- > 0) {
			clickSlot(mc, handler, b.id, 1, SlotActionType.PICKUP);
			clickSlot(mc, handler, slotIndex, 1, SlotActionType.PICKUP);
			clickSlot(mc, handler, b.id, 0, SlotActionType.PICKUP);
		}
		// 不变量校验：光标净空 + 槽数量精确 = keep（B 满等极端导致的光标残留 → 失败，调用方撤销）
		return handler.getCursorStack().isEmpty() && target.getStack().getCount() == keep;
	}

	// 选择拆分/回补用的背包槽 B（计划 D3）：3-38 中与 cost 可合并且未满的槽（优先），否则任一空槽。
	// 前置：无（调用方每次使用前重选，避免跨槽状态）。@return B 槽；无可合并槽且无空槽 → null
	// （调用方走撤销 + CAPACITY_SKIP——无 B 则拆分产物无处安放，光标无法净空）
	private static Slot selectMergeOrEmptySlot(MerchantScreenHandler handler, ItemStack cost) {
		Slot empty = null;
		for (int i = 3; i < 39; i++) {
			ItemStack s = handler.getSlot(i).getStack();
			if (s.isEmpty()) {
				// 记下第一个空槽兜底（可合并槽优先——成本合并回收，避免空槽被拆分产物占满）
				if (empty == null) {
					empty = handler.getSlot(i);
				}
			} else if (ItemStack.canCombine(s, cost) && s.getCount() < s.getMaxCount()) {
				// 可合并且未满：直接返回（拆分产物可并入，光标可净空）
				return handler.getSlot(i);
			}
		}
		return empty;
	}

	// 双成本补充源槽选择（exactTradeNDual 用）：同 selectCostSourceSlot 的选源规则，但排除给定槽 id
	// （B1/B2/已用补充源槽——避免破坏已累积的拆分产物或重复取货）。
	// 规则：优先「数量 ≥ need 且 |S−need| 最小」的堆叠（下限 need——选中 S < need 会导致实际成交 < n 的
	// 低效）；无 ≥ need 者 → 数量最大堆叠兜底（成交 < n 但有进展、无溢出，接受）。
	// @return 选中的源槽；无任何可合并成本堆叠时为 null（调用方走撤销 + CAPACITY_SKIP）
	private static Slot selectCostSourceSlotExcluding(MerchantScreenHandler handler, ItemStack cost, int need,
			int... excludeSlotIds) {
		Slot source = null;
		int bestDiff = Integer.MAX_VALUE;
		int maxCount = -1;
		for (int i = 3; i < 39; i++) {
			// 排除已使用的槽（B 槽/已用补充源槽——避免破坏已累积的拆分产物或重复取货）
			boolean excluded = false;
			for (int id : excludeSlotIds) {
				if (id == i) {
					excluded = true;
					break;
				}
			}
			if (excluded) {
				continue;
			}
			ItemStack s = handler.getSlot(i).getStack();
			if (s.isEmpty() || !s.isOf(cost.getItem()) || !ItemStack.canCombine(s, cost)) {
				continue;
			}
			if (s.getCount() >= need) {
				// 第一遍：S ≥ need 且 |S−need| 最小
				int diff = Math.abs(s.getCount() - need);
				if (diff < bestDiff) {
					bestDiff = diff;
					source = handler.getSlot(i);
				}
			} else if (source == null && s.getCount() > maxCount) {
				// 第二遍（仅当尚无 ≥need 候选）：数量最大堆叠兜底
				maxCount = s.getCount();
				source = handler.getSlot(i);
			}
		}
		return source;
	}

	// 撤销手动 fill（计划 D5）：恢复交易前的安全状态——光标净空、槽 0/1 无残余（成本回背包）。
	// 步骤：① 光标有物品 → 左键放入背包可合并槽/空槽（无可用槽或放不下 → false）；
	// ② QUICK_MOVE 出槽 0 → 仍残留 ? false；③ QUICK_MOVE 出槽 1 → 仍残留 ? false。
	// 违反后果：撤销失败（背包真满，物品放不回）→ 保留现场 → STUCK（D5 守卫 6，真异常）；
	// 槽 2 预览在槽 0/1 清空后由 updateOffers 自动清除（F4：matchesBuyItems 不成立）→ 退出后无滞留物。
	// @return true = 已恢复；false = 撤销失败（调用方按 STUCK 处理）
	private static boolean undoFill(MinecraftClient mc, MerchantScreenHandler handler) {
		// ① 光标净空断言
		ItemStack cursor = handler.getCursorStack();
		if (!cursor.isEmpty()) {
			Slot deposit = selectMergeOrEmptySlot(handler, cursor);
			if (deposit == null) {
				return false;
			}
			clickSlot(mc, handler, deposit.id, 0, SlotActionType.PICKUP);
			// 槽空间不足放不下全部 → 光标仍残留 → 失败
			if (!handler.getCursorStack().isEmpty()) {
				return false;
			}
		}
		// ② QUICK_MOVE 出槽 0 → 仍残留 → 失败（背包满）
		Slot slot0 = handler.getSlot(0);
		if (slot0.hasStack()) {
			quickMoveSlot(mc, handler, slot0);
			if (slot0.hasStack()) {
				return false;
			}
		}
		// ③ QUICK_MOVE 出槽 1 → 仍残留 → 失败（背包满）
		Slot slot1 = handler.getSlot(1);
		if (slot1.hasStack()) {
			quickMoveSlot(mc, handler, slot1);
			if (slot1.hasStack()) {
				return false;
			}
		}
		return true;
	}

	// 判断交易与交易对是否匹配：成本物品等于 giveItem 且产出物品等于 getItem（预解码版本，循环内零 Gson）
	private static boolean doesOfferMatchPair(TradeOffer offer, ParsedPair pair) {
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		ItemStack costB = offer.getSecondBuyItem();
		boolean resultMatch = ItemStringHelper.matches(offer.getSellItem(), pair.get());
		if (pair.hasGive2()) {
			return resultMatch && ItemStringHelper.matches(costA, pair.give())
					&& ItemStringHelper.matches(costB, pair.give2());
		}
		return resultMatch && costB.isEmpty() && ItemStringHelper.matches(costA, pair.give());
	}

	// 检查玩家背包是否足以支付该交易的全部成本槽（第一/第二成本物品）
	private static boolean playerHasMerchantCosts(PlayerEntity player, TradeOffer offer) {
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		if (!costA.isEmpty() && !hasEnoughCostItems(player, costA)) {
			return false;
		}
		ItemStack costB = offer.getSecondBuyItem();
		if (!costB.isEmpty() && !hasEnoughCostItems(player, costB)) {
			return false;
		}
		return true;
	}

	// 统计背包中与 required 精确匹配（含 NBT）的物品总数是否达到所需数量
	private static boolean hasEnoughCostItems(PlayerEntity player, ItemStack required) {
		int need = required.getCount();
		int have = 0;
		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (stacksMatchExact(stack, required)) {
				have += stack.getCount();
				if (have >= need) {
					return true;
				}
			}
		}
		return false;
	}

	// 精确匹配两个物品栈：同物品且 NBT 完全相等（与 ItemStack.canCombine 的 NBT 语义一致）
	private static boolean stacksMatchExact(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty()) {
			return false;
		}
		if (!a.isOf(b.getItem())) {
			return false;
		}
		NbtCompound tagA = a.getNbt();
		NbtCompound tagB = b.getNbt();
		if (tagA == null && tagB == null) {
			return true;
		}
		if (tagA == null || tagB == null) {
			return false;
		}
		return tagA.equals(tagB);
	}

	/**
	 * 交易画面处理：在一次调用中处理当前交易画面的全部可执行交易项。 流程：清理槽 2 残留 → 检查交易数据 → 扫描可执行交易 → 按 pass 轮转执行
	 * → 移出槽 0/1 剩余成本 → 根据结果设置背包阻塞状态并结束本次画面处理。 每个 pass
	 * 中每个交易项最多点击一次；结果滞留、容量不足或交易项耗尽都会移除或结束相应流程。
	 *
	 * @return true 表示画面数据未就绪（下个 tick 继续），false 表示会话结束（调用者应关闭画面）
	 */
	public boolean handleMerchantScreenTick(MinecraftClient mc, MerchantScreen screen) {
		// 重置会话状态（executor 跨会话复用，避免上一会话的 blocked 泄漏）
		inventoryBlocked = false;
		// 玩家或世界为空（如退出世界/传送中）时无法继续交易，等待下个 tick
		if (mc.player == null || mc.world == null) {
			return true;
		}

		MerchantScreenHandler handler = screen.getScreenHandler();
		TradeOfferList offers = handler.getRecipes();

		// 第 1 步：开窗残留清理（必须在任何 switchTo 之前，防御性兜底；失败 → 阻塞并结束会话，
		// 下轮会话重开时重试清理）
		if (!cleanupResidualResult(mc, handler)) {
			return endSession(0);
		}

		// 第 2 步：offers/pairs 空检查（沿用现有语义）
		List<TradePair> pairs = TradePairCache.getAll();
		// pairs 预解码缓存：配置串引用变化才重建数组（每 tick 复用，免 offers×pairs 循环内 Gson 解析）
		String pairsJson = Configs.Generic.TRADE_PAIRS.getStringValue();
		if (pairsJson != cachedPairsJson || cachedParsedPairs == null) {
			cachedPairsJson = pairsJson;
			cachedParsedPairs = buildParsedPairs(pairs);
		}

		if (offers == null || offers.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] Offers not yet synced, waiting...");
			return true;
		}

		if (pairs.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] No trade pairs configured");
			return endSession(0);
		}

		// 第 3 步：预扫描可执行交易项（开屏 isDisabled() 过滤已耗尽的 offer；饿死候选标志随扫描携带）。
		// 列表为每 tick 快照——成本消耗导致的过期由单轮结果确认兜底（轮到时 autofill 无货 → 槽 2 空 → DONE 移出）
		List<OfferState> active = scanExecutableOffers(handler, cachedParsedPairs, mc.player);
		if (active.isEmpty()) {
			// 无任何可执行交易项：移出输入成本后结束会话（下轮会话重试）
			moveOutInputCosts(mc, handler);
			return endSession(0);
		}

		// 第 4 步：公平轮转 pass 循环——每 pass 遍历 active 中每个 offer 至多 1 次点击
		// （runOneBatch 单轮体），本 pass 成交 ≥1 笔则进入下一 pass，直到无成交/全部移出/STUCK；
		// blocked 为 STUCK/post-loop 的局部汇总，由 runPassLoop 汇总返回（见第 7 步统一写入字段）
		PassResult passResult = runPassLoop(mc, handler, active);
		int tradesTotal = passResult.tradesTotal();
		int capacitySkips = passResult.capacitySkips();
		boolean blocked = passResult.blocked();

		// 第 5 步：输入移出——仅非 STUCK 路径执行（STUCK 时跳过：槽 2 滞留物是「未执行交易的预览」、
		// 无真实物品风险，成本由 onClosed offerOrDrop 回收；跳过 moveOut 仅为省去背包满时注定失败的
		// QUICK_MOVE 包）。不得提前 return false——否则第 7 步的 inventoryBlocked 置位不会执行；
		// moveOutInputCosts 内部「移出失败 → inventoryBlocked=true」的兜底写入保留（成本放不回背包 = 背包满）
		if (!blocked) {
			moveOutInputCosts(mc, handler);
		}

		// 第 6 步：post-loop——全部 offer 均因容量不足/整批放不下跳过且 0 笔交易 → 阻塞，等容器 IO 释放空间后下轮恢复
		if (!blocked && capacitySkips > 0 && tradesTotal == 0) {
			blocked = true;
		}

		// 第 7 步：收尾——只置位、不清除（第 5 步 moveOutInputCosts 内部已写入 true，此处不覆盖；
		// 方法顶部已重置过，字段在方法内只会 false→true）。会话收尾日志：tradesTotal/capacitySkips/blocked
		// 该日志用于记录本次处理的汇总结果。
		if (blocked) {
			inventoryBlocked = true;
		}
		AutoTrade.logger.info("[AutoTrade] 会话收尾: tradesTotal={} capacitySkips={} blocked={}", tradesTotal,
				capacitySkips, blocked);
		return endSession(tradesTotal);
	}

	/** 会话结束统一出口：记录会话成交数（含 0 笔会话，保证 HUD 会话计数不显示过期值）并返回 false */
	private boolean endSession(int trades) {
		TradeStats.getInstance().recordSession(trades);
		return false;
	}

	// pass 循环汇总结果 record：tradesTotal = 跨 pass 累计实际成交笔数，capacitySkips = 容量跳过计数，
	// blocked = STUCK/post-loop 的局部汇总（由调用方统一写入 inventoryBlocked 字段）
	private record PassResult(int tradesTotal, int capacitySkips, boolean blocked) {
	}

	// 公平轮转 pass 循环——每 pass 遍历 active 中每个 offer 至多 1 次点击（runOneBatch 单轮体），
	// 本 pass 成交 ≥1 笔则进入下一 pass，直到无成交/全部移出/STUCK。汇总
	// tradesTotal/capacitySkips/blocked 返回。
	private PassResult runPassLoop(MinecraftClient mc, MerchantScreenHandler handler, List<OfferState> active) {
		int tradesTotal = 0;
		int capacitySkips = 0;
		boolean blocked = false;
		// pass 轮次序号：从 1 开始，每 pass 递增。
		int pass = 0;
		while (true) {
			pass++;
			// 每 pass 起始：本 pass 是否成交（防御——无成交即终止轮转，配合出口移出保证 pass 数有界）
			boolean anyTradedThisPass = false;
			// 本 pass 实际成交（tradesDone>0）的 TRADED offer 数——「轮次结束」日志统计用，
			// 防御规则移出的 0 笔 TRADED 不计入（纯日志统计，不参与任何判定）
			int tradedOffersThisPass = 0;
			Iterator<OfferState> it = active.iterator();
			while (it.hasNext()) {
				OfferState target = it.next();
				BatchOutcome o = runOneBatch(mc, handler, target);
				// 跨 pass 累计实际完成交易笔数（单轮成交值）
				tradesTotal += o.tradesDone();
				// 同步累计到 target（record 记账）——供剩余次数推导（remaining = initialRemaining − tradesDone）
				target.record(o.tradesDone());
				switch (o.result()) {
					case TRADED :
						// 已成交且无滞留：保留在 active 由下 pass 继续（exhausted=true → 移出，剩余次数耗尽时置位）；
						// 防御规则：成交 0 笔且槽 2 空（无进展路径）→ 按 DONE 移出
						anyTradedThisPass = true;
						if (o.tradesDone() > 0) {
							// 仅实际成交的 TRADED 计入轮次日志，不参与交易判定。
							tradedOffersThisPass++;
						}
						if (o.exhausted() || (o.tradesDone() == 0 && !handler.getSlot(2).hasStack())) {
							it.remove();
						}
						break;
					case DONE :
						// 耗尽/没货/装填失败：移出，本会话不再尝试（等价于现 DONE break 后外层 for 不回头）
						it.remove();
						break;
					case CAPACITY_SKIP :
					case STOP :
						// 容量不足/整批放不下：移出，本会话不再尝试（等价于现 break 后外层 for 不回头）；
						// 全部移出且 0 笔交易才阻塞，见第 6 步
						capacitySkips++;
						it.remove();
						break;
					case STUCK :
						// 点击后结果滞留槽 2（背包真满）→ 置 blocked 终止整个 pass 与会话；不得提前
						// return——blocked 由调用方统一写入 inventoryBlocked（否则字段不会被置位）
						blocked = true;
						break;
				}
				if (blocked) {
					// STUCK：终止整个 pass（active 余项本会话不再尝试）
					break;
				}
			}
			// 每 pass 结束时记录：pass 从 1 严格递增、tradedOffers 为本 pass
			// 实际成交 TRADED 数、tradesTotal 跨 pass 累计、active.size() 本 pass 移出后的剩余 offer 数
			AutoTrade.logger.info("[AutoTrade] 轮次结束: pass={} tradedOffers={} tradesTotal={} active={}", pass,
					tradedOffersThisPass, tradesTotal, active.size());
			// 每 pass 出口：STUCK / 本 pass 无成交 / active 已空 → 终止轮转
			if (blocked || !anyTradedThisPass || active.isEmpty()) {
				break;
			}
		}
		return new PassResult(tradesTotal, capacitySkips, blocked);
	}

	// 开窗残留清理：槽 2 若有滞留物先 QUICK_MOVE 移出（必须在任何 switchTo 之前）。
	// 槽 2 滞留物表示结果尚未成功移入背包；本步先尝试 QUICK_MOVE，仍滞留则判定背包无空间。
	// 这种情况下标记阻塞，移出输入成本后结束本次处理，下一次打开交易界面时再次清理。
	// @return true = 槽 2 已清空，false = 滞留无法清除（会话应结束）
	private boolean cleanupResidualResult(MinecraftClient mc, MerchantScreenHandler handler) {
		Slot slot2 = handler.getSlot(2);
		if (slot2.hasStack()) {
			AutoTrade.logger.info("[AutoTrade] 清理遗留交易结果: {}x{}", slot2.getStack().getCount(),
					Registries.ITEM.getId(slot2.getStack().getItem()));
			quickMoveSlot(mc, handler, slot2);
			// 本地模拟同步执行（clickSlot 本地模拟），点击返回后本地槽状态已更新；仍滞留 = 背包无空间
			if (slot2.hasStack()) {
				AutoTrade.logger.info("[AutoTrade] 遗留交易结果无法移入背包（背包已满）");
				inventoryBlocked = true;
				moveOutInputCosts(mc, handler);
				return false;
			}
		}
		return true;
	}

	// 输入移出：槽 0/1 剩余成本 QUICK_MOVE 回背包；各自移出失败 → 置阻塞标志（关窗 offerOrDrop 兜底）
	private void moveOutInputCosts(MinecraftClient mc, MerchantScreenHandler handler) {
		moveOutInputSlot(mc, handler, 0);
		moveOutInputSlot(mc, handler, 1);
	}

	// 移出单个输入槽（槽 slotIndex）的剩余成本：QUICK_MOVE 回背包；移出失败（槽内仍有物品）→ 打日志 +
	// 置 inventoryBlocked=true（背包满，关窗 offerOrDrop 兜底）。@return true = 移出成功（或槽本就为空）
	private boolean moveOutInputSlot(MinecraftClient mc, MerchantScreenHandler handler, int slotIndex) {
		Slot slot = handler.getSlot(slotIndex);
		if (slot.hasStack()) {
			quickMoveSlot(mc, handler, slot);
			if (slot.hasStack()) {
				AutoTrade.logger.info("[AutoTrade] 成本物品无法移回背包，标记背包阻塞");
				inventoryBlocked = true;
				return false;
			}
		}
		return true;
	}

	// 会话内预解码交易对 record：give/give2/get 为预解析物品（give2 空串 → parse 返回 null，与旧
	// costB.isEmpty() 单成本分支语义对齐）；hasGive2 用原始串判空白（与旧 doesOfferMatchPair 分支条件逐字
	// 一致——非法 give2 串仍走双成本分支并匹配失败，不能以 parse 结果判分支）；enabled/limit 供扫描循环与
	// 价格限制直接使用
	private record ParsedPair(ItemStringHelper.ParsedItem give, ItemStringHelper.ParsedItem give2,
			ItemStringHelper.ParsedItem get, boolean hasGive2, boolean enabled, int limit) {
	}

	// 构建交易对预解码数组：每个 pair 的 give/give2/get 各调 ItemStringHelper.parse 一次（循环外一次性解析）
	private static ParsedPair[] buildParsedPairs(List<TradePair> pairs) {
		ParsedPair[] arr = new ParsedPair[pairs.size()];
		for (int i = 0; i < pairs.size(); i++) {
			TradePair pair = pairs.get(i);
			String give2 = pair.getGiveItem2();
			arr[i] = new ParsedPair(ItemStringHelper.parse(pair.getGiveItem()), ItemStringHelper.parse(give2),
					ItemStringHelper.parse(pair.getGetItem()), give2 != null && !give2.isBlank(), pair.isEnabled(),
					pair.getLimit());
		}
		return arr;
	}

	// 预扫描：收集未耗尽、匹配交易对、价格达标且背包有成本的交易项。
	// 结果是每 tick 的快照；后续成本变化由轮到该交易项时的槽 2 状态再次确认。
	// 兼容层：ItemScroller 收藏重排下取真实列表，索引即真实索引
	List<OfferState> scanExecutableOffers(MerchantScreenHandler handler, ParsedPair[] parsedPairs,
			PlayerEntity player) {
		List<OfferState> list = new ArrayList<>();
		TradeOfferList offers = ItemScrollerTradeCompat.getOriginalRecipes(handler);
		for (int i = 0; i < offers.size(); i++) {
			TradeOffer candidate = offers.get(i);
			// 扫描发生在任何交易之前（本窗口尚未交易，offers 为开屏时服务端同步真值）→ 此刻 isDisabled() 准确；
			// 开窗即耗尽的 offer 直接跳过（省一次装填尝试）；会话中期耗尽的 offer 在下轮窗口重开时服务端重新同步
			// offers → isDisabled()=true → 同样被本检查过滤（零浪费装填）。会话中期 uses 仍不可靠——内循环终止/
			// 耗尽标记只根据输出槽状态和已同步的交易数据判断。
			if (candidate.isDisabled())
				continue;
			for (int pairIndex = 0; pairIndex < parsedPairs.length; pairIndex++) {
				ParsedPair pair = parsedPairs[pairIndex];
				// 未启用的交易对跳过
				if (!pair.enabled())
					continue;
				if (isOfferExecutableForPair(candidate, pair, pairIndex, player)) {
					// 检查全部通过：记入快照列表（携带饿死候选标志）并结束内层交易对循环。
					// 快照「初始剩余次数」= maxUses − getUses()（非裸 getUses()——裸 uses 对新 offer 为 0，
					// 避免把新交易项的 uses 误当成已使用次数，导致剩余次数被计算为 0。
					// 该读取在 SnapshotOfferState 构造时执行（与上方 isDisabled() 同刻 = 开屏服务端同步真值），
					// 是 OUTPUT_SLOT 策略唯一 allowed 的 uses 读取点（USE 策略经 LiveOfferState 实时读取，见
					// UseBasedExecutorStrategy）；OUTPUT_SLOT
					// 循环内（runOneBatch/pass/exactTradeN/容量函数）不再读取 uses。
					list.add(createOffer(candidate, i, isStarvationCandidate(candidate)));
					break;
				}
			}
		}
		return list;
	}

	// 单 offer 单轮结果枚举：TRADED = 点击已发生且无滞留（保留在 active 下 pass 继续；exhausted=true 时移出）；
	// DONE = 耗尽/没货/装填失败（正常收尾，移出）；
	// CAPACITY_SKIP = 容量不足跳过（无输入或候选 affordable=0，不设 blocked，移出）；
	// STOP = 非候选整批放不下（不 exact-N、不空间封顶兜底，该 offer 本会话不再尝试，移出）；
	// STUCK = 点击后结果滞留槽 2（背包真满，结束会话）
	private enum BatchResult {
		DONE, CAPACITY_SKIP, STOP, STUCK, TRADED
	}

	// 单 offer 单轮结果 record：result = 出口结果，tradesDone = 本轮实际完成交易笔数（供编排层累计
	// tradesTotal；跨 pass 累计由 target.tradesDone 承担），
	// exhausted = 该 offer 剩余次数已耗尽标志（本轮恒 false，仅 TRADED 出口在剩余次数耗尽推导后置位）
	private record BatchOutcome(BatchResult result, int tradesDone, boolean exhausted) {
	}

	// 单轮交易体：对单个 offer 执行一次「装填 + 容量判定 + 点击」，每轮至多 1 次点击。
	// 判定流程：装填 → 槽 2 canCombine(卖品) 否 → DONE → inputBatch 计算（无 uses 项）→
	// 整批可容纳（canFitEffectiveBatch）→ QUICK_MOVE 点击 → TRADED → 否则候选且 affordable ≥ 1 →
	// exact-N（单成本 exactTradeN / 双成本 exactTradeNDual，按成本数分流）→ TRADED → 否则
	// STOP（非候选）/CAPACITY_SKIP（候选 affordable==0）。
	// 点击后滞留检测：槽 2 仍有物品 → STUCK（会话结束）。耗尽检测只信输出槽（槽 2）状态 + 服务端槽同步
	// （≥1 tick）；槽 2 空 → DONE。本轮及后续 pass 不再读取 uses（OUTPUT_SLOT 策略限定；USE 策略经
	// LiveOfferState 实时读取）。
	// exhausted 仅 TRADED 出口置位（剩余次数耗尽推导）；终止性由调用方 pass 循环保证（每 pass 成交 ≥1 或移出
	// ≥1）。
	private BatchOutcome runOneBatch(MinecraftClient mc, MerchantScreenHandler handler, OfferState target) {
		TradeOffer offer = target.offer;
		Slot slot2 = handler.getSlot(2);
		ItemStack result = offer.getSellItem();
		// 出口结果局部变量：默认 DONE（槽 2 校验失败），容量类出口前置 CAPACITY_SKIP/STOP，
		// 点击成功出口 TRADED，滞留出口 STUCK
		BatchResult outcome = BatchResult.DONE;
		// 1) 装填：setRecipeIndex + switchTo + select 包（顺序同现主循环；setRecipeIndex 不可省略，
		// 缺省时非 0 号交易本地结果槽可能不生成）
		refillOffer(mc, handler, target);
		// 2) 槽 2 canCombine(卖品) 校验：失败 → DONE（耗尽/没货/装填失败/switchTo 提前 return
		// 的兜底，均表现为不匹配）。耗尽由扫描时 isDisabled()（开屏服务端同步真值）覆盖：本出口仅结束
		// 该 offer 处理，下轮窗口重开时服务端重新同步 offers → isDisabled() 自然过滤，无需会话级标记集合
		if (!slot2.hasStack() || !ItemStack.canCombine(slot2.getStack(), result)) {
			return new BatchOutcome(outcome, 0, false);
		}
		// 3) inputBatch = min(floor(槽0/costA), floor(槽1/costB))（无 uses 项）；== 0 →
		// CAPACITY_SKIP（无输入）
		int inputBatch = computeInputBatch(handler, offer);
		if (inputBatch <= 0) {
			AutoTrade.logger.info("[AutoTrade] CAPACITY_SKIP offer {}: 无输入（inputBatch=0）", target.index);
			return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
		}
		// 剩余次数推导：remaining = 扫描快照的初始剩余次数 −
		// 本会话已成交笔数（tradesDone 由 pass 循环跨 pass 累计）；不读 offer.getUses()/isDisabled()
		int remaining = target.remaining();
		// 有效整批 = min(整批, 剩余次数)：uses 封顶后本次点击至多交易 remaining 笔
		// need 按有效整批计算，避免把已达到使用上限的次数算入容量需求。
		int effectiveBatch = Math.min(inputBatch, remaining);
		// 防御：有效整批 ≤ 0 → 按 CAPACITY_SKIP 处理（日志注明「剩余次数≤0」；正常流程恒 ≥1——
		// 扫描已过滤 isDisabled，remaining = initialRemaining − tradesDone 推导恒 ≥ 1，此分支仅防异常）
		if (effectiveBatch <= 0) {
			AutoTrade.logger.info("[AutoTrade] CAPACITY_SKIP offer {}: 剩余次数≤0（remaining={}）", target.index, remaining);
			return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
		}
		// 4) 容量判定数据：有效整批所需数量、结果容量、剩余成本占位和剩余次数。
		long need = (long) effectiveBatch * result.getCount();
		int capacity = calculateResultCapacity(handler, result);
		int reservation = calculateLeftoverReservation(handler, offer, effectiveBatch);
		logExecuting(handler, target, result, inputBatch, effectiveBatch, remaining, need, capacity, reservation);
		// 5) 容量判定并执行 QUICK_MOVE、exact-N、CAPACITY_SKIP 或 STOP 分支。
		BatchOutcome batch = decideAndExecuteBatch(mc, handler, target, offer, slot2, result, inputBatch,
				effectiveBatch, need, capacity, reservation);
		// 点击后剩余次数耗尽（推导值：初始剩余 − 已累计成交 − 本轮成交 == 0）→ exhausted=true → pass 循环立即移出
		// （省下 pass 一次无效 switchTo；不读 offer.getUses()）。
		if (batch.result() == BatchResult.TRADED) {
			boolean exhausted = target.exhausted(batch.tradesDone());
			return new BatchOutcome(BatchResult.TRADED, batch.tradesDone(), exhausted);
		}
		return batch;
	}

	// 容量判定 + 执行分支：有效整批可容纳 → QUICK_MOVE；否则候选且 affordable≥1 →
	// exact-N（n = min(affordable, effectiveBatch)；双成本再叠加 D1 槽容量预算 64/costX.count，
	// 按第二成本存在性分流：单成本 exactTradeN / 双成本 exactTradeNDual）；候选 affordable==0 →
	// CAPACITY_SKIP；非候选 → STOP。
	// 点击后统一滞留检测（checkResultStuck，QUICK_MOVE 与 exact-N/双成本路径文案不同均为行为契约）。
	// @return 本轮 BatchOutcome（TRADED/STUCK/CAPACITY_SKIP/STOP；exhausted 由调用方 TRADED
	// 后推导）
	private BatchOutcome decideAndExecuteBatch(MinecraftClient mc, MerchantScreenHandler handler, OfferState target,
			TradeOffer offer, Slot slot2, ItemStack result, int inputBatch, int effectiveBatch, long need, int capacity,
			int reservation) {
		if (canFitEffectiveBatch(handler, offer, effectiveBatch)) {
			// 5) QUICK_MOVE 优先路径：整批可容纳 → 一次点击整批干净耗尽（同 tick 本地增量计数）
			int tradesDone = tradeClick(mc, handler, slot2, result);
			// 6) 滞留检测：点击后槽 2 仍有物品 = 背包满 insertItem 失败 → STUCK（会话结束，
			// 滞留预览由下轮 cleanupResidualResult 续传；输入可能 offerOrDrop 掉地 = 接受）
			if (checkResultStuck(mc, handler, target, slot2, "[AutoTrade] STUCK offer {}: 结果滞留槽 2 {}x{}（背包已满），结束会话")) {
				return new BatchOutcome(BatchResult.STUCK, tradesDone, false);
			}
			// 7) 点击成功且无滞留 → TRADED（原内层循环的「回绕继续」路径映射为单轮出口之一，
			// 保留在 active 由下 pass 重新装填继续）
			return new BatchOutcome(BatchResult.TRADED, tradesDone, false);
		}
		// 整批放不下：候选且 affordable ≥ 1 → exact-N（仅防饿死）；候选 affordable==0 →
		// CAPACITY_SKIP（空间被本批耗尽或初始即满）；非候选 → STOP
		int affordable = capacity / result.getCount();
		if (target.starvationCandidate && affordable >= 1) {
			// 8) exact-N 路径：整批放不下但至少能容纳一笔时，按可用容量交易 n 笔。
			// n = min(affordable, effectiveBatch)，不会超过剩余次数。
			int n = Math.min(affordable, effectiveBatch);
			// 双成本 offer（第二成本存在）：n 受 D1 输入槽容量预算扩展——
			// M1 = n×costA.count 与 M2 = n×costB.count 必须 ≤ 64（槽 0/1 物理上限 min(64, maxCount)，
			// 超限放置时 insertStack 截断 → 光标残留，P0 级）；故 n ≤ 64/costA.count 且 ≤ 64/costB.count。
			if (!offer.getSecondBuyItem().isEmpty()) {
				ItemStack costA = offer.getAdjustedFirstBuyItem();
				ItemStack costB = offer.getSecondBuyItem();
				n = Math.min(n, 64 / costA.getCount());
				n = Math.min(n, 64 / costB.getCount());
				// D1 守卫 1：预算不足（n < 1）→ CAPACITY_SKIP（防御——affordable/effectiveBatch ≥ 1 且
				// costX.count ≤ 64 ⟹ 64/costX.count ≥ 1，正常不可达；不点击、不 fill）
				if (n < 1) {
					AutoTrade.logger.info("[AutoTrade] CAPACITY_SKIP offer {}: 双成本预算不足（n={}）", target.index, n);
					return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
				}
				// ★双成本 exact-N（替代原「双成本守卫 → quickMoveFallback」；本路径永不回退普通 QUICK_MOVE）★
				// 出口语义：TRADED = 恰交易 n 笔；CAPACITY_SKIP = 守卫失败且撤销成功（槽 0/1/光标已恢复、
				// 预览清空 → 不会误判滞留）；STUCK = 撤销失败（背包真满，真异常）
				BatchOutcome dual = exactTradeNDual(mc, handler, target, n, result, slot2);
				// 9) 滞留检测（D5 守卫 7，仅成交路径需要）：成功路径输入 M1/M2 精确耗尽 → 槽 2 应清空；
				// 滞留 = 背包满 insertItem 失败 → STUCK（由下轮 cleanupResidualResult 续传）
				if (dual.result() == BatchResult.TRADED && checkResultStuck(mc, handler, target, slot2,
						"[AutoTrade] STUCK offer {}: 双成本 exact-N 后结果滞留槽 2 {}x{}，结束会话")) {
					return new BatchOutcome(BatchResult.STUCK, dual.tradesDone(), false);
				}
				return dual;
			}
			int tradesDone = exactTradeN(mc, handler, offer, target.index, n, result, slot2);
			// 9) 滞留检测（守卫回退路径可能滞留预览）：有物品 → STUCK（由下轮 cleanupResidualResult 续传）
			if (checkResultStuck(mc, handler, target, slot2,
					"[AutoTrade] STUCK offer {}: exact-N/回退后结果滞留槽 2 {}x{}，结束会话")) {
				return new BatchOutcome(BatchResult.STUCK, tradesDone, false);
			}
			// 10) 点击成功且无滞留 → TRADED（空间被本批耗尽 → 下 pass affordable=0 → CAPACITY_SKIP
			// 自然移出，每会话最多 1 次 exact-N）
			return new BatchOutcome(BatchResult.TRADED, tradesDone, false);
		}
		if (target.starvationCandidate) {
			// 候选但 affordable == 0（空间被本批耗尽或初始即满）→ CAPACITY_SKIP → 移出 → 容器 IO
			AutoTrade.logger.info("[AutoTrade] CAPACITY_SKIP offer {}: 候选但 affordable=0（capacity={} sellCount={}）",
					target.index, capacity, result.getCount());
			return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
		}
		// 11) STOP：非候选整批放不下 → 该 offer 本会话不再尝试（不 exact-N、不空间封顶兜底）→
		// 移出 active → 会话结束 → 输出阈值容器 IO 下轮整批续传
		AutoTrade.logger.info(
				"[AutoTrade] STOP offer {}: 空间满，结束会话待容器 IO（inputBatch={} need={} capacity={} reservation={}）",
				target.index, inputBatch, need, capacity, reservation);
		return new BatchOutcome(BatchResult.STOP, 0, false);
	}

	// 记录执行前的容量判定数据；日志格式由运行时诊断使用，禁止改动。
	private void logExecuting(MerchantScreenHandler handler, OfferState target, ItemStack result, int inputBatch,
			int effectiveBatch, int remaining, long need, int capacity, int reservation) {
		AutoTrade.logger.info(
				"[AutoTrade] EXECUTING trade offer {} result={} inputBatch={} need={} capacity={} reservation={} candidate={} effectiveBatch={} remaining={}",
				target.index, Registries.ITEM.getId(result.getItem()), inputBatch, need, capacity, reservation,
				target.starvationCandidate, effectiveBatch, remaining);
	}

	// 点击后滞留检测：槽 2 仍有物品 = 背包满 insertItem 失败 → STUCK（会话结束，滞留预览由下轮
	// cleanupResidualResult 续传；输入可能 offerOrDrop 掉地 = 接受）。日志文案由调用方按路径传入（
	// QUICK_MOVE 与 exact-N/回退路径文案不同，均为行为契约）。@return true = 滞留（调用方走 STUCK 出口）
	private boolean checkResultStuck(MinecraftClient mc, MerchantScreenHandler handler, OfferState target, Slot slot2,
			String stuckLog) {
		if (slot2.hasStack()) {
			AutoTrade.logger.info(stuckLog, target.index, slot2.getStack().getCount(),
					Registries.ITEM.getId(slot2.getStack().getItem()));
			return true;
		}
		return false;
	}

	// 交易点击：QUICK_MOVE 槽 2，并统计本次点击在背包中新增的结果数量。
	// = 连续交易直到输入耗尽/uses 打满/背包满（空间封顶）；干净退出或滞留预览由调用方判定。
	// 计数：点击前快照槽 3-38 卖品总数（快照时机 = 点击前、exact-N 操纵后 → 成本已移入槽 0/1，
	// cost==sell 不污染）→ 点击后立即再快照 → 差值 / sellCount 累加；无跨会话计数状态。
	// @return 本次点击实际成交笔数
	private int tradeClick(MinecraftClient mc, MerchantScreenHandler handler, Slot slot2, ItemStack result) {
		// 点击前快照（槽 3-38 卖品总数）
		int before = countSellItemsInInventory(handler, result);
		quickMoveSlot(mc, handler, slot2);
		// 点击后立即再快照：差值 / sellCount = 同 tick 本地增量成交笔数
		return (countSellItemsInInventory(handler, result) - before) / result.getCount();
	}

	// exact-N 守卫回退助手：先重新装填交易项（槽 0/1 残余成本放回并
	// autofill 重填、槽 2 预览重建），再空间封顶 QUICK_MOVE 点击——容量 ≥ sellCount → 成交 ≥1（防饿死）；
	// 容量 < sellCount → 结果滞留 → 由调用方 STUCK 出口终止。不能直接点击空槽 2。
	private int quickMoveFallback(MinecraftClient mc, MerchantScreenHandler handler, TradeOffer offer, int offerIndex,
			Slot slot2, ItemStack result) {
		// 可见索引用于 switchTo（内部读 getRecipes() = 重排列表），offerIndex（真实索引）用于发包
		handler.switchTo(ItemScrollerTradeCompat.getVisibleIndex(handler, offerIndex, offer));
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(offerIndex));
		}
		return tradeClick(mc, handler, slot2, result);
	}

	// exact-N 流程（仅单成本交易）：尽量恰好准备 N 笔成本并返回成交笔数（含守卫回退
	// 空间封顶 QUICK_MOVE 路径）。流程：a QUICK_MOVE 槽 0 清空 → b PICKUP 成本源槽（3-38 中与 M 最接近
	// 的堆叠）→ c 右键源槽 (S−M) 次（每次 1 包）→ d 点击槽 0 放置 M → e 槽 2 canCombine 校验 →
	// f 点击槽 2（服务端 while 恰交易 N 笔，输入 M 精确耗尽 → 干净退出）。
	// 守卫（任一命中 → 回退空间封顶 QUICK_MOVE）：S−M > EXACT_N_MAX_RIGHT_CLICKS（每源槽右键预算）；
	// 双成本（★已不可达★——双成本由 decideAndExecuteBatch 分流到 exactTradeNDual（双成本 exact-N，
	// 永不回退），本守卫仅作防御保留，防未来调用路径回归）；a 失败（槽 0 移出后仍有物品）；
	// e 失败（槽 2 不匹配）。
	private int exactTradeN(MinecraftClient mc, MerchantScreenHandler handler, TradeOffer offer, int offerIndex, int n,
			ItemStack result, Slot slot2) {
		// M = N 笔交易所需的第一成本总量
		ItemStack cost = offer.getAdjustedFirstBuyItem();
		int m = n * cost.getCount();
		// 守卫：双成本 offer 不执行 exact-N → 回退空间封顶 QUICK_MOVE（★已不可达★：双成本由
		// decideAndExecuteBatch 改走 exactTradeNDual 不再进入本方法；本守卫仅作防御保留，防未来调用路径回归）
		if (!offer.getSecondBuyItem().isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] exact-N 守卫（双成本）offer {}: 回退空间封顶 QUICK_MOVE", offerIndex);
			return quickMoveFallback(mc, handler, offer, offerIndex, slot2, result);
		}
		Slot slot0 = handler.getSlot(0);
		// a) QUICK_MOVE 槽 0（autofill 整组移回背包）；失败（槽 0 移出后仍有物品）→ 回退空间封顶 QUICK_MOVE
		if (slot0.hasStack()) {
			quickMoveSlot(mc, handler, slot0);
			if (slot0.hasStack()) {
				AutoTrade.logger.info("[AutoTrade] exact-N 守卫（a 失败：槽 0 移出后仍有物品）offer {}: 回退空间封顶 QUICK_MOVE",
						offerIndex);
				return quickMoveFallback(mc, handler, offer, offerIndex, slot2, result);
			}
		}
		// b) 找成本源槽：优先「数量 ≥ M 且 |S−M| 最小」，否则使用数量最大堆叠；无成本堆叠
		// （防御性：输入槽已 autofill 过，正常不可达）→ 回退空间封顶 QUICK_MOVE
		Slot source = selectCostSourceSlot(handler, cost, m);
		if (source == null) {
			AutoTrade.logger.info("[AutoTrade] exact-N 守卫（无成本源堆叠）offer {}: 回退空间封顶 QUICK_MOVE", offerIndex);
			return quickMoveFallback(mc, handler, offer, offerIndex, slot2, result);
		}
		int s = source.getStack().getCount();
		// 守卫：右键包数预算 S−M > EXACT_N_MAX_RIGHT_CLICKS → 回退空间封顶 QUICK_MOVE（仍防饿死）
		if (s - m > EXACT_N_MAX_RIGHT_CLICKS) {
			AutoTrade.logger.info("[AutoTrade] exact-N 守卫（S−M={} > {}）offer {}: 回退空间封顶 QUICK_MOVE", s - m,
					EXACT_N_MAX_RIGHT_CLICKS, offerIndex);
			return quickMoveFallback(mc, handler, offer, offerIndex, slot2, result);
		}
		// c) PICKUP 拿起源堆叠整组 → 右键源槽 (S−M) 次（每次放下 1 包，剩余 S−M 包留在光标）→
		// d) 点击槽 0 放置 M（光标剩余 S−M 包被放回源槽）
		clickSlot(mc, handler, source.id, 0, SlotActionType.PICKUP);
		int rightClicks = Math.max(0, s - m);
		for (int k = 0; k < rightClicks; k++) {
			clickSlot(mc, handler, source.id, 1, SlotActionType.PICKUP);
		}
		clickSlot(mc, handler, slot0.id, 0, SlotActionType.PICKUP);
		// e) 槽 2 canCombine(卖品) 校验：失败（耗尽/错配/装填异常）→ 回退空间封顶 QUICK_MOVE
		if (!slot2.hasStack() || !ItemStack.canCombine(slot2.getStack(), result)) {
			AutoTrade.logger.info("[AutoTrade] exact-N 守卫（e 失败：槽 2 不匹配）offer {}: 回退空间封顶 QUICK_MOVE", offerIndex);
			return quickMoveFallback(mc, handler, offer, offerIndex, slot2, result);
		}
		// f) 点击槽 2（服务端 while 恰交易 N 笔，输入精确耗尽 → 干净退出）
		return tradeClick(mc, handler, slot2, result);
	}

	// 双成本 exact-N 守卫失败的出口映射（D5 守卫 2-5）：先撤销（undoFill），撤销成功 → 槽 0/1/光标均恢复、
	// 预览清空 → CAPACITY_SKIP（该 offer 本会话不再尝试）；撤销失败（背包真满，物品放不回）→ STUCK
	// （真异常，保留现场由关窗 offerOrDrop 兜底）。@return CAPACITY_SKIP / STUCK 的
	// BatchOutcome（tradesDone = 0）
	private BatchOutcome undoOrSkip(MinecraftClient mc, MerchantScreenHandler handler) {
		if (!undoFill(mc, handler)) {
			AutoTrade.logger.info("[AutoTrade] 双成本 exact-N 撤销失败（背包满），按 STUCK 结束会话");
			return new BatchOutcome(BatchResult.STUCK, 0, false);
		}
		return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
	}

	// 双成本 exact-N 流程（计划 D4）：手动精确填满槽 0/1（M1 = n×costA、M2 = n×costB）后 QUICK_MOVE 槽 2，
	// 服务端 while 循环恰交易 n 笔后输入精确耗尽 → 干净退出（F6）——永不回退空间封顶 QUICK_MOVE。
	// 流程：① 槽 0 拆分/补充至 M1 → ② 槽 1 对称至 M2 → ③ 槽 2 canCombine 校验 → ④ QUICK_MOVE 槽 2。
	// 守卫（任一命中 → undoFill 撤销；撤销失败 → STUCK，成功 → CAPACITY_SKIP，见 D5 出口映射）：
	// n<1（D1 预算）；B1/B2 无可用槽或同槽；补充源槽缺失；差值超每槽右键预算（EXACT_N_MAX_RIGHT_CLICKS）；
	// 槽 2 不匹配。点击后滞留 → STUCK（由调用方 checkResultStuck 判定）。
	// 前置：n 已由调用方按 D1 预算封顶（n ≤ 64/costX.count ⟹ M1/M2 ≤ 64，放置不截断）。
	// @return TRADED（tradesDone = 实际成交笔数）/ CAPACITY_SKIP /
	// STUCK（BatchResult/BatchOutcome 语义不变）
	private BatchOutcome exactTradeNDual(MinecraftClient mc, MerchantScreenHandler handler, OfferState target, int n,
			ItemStack result, Slot slot2) {
		TradeOffer offer = target.offer;
		// M1/M2 = n 笔交易所需的两成本总量（n 已按 D1 封顶 → M ≤ 64，槽 0/1 可完整容纳）
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		ItemStack costB = offer.getSecondBuyItem();
		int m1 = n * costA.getCount();
		int m2 = n * costB.getCount();
		// 守卫 1：n < 1（D1 预算不足，防御——调用方已保证 ≥1）→ CAPACITY_SKIP（不点击、不 fill）
		if (n < 1) {
			return new BatchOutcome(BatchResult.CAPACITY_SKIP, 0, false);
		}
		// 前置量：autofill 后槽 0/1 当前数量（switchTo 本地模拟 + 服务端 select 包双重 autofill，F3）
		int s1 = handler.getSlot(0).getStack().getCount();
		int s2 = handler.getSlot(1).getStack().getCount();
		// ① 槽 0：S1 → M1
		// B1 = 拆分/回补用的背包槽（与 costA 可合并且未满，否则空槽）；两者皆无 → 守卫 2 → 撤销
		Slot b1 = selectMergeOrEmptySlot(handler, costA);
		if (b1 == null) {
			return undoOrSkip(mc, handler);
		}
		Slot srcA = null; // 槽 0 补充源槽（仅 S1 < M1 时使用，槽 1 排除时引用）
		if (s1 >= m1) {
			// S1 ≥ M1：二分拆半 S1 → M1（多余回背包槽 B1，光标净空）
			if (!splitSlotExact(mc, handler, 0, m1, b1)) {
				// 守卫 3：拆分失败（内部不变量破坏）→ 撤销
				return undoOrSkip(mc, handler);
			}
		} else {
			// S1 < M1：从背包源槽补充 (M1−S1)（排除 B1——避免破坏已累积的拆分产物）
			srcA = selectCostSourceSlotExcluding(handler, costA, m1 - s1, b1.id);
			if (srcA == null) {
				// 守卫 4a：无可用补充源槽 → 撤销
				return undoOrSkip(mc, handler);
			}
			// 守卫 4b：右键预算——PICKUP 整组后需右键放回 (S−(M1−S1)) 次；> 预算 → 撤销（同单成本 step c）
			int back = srcA.getStack().getCount() - (m1 - s1);
			if (back > EXACT_N_MAX_RIGHT_CLICKS) {
				return undoOrSkip(mc, handler);
			}
			clickSlot(mc, handler, srcA.id, 0, SlotActionType.PICKUP); // 整组上光标
			for (int i = 0; i < back; i++) {
				clickSlot(mc, handler, srcA.id, 1, SlotActionType.PICKUP); // 右键放回 1 个 × back
			}
			clickSlot(mc, handler, 0, 0, SlotActionType.PICKUP); // 光标 (M1−S1) 并入槽 0 → 槽 0 = M1
		}
		// ② 槽 1：S2 → M2（对称；B2 不得与 B1 同槽——避免两路拆分产物互相污染）
		Slot b2 = selectMergeOrEmptySlot(handler, costB);
		if (b2 == null || b2.id == b1.id) {
			// 守卫 2：无可用槽 / 与 B1 冲突 → 撤销
			return undoOrSkip(mc, handler);
		}
		if (s2 >= m2) {
			if (!splitSlotExact(mc, handler, 1, m2, b2)) {
				// 守卫 3（对称）：拆分失败 → 撤销
				return undoOrSkip(mc, handler);
			}
		} else {
			// 补充源槽排除已使用槽：b1、b2、srcA（槽 0 未走补充路径时 srcA 为 null → 传 -1 无匹配）
			Slot srcB = selectCostSourceSlotExcluding(handler, costB, m2 - s2, b1.id, b2.id,
					srcA == null ? -1 : srcA.id);
			if (srcB == null) {
				// 守卫 4a（对称）：无可用补充源槽 → 撤销
				return undoOrSkip(mc, handler);
			}
			// 守卫 4b（对称）：右键预算检查
			int back = srcB.getStack().getCount() - (m2 - s2);
			if (back > EXACT_N_MAX_RIGHT_CLICKS) {
				return undoOrSkip(mc, handler);
			}
			clickSlot(mc, handler, srcB.id, 0, SlotActionType.PICKUP);
			for (int i = 0; i < back; i++) {
				clickSlot(mc, handler, srcB.id, 1, SlotActionType.PICKUP);
			}
			clickSlot(mc, handler, 1, 0, SlotActionType.PICKUP); // 光标 (M2−S2) 并入槽 1 → 槽 1 = M2
		}
		// ③ 槽 2 canCombine 校验：两槽填满后 updateOffers 重新生成预览（F4）；失败 → 守卫 5 → 撤销
		if (!slot2.hasStack() || !ItemStack.canCombine(slot2.getStack(), result)) {
			return undoOrSkip(mc, handler);
		}
		// ④ QUICK_MOVE 槽 2：服务端 while 恰交易 n 笔（M1/M2 精确耗尽 → 干净退出，F6）；
		// 计数沿用现有快照差值法（tradeClick，D8）
		return new BatchOutcome(BatchResult.TRADED, tradeClick(mc, handler, slot2, result), false);
	}

	/** 上次交易扫描是否因背包空间不足而跳过全部匹配交易（会话层据此提前结束并触发容器 IO） */
	public boolean isInventoryBlocked() {
		return inventoryBlocked;
	}

	// 交易项剩余次数/耗尽状态抽象：两策略的差异点（非 use = 快照推导记账；use = 直接读 offer.getUses()）。
	// 状态是策略差异的载体，嵌套于共享流程表达归属（具体实现类嵌套于各自策略）
	abstract static class OfferState {
		final TradeOffer offer;
		final int index;
		final boolean starvationCandidate;

		OfferState(TradeOffer offer, int index, boolean starvationCandidate) {
			this.offer = offer;
			this.index = index;
			this.starvationCandidate = starvationCandidate;
		}

		/**
		 * 当前剩余次数（本次点击前调用；非 use = initialRemaining − tradesDone，use = maxUses −
		 * getUses()）
		 */
		abstract int remaining();

		/** 本轮成交 batchTradesDone 笔后是否耗尽（非 use = 推导，use = uses ≥ maxUses） */
		abstract boolean exhausted(int batchTradesDone);

		/** 跨 pass 累计成交笔数（use 版无需记账，基类空实现） */
		void record(int tradesDone) {
		}
	}
}