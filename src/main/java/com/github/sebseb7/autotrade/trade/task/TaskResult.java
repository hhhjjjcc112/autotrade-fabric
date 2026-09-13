package com.github.sebseb7.autotrade.trade.task;

/**
 * 任务单次 tick 的执行结果。 约定：tick 返回 {@link Status#RUNNING} 表示任务继续执行，返回
 * {@link Status#SUCCEEDED} 或 {@link Status#FAILED}
 * 表示任务结束——结果本身就是完成信号，任务不得自行记录完成标志。
 */
public record TaskResult(Status status, FailReason reason) {

	/** 任务执行状态三态：运行中 / 成功结束 / 失败结束 */
	public enum Status {
		RUNNING, SUCCEEDED, FAILED
	}

	/**
	 * 失败原因 9 值（顺序固定，供机器层按细分原因分发处理动作）：
	 *
	 * <ul>
	 * <li>{@link #WORLD_GONE}：玩家或世界意外缺失（如断线）</li>
	 * <li>{@link #TARGET_INVALID}：目标方块缺失或超出可达距离</li>
	 * <li>{@link #CHUNK_UNLOADED}：目标容器所在区块尚未加载</li>
	 * <li>{@link #SCREEN_TIMEOUT}：交互后交易/容器界面始终未出现（超时）</li>
	 * <li>{@link #SCREEN_CLOSED}：交易窗口在交易过程中意外关闭</li>
	 * <li>{@link #TRANSIT_TIMEOUT}：等待玩家传送回程超时</li>
	 * <li>{@link #CONFIG_INVALID}：配置与现场不符（如返回触发方块类型不匹配）</li>
	 * <li>{@link #INVENTORY_BLOCKED}：背包空间不足，交易结果放不下</li>
	 * <li>{@link #TELEPORT_TIMEOUT}：虚空模式村民始终未消失（传送未完成）超时</li>
	 * </ul>
	 */
	public enum FailReason {
		WORLD_GONE, TARGET_INVALID, CHUNK_UNLOADED, SCREEN_TIMEOUT, SCREEN_CLOSED, TRANSIT_TIMEOUT, CONFIG_INVALID, INVENTORY_BLOCKED, TELEPORT_TIMEOUT
	}

	/** 继续执行的结果常量（reason 恒为 null） */
	public static final TaskResult RUNNING = new TaskResult(Status.RUNNING, null);

	/** 成功结束的结果常量（reason 恒为 null） */
	public static final TaskResult SUCCEEDED = new TaskResult(Status.SUCCEEDED, null);

	/** 失败结束的工厂方法：按失败原因构造结果 */
	public static TaskResult failed(FailReason reason) {
		return new TaskResult(Status.FAILED, reason);
	}

	/** 是否仍在执行中 */
	public boolean isRunning() {
		return status == Status.RUNNING;
	}

	/** 是否已成功结束 */
	public boolean isSucceeded() {
		return status == Status.SUCCEEDED;
	}

	/** 是否已失败结束 */
	public boolean isFailed() {
		return status == Status.FAILED;
	}
}