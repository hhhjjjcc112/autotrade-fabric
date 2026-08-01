package com.github.sebseb7.autotrade.trade.movingmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.AbstractModeMachine;
import com.github.sebseb7.autotrade.trade.ContainerIOHelper;
import net.minecraft.client.MinecraftClient;

/**
 * MOVING 模式：优先处理 4 格内的容器 IO，其次与最近的村民交易， 无目标时保持闲置，等待玩家靠近。
 */
public class MovingModeMachine extends AbstractModeMachine {

	public MovingModeMachine() {
		super(new MovingTradeSession());
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		double contDist = ContainerIOHelper.nearestContainerDistance(mc);
		double villDist = ContainerIOHelper.nearestVillagerDistance(mc);
		double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();

		// 容器比村民更近且在 4 格内 → 优先容器 IO
		if (contDist <= 4 && contDist <= villDist) {
			if (ContainerIOHelper.startContainerIO(mc, op -> currentTask = op))
				return;
		}

		// 范围内有村民 → 开始交易会话
		if (villDist <= range) {
			currentTask = session;
			AutoTrade.logger.info("[MovingMode] IDLE → TRADE_SESSION");
			return;
		}

		// 无村民时仍可执行 4 格内的容器 IO
		if (contDist <= 4) {
			ContainerIOHelper.startContainerIO(mc, op -> currentTask = op);
		}
	}
}
