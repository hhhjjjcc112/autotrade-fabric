package com.github.sebseb7.autotrade.handler;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.GuiConfigs;
import com.github.sebseb7.autotrade.gui.GuiConfigs.ConfigGuiTab;
import com.github.sebseb7.autotrade.gui.widget.ItemIOBaseWidget;
import com.github.sebseb7.autotrade.runtime.AutoTradeClientTick;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public class KeybindCallbacks implements IHotkeyCallback {
	private static final KeybindCallbacks INSTANCE = new KeybindCallbacks();

	private KeybindCallbacks() {
	}

	public static KeybindCallbacks getInstance() {
		return INSTANCE;
	}

	public void setCallbacks() {
		for (ConfigHotkey hotkey : Hotkeys.HOTKEY_LIST) {
			hotkey.getKeybind().setCallback(this);
		}
	}

	@Override
	public boolean onKeyAction(KeyAction action, IKeybind key) {
		if (action != KeyAction.PRESS) {
			return false;
		}
		if (key == Hotkeys.TOGGLE_KEY.getKeybind()) {
			Configs.Generic.ENABLED.toggleBooleanValue();
			String msg = Configs.Generic.ENABLED.getBooleanValue()
					? "autotrade.message.toggled_mod_on"
					: "autotrade.message.toggled_mod_off";
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, msg);
			if (Configs.Generic.ENABLED.getBooleanValue()) {
				AutoTradeClientTick.getInstance().reset();
				AutoTrade.logger.info("[AutoTrade] TOGGLED ON → reset machines");
			}
			return true;
		} else if (key == Hotkeys.OPEN_GUI_SETTINGS.getKeybind()) {
			GuiBase.openGui(new GuiConfigs());
			return true;
		} else if (key == Hotkeys.GRAB_CONTAINER_COORDINATE.getKeybind()) {
			// 抓取时机 = 按键瞬间：GUI 打开期间玩家不可移动，位置无陈旧风险
			BlockPos pos = ItemIOBaseWidget.grabFootBlockPos();
			if (pos == null) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
						"autotrade.message.grab_container_failed");
				return true;
			}
			String dim = ContainerIOHelper.currentDimensionId(MinecraftClient.getInstance());
			// 打开页规则：上次在 IO 页则沿用，否则默认 IO输入
			ConfigGuiTab t = GuiConfigs.getTab();
			if (t == ConfigGuiTab.IO_INPUT || t == ConfigGuiTab.IO_OUTPUT) {
				GuiConfigs.setTab(t);
			} else {
				GuiConfigs.setTab(ConfigGuiTab.IO_INPUT);
			}
			// GUI 已打开时再按热键 → 新 GUI 替换旧 GUI（旧 GUI 未提交的文本框修改丢弃，与 OPEN_GUI_SETTINGS 一致；
			// 旧实例 removed() 触发 exitGrabMode 为无害 no-op）
			GuiConfigs gui = new GuiConfigs();
			gui.enterGrabMode(pos, dim);
			GuiBase.openGui(gui);
			return true;
		}
		return false;
	}
}
