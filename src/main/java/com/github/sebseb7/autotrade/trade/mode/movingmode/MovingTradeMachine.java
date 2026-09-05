package com.github.sebseb7.autotrade.trade.mode.movingmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.machine.ContainerIOScheduler.CompetitorChecker;
import com.github.sebseb7.autotrade.trade.machine.ContainerIOScheduler.ContainerCandidate;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.Entity;

/**
 * MOVING 模式：无 IO/交易冷却，每 tick 对「范围内需要 IO 的容器条目 ∪ 未处理村民」做周期级公平调度。
 *
 * <p>
 * 防饥饿语义（周期级，见 docs/TRADE_MODES.md §三）：目标离开范围时未服务 → 饥饿 +1（cap 10）；服务后（任何结果）清 0；
 * 饥饿跨窗口持久化（错过记忆）。目标持续未服务则每轮 +1，多轮后必然超过容器 bonus 成为最高分插队（前提：目标轮间离窗——
 * 静止玩家/小环线农场中目标永在窗口不被服务为已知边界，由驻留老化根治）。
 * </p>
 *
 * <p>
 * 评分 score = 饥饿×HUNGER_WEIGHT + bonus（容器 +2，决策零成本；村民 0），无距离项；tie-break：最久未服务 →
 * 距离近 → 容器先入列。任务期窗口补偿：L1 任务期记账（onTaskTick 收集窗口内候选入 seenKeys，离窗差集补记错过）；
 * 驻留老化（tickIdle 评分前对候选按 lastServedTick 老化——从未服务或超老化间隔 → 饥饿 +1 并重置周期，每周期至多 +1； 3
 * 周期后必超容器 bonus 插队；容器统一老化——被村民插队压着的容器不会反向饿死）。CONFIG 失败容器冷却 100 tick
 * （防失败容器独占运行位的忙循环/刷屏）。候选收集/处理记录（processedVillagers）在机器层维护：评分选中村民后通过
 * 构造器锁定派发，会话内不再自行重扫（修复「machine 选 A、session 取到 B」的竞态）。
 * </p>
 *
 * <p>
 * 安全点让位（2026-08-31，moving-fairness-refactor）：任务 tick() 入口每 tick
 * 一次检查点（覆盖全部状态——等待窗口/ 交互在途/交易循环均可让位；旧 tickTrading 内检查点已删除）。让位条件 = 候选内（≤
 * MOVING_INTERACT_RANGE，确定可服务） 存在目标 T（≠ 当前任务目标）满足 starvation[T] ≥ 阈值 且
 * starvation[T] > starvation[当前目标]（严格大于防互抢、 排除自己防自抢）。被让位方不标记已处理、饥饿不 +1（被让位 ≠
 * 错过——旧「让位 +1」是自抢/互抢死循环的振荡放大器， 已废弃）、seenKeys
 * 保留。村民任务只被更饿村民抢占（容器是持续状态，村民让位给容器会饿死回归）；容器任务被更饿村民/
 * 更饿不同容器抢占（公平轮转确定版）。交互在途让位的残留窗口由机器层兜底关闭（tickIdle 开头检测交易/容器屏即 close，
 * 等效阻止窗口出现——1.20.4 源码核实）。仅 MOVING 注入检查器（STATIC/VOID 走旧构造器 checker=null，无让位路径）。
 * 村民候选限交互距离（远处村民只记账不派发——交互必失败的结构性错过根治）；饥饿阈值一次性提示（hintedKeys 防刷屏， 服务完成清除后可再提示）。
 * </p>
 */
public class MovingTradeMachine extends AbstractTradeMachine {

	/** 饥饿权重：饥饿计数 × HUNGER_WEIGHT（暂硬编码，可配置化） */
	private static final int HUNGER_WEIGHT = 1;
	/** 容器固定加成：容器决策零成本（本地背包判断），村民必须开窗试探 */
	private static final int CONTAINER_BONUS = 2;
	/** 饥饿计数上限（cap 必须 > 容器 bonus，保证村民最终插队） */
	private static final int STARVATION_CAP = 10;
	/** CONFIG 失败容器冷却（tick）：失败清饥饿后容器仍 pending 且 bonus 恒胜出，冷却防止整轮忙循环/弹窗刷屏 */
	private static final int CONFIG_FAIL_COOLDOWN = 100;

