package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 物品 IO 派生行控件：渲染单个 (item, 方向) 的 IO 配置行，可变高度行布局（行高 = 20 + 20×记录数）： 头部行 = 条目级
 * [开/关] 状态文本（最左）+ 物品预览 icon + 「启用 X · 禁用 Y」计数标签（放不下跳过）+ 行级「启用/禁用」 总开关按钮 + 右侧
 * [阈值] 输入框 + [每次拿取] 输入框（仅输入方向）右对齐 + [添加容器] 按钮（行尾）； 记录行 i = [序号][记录级 开/关][维度
 * 标签][维度 文本框（留空 = 任意维度）][坐标 文本框][抓取容器 按钮][启用/禁用 按钮][✕ 删除 按钮]（stat
 * 由选项卡层填入，本控件只负责渲染）。阈值/每次拿取以组为单位（1 组 = 1 槽位）。
 *
 * <p>
 * 保存路径统一走 {@link ItemIOCache#upsert}（按 (item, 方向) 更新或追加）并回写
 * {@code Configs.Generic.ITEM_IO}；行内文本框为「回车/失焦提交」：光标输入期间不触发保存与列表重建（Enter 由
 * {@link #onKeyTypedImpl} 处理，失焦由列表的 {@code applyPendingModifications} 路径处理），
 * 非法输入（维度格式不符 / 坐标格式不符 / 数量越界）恢复原值并提示，不写入。
 * </p>
 *
 * <p>
 * 本类替代旧独立列表屏 {@code CustomItemIOListWidget} 的行渲染逻辑（含旧 独立编辑屏的 grabContainer
 * 坐标语义与 ConfigCoordinate 解析护栏）。
 * </p>
 */
public class ItemIOEntryWidget extends WidgetConfigOption {
	/** 头部行高（状态文本/icon/统计/行级开关/数量块/添加按钮） */
	public static final int HEADER_HEIGHT = 20;
	/** 单条位置记录行高（序号/状态/维度/坐标/抓取/启用禁用/删除） */
	public static final int RECORD_HEIGHT = 20;
	/** 按记录数计算行高：头部 + 记录行（无底部行，添加按钮已上移头部行） */
	public static int heightFor(int recordCount) {
		return HEADER_HEIGHT + recordCount * RECORD_HEIGHT;
	}
	/** 列表默认/空态行高（固定 40；0 记录派生行高 = heightFor(0) = 20，与空态行解耦） */
	public static final int ENTRY_HEIGHT = 40;
	/**
	 * 行占位配置名前缀：父屏 getConfigs 用 {@code ROW_NAME_PREFIX + i} 命名占位
	 * ConfigString，本控件据此识别行
	 */
	public static final String ROW_NAME_PREFIX = "io_derived_";
	/** 行内文本框种类：列表重建后恢复焦点时用「行物品 + 种类 + 记录下标」定位字段 */
	public enum FieldKind {
		/** 头部阈值小数字框 */
		THRESHOLD,
		/** 头部单次数量小数字框 */
		TAKE_AMOUNT,
		/** 记录行维度文本框 */
		RECORD_DIM,
		/** 记录行 "x y z" 坐标文本框 */
		RECORD_COORD
	}
	/** 聚焦字段定位身份：种类 + 记录下标（recordIndex=-1 表示头部字段） */
	public record FieldRef(FieldKind kind, int recordIndex) {
	}
	/** 阈值/单次拿取的取值范围（单位：组 = 槽位数；上限 36 = 背包槽位，超过无实际意义） */
	private static final int MIN_AMOUNT = 1;
	private static final int MAX_AMOUNT = 36;
	/**
	 * 计数标签翻译键：正式键 autotrade.gui.item_io.stats（格式参数 %d/%d = 启用/禁用交易对数）
	 */
	private static final String STATS_KEY = "autotrade.gui.item_io.stats";
	/**
	 * 阈值/每次拿取字段的简写文本标签翻译键（输入框前显示，避免裸数字无含义；悬浮显示完整说明）
	 */
	private static final String THRESHOLD_SHORT_KEY = "autotrade.gui.item_io.threshold_short";
	private static final String TAKE_AMOUNT_SHORT_KEY = "autotrade.gui.item_io.take_amount_short";
	/** 阈值字段的悬浮完整说明翻译键（分方向：输入 = 补货，输出 = 清出；单位 = 组） */
	private static final String THRESHOLD_TIP_INPUT_KEY = "autotrade.gui.item_io.threshold_tip_input";
	private static final String THRESHOLD_TIP_OUTPUT_KEY = "autotrade.gui.item_io.threshold_tip_output";
	/** 每次拿取字段的悬浮完整说明翻译键（单位 = 组，仅输入方向） */
	private static final String TAKE_AMOUNT_TIP_KEY = "autotrade.gui.item_io.take_amount_tip";
	/**
	 * 启用状态指示文本翻译键：仅作状态展示（[开]/[关]），实际开关操作由「启用/禁用」按钮承担；
	 * 键值不携带颜色代码，渲染时按启用状态直接选色（STATUS_ON_COLOR/STATUS_OFF_COLOR）
	 */
	private static final String STATUS_ON_KEY = "autotrade.gui.item_io.status_on";
	private static final String STATUS_OFF_KEY = "autotrade.gui.item_io.status_off";
	/** 状态文本颜色：启用绿 / 禁用红（样式同交易对列表状态文本） */
	private static final int STATUS_ON_COLOR = 0xFF55FF55;
	private static final int STATUS_OFF_COLOR = 0xFFFF5555;
	/** 条目级状态文本悬浮提示翻译键（区分层级：条目级总开关） */
	private static final String STATUS_TIP_ENTRY_ON_KEY = "autotrade.gui.item_io.status_tip_entry_on";
	private static final String STATUS_TIP_ENTRY_OFF_KEY = "autotrade.gui.item_io.status_tip_entry_off";
	/** 记录级状态文本悬浮提示翻译键（与条目级开关 AND 生效） */
	private static final String STATUS_TIP_RECORD_ON_KEY = "autotrade.gui.item_io.status_tip_record_on";
	private static final String STATUS_TIP_RECORD_OFF_KEY = "autotrade.gui.item_io.status_tip_record_off";
	/** 启停按钮悬浮提示翻译键（动作语义：点击后发生什么，消除「按钮显示的是当前状态还是动作」歧义） */
	private static final String TOGGLE_BTN_TIP_ON_KEY = "autotrade.gui.item_io.toggle_btn_tip_on";
	private static final String TOGGLE_BTN_TIP_OFF_KEY = "autotrade.gui.item_io.toggle_btn_tip_off";
	private static final String REC_TOGGLE_BTN_TIP_ON_KEY = "autotrade.gui.item_io.rec_toggle_btn_tip_on";
	private static final String REC_TOGGLE_BTN_TIP_OFF_KEY = "autotrade.gui.item_io.rec_toggle_btn_tip_off";
	/** 记录行序号翻译键（格式参数 %d = 记录下标+1，灰色渲染；序号使记录级状态与头部条目级状态错位，表达层级从属） */
	private static final String RECORD_NUMBER_KEY = "autotrade.gui.item_io.record_number";
	/** 记录行序号颜色（灰色，弱于主内容） */
	private static final int RECORD_NUM_COLOR = 0xFFAAAAAA;
	/** 记录行维度标签简写翻译键（悬浮显示完整说明） */
	private static final String DIMENSION_SHORT_KEY = "autotrade.gui.item_io.dimension_short";
	/** 记录行维度标签悬浮完整说明翻译键 */
	private static final String DIMENSION_TIP_KEY = "autotrade.gui.item_io.dimension_tip";
	/** 记录行抓取容器按钮简写翻译键（悬浮显示完整说明） */
	private static final String GRAB_CONTAINER_SHORT_KEY = "autotrade.gui.item_io.grab_container_short";
	/** 抓取容器按钮悬浮完整说明翻译键 */
	private static final String GRAB_CONTAINER_TIP_KEY = "autotrade.gui.item_io.grab_container_tip";
	/** 记录行删除按钮翻译键（✕，点击即删除该记录并保存，无确认弹窗） */
	private static final String DELETE_KEY = "autotrade.gui.item_io.delete";
	/** 删除按钮悬浮提示翻译键 */
	private static final String DELETE_TIP_KEY = "autotrade.gui.item_io.delete_tip";
	/** 头部行「添加容器」按钮翻译键（原底部行按钮上移；点击新增一条记录） */
	private static final String ADD_LOCATION_KEY = "autotrade.gui.item_io.add_location";
	/** 添加容器按钮悬浮提示翻译键 */
	private static final String ADD_LOCATION_TIP_KEY = "autotrade.gui.item_io.add_location_tip";
	/** 非法维度提示翻译键（非空且无法解析为 Identifier 时提示并恢复原值） */
	private static final String INVALID_DIMENSION_KEY = "autotrade.message.invalid_dimension";
	/** 计数标签正常色（有启用交易对使用该物品时） */
	private static final int STATS_NORMAL_COLOR = 0xFFAAAAAA;
	/** 计数标签「当前不生效」高亮色（X=0 时使用，提示该物品未被任何启用交易对使用） */
	private static final int STATS_INACTIVE_COLOR = 0xFFAA5555;
	/** 「当前不生效」悬浮提示翻译键（X=0 时悬浮显示，缺失时渲染键名） */
	private static final String STATS_INACTIVE_HINT_KEY = "autotrade.gui.item_io.inactive_hint";

	private final String item;
	private final boolean isInput;
	private final ItemIO entry;
	private final IoItemDeriver.IoItemStat stat;
	private final Runnable onCommit;

	private GuiTextFieldGeneric thresholdField;
	private GuiTextFieldGeneric takeAmountField;
	/** 各记录行的维度文本框（按下标与 locations 对齐；仅布局期填充） */
	private final List<GuiTextFieldGeneric> recordDimFields = new ArrayList<>();
	/** 各记录行的坐标文本框（按下标与 locations 对齐；仅布局期填充） */
	private final List<GuiTextFieldGeneric> recordCoordFields = new ArrayList<>();
	/** 最近一次已提交的头部文本框内容（用于判断是否有未提交修改） */
	private String committedThresholdText = "";
	private String committedTakeAmountText = "";
	/** 最近一次已提交的各记录维度/坐标文本框内容（按下标与 locations 对齐） */
	private final List<String> committedDimTexts = new ArrayList<>();
	private final List<String> committedCoordTexts = new ArrayList<>();
	private final List<GuiTextFieldGeneric> textFields = new ArrayList<>();
	/**
	 * 布局参数缓存（两阶段初始化）：基类 {@code WidgetConfigOption} 构造函数在 super() 链中会调用本类的
	 * {@link #addConfigOption} 覆写方法，此时本类字段（item/isInput/entry/stat/onCommit）尚未赋值（仍为
	 * JVM 默认值 null/false），直接执行布局会解引用 null 的 entry 而崩溃（经典「基类构造调用覆写方法」陷阱）。
	 * 故构造期调用只缓存参数并立即返回，待构造函数完成、字段赋值后由 {@link #initRowLayout()} 用缓存参数重放布局。
	 */
	private int layoutX;
	private int layoutY;
	private float layoutZLevel;
	private int layoutLabelWidth;
	private int layoutConfigWidth;
	private IConfigBase layoutConfig;
	/** 布局是否尚未执行：构造期缓存后为 true，initRowLayout 重放布局后置 false */
	private boolean layoutPending = true;

	/**
	 * @param item
	 *            物品编码串（ItemStringHelper 格式）
	 * @param isInput
	 *            行方向：true = 输入（give ∪ give2），false = 输出（get）
	 * @param entry
	 *            当前条目（由调用方按 (item, 方向) 匹配或构造占位条目，本控件读写其值并 upsert）
	 * @param stat
	 *            派生统计（可为 null：旧列表屏行无派生统计，不渲染计数标签）
	 * @param onCommit
	 *            提交（保存）后执行的列表刷新回调（由列表控件注入，如 {@code () -> list.refreshEntries()}；
	 *            不得重建宿主屏，否则点击分发会中断在已脱离的旧控件上导致焦点丢失）
	 */
	public ItemIOEntryWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent, String item, boolean isInput, ItemIO entry,
			IoItemDeriver.IoItemStat stat, Runnable onCommit) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
		this.item = item;
		this.isInput = isInput;
		this.entry = entry;
		this.stat = stat;
		this.onCommit = onCommit;
	}

	/** 行物品编码串（派生行唯一标识，供列表重建后按行恢复焦点） */
	public String getItem() {
		return item;
	}

	/** 当前聚焦的字段定位身份（头部字段 recordIndex=-1）；无文本框聚焦时返回 null */
	public FieldRef getFocusedFieldRef() {
		if (thresholdField.isFocused())
			return new FieldRef(FieldKind.THRESHOLD, -1);
		// 输出行无「每次拿取」字段（takeAmountField 为 null）
		if (takeAmountField != null && takeAmountField.isFocused())
			return new FieldRef(FieldKind.TAKE_AMOUNT, -1);
		for (int i = 0; i < recordDimFields.size(); i++) {
			if (recordDimFields.get(i).isFocused())
				return new FieldRef(FieldKind.RECORD_DIM, i);
		}
		for (int i = 0; i < recordCoordFields.size(); i++) {
			if (recordCoordFields.get(i).isFocused())
				return new FieldRef(FieldKind.RECORD_COORD, i);
		}
		return null;
	}

	/** 聚焦指定字段（列表重建后恢复焦点用，光标置于行尾）；输出行请求 TAKE_AMOUNT 时静默跳过 */
	public void focusField(FieldRef ref) {
		if (ref == null)
			return;
		switch (ref.kind()) {
			case THRESHOLD -> focusText(thresholdField);
			// 输出行无「每次拿取」字段（takeAmountField 为 null），静默跳过（保留现有行为）
			case TAKE_AMOUNT -> focusText(takeAmountField);
			case RECORD_DIM -> focusRecordField(ref.recordIndex(), recordDimFields);
			case RECORD_COORD -> focusRecordField(ref.recordIndex(), recordCoordFields);
		}
	}

	/** 聚焦单个文本框（光标置于行尾）；字段不存在（null）时静默跳过 */
	private void focusText(GuiTextFieldGeneric field) {
		if (field == null)
			return;
		field.setFocused(true);
		field.setCursorPositionEnd();
	}

	/** 聚焦指定记录下标的字段；下标越界（如删除记录后重建）时静默回落头部：不聚焦任何记录字段，不报错 */
	private void focusRecordField(int index, List<GuiTextFieldGeneric> fields) {
		if (index < 0 || index >= fields.size())
			return;
		focusText(fields.get(index));
	}

	/**
	 * 重放行布局（两阶段初始化的第二阶段）：构造函数完成后由列表控件调用（此时 entry 等字段已赋值），
	 * 用构造期缓存的布局参数执行真正的行布局；布局已执行过或 entry 仍为 null 时静默跳过。
	 */
	public void initRowLayout() {
		if (this.entry != null && this.layoutPending) {
			this.addConfigOption(this.layoutX, this.layoutY, this.layoutZLevel, this.layoutLabelWidth,
					this.layoutConfigWidth, this.layoutConfig);
		}
	}

	@Override
	protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
		// 两阶段初始化（修复构造期 NPE）：基类 WidgetConfigOption 构造函数在 super() 链中调用本覆写方法时，
		// 本类字段（item/isInput/entry/stat/onCommit）尚未赋值（仍为 JVM 默认值 null/false），直接执行布局会
		// 解引用 null 的 entry 而崩溃。故先缓存全部布局参数并立即返回（构造期调用为 no-op），待构造函数完成、
		// 字段赋值后由 initRowLayout() 用缓存参数重放布局。
		this.layoutX = x;
		this.layoutY = y;
		this.layoutZLevel = zLevel;
		this.layoutLabelWidth = labelWidth;
		this.layoutConfigWidth = configWidth;
		this.layoutConfig = config;
		if (this.entry == null || !this.layoutPending) {
			return;
		}
		this.layoutPending = false;

		String name = config.getName();
		if (name == null || !name.startsWith(ROW_NAME_PREFIX)) {
			super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			return;
		}

		int gap = 4;
		int rightEdge = (this.x + this.width) - gap;
		int cx = x + 2;

		// ── 头部行（y+0..20）：条目级 [开/关] 状态文本（最左）+ 物品预览图标 + 统计文本（放得下才渲染）；
		// [阈值]/[每次拿取] 简写标签+输入框块右对齐 ──
		// 条目级状态文本：仅展示当前启用状态（绿色 [开]/红色 [关]），实际开关操作由「启用/禁用」按钮承担，
		// 悬浮显示完整说明（与按钮 hover 共同消除「按钮显示的是状态还是动作」歧义）
		String statusLabel = StringUtils.translate(entry.isEnabled() ? STATUS_ON_KEY : STATUS_OFF_KEY);
		int statusW = this.getStringWidth(statusLabel);
		int statusColor = entry.isEnabled() ? STATUS_ON_COLOR : STATUS_OFF_COLOR;
		String statusTipKey = entry.isEnabled() ? STATUS_TIP_ENTRY_ON_KEY : STATUS_TIP_ENTRY_OFF_KEY;
		this.addWidget(new HoverLabelWidget(cx, y + 6, statusLabel, statusColor, statusTipKey));
		cx += statusW + gap;

		// 物品预览图标（状态文本之后）
		ItemStack stack = ItemStringHelper.decode(item);
		if (!stack.isEmpty()) {
			this.addWidget(new ItemIconWidget(cx, y + 1, stack));
		}
		int iconEndX = cx + 22;
		// 字段文本标签：阈值两个方向都显示；每次拿取仅输入方向（输出方向无此概念，见 ContainerIOTask
		// transferLimit：输出固定 999 全量搬运，不读 takeAmount）；标签用简写，悬浮显示完整说明
		int numW = Math.min(60, Math.max(40, (rightEdge - iconEndX) * 12 / 100));
		String thresholdLabel = StringUtils.translate(THRESHOLD_SHORT_KEY);
		int threshLabelW = this.getStringWidth(thresholdLabel);
		String takeAmountLabel = null;
		int takeLabelW = 0;
		if (isInput) {
			takeAmountLabel = StringUtils.translate(TAKE_AMOUNT_SHORT_KEY);
			takeLabelW = this.getStringWidth(takeAmountLabel);
		}
		// 右侧块（右对齐到行尾）：[阈值 标签+输入框] + [每次拿取 标签+输入框]（每次拿取仅输入行）+ [添加容器 按钮]
		// 阈值/每次拿取以组为单位（1 组 = 1 槽位）
		String addLabelText = StringUtils.translate(ADD_LOCATION_KEY);
		int addW = Math.min(64, Math.max(50, this.getStringWidth(addLabelText) + 10));
		int takeBlockW = takeAmountLabel != null ? takeLabelW + 2 + numW : 0;
		int rightBlockW = threshLabelW + 2 + numW + takeBlockW + gap + addW;
		int blockX = rightEdge - rightBlockW;
		// 统计文本：位于图标之后、右侧块之前，放得下才渲染（X=0 时高亮提示「当前不生效」）
		int statsEndX = iconEndX;
		if (stat != null) {
			String statsText = StringUtils.translate(STATS_KEY, stat.enabledCount(), stat.disabledCount());
			boolean inactive = stat.enabledCount() == 0;
			if (iconEndX + this.getStringWidth(statsText) + gap <= blockX) {
				this.addWidget(new CountLabelWidget(iconEndX, y + 6, statsText, inactive));
				statsEndX = iconEndX + this.getStringWidth(statsText);
			}
		}

		// 行级「启用/禁用」总开关按钮（统计文本之后）：宽度按剩余空间钳制，防止与右侧块重叠，
		// 按钮显示「点击后执行的动作」（条目当前启用时显示「禁用」、禁用时显示「启用」），hover 补当前状态
		int toggleW = Math.min(70, Math.max(40, (blockX - statsEndX) * 14 / 100));
		toggleW = Math.min(toggleW, Math.max(40, blockX - statsEndX - gap));
		String toggleLabel = StringUtils
				.translate(entry.isEnabled() ? "autotrade.gui.item_io.disabled" : "autotrade.gui.item_io.enabled");
		String toggleTipKey = entry.isEnabled() ? TOGGLE_BTN_TIP_ON_KEY : TOGGLE_BTN_TIP_OFF_KEY;
		ButtonGeneric toggleBtn = new ButtonGeneric(statsEndX + gap, y, toggleW, 20, toggleLabel);
		toggleBtn.setHoverStrings(toggleTipKey);
		this.addButton(toggleBtn, (button, mouseButton) -> {
			entry.setEnabled(!entry.isEnabled());
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});

		// 阈值标签（简写 + 悬浮完整说明，按方向区分补货/清出语义）+ 输入框：范围 1..36 组，Enter/失焦提交
		cx = blockX;
		String thresholdTipKey = isInput ? THRESHOLD_TIP_INPUT_KEY : THRESHOLD_TIP_OUTPUT_KEY;
		this.addWidget(new HoverLabelWidget(cx, y + 6, thresholdLabel, 0xFFFFFFFF, thresholdTipKey));
		cx += threshLabelW + 2;
		thresholdField = this.createTextField(cx, y + 1, numW - 4, 17);
		thresholdField.setMaxLength(8);
		thresholdField.setText(String.valueOf(entry.getThreshold()));
		committedThresholdText = thresholdField.getText();
		registerField(thresholdField);
		cx += numW;

		// 每次拿取标签（简写 + 悬浮完整说明）+ 输入框（仅输入方向；输出方向不渲染该字段，takeAmountField 保持 null）
		if (takeAmountLabel != null) {
			cx += gap;
			this.addWidget(new HoverLabelWidget(cx, y + 6, takeAmountLabel, 0xFFFFFFFF, TAKE_AMOUNT_TIP_KEY));
			cx += takeLabelW + 2;
			takeAmountField = this.createTextField(cx, y + 1, numW - 4, 17);
			takeAmountField.setMaxLength(8);
			takeAmountField.setText(String.valueOf(entry.getTakeAmount()));
			committedTakeAmountText = takeAmountField.getText();
			registerField(takeAmountField);
			cx += numW;
		}
		cx += gap;

		// 添加容器按钮（头部行行尾，原底部行按钮上移）：新增一条记录（维度 = 当前维度，坐标 0 0 0 占位，不触发 IO），即时生效并保存
		ButtonGeneric addBtn = new ButtonGeneric(cx, y, addW, 20, addLabelText);
		addBtn.setHoverStrings(ADD_LOCATION_TIP_KEY);
		this.addButton(addBtn, (button, mouseButton) -> {
			String dim = ContainerIOHelper.currentDimensionId(MinecraftClient.getInstance());
			entry.getLocations().add(new ItemIOLocation(dim != null ? dim : "", 0, 0, 0, true));
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});

		// ── 记录行 i（y+20+20i）：[序号][记录级 开/关][维度 简写标签+文本框][坐标 文本框][抓取容器][启用/禁用][✕ 删除] ──
		// 宽度按可用宽度比例计算并钳制（沿用旧第二行 row2AvailableW 比例手法），坐标框吃剩余宽度，
		// 小窗口下保证不溢出滚动条区域；序号 + 记录级状态文本使记录状态与头部条目级状态错位（层级从属视觉）
		List<ItemIOLocation> locations = entry.getLocations();
		int recordRowW = rightEdge - (x + 2);
		String dimLabel = StringUtils.translate(DIMENSION_SHORT_KEY);
		int dimLabelW = this.getStringWidth(dimLabel);
		int dimW = Math.min(80, Math.max(40, recordRowW * 14 / 100));
		int grabW = Math.min(90, Math.max(50, recordRowW * 18 / 100));
		int recToggleW = Math.min(70, Math.max(40, recordRowW * 14 / 100));
		int delW = Math.min(32, Math.max(18, recordRowW * 6 / 100));
		for (int i = 0; i < locations.size(); i++) {
			ItemIOLocation loc = locations.get(i);
			int rowY = y + HEADER_HEIGHT + i * RECORD_HEIGHT;
			int rc = x + 2;

			// 记录序号（灰色，最左）：记录编号（删除后自动重排），使后续记录级状态与头部条目级状态错位
			String numText = StringUtils.translate(RECORD_NUMBER_KEY, i + 1);
			int numTextW = this.getStringWidth(numText);
			this.addWidget(new HoverLabelWidget(rc, rowY + 6, numText, RECORD_NUM_COLOR, null));
			rc += numTextW + gap;

			// 记录级 [开/关] 状态文本（紧随序号，仅展示该记录启用状态）：实际开关操作由行尾「启用/禁用」按钮
			// 承担（与条目级开关 AND 生效），悬浮显示层级说明
			String recStatusLabel = StringUtils.translate(loc.isEnabled() ? STATUS_ON_KEY : STATUS_OFF_KEY);
			int recStatusW = this.getStringWidth(recStatusLabel);
			int recStatusColor = loc.isEnabled() ? STATUS_ON_COLOR : STATUS_OFF_COLOR;
			String recStatusTipKey = loc.isEnabled() ? STATUS_TIP_RECORD_ON_KEY : STATUS_TIP_RECORD_OFF_KEY;
			this.addWidget(new HoverLabelWidget(rc, rowY + 6, recStatusLabel, recStatusColor, recStatusTipKey));
			rc += recStatusW + gap;

			// 维度标签（简写 + 悬浮完整说明）+ 文本框：留空 = 任意维度（兼容旧配置），非空必须为可解析的维度 id，Enter/失焦提交
			this.addWidget(new HoverLabelWidget(rc, rowY + 6, dimLabel, 0xFFFFFFFF, DIMENSION_TIP_KEY));
			rc += dimLabelW + 2;
			GuiTextFieldGeneric dimField = this.createTextField(rc, rowY + 1, dimW - 4, 17);
			dimField.setMaxLength(64);
			dimField.setText(loc.getDimension());
			committedDimTexts.add(dimField.getText());
			recordDimFields.add(dimField);
			registerField(dimField);
			rc += dimW + gap;

			// 坐标文本框：吃剩余宽度（下限 60，修复旧 max(120) 在窄窗口下整体溢出的缺陷），
			// ConfigCoordinate 校验语义，Enter/失焦提交（输入期间不保存不重建）
			int coordW = Math.max(60, recordRowW - (numTextW + gap + recStatusW + gap + dimLabelW + 2 + dimW + gap
					+ grabW + gap + recToggleW + gap + delW));
			GuiTextFieldGeneric coordField = this.createTextField(rc, rowY + 1, coordW - 4, 17);
			coordField.setMaxLength(48);
			coordField.setText(String.format("%d %d %d", loc.getX(), loc.getY(), loc.getZ()));
			committedCoordTexts.add(coordField.getText());
			recordCoordFields.add(coordField);
			registerField(coordField);
			rc += coordW + gap;

			// 抓取容器按钮（简写 + 悬浮完整说明）：写入玩家脚下方块坐标 + 当前维度（world 非空才写维度），即时生效并保存
			final int idx = i;
			ButtonGeneric grabBtn = new ButtonGeneric(rc, rowY, grabW, 20,
					StringUtils.translate(GRAB_CONTAINER_SHORT_KEY));
			grabBtn.setHoverStrings(GRAB_CONTAINER_TIP_KEY);
			this.addButton(grabBtn, (button, mouseButton) -> {
				BlockPos pos = grabFootBlockPos();
				if (pos == null)
					return;
				ItemIOLocation target = entry.getLocations().get(idx);
				target.setX(pos.getX());
				target.setY(pos.getY());
				target.setZ(pos.getZ());
				String dim = ContainerIOHelper.currentDimensionId(MinecraftClient.getInstance());
				if (dim != null)
					target.setDimension(dim);
				saveEntry();
				if (onCommit != null)
					onCommit.run();
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.item_io_container_set",
						dim, pos.getX(), pos.getY(), pos.getZ());
			});
			rc += grabW + gap;

			// 记录启用/禁用按钮（hover 补当前状态与 AND 语义）：写入该记录 enabled（与行级总开关 AND 生效），即时生效并保存
			String recToggleLabel = StringUtils
					.translate(loc.isEnabled() ? "autotrade.gui.item_io.disabled" : "autotrade.gui.item_io.enabled");
			ButtonGeneric recToggleBtn = new ButtonGeneric(rc, rowY, recToggleW, 20, recToggleLabel);
			recToggleBtn.setHoverStrings(loc.isEnabled() ? REC_TOGGLE_BTN_TIP_ON_KEY : REC_TOGGLE_BTN_TIP_OFF_KEY);
			this.addButton(recToggleBtn, (button, mouseButton) -> {
				ItemIOLocation target = entry.getLocations().get(idx);
				target.setEnabled(!target.isEnabled());
				saveEntry();
				if (onCommit != null)
					onCommit.run();
			});
			rc += recToggleW + gap;

			// 删除按钮（✕，悬浮说明）：点击即删除该记录并保存（无确认弹窗，与行级按钮即时生效风格一致）
			ButtonGeneric delBtn = new ButtonGeneric(rc, rowY, delW, 20, StringUtils.translate(DELETE_KEY));
			delBtn.setHoverStrings(DELETE_TIP_KEY);
			this.addButton(delBtn, (button, mouseButton) -> {
				entry.getLocations().remove(idx);
				saveEntry();
				if (onCommit != null)
					onCommit.run();
			});
		}
	}

	@Override
	public boolean hasPendingModifications() {
		// 任一文本框内容与最近一次提交值不同即视为有待提交修改（覆盖基类仅主字段的判断）；
		// 输出行无「每次拿取」字段（takeAmountField 为 null），跳过该项比较；记录字段按下标逐条比较
		if (!thresholdField.getText().equals(committedThresholdText)
				|| (takeAmountField != null && !takeAmountField.getText().equals(committedTakeAmountText))) {
			return true;
		}
		for (int i = 0; i < recordDimFields.size(); i++) {
			if (!recordDimFields.get(i).getText().equals(committedDimTexts.get(i)))
				return true;
		}
		for (int i = 0; i < recordCoordFields.size(); i++) {
			if (!recordCoordFields.get(i).getText().equals(committedCoordTexts.get(i)))
				return true;
		}
		return false;
	}

	@Override
	public void applyNewValueToConfig() {
		if (!hasPendingModifications())
			return;
		boolean changed = false;

		// 逐记录校验：维度（空串 = 任意维度合法；非空必须可解析为 Identifier）+ 坐标（ConfigCoordinate 语义），
		// 非法输入恢复原值并提示一次，不写入
		for (int i = 0; i < recordDimFields.size(); i++) {
			ItemIOLocation loc = entry.getLocations().get(i);
			GuiTextFieldGeneric dimField = recordDimFields.get(i);
			String dimText = dimField.getText().trim();
			if (!dimText.isEmpty() && Identifier.tryParse(dimText) == null) {
				dimField.setText(committedDimTexts.get(i));
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, INVALID_DIMENSION_KEY);
			} else if (!dimText.equals(committedDimTexts.get(i).trim())) {
				loc.setDimension(dimText);
				changed = true;
			}
			GuiTextFieldGeneric coordField = recordCoordFields.get(i);
			String coordText = coordField.getText().trim();
			BlockPos pos = ConfigCoordinate.parse(coordText);
			if (pos == null) {
				coordField.setText(committedCoordTexts.get(i));
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_pos");
			} else if (!coordText.equals(committedCoordTexts.get(i).trim())) {
				loc.setX(pos.getX());
				loc.setY(pos.getY());
				loc.setZ(pos.getZ());
				changed = true;
			}
		}

		// 阈值/单次数量（行级，不随记录拆分）：非法输入回退已保存值，越界钳制到 1..2304（与旧
		// ConfigInteger(1, 2304) 语义一致）；输出行无「每次拿取」字段，仅处理阈值
		int threshold = parseAmount(thresholdField.getText(), entry.getThreshold());
		if (threshold != entry.getThreshold()) {
			entry.setThreshold(threshold);
			changed = true;
		}
		if (takeAmountField != null) {
			int takeAmount = parseAmount(takeAmountField.getText(), entry.getTakeAmount());
			if (takeAmount != entry.getTakeAmount()) {
				entry.setTakeAmount(takeAmount);
				changed = true;
			}
		}
		// 用已保存值回写输入框（非法输入被替换为原值），并刷新提交快照
		thresholdField.setText(String.valueOf(entry.getThreshold()));
		if (takeAmountField != null) {
			takeAmountField.setText(String.valueOf(entry.getTakeAmount()));
		}
		committedThresholdText = thresholdField.getText();
		if (takeAmountField != null) {
			committedTakeAmountText = takeAmountField.getText();
		}
		for (int i = 0; i < recordDimFields.size(); i++) {
			committedDimTexts.set(i, recordDimFields.get(i).getText());
		}
		for (int i = 0; i < recordCoordFields.size(); i++) {
			committedCoordTexts.set(i, recordCoordFields.get(i).getText());
		}

		if (changed) {
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		}
	}

	@Override
	public boolean wasConfigModified() {
		// 仅按真实待提交修改判定（占位配置的 initialStringValue 与行文本无关，不能用于比较）
		return this.hasPendingModifications();
	}

	@Override
	public boolean onKeyTypedImpl(int keyCode, int scanCode, int modifiers) {
		// Enter 提交：任一文本框聚焦时触发提交（基类仅处理主文本框，这里覆盖全部文本框）
		if (keyCode == KeyCodes.KEY_ENTER && isAnyFieldFocused()) {
			this.applyNewValueToConfig();
			return true;
		}
		// 其余按键分发给聚焦的文本框（基类只分发主文本框）
		for (GuiTextFieldGeneric field : textFields) {
			if (field.isFocused() && field.keyPressed(keyCode, scanCode, modifiers)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected boolean onCharTypedImpl(char charIn, int modifiers) {
		// 字符输入分发给聚焦的文本框（基类只分发主文本框，次文本框收不到字符）
		for (GuiTextFieldGeneric field : textFields) {
			if (field.isFocused() && field.charTyped(charIn, modifiers)) {
				return true;
			}
		}
		return super.onCharTypedImpl(charIn, modifiers);
	}

	@Override
	protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
		// 先让全部文本框处理点击（聚焦/取消聚焦），再走基类的按钮/子控件路径
		boolean ret = false;
		for (GuiTextFieldGeneric field : textFields) {
			ret |= field.mouseClicked(mouseX, mouseY, mouseButton);
		}
		return super.onMouseClickedImpl(mouseX, mouseY, mouseButton) || ret;
	}

	@Override
	protected void drawTextFields(int mouseX, int mouseY, DrawContext drawContext) {
		// 绘制全部文本框（基类仅绘制主文本框）
		for (GuiTextFieldGeneric field : textFields) {
			field.render(drawContext, mouseX, mouseY, 0f);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
		super.render(mouseX, mouseY, selected, drawContext);
		// 行分隔线（沿用旧行视觉参数；this.height 已是随记录数变化的动态值）
		drawContext.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF555555);
	}

	/** 注册文本框：进入父列表的 TAB 循环/失焦提交清单；监听器传 null = 无逐键回调（仅 Enter/失焦提交） */
	private void registerField(GuiTextFieldGeneric field) {
		textFields.add(field);
		this.parent.addTextField(new TextFieldWrapper<>(field, null));
		if (this.textField == null) {
			this.textField = new TextFieldWrapper<>(field, null);
		}
	}

	/** 任一文本框是否聚焦 */
	private boolean isAnyFieldFocused() {
		for (GuiTextFieldGeneric field : textFields) {
			if (field.isFocused()) {
				return true;
			}
		}
		return false;
	}

	/** 解析数量输入：非法回退 fallback，越界钳制到 1..2304 */
	private static int parseAmount(String text, int fallback) {
		try {
			int v = Integer.parseInt(text.trim());
			return Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, v));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** 按 (item, 方向) upsert 当前条目并保存到配置文件 */
	private void saveEntry() {
		ItemIOCache.upsert(item, isInput, entry);
	}

	/**
	 * 抓取玩家脚下方块坐标（旧独立编辑屏 grabContainer 语义的规范实现）；玩家不存在时返回 null。 修复：箱子等高度不足 1
	 * 格的容器，玩家脚底实际落在容器方块内部（getBlockPos 向下取整即容器自身坐标）， 无条件下移一格会把 y 取低 1
	 * 格（抓错方块）；故先判断脚底方块是否为容器，是则直接取脚底坐标。
	 */
	public static BlockPos grabFootBlockPos() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null)
			return null;
		// 优先取玩家脚底所在方块：箱子等高度不足 1 格的容器，玩家脚底实际落在容器方块内部
		// （getBlockPos 向下取整即容器自身坐标），无条件下移一格会把 y 取低 1 格（抓错方块）
		BlockPos feetPos = mc.player.getBlockPos();
		if (mc.world != null && ContainerIOTask.isContainerBlock(mc.world.getBlockState(feetPos))) {
			return feetPos;
		}
		// 站在满格方块上时脚底方块为空气，取正下方容器（原语义）
		return feetPos.down();
	}

	/**
	 * 带悬浮提示的文本标签控件：渲染单行文本（指定颜色），悬浮时显示 tooltip（翻译键，null = 无提示）。
	 * 用于简写标签（阈/拿取/维/抓取）与状态文本（[开]/[关]）的完整说明兜底（第 3 点：长文本简写 + hover 全解）。
	 */
	private class HoverLabelWidget extends WidgetBase {
		private final String text;
		private final int color;
		private final String tooltipKey;

		HoverLabelWidget(int x, int y, String text, int color, String tooltipKey) {
			super(x, y, ItemIOEntryWidget.this.getStringWidth(text), 8);
			this.text = text;
			this.color = color;
			this.tooltipKey = tooltipKey;
		}

		@Override
		public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			drawContext.drawText(this.textRenderer, text, getX(), getY(), color, false);
		}

		@Override
		public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			// 有提示键且鼠标悬停时渲染 tooltip（与 CountLabelWidget 的 hover 模式一致）
			if (tooltipKey != null && mouseX >= getX() && mouseX <= getX() + getWidth() && mouseY >= getY()
					&& mouseY <= getY() + getHeight()) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc.textRenderer != null) {
					drawContext.drawTooltip(mc.textRenderer, List.of(Text.literal(StringUtils.translate(tooltipKey))),
							mouseX, mouseY);
				}
			}
			super.postRenderHovered(mouseX, mouseY, selected, drawContext);
		}
	}

	/**
	 * 计数标签控件：渲染「启用 X · 禁用 Y」；X=0（无启用交易对使用该物品，当前不生效）时用高亮色并附悬浮提示
	 */
	private class CountLabelWidget extends WidgetBase {
		private final String text;
		private final boolean inactive;

		CountLabelWidget(int x, int y, String text, boolean inactive) {
			super(x, y, ItemIOEntryWidget.this.getStringWidth(text), 8);
			this.text = text;
			this.inactive = inactive;
		}

		@Override
		public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			drawContext.drawText(this.textRenderer, text, getX(), getY(),
					inactive ? STATS_INACTIVE_COLOR : STATS_NORMAL_COLOR, false);
		}

		@Override
		public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			// X=0 时悬浮显示「当前不生效」提示（文本键 autotrade.gui.item_io.inactive_hint）
			if (inactive && mouseX >= getX() && mouseX <= getX() + getWidth() && mouseY >= getY()
					&& mouseY <= getY() + getHeight()) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mc.textRenderer != null) {
					drawContext.drawTooltip(mc.textRenderer,
							List.of(Text.literal(StringUtils.translate(STATS_INACTIVE_HINT_KEY))), mouseX, mouseY);
				}
			}
			super.postRenderHovered(mouseX, mouseY, selected, drawContext);
		}
	}
}