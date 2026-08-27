package com.github.sebseb7.autotrade.handler;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.GuiConfigs;
import com.github.sebseb7.autotrade.runtime.AutoTradeClientTick;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;

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
		}
		return false;
	}
}
