package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.event.TradePair;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.github.sebseb7.autotrade.util.TradePairList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;

public class PairListScreen extends GuiBase {
	public PairListScreen() {
		this.title = "autotrade.gui.title.pair_list";
	}

	@Override
	public void initGui() {
		super.initGui();
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		int y = 36;

		ButtonGeneric countLabel = new ButtonGeneric(20, y - 10, 220, 18, "Trade Pairs: " + pairs.size() + " total");
		this.addButton(countLabel, (b, mb) -> {
		});
		y += 14;

		for (int i = 0; i < pairs.size(); i++) {
			TradePair p = pairs.get(i);
			String giveName = ItemStringHelper.getItemId(p.getGiveItem());
			String getName = ItemStringHelper.getItemId(p.getGetItem());
			String status = p.isEnabled() ? "§a[ON]" : "§8[OFF]";
			String label = (i + 1) + ". " + status + " " + giveName + " -> " + getName + " (limit " + p.getLimit()
					+ ")";
			// Truncate long labels
			if (label.length() > 55)
				label = label.substring(0, 52) + "...";
			ButtonGeneric pairLabel = new ButtonGeneric(20, y, 250, 18, label);
			this.addButton(pairLabel, (b, mb) -> {
			});

			final int idx = i;
			int bx = 274;

			ButtonGeneric toggleBtn = new ButtonGeneric(bx, y, 40, 18, p.isEnabled() ? "ON" : "OFF");
			this.addButton(toggleBtn, (b, mb) -> {
				String json = Configs.Generic.TRADE_PAIRS.getStringValue();
				Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.togglePair(json, idx));
				Configs.saveToFile();
				refreshList();
			});

			ButtonGeneric editBtn = new ButtonGeneric(bx + 44, y, 40, 18,
					StringUtils.translate("autotrade.gui.pair_list.edit"));
			this.addButton(editBtn, (b, mb) -> GuiBase.openGui(new PairEditScreen(idx)));

			ButtonGeneric removeBtn = new ButtonGeneric(bx + 88, y, 50, 18,
					StringUtils.translate("autotrade.gui.pair_list.remove"));
			this.addButton(removeBtn, (b, mb) -> {
				String json = Configs.Generic.TRADE_PAIRS.getStringValue();
				Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.removePair(json, idx));
				Configs.saveToFile();
				refreshList();
			});
			y += 22;
		}

		ButtonGeneric backBtn = new ButtonGeneric(20, y, 80, 20, StringUtils.translate("autotrade.gui.pair_list.back"));
		this.addButton(backBtn, (b, mb) -> {
			net.minecraft.client.MinecraftClient.getInstance().setScreen(new GuiConfigs());
		});
	}

	private void refreshList() {
		net.minecraft.client.MinecraftClient.getInstance().setScreen(this);
	}
}
