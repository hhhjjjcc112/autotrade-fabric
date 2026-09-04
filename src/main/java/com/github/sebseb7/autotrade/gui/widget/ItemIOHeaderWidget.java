package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

/**
 * 物品 IO 头部行控件（固定高 20px，方案 B 拆分后的头部条目）：渲染单个 (item, 方向) 的条目级头部行 —— [开/关]
 * 状态文本（最左）+ 物品预览 icon + 「开 X · 关 Y」计数标签（放不下跳过）+ 行级「启用/禁用」总开关按钮 + 右侧 [阈值] 输入框 +
 * [每次拿取] 输入框（仅输入方向）右对齐 + [添加容器] 按钮（行尾）。阈值/每次拿取以组为单位 （1 组 = 1 槽位）。stat
 * 由选项卡层填入，本控件只负责渲染。
 *
 * <p>
 * 旧实现把头部段与全部记录行渲染在同一个可变高条目里（行高 = 20 + 20×记录数），记录数过多时行高超过列表视口， malilib
 * 的空间判定会拒绝该条目并 break 整列表（整页空白）。拆分后头部行与每条记录行都是固定高 20px 的独立
 * 条目，空间判定永不拒绝条目。本控件只承载头部段；记录段见 {@link ItemIORecordWidget}。
 * </p>
 *
 * <p>
 * 保存路径统一走 {@link com.github.sebseb7.autotrade.trade.data.ItemIOCache#upsert}（按
 * (item, 方向) 更新或追加） 并回写
 * {@code Configs.Generic.ITEM_IO}；行内文本框为「回车/失焦提交」：光标输入期间不触发保存与列表重建（Enter 由
 * {@link #onKeyTypedImpl} 处理，失焦由列表的 {@code applyPendingModifications} 路径处理），
 * 非法输入（数量越界）恢复原值并提示，不写入。
 * </p>
 */
