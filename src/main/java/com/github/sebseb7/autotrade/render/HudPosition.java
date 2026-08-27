package com.github.sebseb7.autotrade.render;

import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 调试 HUD 的屏幕角落位置枚举。 实现 {@link IConfigOptionListEntry} 以便作为 malilib 下拉列表配置项使用；
 * configString 与 malilib 的 {@link HudAlignment} 保持一致，显示名复用 malilib 自带的
 * malilib.label.alignment.* 翻译键（零新增翻译）。
 */
public enum HudPosition implements IConfigOptionListEntry {
	TOP_LEFT("top_left", HudAlignment.TOP_LEFT), TOP_RIGHT("top_right", HudAlignment.TOP_RIGHT), BOTTOM_LEFT(
			"bottom_left", HudAlignment.BOTTOM_LEFT), BOTTOM_RIGHT("bottom_right", HudAlignment.BOTTOM_RIGHT);

	private final String configString;
	private final HudAlignment hudAlignment;

	HudPosition(String configString, HudAlignment hudAlignment) {
		this.configString = configString;
		this.hudAlignment = hudAlignment;
	}

	@Override
	public String getStringValue() {
		return configString;
	}

	@Override
	public String getDisplayName() {
		// 复用 malilib 自带的对齐翻译键（与 HudAlignment 显示名一致）
		return StringUtils.translate("malilib.label.alignment." + configString);
	}

	@Override
	public IConfigOptionListEntry cycle(boolean forward) {
		HudPosition[] values = HudPosition.values();
		int index = (this.ordinal() + (forward ? 1 : -1) + values.length) % values.length;
		return values[index];
	}

	@Override
	public IConfigOptionListEntry fromString(String value) {
		for (HudPosition position : HudPosition.values()) {
			if (position.configString.equalsIgnoreCase(value)) {
				return position;
			}
		}
		return TOP_LEFT;
	}

	/** 转换为对应的 malilib HudAlignment，供 HUD 渲染时计算角落坐标 */
	public HudAlignment toHudAlignment() {
		return hudAlignment;
	}
}
