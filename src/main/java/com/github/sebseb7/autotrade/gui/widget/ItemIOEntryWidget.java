package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOList;
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
import net.minecraft.util.math.BlockPos;

/**
 * 物品 IO 派生行控件：渲染单个 (item, 方向) 的 IO 配置行，两行布局： 第 1 行 = 物品预览 icon + 「启用 X · 禁用
 * Y」计数标签（左侧），[阈值] 输入框 + [每次拿取] 输入框（仅输入方向）右对齐； 第 2 行 = [开/关] 状态指示文本（仅展示）+
 * 启用/禁用按钮 + "x y z" 坐标文本框 + 抓取坐标按钮 （stat 由选项卡层填入，本控件只负责渲染）。
 *
 * <p>
 * 保存路径统一走 {@link ItemIOList#upsertItem}（按 (item, 方向) 更新或追加）并回写
 * {@code Configs.Generic.ITEM_IO}；行内文本框为「回车/失焦提交」：光标输入期间不触发保存与列表重建（Enter 由
 * {@link #onKeyTypedImpl} 处理，失焦由列表的 {@code applyPendingModifications} 路径处理），
 * 非法输入（坐标格式不符 / 数量越界）恢复原值并提示，不写入。
 * </p>
 *
 * <p>
 * 本类替代旧独立列表屏 {@code CustomItemIOListWidget} 的行渲染逻辑（含旧 独立编辑屏的 grabContainer
 * 坐标语义与 ConfigCoordinate 解析护栏）。
 * </p>
 */
public class ItemIOEntryWidget extends WidgetConfigOption {
	/** 行高：沿用旧 IO 列表的 ENTRY_HEIGHT=40（两行 20px 布局） */
	public static final int ENTRY_HEIGHT = 40;
	/**
	 * 行占位配置名前缀：父屏 getConfigs 用 {@code ROW_NAME_PREFIX + i} 命名占位
	 * ConfigString，本控件据此识别行
	 */
	public static final String ROW_NAME_PREFIX = "io_derived_";
	/** 行内文本框种类：列表重建后恢复焦点时用「行物品 + 种类」定位字段 */
	public enum FieldKind {
		/** "x y z" 坐标文本框 */
		COORD,
		/** 阈值小数字框 */
		THRESHOLD,
		/** 单次数量小数字框 */
		TAKE_AMOUNT
	}
	/** 阈值/单次数量的取值范围（与旧独立编辑屏的 ConfigInteger(1, 2304) 一致） */
	private static final int MIN_AMOUNT = 1;
	private static final int MAX_AMOUNT = 2304;
	/**
	 * 计数标签翻译键：正式键 autotrade.gui.item_io.stats（格式参数 {0}/{1} = 启用/禁用交易对数）
	 */
	private static final String STATS_KEY = "autotrade.gui.item_io.stats";
	/**
	 * 阈值/每次拿取字段的文本标签翻译键（输入框前显示，避免裸数字无含义）
	 */
	private static final String THRESHOLD_KEY = "autotrade.gui.item_io.threshold";
	private static final String TAKE_AMOUNT_KEY = "autotrade.gui.item_io.take_amount";
	/**
	 * 启用状态指示文本翻译键：仅作状态展示（绿色 [开] / 红色 [关]，样式同交易对列表状态文本）， 实际开关操作由「启用/禁用」按钮承担
	 */
	private static final String STATUS_ON_KEY = "autotrade.gui.item_io.status_on";
	private static final String STATUS_OFF_KEY = "autotrade.gui.item_io.status_off";
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

	private GuiTextFieldGeneric coordField;
	private GuiTextFieldGeneric thresholdField;
	private GuiTextFieldGeneric takeAmountField;
	/** 最近一次已提交的文本框内容（用于判断是否有未提交修改） */
	private String committedCoordText = "";
	private String committedThresholdText = "";
	private String committedTakeAmountText = "";
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

	/** 当前聚焦的文本框种类；无文本框聚焦时返回 null */
	public FieldKind getFocusedFieldKind() {
		if (coordField.isFocused())
			return FieldKind.COORD;
		if (thresholdField.isFocused())
			return FieldKind.THRESHOLD;
		// 输出行无「每次拿取」字段（takeAmountField 为 null）
		if (takeAmountField != null && takeAmountField.isFocused())
			return FieldKind.TAKE_AMOUNT;
		return null;
	}