	/**
	 * 已处理村民记录（交易完成或超时均标记；背包满时不标记），由 findUnprocessedVillagers 做失效清理。 HashSet：村民 UUID
	 * 无重复、contains 是每 tick 热路径（候选收集），集合无序不影响后续显式距离排序（tie-break 用）
	 */
	private final Set<UUID> processedVillagers = new HashSet<>();
	/** 当前派发给会话的目标村民 UUID（任务结束钩子标记已处理用，完成与强杀统一；null = 未派发） */
	private UUID dispatchedVillagerId;
	/**
	 * 当前派发给会话的目标容器条目 ioKey（容器检查器查自身饥饿用；与 dispatchedVillagerId
	 * 对称——excludedContainerKey 是 containerKey 格式（x,y,z#isInput），而饥饿记账键是 ioKey
	 * 格式（item#x,y,z#isInput），不能混用）
	 */
	private String dispatchedContainerIoKey = "";

	/** 饥饿记账统一键：村民 = UUID，容器 = 条目 ioKey（item#x,y,z#isInput） */
	private sealed interface StarvationKey permits VillagerKey, ContainerKey {
	}
	/** 村民饥饿键：UUID */
	private record VillagerKey(UUID uuid) implements StarvationKey {
	}
	/** 容器饥饿键：条目级 ioKey（跨条目实例稳定；同容器不同物品独立记账，消除共享键翻倍/恒选首个缺陷） */
	private record ContainerKey(String ioKey) implements StarvationKey {
	}

	/** 统一评分候选：饥饿键 + 容器加成 + 距离 + 原对象引用（容器/村民，派发用）；容器先入列 → 平分时容器优先 */
	private record Candidate(StarvationKey key, int bonus, double distance, ContainerCandidate container,
			Entity villager) {
	}

	/** 目标级饥饿记账：统一键 → 错过次数（cap 10；跨窗口持久化，reset 清空） */
	private final Map<StarvationKey, Integer> starvation = new HashMap<>();
	/**
	 * 自上次决策以来见过的候选键（含任务期 L1 收集；★ 拷贝语义更新——引用赋值会因 candidateKeys 复用 clear 而清空，机制静默失效）
	 */
	private final Set<StarvationKey> seenKeys = new HashSet<>();
	/** 上次服务时间（tie-break：最久未服务优先；默认 MIN_VALUE = 从未服务） */
	private final Map<StarvationKey, Long> lastServedTick = new HashMap<>();
	/** CONFIG 失败容器冷却：containerKey（坐标+方向）→ 失败时世界 tick（防失败容器独占运行位） */
	private final Map<String, Long> failedContainerCooldown = new HashMap<>();

	/** 复用集合（类内初始化，每 tick clear，避免分配） */
	private final List<Candidate> candidates = new ArrayList<>();
	private final Set<StarvationKey> candidateKeys = new HashSet<>();
	private final List<Entity> unprocessedVillagers = new ArrayList<>();

	/** 最近一次可见的世界 tick（结束钩子记录 CONFIG 冷却用；误差 ≤1 tick，100 tick 冷却不敏感） */
	private long lastWorldTick = 0;
	/** 安全点让位检查器（村民任务注入）：候选内存在「饥饿 ≥ 阈值 且 > 当前村民」的未处理村民（排除自己）→ 让位 */
	private final CompetitorChecker villagerCompetitorChecker;
	/** 提示防刷屏状态：已提示过饥饿阈值的目标键（服务完成/清除饥饿时移除，reset 清空） */
	private final Set<StarvationKey> hintedKeys = new HashSet<>();

