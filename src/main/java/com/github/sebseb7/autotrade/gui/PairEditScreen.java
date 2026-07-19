package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.event.TradePair;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.github.sebseb7.autotrade.util.TradePairList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

public class PairEditScreen extends GuiBase {
	private final int pairIndex;
	private TradePair currentPair;

	public PairEditScreen(int pairIndex) {
		this.pairIndex = pairIndex;
		this.title = "autotrade.gui.title.pair_edit";
	}

	@Override
	public void initGui() {
		super.initGui();
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		if (pairIndex < 0 || pairIndex >= pairs.size()) {
			ButtonGeneric back = new ButtonGeneric(20, 40, 80, 20,
					StringUtils.translate("autotrade.gui.pair_edit.back"));
			this.addButton(back, (b, mb) -> closeGui(true));
			return;
		}
		currentPair = pairs.get(pairIndex);
		int y = 36;

		ButtonGeneric titleBtn = new ButtonGeneric(20, y, 200, 18, "Pair #" + (pairIndex + 1));
		this.addButton(titleBtn, (b, mb) -> {
		});
		y += 18;

		ButtonGeneric giveBtn = new ButtonGeneric(20, y, 250, 18,
				String.format(StringUtils.translate("autotrade.gui.pair_edit.give"),
						ItemStringHelper.getItemId(currentPair.getGiveItem())));
		this.addButton(giveBtn, (b, mb) -> {
		});
		ButtonGeneric grabGive = new ButtonGeneric(274, y - 1, 60, 18,
				StringUtils.translate("autotrade.gui.pair_edit.grab"));
		this.addButton(grabGive, (b, mb) -> grabItem(true));
		y += 18;

		ButtonGeneric getBtn = new ButtonGeneric(20, y, 250, 18,
				String.format(StringUtils.translate("autotrade.gui.pair_edit.get"),
						ItemStringHelper.getItemId(currentPair.getGetItem())));
		this.addButton(getBtn, (b, mb) -> {
		});
		ButtonGeneric grabGet = new ButtonGeneric(274, y - 1, 60, 18,
				StringUtils.translate("autotrade.gui.pair_edit.grab"));
		this.addButton(grabGet, (b, mb) -> grabItem(false));
		y += 18;

		ButtonGeneric limitBtn = new ButtonGeneric(20, y, 150, 18,
				String.format(StringUtils.translate("autotrade.gui.pair_edit.limit"), currentPair.getLimit()));
		final int currentLimit = currentPair.getLimit();
		this.addButton(limitBtn, (b, mb) -> {
			int step = (mb == 0) ? 1 : -1;
			int nl = Math.max(1, Math.min(64, currentLimit + step));
			saveAndRefresh(nl);
		});
		y += 20;

		ButtonGeneric setInput = new ButtonGeneric(20, y, 120, 18,
				StringUtils.translate("autotrade.gui.pair_edit.set_input"));
		this.addButton(setInput, (b, mb) -> setContainerPos(true));
		ButtonGeneric setOutput = new ButtonGeneric(144, y, 120, 18,
				StringUtils.translate("autotrade.gui.pair_edit.set_output"));
		this.addButton(setOutput, (b, mb) -> setContainerPos(false));
		y += 22;

		ButtonGeneric save = new ButtonGeneric(20, y, 60, 20, StringUtils.translate("autotrade.gui.pair_edit.save"));
		this.addButton(save, (b, mb) -> saveAndRefresh(currentPair.getLimit()));
		ButtonGeneric back = new ButtonGeneric(84, y, 60, 20, StringUtils.translate("autotrade.gui.pair_edit.back"));
		this.addButton(back, (b, mb) -> closeGui(true));
	}

	private void setContainerPos(boolean isInput) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null)
			return;
		HitResult hit = mc.player.raycast(20.0D, 0.0F, false);
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockHitResult blockHit = (BlockHitResult) hit;
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO,
					isInput ? "autotrade.message.input_container_set" : "autotrade.message.output_container_set",
					blockHit.getBlockPos().getX(), blockHit.getBlockPos().getY(), blockHit.getBlockPos().getZ());
		}
	}

	private void grabItem(boolean isGive) {
		ItemStack held = MinecraftClient.getInstance().player.getMainHandStack();
		if (held.isEmpty() || currentPair == null)
			return;
		String encoded = ItemStringHelper.encode(held);
		String give = isGive ? encoded : currentPair.getGiveItem();
		String get = isGive ? currentPair.getGetItem() : encoded;
		saveFields(give, get, currentPair.getLimit());
	}

	private void saveAndRefresh(int limit) {
		if (currentPair == null)
			return;
		saveFields(currentPair.getGiveItem(), currentPair.getGetItem(), limit);
	}

	private void saveFields(String give, String get, int limit) {
		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.updatePair(json, pairIndex, give, get, limit));
		Configs.saveToFile();
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.pair_saved", pairIndex + 1);
		// Refresh the current screen without recreating it, to avoid mouse centering
		this.initGui();
	}
}
