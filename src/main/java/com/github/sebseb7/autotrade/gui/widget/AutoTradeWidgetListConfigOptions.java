package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

// 值页配置列表控件：行控件改用 AutoTradeConfigOption（布尔值本地化「开/关」+ 标签灰化），
// 由 GuiConfigs.createListWidget 为 通用/静止/移动/虚空 四个值页返回；构造参数与 malilib 默认列表控件一致
public class AutoTradeWidgetListConfigOptions extends WidgetListConfigOptions {
	public AutoTradeWidgetListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
			boolean useKeybindSearch, GuiConfigsBase parent) {
		super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
	}

	/** 行控件工厂：创建自定义行控件，并把本列表自身作为 parent 传入（行控件借此获得父列表引用） */
	@Override
	protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
			ConfigOptionWrapper wrapper) {
		return new AutoTradeConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight, this.maxLabelWidth,
				this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
	}
}