	public MovingTradeMachine() {
		super();
		// 安全点让位检查器（容器任务注入）：候选内存在「饥饿 ≥ 阈值 且 > 当前容器」的村民/不同容器 → 让位
		containerIOScheduler.setCompetitorChecker((mc, excludedContainerKey) -> {
			// 当前容器自身饥饿（基准）：用派发时记录的 ioKey 查（excludedContainerKey 是 containerKey 格式，与记账键不一致）
			int myHunger = excludedContainerKey == null
					? 0
					: starvation.getOrDefault(new ContainerKey(dispatchedContainerIoKey), 0);
			int hintThreshold = Configs.Moving.MOVING_STARVATION_HINT_THRESHOLD.getIntegerValue();
			double range = Configs.Moving.MOVING_INTERACT_RANGE.getDoubleValue();
			// 候选内更饿的村民（机会窗口优先；容器任务让位给更饿村民，防饿死回归）
			for (Entity v : findUnprocessedVillagers(mc)) {
				if (v.getPos().distanceTo(mc.player.getPos()) > range) {
					continue;
				}
				int h = starvation.getOrDefault(new VillagerKey(v.getUuid()), 0);
				if (h >= hintThreshold && h > myHunger) {
					return true;
				}
			}
			// 候选内更饿的不同容器（公平轮转确定版；同容器条目不视为竞争者——防 X/Y 各转移 1 组互相让位的 ping-pong）
			for (ContainerCandidate c : containerIOScheduler.findPendingContainers(mc)) {
				if (c.containerKey().equals(excludedContainerKey) || isContainerOnCooldown(mc, c.containerKey())) {
					continue;
				}
				int h = starvation.getOrDefault(new ContainerKey(c.ioKey()), 0);
				if (h >= hintThreshold && h > myHunger) {
					return true;
				}
			}
			return false;
		});
		// 安全点让位检查器（村民任务注入）：候选内存在「饥饿 ≥ 阈值 且 > 当前村民」的未处理村民（排除自己）→ 让位
		villagerCompetitorChecker = (mc, excludedContainerKey) -> {
			int myHunger = starvation.getOrDefault(new VillagerKey(dispatchedVillagerId), 0);
			int hintThreshold = Configs.Moving.MOVING_STARVATION_HINT_THRESHOLD.getIntegerValue();
			double range = Configs.Moving.MOVING_INTERACT_RANGE.getDoubleValue();
			for (Entity v : findUnprocessedVillagers(mc)) {
				if (v.getUuid().equals(dispatchedVillagerId)) { // 排除自己（防自抢）
					continue;
				}
				if (v.getPos().distanceTo(mc.player.getPos()) > range) { // 候选内（确定可服务）
					continue;
				}
				int h = starvation.getOrDefault(new VillagerKey(v.getUuid()), 0);
				if (h >= hintThreshold && h > myHunger) { // 严格大于（防互抢）
					return true;
				}
			}
			return false;
		};
	}

	/**
	 * 任务正常结束回调：先经 handleTaskEnded 统一标记村民已处理/容器清饥饿/CONFIG 冷却记录，再交基类同步背包满暂停/传送超时告警/输出
	 * IO 解除暂停。
	 */
	@Override
	protected void onTaskEnded(Task task, TaskResult result) {
		handleTaskEnded(task, result);
		super.onTaskEnded(task, result);
	}

	/**
	 * 任务被看门狗强杀时的回调：强杀与完成统一标记/清饥饿——防卡死村民每 TASK_TIMEOUT 周期被重派的活锁
	 * （强杀不标记则村民永远"未处理"，看门狗每轮重派 → 无限循环）；随后交基类（基类中断回调默认无操作）。 强杀路径无结果可判
	 * CONFIG，不记录失败冷却（强杀时任务状态不可信）。
	 */
	@Override
	protected void onTaskInterrupted(Task task) {
		// 强杀路径无结果可用：用任务访问器判断（与现状 handleTaskEnded 强杀路径一致）
		if (task instanceof TradeTask ts) {
			if (ts.isYielded()) {
				// 安全点让位后 CLOSING 阶段被强杀：不得误标已处理、饥饿不 +1（等价 handleTaskEnded 让位分支）
			} else if (!ts.isInventoryBlocked()) {
				// 标记该村民已处理并清除饥饿（强杀不标记则村民永远"未处理"，看门狗每轮重派 → 无限循环）；
				// 背包满不标记（保留现状 inventoryBlocked 短路语义：保留记录，背包清空后由失效清理重试）
				processedVillagers.add(dispatchedVillagerId);
				starvation.remove(new VillagerKey(dispatchedVillagerId));
				seenKeys.remove(new VillagerKey(dispatchedVillagerId));
				hintedKeys.remove(new VillagerKey(dispatchedVillagerId));
			}
		} else if (task instanceof ContainerIOTask op) {
			// 容器 IO 强杀也清饥饿 + 窗口快照移除——保持现状（无论完成还是强杀均视为该目标已执行一次，
			// 饥饿记录才不会永不清理，防饿死回归）
			starvation.remove(new ContainerKey(op.getIntent().ioKey()));
			seenKeys.remove(new ContainerKey(op.getIntent().ioKey()));
		}
		super.onTaskInterrupted(task);
	}

