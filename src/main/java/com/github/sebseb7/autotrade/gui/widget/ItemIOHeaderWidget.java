package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.gui.GuiConfigs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/**
 * 物品 IO 头部行控件（固定高 20px，方案 B 拆分后的头部条目）：渲染单个 (item, 方向) 的条目级头部行 —— 物品预览图标（最左）+ 「开
 * X · 关 Y」统计（中部弹性区，放不下不渲染）+ 右侧控件组右对齐（右锚 x + width -
 * ROW_RIGHT_MARGIN）：状态显示开关（开=绿 / 关=灰，固定 36px）+ 阈值标签+输入框 + 每次拿取标签+输入框（仅输入方向）+
 * 添加按钮（50px，行尾）。阈值/每次拿取以组为单位 （1 组 = 1 槽位）。stat 由选项卡层填入，本控件只负责渲染。
 *
 * <p>
 * 窄窗收缩阶梯：统计让位 → 数值框 40→30 → 开关 36→30 → 间隔压到 2 → 极限钳制（数值框 24）；右侧控件组宽度 由内容派生 +
 * 固定档位，保证同页各行右对齐列整齐。
 * </p>
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
		// ── 头部行（固定高 20px）：物品预览图标（最左）+ 统计文本（中部弹性区）+
		// 右侧控件组右对齐（右锚 x + width - ROW_RIGHT_MARGIN）──
		// 物品预览图标（最左；条目级状态改由右侧开关按钮自身以颜色显示，不再单独渲染状态文本）
		int cx = x + 2;
		ItemStack stack = ItemStringHelper.decode(item);
		if (!stack.isEmpty()) {
			this.addWidget(new ItemIconWidget(cx, y + 1, stack));
		}
		int iconEndX = cx + ICON_BLOCK_WIDTH;

		// 右侧控件组（右对齐）：状态显示开关 + 阈值标签+输入框 + 每次拿取标签+输入框（仅输入方向）+ 添加按钮
		int rightEdge = (this.x + this.width) - ROW_RIGHT_MARGIN;
		// 字段文本标签：阈值两个方向都显示；每次拿取仅输入方向（输出方向无此概念，见 ContainerIOTask
		// transferLimit：输出固定 999 全量搬运，不读 takeAmount）；标签用简写，悬浮显示完整说明
		String thresholdLabel = StringUtils.translate(THRESHOLD_SHORT_KEY);
		int threshW = this.getStringWidth(thresholdLabel);
		String takeAmountLabel = null;
		int takeW = 0;
		if (isInput) {
			takeAmountLabel = StringUtils.translate(TAKE_AMOUNT_SHORT_KEY);
			takeW = this.getStringWidth(takeAmountLabel);
		}
		// 右侧控件组初始宽度（收缩阶梯起点）：开关 36 + 阈值块 +（输入方向）拿取块 + 添加按钮，子组间 GAP_WIDE
		int toggleW = BTN_STATE_WIDTH;
		int threshNumW = FIELD_NUM_WIDTH;
		int takeNumW = isInput ? FIELD_NUM_WIDTH : 0;
		int rightBlockW = toggleW + GAP_WIDE + (threshW + GAP_TIGHT + threshNumW)
				+ (isInput ? GAP + (takeW + GAP_TIGHT + takeNumW) : 0) + GAP_WIDE + BTN_ADD_WIDTH;
		// 收缩阶梯（统计文本不预留空间）：数值框依次分摊（各最多 -10，下限 30）→ 开关 36→30 →
		// 仍不满足则走硬钳制路径（间隔可压到 GAP_TIGHT，数值框降至 24）
		int maxRightW = rightEdge - iconEndX - GAP_WIDE;
		if (rightBlockW > maxRightW) {
			int deficit = rightBlockW - maxRightW;
			// (b) 数值框依次分摊：先阈值框、再拿取框，每框最多 -10（下限 30）
			int cut = Math.min(Math.min(10, threshNumW - 30), Math.max(0, deficit));
			threshNumW -= cut;
			deficit -= cut;
			if (isInput) {
				cut = Math.min(Math.min(10, takeNumW - 30), Math.max(0, deficit));
				takeNumW -= cut;
				deficit -= cut;
			}
			// (c) 开关 36→30
			if (deficit > 0) {
				cut = Math.min(BTN_STATE_WIDTH - 30, deficit);
				toggleW -= cut;
				deficit -= cut;
			}
			rightBlockW = toggleW + GAP_WIDE + (threshW + GAP_TIGHT + threshNumW)
					+ (isInput ? GAP + (takeW + GAP_TIGHT + takeNumW) : 0) + GAP_WIDE + BTN_ADD_WIDTH;
			// (d)+(e) 仍不满足（间隔可压到 GAP_TIGHT）→ 硬钳制路径：数值框降至 24（覆盖步骤 b 的 30）
			if (rightBlockW > rightEdge - iconEndX - GAP_TIGHT) {
				threshNumW = 24;
				if (isInput) {
					takeNumW = 24;
				}
				rightBlockW = toggleW + GAP_WIDE + (threshW + GAP_TIGHT + threshNumW)
						+ (isInput ? GAP + (takeW + GAP_TIGHT + takeNumW) : 0) + GAP_WIDE + BTN_ADD_WIDTH;
			}
		}
		// 控件组左边界：右锚算出后不越过图标区（极限窄窗下与图标区仅隔 GAP_TIGHT）
		int blockX = Math.max(iconEndX + GAP_TIGHT, rightEdge - rightBlockW);

		// 统计文本（中部弹性区）：不参与宽度分配；放不下（会撞上右侧控件组）则不渲染
		boolean renderStats = false;
		String statsText = null;
		boolean statsInactive = false;
		if (stat != null) {
			statsText = StringUtils.translate(STATS_KEY, stat.enabledCount(), stat.disabledCount());
			statsInactive = stat.enabledCount() == 0;
			int statsW = this.getStringWidth(statsText);
			if (iconEndX + statsW + GAP <= blockX) {
				renderStats = true;
			}
		}
		if (renderStats) {
			this.addWidget(new CountLabelWidget(iconEndX, y + 6, statsText, statsInactive));
		}

		// 状态显示开关（控件组最左，固定宽）：按钮显示「当前状态」（开=绿 / 关=灰），点击切换；
		// 悬浮提示说明点击后的动作，消除「按钮显示的是状态还是动作」歧义
		String toggleLabel = entry.isEnabled()
				? GuiBase.TXT_GREEN + StringUtils.translate(TOGGLE_ON_LABEL) + GuiBase.TXT_RST
				: GuiBase.TXT_GRAY + StringUtils.translate(TOGGLE_OFF_LABEL) + GuiBase.TXT_RST;
		String toggleTipKey = entry.isEnabled() ? TOGGLE_BTN_TIP_ON_KEY : TOGGLE_BTN_TIP_OFF_KEY;
		ButtonGeneric toggleBtn = new ButtonGeneric(blockX, y, toggleW, 20, toggleLabel);
		toggleBtn.setHoverStrings(toggleTipKey);
		this.addButton(toggleBtn, (button, mouseButton) -> {
			entry.setEnabled(!entry.isEnabled());
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});
		int cursor = blockX + toggleW + GAP_WIDE;

		// 阈值标签（简写 + 悬浮完整说明，按方向区分补货/清出语义）+ 输入框：范围 1..36 组，Enter/失焦提交
		String thresholdTipKey = isInput ? THRESHOLD_TIP_INPUT_KEY : THRESHOLD_TIP_OUTPUT_KEY;
		this.addWidget(new HoverLabelWidget(cursor, y + 6, thresholdLabel, 0xFFFFFFFF, thresholdTipKey));
		cursor += threshW + GAP_TIGHT;
		thresholdField = this.createTextField(cursor, y + 1, threshNumW - 4, 17);
		thresholdField.setMaxLength(8);
		thresholdField.setText(String.valueOf(entry.getThreshold()));
		registerField(thresholdField);
		cursor += threshNumW;

		// 每次拿取标签（简写 + 悬浮完整说明）+ 输入框（仅输入方向；输出方向不渲染该字段，takeAmountField 保持 null）
		if (isInput) {
			cursor += GAP;
			this.addWidget(new HoverLabelWidget(cursor, y + 6, takeAmountLabel, 0xFFFFFFFF, TAKE_AMOUNT_TIP_KEY));
			cursor += takeW + GAP_TIGHT;
			takeAmountField = this.createTextField(cursor, y + 1, takeNumW - 4, 17);
			takeAmountField.setMaxLength(8);
			takeAmountField.setText(String.valueOf(entry.getTakeAmount()));
			registerField(takeAmountField);
			cursor += takeNumW;
		}

		// 抓取模式下「+ 添加」按钮与保存按钮同样闪动，提示当前处于抓取模式
		boolean grabMode = this.host instanceof GuiConfigs gc && gc.isGrabMode();
		// 添加容器按钮（行尾右锚，原底部行按钮上移）：新增一条记录（维度 = 当前维度，坐标 0 0 0 占位，不触发 IO），即时生效并保存
		String addLabelText = StringUtils.translate(ADD_LOCATION_KEY);
		ButtonGeneric addBtn = grabMode
				? new FlashingButton(rightEdge - BTN_ADD_WIDTH, y, BTN_ADD_WIDTH, 20, addLabelText)
				: new ButtonGeneric(rightEdge - BTN_ADD_WIDTH, y, BTN_ADD_WIDTH, 20, addLabelText);
		addBtn.setHoverStrings(ADD_LOCATION_TIP_KEY);
		this.addButton(addBtn, (button, mouseButton) -> {
			// 抓取模式：新增记录行并立即把热键抓取的待保存坐标/维度写入该记录（等价于保存按钮），随后结束抓取模式
			if (host instanceof GuiConfigs gc && gc.isGrabMode()) {
				BlockPos pending = gc.getPendingGrabPos();
				if (pending == null) {
					InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
							"autotrade.message.grab_container_failed");
					return;
				}
				String pendingDim = gc.getPendingGrabDim();
				entry.getLocations().add(new ItemIOLocation(pendingDim != null ? pendingDim : "", pending.getX(),
						pending.getY(), pending.getZ(), true));
				gc.exitGrabMode();
				saveEntry();
				if (onCommit != null)
					onCommit.run();
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.item_io_container_set",
						pendingDim, pending.getX(), pending.getY(), pending.getZ());
				return;
			}
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
