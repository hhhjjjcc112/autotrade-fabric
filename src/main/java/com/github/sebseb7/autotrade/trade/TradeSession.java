package com.github.sebseb7.autotrade.trade;

import net.minecraft.client.MinecraftClient;

public interface TradeSession {
	void tick(MinecraftClient mc);
	boolean isDone();
	int getSessionCooldown();
	/** 本次会话运行中实际找到并开始交互的村民数（0 = 零进度会话，用于忙碌循环冷却判定） */
	int getVillagersInteracted();
	/** 本次会话是否因背包空间不足提前结束（机器层据此暂停交易并优先容器 IO） */
	boolean isInventoryBlocked();
	void clear();
	void resetForNextVillager();
}
