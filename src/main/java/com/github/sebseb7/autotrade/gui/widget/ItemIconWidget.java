package com.github.sebseb7.autotrade.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.malilib.gui.widgets.WidgetBase;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public class ItemIconWidget extends WidgetBase {
	private final ItemStack stack;
	/** 左键点击回调（交易对页跳转 IO 页用；null = 无点击行为，与旧行为一致） */
	private final Runnable onClick;

	public ItemIconWidget(int x, int y, ItemStack stack) {
		this(x, y, stack, null);
	}

	// 物品图标控件：渲染物品图标 + 悬浮 tooltip，可选左键点击回调（onClick 为 null 时点击无操作）
	public ItemIconWidget(int x, int y, ItemStack stack, Runnable onClick) {
		super(x, y, 18, 18);
		this.stack = stack;
		this.onClick = onClick;
	}

	@Override
	public void render(int mouseX, int mouseY, boolean selected, DrawContext ctx) {
		if (!stack.isEmpty()) {
			// 关闭深度测试绘制物品图标：drawItem 默认在 z=150 绘制并写入深度缓冲，而 malilib 的
			// MalilibDrawContext 冲刷时不会像原版那样关闭深度测试——后续绘制的弹出消息（InfoUtils，
			// drawGuiMessages 在 render 最后一步）会被图标的深度挡住（图标压在消息之上）。
			// 临时关闭深度测试后图标不读写深度，消息可正常盖在图标之上（图层修正）。
			RenderSystem.disableDepthTest();
			try {
				ctx.drawItem(stack, getX() + 1, getY() + 1);
			} finally {
				RenderSystem.enableDepthTest();
			}
		}
	}

	// 鼠标悬浮在图标区域时渲染 ItemStack 的详细 tooltip（与旧独立编辑屏中已验证的相同模式）
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

	// 左键点击图标时触发回调（交易对页跳转 IO 页）；无回调或非左键时走默认处理
	@Override
	protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton) {
		if (onClick != null && mouseButton == 0) {
			onClick.run();
			return true;
		}
		return super.onMouseClickedImpl(mouseX, mouseY, mouseButton);
	}
}
