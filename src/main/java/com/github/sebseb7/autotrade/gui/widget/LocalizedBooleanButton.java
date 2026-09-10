package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ConfigButtonBoolean;
import fi.dy.masa.malilib.util.StringUtils;

// 本地化布尔值按钮：把 malilib 的 true/false 显示文本替换为「开/关」（英文 ON/OFF），
// 保留暗绿/暗红着色与点击翻转行为；以固定窄宽（状态指示器）形态与列表行的满宽「动作按钮」区分
public class LocalizedBooleanButton extends ConfigButtonBoolean {
	/** 固定宽度（像素）：布尔按钮作为「状态指示器」形态，刻意窄于满宽列表动作按钮 */
	public static final int INDICATOR_WIDTH = 60;

	/** 配置引用副本（malilib 父类的 config 字段为 private，显示文本需自行读取当前值） */
	private final IConfigBoolean config;

	public LocalizedBooleanButton(int x, int y, int width, int height, IConfigBoolean config) {
		super(x, y, width, height, config);
		this.config = config;
		// 父类构造期已调用过一次 updateDisplayString（此时本类字段尚未赋值，走父类兜底），
		// 这里用本地化文本再刷一次，保证初始显示即为「开/关」
		this.updateDisplayString();
	}

	/**
	 * 覆写显示文本：按当前布尔值取「开/关」翻译键（暗绿 = 开 / 暗红 = 关）。 点击（父类 onMouseClickedImpl
	 * 翻转值后）与重置（ConfigResetterButton）都会回调本方法， 因此按钮文本始终反映配置当前值（无陈旧状态）
	 */
	@Override
	public void updateDisplayString() {
		// 构造期父类构造函数会先调用本方法，此子类字段尚未赋值（null）→ 退回父类实现避免 NPE
		if (this.config == null) {
			super.updateDisplayString();
			return;
		}

		boolean value = this.config.getBooleanValue();
		String valueStr = StringUtils
				.translate(value ? "autotrade.gui.config.boolean_on" : "autotrade.gui.config.boolean_off");

		if (value) {
			this.displayString = GuiBase.TXT_DARK_GREEN + valueStr + GuiBase.TXT_RST;
		} else {
			this.displayString = GuiBase.TXT_DARK_RED + valueStr + GuiBase.TXT_RST;
		}
	}
}
