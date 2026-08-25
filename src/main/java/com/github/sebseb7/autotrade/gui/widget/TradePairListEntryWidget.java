package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

// 交易对列表行控件：单行布局（行高 20），渲染单个交易对的图标/标签/压缩按钮组（启停/编辑/删除）与分隔线
// （由旧独立列表屏的原嵌套类 CustomPairListWidget 抽取而来，2026-08-18 改造为单行压缩布局）
public class TradePairListEntryWidget extends WidgetConfigOption {
	private final Runnable refreshAction;
	private final Consumer<Integer> editAction;
	/**
	 * 物品图标点击跳转 IO 页回调（Boolean=是否输入方向
	 * true=IO输入(give/give2)、false=IO输出(get)；String=物品编码串；null = 无跳转行为）
	 */
	private final BiConsumer<Boolean, String> jumpToIoAction;
	private ItemStack giveStack = ItemStack.EMPTY;
	private ItemStack getStack = ItemStack.EMPTY;
	/**
	 * 当前条目备注（悬浮条目行时显示；addConfigOption 调用发生在 super 构造器内，而字段初始化器在其后才执行并会覆盖该值，
	 * 故此处不能带初始化器（否则备注恒为空））
	 */
	private String note;
	/** 物品图标引用（悬浮检测排除用：图标上方显示物品 tooltip 而非备注） */
	private ItemIconWidget giveIcon;
	private ItemIconWidget give2Icon;
	private ItemIconWidget getIcon;