	/** 聚焦指定文本框（列表重建后恢复焦点用，光标置于行尾）；输出行请求 TAKE_AMOUNT 时静默跳过 */
	public void focusField(FieldKind kind) {
		GuiTextFieldGeneric field = switch (kind) {
			case COORD -> coordField;
			case THRESHOLD -> thresholdField;
			case TAKE_AMOUNT -> takeAmountField;
		};
		if (field == null)
			return;
		field.setFocused(true);
		field.setCursorPositionEnd();
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

		// 第一行：物品预览图标 + 统计文本（左侧，空间不足时自动跳过）；[阈值]/[每次拿取] 标签+输入框块右对齐
		int cx = x + 2;
		ItemStack stack = ItemStringHelper.decode(item);
		if (!stack.isEmpty()) {
			this.addWidget(new ItemIconWidget(cx, y + 1, stack));
		}
		int iconEndX = x + 2 + 22;
		int gap = 4;
		int rightEdge = (this.x + this.width) - gap;
		int numW = Math.min(60, Math.max(40, (rightEdge - iconEndX) * 12 / 100));
		// 字段文本标签：阈值两个方向都显示；每次拿取仅输入方向（输出方向无此概念，见 ContainerIOTask
		// transferLimit：输出固定 999 全量搬运，不读 takeAmount）
		String thresholdLabel = StringUtils.translate(THRESHOLD_KEY);
		int threshLabelW = this.getStringWidth(thresholdLabel);
		String takeAmountLabel = null;
		int takeLabelW = 0;
		if (isInput) {
			takeAmountLabel = StringUtils.translate(TAKE_AMOUNT_KEY);
			takeLabelW = this.getStringWidth(takeAmountLabel);
		}
		// 右侧块（右对齐到行尾）：[阈值 标签+输入框] + [每次拿取 标签+输入框]（每次拿取仅输入行）
		int takeBlockW = takeAmountLabel != null ? takeLabelW + 2 + numW + gap : 0;
		int rightBlockW = threshLabelW + 2 + numW + takeBlockW;
		int blockX = rightEdge - rightBlockW;
		// 统计文本：位于图标之后、右侧块之前，放得下才渲染（X=0 时高亮提示「当前不生效」）
		if (stat != null) {
			String statsText = StringUtils.translate(STATS_KEY, stat.enabledCount(), stat.disabledCount());
			boolean inactive = stat.enabledCount() == 0;
			if (iconEndX + this.getStringWidth(statsText) + gap <= blockX) {
				this.addWidget(new CountLabelWidget(iconEndX, y + 6, statsText, inactive));
			}
		}

		// 阈值标签 + 输入框：范围 1..2304，Enter/失焦提交
		cx = blockX;
		this.addLabel(cx, y + 6, threshLabelW, 8, 0xFFFFFFFF, thresholdLabel);
		cx += threshLabelW + 2;
		thresholdField = this.createTextField(cx, y + 1, numW - 4, 17);
		thresholdField.setMaxLength(8);
		thresholdField.setText(String.valueOf(entry.getThreshold()));
		committedThresholdText = thresholdField.getText();
		registerField(thresholdField);
		cx += numW + gap;

		// 每次拿取标签 + 输入框（仅输入方向；输出方向不渲染该字段，takeAmountField 保持 null）
		if (takeAmountLabel != null) {
			this.addLabel(cx, y + 6, takeLabelW, 8, 0xFFFFFFFF, takeAmountLabel);
			cx += takeLabelW + 2;
			takeAmountField = this.createTextField(cx, y + 1, numW - 4, 17);
			takeAmountField.setMaxLength(8);
			takeAmountField.setText(String.valueOf(entry.getTakeAmount()));
			committedTakeAmountText = takeAmountField.getText();
			registerField(takeAmountField);
		}

		// 第二行：[开/关] 状态指示文本（仅展示当前启用状态，绿色 [开]/红色 [关]，样式同交易对列表）+ 启用/禁用按钮
		// + 坐标文本框 + 抓取坐标按钮
		int row2Y = y + 20;
		cx = x + 2;
		String statusLabel = StringUtils.translate(entry.isEnabled() ? STATUS_ON_KEY : STATUS_OFF_KEY);
		int statusW = this.getStringWidth(statusLabel);
		int row2AvailableW = (this.x + this.width) - cx - gap;
		// 按钮宽度按可用宽度比例计算并钳制，坐标框吃剩余宽度（小窗口下保证不溢出滚动条区域）
		int toggleW = Math.min(70, Math.max(50, row2AvailableW * 14 / 100));
		int grabW = Math.min(90, Math.max(60, row2AvailableW * 18 / 100));
		int coordW = Math.max(80, row2AvailableW - statusW - 4 - toggleW - grabW - 3 * gap);

		// 启用状态指示文本：仅作展示（§a[开]/§c[关]），点击切换走下方「启用/禁用」按钮
		this.addLabel(cx, row2Y + 6, statusW, 8, 0xFFFFFFFF, statusLabel);
		cx += statusW + 4;

		// 启用开关：写入条目 enabled（正式键 autotrade.gui.item_io.enabled/disabled：按钮显示「点击后执行的动作」，
		// 条目当前启用时显示「禁用」动作、当前禁用时显示「启用」动作）
		String toggleLabel = StringUtils
				.translate(entry.isEnabled() ? "autotrade.gui.item_io.disabled" : "autotrade.gui.item_io.enabled");
		ButtonGeneric toggleBtn = new ButtonGeneric(cx, row2Y, toggleW, 20, toggleLabel);
		this.addButton(toggleBtn, (button, mouseButton) -> {
			entry.setEnabled(!entry.isEnabled());
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		});
		cx += toggleW + gap;

		// 坐标文本框：ConfigCoordinate 校验语义，Enter/失焦提交（输入期间不保存不重建）
		coordField = this.createTextField(cx, row2Y + 1, coordW - 4, 17);
		coordField.setMaxLength(48);
		coordField.setText(entry.getX() + " " + entry.getY() + " " + entry.getZ());
		committedCoordText = coordField.getText();
		registerField(coordField);
		cx += coordW + gap;

		// 抓取坐标按钮：写入玩家脚下方块坐标（旧独立编辑屏 grabContainer 语义）
		ButtonGeneric grabBtn = new ButtonGeneric(cx, row2Y, grabW, 20,
				StringUtils.translate("autotrade.gui.item_io.grab_container"));
		this.addButton(grabBtn, (button, mouseButton) -> {
			BlockPos pos = grabFootBlockPos();
			if (pos == null)
				return;
			entry.setX(pos.getX());
			entry.setY(pos.getY());
			entry.setZ(pos.getZ());
			saveEntry();
			if (onCommit != null)
				onCommit.run();
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.item_io_container_set",
					pos.getX(), pos.getY(), pos.getZ());
		});
	}

	@Override
	public boolean hasPendingModifications() {
		// 任一文本框内容与最近一次提交值不同即视为有待提交修改（覆盖基类仅主字段的判断）；
		// 输出行无「每次拿取」字段（takeAmountField 为 null），跳过该项比较
		return !coordField.getText().equals(committedCoordText)
				|| !thresholdField.getText().equals(committedThresholdText)
				|| (takeAmountField != null && !takeAmountField.getText().equals(committedTakeAmountText));
	}

	@Override
	public void applyNewValueToConfig() {
		if (!hasPendingModifications())
			return;
		boolean changed = false;

		// 坐标：ConfigCoordinate 解析语义，非法格式恢复原值并提示（复用现有提示键，与旧编辑屏护栏一致）
		String coordText = coordField.getText().trim();
		BlockPos pos = ConfigCoordinate.parse(coordText);
		if (pos == null) {
			coordField.setText(committedCoordText);
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.invalid_pos");
		} else if (!coordText.equals(committedCoordText)) {
			entry.setX(pos.getX());
			entry.setY(pos.getY());
			entry.setZ(pos.getZ());
			changed = true;
		}

		// 阈值/单次数量：非法输入回退已保存值，越界钳制到 1..2304（与旧 ConfigInteger(1, 2304) 语义一致）；
		// 输出行无「每次拿取」字段，仅处理阈值
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

		committedCoordText = coordField.getText();
		committedThresholdText = thresholdField.getText();
		if (takeAmountField != null) {
			committedTakeAmountText = takeAmountField.getText();
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
		// Enter 提交：任一文本框聚焦时触发提交（基类仅处理主文本框，这里覆盖三个框）
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
		// 先让三个文本框处理点击（聚焦/取消聚焦），再走基类的按钮/子控件路径
		boolean ret = false;
		for (GuiTextFieldGeneric field : textFields) {
			ret |= field.mouseClicked(mouseX, mouseY, mouseButton);
		}
		return super.onMouseClickedImpl(mouseX, mouseY, mouseButton) || ret;
	}

	@Override
	protected void drawTextFields(int mouseX, int mouseY, DrawContext drawContext) {
		// 绘制全部三个文本框（基类仅绘制主文本框）
		for (GuiTextFieldGeneric field : textFields) {
			field.render(drawContext, mouseX, mouseY, 0f);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
		super.render(mouseX, mouseY, selected, drawContext);
		// 行分隔线（沿用旧行视觉参数）
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
		String json = Configs.Generic.ITEM_IO.getStringValue();
		Configs.Generic.ITEM_IO.setValueFromString(ItemIOList.upsertItem(json, item, isInput, entry));
		Configs.saveToFile();
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
