package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.config.options.ConfigItem;
import com.github.sebseb7.autotrade.gui.widget.CustomWidgetListConfigOptions;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairList;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

public class PairEditScreen extends GuiConfigsBase {
	private final int pairIndex;
	private TradePair currentPair;

	private ConfigItem giveConfig;
	private ConfigItem give2Config;
	private ConfigItem getConfig;
	private ConfigInteger limitConfig;
	private ConfigBoolean enableInputConfig;
	private ConfigCoordinate inputPosConfig;
	private ConfigBoolean enableGive2InputConfig;
	private ConfigCoordinate give2InputPosConfig;
	private ConfigBoolean enableOutputConfig;
	private ConfigCoordinate outputPosConfig;
	private ConfigInteger inputThresholdConfig, inputTakeAmountConfig, outputThresholdConfig, give2InputThresholdConfig;
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
		int browserWidth = this.getBrowserWidth();
		int desired = Math.max(200, browserWidth * 2 / 5);
		// 预留空间：标签与控件间距 10 + 控件与重置按钮间距 2 + 重置按钮宽度（RESET 约 40，中文约 28）
		int reserved = 58;
		// 最大标签宽度（与 malilib 内部 maxLabelWidth 计算方式一致）
		int maxLabel = 0;
		for (ConfigOptionWrapper wrapper : this.getConfigs()) {
			if (wrapper.getType() == ConfigOptionWrapper.Type.CONFIG && wrapper.getConfig() != null) {
				maxLabel = Math.max(maxLabel, this.getStringWidth(wrapper.getConfig().getConfigGuiDisplayName()));
			}
		}
		// 收窄值控件宽度，保证「标签 + 值控件 + 重置按钮」不超出浏览器区域（滚动条），任何 GUI 缩放下都不溢出
		return Math.max(120, Math.min(desired, browserWidth - 14 - maxLabel - reserved));
	}

	@Override
	public void initGui() {
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		if (pairIndex < 0 || pairIndex >= pairs.size()) {
			// 无效索引：仅显示返回按钮
			this.setListPosition(this.width / 10, this.getListY());
			this.reCreateListWidget();
			super.initGui();
			ButtonGeneric back = new ButtonGeneric(this.width / 10, 40, 80, 20,
					StringUtils.translate("autotrade.gui.pair_edit.back"));
			this.addButton(back, (b, mb) -> closeGui(true));
			return;
		}
		currentPair = pairs.get(pairIndex);

		giveConfig = new ConfigItem("paireditGive", currentPair.getGiveItem(),
				"Click [Grab Give] to use the item in hand");
		give2Config = new ConfigItem("paireditGive2", currentPair.getGiveItem2(),
				"Click [Grab Give 2] to use the item in hand");
		getConfig = new ConfigItem("paireditGet", currentPair.getGetItem(), "Click [Grab Get] to use the item in hand");
		limitConfig = new ConfigInteger("paireditLimit", currentPair.getLimit(), 1, 64,
				"Max give items consumed per trade");
		enableInputConfig = new ConfigBoolean("paireditEnableInput", currentPair.isInputEnabled(),
				"Auto restock give items from input container");
		// 三个坐标合并为一个空格分隔字符串字段（仅 GUI 展示），JSON 持久化仍为三个 int
		inputPosConfig = new ConfigCoordinate("paireditInputPos",
				formatPos(currentPair.getInputX(), currentPair.getInputY(), currentPair.getInputZ()),
				"Input container position (x y z)");
		enableGive2InputConfig = new ConfigBoolean("paireditEnableGive2Input", currentPair.isGive2InputEnabled(),
				"Auto restock give2 items from its own input container");
		give2InputPosConfig = new ConfigCoordinate("paireditGive2InputPos",
				formatPos(currentPair.getGive2InputX(), currentPair.getGive2InputY(), currentPair.getGive2InputZ()),
				"Give2 input container position (x y z)");
		give2InputThresholdConfig = new ConfigInteger("paireditGive2InputThreshold",
				currentPair.getGive2InputThreshold(), 1, 2304,
				"Restock when give2 items drop below this threshold (in slots)");
		enableOutputConfig = new ConfigBoolean("paireditEnableOutput", currentPair.isOutputEnabled(),
				"Auto deposit products to output container");
		outputPosConfig = new ConfigCoordinate("paireditOutputPos",
				formatPos(currentPair.getOutputX(), currentPair.getOutputY(), currentPair.getOutputZ()),
				"Output container position (x y z)");
		inputThresholdConfig = new ConfigInteger("paireditInputThreshold", currentPair.getInputThreshold(), 1, 2304,
				"Restock when give items drop to this many slots or fewer");
		inputTakeAmountConfig = new ConfigInteger("paireditInputTake", currentPair.getInputTakeAmount(), 1, 2304,
				"Items to take from input container per restock");
		outputThresholdConfig = new ConfigInteger("paireditOutputThreshold", currentPair.getOutputThreshold(), 1, 2304,
				"Trigger output when products occupy this many slots or more");
		noteConfig = new ConfigString("paireditNote", currentPair.getNote() != null ? currentPair.getNote() : "",
				"Notes");

		attachAutoSave(giveConfig);
		attachAutoSave(give2Config);
		attachAutoSave(getConfig);
		attachAutoSave(limitConfig);
		attachAutoSave(enableInputConfig);
		attachAutoSave(inputPosConfig);
		attachAutoSave(enableGive2InputConfig);
		attachAutoSave(give2InputPosConfig);
		attachAutoSave(give2InputThresholdConfig);
		attachAutoSave(enableOutputConfig);
		attachAutoSave(outputPosConfig);
		attachAutoSave(inputThresholdConfig);
		attachAutoSave(inputTakeAmountConfig);
		attachAutoSave(outputThresholdConfig);
		attachAutoSave(noteConfig);

		// 先创建配置项再创建列表控件，保证 getConfigWidth() 能按实际标签宽度计算（列表宽度创建后不再更新）
		this.setListPosition(this.width / 10, this.getListY());
		this.reCreateListWidget();
		super.initGui();
		String statusKey = currentPair.isEnabled()
				? "autotrade.gui.pair_edit.status_on"
				: "autotrade.gui.pair_edit.status_off";
		this.setTitle(StringUtils.translate("autotrade.gui.pair_edit.pair_title", pairIndex + 1,
				StringUtils.translate(statusKey)));

		int grabX = this.width / 10;
		// 按钮文案（走翻译键，保持与语言文件一致）
		String[] grabLabels = {StringUtils.translate("autotrade.gui.pair_edit.grab_give"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_give2"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_get"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_input"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_output")};
		// 计算按钮宽度：取「最长文案所需宽度」与「5 个按钮 + 4 个 4px 间隔均分浏览器宽度」的较小值，
		// 既不截断文案，也不超出滚动条区域
		int gap = 4;
		int needed = 0;
		for (String label : grabLabels) {
			needed = Math.max(needed, this.getStringWidth(label) + 10);
		}
		int btnWidth = Math.min(needed, (this.getBrowserWidth() - 4 * gap) / 5);
		ButtonGeneric grabGive = new ButtonGeneric(grabX, 26, btnWidth, 20, grabLabels[0]);
		this.addButton(grabGive, (b, mb) -> grabItem(true));
		ButtonGeneric grabGive2 = new ButtonGeneric(grabX + btnWidth + gap, 26, btnWidth, 20, grabLabels[1]);
		this.addButton(grabGive2, (b, mb) -> grabItem2());
		ButtonGeneric grabGet = new ButtonGeneric(grabX + 2 * (btnWidth + gap), 26, btnWidth, 20, grabLabels[2]);
		this.addButton(grabGet, (b, mb) -> grabItem(false));
		ButtonGeneric grabInput = new ButtonGeneric(grabX + 3 * (btnWidth + gap), 26, btnWidth, 20, grabLabels[3]);
		this.addButton(grabInput, (b, mb) -> grabContainer(true));
		ButtonGeneric grabOutput = new ButtonGeneric(grabX + 4 * (btnWidth + gap), 26, btnWidth, 20, grabLabels[4]);
		this.addButton(grabOutput, (b, mb) -> grabContainer(false));

		ButtonGeneric backBtn = new ButtonGeneric(this.width / 10, this.height - 30, 60, 20,
				StringUtils.translate("autotrade.gui.pair_edit.back"));
		this.addButton(backBtn, (b, mb) -> GuiBase.openGui(new PairListScreen()));
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		if (giveConfig == null)
			return ImmutableList.of();
		return ConfigOptionWrapper.createFor(ImmutableList.of(giveConfig, give2Config, getConfig, noteConfig,
				limitConfig, enableInputConfig, inputPosConfig, inputThresholdConfig, inputTakeAmountConfig,
				enableGive2InputConfig, give2InputPosConfig, give2InputThresholdConfig, enableOutputConfig,
				outputPosConfig, outputThresholdConfig));
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
		currentPair.setGiveItem2(give2Config.getStringValue());
		currentPair.setGetItem(getConfig.getStringValue());
		currentPair.setLimit(limitConfig.getIntegerValue());
		currentPair.setInputEnabled(enableInputConfig.getBooleanValue());
		// 解析输入容器坐标；解析失败时保留上次有效坐标并提示，绝不让异常逃出回调链（客户端防崩溃护栏）
		BlockPos inputPos = inputPosConfig.toBlockPos();
		if (inputPos != null) {
			currentPair.setInputX(inputPos.getX());
			currentPair.setInputY(inputPos.getY());
			currentPair.setInputZ(inputPos.getZ());
		} else {
			warnInvalidPos();
		}
		currentPair.setGive2InputEnabled(enableGive2InputConfig.getBooleanValue());
		// 解析 give2 输入容器坐标；失败时同样保留上次有效坐标
		BlockPos give2InputPos = give2InputPosConfig.toBlockPos();
		if (give2InputPos != null) {
			currentPair.setGive2InputX(give2InputPos.getX());
			currentPair.setGive2InputY(give2InputPos.getY());
			currentPair.setGive2InputZ(give2InputPos.getZ());
		} else {
			warnInvalidPos();
		}
		currentPair.setOutputEnabled(enableOutputConfig.getBooleanValue());
		// 解析输出容器坐标；失败时保留上次有效坐标
		BlockPos outputPos = outputPosConfig.toBlockPos();
		if (outputPos != null) {
			currentPair.setOutputX(outputPos.getX());
			currentPair.setOutputY(outputPos.getY());
			currentPair.setOutputZ(outputPos.getZ());
		} else {
			warnInvalidPos();
		}
		currentPair.setInputThreshold(inputThresholdConfig.getIntegerValue());
		currentPair.setInputTakeAmount(inputTakeAmountConfig.getIntegerValue());
		currentPair.setOutputThreshold(outputThresholdConfig.getIntegerValue());
		currentPair.setGive2InputThreshold(give2InputThresholdConfig.getIntegerValue());
		currentPair.setNote(noteConfig.getStringValue());
	}

	// 将三个坐标格式化为 "x y z" 空格分隔字符串，用于 GUI 展示
	private String formatPos(int x, int y, int z) {
		return x + " " + y + " " + z;
	}

	// 坐标格式非法时提示用户；对应组坐标保持上次有效值，不覆盖不崩溃
	private void warnInvalidPos() {
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_pos");
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

	// 抓取主手物品作为第二给出物品（give2）：写入 currentPair 并保存刷新，与 grabItem(true) 同模式
	private void grabItem2() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || currentPair == null)
			return;
		ItemStack held = mc.player.getMainHandStack();
		if (held.isEmpty())
			return;
		String encoded = ItemStringHelper.encode(held);
		String itemId = ItemStringHelper.getItemId(encoded);
		currentPair.setGiveItem2(encoded);
		saveCurrentPair();
		initGui();
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.sell_item_set", itemId);
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
}
