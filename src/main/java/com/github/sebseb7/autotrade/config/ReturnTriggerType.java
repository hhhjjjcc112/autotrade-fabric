package com.github.sebseb7.autotrade.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * VOID 返回触发类型枚举：NONE = 不启用（默认行为不变）；TRAPPED_CHEST / BUTTON / LEVER 为三种可用的返回机关。
 * 实现 {@link IConfigOptionListEntry} 以便作为 malilib 下拉配置项使用（参照 {@link TradeMode}）。
 */
public enum ReturnTriggerType implements IConfigOptionListEntry {
	NONE("NONE"), TRAPPED_CHEST("TRAPPED_CHEST"), BUTTON("BUTTON"), LEVER("LEVER");

	private final String configString;

	ReturnTriggerType(String configString) {
		this.configString = configString;
	}

	@Override
	public String getStringValue() {
		return configString;
	}

	@Override
	public String getDisplayName() {
		return StringUtils.translate("autotrade.returntrigger." + configString.toLowerCase());
	}

	@Override
	public IConfigOptionListEntry cycle(boolean forward) {
		ReturnTriggerType[] values = ReturnTriggerType.values();
		int index = (this.ordinal() + (forward ? 1 : -1) + values.length) % values.length;
		return values[index];
	}

	@Override
	public IConfigOptionListEntry fromString(String value) {
		for (ReturnTriggerType type : ReturnTriggerType.values()) {
			if (type.configString.equalsIgnoreCase(value)) {
				return type;
			}
		}
		return NONE;
	}
}
