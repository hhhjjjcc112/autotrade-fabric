package com.github.sebseb7.autotrade.trade.task;

import net.minecraft.client.MinecraftClient;

/**
 * 单个自动化操作（交易会话或容器 IO）的抽象基类。 新约定：每个 tick 调用 {@link #tick(MinecraftClient)}，返回
 * {@link TaskResult#RUNNING} 表示继续执行，返回 {@link TaskResult#SUCCEEDED} 或
 * {@link TaskResult#FAILED} 表示任务结束——结果本身就是完成信号，任务不得自行记录完成标志（不再有完成标志字段）。
 */
public abstract class Task {

	/**
	 * 每个游戏 tick 调用一次。实现约定： 1. 执行一步操作； 2. 若后续仍需多个 tick 才能完成，返回
	 * {@link TaskResult#RUNNING}； 3. 若完全完成，返回 {@link TaskResult#SUCCEEDED}； 4.
	 * 若失败结束，返回 {@link TaskResult#failed(FailReason)}。
	 */
	public abstract TaskResult tick(MinecraftClient mc);
}