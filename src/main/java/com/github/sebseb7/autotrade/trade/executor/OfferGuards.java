package com.github.sebseb7.autotrade.trade.executor;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.trade.executor.AbstractTradeStrategy.ParsedPair;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.village.TradeOffer;

/**
 * Offer 匹配/成本判定守卫（纯函数，全 static，无实例字段）：从 AbstractTradeStrategy 匹配/守卫方法区整体平移，
 * 方法体零改动（仅位置变化）。供扫描循环 isOfferExecutableForPair 与匹配判定 doesOfferMatchPair 使用； 受 ⛔
 * 禁止规则约束的 batch/点击流程仍留在基类，本类仅承载纯守卫判定。
 */
final class OfferGuards {
	private OfferGuards() {
	}

	// 判定候选 offer 是否可由该交易对执行：匹配（doesOfferMatchPair，失败且 offer 实际有第二成本但交易对
	// 未配置 give2 时打降级警告日志）+ 单笔成本不超 limit + 背包有全部成本。
	// 所有拒绝路径均打诊断日志（不再静默——give2 严格匹配/limit/成本不足的失败此前对用户不可见）。
	// @return true = 可执行（调用方记入快照并结束内层交易对循环）
	static boolean isOfferExecutableForPair(TradeOffer candidate, ParsedPair pair, int pairIndex, PlayerEntity player) {
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

	// 判断交易与交易对是否匹配：成本物品等于 giveItem 且产出物品等于 getItem（预解码版本，循环内零 Gson）
	static boolean doesOfferMatchPair(TradeOffer offer, ParsedPair pair) {
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
	static boolean playerHasMerchantCosts(PlayerEntity player, TradeOffer offer) {
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
	static boolean hasEnoughCostItems(PlayerEntity player, ItemStack required) {
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
	static boolean stacksMatchExact(ItemStack a, ItemStack b) {
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
}
