package com.github.sebseb7.autotrade.trade.executor;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.compat.itemscroller.ItemScrollerTradeCompat;
import com.github.sebseb7.autotrade.trade.executor.AbstractTradeStrategy.OfferState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 装填/拆单/撤单点击助手（纯函数，全 static，无实例字段）：从 AbstractTradeStrategy 装填/拆分/撤销方法区整体
 * 平移，方法体零改动（仅位置变化）。承载槽位点击（quickMoveSlot/clickSlot）与装填（refillOffer）、
 * 拆分/选择/撤销（splitSlotExact/selectCostSourceSlot/selectMergeOrEmptySlot/undoFill
 * 等）原语，供 batch/点击流程调用；受 ⛔ 禁止规则约束的 batch
 * 机（runOneBatch/exactTradeN/exactTradeNDual 等）仍留在基类。
 */
final class FillHelpers {
	/**
	 * splitSlotExact 阶段一（二分拆半）的硬迭代上限：正常情况拆半次数 ≤ log2(64) ≈ 6 次（输入堆叠最大 64）， 16
	 * 为安全余量。超出该上限即视为不变量破坏（如槽状态异常导致循环不收敛），放弃拆分并返回 false。
	 */
	private static final int SPLIT_SLOT_MAX_HALVINGS = 16;

	private FillHelpers() {
	}

	// 点击指定槽位 QUICK_MOVE，并使用点击后的本地槽位状态继续判断结果。
	static void quickMoveSlot(MinecraftClient mc, MerchantScreenHandler handler, Slot slot) {
		clickSlot(mc, handler, slot.id, 0, SlotActionType.QUICK_MOVE);
	}

	// 点击指定槽位，支持 PICKUP 和 QUICK_MOVE；点击异常只记录日志，不中断当前处理。
	static void clickSlot(MinecraftClient mc, MerchantScreenHandler handler, int slotId, int button,
			SlotActionType type) {
		try {
			mc.interactionManager.clickSlot(handler.syncId, slotId, button, type, mc.player);
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] 槽 {} 点击失败 (button={}, type={})", slotId, button, type, e);
		}
	}

	// 装填指定 offer：setRecipeIndex + switchTo + select 包（顺序同现主循环；setRecipeIndex 不可省略，
	// 缺省时非 0 号交易本地结果槽可能不生成）
	static void refillOffer(MinecraftClient mc, MerchantScreenHandler handler, OfferState target) {
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
	static int countSellItemsInInventory(MerchantScreenHandler handler, ItemStack result) {
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
	static Slot selectCostSourceSlot(MerchantScreenHandler handler, ItemStack cost, int m) {
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
	// 阶段 1 迭代上限 SPLIT_SLOT_MAX_HALVINGS（16，安全余量）：超限 = 不变量破坏，与收尾校验失败同路径
	// （返回 false → 调用方 undoFill + CAPACITY_SKIP）。
	// 收尾（放回法）：溢出 ≤ 右键预算(8) → PICKUP 槽（光标 = cur、槽 = 0）→ 右键 B × overflow（光标每次
	// 放回 1 → 光标 = keep）→ PICKUP 槽（光标 keep 放回 → 槽 = keep ✓）。
	// 阶段 2 回补：取半过头（槽 < keep）→ 每轮 B 取半到光标、右键槽放回 1 个（槽 +1）、左键 B 清光标；
	// 循环上限 = keep − 当前量（每轮至少 +1，有界）。
	// 违反后果：光标残留（B 槽空间不足等极端情况）→ 后续点击语义改变（光标非空时右键 = 放回而非取半）
	// → 返回 false，调用方撤销 + CAPACITY_SKIP（防静默错交易）。
	// @return true = 槽数量精确等于 keep 且光标净空；false = 失败（调用方走 undoFill + CAPACITY_SKIP）
	static boolean splitSlotExact(MinecraftClient mc, MerchantScreenHandler handler, int slotIndex, int keep, Slot b) {
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
		int halvings = 0;
		while (target.getStack().getCount() > keep) {
			// 阶段 1 硬迭代上限护栏：超限 = 不变量破坏（循环不收敛），放弃拆分（调用方撤销 + CAPACITY_SKIP）
			if (++halvings > SPLIT_SLOT_MAX_HALVINGS) {
				AutoTrade.logger.warn("[Executor] 拆半循环超出上限（{} 次），放弃拆分（调用方撤销 + CAPACITY_SKIP）", SPLIT_SLOT_MAX_HALVINGS);
				return false;
			}
			int cur = target.getStack().getCount();
			int overflow = cur - keep;
			// 收尾（放回法）：溢出 ≤ 右键预算 → 放回 finish，返回前校验光标净空 + 槽 = keep
			if (overflow <= AbstractTradeStrategy.EXACT_N_MAX_RIGHT_CLICKS) {
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
	static Slot selectMergeOrEmptySlot(MerchantScreenHandler handler, ItemStack cost) {
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
	static Slot selectCostSourceSlotExcluding(MerchantScreenHandler handler, ItemStack cost, int need,
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
	static boolean undoFill(MinecraftClient mc, MerchantScreenHandler handler) {
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
}
