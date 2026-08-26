package com.github.sebseb7.autotrade.config.options;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.config.options.ConfigString;

/**
 * 以 JSON 数组形式序列化/反序列化的字符串配置： 内部值仍以 JSON 字符串存储（保证 getStringValue
 * 的引用失效语义不变），仅重写两个序列化入口
 */
public final class ConfigJsonArray extends ConfigString {
	/** 构造 JSON 数组配置：defaultValue 为 JSON 数组文本，直接透传给父类 */
	public ConfigJsonArray(String name, String defaultValue, String comment) {
		super(name, defaultValue, comment);
	}

	/**
	 * 序列化：内部字符串可解析为 JSON 数组则原样返回； 解析失败（如空字符串抛
	 * JsonSyntaxException）或解析结果不是数组时警告并返回空数组
	 */
	@Override
	public JsonElement getAsJsonElement() {
		try {
			// 尝试把内部字符串解析为 JSON 数组
			JsonElement parsed = JsonParser.parseString(this.getStringValue());
			if (parsed instanceof JsonArray arr) {
				return arr;
			}
		} catch (Exception e) {
			// 解析异常落入下方警告分支（返回空数组，不写回损坏值）
		}
		AutoTrade.logger.warn("[AutoTrade] Failed to parse config '{}' as a JSON array, saving as empty array",
				this.getName());
		return new JsonArray();
	}

	/**
	 * 从 JSON 加载值：新格式（数组）与旧格式（字符串）均接受； 旧格式字符串原样保留，首次保存时经 getAsJsonElement 自动迁移为数组
	 */
	@Override
	public void setValueFromJsonElement(JsonElement element) {
		if (element.isJsonArray()) {
			// 新格式：把数组的序列化文本作为内部字符串存储
			super.setValueFromString(element.toString());
		} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			// 旧格式：原样保留字符串值（迁移由首次保存自动完成）
			super.setValueFromString(element.getAsString());
		} else {
			// 其他类型（null/数字/布尔/对象）：警告并忽略，不改变当前值
			AutoTrade.logger.warn("[AutoTrade] Ignoring invalid value for config '{}': {}", this.getName(), element);
		}
	}
}
