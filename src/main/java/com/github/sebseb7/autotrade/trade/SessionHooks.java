package com.github.sebseb7.autotrade.trade;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

public interface SessionHooks {
	Entity findNextVillager(MinecraftClient mc);
	boolean onVillagerDone(MinecraftClient mc, int villagerActiveId);
	void onVillagerTimeout(int villagerActiveId);
	boolean useVoidDelay();
	int getSessionCooldown();
	double getScanRange();
}
