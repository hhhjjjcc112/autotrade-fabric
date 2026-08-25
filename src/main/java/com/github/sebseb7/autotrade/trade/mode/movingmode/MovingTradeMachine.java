package com.github.sebseb7.autotrade.trade.mode.movingmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper.ContainerCandidate;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * MOVING 模式：无 IO/交易冷却，每 tick 对「≤4 格需要 IO 的容器 ∪ 范围内未处理村民」做目标级饥饿评分（A.3），
 * 选最高分执行——容器有固定加成（决策零成本），村民被持续抢占时饥饿上涨可插队（防饿死）；
 * 容器失败不做冷却/禁用（位置错误类失败为本地零成本检查，重试无害）。 无目标时保持闲置，等待玩家靠近。
 * 候选收集/处理记录（processedVillagers）在机器层维护：评分选中村民后通过构造器锁定派发， 会话内不再自行重扫（修复「machine 选
 * A、session 取到 B」的竞态）。
 */
public class MovingTradeMachine extends AbstractTradeMachine {

	/** 饥饿权重：饥饿计数 × HUNGER_WEIGHT（A.3 参数，暂硬编码，可配置化） */
	private static final int HUNGER_WEIGHT = 1;
	/** 容器固定加成：容器决策零成本（本地背包判断），村民必须开窗试探（A.3 参数） */
	private static final int CONTAINER_BONUS = 2;
	/** 距离权重：距离 × DISTANCE_WEIGHT（A.3 参数） */
	private static final double DISTANCE_WEIGHT = 0.1;
	/** 饥饿计数上限（A.3 参数） */
	private static final int STARVATION_CAP = 10;

	/**
	 * 已处理村民记录（交易完成或超时均标记；背包满时不标记），由 findUnprocessedVillagers 做失效清理。 HashSet：村民 id
	 * 无重复、contains 是每 tick 热路径（M1 收集），集合无序不影响后续显式距离排序（L190）
	 */
	private final Set<Integer> processedVillagers = new HashSet<>();
	/** 当前派发给会话的目标村民 id（任务结束钩子标记已处理用，完成与强杀统一） */
	private int dispatchedVillagerId = 0;

	/** 饥饿记账统一键：村民 = 实体 id，容器 = ioKey（坐标+方向） */
	private sealed interface StarvationKey permits VillagerKey, ContainerKey {
	}
	/** 村民饥饿键：实体 id */
	private record VillagerKey(int entityId) implements StarvationKey {
	}
	/** 容器饥饿键：ContainerIOHelper.ContainerCandidate.ioKey()（跨 ItemIO 条目稳定） */
	private record ContainerKey(String ioKey) implements StarvationKey {
	}

	/** 统一评分候选：饥饿键 + 容器加成 + 距离 + 原对象引用（容器/村民，派发用）；容器先入列 → 平分时容器优先 */
	private record Candidate(StarvationKey key, int bonus, double distance, ContainerCandidate container,
			Entity villager) {
	}

	/** 目标级饥饿记账：统一键 → 路过未执行次数（A.3，cap 见 STARVATION_CAP） */
	private final Map<StarvationKey, Integer> starvation = new HashMap<>();

	public MovingTradeMachine() {
		super();
	}

	/**
	 * 任务正常结束回调：先经 handleTaskEnded 统一标记村民已处理/容器清饥饿，再交基类同步背包满暂停/传送超时告警/输出 IO 解除暂停。
	 */
	@Override
	protected void onTaskEnded(Task task, TaskResult result) {
		handleTaskEnded(task, result);
		super.onTaskEnded(task, result);
	}

	/**
	 * 任务被看门狗强杀时的回调：强杀与完成统一标记/清饥饿——防卡死村民每 TASK_TIMEOUT 周期被重派的活锁
	 * （强杀不标记则村民永远"未处理"，看门狗每轮重派 → 无限循环）；随后交基类（基类中断回调默认无操作）
	 */
	@Override
	protected void onTaskInterrupted(Task task) {
		// 强杀路径无结果可用：用任务访问器判断（与现状 handleTaskEnded 强杀路径一致）
		if (task instanceof TradeTask ts) {
			// 标记该村民已处理并清除饥饿（强杀不标记则村民永远"未处理"，看门狗每轮重派 → 无限循环）；
			// 背包满不标记（保留现状 inventoryBlocked 短路语义：保留记录，背包清空后由失效清理重试）
			if (!ts.isInventoryBlocked()) {
				processedVillagers.add(dispatchedVillagerId);
				starvation.remove(new VillagerKey(dispatchedVillagerId));
			}
		} else if (task instanceof ContainerIOTask op) {
			// 容器 IO 强杀也清饥饿——保持现状（防饿死回归：无论完成还是强杀均视为该目标已执行一次，饥饿记录才不会永不清理）
			starvation.remove(new ContainerKey(op.getIntent().ioKey()));
		}
		super.onTaskInterrupted(task);
	}

