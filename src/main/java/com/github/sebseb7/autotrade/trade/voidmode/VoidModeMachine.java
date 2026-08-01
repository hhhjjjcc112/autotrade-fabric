package com.github.sebseb7.autotrade.trade.voidmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.trade.AbstractModeMachine;
import com.github.sebseb7.autotrade.trade.ContainerIOHelper;
import net.minecraft.client.MinecraftClient;

/**
 * VOID 模式：优先容器 IO，其次与范围内任意村民交易。 交易后的等待延迟（配合村民传送/卸载）由 VoidTradeSession 的钩子处理。
 */
public class VoidModeMachine extends AbstractModeMachine {

	public VoidModeMachine() {
		super(new VoidTradeSession());
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		// 优先容器 IO
		if (ContainerIOHelper.startContainerIO(mc, op -> currentTask = op))
			return;

		// 范围内有村民 → 开始交易会话
		if (ContainerIOHelper.hasVillagerInRange(mc)) {
			currentTask = session;
			AutoTrade.logger.info("[VoidMode] IDLE → TRADE_SESSION");
		}
	}
}
