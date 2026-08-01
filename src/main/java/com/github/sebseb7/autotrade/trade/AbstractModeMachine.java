package com.github.sebseb7.autotrade.trade;

import net.minecraft.client.MinecraftClient;

/**
 * 交易模式机器的公共基类：封装「当前任务（交易会话/容器 IO）的生命周期管理」。 三种模式（STATIC/MOVING/VOID）只需实现
 * {@link #tickIdle(MinecraftClient)} 与可选的
 * {@link #onTaskDone()}，即可复用任务切换、状态命名与重置逻辑。
 */
public abstract class AbstractModeMachine implements TradingModeMachine {

	protected Object currentTask;
	protected final TradeSession session;

	protected AbstractModeMachine(TradeSession session) {
		this.session = session;
	}

	@Override
	public void tick(MinecraftClient mc) {
		// 玩家/世界可能为空（退出世界等），此时不执行任何任务
		if (mc.player == null || mc.world == null) {
			return;
		}

		// 当前任务完成 → 回调并清空，准备选择下一个任务
		if (currentTask != null) {
			boolean done = (currentTask instanceof TradeSession ts && ts.isDone())
					|| (currentTask instanceof ContainerIOOperation op && op.isDone());
			if (done) {
				onTaskDone();
				currentTask = null;
			}
		}

		if (currentTask != null) {
			// 继续推进当前任务
			if (currentTask instanceof TradeSession ts) {
				ts.tick(mc);
			} else if (currentTask instanceof ContainerIOOperation op) {
				op.tick(mc);
			}
			return;
		}

		// 空闲：由子类决定下一个任务
		tickIdle(mc);
	}

	/**
	 * 当前任务完成后的回调。默认将交易会话重置为「下一村民」； STATIC 模式覆写为设置交易/容器 IO 冷却。
	 */
	protected void onTaskDone() {
		if (currentTask instanceof TradeSession) {
			session.resetForNextVillager();
		}
	}

	/** 空闲状态下选择下一个任务的逻辑（子类实现） */
	protected abstract void tickIdle(MinecraftClient mc);

	@Override
	public void reset() {
		currentTask = null;
		session.clear();
	}

	@Override
	public String getStateName() {
		if (currentTask == null)
			return "IDLE";
		if (currentTask instanceof TradeSession)
			return "TRADE_SESSION";
		return "CONTAINER_IO";
	}
}
