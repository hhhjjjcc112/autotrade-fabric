package com.github.sebseb7.autotrade.trade.staticmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.AbstractModeMachine;
import com.github.sebseb7.autotrade.trade.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.TradeSession;
import net.minecraft.client.MinecraftClient;

/**
 * STATIC 模式：站在固定位置逐村交易 + 容器 IO + 交易/IO 冷却。 完成一轮交易后进入交易冷却，容器 IO 后进入 IO 间隔冷却。
 */
public class StaticModeMachine extends AbstractModeMachine {

	private int tradeCooldown = 0;
	private int containerIOCooldown;

	public StaticModeMachine() {
		super(new StaticTradeSession());
		containerIOCooldown = Configs.Generic.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}

	@Override
	protected void onTaskDone() {
		if (currentTask instanceof TradeSession) {
			// 一轮交易结束 → 设置交易冷却，允许立即检查容器 IO
			tradeCooldown = session.getSessionCooldown();
			containerIOCooldown = 0;
			AutoTrade.logger.info("[StaticMode] Trade done → cooldown={}", tradeCooldown);
		} else {
			// 容器 IO 结束 → 设置 IO 间隔冷却
			containerIOCooldown = Configs.Generic.CONTAINER_IO_INTERVAL.getIntegerValue();
		}
		// 基类同步背包满暂停状态（会话 blocked → 暂停交易；输出 IO 完成 → 解除暂停）
		super.onTaskDone();
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		if (tradeCooldown > 0)
			tradeCooldown--;
		if (containerIOCooldown > 0)
			containerIOCooldown--;

		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 交易冷却结束且范围内有村民 → 开启新一轮交易会话
		if (tradeCooldown == 0 && ContainerIOHelper.hasVillagerInRange(mc)) {
			session.clear();
			currentTask = session;
			AutoTrade.logger.info("[StaticMode] IDLE → TRADE_SESSION at tick {}", mc.world.getTime());
			return;
		}

		// IO 冷却结束 → 尝试发起容器 IO；无 IO 需求则重置为闲置间隔
		if (containerIOCooldown == 0) {
			if (ContainerIOHelper.startContainerIO(mc, op -> currentTask = op))
				return;
			containerIOCooldown = Configs.Generic.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
		}
	}

	@Override
	public void reset() {
		super.reset();
		tradeCooldown = 0;
		containerIOCooldown = Configs.Generic.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}
}
