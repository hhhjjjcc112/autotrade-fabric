package com.github.sebseb7.autotrade.trade;

import net.minecraft.client.MinecraftClient;

public interface TradingModeMachine {
	void tick(MinecraftClient mc);
	void reset();
	String getStateName();
}
