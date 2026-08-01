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

		String giveEncoded = ItemStringHelper.encode(costItem);
		String getEncoded = ItemStringHelper.encode(resultItem);
		int limit = Math.max(costItem.getCount(), 1);

		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.addPair(json, giveEncoded, getEncoded, limit));
		Configs.saveToFile();

		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.added_trade_pairs", 1);
	}
}
