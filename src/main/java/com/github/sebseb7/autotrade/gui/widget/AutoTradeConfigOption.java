package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.config.ConfigType;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigBoolean;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ConfigButtonBoolean;
import fi.dy.masa.malilib.gui.interfaces.IConfigInfoProvider;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;

// 值页自定义配置行控件：仅布尔类型改用本地化「开/关」状态指示器按钮（其余类型全部交回 malilib 基类），
// 并把基类硬编码的白色标签弱化为次级灰；由 AutoTradeWidgetListConfigOptions 构造
public class AutoTradeConfigOption extends WidgetConfigOption {
	/**
	 * 父列表控件引用（构造时由列表传入自身）：行内自定义交互写入配置值后需要用它调用 refreshEntries()
	 * 重建行内容（文本框等不会因配置值变化自动重绘）
	 */
	protected final WidgetListConfigOptionsBase<?, ?> parentList;

	public AutoTradeConfigOption(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
		this.parentList = parent;
	}

	/**
	 * 覆写配置项构建：仅 BOOLEAN 类型改建本地化按钮（固定 60px 窄宽状态指示器）； 其余类型（文本/数值/热键等，以及 LABEL 型
	 * wrapper）一律调 super 透传
	 */
	@Override
	protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
		if (config.getType() != ConfigType.BOOLEAN) {
			super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			return;
		}

		// 以下复刻 malilib BOOLEAN 分支（WidgetConfigOption.java:114-157）：标签 + 悬浮注释 + 值按钮 +
		// 重置按钮；
		// 唯一差异是值按钮宽度固定为状态指示器常量（60px），重置按钮仍在原右缘（与其它配置行对齐）
		y += 1;
		int configHeight = 20;

		this.addLabel(x, y + 7, labelWidth, 8, 0xFFFFFFFF, config.getConfigGuiDisplayName());

		String comment;
		IConfigInfoProvider infoProvider = this.host.getHoverInfoProvider();

		if (infoProvider != null) {
			comment = infoProvider.getHoverInfo(config);
		} else {
			comment = config.getComment();
		}

		if (comment != null) {
			this.addConfigComment(x, y + 5, labelWidth, 12, comment);
		}

		x += labelWidth + 10;

		ConfigButtonBoolean optionButton = new LocalizedBooleanButton(x, y, LocalizedBooleanButton.INDICATOR_WIDTH,
				configHeight, (IConfigBoolean) config);
		this.addConfigButtonEntry(x + configWidth + 2, y, (IConfigResettable) config, optionButton);
	}

	/**
	 * 覆写标签构建：malilib 基类对配置标签与 LABEL 型说明行硬编码白色 0xFFFFFFFF
	 * （WidgetConfigOption.java:110,121），统一弱化为次级灰 0xFFB0B0B0； 分节标题由文本自带 §f
	 * 前缀自行保持白色（文本内格式码优先于控件色）
	 */
	@Override
	protected void addLabel(int x, int y, int width, int height, int textColor, String... lines) {
		if (textColor == 0xFFFFFFFF) {
			textColor = 0xFFB0B0B0;
		}

		super.addLabel(x, y, width, height, textColor, lines);
	}
}
