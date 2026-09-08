package com.github.sebseb7.autotrade;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.input.InputHandler;
import com.github.sebseb7.autotrade.input.KeybindCallbacks;
import com.github.sebseb7.autotrade.render.DebugHudRenderer;
import com.github.sebseb7.autotrade.runtime.AutoTradeClientTick;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

// malilib 初始化 bootstrap：注册配置/快捷键/渲染/时钟处理器（保持原位，不随 input 包迁移）
public class InitHandler implements IInitializationHandler {
	@Override
	public void registerModHandlers() {
		ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());

		InputHandler handler = new InputHandler();
		InputEventHandler.getKeybindManager().registerKeybindProvider(handler);

		TickHandler.getInstance().registerClientTickHandler(AutoTradeClientTick.getInstance());

		// 注册调试 HUD 叠加层渲染器（游戏原版 HUD 绘制完成后回调）
		RenderEventHandler.getInstance().registerGameOverlayRenderer(DebugHudRenderer.getInstance());

		KeybindCallbacks.getInstance().setCallbacks();
	}
}
