package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.gui.GuiConfigs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 物品 IO 记录行控件（固定高 20px，方案 B 拆分后的单条记录条目）：渲染单条容器记录行 —— [序号][记录级 开/关] [维度
 * 标签+文本框][坐标 文本框][抓取容器][启用/禁用][✕ 删除]（stat 由选项卡层填入，本控件只负责渲染）。 阈值/每次拿取以组为单位（1 组 =
 * 1 槽位）。
 *
 * <p>
 * 旧实现把头部段与全部记录行渲染在同一个可变高条目里（行高 = 20 + 20×记录数），记录数过多时行高超过列表视口， malilib
 * 的空间判定会拒绝该条目并 break 整列表（整页空白）。拆分后头部行与每条记录行都是固定高 20px 的独立
 * 条目，空间判定永不拒绝条目。本控件只承载单条记录段（recordIndex 定位）；头部段见 {@link ItemIOHeaderWidget}。
 * </p>
 *
 * <p>
 * 保存路径统一走 {@link com.github.sebseb7.autotrade.trade.data.ItemIOCache#upsert}（按
 * (item, 方向) 更新或追加） 并回写
 * {@code Configs.Generic.ITEM_IO}；行内文本框为「回车/失焦提交」：光标输入期间不触发保存与列表重建（Enter 由
 * {@link #onKeyTypedImpl} 处理，失焦由列表的 {@code applyPendingModifications} 路径处理），
 * 非法输入（维度格式不符 / 坐标格式不符）恢复原值并提示，不写入。
 * </p>
 */
public class ItemIORecordWidget extends ItemIOBaseWidget {
	/** 本控件对应的记录下标（与 entry.getLocations() 对齐；删除记录后重建时下标自动重排） */
	private final int recordIndex;
	/** 记录行维度文本框（留空 = 任意维度） */
	private GuiTextFieldGeneric dimField;
	/** 记录行坐标文本框（"x y z"） */
	private GuiTextFieldGeneric coordField;

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
	 * @param recordIndex
	 *            本控件对应的记录下标（与 entry.getLocations() 对齐）
	 */
	public ItemIORecordWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent, String item, boolean isInput, ItemIO entry,
			IoItemDeriver.IoItemStat stat, Runnable onCommit, int recordIndex) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent, item, isInput, entry,
				stat, onCommit);
		this.recordIndex = recordIndex;
	}

	@Override
	public FieldRef getFocusedFieldRef() {
		if (dimField.isFocused())
			return new FieldRef(FieldKind.RECORD_DIM, recordIndex);
		if (coordField.isFocused())
			return new FieldRef(FieldKind.RECORD_COORD, recordIndex);
		return null;
	}

	@Override
	public void focusField(FieldRef ref) {
		if (ref == null)
			return;
		switch (ref.kind()) {
			// 记录控件无头部字段：静默跳过（头部字段由头部控件处理，列表恢复焦点时逐个尝试匹配控件）
			case THRESHOLD, TAKE_AMOUNT -> {
			}
			// 引用下标必须与本控件记录下标一致（同物品多条记录控件逐个尝试匹配，避免错位聚焦）；
			// 下标不匹配（如删除记录后重建）时静默回落：不聚焦任何字段，不报错（保留 focusRecordField 的越界返回语义）
			case RECORD_DIM -> {
				if (ref.recordIndex() == this.recordIndex) {
					focusText(dimField);
				}
			}
			case RECORD_COORD -> {
				if (ref.recordIndex() == this.recordIndex) {
					focusText(coordField);
				}
			}
		}
	}

	@Override
	protected void layoutRow(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
		int gap = 4;
		int rightEdge = (this.x + this.width) - gap;
		// 左侧内容整体右移 RECORD_INDENT：记录行相对头部行缩进，表达层级从属；
		// 右侧按钮组仍由 rightEdge/btnX 右对齐锚定行尾（不随缩进移动），弹性区 flexW = rowBtnX - rc 自动扣减缩进
		int rc = x + 2 + RECORD_INDENT;

		// ── 记录行（固定高 20px）：[序号][记录级 开/关][维度 简写标签+文本框][坐标 文本框][抓取容器][启用/禁用][✕ 删除] ──
		// 按钮组（抓取/启停/删除）整体右对齐到行尾：右对齐锚定行尾使删除按钮永不超出右边界
		// （根治左对齐流式布局下 ✕ 出界）；弹性区（维度框 + 坐标框）吃「前缀到按钮组」剩余宽度，
		// 维度框 55% / 坐标框 45%（坐标框吃剩余，保证和 = flexW - gap）；
		// 序号 + 记录级状态文本使记录状态与头部条目级状态错位（层级从属视觉）
		ItemIOLocation loc = entry.getLocations().get(recordIndex);
		String dimLabel = StringUtils.translate(DIMENSION_SHORT_KEY);
		int dimLabelW = this.getStringWidth(dimLabel);
		// 按钮组（右对齐）内各按钮宽度按各自文本自适应 + 硬上限：宽度贴合内容（Grab/✕ 短、Disable 长），
		// 不强制等宽以免短文本按钮出现大段空白；上限防英文长文本撑爆行宽（Grab 40 / 启停 44 / ✕ 24）
		// 抓取模式：抓取热键按下后本行抓取按钮变为「保存」按钮（标签/悬浮/点击语义均切换）
		boolean grabMode = this.host instanceof GuiConfigs gc && gc.isGrabMode();
		String grabLabel = StringUtils.translate(grabMode ? GRAB_SAVE_SHORT_KEY : GRAB_CONTAINER_SHORT_KEY);
		int grabW = Math.min(40, this.getStringWidth(grabLabel) + 6);
		int recToggleW = Math.min(44,
				Math.max(this.getStringWidth(TOGGLE_ON_LABEL), this.getStringWidth(TOGGLE_OFF_LABEL)) + 6);
		int delW = Math.min(24, this.getStringWidth(StringUtils.translate(DELETE_KEY)) + 6);
		// 按钮组右对齐：组左端 = 行尾 - 组宽（3 个按钮 + 2 个间隙）
		int btnGroupW = grabW + gap + recToggleW + gap + delW;
		int btnX = rightEdge - btnGroupW;

		// 记录序号（灰色，最左）：记录编号（删除后自动重排），使后续记录级状态与头部条目级状态错位
		String numText = StringUtils.translate(RECORD_NUMBER_KEY, recordIndex + 1);
		int numTextW = this.getStringWidth(numText);
		this.addWidget(new HoverLabelWidget(rc, y + 6, numText, RECORD_NUM_COLOR, null));
		rc += numTextW + gap;

		// 记录级 [开/关] 状态文本（紧随序号，仅展示该记录启用状态）：实际开关操作由行尾「启用/禁用」按钮
		// 承担（与条目级开关 AND 生效），悬浮显示层级说明
		String recStatusLabel = StringUtils.translate(loc.isEnabled() ? STATUS_ON_KEY : STATUS_OFF_KEY);
		int recStatusW = this.getStringWidth(recStatusLabel);
		int recStatusColor = loc.isEnabled() ? STATUS_ON_COLOR : STATUS_OFF_COLOR;
		String recStatusTipKey = loc.isEnabled() ? STATUS_TIP_RECORD_ON_KEY : STATUS_TIP_RECORD_OFF_KEY;
		this.addWidget(new HoverLabelWidget(rc, y + 6, recStatusLabel, recStatusColor, recStatusTipKey));
		rc += recStatusW + gap;

		// 维度标签（简写 + 悬浮完整说明）+ 文本框：留空 = 任意维度（兼容旧配置），非空必须为可解析的维度 id，Enter/失焦提交
		this.addWidget(new HoverLabelWidget(rc, y + 6, dimLabel, 0xFFFFFFFF, DIMENSION_TIP_KEY));
		rc += dimLabelW + 2;

		// 弹性区：维度框 55% / 坐标框 45%（坐标框吃剩余，保证和 = flexW - gap）；下限保护
		// dimW ≥ 40、coordW ≥ 60（或 flexW < 140 提前触发收缩），不满足则收缩按钮组
		// 行级局部副本：收缩只影响当前行，不污染其他记录行
		int rowGrabW = grabW;
		int rowToggleW = recToggleW;
		int rowDelW = delW;
		int rowBtnX = btnX;
		int flexW = rowBtnX - rc;
		int dimW = flexW * 55 / 100;
		int coordW = flexW - dimW - gap;
		if (dimW < 40 || coordW < 60 || flexW < 140) {
			// 收缩路径：按 ✕(下限14) → 抓取(下限24) → 启停(下限30) 顺序收缩按钮组（保文本完整），组右端仍锚定行尾
			int deficit = Math.max(0, Math.max(60 - coordW, 40 - dimW));
			int delShrink = Math.min(rowDelW - 14, deficit);
			rowDelW -= delShrink;
			deficit -= delShrink;
			int grabShrink = Math.min(rowGrabW - 24, deficit);
			rowGrabW -= grabShrink;
			deficit -= grabShrink;
			rowToggleW = Math.max(30, rowToggleW - deficit);
			int rowBtnGroupW = rowGrabW + gap + rowToggleW + gap + rowDelW;
			rowBtnX = rightEdge - rowBtnGroupW;
			// 收缩后重算弹性区并重新分配
			flexW = rowBtnX - rc;
			dimW = flexW * 55 / 100;
			coordW = flexW - dimW - gap;
			// 收缩后 coordW 仍 < 60（极端窄窗口）：维度框先保 40，坐标框尽力吃剩余（下限 4，避免负宽）
			if (coordW < 60) {
				dimW = Math.min(40, flexW);
				coordW = Math.max(4, flexW - dimW - gap);
			}
		}
		dimField = this.createTextField(rc, y + 1, dimW - 4, 17);
		dimField.setMaxLength(64);
		dimField.setText(loc.getDimension());
		registerField(dimField);
		rc += dimW + gap;

		// 坐标文本框：吃「前缀（序号/状态/维度）到按钮组」剩余宽度（下限 60）；极端窄窗口下按钮组
		// 收缩（行级局部计算，不影响其他记录行），组右端仍锚定行尾；
		// ConfigCoordinate 校验语义，Enter/失焦提交（输入期间不保存不重建）
		coordField = this.createTextField(rc, y + 1, coordW - 4, 17);
		coordField.setMaxLength(48);
		coordField.setText(String.format("%d %d %d", loc.getX(), loc.getY(), loc.getZ()));
		registerField(coordField);

		// 按钮组（右对齐到行尾）：[抓取容器][启用/禁用][✕ 删除]，组内等宽 rowBtnW，组左端 rowBtnX
		// 抓取容器按钮（简写 + 悬浮完整说明）：写入玩家脚下方块坐标 + 当前维度（world 非空才写维度），即时生效并保存；
		// 抓取模式下变为闪动的「保存」按钮（FlashingButton，基类共享）：点击把热键抓取的待保存坐标/维度写入本记录并结束模式
		final int idx = recordIndex;
		ButtonGeneric grabBtn = grabMode
				? new FlashingButton(rowBtnX, y, rowGrabW, 20, grabLabel)
				: new ButtonGeneric(rowBtnX, y, rowGrabW, 20, grabLabel);
		grabBtn.setHoverStrings(grabMode ? GRAB_SAVE_TIP_KEY : GRAB_CONTAINER_TIP_KEY);
		this.addButton(grabBtn, (button, mouseButton) -> {
			// 抓取模式：把热键抓取的待保存坐标/维度写入本记录，结束模式并提示成功
			if (host instanceof GuiConfigs gc && gc.isGrabMode()) {
				BlockPos pending = gc.getPendingGrabPos();
				if (pending == null) {
					InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
							"autotrade.message.grab_container_failed");
					return;
				}
				ItemIOLocation target = entry.getLocations().get(idx);
				target.setX(pending.getX());
				target.setY(pending.getY());
				target.setZ(pending.getZ());
				String dim = gc.getPendingGrabDim();
				if (dim != null)
					target.setDimension(dim);
				gc.exitGrabMode();
				saveEntry();
				if (onCommit != null)
					onCommit.run();
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.item_io_container_set",
						dim, pending.getX(), pending.getY(), pending.getZ());
				return;
			}
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

		// 记录启用/禁用按钮（hover 补当前状态与 AND 语义）：写入该记录 enabled（与行级总开关 AND 生效），即时生效并保存
		String recToggleLabel = StringUtils.translate(loc.isEnabled() ? TOGGLE_OFF_LABEL : TOGGLE_ON_LABEL);
		ButtonGeneric recToggleBtn = new ButtonGeneric(rowBtnX + rowGrabW + gap, y, rowToggleW, 20, recToggleLabel);
		recToggleBtn.setHoverStrings(loc.isEnabled() ? REC_TOGGLE_BTN_TIP_ON_KEY : REC_TOGGLE_BTN_TIP_OFF_KEY);
		this.addButton(recToggleBtn, (button, mouseButton) -> {
			ItemIOLocation target = entry.getLocations().get(idx);
			target.setEnabled(!target.isEnabled());
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});

		// 删除按钮（✕，悬浮说明）：点击即删除该记录并保存（无确认弹窗，与行级按钮即时生效风格一致）
		ButtonGeneric delBtn = new ButtonGeneric(rowBtnX + rowGrabW + gap + rowToggleW + gap, y, rowDelW, 20,
				StringUtils.translate(DELETE_KEY));
		delBtn.setHoverStrings(DELETE_TIP_KEY);
		this.addButton(delBtn, (button, mouseButton) -> {
			entry.getLocations().remove(idx);
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});
	}

	@Override
	protected boolean applyPendingValues() {
		boolean changed = false;
		ItemIOLocation loc = entry.getLocations().get(recordIndex);
		// 逐记录校验：维度（空串 = 任意维度合法；非空必须可解析为 Identifier）+ 坐标（ConfigCoordinate 语义），
		// 非法输入恢复原值并提示一次，不写入；提交快照按下标与 textFields 对齐（维度先注册、坐标后注册）
		String dimText = dimField.getText().trim();
		if (!dimText.isEmpty() && Identifier.tryParse(dimText) == null) {
			dimField.setText(committedTexts.get(0));
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, INVALID_DIMENSION_KEY);
		} else if (!dimText.equals(committedTexts.get(0).trim())) {
			loc.setDimension(dimText);
			changed = true;
		}
		String coordText = coordField.getText().trim();
		BlockPos pos = ConfigCoordinate.parse(coordText);
		if (pos == null) {
			coordField.setText(committedTexts.get(1));
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_pos");
		} else if (!coordText.equals(committedTexts.get(1).trim())) {
			loc.setX(pos.getX());
			loc.setY(pos.getY());
			loc.setZ(pos.getZ());
			changed = true;
		}
		return changed;
	}
}