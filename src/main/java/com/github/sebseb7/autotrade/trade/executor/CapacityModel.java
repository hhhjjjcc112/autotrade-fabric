package com.github.sebseb7.autotrade.trade.executor;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradeOffer;

/**
 * 容量数学单点（纯函数，全 static，无实例字段）：从 AbstractTradeStrategy 容量方法区整体平移而来， 方法体零改动。未来供
 * {@code .omo/evidence/capacity_model_sim.py} 直接引用（docs/TASKS.md I-9）。
 */
final class CapacityModel {
	private CapacityModel() {
	}

	// 计算装填完成后背包对交易结果的可用容量；此时槽 0/1 中的装填成本不计入背包容量。
	static int calculateResultCapacity(MerchantScreenHandler handler, ItemStack result) {
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
	static int computeInputBatch(MerchantScreenHandler handler, TradeOffer offer) {
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
	static int calculateLeftoverReservation(MerchantScreenHandler handler, TradeOffer offer, int trades) {
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
	static int costMergeSpace(MerchantScreenHandler handler, ItemStack cost) {
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
	static boolean canFitEffectiveBatch(MerchantScreenHandler handler, TradeOffer offer, int effectiveBatch) {
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
	static boolean isStarvationCandidate(TradeOffer offer) {
		// autofill 单输入槽最大填充量对应的整批笔数：可堆叠 = maxCount/costCount（珍珠等 16、绿宝石 64），
		// 不可堆叠（maxCount=1）→ 1 笔；36 × resultMaxCount = 空背包理论最大容量（槽 3-38 共 36 槽）
		int costCount = offer.getAdjustedFirstBuyItem().getCount();
		int costMaxCount = offer.getAdjustedFirstBuyItem().getMaxCount();
		int autofillBatch = costMaxCount > 1 ? costMaxCount / costCount : 1;
		int sellCount = offer.getSellItem().getCount();
		int resultMaxCount = offer.getSellItem().getMaxCount();
		return autofillBatch * sellCount > 36 * resultMaxCount;
	}
}
