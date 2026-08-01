package com.github.sebseb7.autotrade.mixin;

import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.MerchantScreenPairInjector;
import com.github.sebseb7.autotrade.mixin.accessor.MerchantScreenAccessor;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.village.TradeOfferList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Screen.class)
public class MerchantScreenHotkeyMixin {

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof MerchantScreen screen))
			return;

		IKeybind keybind = Hotkeys.ADD_TRADE_PAIR_KEY.getKeybind();
		if (!keybind.matches(keyCode))
			return;

		MinecraftClient mc = MinecraftClient.getInstance();

		TradeOfferList offers = screen.getScreenHandler().getRecipes();
		if (offers == null || offers.isEmpty())
			return;

		int indexStartOffset = ((MerchantScreenAccessor) screen).getIndexStartOffset();
		// 将鼠标的原始窗口坐标换算为 GUI 内的缩放坐标（与 Minecraft 渲染缩放保持一致）
		double mouseX = mc.mouse.getX() * screen.width / mc.getWindow().getWidth();
		double mouseY = mc.mouse.getY() * screen.height / mc.getWindow().getHeight();

		// 交易界面背景贴图为 276×166 居中绘制，据此推算交易按钮区域的左上角
		int guiLeft = (screen.width - 276) / 2;
		int guiTop = (screen.height - 166) / 2;
		int btnX = guiLeft + 5; // 交易按钮列的 X 起点
		int btnYStart = guiTop + 18; // 第一个交易按钮的 Y 起点

		// 交易列表最多显示 7 行，每行按钮高 20 像素
		for (int i = 0; i < 7; i++) {
			int realIndex = i + indexStartOffset;
			if (realIndex >= offers.size())
				break;

			// 命中检测：鼠标落在某个交易按钮的 88×20 区域内时，将该交易添加为交易对
			int y = btnYStart + i * 20;
			if (mouseX >= btnX && mouseX <= btnX + 88 && mouseY >= y && mouseY <= y + 20) {
				MerchantScreenPairInjector.addTrade(mc, screen.getScreenHandler(), realIndex);
				cir.setReturnValue(true);
				return;
			}
		}
	}
}
