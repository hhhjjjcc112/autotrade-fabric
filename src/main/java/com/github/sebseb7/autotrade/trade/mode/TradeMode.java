package com.github.sebseb7.autotrade.trade.mode;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

public enum TradeMode implements IConfigOptionListEntry {
	STATIC("STATIC"), MOVING("MOVING"), VOID("VOID");

	private final String configString;

	TradeMode(String configString) {
		this.configString = configString;
	}

	@Override
	public String getStringValue() {
		return configString;
	}

	@Override
	public String getDisplayName() {
		return StringUtils.translate("autotrade.trademode." + configString.toLowerCase());
	}

	@Override
	public IConfigOptionListEntry cycle(boolean forward) {
		TradeMode[] values = TradeMode.values();
		int index = (this.ordinal() + (forward ? 1 : -1) + values.length) % values.length;
		return values[index];
	}

	@Override
	public IConfigOptionListEntry fromString(String value) {
		for (TradeMode mode : TradeMode.values()) {
			if (mode.configString.equalsIgnoreCase(value)) {
				return mode;
			}
		}
		return STATIC;
	}
}
