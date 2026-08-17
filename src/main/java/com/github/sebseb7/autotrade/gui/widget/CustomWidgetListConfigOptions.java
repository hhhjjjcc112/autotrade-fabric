package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

// 配置列表控件：定制 createListEntryWidget，使每个配置项条目使用本包的 CustomWidgetConfigOption 渲染（支持物品图标）
public class CustomWidgetListConfigOptions extends WidgetListConfigOptions {
	public CustomWidgetListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
			boolean useKeybindSearch, GuiConfigsBase parent) {
		super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
	}

	@Override
	protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
			ConfigOptionWrapper wrapper) {
		return new CustomWidgetConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight, this.maxLabelWidth,
				this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
	}
}