	/**
	 * 任务正常结束统一收尾：村民标记已处理并清饥饿 / 容器清饥饿。 容器 IO 任何结果（含失败）均清饥饿——保持现状
	 * （无论完成还是失败均视为该目标已执行一次，饥饿记录才不会永不清理，防饿死回归）
	 */
	private void handleTaskEnded(Task task, TaskResult result) {
		if (task instanceof TradeTask ts) {
			// 标记该村民已处理并清除饥饿（完成与超时路径均在此统一标记）；
			// 背包满失败不标记（等价现状 inventoryBlocked 短路语义：保留记录，背包清空后由失效清理重试）
			if (!(result.isFailed() && result.reason() == TaskResult.FailReason.INVENTORY_BLOCKED)) {
				processedVillagers.add(dispatchedVillagerId);
				starvation.remove(new VillagerKey(dispatchedVillagerId));
			}
		} else if (task instanceof ContainerIOTask op) {
			// 容器 IO 完成 → 清饥饿记录（任何结果均清；村民选中执行后进 processedVillagers 并显式移除，语义等价）
			starvation.remove(new ContainerKey(op.getIntent().ioKey()));
		}
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 收集候选：≤4 格需要 IO 的容器 ∪ 范围内未处理村民（无冷却，每 tick 决策）；
		// 容器先入列（bonus=CONTAINER_BONUS），村民后入列（bonus=0）——平分时容器优先（等价原 bestContainerScore
		// >= bestVillagerScore）
		List<Candidate> candidates = new ArrayList<>();
		for (ContainerCandidate c : ContainerIOHelper.findPendingContainers(mc))
			candidates.add(new Candidate(new ContainerKey(c.ioKey()), CONTAINER_BONUS, c.distance(), c, null));
		for (Entity v : findUnprocessedVillagers(mc))
			candidates.add(
					new Candidate(new VillagerKey(v.getId()), 0, v.getPos().distanceTo(mc.player.getPos()), null, v));

		// 生命周期清理：不在本 tick 候选集合的饥饿记录移除（已处理/离开范围/不再需要 IO），防 Map 膨胀。
		// 先构建 Set 再 retainAll，避免每 tick 的 stream/toList 中间分配（与 List 版 retainAll 结果一致）
		Set<StarvationKey> keys = new HashSet<>(candidates.size());
		for (Candidate c : candidates)
			keys.add(c.key());
		starvation.keySet().retainAll(keys);

		if (candidates.isEmpty())
			return;

		// 一遍循环评分：score = 饥饿×HUNGER_WEIGHT + bonus − 距离×DISTANCE_WEIGHT，严格 >
		// 取最高（容器先入列保证平分时容器胜出）
		Candidate best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Candidate c : candidates) {
			double score = starvation.getOrDefault(c.key(), 0) * HUNGER_WEIGHT + c.bonus()
					- c.distance() * DISTANCE_WEIGHT;
			if (score > bestScore) {
				bestScore = score;
				best = c;
			}
		}

		// 未选中的候选饥饿 +1（cap）；选中者：容器/村民均在任务结束钩子清零
		for (Candidate c : candidates)
			if (c != best)
				starvation.merge(c.key(), 1, this::capStarvation);

		// 派发：容器 → startContainerIO（按原对象派发）；村民 → 构造器锁定派发，会话内不再自行重扫（竞态修复）
		if (best.key() instanceof ContainerKey) {
			ContainerIOHelper.startContainerIO(best.container(), this::setTaskIfEmpty);
			return;
		}
		dispatchedVillagerId = ((VillagerKey) best.key()).entityId();
		setTaskIfEmpty(new MovingTradeTask(dispatchedVillagerId));
		AutoTrade.logger.info("[MovingMode] IDLE → TRADE_SESSION (villager id={})", dispatchedVillagerId);
	}

	/** 扫描范围（格数）；MOVING 的扫描放大（×范围乘数配置）与处理记录失效阈值（> 乘数×范围）共用 */
	private double scanRange() {
		return Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue()
				* Configs.Moving.MOVING_RANGE_MULTIPLIER.getDoubleValue();
	}

	/**
	 * 返回范围内全部未处理村民（距离升序），供每 tick 评分决策（M1/M6）。 同时清理失效处理记录（M3）：记录失效条件 = 村民消失 或 距离 >
	 * 范围乘数×扫描范围 —— MOVING 村民交易后不消失，若仅按消失清理则记录只增不减、补货后永不重交易； 玩家离开范围后记录失效，返回时村民可重新交易。
	 */
	private List<Entity> findUnprocessedVillagers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}
		double range = scanRange();
		// M3：清理失效处理记录（消失 或 离开 范围乘数×扫描范围）
		processedVillagers.removeIf(id -> {
			Entity e = mc.world.getEntityById(id);
			return e == null || e.getPos().distanceTo(mc.player.getPos()) > range;
		});
		// M1/M6：收集未处理村民并按距离升序（供评分使用距离项）
		List<Entity> unprocessed = new ArrayList<>();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			if (!processedVillagers.contains(e.getId()))
				unprocessed.add(e);
		}
		unprocessed.sort(Comparator.comparingDouble(e -> e.getPos().distanceTo(mc.player.getPos())));
		return unprocessed;
	}

	// 饥饿计数自增并封顶（cap = STARVATION_CAP）
	private int capStarvation(int oldValue, int inc) {
		return Math.min(oldValue + inc, STARVATION_CAP);
	}

	@Override
	public void reset() {
		super.reset();
		processedVillagers.clear();
		dispatchedVillagerId = 0;
		starvation.clear();
	}
}
