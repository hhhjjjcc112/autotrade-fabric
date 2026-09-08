package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
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
 * （findByKey 语义），未命中使用占位条目（enabled=true、无位置记录、阈值 1、单次 6）；派生集为空时渲染空态提示行。
 * </p>
 *
 * <p>
 * 方案 B（拆分可变高行为固定高条目）：每个物品展开为 1 个头部占位 + locations.size() 个记录占位，全部固定 20px
 * 行高（{@link ItemIOBaseWidget#HEADER_HEIGHT}/{@link ItemIOBaseWidget#RECORD_HEIGHT}）。旧实现把单个物品渲染为
 * 一条可变高行（20 + 20×记录数），记录数过多时行高超过列表视口，malilib
 * {@code WidgetListBase.createListEntryWidgetIfSpace} 的空间判定（usedHeight + height
 * &gt; usableHeight 返回 null） 会拒绝该条目并 break 整列表，导致列表整页空白；拆分后空间判定永不拒绝条目，根治空白问题。
 * </p>
 *
 * <p>
 * 本控件自给自足：行数据与条目列表由本类构建（覆写 {@link #getAllEntries}），父屏只需在 createListWidget
 * 中实例化并传入方向；每次列表刷新都会重新派生（交易对/条目变化无需缓存同步）。 行渲染委托
 * {@link ItemIOHeaderWidget}（头部行）/ {@link ItemIORecordWidget}（单条记录行）（含
 * Enter/失焦提交机制）。
 * </p>
 */
public class ItemIOTabList extends WidgetListConfigOptions {
	/** 空态提示行高（固定 40；派生行全部固定 20px，与空态行解耦） */
	public static final int ENTRY_HEIGHT = 40;
	/** 未命中已保存条目时占位条目的默认值：无位置记录（0 记录）、阈值 1、单次 6、启用 */
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
	/** 重建前聚焦文本框的定位身份（行物品编码串 + 字段引用：种类 + 记录下标），重建后恢复焦点；null = 无聚焦 */
	private String focusRowItem;
	private ItemIOBaseWidget.FieldRef focusFieldRef;

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

	/** 重建前记录聚焦文本框身份（行物品 + 字段引用：种类 + 记录下标）；无聚焦时记录为空 */
	private void captureFocusState() {
		this.focusRowItem = null;
		this.focusFieldRef = null;
		for (WidgetConfigOption widget : this.listWidgets) {
			if (widget instanceof ItemIOBaseWidget ioWidget) {
				ItemIOBaseWidget.FieldRef ref = ioWidget.getFocusedFieldRef();
				if (ref != null) {
					this.focusRowItem = ioWidget.getItem();
					this.focusFieldRef = ref;
					return;
				}
			}
		}
	}

	/**
	 * 重建后按（行物品, 字段引用）恢复聚焦文本框；行已不存在时静默跳过（记录下标越界时控件内已静默回落）。 同物品展开为头部 +
	 * 多条记录控件：逐个尝试匹配控件的 focusField（不匹配的字段种类/越界记录下标在控件内 静默跳过），保证记录字段能落到对应记录控件上（D2
	 * 修复在拆分后的等价实现）。
	 */
	private void restoreFocusState() {
		if (this.focusRowItem == null || this.focusFieldRef == null) {
			return;
		}
		for (WidgetConfigOption widget : this.listWidgets) {
			if (widget instanceof ItemIOBaseWidget ioWidget && ioWidget.getItem().equals(this.focusRowItem)) {
				ioWidget.focusField(this.focusFieldRef);
			}
		}
	}

	/**
	 * 构建行数据：派生 + 排序 + (item, 方向) 匹配。子类可覆写以替换行源（如旧的独立列表屏直接以 JSON 条目为行源，无派生统计）。
	 */
	protected List<RowData> buildRows() {
		// 缓存访问器：派生只读（IoItemDeriver 仅 get 交易对字段，不改动）
		List<TradePair> pairs = TradePairCache.getAll();
		IoItemDeriver.DerivedIo derived = IoItemDeriver.derive(pairs);
		List<IoItemDeriver.IoItemStat> stats = isInput ? derived.inputs() : derived.outputs();
		// 排序：启用数降序 → 禁用数降序 → 物品 id 字母序（派生类保持出现顺序，排序由本控件负责）
		Comparator<IoItemDeriver.IoItemStat> byEnabled = Comparator.comparingInt(IoItemDeriver.IoItemStat::enabledCount)
				.reversed();
		Comparator<IoItemDeriver.IoItemStat> byDisabled = Comparator
				.comparingInt(IoItemDeriver.IoItemStat::disabledCount).reversed();
		Comparator<IoItemDeriver.IoItemStat> byId = Comparator.comparing(s -> ItemStringHelper.getItemId(s.item()));
		stats.sort(byEnabled.thenComparing(byDisabled).thenComparing(byId));

		// 每行按 (item, 方向) 匹配已保存条目（ItemIOCache.findByKey 语义：首个匹配）；未命中使用占位条目
		// （无位置记录 = 0 记录、阈值 1、单次 6，与计划 todo 5 的占位默认一致）
		List<ItemIO> ioItems = ItemIOCache.getAll();
		List<RowData> rows = new ArrayList<>(stats.size());
		for (IoItemDeriver.IoItemStat stat : stats) {
			int index = ItemIOCache.findByKey(ioItems, stat.item(), isInput);
			ItemIO entry = index >= 0
					? new ItemIO(ioItems.get(index))
					: new ItemIO(stat.item(), isInput, List.of(), DEFAULT_THRESHOLD, DEFAULT_TAKE_AMOUNT);
			rows.add(new RowData(stat.item(), stat, entry, isInput));
		}
		return rows;
	}

	/**
	 * 将指定物品的行滚动到可视区顶部（供交易对页点击图标跳转定位；行不存在时静默跳过）。 滚动条值 = 展开后的条目下标 （头部行 + 记录行独立条目）：遍历
	 * rowData 累计偏移（每物品 1 个头部 + locations.size() 个记录）， 命中物品后先抬高 maxValue 再
	 * setValue，避免首次绘制前被默认 maxValue 钳制（与 GuiConfigs.pendingScrollRestore 手法一致）
	 */
	public void scrollToItem(String item) {
		int rowOffset = 0;
		for (RowData row : rowData) {
			if (row.item().equals(item)) {
				GuiScrollBar sb = this.getScrollbar();
				if (sb != null) {
					sb.setMaxValue(rowOffset);
					sb.setValue(rowOffset);
				}
				return;
			}
			rowOffset += 1 + row.entry().getLocations().size();
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
		// 每个物品展开为 1 个头部占位 + locations.size() 个记录占位（全部固定 20px 行高，
		// 根治旧可变高行超过视口导致列表整页空白的问题）
		List<ConfigOptionWrapper> wrappers = new ArrayList<>(rowData.size());
		for (int i = 0; i < rowData.size(); i++) {
			wrappers.add(new ConfigOptionWrapper(new ConfigString(ItemIOBaseWidget.HEADER_NAME_PREFIX + i, "", "")));
			int recordCount = rowData.get(i).entry().getLocations().size();
			for (int j = 0; j < recordCount; j++) {
				wrappers.add(new ConfigOptionWrapper(
						new ConfigString(ItemIOBaseWidget.RECORD_NAME_PREFIX + i + "_" + j, "", "")));
			}
		}
		return wrappers;
	}

	/**
	 * 按占位配置名返回固定行高：头部行/记录行各 20px（与控件常量一致），空态提示行返回 ENTRY_HEIGHT。 基类
	 * createListEntryWidgetIfSpace 逐条取本方法高度并顺序累计 y 定位，滚动条按展开后的条目索引。
	 */
	@Override
	protected int getBrowserEntryHeightFor(ConfigOptionWrapper entry) {
		String name = entry.getConfig() != null ? entry.getConfig().getName() : null;
		if (name != null && name.startsWith(ItemIOBaseWidget.HEADER_NAME_PREFIX)) {
			return ItemIOBaseWidget.HEADER_HEIGHT;
		}
		if (name != null && name.startsWith(ItemIOBaseWidget.RECORD_NAME_PREFIX)) {
			return ItemIOBaseWidget.RECORD_HEIGHT;
		}
		return ENTRY_HEIGHT;
	}

	@Override
	protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
			ConfigOptionWrapper wrapper) {
		String name = wrapper.getConfig() != null ? wrapper.getConfig().getName() : null;
		if (name != null && name.startsWith(ItemIOBaseWidget.HEADER_NAME_PREFIX)) {
			// 头部行：占位名 = HEADER_NAME_PREFIX + 行下标（listIndex 是展开后的条目下标，不能直接用作行下标）
			int i = Integer.parseInt(name.substring(ItemIOBaseWidget.HEADER_NAME_PREFIX.length()));
			RowData row = rowData.get(i);
			ItemIOHeaderWidget widget = new ItemIOHeaderWidget(x, y, this.browserEntryWidth,
					ItemIOBaseWidget.HEADER_HEIGHT, this.maxLabelWidth, this.configWidth, wrapper, listIndex,
					(IKeybindConfigGui) this.parent, this, row.item(), row.isInput(), row.entry(), row.stat(),
					this::refreshEntries);
			// 两阶段初始化：基类构造函数在 super() 链中调用 addConfigOption 时本控件字段尚未赋值（仅缓存参数），
			// 构造完成后在此重放行布局，避免解引用 null 字段的 NPE
			widget.initRowLayout();
			return widget;
		}
		if (name != null && name.startsWith(ItemIOBaseWidget.RECORD_NAME_PREFIX)) {
			// 记录行：占位名 = RECORD_NAME_PREFIX + 行下标 + "_" + 记录下标（listIndex 是展开后的条目下标，
			// 不能直接用作行下标/记录下标）
			String suffix = name.substring(ItemIOBaseWidget.RECORD_NAME_PREFIX.length());
			int sep = suffix.indexOf('_');
			int i = Integer.parseInt(suffix.substring(0, sep));
			int j = Integer.parseInt(suffix.substring(sep + 1));
			RowData row = rowData.get(i);
			ItemIORecordWidget widget = new ItemIORecordWidget(x, y, this.browserEntryWidth,
					ItemIOBaseWidget.RECORD_HEIGHT, this.maxLabelWidth, this.configWidth, wrapper, listIndex,
					(IKeybindConfigGui) this.parent, this, row.item(), row.isInput(), row.entry(), row.stat(),
					this::refreshEntries, j);
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