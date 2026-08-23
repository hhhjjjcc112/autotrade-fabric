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

	/** 失败原因四值：瞬态失败 / 配置错误 / 背包空间不足 / 虚空模式传送超时 */
	public enum FailReason {
		TRANSIENT, CONFIG, INVENTORY_BLOCKED, TELEPORT_TIMEOUT
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