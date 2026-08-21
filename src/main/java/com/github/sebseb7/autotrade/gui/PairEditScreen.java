package com.github.sebseb7.autotrade.gui;

import com.github.sebseb7.autotrade.Reference;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.options.ConfigItem;
import com.github.sebseb7.autotrade.gui.widget.ItemIconWidget;
import com.github.sebseb7.autotrade.gui.widget.TradePairEditPanel;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairList;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.util.StringUtils;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/**
 * 交易对独立编辑屏（复原旧 PairEditScreen）：从「交易对」选项卡点击「编辑」打开的全屏页面， 顶部为抓取按钮组（抓取主手物品写入
 * give/give2/get），中部为 5 个配置项（give/give2/get/limit/note， 行高紧凑 22px，ConfigItem
 * 行渲染物品图标），底部为返回按钮（返回主设置页并停留在交易对选项卡）。
 *
 * <p>
 * 配置项与自动保存/抓取逻辑委托 {@link TradePairEditPanel}（与旧独立编辑屏的编辑逻辑同源）； 每交易对的容器 IO
 * 配置已随「按物品配置重构」移除，此处只编辑交易本身字段。
 * </p>
 */
public class PairEditScreen extends GuiConfigsBase {
	private final int pairIndex;
	private TradePairEditPanel editPanel;

	public PairEditScreen(int pairIndex) {
		super(10, 50, Reference.MOD_ID, null, "autotrade.gui.title.pair_edit");
		this.pairIndex = pairIndex;
	}

	@Override
	protected int getBrowserWidth() {
		return this.width * 8 / 10;
	}

	@Override
	protected int getConfigWidth() {
		int browserWidth = this.getBrowserWidth();
		int desired = Math.max(200, browserWidth * 2 / 5);
		// 预留空间：标签与控件间距 10 + 控件与重置按钮间距 2 + 重置按钮宽度（RESET 约 40，中文约 28）
		int reserved = 58;
		// 最大标签宽度（与 malilib 内部 maxLabelWidth 计算方式一致）
		int maxLabel = 0;
		for (ConfigOptionWrapper wrapper : this.getConfigs()) {
			if (wrapper.getType() == ConfigOptionWrapper.Type.CONFIG && wrapper.getConfig() != null) {
				maxLabel = Math.max(maxLabel, this.getStringWidth(wrapper.getConfig().getConfigGuiDisplayName()));
			}
		}
		// 收窄值控件宽度，保证「标签 + 值控件 + 重置按钮」不超出浏览器区域（滚动条），任何 GUI 缩放下都不溢出
		return Math.max(120, Math.min(desired, browserWidth - 14 - maxLabel - reserved));
	}

