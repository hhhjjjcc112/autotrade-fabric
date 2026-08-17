package com.github.sebseb7.autotrade.trade.machine;

import net.minecraft.client.MinecraftClient;

public interface TradingMachine {
	void tick(MinecraftClient mc);
	void reset();
	String getStateName();
}
