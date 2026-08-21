package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;

// 交易对列表控件：每行使用 TradePairListEntryWidget 渲染（ENTRY_HEIGHT=20 单行布局，
// 所有组件压缩到一行：内容 + 压缩按钮组，视觉参数沿用 pairlistscreen-layout-refinement 成果；
// 由旧独立列表屏的嵌套类抽取而来）
public class TradePairListConfigOptions extends WidgetListConfigOptions {
	private static final int ENTRY_HEIGHT = 20;

	public TradePairListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
			boolean useKeybindSearch, GuiConfigsBase parent) {
		super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
		this.browserEntryHeight = ENTRY_HEIGHT;
	}

	@Override
	protected int getBrowserEntryHeightFor(ConfigOptionWrapper entry) {
		return ENTRY_HEIGHT;
	}

	@Override
	protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
			ConfigOptionWrapper wrapper) {
		return new TradePairListEntryWidget(x, y, this.browserEntryWidth, ENTRY_HEIGHT, this.maxLabelWidth,
				this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
	}
}
