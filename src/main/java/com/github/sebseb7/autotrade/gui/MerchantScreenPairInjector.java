package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.github.sebseb7.autotrade.util.TradePairList;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * Injects [+Pair] buttons into the MerchantScreen for each trade offer. Called
 * from KeybindCallbacks.onClientTick once offers are synced from server.
 */
public final class MerchantScreenPairInjector {

	private MerchantScreenPairInjector() {
	}

	public static void addPairButtons(MinecraftClient client, MerchantScreen screen) {
		AutoTrade.logger.info("[AutoTrade Debug] ScreenEvents.AFTER_INIT fired for MerchantScreen");
		AutoTrade.logger.info("[AutoTrade Debug] screen.width={} screen.height={}", screen.width, screen.height);

		MerchantScreenHandler handler = screen.getScreenHandler();
		TradeOfferList offers = handler.getRecipes();

		if (offers == null) {
			AutoTrade.logger.warn("[AutoTrade Debug] offers is null - no trades to show");
			return;
		}
		AutoTrade.logger.info("[AutoTrade Debug] offers.size()={}", offers.size());

		// From MerchantScreen source: backgroundWidth=276, backgroundHeight=166.
		// WidgetButtonPage: 88x20 at (guiLeft+5, guiTop+18 + l*20).
		int guiLeft = (screen.width - 276) / 2;
		int guiTop = (screen.height - 166) / 2;
		int btnX = guiLeft + 95; // 5 (button X) + 88 (width) + 2 (gap)
		int btnYStart = guiTop + 18; // TRADE_LIST_AREA_Y_OFFSET(16) + 2

		int addedCount = 0;
		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			if (offer.isDisabled()) {
				continue;
			}

			final int tradeIndex = i;
			int yPos = btnYStart + i * 20; // each button is 20px tall, no gap

			ButtonWidget addBtn = ButtonWidget.builder(Text.literal("+P"), btn -> addTrade(client, handler, tradeIndex))
					.dimensions(btnX, yPos, 30, 18).build();
			Screens.getButtons(screen).add(addBtn);
			addedCount++;
		}
		AutoTrade.logger.info("[AutoTrade Debug] Added {} buttons total", addedCount);
		AutoTrade.logger.info("[AutoTrade Debug] Screens.getButtons(screen).size()={}",
				Screens.getButtons(screen).size());
	}

	private static void addTrade(MinecraftClient client, MerchantScreenHandler handler, int tradeIndex) {
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

		if (client.player != null) {
			client.player.sendMessage(Text.literal("§a[AutoTrade] Added trade: ")
					.append(Text.literal(ItemStringHelper.getItemId(giveEncoded))).append(Text.literal(" -> "))
					.append(Text.literal(ItemStringHelper.getItemId(getEncoded))), false);
		}
	}
}