public class ItemIOHeaderWidget extends ItemIOBaseWidget {
	/** 头部阈值小数字框（输出行也显示，两方向共用） */
	private GuiTextFieldGeneric thresholdField;
	/** 头部单次数量小数字框（仅输入方向；输出方向保持 null） */
	private GuiTextFieldGeneric takeAmountField;

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
	public ItemIOHeaderWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent, String item, boolean isInput, ItemIO entry,
			IoItemDeriver.IoItemStat stat, Runnable onCommit) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent, item, isInput, entry,
				stat, onCommit);
	}

	@Override
	public FieldRef getFocusedFieldRef() {
		if (thresholdField.isFocused())
			return new FieldRef(FieldKind.THRESHOLD, -1);
		// 输出行无「每次拿取」字段（takeAmountField 为 null）
		if (takeAmountField != null && takeAmountField.isFocused())
			return new FieldRef(FieldKind.TAKE_AMOUNT, -1);
		return null;
	}

	@Override
	public void focusField(FieldRef ref) {
		if (ref == null)
			return;
		switch (ref.kind()) {
			case THRESHOLD -> focusText(thresholdField);
			// 输出行无「每次拿取」字段（takeAmountField 为 null），静默跳过（保留现有行为）
			case TAKE_AMOUNT -> focusText(takeAmountField);
			// 头部控件无记录字段：静默跳过（记录字段由记录控件处理，列表恢复焦点时逐个尝试匹配控件）
			case RECORD_DIM, RECORD_COORD -> {
			}
		}
	}

	@Override
	protected void layoutRow(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
		int gap = 4;
		int rightEdge = (this.x + this.width) - gap;
		int cx = x + 2;

		// ── 头部行（固定高 20px）：条目级 [开/关] 状态文本（最左）+ 物品预览图标 + 统计文本（放得下才渲染）；
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
		String thresholdLabel = StringUtils.translate(THRESHOLD_SHORT_KEY);
		int threshLabelW = this.getStringWidth(thresholdLabel);
		String takeAmountLabel = null;
		int takeLabelW = 0;
		if (isInput) {
			takeAmountLabel = StringUtils.translate(TAKE_AMOUNT_SHORT_KEY);
			takeLabelW = this.getStringWidth(takeAmountLabel);
		}
		// 右侧块（右对齐到行尾）：[阈值 标签+输入框] + [每次拿取 标签+输入框]（每次拿取仅输入行）+ [添加容器 按钮]
		// 阈值/每次拿取以组为单位（1 组 = 1 槽位）；右段宽度按行宽比例分配（40%..60%），下限为内容最小宽
		String addLabelText = StringUtils.translate(ADD_LOCATION_KEY);
		int addW = Math.min(60, Math.max(50, this.getStringWidth(addLabelText) + 10));
		// 内容最小宽：阈值块（标签+2+numW 下限 40）+ 每次拿取块（仅输入行）+ 添加按钮
		int minRightW = (threshLabelW + 2 + 40) + (isInput ? (takeLabelW + 2 + 40 + gap) : 0) + gap + addW;
		// 右段按行宽比例分配并钳制 [40%, 60%]，下限为内容最小宽（窄窗口下右段不压缩到内容以下）
		int rightBlockW = Math.max(minRightW, Math.min(this.width * 40 / 100, this.width * 60 / 100));
		int blockX = rightEdge - rightBlockW;
		// 数量输入框宽：右段内扣除标签与按钮后均分（输入行 2 个、输出行 1 个），钳制 [40, 60]
		int numW = (rightBlockW - (threshLabelW + 2) - (isInput ? takeLabelW + 2 + gap : 0) - gap - addW)
				/ (isInput ? 2 : 1);
		numW = Math.min(60, Math.max(40, numW));
		// 统计文本：位于图标之后、右侧块之前；启停按钮优先，统计文本让位（含统计放不下 toggleW 时统计不渲染）
		int statsEndX = iconEndX;
		int toggleW = Math
				.min(Math.max(this.getStringWidth(TOGGLE_ON_LABEL), this.getStringWidth(TOGGLE_OFF_LABEL)) + 8, 44);
		boolean renderStats = false;
		String statsText = null;
		boolean statsInactive = false;
		if (stat != null) {
			statsText = StringUtils.translate(STATS_KEY, stat.enabledCount(), stat.disabledCount());
			statsInactive = stat.enabledCount() == 0;
			int statsW = this.getStringWidth(statsText);
			// 先按「含统计」判断中段是否放得下 toggleW：放不下则统计让位（不渲染，statsEndX 保持 iconEndX）
			if (iconEndX + statsW + gap <= blockX && toggleW <= blockX - (iconEndX + statsW) - gap) {
				renderStats = true;
				statsEndX = iconEndX + statsW;
			}
		}
		// 中段可用宽（统计已渲染则从统计文本之后起算，否则从图标之后起算）
		int midAvailable = blockX - statsEndX - gap;
		toggleW = Math.max(30, Math.min(toggleW, midAvailable));
		if (renderStats) {
			this.addWidget(new CountLabelWidget(iconEndX, y + 6, statsText, statsInactive));
		}

		// 行级「启用/禁用」总开关按钮（统计文本之后）：宽度按两态文本中最宽者 + 边距自适应，
		// 44px 硬上限；中段放不下时统计文本让位，极端窄窗口下按钮紧贴右段（下限 30）；
		// 按钮显示「点击后执行的动作」（条目当前启用时显示「禁用」、禁用时显示「启用」），hover 补当前状态
		String toggleLabel = StringUtils.translate(entry.isEnabled() ? TOGGLE_OFF_LABEL : TOGGLE_ON_LABEL);
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
	}

	@Override
	protected boolean applyPendingValues() {
		boolean changed = false;
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
		return changed;
	}

	@Override
	protected void rewriteFieldsFromSaved() {
		// 用已保存值回写输入框（非法输入被替换为原值）
		thresholdField.setText(String.valueOf(entry.getThreshold()));
		if (takeAmountField != null) {
			takeAmountField.setText(String.valueOf(entry.getTakeAmount()));
		}
	}
}