	public TradePairListEntryWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent) {
		this(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent, null, null, null);
	}

	// editAction：编辑按钮回调（参数为交易对下标；null = 编辑按钮无操作——选项卡始终注入回调，无回调即死代码路径）
	// refreshAction：启停/删除保存后的刷新回调（null = 默认重建宿主屏）
	public TradePairListEntryWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent, Consumer<Integer> editAction, Runnable refreshAction) {
		this(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent, editAction, refreshAction,
				null);
	}

	// jumpToIoAction：物品图标点击跳转 IO 页回调（null = 无跳转行为）
	public TradePairListEntryWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent, Consumer<Integer> editAction, Runnable refreshAction,
			BiConsumer<Boolean, String> jumpToIoAction) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
		this.editAction = editAction;
		this.jumpToIoAction = jumpToIoAction;
		if (refreshAction != null) {
			this.refreshAction = refreshAction;
		} else if (host instanceof GuiBase) {
			// 默认刷新行为：重建宿主配置屏（与原先 parentScreen.initGui() 等价）
			GuiBase base = (GuiBase) host;
			this.refreshAction = base::initGui;
		} else {
			this.refreshAction = null;
		}
	}

	@Override
	protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
		String name = config.getName();
		if (name != null && name.startsWith("pair_")) {
			int idx;
			try {
				idx = Integer.parseInt(name.substring(5));
			} catch (NumberFormatException e) {
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
				return;
			}
			// 缓存访问器：每行构造共享同一次解析结果（仅 get/读取，不改动交易对）
			List<TradePair> pairs = TradePairCache.getAll();
			if (idx < 0 || idx >= pairs.size()) {
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
				return;
			}
			TradePair p = pairs.get(idx);

			// 解码物品栈
			giveStack = ItemStringHelper.decode(p.getGiveItem());
			getStack = ItemStringHelper.decode(p.getGetItem());
			// 解码可选第二给出物品栈（空串时为单成本交易对，不渲染图标/Input2 文本）
			ItemStack give2Stack = ItemStringHelper.decode(p.getGiveItem2());
			note = p.getNote() != null ? p.getNote() : "";

			// 单行布局（行高 20）：左侧为交易内容（序号/状态/物品/数量），右侧为等宽压缩按钮组
			int cx = x + 2;

			String indexText = StringUtils.translate("autotrade.gui.pair_list.trade_number", idx + 1);
			this.addLabel(cx, y + 5, this.getStringWidth(indexText), 8, 0xFFFFFFFF, indexText);
			cx += this.getStringWidth(indexText) + 6;

			String statusText = StringUtils.translate(
					p.isEnabled() ? "autotrade.gui.pair_list.status_on" : "autotrade.gui.pair_list.status_off");
			int statusWidth = this.getStringWidth(statusText);
			this.addLabel(cx, y + 5, statusWidth, 8, 0xFFFFFFFF, statusText);
			cx += statusWidth + 6;

			// 物品悬浮提示由 ItemIconWidget 自行处理
			if (!giveStack.isEmpty()) {
				giveIcon = this
						.addWidget(new ItemIconWidget(cx, y + 1, giveStack, () -> jumpToIo(true, p.getGiveItem())));
			}
			cx += 22;
			// give1 数量标签（x{limit}，每笔交易上限）
			String limitLabel = StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getLimit());
			int limitWidth = this.getStringWidth(limitLabel);
			this.addLabel(cx, y + 5, limitWidth, 8, 0xFFFFFFFF, limitLabel);
			cx += limitWidth + 6;

			// give2 图标 + 其每笔数量（x{give2Count}，0 = 未记录 → 不显示）；单成本交易对时整个块跳过（回归防护）
			if (!give2Stack.isEmpty()) {
				give2Icon = this
						.addWidget(new ItemIconWidget(cx, y + 1, give2Stack, () -> jumpToIo(true, p.getGiveItem2())));
				cx += 22;
				if (p.getGive2Count() > 0) {
					String count2Label = StringUtils.translate("autotrade.gui.pair_list.limit_prefix",
							p.getGive2Count());
					int count2Width = this.getStringWidth(count2Label);
					this.addLabel(cx, y + 5, count2Width, 8, 0xFFFFFFFF, count2Label);
					cx += count2Width + 6;
				}
			}

			String arrow = StringUtils.translate("autotrade.gui.pair_list.arrow");
			int arrowWidth = this.getStringWidth(arrow);
			this.addLabel(cx, y + 5, arrowWidth, 8, 0xFFFFFFFF, arrow);
			cx += arrowWidth + 6;

			if (!getStack.isEmpty()) {
				getIcon = this
						.addWidget(new ItemIconWidget(cx, y + 1, getStack, () -> jumpToIo(false, p.getGetItem())));
			}
			cx += 22;
			// 产出物品每笔数量（x{getCount}，0 = 未记录 → 不显示）
			if (p.getGetCount() > 0) {
				String getCountLabel = StringUtils.translate("autotrade.gui.pair_list.limit_prefix", p.getGetCount());
				int getCountWidth = this.getStringWidth(getCountLabel);
				this.addLabel(cx, y + 5, getCountWidth, 8, 0xFFFFFFFF, getCountLabel);
				cx += getCountWidth + 6;
			}

			// 右侧固定块（从右往左排布）：[删除] [编辑] [启用]，3 个按钮等宽；按钮宽度按可用宽度压缩并钳制
			int gap = 4;
			// 行右边缘 = 控件绝对 x + 行宽（addConfigOption 收到的是绝对屏幕坐标，不能用 this.width 当右边界）
			int rightX = (this.x + this.width) - 4;
			int availableW = rightX - cx - gap;
			int btnW = Math.min(46, Math.max(36, (availableW - 2 * gap) / 3));

			int removeX = rightX - btnW;
			int editX = removeX - gap - btnW;
			int toggleX = editX - gap - btnW;

			// 备注不占行内空间：完整备注通过悬浮条目行查看（物品图标上方仍显示物品 tooltip）

			String enableLabel = StringUtils.translate(
					p.isEnabled() ? "autotrade.gui.pair_list.disable_btn" : "autotrade.gui.pair_list.enable_btn");
			ButtonGeneric toggleBtn = new ButtonGeneric(toggleX, y, btnW, 20, enableLabel);
			this.addButton(toggleBtn, (button, mouseButton) -> {
				TradePairCache.toggle(idx);
				if (refreshAction != null)
					refreshAction.run();
			});

			// Edit 按钮：使用自定义回调（打开独立编辑屏）；回调为 null 时无操作
			ButtonGeneric editBtn = new ButtonGeneric(editX, y, btnW, 20,
					StringUtils.translate("autotrade.gui.pair_list.edit"));
			this.addButton(editBtn, (button, mouseButton) -> {
				if (editAction != null) {
					editAction.accept(idx);
				}
			});

			// Remove 按钮
			ButtonGeneric removeBtn = new ButtonGeneric(removeX, y, btnW, 20,
					StringUtils.translate("autotrade.gui.pair_list.remove"));
			this.addButton(removeBtn, (button, mouseButton) -> {
				TradePairCache.remove(idx);
				if (refreshAction != null)
					refreshAction.run();
			});
		} else {
			super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
		super.render(mouseX, mouseY, selected, drawContext);
		// 分隔线
		drawContext.fill(this.x, this.y + this.height - 1, this.x + this.width, this.y + this.height, 0xFF555555);
	}

	// 悬浮交易对条目行（排除物品图标区域，避免与物品 tooltip 重叠）时显示完整备注
	@Override
	public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext drawContext) {
		// 先渲染子控件 tooltip（物品图标名称等）
		super.postRenderHovered(mouseX, mouseY, selected, drawContext);
		if (note != null && !note.isEmpty() && this.isMouseOver(mouseX, mouseY) && !isOverAnyItemIcon(mouseX, mouseY)) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mc.textRenderer != null) {
				drawContext.drawTooltip(mc.textRenderer,
						List.of(Text.literal(StringUtils.translate("autotrade.gui.pair_list.note_label", note))),
						mouseX, mouseY);
			}
		}
	}

	// 鼠标是否位于任一物品图标（give/give2/get）区域内（该区域显示物品 tooltip，不显示备注）
	private boolean isOverAnyItemIcon(int mouseX, int mouseY) {
		return (giveIcon != null && giveIcon.isMouseOver(mouseX, mouseY))
				|| (give2Icon != null && give2Icon.isMouseOver(mouseX, mouseY))
				|| (getIcon != null && getIcon.isMouseOver(mouseX, mouseY));
	}

	// 物品图标点击跳转 IO 页：转发给宿主屏注入的回调（null 防护；isInput=true 跳 IO输入，false 跳 IO输出）
	private void jumpToIo(boolean isInput, String item) {
		if (jumpToIoAction != null) {
			jumpToIoAction.accept(isInput, item);
		}
	}

}
