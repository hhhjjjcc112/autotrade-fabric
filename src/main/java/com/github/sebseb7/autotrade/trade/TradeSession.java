package com.github.sebseb7.autotrade.trade;

import net.minecraft.client.MinecraftClient;

public interface TradeSession {
	void tick(MinecraftClient mc);
	boolean isDone();
	int getSessionCooldown();
	void clear();
	void resetForNextVillager();
}
