package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.options.ConfigItem;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairList;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

// 交易对行内编辑面板：维护 give/give2/get/limit/note 五个配置项与自动保存、
// 主手抓取物品逻辑，保存走 TradePairList.updatePair（由旧独立编辑屏的编辑逻辑抽取而来）。
// 宿主（独立屏或未来的选项卡）负责渲染 getConfigs() 与抓取按钮，并通过 onSaved 回调刷新视图。
public class TradePairEditPanel {
	private final int pairIndex;
	private final TradePair currentPair;
	// 保存成功后回调（宿主刷新视图用，可空）
	private final Runnable onSaved;
	// 保存前提交宿主列表控件中未提交的文本输入（可空）
	private final Runnable applyPendingHook;

	private ConfigItem giveConfig;
	private ConfigItem give2Config;
	private ConfigItem getConfig;
	private ConfigInteger limitConfig;
	private ConfigString noteConfig;

	public TradePairEditPanel(int pairIndex, TradePair currentPair) {
		this(pairIndex, currentPair, null, null);
	}

	public TradePairEditPanel(int pairIndex, TradePair currentPair, Runnable onSaved, Runnable applyPendingHook) {
		this.pairIndex = pairIndex;
		this.currentPair = currentPair;
		this.onSaved = onSaved;
		this.applyPendingHook = applyPendingHook;
	}

	public int getPairIndex() {
		return pairIndex;
	}

	// 从当前交易对数据重建五个配置项并挂自动保存（等价于原独立编辑屏 initGui 中的创建段；
	// 交易对被外部修改后调用本方法可刷新面板内容）
	public void rebuildConfigs() {
		giveConfig = new ConfigItem("paireditGive", currentPair.getGiveItem(),
				"Click [Grab Give] to use the item in hand");
		give2Config = new ConfigItem("paireditGive2", currentPair.getGiveItem2(),
				"Click [Grab Give 2] to use the item in hand");
		getConfig = new ConfigItem("paireditGet", currentPair.getGetItem(), "Click [Grab Get] to use the item in hand");
		limitConfig = new ConfigInteger("paireditLimit", currentPair.getLimit(), 1, 64,
				"Max give items consumed per trade");
		noteConfig = new ConfigString("paireditNote", currentPair.getNote() != null ? currentPair.getNote() : "",
				"Notes");

		attachAutoSave(giveConfig);
		attachAutoSave(give2Config);
		attachAutoSave(getConfig);
		attachAutoSave(limitConfig);
		attachAutoSave(noteConfig);
	}

	// 供宿主 getConfigs() 返回渲染（必须先调用 rebuildConfigs）
	public List<ConfigOptionWrapper> getConfigs() {
		if (giveConfig == null)
			return ImmutableList.of();
		return ConfigOptionWrapper
				.createFor(ImmutableList.of(giveConfig, give2Config, getConfig, noteConfig, limitConfig));
	}

	private <T extends IConfigValue> void attachAutoSave(T config) {
		if (config instanceof fi.dy.masa.malilib.config.options.ConfigBase<?> base) {
			base.setValueChangeCallback(c -> applyAndSave());
		}
	}

	// 提交宿主列表待定输入 → 配置值写回 currentPair → 保存 → 回调宿主刷新
	public void applyAndSave() {
		if (currentPair == null)
			return;
		if (applyPendingHook != null) {
			applyPendingHook.run();
		}
		syncFromConfigs();
		saveCurrentPair();
		if (onSaved != null) {
			onSaved.run();
		}
	}

	private void syncFromConfigs() {
		currentPair.setGiveItem(giveConfig.getStringValue());
		currentPair.setGiveItem2(give2Config.getStringValue());
		currentPair.setGetItem(getConfig.getStringValue());
		currentPair.setLimit(limitConfig.getIntegerValue());
		currentPair.setNote(noteConfig.getStringValue());
	}

	// 抓取主手物品作为给出/获取物品：isGive=true 写入 giveItem，否则写入 getItem，保存并刷新显示
	public void grabItem(boolean isGive) {
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
		rebuildConfigs();
		saveCurrentPair();
		if (onSaved != null) {
			onSaved.run();
		}
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS,
				isGive ? "autotrade.message.sell_item_set" : "autotrade.message.buy_item_set", itemId);
	}

	// 抓取主手物品作为第二给出物品（give2）：写入 currentPair 并保存刷新，与 grabItem(true) 同模式
	public void grabItem2() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || currentPair == null)
			return;
		ItemStack held = mc.player.getMainHandStack();
		if (held.isEmpty())
			return;
		String encoded = ItemStringHelper.encode(held);
		String itemId = ItemStringHelper.getItemId(encoded);
		currentPair.setGiveItem2(encoded);
		rebuildConfigs();
		saveCurrentPair();
		if (onSaved != null) {
			onSaved.run();
		}
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.sell_item_set", itemId);
	}

	// 将当前交易对保存回 TRADE_PAIRS 配置（updatePair + saveToFile）
	public void saveCurrentPair() {
		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.updatePair(json, pairIndex, currentPair));
		Configs.saveToFile();
	}
}
