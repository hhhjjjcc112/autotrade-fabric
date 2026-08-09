package com.github.sebseb7.autotrade.trade.movingmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.AbstractModeMachine;
import com.github.sebseb7.autotrade.trade.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.TradeSession;
import net.minecraft.client.MinecraftClient;

/**
 * MOVING 模式：优先处理 4 格内需要 IO 的容器（公平调度），其次与范围内的村民交易， 无目标时保持闲置，等待玩家靠近。
 * 会话「零进度」完成后进入冷却，避免已处理村民仍在范围内导致的忙碌循环。
 */
public class MovingModeMachine extends AbstractModeMachine {

	/** 零进度会话后的冷却（tick），防止村民全部已处理时每 2 tick 重启会话 */
	private int zeroProgressCooldown = 0;

	public MovingModeMachine() {
		super(new MovingTradeSession());
	}

	@Override
	protected void onTaskDone() {
		// 会话零进度（本次运行没有找到可交互的村民）→ 冷却一段时间再重试，避免忙碌循环
		if (currentTask instanceof TradeSession ts && ts.getVillagersInteracted() == 0) {
			zeroProgressCooldown = Configs.Generic.SESSION_ZERO_PROGRESS_COOLDOWN.getIntegerValue();
			AutoTrade.logger.info("[MovingMode] Zero-progress session done → cooldown={}", zeroProgressCooldown);
		}
		super.onTaskDone();
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		if (zeroProgressCooldown > 0)
			zeroProgressCooldown--;

		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 公平调度：4 格内有需要 IO 的容器时优先执行容器 IO（无论村民远近），
		// 避免村民会话长期挤占容器 IO；IO 完成后自然回到本方法继续决策
		if (ContainerIOHelper.startContainerIO(mc, op -> currentTask = op))
			return;

		// 零进度冷却期间不重启村民会话（容器 IO 仍可执行）
		if (zeroProgressCooldown > 0)
			return;

		// 范围内有村民 → 开始交易会话
		if (ContainerIOHelper.nearestVillagerDistance(mc) <= Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue()) {
			currentTask = session;
			AutoTrade.logger.info("[MovingMode] IDLE → TRADE_SESSION");
		}
	}

	@Override
	public void reset() {
		super.reset();
		zeroProgressCooldown = 0;
	}
}
