package com.github.sebseb7.autotrade.trade.machine;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.VillagerTradeCache;
import com.github.sebseb7.autotrade.trade.io.ContainerIOScheduler;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.stats.TradeStats;
import com.github.sebseb7.autotrade.trade.task.BlockTriggerTask;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.util.math.BlockPos;

/**
 * 交易模式机器的公共基类：封装「当前任务（交易会话/容器 IO）的生命周期管理」。 三种模式（STATIC/MOVING/VOID）只需实现
 * {@link #tickIdle(MinecraftClient)}，即可复用任务切换、状态命名与重置逻辑。 任务统一经
 * {@link #setTaskIfEmpty(Task)} 启动（守卫保证同一时刻最多一个运行中任务）；任务结束经双钩子分发——正常结束走
 * {@link #onTaskEnded(Task, TaskResult)}， 看门狗强杀走
 * {@link #onTaskInterrupted(Task)}。
 *
 * <p>
 * <b>失败处理矩阵（10 值 FailReason →
 * 机器层动作/告警）</b>：{@link #onTaskEnded(Task, TaskResult)} 按细分原因
 * 分发机器层动作；告警走状态边沿（{@link AlertType} + armedAlerts，同故障一次、恢复重新武装，无时间窗口限频）。
 *
 * <pre>
 * 细分原因            触发场景                        机器层动作                                告警
 * WORLD_GONE          玩家/世界意外缺失               静默                                      无
 * TARGET_INVALID      返回触发方块缺失/超距           静默（可达性门控兜底）                    无
 * CHUNK_UNLOADED      容器所在区块未加载              静默快速重试                              无
 * SCREEN_TIMEOUT      交易/容器界面超时               容器任务 100t 冷却；返回触发 100t 节流    容器开窗失败（边沿）
 * SCREEN_CLOSED       交易窗口意外关闭                静默                                      无
 * TRANSIT_TIMEOUT     等待玩家传送回程超时            返回触发 100t 节流                        无
 * CONFIG_INVALID      配置与现场不符                  容器任务 100t 冷却；返回触发门控/冷却     容器非容器 / 返回触发 STRICT（边沿）
 * INVENTORY_BLOCKED   背包空间不足                    暂停交易 100t                             背包满（边沿）
 * TELEPORT_TIMEOUT    虚空村民始终未消失              会话结束（由模式层决定后续）              传送超时（边沿）
 * NO_PROGRESS         容器搬运零进展（满/无匹配/背包无法接收）  逐容器 100t 冷却                          容器无进展（边沿）
 * </pre>
 */
public abstract class AbstractTradeMachine implements TradingMachine {

	/** 当前任务（交易会话或容器 IO，均为 Task 子类） */
	private Task currentTask;

	/** 空闲原因（HUD 只读展示用）：tick 入口默认 NONE，各空闲分支就地写入；有任务时由 getIdleReason 按任务类型派生 */
	private IdleReason idleReason = IdleReason.NONE;

	/** 当前任务已持续运行的 tick 数（看门狗计数，任务完成/强杀时归零） */
	private int taskTicks = 0;
	/** 背包满暂停交易的退避冷却（tick）；>0 期间不启动交易会话，只尝试输出优先的容器 IO */
	private int inventoryPauseCooldown = 0;
	/** 背包满后的暂停时长：100 tick = 5 秒，到期后重新探测背包空间 */
	private static final int INVENTORY_PAUSE_TICKS = 100;

	/** 容器 IO 调度器（机器层拥有调度决策；本基类与三模式共用同一实例） */
	protected final ContainerIOScheduler containerIOScheduler = new ContainerIOScheduler();

	/** 最近一次 tick 的世界时间戳（玩家/世界空检查后刷新）；子类冷却/节流（如 VOID 返回触发重试）的时间基准 */
	protected long lastWorldTime = 0;

	/** 已武装的故障告警键集合：状态边沿去重——同键只在首次发送，恢复时 clear 重新武装；无时间窗口限频 */
	private final Set<String> armedAlerts = new HashSet<>();

