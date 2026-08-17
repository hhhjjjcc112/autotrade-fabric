package com.github.sebseb7.autotrade.config.options;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigOptionList;

/**
 * 包装 {@link ConfigOptionList} 使其实现 {@link IConfigValue}， 以便放入 malilib 的 OPTIONS
 * 列表。
 */
public class ConfigOptionListValue extends ConfigOptionList implements IConfigValue {

	public ConfigOptionListValue(String name, IConfigOptionListEntry defaultValue, String comment) {
		super(name, defaultValue, comment);
	}

	public ConfigOptionListValue(String name, IConfigOptionListEntry defaultValue, String comment, String prettyName) {
		super(name, defaultValue, comment, prettyName);
	}
}
