package com.github.sebseb7.autotrade.config.options;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.JsonElement;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.regex.Pattern;

/**
 * 带维度 registry id 校验的字符串配置（如 Void 的 voidReturnDim）。 语义：空串合法（= 任意维度，与 IO
 * 记录空语义一致）；非空必须完整匹配小写 registry id（namespace:path）。
 */
public class ConfigDimension extends ConfigString {
	// 小写 registry id：namespace + ':' + path（字符集与 Identifier 一致，如
	// minecraft:overworld）
	public static final Pattern DIMENSION_PATTERN = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

	/** 构造维度字符串配置：空串合法，非空须完整匹配 DIMENSION_PATTERN */
	public ConfigDimension(String name, String defaultValue, String comment) {
		super(name, defaultValue, comment);
	}

	/**
	 * 重写字符串设置路径（GUI 编辑等）：空串合法直接放行；非空须整串正则匹配；非法值保留原值并提示，且不触发
	 * onValueChanged（避免旧独立编辑屏自动保存链的无限递归）
	 */
	@Override
	public void setValueFromString(String value) {
		String trimmed = value.trim();
		if (!trimmed.isEmpty() && !DIMENSION_PATTERN.matcher(trimmed).matches()) {
			// 拒绝路径：只提示并直接返回，不调用 super（不改变值、不触发 onValueChanged）
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_dimension");
			return;
		}
		// 合法值（含空串）：存入 trim 后的字符串，沿用 ConfigString 的 previousValue 跟踪与仅变更时回调语义
		super.setValueFromString(trimmed);
	}

	/** 重写 JSON 配置加载路径：空串或完整匹配维度正则时放行；其余非法值静默忽略（保留当前值）并记 warn 日志 */
	@Override
	public void setValueFromJsonElement(JsonElement element) {
		if (element.isJsonPrimitive()) {
			String trimmed = element.getAsString().trim();
			if (trimmed.isEmpty() || DIMENSION_PATTERN.matcher(trimmed).matches()) {
				super.setValueFromJsonElement(element);
				return;
			}
		}
		// 加载期拒绝路径：仅记录日志，不修改当前值
		AutoTrade.logger.warn("[AutoTrade] Ignoring invalid value for config '{}': {}", this.getName(), element);
	}
}
