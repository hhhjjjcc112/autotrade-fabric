package com.github.sebseb7.autotrade.config;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import java.util.List;

public class Hotkeys {
	public static final ConfigHotkey TOGGLE_KEY = new ConfigHotkey("toggleTrading", "",
			"Enable / disable auto trading");
	public static final ConfigHotkey OPEN_GUI_SETTINGS = new ConfigHotkey("openGuiSettings", "RIGHT_SHIFT,T",
			"Open the settings GUI");
	public static final ConfigHotkey ADD_TRADE_PAIR_KEY = new ConfigHotkey("addTradePair", "",
			"Press while hovering over a trade in the villager screen to add it as a trade pair");

	public static final List<ConfigHotkey> HOTKEY_LIST = ImmutableList.of(TOGGLE_KEY, OPEN_GUI_SETTINGS,
			ADD_TRADE_PAIR_KEY);
}
