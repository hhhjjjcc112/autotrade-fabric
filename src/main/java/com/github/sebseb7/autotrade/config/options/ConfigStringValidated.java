package com.github.sebseb7.autotrade.config.options;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.JsonElement;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.regex.Pattern;

public class ConfigStringValidated extends ConfigString {
	// 与 VoidTradeMachine.parseReturnPos 的 trim + split("\\s+") + 3 段语义一致：
	// 匹配「x y z」三个整数坐标，每段可带可选负号，段间允许任意空白（含制表符/换行）
	public static final Pattern POSITION_PATTERN = Pattern.compile("-?\\d+\\s+-?\\d+\\s+-?\\d+");

	private final Pattern pattern;

	/** 构造带正则校验的字符串配置：pattern 决定哪些输入可被接受 */
	public ConfigStringValidated(String name, String defaultValue, String comment, Pattern pattern) {
		super(name, defaultValue, comment);
		this.pattern = pattern;
	}

	/**
	 * 重写字符串设置路径（GUI 编辑等）：整串正则匹配，非法值保留原值并提示，且不触发 onValueChanged（避免旧独立编辑屏 自动保存链的无限递归）
	 */
	@Override
	public void setValueFromString(String value) {
		String trimmed = value.trim();
		if (!this.pattern.matcher(trimmed).matches()) {
			// 拒绝路径：只提示并直接返回，不调用 super（不改变值、不触发 onValueChanged）
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_pos");
			return;
		}
		// 合法值：存入 trim 后的字符串，沿用 ConfigString 的 previousValue 跟踪与仅变更时回调语义
		super.setValueFromString(trimmed);
	}

	/** 重写 JSON 配置加载路径：非法值静默忽略（保留当前值即构造默认值）并记 warn 日志 */
	@Override
	public void setValueFromJsonElement(JsonElement element) {
		if (element.isJsonPrimitive() && this.pattern.matcher(element.getAsString().trim()).matches()) {
			super.setValueFromJsonElement(element);
		} else {
			// 加载期拒绝路径：仅记录日志，不修改当前值
			AutoTrade.logger.warn("[AutoTrade] Ignoring invalid value for config '{}': {}", this.getName(), element);
		}
	}
}
