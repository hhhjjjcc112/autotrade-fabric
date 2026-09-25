package com.github.sebseb7.autotrade.trade.executor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;

// 策略接口：交易执行策略的对外契约（门面委托 + 策略实现）
interface TradeStrategy {
	boolean handleMerchantScreenTick(MinecraftClient mc, MerchantScreen screen);

	boolean isInventoryBlocked();

	boolean hasSessionHadExecutable();

	// 本会话是否见过与启用交易对匹配的 offer（不论可执行性；供缓存分级）
	boolean hasSessionHadPairMatch();
}