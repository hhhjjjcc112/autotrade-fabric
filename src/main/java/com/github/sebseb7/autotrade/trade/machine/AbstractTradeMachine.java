package com.github.sebseb7.autotrade.trade.machine;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.VillagerTradeCache;
import com.github.sebseb7.autotrade.trade.io.ContainerIOScheduler;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.stats.TradeStats;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.UUID;
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

	/** 容器 IO 调度器（机器层拥有调度决策；本基类与三模式共用同一实例） */
	protected final ContainerIOScheduler containerIOScheduler = new ContainerIOScheduler();

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
			// 任务运行期钩子：窗口记账（MOVING 的 L1）——先于任务推进，保证任务 tick 前的窗口可见；
			// 看门狗强杀 tick 上亦执行（子类需保证与强杀清理无冲突）
			onTaskTick(mc);
			TaskResult result = currentTask.tick(mc);
			if (!result.isRunning()) {
				// 任务结束（成功或失败）→ 回调并清空，落入下方 tickIdle（同 tick，等价现状 fall-through）
				onTaskEnded(currentTask, result);
				// 任何任务结束都可能改变背包（交易消耗/产出、容器转运增减）→ 缓存必须失效
				containerIOScheduler.invalidate();
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
	 * STATIC/MOVING 模式经其覆写的 onTaskInterrupted → 基类 handleTaskInterrupted 骨架统一标记任务结束
	 * （见 handleTaskInterrupted），本基类不区分正常完成与强杀。
	 */
	private void forceAbortTask(MinecraftClient mc) {
		// 残留窗口会挡住下一轮交互/开箱，先防御性关闭（与各任务 CLOSING 状态行为一致）
		closeResidualScreen(mc);
		AutoTrade.logger.warn("[ModeMachine] 任务运行超过 {} tick 未完成，看门狗强杀 ({}, state={})", taskTicks,
				currentTask.getClass().getSimpleName(), getStateName());
		onTaskInterrupted(currentTask);
		// 强杀时转运可能半途，背包状态不可信 → 缓存必须失效
		containerIOScheduler.invalidate();
		currentTask = null;
		taskTicks = 0;
	}

	/**
	 * 防御性关闭残留的交互窗口（交易/通用容器/潜影盒）：命中即 close 并返回 true，否则返回 false。 残留窗口会挡住下一轮交互/开箱——
	 * 看门狗强杀（{@link #forceAbortTask}）与 MOVING 让位路径的兜底关闭共用（HandledScreen.close()
	 * 完整链路： 发 CloseHandledScreenC2SPacket + setScreen(null)；服务端
	 * onCloseHandledScreen 不校验 syncId 无条件关 handler——1.20.4 源码核实，等效阻止窗口出现）。
	 *
	 * @param mc
	 *            Minecraft 客户端实例
	 * @return true = 检测到残留窗口并已关闭
	 */
	protected static boolean closeResidualScreen(MinecraftClient mc) {
		if (mc.currentScreen instanceof MerchantScreen || mc.currentScreen instanceof GenericContainerScreen
				|| mc.currentScreen instanceof ShulkerBoxScreen) {
			mc.currentScreen.close();
			return true;
		}
		return false;
	}

	/**
	 * 9.8 村民交易缓存判定：TTL 内已知不匹配 → 返回 true（并记录一次实际跳过，HUD 跳过计数）； 未知/命中/TTL 到期（需开窗复查）→
	 * 返回 false。 三个模式的村民派发漏斗共用本方法，一处判定全局一致（跳过语义与 recordSkip 计数不变）。
	 *
	 * @param id
	 *            村民 UUID
	 * @param worldTime
	 *            当前世界 tick（缓存 TTL 判定基准）
	 * @return true = 缓存已知不匹配，应跳过该村民（已计跳过数）
	 */
	protected final boolean isCachedMiss(UUID id, long worldTime) {
		// 缓存命中（TTL 内已知不匹配）→ 记录一次实际跳过并返回 true
		if (VillagerTradeCache.isNotMatch(id, worldTime)) {
			VillagerTradeCache.recordSkip();
			return true;
		}
		return false;
	}

	/**
	 * 任务运行期间的每 tick 钩子（默认空）：子类可做窗口记账/节流扫描（MOVING 的 L1 任务期记账——
	 * 任务运行期进入范围的目标并入窗口快照，离窗差集补记错过）。 在 currentTask.tick 之前调用；看门狗强杀 tick
	 * 上也会执行（子类需保证与强杀清理无冲突）。
	 */
	protected void onTaskTick(MinecraftClient mc) {
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
		// 9.8 村民交易缓存：仅「确已扫描」的会话写入学习结果——未进入 TRADING 的会话（开窗失败/传送超时/让位于 tick 入口）天然被
		// sessionScanned 排除；result.isSucceeded() 为双保险（TradeTask 从 CLOSING_SCREEN 恒返回
		// SUCCEEDED）
		if (task instanceof TradeTask ts && result.isSucceeded() && ts.isSessionScanned()) {
			VillagerTradeCache.learn(ts.getVillagerUuid(), ts.isSessionMatched(), ts.getSessionMatchedTick());
		}
		// 统计：仅记录成功完成的容器 IO（失败/超时/强杀不计入调试计数）
		if (task instanceof ContainerIOTask op && result.isSucceeded()) {
			TradeStats.getInstance().recordIoOp(op.isInputOp());
		}
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
	 * 结果是否为「背包空间不足失败」（blocked 判定谓词）：任务结束骨架的短路判断与子类日志重算共用同一谓词， 保证两处判定 同源一致（失败结果 +
	 * reason == INVENTORY_BLOCKED）。
	 *
	 * @param result
	 *            任务最后一次 tick 返回的结果
	 * @return true = 会话因背包空间不足失败结束
	 */
	protected static boolean isInventoryBlockedResult(TaskResult result) {
		return result.isFailed() && result.reason() == TaskResult.FailReason.INVENTORY_BLOCKED;
	}

	/**
	 * 任务结束统一收尾骨架（protected final）：由子类覆写的 onTaskEnded 内先调用本方法，再交 super.onTaskEnded
	 * 同步基类暂停状态。 按任务类型分派到最小钩子——交易会话：让位短路（onVillagerYielded，默认空）→ 背包满短路 →
	 * markVillagerProcessed 钩子；容器 IO：onContainerTaskEnded 钩子。 本方法为 final：子类只能经钩子
	 * 定制收尾行为，不能改写分派骨架本身。
	 *
	 * @param task
	 *            已结束的任务（TradeTask 或 ContainerIOTask）
	 * @param result
	 *            任务最后一次 tick 返回的结果
	 */
	protected final void handleTaskEnded(Task task, TaskResult result) {
		if (task instanceof TradeTask ts) {
			if (ts.isYielded()) {
				// 安全点让位：不标记已处理、饥饿不 +1、seenKeys 保留（被让位 ≠ 错过；仅 MOVING 会 yield）
				onVillagerYielded(ts);
			} else if (!isInventoryBlockedResult(result)) {
				// 标记该村民已处理（完成/超时统一；背包满失败短路不标记——保留记录，背包清空后下轮重试）
				markVillagerProcessed(ts.getVillagerUuid());
			}
		} else if (task instanceof ContainerIOTask op) {
			onContainerTaskEnded(op, result);
		}
	}

	/**
	 * 任务被看门狗强杀统一收尾骨架（protected final）：由子类覆写的 onTaskInterrupted 内先调用本方法，再交
	 * super.onTaskInterrupted。 分支与 handleTaskEnded 同构——交易会话：让位短路 → 背包满短路（强杀无结果，改用任务
	 * 访问器 isInventoryBlocked 判断，与正常结束的 result 判断等价）→ markVillagerProcessed 钩子；容器
	 * IO：走独立钩子 onContainerTaskInterrupted（中断路径无 TaskResult 可判失败原因，故钩子无结果参数）。
	 *
	 * @param task
	 *            被强杀的任务（TradeTask 或 ContainerIOTask）
	 */
	protected final void handleTaskInterrupted(Task task) {
		if (task instanceof TradeTask ts) {
			if (ts.isYielded()) {
				// 安全点让位后被强杀：不得误标已处理、饥饿不 +1（与 handleTaskEnded 的让位分支等价）
				onVillagerYielded(ts);
			} else if (!ts.isInventoryBlocked()) {
				// 标记该村民已处理（强杀不标记则村民永远"未处理"，看门狗每轮重派 → 无限循环活锁）
				markVillagerProcessed(ts.getVillagerUuid());
			}
		} else if (task instanceof ContainerIOTask op) {
			onContainerTaskInterrupted(op);
		}
	}

	/** 让位钩子（默认空，非 abstract）：交易会话因安全点让位提前结束（正常结束与强杀路径均经骨架调用）； 仅 MOVING 注入检查器会让位 */
	protected void onVillagerYielded(TradeTask task) {
	}

	/** 已处理标记钩子（默认空，非 abstract）：非让位、非背包满短路的会话结束时经骨架调用，参数为会话锁定的村民 UUID */
	protected void markVillagerProcessed(UUID villagerId) {
	}

	/** 容器 IO 正常结束钩子（默认空，非 abstract）：任意结果（成功/失败）均调用，子类据此设置冷却/清理记账 */
	protected void onContainerTaskEnded(ContainerIOTask op, TaskResult result) {
	}

	/** 容器 IO 被看门狗强杀钩子（默认空，非 abstract）：与正常结束钩子独立——中断路径无 TaskResult，子类按需清理 */
	protected void onContainerTaskInterrupted(ContainerIOTask op) {
	}

	/**
	 * 背包满暂停逻辑（子类在 tickIdle 开头调用）：暂停期间不启动交易会话，只尝试「输出优先」的容器 IO （释放背包空间）；返回 true 表示本
	 * tick 已被暂停逻辑消费，调用方应直接 return。
	 */
	protected boolean tickInventoryPause(MinecraftClient mc) {
		if (inventoryPauseCooldown <= 0)
			return false;

		// 暂停期间每 tick 尝试输出优先容器 IO（本地零成本检查，无 IO 需求时不发包）
		if (containerIOScheduler.startOutputFirst(mc, this::setTaskIfEmpty))
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
		// 重置后背包状态可能已变（清空/转移）→ 缓存立即失效，避免复用过期扫描结果
		containerIOScheduler.invalidate();
	}

	@Override
	public String getStateName() {
		if (currentTask == null)
			return "IDLE";
		if (currentTask instanceof TradeTask)
			return "TRADE_SESSION";
		return "CONTAINER_IO";
	}

	/** 返回当前任务（HUD 只读展示用；null = 空闲） */
	public Task getCurrentTask() {
		return currentTask;
	}

	/** 返回当前任务已运行的 tick 数（HUD 只读展示用） */
	public int getTaskTicks() {
		return taskTicks;
	}

	/** 返回背包满暂停冷却剩余 tick（HUD 只读展示用；>0 表示暂停中） */
	public int getInventoryPauseCooldown() {
		return inventoryPauseCooldown;
	}
}