	/**
	 * 任务正常结束统一收尾：村民标记已处理并清饥饿 / 容器清饥饿 + CONFIG 失败冷却。 容器 IO 任何结果（含失败）均清饥饿——保持现状
	 * （无论完成还是失败均视为该目标已执行一次，饥饿记录才不会永不清理，防饿死回归）；CONFIG 失败（目标位置非容器/方块类型不符）
	 * 额外冷却排除，防失败容器整轮忙循环。
	 */
	private void handleTaskEnded(Task task, TaskResult result) {
		if (task instanceof TradeTask ts) {
			if (ts.isYielded()) {
				// 安全点让位：不标记已处理、饥饿不 +1（被让位 ≠ 错过——保持原 hunger 公平参与下次评分；
				// 旧实现「让位 +1」使 A=2→3 又抢回 B → 自抢/互抢死循环（P1/P2），已废弃）；
				// seenKeys 保留（目标在范围内不离窗；出范围自然离窗 +1 记错过）
			} else if (!(result.isFailed() && result.reason() == TaskResult.FailReason.INVENTORY_BLOCKED)) {
				// 标记该村民已处理并清除饥饿（完成与超时路径均在此统一标记）；
				// 背包满失败不标记（等价现状 inventoryBlocked 短路语义：保留记录，背包清空后由失效清理重试）
				processedVillagers.add(dispatchedVillagerId);
				starvation.remove(new VillagerKey(dispatchedVillagerId));
				seenKeys.remove(new VillagerKey(dispatchedVillagerId));
				hintedKeys.remove(new VillagerKey(dispatchedVillagerId));
			}
		} else if (task instanceof ContainerIOTask op) {
			// 容器 IO 完成 → 清饥饿记录 + 窗口快照移除（任何结果均清；村民选中执行后进 processedVillagers 并显式移除，语义等价）
			starvation.remove(new ContainerKey(op.getIntent().ioKey()));
			seenKeys.remove(new ContainerKey(op.getIntent().ioKey()));
			hintedKeys.remove(new ContainerKey(op.getIntent().ioKey()));
			// CONFIG 失败 → 冷却排除：失败清饥饿后该容器仍 pending（scanPendingContainers 不校验方块类型）且 bonus
			// 恒胜出，
			// 不冷却则整轮忙循环（独占运行位 + 弹窗刷屏）；冷却 CONFIG_FAIL_COOLDOWN tick 到期重试
			if (result.isFailed() && result.reason() == TaskResult.FailReason.CONFIG) {
				failedContainerCooldown.put(op.getIntent().containerKey(), lastWorldTick);
			}
		}
	}

