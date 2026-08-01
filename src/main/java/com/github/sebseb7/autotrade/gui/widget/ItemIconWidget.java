package com.github.sebseb7.autotrade.gui.widget;

import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public class ItemIconWidget extends WidgetBase {
	private final ItemStack stack;

	public ItemIconWidget(int x, int y, ItemStack stack) {
		super(x, y, 18, 18);
		this.stack = stack;
	}

	@Override
	public void render(int mouseX, int mouseY, boolean selected, DrawContext ctx) {
		if (!stack.isEmpty()) {
			ctx.drawItem(stack, getX() + 1, getY() + 1);
		}
	}

	// 鼠标悬浮在图标区域时渲染 ItemStack 的详细 tooltip（与 PairEditScreen 中已验证的相同模式）
	@Override
	public void postRenderHovered(int mouseX, int mouseY, boolean selected, DrawContext ctx) {
		// 空 stack / null ctx 防护
		if (stack != null && !stack.isEmpty() && ctx != null) {
			// 每帧获取最新 mc 引用，规避 WidgetBase 构造时缓存可能未初始化的缺陷
			MinecraftClient mc = MinecraftClient.getInstance();
			// 边界判断使用本 widget 的绝对屏幕坐标（18×18），并防护 player / textRenderer 为空
			if (mouseX >= getX() && mouseX <= getX() + 18 && mouseY >= getY() && mouseY <= getY() + 18
					&& mc.player != null && mc.textRenderer != null) {
				// 生成 ADVANCED 级别（含 NBT 细节）的 tooltip 文本列表
				List<Text> tooltip = stack.getTooltip(mc.player, TooltipContext.Default.ADVANCED);
				// 在鼠标位置渲染 tooltip
				ctx.drawTooltip(mc.textRenderer, tooltip, mouseX, mouseY);
			}
		}
		super.postRenderHovered(mouseX, mouseY, selected, ctx);
	}

	public ItemStack getStack() {
		return stack;
	}
}
