package com.github.sebseb7.autotrade;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.handler.AutoTradeClientTick;
import com.github.sebseb7.autotrade.handler.InputHandler;
import com.github.sebseb7.autotrade.handler.KeybindCallbacks;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

public class InitHandler implements IInitializationHandler {
	@Override
	public void registerModHandlers() {
		ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());

		InputHandler handler = new InputHandler();
		InputEventHandler.getKeybindManager().registerKeybindProvider(handler);

		TickHandler.getInstance().registerClientTickHandler(AutoTradeClientTick.getInstance());

		KeybindCallbacks.getInstance().setCallbacks();
	}
}
