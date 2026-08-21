package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOList;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairList;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 物品 IO 选项卡列表控件（方向参数化）：渲染某个方向（输入/输出）的派生行列表。
 *
 * <p>
 * 行集合 = {@link IoItemDeriver#derive} 全部交易对（含禁用）后按方向过滤，渲染前按 enabledCount 降序排序
 * （次键：disabledCount 降序，再物品 id 字母序）；每行与 {@link ItemIOList} 按 (item, 方向) 匹配
 * （findByKey 语义），未命中使用占位条目（enabled=true、0 0 0、阈值 1、单次 6）；派生集为空时渲染空态提示行。
 * </p>
 *
 * <p>
 * 本控件自给自足：行数据与条目列表由本类构建（覆写 {@link #getAllEntries}），父屏只需在 createListWidget
 * 中实例化并传入方向；每次列表刷新都会重新派生（交易对/条目变化无需缓存同步）。 行渲染委托 {@link ItemIOEntryWidget}（含
 * Enter/失焦提交机制）。
 * </p>
 */
public class ItemIOTabList extends WidgetListConfigOptions {
	/** 行高：沿用旧 IO 列表的 ENTRY_HEIGHT=40 */
	public static final int ENTRY_HEIGHT = ItemIOEntryWidget.ENTRY_HEIGHT;
	/** 未命中已保存条目时占位条目的默认值：坐标 0 0 0、阈值 1、单次 6、启用 */
	private static final int DEFAULT_X = 0;
	private static final int DEFAULT_Y = 0;
	private static final int DEFAULT_Z = 0;
	private static final int DEFAULT_THRESHOLD = 1;
	private static final int DEFAULT_TAKE_AMOUNT = 6;

	/** 单行数据：物品编码串 + 派生统计（可为 null，旧列表屏行无统计）+ 当前条目 + 方向 */
	public record RowData(String item, IoItemDeriver.IoItemStat stat, ItemIO entry, boolean isInput) {
	}

	private final boolean isInput;
	private List<RowData> rowData = new ArrayList<>();
	/**
	 * D3 重入保护：applyPendingModifications 执行期间置位，防止列表重建（refreshEntries →
	 * reCreateListEntryWidgets → applyPendingModifications）导致重入
	 */
	private boolean applyingPendingModifications;
	/** 重建前聚焦文本框的定位身份（行物品编码串 + 字段种类），重建后恢复焦点；null = 无聚焦 */
	private String focusRowItem;
	private ItemIOEntryWidget.FieldKind focusFieldKind;

	/**
	 * @param isInput
	 *            本选项卡方向：true = IO输入（give ∪ give2），false = IO输出（getItem）
	 */
	public ItemIOTabList(int x, int y, int width, int height, int configWidth, float zLevel, boolean useKeybindSearch,
			GuiConfigsBase parent, boolean isInput) {
		super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
		this.isInput = isInput;
		this.browserEntryHeight = ENTRY_HEIGHT;
	}

	/** 本选项卡方向（供外部按方向过滤等使用） */
	public boolean isInput() {
		return isInput;
	}

	/**
	 * 刷新列表行（提交回调用）：与基类 refreshEntries 不同，本方法不重建列表对象本身，只重建行控件
	 * （派生数据可能变化：排序/占位/统计）。重建前后记录并恢复聚焦文本框，避免提交 （Enter/失焦/Tab/按钮）后焦点丢失（D2 缺陷修复核心）。
	 */
	@Override
	public void refreshEntries() {
		captureFocusState();
		try {
			super.refreshEntries();
		} finally {
			restoreFocusState();
		}
	}

	/**
	 * 提交待定修改（覆写）：修复两个缺陷 —— ① 提交回调（onCommit → refreshEntries）会在遍历期间重建
	 * listWidgets，直接迭代原集合会抛 ConcurrentModificationException，改为快照遍历； ②
	 * 重建路径（reCreateListEntryWidgets）会再次调用本方法，用布尔标志防止重入（D3）。
	 * 同一时刻仅一个文本框可聚焦，至多一个控件有待提交修改，故首次提交后即可终止遍历。
	 */
	@Override
	public void applyPendingModifications() {
		if (this.applyingPendingModifications) {
			return;
		}
		this.applyingPendingModifications = true;
		try {
			List<WidgetConfigOption> snapshot = new ArrayList<>(this.listWidgets);
			for (WidgetConfigOption widget : snapshot) {
				if (widget.hasPendingModifications()) {
					widget.applyNewValueToConfig();
					this.configsModified = true;
					break;
				}
			}
		} finally {
			this.applyingPendingModifications = false;
		}
	}

	/** 重建前记录聚焦文本框身份（行物品 + 字段种类）；无聚焦时记录为空 */
	private void captureFocusState() {
		this.focusRowItem = null;
		this.focusFieldKind = null;
		for (WidgetConfigOption widget : this.listWidgets) {
			if (widget instanceof ItemIOEntryWidget ioWidget) {
				ItemIOEntryWidget.FieldKind kind = ioWidget.getFocusedFieldKind();
				if (kind != null) {
					this.focusRowItem = ioWidget.getItem();
					this.focusFieldKind = kind;
					return;
				}
			}
		}
	}

	/** 重建后按（行物品, 字段种类）恢复聚焦文本框；行已不存在时静默跳过 */
	private void restoreFocusState() {
		if (this.focusRowItem == null || this.focusFieldKind == null) {
			return;
		}
		for (WidgetConfigOption widget : this.listWidgets) {
			if (widget instanceof ItemIOEntryWidget ioWidget && ioWidget.getItem().equals(this.focusRowItem)) {
				ioWidget.focusField(this.focusFieldKind);
				return;
			}
		}
	}

	/**
	 * 构建行数据：派生 + 排序 + (item, 方向) 匹配。子类可覆写以替换行源（如旧的独立列表屏直接以 JSON 条目为行源，无派生统计）。
	 */
	protected List<RowData> buildRows() {
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		IoItemDeriver.DerivedIo derived = IoItemDeriver.derive(pairs);
		List<IoItemDeriver.IoItemStat> stats = isInput ? derived.inputs() : derived.outputs();
		// 排序：启用数降序 → 禁用数降序 → 物品 id 字母序（派生类保持出现顺序，排序由本控件负责）
		Comparator<IoItemDeriver.IoItemStat> byEnabled = Comparator.comparingInt(IoItemDeriver.IoItemStat::enabledCount)
				.reversed();
		Comparator<IoItemDeriver.IoItemStat> byDisabled = Comparator
				.comparingInt(IoItemDeriver.IoItemStat::disabledCount).reversed();
		Comparator<IoItemDeriver.IoItemStat> byId = Comparator.comparing(s -> ItemStringHelper.getItemId(s.item()));
		stats.sort(byEnabled.thenComparing(byDisabled).thenComparing(byId));

		// 每行按 (item, 方向) 匹配已保存条目（ItemIOList.findByKey 语义：首个匹配）；未命中使用占位条目
		// （enabled=true、0 0 0、阈值 1、单次 6，与计划 todo 5 的占位默认一致）
		String ioJson = Configs.Generic.ITEM_IO.getStringValue();
		List<ItemIO> ioItems = ItemIOList.fromJson(ioJson);
		List<RowData> rows = new ArrayList<>(stats.size());
		for (IoItemDeriver.IoItemStat stat : stats) {
			int index = ItemIOList.findByKey(ioJson, stat.item(), isInput);
			ItemIO entry = index >= 0
					? ioItems.get(index)
					: new ItemIO(stat.item(), isInput, DEFAULT_X, DEFAULT_Y, DEFAULT_Z, DEFAULT_THRESHOLD,
							DEFAULT_TAKE_AMOUNT);
			rows.add(new RowData(stat.item(), stat, entry, isInput));
		}
		return rows;
	}

	/**
	 * 将指定物品的行滚动到可视区顶部（供交易对页点击图标跳转定位；行不存在时静默跳过）。 滚动条值 = 行下标；先抬高 maxValue 再
	 * setValue，避免首次绘制前被默认 maxValue 钳制（与 GuiConfigs.pendingScrollRestore 手法一致）
	 */
	public void scrollToItem(String item) {
		for (int i = 0; i < rowData.size(); i++) {
			if (rowData.get(i).item().equals(item)) {
				GuiScrollBar sb = this.getScrollbar();
				if (sb != null) {
					sb.setMaxValue(i);
					sb.setValue(i);
				}
				return;
			}
		}
	}

	@Override
	protected Collection<ConfigOptionWrapper> getAllEntries() {
		// 每次刷新重新派生（交易对/条目变化后无需缓存同步）
		rowData = buildRows();
		if (rowData.isEmpty()) {
			// 空态：派生集为空（无交易对或该方向无物品）时渲染提示行（正式键 autotrade.gui.item_io.empty）
			return ImmutableList.of(new ConfigOptionWrapper(StringUtils.translate("autotrade.gui.item_io.empty")));
		}
		List<ConfigOptionWrapper> wrappers = new ArrayList<>(rowData.size());
		for (int i = 0; i < rowData.size(); i++) {
			wrappers.add(new ConfigOptionWrapper(new ConfigString(ItemIOEntryWidget.ROW_NAME_PREFIX + i, "", "")));
		}
		return wrappers;
	}

	@Override
	protected int getBrowserEntryHeightFor(ConfigOptionWrapper entry) {
		return ENTRY_HEIGHT;
	}

	@Override
	protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
			ConfigOptionWrapper wrapper) {
		String name = wrapper.getConfig() != null ? wrapper.getConfig().getName() : null;
		if (name != null && name.startsWith(ItemIOEntryWidget.ROW_NAME_PREFIX)) {
			// 派生行：委托 ItemIOEntryWidget 渲染；提交（保存）后仅刷新本列表（不重建宿主屏，避免焦点丢失）
			RowData row = rowData.get(listIndex);
			ItemIOEntryWidget widget = new ItemIOEntryWidget(x, y, this.browserEntryWidth, ENTRY_HEIGHT,
					this.maxLabelWidth, this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this,
					row.item(), row.isInput(), row.entry(), row.stat(), this::refreshEntries);
			// 两阶段初始化：基类构造函数在 super() 链中调用 addConfigOption 时本控件字段尚未赋值（仅缓存参数），
			// 构造完成后在此重放行布局，避免解引用 null 字段的 NPE
			widget.initRowLayout();
			return widget;
		}
		// 空态标签行：使用默认行渲染
		return new WidgetConfigOption(x, y, this.browserEntryWidth, ENTRY_HEIGHT, this.maxLabelWidth, this.configWidth,
				wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
	}
}
