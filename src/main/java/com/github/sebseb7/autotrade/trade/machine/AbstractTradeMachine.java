package com.github.sebseb7.autotrade.trade.machine;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;

/**
 * 交易模式机器的公共基类：封装「当前任务（交易会话/容器 IO）的生命周期管理」。 三种模式（STATIC/MOVING/VOID）只需实现
 * {@link #tickIdle(MinecraftClient)}，即可复用任务切换、状态命名与重置逻辑。 任务统一经
 * {@link #setTaskIfEmpty(Task)} 启动（守卫保证同一时刻最多一个运行中任务）；任务结束经双钩子分发——正常结束走
 * {@link #onTaskEnded(Task, TaskResult)}， 看门狗强杀走
 * {@link #onTaskInterrupted(Task)}。
 */
public abstract class AbstractTradeMachine implements TradingMachine {

	/** 当前任务（交易会话或容器 IO，均为 Task 子类） */
	private Task currentTask;

	/** 当前任务已持续运行的 tick 数（看门狗计数，任务完成/强杀时归零） */
	private int taskTicks = 0;
	/** 背包满暂停交易的退避冷却（tick）；>0 期间不启动交易会话，只尝试输出优先的容器 IO */
	private int inventoryPauseCooldown = 0;
	/** 背包满后的暂停时长：100 tick = 5 秒，到期后重新探测背包空间 */
	private static final int INVENTORY_PAUSE_TICKS = 100;

	protected AbstractTradeMachine() {
	}

	/**
	 * 尝试将当前任务设置为给定任务，仅在没有运行中任务时成功。 正常流程下 tickIdle 不变量（仅在无运行中任务时才启动新任务）
	 * 保证本方法恒成功，该守卫为防御性：防止任何路径意外覆盖仍在运行的任务。
	 *
	 * @param task
	 *            要启动的任务（TradeTask 或 ContainerIOTask）
	 * @return true 表示设置成功；false 表示已有任务运行中，拒绝覆盖
	 */
	protected final boolean setTaskIfEmpty(Task task) {
		if (currentTask != null) {
			AutoTrade.logger.warn("[ModeMachine] setTaskIfEmpty 被拒绝：已有任务 {} 运行中",
					currentTask.getClass().getSimpleName());
			return false;
		}
		currentTask = task;
		return true;
	}

	@Override
	public void tick(MinecraftClient mc) {
		// 玩家/世界可能为空（退出世界等），此时不执行任何任务
		if (mc.player == null || mc.world == null) {
			return;
		}

		// 当前任务推进：每 tick 执行一步，返回非 RUNNING 结果即任务结束
		if (currentTask != null) {
			taskTicks++;
			TaskResult result = currentTask.tick(mc);
			if (!result.isRunning()) {
				// 任务结束（成功或失败）→ 回调并清空，落入下方 tickIdle（同 tick，等价现状 fall-through）
				onTaskEnded(currentTask, result);
				currentTask = null;
				taskTicks = 0;
			} else {
				// 看门狗：任务运行超过 TASK_TIMEOUT 仍未结束 → 强杀清空，放行 tickIdle
				// （防卡死兜底：避免失败/卡死任务永久占用运行位，如交易 offers 永不同步、返回触发永久失败）
				int timeout = Configs.Generic.TASK_TIMEOUT.getIntegerValue();
				if (timeout > 0 && taskTicks >= timeout) {
					forceAbortTask(mc);
				}
				return;
			}
		}

		// 空闲：由子类决定下一个任务
		tickIdle(mc);
	}

