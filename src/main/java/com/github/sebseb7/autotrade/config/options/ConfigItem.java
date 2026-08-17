package com.github.sebseb7.autotrade.config.options;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.config.ConfigType;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBase;

public class ConfigItem extends ConfigBase<ConfigItem> implements IConfigValue {
	private final String defaultValue;
	private String value;

	public ConfigItem(String name, String defaultValue, String comment) {
		super(ConfigType.STRING, name, comment);
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	@Override
	public String getStringValue() {
		return this.value;
	}

	@Override
	public String getDefaultStringValue() {
		return this.defaultValue;
	}

	@Override
	public void setValueFromString(String value) {
		this.value = value;
		this.onValueChanged();
	}

	@Override
	public void resetToDefault() {
		this.setValueFromString(this.defaultValue);
	}

	@Override
	public boolean isModified() {
		return !this.value.equals(this.defaultValue);
	}

	@Override
	public boolean isModified(String newValue) {
		return !this.defaultValue.equals(newValue);
	}

	@Override
	public void setValueFromJsonElement(JsonElement element) {
		if (element.isJsonPrimitive()) {
			this.value = element.getAsString();
		}
	}

	@Override
	public JsonElement getAsJsonElement() {
		return new JsonPrimitive(this.value);
	}
}