	/**
	 * 容器任务 NO_PROGRESS / SCREEN_TIMEOUT / CONFIG_INVALID 失败后的逐容器冷却 tick（矩阵行；
	 * STATIC/VOID/MOVING 共用常量）。 100 tick = 5 秒；仅约束「非静默失败」后的容器 IO 重试节奏，成功路径不受影响。
	 */
	protected static final int CONFIG_FAIL_COOLDOWN_TICKS = 100;

	/** 故障告警类型：携带 i18n 消息键；告警走状态边沿（同键一次、恢复重新武装），无时间窗口限频 */
	protected enum AlertType {
		/** 背包空间不足（INVENTORY_BLOCKED） */
		INVENTORY_FULL("autotrade.message.inventory.full"),
		/** VOID 传送超时（TELEPORT_TIMEOUT） */
		TELEPORT_TIMEOUT("autotrade.message.void.teleport_timeout"),
		/** 容器开窗失败（SCREEN_TIMEOUT 且任务标记 openFailed） */
		CONTAINER_OPEN_FAILED("autotrade.message.io.open_failed"),
		/** 目标位置不是容器（CONFIG_INVALID） */
		CONTAINER_NOT_CONTAINER("autotrade.message.io.not_container"),
		/** VOID 交易耗尽证据（开窗 offers uses > 0） */
		VOID_EXHAUSTED("autotrade.message.void.exhausted"),
		/** 返回触发 STRICT 类型不符（VOID 派发前门控） */
		RETURN_TRIGGER_STRICT("autotrade.message.void.return_strict"),
		/** 容器搬运零进展（NO_PROGRESS）。 */
		CONTAINER_NO_PROGRESS("autotrade.message.io.no_progress");

		/** i18n 消息键（发送时经 InfoUtils 本地化渲染） */
		final String messageKey;

		AlertType(String messageKey) {
			this.messageKey = messageKey;
		}
	}

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

	/**
	 * 发送全局键故障告警（状态边沿：同一故障键只在首次发送，恢复时 clear 重新武装；无时间窗口限频）。
	 *
	 * @param type
	 *            告警类型（决定 i18n 消息键与去重键）
	 * @param args
	 *            消息格式化参数
	 */
	protected final void showFaultAlert(AlertType type, Object... args) {
		showAlertKeyed(type.name(), type, args);
	}

	/**
	 * 发送按容器键隔离的故障告警（状态边沿：同容器同故障只在首次发送，恢复时 clear 重新武装；无时间窗口限频）。
	 *
	 * @param type
	 *            告警类型（决定 i18n 消息键）
	 * @param containerKey
	 *            容器身份键（坐标+方向；同类型故障按容器独立去重）
	 * @param args
	 *            消息格式化参数
	 */
	protected final void showContainerFaultAlert(AlertType type, String containerKey, Object... args) {
		showAlertKeyed(type.name() + "#" + containerKey, type, args);
	}

	/**
	 * 解除全局键故障告警的武装（故障恢复时调用；下次同键故障会重新发送一次；状态边沿，无时间窗口限频）。
	 *
	 * @param type
	 *            告警类型
	 */
	protected final void clearFaultAlert(AlertType type) {
		armedAlerts.remove(type.name());
	}

	/**
	 * 解除按容器键隔离的故障告警的武装（该容器故障恢复时调用；下次同容器同故障会重新发送一次；状态边沿，无时间窗口限频）。
	 *
	 * @param type
	 *            告警类型
	 * @param containerKey
	 *            容器身份键
	 */
	protected final void clearContainerFaultAlert(AlertType type, String containerKey) {
		armedAlerts.remove(type.name() + "#" + containerKey);
	}