	/**
	 * 任务运行期钩子（基类每任务 tick 调用，先于 currentTask.tick）：L1 任务期窗口记账——任务运行期间进入范围的目标并入
	 * seenKeys，离窗差集补记错过（修「任务期进出范围无痕迹」的结构性错过）。 容器候选每 tick 重算（距离实时，无 TTL 漏记）；村民每 tick
	 * 记账（正确性优先，无节流）。 冷却中的失败容器不记账（配置错误目标既不执行也不记饥饿）。
	 */
	@Override
	protected void onTaskTick(MinecraftClient mc) {
		lastWorldTick = mc.world.getTime();
		for (ContainerCandidate c : containerIOScheduler.findPendingContainers(mc)) {
			if (!isContainerOnCooldown(mc, c.containerKey())) {
				seenKeys.add(new ContainerKey(c.ioKey()));
			}
		}
		for (Entity v : findUnprocessedVillagers(mc)) {
			seenKeys.add(new VillagerKey(v.getUuid()));
		}
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		lastWorldTick = mc.world.getTime();
		// 残留窗口兜底：让位/异常路径可能遗留「交互在途、窗口晚到」的窗口（任务已结束）→ 检测到即关闭
		// （HandledScreen.close() 完整链路：发 CloseHandledScreenC2SPacket +
		// setScreen(null)；服务端
		// onCloseHandledScreen 不校验 syncId 无条件关 handler——1.20.4 源码核实，等效阻止窗口出现）
		if (mc.currentScreen instanceof MerchantScreen || mc.currentScreen instanceof GenericContainerScreen
				|| mc.currentScreen instanceof ShulkerBoxScreen) {
			mc.currentScreen.close();
			AutoTrade.logger.info("[MovingMode] 残留窗口兜底关闭 ({})", mc.currentScreen.getClass().getSimpleName());
		}
		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc)) {
			return;
		}

		// 饥饿阈值提示：scanRange 内 hunger ≥ 阈值且未提示过 → 一次性提示（hintedKeys 防刷屏；服务完成清除后可再提示）
		int hintThreshold = Configs.Moving.MOVING_STARVATION_HINT_THRESHOLD.getIntegerValue();
		for (Entity v : findUnprocessedVillagers(mc)) {
			VillagerKey k = new VillagerKey(v.getUuid());
			int h = starvation.getOrDefault(k, 0);
			if (h >= hintThreshold && hintedKeys.add(k)) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.moving.starvation_hint",
						"villager", h, v.getPos().distanceTo(mc.player.getPos()));
			}
		}
		for (ContainerCandidate c : containerIOScheduler.findPendingContainers(mc)) {
			ContainerKey k = new ContainerKey(c.ioKey());
			int h = starvation.getOrDefault(k, 0);
			if (h >= hintThreshold && hintedKeys.add(k)) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.moving.starvation_hint",
						"container", h, c.distance());
			}
		}

		// 收集候选：范围内需要 IO 的容器条目（过滤 CONFIG 冷却中）∪ 未处理村民（无冷却，每 tick 决策）；
		// 容器先入列（bonus=CONTAINER_BONUS），村民后入列（bonus=0）——评分相同时先入列者胜（容器优先）
		candidates.clear();
		for (ContainerCandidate c : containerIOScheduler.findPendingContainers(mc)) {
			if (!isContainerOnCooldown(mc, c.containerKey())) {
				candidates.add(new Candidate(new ContainerKey(c.ioKey()), CONTAINER_BONUS, c.distance(), c, null));
			}
		}
		double interactRange = Configs.Moving.MOVING_INTERACT_RANGE.getDoubleValue();
		for (Entity v : findUnprocessedVillagers(mc)) {
			double dist = v.getPos().distanceTo(mc.player.getPos());
			if (dist > interactRange) {
				continue; // 候选限交互距离（远处村民只记账不派发——交互必失败的结构性错过根治）
			}
			candidates.add(new Candidate(new VillagerKey(v.getUuid()), 0, dist, null, v));
		}

		// 本 tick 候选键集合（离窗差集用）
		candidateKeys.clear();
		for (Candidate c : candidates) {
			candidateKeys.add(c.key());
		}

		// 离窗检测（错过惩罚 +1）：seenKeys（自上次决策以来见过的候选，含任务期 L1 收集）中已不在候选的
		// → 玩家离开范围/目标消失/不再 need 且未服务 = 错过一次；已完成目标已在结束钩子从 seenKeys 移除，不误判
		for (StarvationKey k : seenKeys) {
			if (!candidateKeys.contains(k)) {
				starvation.merge(k, 1, this::capStarvation);
			}
		}

		// ★ seenKeys 快照更新必须为拷贝语义：引用赋值会因 candidateKeys 每 tick clear 复用而清空 seenKeys
		// → 离窗检测永不触发 → 整个错过惩罚机制静默失效
		seenKeys.clear();
		seenKeys.addAll(candidateKeys);

		if (candidates.isEmpty()) {
			return;
		}

		// 驻留老化：候选目标按 lastServedTick 老化——从未服务或 now - lastServed >= 老化间隔 → 饥饿 +1 并重置周期
		// （每周期至多 +1；3 周期后必然超容器 bonus 2 插队；容器统一老化——被村民插队压着的容器不会反向饿死）
		long agingInterval = Configs.Moving.MOVING_STARVATION_AGING_INTERVAL.getIntegerValue();
		for (Candidate c : candidates) {
			long lastServed = lastServedTick.getOrDefault(c.key(), Long.MIN_VALUE);
			if (lastServed == Long.MIN_VALUE || mc.world.getTime() - lastServed >= agingInterval) {
				starvation.merge(c.key(), 1, this::capStarvation);
				lastServedTick.put(c.key(), mc.world.getTime());
			}
		}

		// 一遍循环评分：score = 饥饿×HUNGER_WEIGHT + bonus（无距离项）；tie-break：
		// 最久未服务（lastServedTick 最小，默认 MIN_VALUE = 从未服务优先）→ 距离近 → 先入列（容器，平分时容器胜）
		Candidate best = null;
		int bestScore = Integer.MIN_VALUE;
		long bestLastServed = Long.MAX_VALUE;
		double bestDistance = Double.MAX_VALUE;
		for (Candidate c : candidates) {
			int score = starvation.getOrDefault(c.key(), 0) * HUNGER_WEIGHT + c.bonus();
			long lastServed = lastServedTick.getOrDefault(c.key(), Long.MIN_VALUE);
			if (best == null || score > bestScore || (score == bestScore && (lastServed < bestLastServed
					|| (lastServed == bestLastServed && c.distance() < bestDistance)))) {
				best = c;
				bestScore = score;
				bestLastServed = lastServed;
				bestDistance = c.distance();
			}
		}

		// 派发：容器 → startCandidate（按原对象派发，安全点让位检查器随任务注入）；村民 → 构造器锁定派发，会话内不再自行重扫（竞态修复）
		if (best.key() instanceof ContainerKey k) {
			dispatchedContainerIoKey = k.ioKey(); // 记录当前容器条目 ioKey（检查器查自身饥饿用）
			containerIOScheduler.startCandidate(best.container(), this::setTaskIfEmpty);
		} else {
			dispatchedVillagerId = ((VillagerKey) best.key()).uuid();
			setTaskIfEmpty(new MovingTradeTask(dispatchedVillagerId, villagerCompetitorChecker));
			AutoTrade.logger.info("[MovingMode] IDLE → TRADE_SESSION (villager uuid={})", dispatchedVillagerId);
		}
		lastServedTick.put(best.key(), mc.world.getTime());
	}

	/** 扫描范围（格数）；MOVING 的扫描放大（×范围乘数配置）与处理记录失效阈值（> 乘数×范围）共用 */
	private double scanRange() {
		return Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue()
				* Configs.Moving.MOVING_RANGE_MULTIPLIER.getDoubleValue();
	}

	/**
	 * 返回范围内全部未处理村民（距离升序），供评分决策（tie-break 距离项）与 L1/L2 村民检查。 同时清理失效处理记录：记录失效条件 = 村民消失
	 * 或 距离 > 范围乘数×扫描范围 —— MOVING 村民交易后不消失，
	 * 若仅按消失清理则记录只增不减、补货后永不重交易；玩家离开范围后记录失效，返回时村民可重新交易。
	 * 复用集合字段：调用方须在方法返回后立即消费（tickIdle 候选收集与 L2 检查器 isEmpty 检查，均立即消费，无别名问题）。
	 */
	private List<Entity> findUnprocessedVillagers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}
		double range = scanRange();
		// 清理失效处理记录（消失 或 离开 范围乘数×扫描范围）：单次遍历构建 UUID → 实体 映射后一次 removeIf
		Map<UUID, Entity> loaded = VillagerHelper.buildUuidEntityMap(mc);
		processedVillagers.removeIf(uuid -> {
			Entity e = loaded.get(uuid);
			return e == null || e.getPos().distanceTo(mc.player.getPos()) > range;
		});
		// 收集未处理村民并按距离升序（复用集合）
		unprocessedVillagers.clear();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			if (!processedVillagers.contains(e.getUuid())) {
				unprocessedVillagers.add(e);
			}
		}
		unprocessedVillagers.sort(Comparator.comparingDouble(e -> e.getPos().distanceTo(mc.player.getPos())));
		return unprocessedVillagers;
	}

	// 饥饿计数自增并封顶（cap = STARVATION_CAP，必须 > 容器 bonus 2 保证村民插队）
	private int capStarvation(int oldValue, int inc) {
		return Math.min(oldValue + inc, STARVATION_CAP);
	}

	/** 容器是否处于 CONFIG 失败冷却（tickIdle 候选收集 / L1 记账 / L2 检查器三处共用过滤；惰性清理过期条目） */
	private boolean isContainerOnCooldown(MinecraftClient mc, String containerKey) {
		Long at = failedContainerCooldown.get(containerKey);
		if (at == null) {
			return false;
		}
		if (mc.world.getTime() - at >= CONFIG_FAIL_COOLDOWN) {
			// 冷却到期，惰性移除
			failedContainerCooldown.remove(containerKey);
			return false;
		}
		return true;
	}

	@Override
	public void reset() {
		super.reset();
		processedVillagers.clear();
		dispatchedVillagerId = null;
		dispatchedContainerIoKey = "";
		starvation.clear();
		seenKeys.clear();
		lastServedTick.clear();
		failedContainerCooldown.clear();
		hintedKeys.clear();
	}

	/** 返回已处理村民数（HUD 只读展示用） */
	public int getProcessedCount() {
		return processedVillagers.size();
	}

	/** 返回当前饥饿记账条目数（HUD 只读展示用） */
	public int getStarvationCount() {
		return starvation.size();
	}
}