	/**
	 * 看门狗强杀当前任务：防御性关闭残留窗口（挡住下一轮交互/开箱）→ 经 {@link #onTaskInterrupted(Task)} 回调 →
	 * 清空任务放行 tickIdle。 强杀时任务处于中途状态、isInventoryBlocked 等标志不可信，基类回调不设置/解除背包满暂停；
	 * STATIC/MOVING 模式经各自私有 handleTaskEnded 统一标记任务结束（见后续任务），本基类不区分正常完成与强杀。
	 */
	private void forceAbortTask(MinecraftClient mc) {
		// 残留窗口会挡住下一轮交互/开箱，先防御性关闭（与各任务 CLOSING 状态行为一致）
		if (mc.currentScreen instanceof MerchantScreen || mc.currentScreen instanceof GenericContainerScreen
				|| mc.currentScreen instanceof ShulkerBoxScreen) {
			mc.currentScreen.close();
		}
		AutoTrade.logger.warn("[ModeMachine] 任务运行超过 {} tick 未完成，看门狗强杀 ({}, state={})", taskTicks,
				currentTask.getClass().getSimpleName(), getStateName());
		onTaskInterrupted(currentTask);
		currentTask = null;
		taskTicks = 0;
	}

	/**
	 * 任务结束后的回调（由 tick 检测到任务返回非 RUNNING 结果时调用，非强杀）。 STATIC 模式覆写为设置交易/容器 IO 冷却。
	 * 基类统一在此同步「背包满暂停」状态：会话因背包满失败结束 → 暂停交易 + 游戏内提示； 输出容器 IO 结束（背包空间释放）→ 解除暂停。
	 *
	 * @param task
	 *            已结束的任务
	 * @param result
	 *            任务最后一次 tick 返回的结果（成功或失败）
	 */
	protected void onTaskEnded(Task task, TaskResult result) {
		if (result.isFailed() && result.reason() == TaskResult.FailReason.INVENTORY_BLOCKED) {
			// 会话因背包空间不足失败结束 → 暂停交易并提示
			inventoryPauseCooldown = INVENTORY_PAUSE_TICKS;
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.inventory.full");
		} else if (result.reason() == TaskResult.FailReason.TELEPORT_TIMEOUT) {
			// VOID 模式传送超时（村民一直未消失）→ 游戏内告警，提示检查装置
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.void.teleport_timeout");
		}
		if (task instanceof ContainerIOTask op && !op.isInputOp() && inventoryPauseCooldown > 0) {
			// 输出 IO 把产出物品运走后背包应有空间 → 立即恢复交易探测
			AutoTrade.logger.info("[ModeMachine] Output container IO done, inventory pause released");
			inventoryPauseCooldown = 0;
		}
		AutoTrade.logger.info("[ModeMachine] Task ended (class={}, result={})", task.getClass().getSimpleName(),
				result);
	}

	/**
	 * 任务被看门狗强杀（forceAbortTask）时的回调。 强杀时任务处于中途状态、无结果可言（任务未返回终态结果），
	 * 基类不设置/解除背包满暂停（与正常结束的 onTaskEnded 语义不同）。 子类可按需覆写以处理中断收尾； 需要查询任务状态时使用任务访问器（如
	 * TradeTask#isInventoryBlocked）。
	 *
	 * @param task
	 *            被强杀的任务（未正常结束）
	 */
	protected void onTaskInterrupted(Task task) {
		// 默认无操作：中断时任务状态不可信，基类不触碰背包满暂停
	}

	/**
	 * 背包满暂停逻辑（子类在 tickIdle 开头调用）：暂停期间不启动交易会话，只尝试「输出优先」的容器 IO （释放背包空间）；返回 true 表示本
	 * tick 已被暂停逻辑消费，调用方应直接 return。
	 */
	protected boolean tickInventoryPause(MinecraftClient mc) {
		if (inventoryPauseCooldown <= 0)
			return false;

		// 暂停期间每 tick 尝试输出优先容器 IO（本地零成本检查，无 IO 需求时不发包）
		if (ContainerIOHelper.startOutputFirstContainerIO(mc, this::setTaskIfEmpty))
			return true;

		inventoryPauseCooldown--;
		return true;
	}

	/** 空闲状态下选择下一个任务的逻辑（子类实现） */
	protected abstract void tickIdle(MinecraftClient mc);

	@Override
	public void reset() {
		currentTask = null;
		taskTicks = 0;
		inventoryPauseCooldown = 0;
	}

	@Override
	public String getStateName() {
		if (currentTask == null)
			return "IDLE";
		if (currentTask instanceof TradeTask)
			return "TRADE_SESSION";
		return "CONTAINER_IO";
	}
}