	/** 状态边沿告警核心：键首次出现才发送（armedAlerts.add 返回 true），恢复点经 clear 解除武装后可再次发送 */
	private void showAlertKeyed(String key, AlertType type, Object... args) {
		if (armedAlerts.add(key)) {
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, type.messageKey, args);
		}
	}

	@Override
	public void tick(MinecraftClient mc) {
		// 玩家/世界可能为空（退出世界等），此时不执行任何任务
		if (mc.player == null || mc.world == null) {
			return;
		}

		// 记录世界时间戳（子类节流/冷却的时间基准；须在玩家/世界空检查之后，避免 NPE）
		lastWorldTime = mc.world.getTime();

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

		// 空闲：由子类决定下一个任务；先清空原因，保证未覆盖分支暴露为 NONE（而非陈旧值）
		idleReason = IdleReason.NONE;
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
	 * 任务结束后的回调（由 tick 检测到任务返回非 RUNNING 结果时调用，非强杀）：按细分失败原因分发机器层动作 —— 静默 / 节流 / 暂停 /
	 * 告警（见类 javadoc 的 10 行矩阵），并同步「背包满暂停」与状态边沿告警的解除。 STATIC/MOVING/VOID
	 * 覆写为设置各自冷却后须委托 super（矩阵分发与告警均在基类）。 告警为状态边沿：同一故障键只在首次发送，恢复点 clear 重新武装；无时间窗口限频。
	 *
	 * @param task
	 *            已结束的任务
	 * @param result
	 *            任务最后一次 tick 返回的结果（成功或失败）
	 */
	protected void onTaskEnded(Task task, TaskResult result) {
		// 9.8 村民交易缓存：仅「确已扫描」的会话写入学习结果——未进入 TRADING 的会话（开窗失败/传送超时/让位于 tick 入口）天然被
		// sessionScanned 排除；result.isSucceeded() 为双保险（TradeTask 从 CLOSING_SCREEN 返回
		// SUCCEEDED 或 INVENTORY_BLOCKED）。学习时同时写入两类不命中标记：isSessionMatched =
		// 有可执行交易（命中）；isSessionPairMatched = 有匹配交易对但无执行（耗尽/成本不足，供分级跳过）
		if (task instanceof TradeTask ts && result.isSucceeded() && ts.isSessionScanned()) {
			VillagerTradeCache.learn(ts.getVillagerUuid(), ts.isSessionMatched(), ts.isSessionPairMatched(),
					ts.getSessionMatchedTick());
		}
		// 容器成功：记录统计，并解除该容器的开窗失败/非容器告警与全局背包满告警（故障已恢复 → 解除武装）
		if (task instanceof ContainerIOTask op && result.isSucceeded()) {
			TradeStats.getInstance().recordIoOp(op.isInputOp());
			clearContainerFaultAlert(AlertType.CONTAINER_OPEN_FAILED, op.getIntent().containerKey());
			clearContainerFaultAlert(AlertType.CONTAINER_NOT_CONTAINER, op.getIntent().containerKey());
			clearContainerFaultAlert(AlertType.CONTAINER_NO_PROGRESS, op.getIntent().containerKey());
			clearFaultAlert(AlertType.INVENTORY_FULL);
		}
		// 失败矩阵：仅 5 个细分原因有机器层动作（INVENTORY_BLOCKED / TELEPORT_TIMEOUT / CONFIG_INVALID /
		// SCREEN_TIMEOUT / NO_PROGRESS）；其余 5 值静默
		if (result.isFailed()) {
			// 容器失败统一逐容器冷却（NO_PROGRESS / CONFIG_INVALID / SCREEN_TIMEOUT 三原因共用同一标记点，禁止分散进
			// case）
			if (task instanceof ContainerIOTask op && isContainerCooldownReason(result)) {
				containerIOScheduler.markContainerCooldown(op.getIntent().containerKey(), lastWorldTime,
						CONFIG_FAIL_COOLDOWN_TICKS);
			}
			switch (result.reason()) {
				case INVENTORY_BLOCKED -> {
					// 背包空间不足：暂停交易退避 + 边沿告警（满包不标记村民由 handleTaskEnded 骨架短路保证）
					inventoryPauseCooldown = INVENTORY_PAUSE_TICKS;
					showFaultAlert(AlertType.INVENTORY_FULL);
				}
				case TELEPORT_TIMEOUT -> {
					// VOID 村民一直未消失（传送未完成）：边沿告警，提示检查装置
					showFaultAlert(AlertType.TELEPORT_TIMEOUT);
				}
				case CONFIG_INVALID -> {
					// 仅容器任务：目标位置非容器（配置错误）→ 按容器键边沿告警，附带坐标与方块名
					if (task instanceof ContainerIOTask op) {
						showContainerFaultAlert(AlertType.CONTAINER_NOT_CONTAINER, op.getIntent().containerKey(),
								new BlockPos(op.getIntent().loc().getX(), op.getIntent().loc().getY(),
										op.getIntent().loc().getZ()).toShortString(),
								op.getNotContainerBlockName());
					}
				}
				case SCREEN_TIMEOUT -> {
					// 仅容器任务且确实开窗失败：按容器键边沿告警（TradeTask/BlockTriggerTask 的同名原因静默，防误报）
					if (task instanceof ContainerIOTask op && op.isOpenFailed()) {
						showContainerFaultAlert(AlertType.CONTAINER_OPEN_FAILED, op.getIntent().containerKey());
					}
				}
				case NO_PROGRESS -> {
					// 容器搬运零进展（满/无匹配/背包无法接收）：按容器键边沿告警（逐容器冷却已在 switch 前统一标记）
					if (task instanceof ContainerIOTask op) {
						showContainerFaultAlert(AlertType.CONTAINER_NO_PROGRESS, op.getIntent().containerKey());
					}
				}
				default -> {
					// 静默分支：WORLD_GONE / TARGET_INVALID / CHUNK_UNLOADED / SCREEN_CLOSED /
					// TRANSIT_TIMEOUT
				}
			}
		}
		// TradeTask：成功 → 解除传送超时/背包满告警；检出耗尽证据 → 边沿告警，无证据的成功会话 → 解除（恢复正常）
		if (task instanceof TradeTask ts) {
			if (result.isSucceeded()) {
				clearFaultAlert(AlertType.TELEPORT_TIMEOUT);
				clearFaultAlert(AlertType.INVENTORY_FULL);
			}
			if (ts.isExhaustedEvidenceDetected()) {
				showFaultAlert(AlertType.VOID_EXHAUSTED);
			} else if (result.isSucceeded()) {
				clearFaultAlert(AlertType.VOID_EXHAUSTED);
			}
		}
		if (task instanceof ContainerIOTask op && !op.isInputOp() && inventoryPauseCooldown > 0 && result.isSucceeded()
				&& op.getTransferred() > 0) {
			// 仅「成功且实际搬运 > 0」的输出 IO（真正释放了背包空间）才解除暂停
			AutoTrade.logger.info("[ModeMachine] Output container IO done, inventory pause released");
			inventoryPauseCooldown = 0;
		}
		AutoTrade.logger.info("[ModeMachine] Task ended (class={}, result={})", task.getClass().getSimpleName(),
				result);
	}

	/**
	 * 任务被看门狗强杀（forceAbortTask）时的回调。 强杀时任务处于中途状态、无结果可言（任务未返回终态结果），
	 * 基类不设置/解除背包满暂停（与正常结束的 onTaskEnded 语义不同）。 例外：VOID 耗尽证据在强杀前可能已置位，
	 * 此处补发状态边沿告警，避免证据告警因看门狗强杀丢失。 子类可按需覆写以处理中断收尾； 需要查询任务状态时使用任务访问器（如
	 * TradeTask#isInventoryBlocked）。
	 *
	 * @param task
	 *            被强杀的任务（未正常结束）
	 */
	protected void onTaskInterrupted(Task task) {
		// 看门狗强杀时任务未返回终态结果，但耗尽证据可能已置位 → 补发边沿告警（已武装则去重不重复发送）
		if (task instanceof TradeTask ts && ts.isExhaustedEvidenceDetected()) {
			showFaultAlert(AlertType.VOID_EXHAUSTED);
		}
		// 其余默认无操作：中断时任务状态不可信，基类不触碰背包满暂停
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
	 * 容器任务是否需要逐容器冷却的失败原因判定（NO_PROGRESS / CONFIG_INVALID / SCREEN_TIMEOUT）。
	 *
	 * @param result
	 *            任务最后一次 tick 返回的结果
	 * @return true = 该失败原因应触发逐容器冷却标记
	 */
	private static boolean isContainerCooldownReason(TaskResult result) {
		return result.isFailed() && (result.reason() == TaskResult.FailReason.NO_PROGRESS
				|| result.reason() == TaskResult.FailReason.CONFIG_INVALID
				|| result.reason() == TaskResult.FailReason.SCREEN_TIMEOUT);
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
	 * 容器 IO 节流门（默认不限流，返回 false）：子类可覆写以在特定冷却期（如 VOID 返回触发 100t 重试节流）内抑制 暂停期的输出优先 IO。
	 * 返回 true 时 {@link #tickInventoryPause(MinecraftClient)} 不启动容器 IO，但
	 * 仍递减暂停冷却并保持「暂停期不启动交易」语义。
	 *
	 * @param mc
	 *            Minecraft 客户端实例
	 * @return true = 当前处于容器 IO 节流期，暂不启动输出优先 IO
	 */
	protected boolean isContainerIoThrottled(MinecraftClient mc) {
		return false;
	}

	/**
	 * 背包满暂停逻辑（子类在 tickIdle 开头调用）：暂停期间不启动交易会话，只尝试「输出优先」的容器 IO （释放背包空间）；返回 true 表示本
	 * tick 已被暂停逻辑消费，调用方应直接 return。
	 */
	protected boolean tickInventoryPause(MinecraftClient mc) {
		if (inventoryPauseCooldown <= 0)
			return false;

		// 暂停期间每 tick 尝试输出优先容器 IO（本地零成本检查，无 IO 需求时不发包）；
		// 子类可经 isContainerIoThrottled 节流，被节流时仍递减冷却并保持暂停语义
		if (!isContainerIoThrottled(mc) && containerIOScheduler.startOutputFirst(mc, this::setTaskIfEmpty))
			return true;

		// 仍处暂停期（未启动输出优先 IO）：原因置背包满，供 HUD 展示
		idleReason = IdleReason.INVENTORY_FULL;
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
		idleReason = IdleReason.NONE;
		// 告警武装集合重置：重置视为全新状态，故障在下次出现时重新提示（状态边沿重新武装）
		armedAlerts.clear();
		// 重置后背包状态可能已变（清空/转移）→ 缓存立即失效，避免复用过期扫描结果
		containerIOScheduler.reset();
	}

	@Override
	public String getStateName() {
		if (currentTask == null)
			return "IDLE";
		if (currentTask instanceof TradeTask)
			return "TRADE_SESSION";
		if (currentTask instanceof BlockTriggerTask)
			return "RETURN_TRIGGER";
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

	/** 写入空闲原因（供子类在空闲分支就地设置；有任务运行时不生效——getter 优先派生 BUSY 值） */
	protected void setIdleReason(IdleReason reason) {
		idleReason = reason;
	}

	/**
	 * 返回当前空闲原因（HUD 只读展示用）：有运行中任务时按任务类型派生 BUSY 值 —— TradeTask→TRADING、
	 * ContainerIOTask→CONTAINER_IO、BlockTriggerTask→RETURN_TRIGGER；无任务时返回最近一次写入的空闲原因。
	 */
	public IdleReason getIdleReason() {
		if (currentTask instanceof TradeTask)
			return IdleReason.TRADING;
		if (currentTask instanceof ContainerIOTask)
			return IdleReason.CONTAINER_IO;
		if (currentTask instanceof BlockTriggerTask)
			return IdleReason.RETURN_TRIGGER;
		return idleReason;
	}
}
