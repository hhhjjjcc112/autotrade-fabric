package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.wrappers.TextFieldWrapper;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * 物品 IO 派生行控件公共基类（方案 B：可变高行拆分为固定高头部行 + 固定高记录行）。
 *
 * <p>
 * 旧实现 {@code ItemIOEntryWidget} 把单个 (item, 方向) 渲染为一条可变高行（行高 = 20 + 20×记录数），
 * 记录数过多时行高超过列表视口，malilib {@code WidgetListBase.createListEntryWidgetIfSpace}
 * 的空间判定 （usedHeight + height &gt; usableHeight 返回 null）会拒绝该条目并 break
 * 整列表，导致列表整页空白。 本基类 + {@link ItemIOHeaderWidget}（固定高 20px 头部行）+
 * {@link ItemIORecordWidget}（固定高 20px 单条记录行）拆分后，全部条目固定 20px，空间判定永不拒绝条目，根治空白问题。
 * </p>
 *
 * <p>
 * 本基类承载两类的公共骨架：翻译键常量、FieldKind/FieldRef 焦点定位身份、文本框注册与分发
 * （绘制/键盘/字符/点击）、提交语义（hasPendingModifications/applyNewValueToConfig/wasConfigModified）、
 * 两阶段初始化（构造期缓存布局参数 + {@link #initRowLayout()} 重放）与公共工具
 * （parseAmount/saveEntry/grabFootBlockPos/HoverLabelWidget/CountLabelWidget）。
 * 子类各自实现 {@link #layoutRow}（头部段/单条记录段布局）与焦点定位（getFocusedFieldRef/focusField）。
 * </p>
 *
 * <p>
 * 保存路径统一走 {@link ItemIOCache#upsert}（按 (item, 方向) 更新或追加）并回写
 * {@code Configs.Generic.ITEM_IO}；行内文本框为「回车/失焦提交」：光标输入期间不触发保存与列表重建（Enter 由
 * {@link #onKeyTypedImpl} 处理，失焦由列表的 {@code applyPendingModifications} 路径处理），
 * 非法输入（维度格式不符 / 坐标格式不符 / 数量越界）恢复原值并提示，不写入。
 * </p>
 */
public abstract class ItemIOBaseWidget extends WidgetConfigOption {
	/** 头部行高（状态文本/icon/统计/行级开关/数量块/添加按钮） */
	public static final int HEADER_HEIGHT = 20;
	/** 单条位置记录行高（序号/状态/维度/坐标/抓取/启用禁用/删除） */
	public static final int RECORD_HEIGHT = 20;
	/**
	 * 头部行占位配置名前缀：父列表用 {@code HEADER_NAME_PREFIX + i} 命名占位 ConfigString，
	 * 本控件据此识别头部行（i = 行数据下标）
	 */
	public static final String HEADER_NAME_PREFIX = "io_head_";
	/**
	 * 记录行占位配置名前缀：父列表用 {@code RECORD_NAME_PREFIX + i + "_" + j} 命名占位 ConfigString，
	 * 本控件据此识别记录行（i = 行数据下标，j = 记录下标）
	 */
	public static final String RECORD_NAME_PREFIX = "io_rec_";
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
	protected static final int MIN_AMOUNT = 1;
	protected static final int MAX_AMOUNT = 36;
	/**
	 * 计数标签翻译键：正式键 autotrade.gui.item_io.stats（格式参数 %d/%d = 启用/禁用交易对数）
	 */
	protected static final String STATS_KEY = "autotrade.gui.item_io.stats";
	/**
	 * 阈值/每次拿取字段的简写文本标签翻译键（输入框前显示，避免裸数字无含义；悬浮显示完整说明）
	 */
	protected static final String THRESHOLD_SHORT_KEY = "autotrade.gui.item_io.threshold_short";
	protected static final String TAKE_AMOUNT_SHORT_KEY = "autotrade.gui.item_io.take_amount_short";
	/** 阈值字段的悬浮完整说明翻译键（分方向：输入 = 补货，输出 = 清出；单位 = 组） */
	protected static final String THRESHOLD_TIP_INPUT_KEY = "autotrade.gui.item_io.threshold_tip_input";
	protected static final String THRESHOLD_TIP_OUTPUT_KEY = "autotrade.gui.item_io.threshold_tip_output";
	/** 每次拿取字段的悬浮完整说明翻译键（单位 = 组，仅输入方向） */
	protected static final String TAKE_AMOUNT_TIP_KEY = "autotrade.gui.item_io.take_amount_tip";
	/**
	 * 启用状态指示文本翻译键：仅作状态展示（[开]/[关]），实际开关操作由「启用/禁用」按钮承担；
	 * 键值不携带颜色代码，渲染时按启用状态直接选色（STATUS_ON_COLOR/STATUS_OFF_COLOR）
	 */
	protected static final String STATUS_ON_KEY = "autotrade.gui.item_io.status_on";
	protected static final String STATUS_OFF_KEY = "autotrade.gui.item_io.status_off";
	/** 状态文本颜色：启用绿 / 禁用红（样式同交易对列表状态文本） */
	protected static final int STATUS_ON_COLOR = 0xFF55FF55;
	protected static final int STATUS_OFF_COLOR = 0xFFFF5555;
	/** 条目级状态文本悬浮提示翻译键（区分层级：条目级总开关） */
	protected static final String STATUS_TIP_ENTRY_ON_KEY = "autotrade.gui.item_io.status_tip_entry_on";
	protected static final String STATUS_TIP_ENTRY_OFF_KEY = "autotrade.gui.item_io.status_tip_entry_off";
	/** 记录级状态文本悬浮提示翻译键（与条目级开关 AND 生效） */
	protected static final String STATUS_TIP_RECORD_ON_KEY = "autotrade.gui.item_io.status_tip_record_on";
	protected static final String STATUS_TIP_RECORD_OFF_KEY = "autotrade.gui.item_io.status_tip_record_off";
	/** 启停按钮悬浮提示翻译键（动作语义：点击后发生什么，消除「按钮显示的是当前状态还是动作」歧义） */
	protected static final String TOGGLE_BTN_TIP_ON_KEY = "autotrade.gui.item_io.toggle_btn_tip_on";
	protected static final String TOGGLE_BTN_TIP_OFF_KEY = "autotrade.gui.item_io.toggle_btn_tip_off";
	protected static final String REC_TOGGLE_BTN_TIP_ON_KEY = "autotrade.gui.item_io.rec_toggle_btn_tip_on";
	protected static final String REC_TOGGLE_BTN_TIP_OFF_KEY = "autotrade.gui.item_io.rec_toggle_btn_tip_off";
	/** 记录行序号翻译键（格式参数 %d = 记录下标+1，灰色渲染；序号使记录级状态与头部条目级状态错位，表达层级从属） */
	protected static final String RECORD_NUMBER_KEY = "autotrade.gui.item_io.record_number";
	/** 记录行序号颜色（灰色，弱于主内容） */
	protected static final int RECORD_NUM_COLOR = 0xFFAAAAAA;
	/** 记录行维度标签简写翻译键（悬浮显示完整说明） */
	protected static final String DIMENSION_SHORT_KEY = "autotrade.gui.item_io.dimension_short";
	/** 记录行维度标签悬浮完整说明翻译键 */
	protected static final String DIMENSION_TIP_KEY = "autotrade.gui.item_io.dimension_tip";
	/** 记录行抓取容器按钮简写翻译键（悬浮显示完整说明） */
	protected static final String GRAB_CONTAINER_SHORT_KEY = "autotrade.gui.item_io.grab_container_short";
	/** 抓取容器按钮悬浮完整说明翻译键 */
	protected static final String GRAB_CONTAINER_TIP_KEY = "autotrade.gui.item_io.grab_container_tip";
	/** 抓取模式下「保存」按钮简写翻译键（抓取热键按下后，抓取按钮变为保存按钮） */
	protected static final String GRAB_SAVE_SHORT_KEY = "autotrade.gui.item_io.grab_container_save_short";
	/** 抓取模式下「保存」按钮悬浮完整说明翻译键 */
	protected static final String GRAB_SAVE_TIP_KEY = "autotrade.gui.item_io.grab_container_save_tip";
	/** 记录行删除按钮翻译键（✕，点击即删除该记录并保存，无确认弹窗） */
	protected static final String DELETE_KEY = "autotrade.gui.item_io.delete";
	/** 删除按钮悬浮提示翻译键 */
	protected static final String DELETE_TIP_KEY = "autotrade.gui.item_io.delete_tip";
	/** 头部行「添加容器」按钮翻译键（原底部行按钮上移；点击新增一条记录） */
	protected static final String ADD_LOCATION_KEY = "autotrade.gui.item_io.add_location";
	/** 添加容器按钮悬浮提示翻译键 */
	protected static final String ADD_LOCATION_TIP_KEY = "autotrade.gui.item_io.add_location_tip";
	/** 启停按钮显示文本翻译键（按钮显示「点击后执行的动作」，悬浮补当前状态；头部行总开关与记录行共用） */
	protected static final String TOGGLE_ON_LABEL = "autotrade.gui.item_io.enabled";
	protected static final String TOGGLE_OFF_LABEL = "autotrade.gui.item_io.disabled";
	/** 非法维度提示翻译键（非空且无法解析为 Identifier 时提示并恢复原值） */
	protected static final String INVALID_DIMENSION_KEY = "autotrade.message.invalid_dimension";
	/** 计数标签正常色（有启用交易对使用该物品时） */
	protected static final int STATS_NORMAL_COLOR = 0xFFAAAAAA;
	/** 计数标签「当前不生效」高亮色（X=0 时使用，提示该物品未被任何启用交易对使用） */
	protected static final int STATS_INACTIVE_COLOR = 0xFFAA5555;
	/** 「当前不生效」悬浮提示翻译键（X=0 时悬浮显示，缺失时渲染键名） */
	protected static final String STATS_INACTIVE_HINT_KEY = "autotrade.gui.item_io.inactive_hint";

	/** 行物品编码串（派生行唯一标识，供列表重建后按行恢复焦点） */
	protected final String item;
	/** 行方向：true = 输入（give ∪ give2），false = 输出（get） */
	protected final boolean isInput;
	/** 当前条目（由调用方按 (item, 方向) 匹配或构造占位条目，本控件读写其值并 upsert） */
	protected final ItemIO entry;
	/** 派生统计（可为 null：旧列表屏行无派生统计，不渲染计数标签） */
	protected final IoItemDeriver.IoItemStat stat;
	/**
	 * 提交（保存）后执行的列表刷新回调（由列表控件注入，如 {@code () -> list.refreshEntries()}；
	 * 不得重建宿主屏，否则点击分发会中断在已脱离的旧控件上导致焦点丢失）
	 */
	protected final Runnable onCommit;

	/** 全部文本框（按下标与 committedTexts 对齐；仅布局期填充） */
	protected final List<GuiTextFieldGeneric> textFields = new ArrayList<>();
	/** 最近一次已提交的各文本框内容（按下标与 textFields 对齐；registerField 时快照，提交后刷新） */
	protected final List<String> committedTexts = new ArrayList<>();
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
	public ItemIOBaseWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
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
	public abstract FieldRef getFocusedFieldRef();

	/** 聚焦指定字段（列表重建后恢复焦点用，光标置于行尾）；字段不存在/种类不匹配时静默跳过 */
	public abstract void focusField(FieldRef ref);

	/** 聚焦单个文本框（光标置于行尾）；字段不存在（null）时静默跳过 */
	protected void focusText(GuiTextFieldGeneric field) {
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
			this.layoutRow(this.layoutX, this.layoutY, this.layoutZLevel, this.layoutLabelWidth, this.layoutConfigWidth,
					this.layoutConfig);
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
		if (name == null || (!name.startsWith(HEADER_NAME_PREFIX) && !name.startsWith(RECORD_NAME_PREFIX))) {
			super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			return;
		}
		this.layoutRow(x, y, zLevel, labelWidth, configWidth, config);
	}

	/** 子类行布局（两阶段初始化第二阶段的实际布局；仅布局期调用一次）：头部段或单条记录段 */
	protected abstract void layoutRow(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config);

	@Override
	public boolean hasPendingModifications() {
		// 任一文本框内容与最近一次提交值不同即视为有待提交修改（覆盖基类仅主字段的判断）；
		// 全部文本框按下标与提交快照逐条比较（头部字段/记录字段统一由 registerField 登记）
		for (int i = 0; i < textFields.size(); i++) {
			if (!textFields.get(i).getText().equals(committedTexts.get(i)))
				return true;
		}
		return false;
	}

	@Override
	public void applyNewValueToConfig() {
		if (!hasPendingModifications())
			return;
		// 子类校验并应用各文本框值（非法输入恢复原值并提示，不写入）
		boolean changed = applyPendingValues();
		// 用已保存值回写输入框（如数量钳制后的回写），并刷新提交快照
		rewriteFieldsFromSaved();
		refreshCommittedSnapshots();
		if (changed) {
			saveEntry();
			if (onCommit != null)
				onCommit.run();
		}
	}

	/** 子类：校验并应用各文本框值到条目/记录；非法输入恢复原值并提示一次；返回是否有实际变更 */
	protected abstract boolean applyPendingValues();

	/** 子类：用已保存值回写输入框（如数量钳制后的回写）；无回写需求时保持空实现 */
	protected void rewriteFieldsFromSaved() {
	}

	/** 刷新全部文本框的提交快照（applyNewValueToConfig 末尾调用，与 textFields 按下标对齐） */
	private void refreshCommittedSnapshots() {
		for (int i = 0; i < textFields.size(); i++) {
			committedTexts.set(i, textFields.get(i).getText());
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
		// 行分隔线（沿用旧行视觉参数；固定 20px 行高，每行底部一条分隔线）
		drawContext.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF555555);
	}

	/** 注册文本框：进入父列表的 TAB 循环/失焦提交清单，并快照当前文本为提交基线；监听器传 null = 无逐键回调（仅 Enter/失焦提交） */
	protected void registerField(GuiTextFieldGeneric field) {
		textFields.add(field);
		committedTexts.add(field.getText());
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
	protected static int parseAmount(String text, int fallback) {
		try {
			int v = Integer.parseInt(text.trim());
			return Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, v));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** 按 (item, 方向) upsert 当前条目并保存到配置文件 */
	protected void saveEntry() {
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
	protected class HoverLabelWidget extends WidgetBase {
		private final String text;
		private final int color;
		private final String tooltipKey;

		HoverLabelWidget(int x, int y, String text, int color, String tooltipKey) {
			super(x, y, ItemIOBaseWidget.this.getStringWidth(text), 8);
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
	 * 计数标签控件：渲染「开 X · 关 Y」（启用/禁用记录数）；X=0（无启用交易对使用该物品，当前不生效）时用高亮色并附悬浮提示
	 */
	protected class CountLabelWidget extends WidgetBase {
		private final String text;
		private final boolean inactive;

		CountLabelWidget(int x, int y, String text, boolean inactive) {
			super(x, y, ItemIOBaseWidget.this.getStringWidth(text), 8);
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

	/**
	 * 抓取模式下的闪动按钮（记录行「保存」按钮与头部行「+ 添加」按钮共用）：文本色按 ~250ms 周期在琥珀/橙之间交替闪动， 其余渲染与
	 * ButtonGeneric 完全一致（复制其 render 全量逻辑，仅文本色行不同）
	 */
	protected static class FlashingButton extends ButtonGeneric {
		FlashingButton(int x, int y, int width, int height, String label) {
			super(x, y, width, height, label);
		}

		@Override
		public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
			// 复制 ButtonGeneric.render 全量逻辑，仅文本色行改为闪动色（琥珀 0xFFD070 / 橙 0xFF8A00 交替）
			if (this.visible) {
				this.hovered = mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width
						&& mouseY < this.y + this.height;
				RenderUtils.color(1f, 1f, 1f, 1f);
				if (this.renderDefaultBackground) {
					drawContext.drawGuiTexture(this.getTexture(this.hovered), this.x, this.y, this.width, this.height);
				}
				if (this.icon != null) {
					int offset = this.renderDefaultBackground ? 4 : 0;
					int x = this.alignment == LeftRight.LEFT
							? this.x + offset
							: this.x + this.width - this.icon.getWidth() - offset;
					int y = this.y + (this.height - this.icon.getHeight()) / 2;
					int u = this.icon.getU() + this.getTextureOffset(this.hovered) * this.icon.getWidth();
					this.bindTexture(this.icon.getTexture());
					RenderUtils.drawTexturedRect(x, y, u, this.icon.getV(), this.icon.getWidth(),
							this.icon.getHeight());
				}
				if (org.apache.commons.lang3.StringUtils.isBlank(this.displayString) == false) {
					int y = this.y + (this.height - 8) / 2;
					// 闪动文本色：250ms 周期琥珀/橙交替（仅此一行与 ButtonGeneric 不同）
					int color = (System.currentTimeMillis() / 250) % 2 == 0 ? 0xFFD070 : 0xFF8A00;
					if (this.textCentered) {
						this.drawCenteredStringWithShadow(this.x + this.width / 2, y, color, this.displayString,
								drawContext);
					} else {
						int x = this.x + 6;
						if (this.icon != null && this.alignment == LeftRight.LEFT) {
							x += this.icon.getWidth() + 2;
						}
						this.drawStringWithShadow(x, y, color, this.displayString, drawContext);
					}
				}
			}
		}
	}
}