	@Override
	public void initGui() {
		List<TradePair> pairs = TradePairList.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
		if (pairIndex < 0 || pairIndex >= pairs.size()) {
			// 无效索引：仅显示返回按钮（防越界崩溃护栏）
			this.setListPosition(this.width / 10, this.getListY());
			this.reCreateListWidget();
			super.initGui();
			ButtonGeneric back = new ButtonGeneric(this.width / 10, this.height - 30, 80, 20,
					StringUtils.translate("autotrade.gui.pair_edit.back"));
			this.addButton(back, (b, mb) -> GuiBase.openGui(new GuiConfigs()));
			return;
		}
		TradePair currentPair = pairs.get(pairIndex);
		// 编辑面板：维护 5 个配置项与自动保存；保存成功后重建本屏刷新显示；保存前提交列表控件中的待定文本输入
		this.editPanel = new TradePairEditPanel(pairIndex, currentPair, this::initGui, () -> {
			if (this.getListWidget() != null) {
				this.getListWidget().applyPendingModifications();
			}
		});
		this.editPanel.rebuildConfigs();

		// 先创建配置项再创建列表控件，保证 getConfigWidth() 能按实际标签宽度计算（列表宽度创建后不再更新）
		this.setListPosition(this.width / 10, this.getListY());
		this.reCreateListWidget();
		super.initGui();

		// 标题带交易序号与启停状态（正式键 autotrade.gui.pair_edit.pair_title/status_on/status_off）
		String statusKey = currentPair.isEnabled()
				? "autotrade.gui.pair_edit.status_on"
				: "autotrade.gui.pair_edit.status_off";
		this.setTitle(StringUtils.translate("autotrade.gui.pair_edit.pair_title", pairIndex + 1,
				StringUtils.translate(statusKey)));

		// 顶部抓取按钮行（仿旧屏布局）：抓取成本 / 抓取成本 2 / 抓取产物
		String[] grabLabels = {StringUtils.translate("autotrade.gui.pair_edit.grab_give"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_give2"),
				StringUtils.translate("autotrade.gui.pair_edit.grab_get")};
		int btnGap = 4;
		int needed = 0;
		for (String label : grabLabels) {
			needed = Math.max(needed, this.getStringWidth(label) + 10);
		}
		int btnWidth = Math.min(needed, (this.getBrowserWidth() - 2 * btnGap) / 3);
		int btnX = this.width / 10;
		// 抓取成本：主手物品写入 giveItem
		ButtonGeneric grabGive = new ButtonGeneric(btnX, 26, btnWidth, 20, grabLabels[0]);
		this.addButton(grabGive, (b, mb) -> {
			if (this.editPanel != null) {
				this.editPanel.grabItem(true);
			}
		});
		// 抓取成本 2：主手物品写入 giveItem2
		ButtonGeneric grabGive2 = new ButtonGeneric(btnX + btnWidth + btnGap, 26, btnWidth, 20, grabLabels[1]);
		this.addButton(grabGive2, (b, mb) -> {
			if (this.editPanel != null) {
				this.editPanel.grabItem2();
			}
		});
		// 抓取产物：主手物品写入 getItem
		ButtonGeneric grabGet = new ButtonGeneric(btnX + 2 * (btnWidth + btnGap), 26, btnWidth, 20, grabLabels[2]);
		this.addButton(grabGet, (b, mb) -> {
			if (this.editPanel != null) {
				this.editPanel.grabItem(false);
			}
		});

		// 底部返回按钮：返回主设置页（静态 tab 仍停留在「交易对」选项卡）
		ButtonGeneric backBtn = new ButtonGeneric(this.width / 10, this.height - 30, 80, 20,
				StringUtils.translate("autotrade.gui.pair_edit.back"));
		this.addButton(backBtn, (b, mb) -> GuiBase.openGui(new GuiConfigs()));
	}

	@Override
	public List<ConfigOptionWrapper> getConfigs() {
		if (editPanel == null)
			return ImmutableList.of();
		return editPanel.getConfigs();
	}

	@Override
	protected WidgetListConfigOptions createListWidget(int listX, int listY) {
		// 行高沿用 malilib 默认紧凑行高（22px），ConfigItem 行渲染物品图标（CustomWidgetConfigOption）
		return new CustomWidgetListConfigOptions(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
				this.getConfigWidth(), 0.f, this.useKeybindSearch(), this);
	}

	private static class CustomWidgetListConfigOptions extends WidgetListConfigOptions {
		public CustomWidgetListConfigOptions(int x, int y, int width, int height, int configWidth, float zLevel,
				boolean useKeybindSearch, GuiConfigsBase parent) {
			super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
		}

		@Override
		protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
				ConfigOptionWrapper wrapper) {
			return new CustomWidgetConfigOption(x, y, this.browserEntryWidth, this.browserEntryHeight,
					this.maxLabelWidth, this.configWidth, wrapper, listIndex, (IKeybindConfigGui) this.parent, this);
		}
	}

	/** 配置行控件：ConfigItem（物品编码串）行在值控件旁渲染物品图标，悬浮显示物品 tooltip */
	private static class CustomWidgetConfigOption extends WidgetConfigOption {
		private ItemStack hoverIconStack;
		private int iconX;
		private int iconY;

		public CustomWidgetConfigOption(int x, int y, int width, int height, int labelWidth, int configWidth,
				ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
				WidgetListConfigOptionsBase<?, ?> parent) {
			super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
		}

		@Override
		protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth,
				IConfigBase config) {
			if (config instanceof ConfigItem) {
				String encoded = ((IConfigValue) config).getStringValue();
				ItemStack stack = ItemStringHelper.decode(encoded);
				hoverIconStack = stack;
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
				iconX = x + labelWidth - 10;
				iconY = y + 1;
				if (!stack.isEmpty()) {
					this.addWidget(new ItemIconWidget(iconX, iconY, stack));
				}
			} else {
				hoverIconStack = null;
				super.addConfigOption(x, y, zLevel, labelWidth, configWidth, config);
			}
		}

		@Override
		public void postRenderHovered(int mouseX, int mouseY, boolean hovered, DrawContext ctx) {
			// 悬浮物品图标区域时渲染 tooltip（与旧独立编辑屏已验证的相同模式，含空值防护）
			if (hoverIconStack != null && !hoverIconStack.isEmpty() && ctx != null) {
				MinecraftClient mc = MinecraftClient.getInstance();
				if (mouseX >= iconX && mouseX <= iconX + 18 && mouseY >= iconY && mouseY <= iconY + 18
						&& mc.player != null && mc.textRenderer != null) {
					List<Text> tooltip = hoverIconStack.getTooltip(mc.player, TooltipContext.Default.ADVANCED);
					ctx.drawTooltip(mc.textRenderer, tooltip, mouseX, mouseY);
				}
			}
			super.postRenderHovered(mouseX, mouseY, hovered, ctx);
		}
	}
}
