package com.github.sebseb7.autotrade.trade.voidmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.AbstractModeMachine;
import com.github.sebseb7.autotrade.trade.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.TradeSession;
import net.minecraft.client.MinecraftClient;

/**
 * VOID 模式：优先容器 IO，其次与范围内任意村民交易。 交易后的等待延迟（配合村民传送/卸载）由 VoidTradeSession 的钩子处理。
 * 会话「零进度」完成后进入冷却，避免已处理村民仍在范围内导致的忙碌循环。
 */
public class VoidModeMachine extends AbstractModeMachine {

	/** 零进度会话后的冷却（tick），防止村民全部已处理时每 2 tick 重启会话 */
	private int zeroProgressCooldown = 0;

	public VoidModeMachine() {
		super(new VoidTradeSession());
	}

	@Override
	protected void onTaskDone() {
		// 会话零进度（本次运行没有找到可交互的村民）→ 冷却一段时间再重试，避免忙碌循环
		if (currentTask instanceof TradeSession ts && ts.getVillagersInteracted() == 0) {
			zeroProgressCooldown = Configs.Generic.SESSION_ZERO_PROGRESS_COOLDOWN.getIntegerValue();
			AutoTrade.logger.info("[VoidMode] Zero-progress session done → cooldown={}", zeroProgressCooldown);
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

		// 优先容器 IO
		if (ContainerIOHelper.startContainerIO(mc, op -> currentTask = op))
			return;

		// 零进度冷却期间不重启村民会话（容器 IO 仍可执行）
		if (zeroProgressCooldown > 0)
			return;

		// 范围内有村民 → 开始交易会话
		if (ContainerIOHelper.hasVillagerInRange(mc)) {
			currentTask = session;
			AutoTrade.logger.info("[VoidMode] IDLE → TRADE_SESSION");
		}
	}

	@Override
	public void reset() {
		super.reset();
		zeroProgressCooldown = 0;
	}
}
