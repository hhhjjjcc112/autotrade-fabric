package com.github.sebseb7.autotrade.gui.widget;

import com.github.sebseb7.autotrade.config.options.ConfigItem;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

// 配置项条目控件：对 ConfigItem 类型配置在标签旁渲染物品图标并支持悬浮 tooltip（原配对编辑界面内部类拆分而来）
public class CustomWidgetConfigOption extends WidgetConfigOption {
	private ItemStack hoverIconStack;
	private int iconX, iconY;

	public CustomWidgetConfigOption(int x, int y, int width, int height, int labelWidth, int configWidth,
			ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
			WidgetListConfigOptionsBase<?, ?> parent) {
		super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
	}

	@Override
	protected void addConfigOption(int x, int y, float zLevel, int labelWidth, int configWidth, IConfigBase config) {
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
		if (hoverIconStack != null && !hoverIconStack.isEmpty() && ctx != null) {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (mouseX >= iconX && mouseX <= iconX + 18 && mouseY >= iconY && mouseY <= iconY + 18 && mc.player != null
					&& mc.textRenderer != null) {
				List<Text> tooltip = hoverIconStack.getTooltip(mc.player, TooltipContext.Default.ADVANCED);
				ctx.drawTooltip(mc.textRenderer, tooltip, mouseX, mouseY);
			}
		}
		super.postRenderHovered(mouseX, mouseY, hovered, ctx);
	}
}
