package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradePairList;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

public final class MerchantScreenPairInjector {

	private MerchantScreenPairInjector() {
	}

	public static void addTrade(MinecraftClient client, MerchantScreenHandler handler, int tradeIndex) {
		TradeOfferList offers = handler.getRecipes();
		if (offers == null || tradeIndex < 0 || tradeIndex >= offers.size())
			return;

		TradeOffer offer = offers.get(tradeIndex);
		if (offer.isDisabled())
			return;

		ItemStack costItem = offer.getAdjustedFirstBuyItem();
		ItemStack resultItem = offer.getSellItem();
		if (costItem.isEmpty() || resultItem.isEmpty())
			return;

		// 读取第二个成本物品（双成本交易）；getSecondBuyItem 返回未调整价格的原始物品
		ItemStack secondCost = offer.getSecondBuyItem();
		String giveEncoded = ItemStringHelper.encode(costItem);
		String getEncoded = ItemStringHelper.encode(resultItem);
		int limit = Math.max(costItem.getCount(), 1);
		// 产出物品每笔交易的数量（用于列表展示 x{getCount}）
		int getCount = resultItem.getCount();

		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		if (!secondCost.isEmpty()) {
			// 双成本交易：将第二成本编码进 give2，保证后续严格匹配能命中该交易；give2Count 记录其每笔数量
			String give2Encoded = ItemStringHelper.encode(secondCost);
			int give2Count = secondCost.getCount();
			Configs.Generic.TRADE_PAIRS.setValueFromString(
					TradePairList.addPair(json, giveEncoded, give2Encoded, getEncoded, limit, give2Count, getCount));
		} else {
			// 单成本交易：无第二成本（give2Count=0），但仍记录产出数量
			Configs.Generic.TRADE_PAIRS
					.setValueFromString(TradePairList.addPair(json, giveEncoded, "", getEncoded, limit, 0, getCount));
		}
		Configs.saveToFile();

		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.added_trade_pairs", 1);
	}
}
