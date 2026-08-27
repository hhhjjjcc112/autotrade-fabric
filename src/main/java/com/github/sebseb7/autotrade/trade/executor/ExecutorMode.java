package com.github.sebseb7.autotrade.trade.executor;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 交易执行策略：USE = 直接读取 offer.getUses()（本地模拟同步值，逻辑更简，默认）；OUTPUT_SLOT = 不读取 offer
 * uses（快照推导剩余次数，不依赖本地点击模拟保真度，保守可选）
 */
public enum ExecutorMode implements IConfigOptionListEntry {
	USE("USE"), OUTPUT_SLOT("OUTPUT_SLOT");

	private final String configString;

	ExecutorMode(String configString) {
		this.configString = configString;
	}

	@Override
	public String getStringValue() {
		return configString;
	}

	@Override
	public String getDisplayName() {
		return StringUtils.translate("autotrade.executormode." + configString.toLowerCase());
	}

	@Override
	public IConfigOptionListEntry cycle(boolean forward) {
		ExecutorMode[] values = ExecutorMode.values();
		int index = (this.ordinal() + (forward ? 1 : -1) + values.length) % values.length;
		return values[index];
	}

	@Override
	public IConfigOptionListEntry fromString(String value) {
		for (ExecutorMode mode : ExecutorMode.values()) {
			if (mode.configString.equalsIgnoreCase(value)) {
				return mode;
			}
		}
		return USE;
	}
}