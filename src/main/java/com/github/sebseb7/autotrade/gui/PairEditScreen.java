package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.ConfigItem;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradePairList;
import com.github.sebseb7.autotrade.gui.widget.ItemIconWidget;
import com.github.sebseb7.autotrade.trade.TradePair;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class PairEditScreen extends GuiConfigsBase {
	private final int pairIndex;
	private TradePair currentPair;

	private ConfigItem giveConfig;
	private ConfigItem getConfig;
	private ConfigInteger limitConfig;
	private ConfigBoolean enableInputConfig;
	private ConfigInteger inputXConfig, inputYConfig, inputZConfig;
	private ConfigBoolean enableOutputConfig;
	private ConfigInteger outputXConfig, outputYConfig, outputZConfig;
	private ConfigInteger inputThresholdConfig, inputTakeAmountConfig, outputThresholdConfig;
	private ConfigString noteConfig;

	public PairEditScreen(int pairIndex) {
		super(10, 50, "autotrade", null, "autotrade.gui.title.pair_edit");
		this.pairIndex = pairIndex;
	}

	@Override
	protected int getBrowserWidth() {
		return this.width * 8 / 10;
	}

	@Override
	protected int getConfigWidth() {
		return Math.max(200, getBrowserWidth() * 2 / 5);
	}

	@Override
	public void initGui() {
		this.setListPosition(this.width / 10, this.getListY());
		this.reCreateListWidget();
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		if (pairIndex < 0 || pairIndex >= pairs.size()) {
			super.initGui();
			ButtonGeneric back = new ButtonGeneric(this.width / 10, 40, 80, 20,
					StringUtils.translate("autotrade.gui.pair_edit.back"));
			this.addButton(back, (b, mb) -> closeGui(true));
			return;
		}
		currentPair = pairs.get(pairIndex);

		giveConfig = new ConfigItem("paireditGive", currentPair.getGiveItem(),
				"Click [Grab Give] to use the item in hand");
		getConfig = new ConfigItem("paireditGet", currentPair.getGetItem(), "Click [Grab Get] to use the item in hand");
		limitConfig = new ConfigInteger("paireditLimit", currentPair.getLimit(), 1, 64,
				"Max give items consumed per trade");
		enableInputConfig = new ConfigBoolean("paireditEnableInput", currentPair.isInputEnabled(),
				"Auto restock give items from input container");
		inputXConfig = new ConfigInteger("paireditInputX", currentPair.getInputX(), -30000000, 30000000,
				"Input container X coordinate");
		inputYConfig = new ConfigInteger("paireditInputY", currentPair.getInputY(), -30000000, 30000000,
				"Input container Y coordinate");
		inputZConfig = new ConfigInteger("paireditInputZ", currentPair.getInputZ(), -30000000, 30000000,
				"Input container Z coordinate");
		enableOutputConfig = new ConfigBoolean("paireditEnableOutput", currentPair.isOutputEnabled(),
				"Auto deposit products to output container");
		outputXConfig = new ConfigInteger("paireditOutputX", currentPair.getOutputX(), -30000000, 30000000,
				"Output container X coordinate");
		outputYConfig = new ConfigInteger("paireditOutputY", currentPair.getOutputY(), -30000000, 30000000,
				"Output container Y coordinate");
		outputZConfig = new ConfigInteger("paireditOutputZ", currentPair.getOutputZ(), -30000000, 30000000,
				"Output container Z coordinate");
		inputThresholdConfig = new ConfigInteger("paireditInputThreshold", currentPair.getInputThreshold(), 1, 2304,
				"Restock when give items drop below this threshold");
		inputTakeAmountConfig = new ConfigInteger("paireditInputTake", currentPair.getInputTakeAmount(), 1, 2304,
				"Items to take from input container per restock");
		outputThresholdConfig = new ConfigInteger("paireditOutputThreshold", currentPair.getOutputThreshold(), 1, 2304,
				"Trigger output when products reach this amount");
		noteConfig = new ConfigString("paireditNote", currentPair.getNote() != null ? currentPair.getNote() : "",
				"Notes");

		attachAutoSave(giveConfig);
		attachAutoSave(getConfig);
		attachAutoSave(limitConfig);
		attachAutoSave(enableInputConfig);
		attachAutoSave(inputXConfig);
		attachAutoSave(inputYConfig);
		attachAutoSave(inputZConfig);
		attachAutoSave(enableOutputConfig);
		attachAutoSave(outputXConfig);
		attachAutoSave(outputYConfig);
		attachAutoSave(outputZConfig);
		attachAutoSave(inputThresholdConfig);
		attachAutoSave(inputTakeAmountConfig);
		attachAutoSave(outputThresholdConfig);
		attachAutoSave(noteConfig);

		super.initGui();
		String statusKey = currentPair.isEnabled()
				? "autotrade.gui.pair_edit.status_on"
				: "autotrade.gui.pair_edit.status_off";
		this.setTitle(StringUtils.translate("autotrade.gui.pair_edit.pair_title", pairIndex + 1,
				StringUtils.translate(statusKey)));

		int grabX = this.width / 10;
		// 计算按钮宽度：4 个按钮 + 3 个 4px 间隔，保证不超出 80% 浏览器区域
		int gap = 4;
		int btnWidth = Math.min(75, (this.getBrowserWidth() - 3 * gap) / 4);
		ButtonGeneric grabGive = new ButtonGeneric(grabX, 26, btnWidth, 20,
				StringUtils.translate("autotrade.gui.pair_edit.grab_give"));
		this.addButton(grabGive, (b, mb) -> grabItem(true));
		ButtonGeneric grabGet = new ButtonGeneric(grabX + btnWidth + gap, 26, btnWidth, 20,
				StringUtils.translate("autotrade.gui.pair_edit.grab_get"));
		this.addButton(grabGet, (b, mb) -> grabItem(false));
		ButtonGeneric grabInput = new ButtonGeneric(grabX + 2 * (btnWidth + gap), 26, btnWidth, 20,
				StringUtils.translate("autotrade.gui.pair_edit.grab_input"));
		this.addButton(grabInput, (b, mb) -> grabContainer(true));
		ButtonGeneric grabOutput = new ButtonGeneric(grabX + 3 * (btnWidth + gap), 26, btnWidth, 20,
				StringUtils.translate("autotrade.gui.pair_edit.grab_output"));
		this.addButton(grabOutput, (b, mb) -> grabContainer(false));

		ButtonGeneric backBtn = new ButtonGeneric(this.width / 10, this.height - 30, 60, 20,
				StringUtils.translate("autotrade.gui.pair_edit.back"));
		this.addButton(backBtn, (b, mb) -> GuiBase.openGui(new PairListScreen()));
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		if (giveConfig == null)
			return ImmutableList.of();
		return ConfigOptionWrapper.createFor(ImmutableList.of(giveConfig, getConfig, noteConfig, limitConfig,
				enableInputConfig, inputXConfig, inputYConfig, inputZConfig, enableOutputConfig, outputXConfig,
				outputYConfig, outputZConfig, inputThresholdConfig, inputTakeAmountConfig, outputThresholdConfig));
	}

	@Override
	protected WidgetListConfigOptions createListWidget(int listX, int listY) {
		return new CustomWidgetListConfigOptions(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
				this.getConfigWidth(), 0.f, this.useKeybindSearch(), this);
	}

	private <T extends IConfigValue> void attachAutoSave(T config) {
		if (config instanceof fi.dy.masa.malilib.config.options.ConfigBase<?> base) {
			base.setValueChangeCallback(c -> applyAndSave());
		}
	}

	private void applyAndSave() {
		if (currentPair == null)
			return;
		if (this.getListWidget() != null) {
			this.getListWidget().applyPendingModifications();
		}
		syncFromConfigs();
		saveCurrentPair();
		this.initGui();
	}

	private void syncFromConfigs() {
		currentPair.setGiveItem(giveConfig.getStringValue());
		currentPair.setGetItem(getConfig.getStringValue());
		currentPair.setLimit(limitConfig.getIntegerValue());
		currentPair.setInputEnabled(enableInputConfig.getBooleanValue());
		currentPair.setInputX(inputXConfig.getIntegerValue());
		currentPair.setInputY(inputYConfig.getIntegerValue());
		currentPair.setInputZ(inputZConfig.getIntegerValue());
		currentPair.setOutputEnabled(enableOutputConfig.getBooleanValue());
		currentPair.setOutputX(outputXConfig.getIntegerValue());
		currentPair.setOutputY(outputYConfig.getIntegerValue());
		currentPair.setOutputZ(outputZConfig.getIntegerValue());
		currentPair.setInputThreshold(inputThresholdConfig.getIntegerValue());
		currentPair.setInputTakeAmount(inputTakeAmountConfig.getIntegerValue());
		currentPair.setOutputThreshold(outputThresholdConfig.getIntegerValue());
		currentPair.setNote(noteConfig.getStringValue());
	}

	private void grabItem(boolean isGive) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || currentPair == null)
			return;
		ItemStack held = mc.player.getMainHandStack();
		if (held.isEmpty())
			return;
		String encoded = ItemStringHelper.encode(held);
		String itemId = ItemStringHelper.getItemId(encoded);
		currentPair.setGiveItem(isGive ? encoded : currentPair.getGiveItem());
		currentPair.setGetItem(isGive ? currentPair.getGetItem() : encoded);
		saveCurrentPair();
		initGui();
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS,
				isGive ? "autotrade.message.sell_item_set" : "autotrade.message.buy_item_set", itemId);
	}

	private void grabContainer(boolean isInput) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null)
			return;
		BlockPos pos = mc.player.getBlockPos().down();
		if (isInput) {
			currentPair.setInputX(pos.getX());
			currentPair.setInputY(pos.getY());
			currentPair.setInputZ(pos.getZ());
		} else {
			currentPair.setOutputX(pos.getX());
			currentPair.setOutputY(pos.getY());
			currentPair.setOutputZ(pos.getZ());
		}
		saveCurrentPair();
		initGui();
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS,
				isInput ? "autotrade.message.input_container_set" : "autotrade.message.output_container_set",
				pos.getX(), pos.getY(), pos.getZ());
	}

	private void saveCurrentPair() {
		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.updatePair(json, pairIndex, currentPair));
		Configs.saveToFile();
	}

	private static class CustomWidgetListConfigOptions extends WidgetListConfigOptions {
		public CustomWidgetListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
				boolean useKeybindSearch, GuiConfigsBase parent) {
			super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
		}

		@Override
		protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
				ConfigOptionWrapper wrapper) {
			return new CustomWidgetConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
					this.maxLabelWidth, this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
		}
	}

	private static class CustomWidgetConfigOption extends WidgetConfigOption {
		private final PairEditScreen screen;
		private ItemStack hoverIconStack;
		private int iconX, iconY;

		public CustomWidgetConfigOption(int x, int y, int width, int height, int labelWidth, int configWidth,
				ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
				WidgetListConfigOptionsBase<?, ?> parent) {
			super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
			if (host instanceof PairEditScreen) {
				this.screen = (PairEditScreen) host;
			} else {
				this.screen = null;
			}
		}

		@Override
		protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth,
				IConfigBase config) {
			if (config instanceof ConfigItem) {
				String encoded = ((IConfigValue) config).getStringValue();
				ItemStack stack = ItemStringHelper.decode(encoded);
				hoverIconStack = stack;
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
				iconX = x + labelWidth - 10;
				iconY = y + 1;
				if (!stack.isEmpty()) {
					this.addWidget(new ItemIconWidget(iconX, iconY, stack));
				}
			} else {
				hoverIconStack = null;
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			}
		}

		@Override
		public void postRenderHovered(int mouseX, int mouseY, boolean hovered, DrawContext ctx) {
			if (hoverIconStack != null && !hoverIconStack.isEmpty() && ctx != null) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mouseX >= iconX && mouseX <= iconX + 18 && mouseY >= iconY && mouseY <= iconY + 18
						&& mc.player != null && mc.textRenderer != null) {
					List<Text> tooltip = hoverIconStack.getTooltip(mc.player, TooltipContext.Default.ADVANCED);
					ctx.drawTooltip(mc.textRenderer, tooltip, mouseX, mouseY);
				}
			}
			super.postRenderHovered(mouseX, mouseY, hovered, ctx);
		}
	}
}
