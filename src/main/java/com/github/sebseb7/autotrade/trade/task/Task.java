package com.github.sebseb7.autotrade.trade.task;

import net.minecraft.client.MinecraftClient;

/**
 * 单个自动化操作（交易会话或容器 IO）的抽象基类。 内置基于 tick 的等待机制，用于实现 tick 级延时。
 */
public abstract class Task {
	protected int waitTicks = 0;
	protected boolean done = false;

	/**
	 * 每个游戏 tick 调用一次。实现约定： 1. 先调用 tickWait()； 2. 若 isWaiting() 为 true 则直接返回； 3.
	 * 执行一步操作； 4. 若后续仍需多个 tick 才能完成，调用 wait(N)； 5. 若完全完成，设置 done = true。
	 */
	public abstract void tick(MinecraftClient mc);

	/** 操作是否已完成 */
	public boolean isDone() {
		return done;
	}

	/** 等待 N 个 tick 后再执行下一步 */
	protected void wait(int ticks) {
		waitTicks = Math.max(waitTicks, ticks);
	}

	/** 当前是否处于等待状态 */
	protected boolean isWaiting() {
		return waitTicks > 0;
	}

	/** 每个 tick 递减等待计数 */
	protected void tickWait() {
		if (waitTicks > 0)
			waitTicks--;
	}
}
