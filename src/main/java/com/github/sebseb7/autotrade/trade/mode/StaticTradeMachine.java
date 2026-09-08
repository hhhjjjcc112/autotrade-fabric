package com.github.sebseb7.autotrade.trade.mode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.VillagerTradeCache;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * STATIC 模式：站在固定位置逐村交易 + 容器 IO + 交易/IO 冷却。 扫描/名单/已处理记录全部在机器层维护：每轮扫描范围内全部村民建立
 * 名单（targetVillagers），按顺序逐个派发单村民会话，名单处理完后进入交易冷却（约 5 秒），冷却结束重新扫描开始新一轮
 * （每轮都重新处理全部村民，不记忆上一轮谁已耗尽）。 容器 IO 在交易冷却期间按 IO 间隔尝试（经机器层 ContainerIOScheduler
 * 调度），交易进行中不插队。
 */
public class StaticTradeMachine extends AbstractTradeMachine {

	/** 本轮交易名单（扫描到的村民 UUID，按扫描顺序） */
	private final List<UUID> targetVillagers = new ArrayList<>();
	/** 本轮已处理村民（派发完成或超时，均标记；背包满时不标记） */
	private final List<UUID> processedVillagers = new ArrayList<>();
	/** 名单遍历游标 */
	private int targetIndex = 0;
	/** 本轮名单是否已扫描（冷却期间置 false，冷却结束重新扫描建名单） */
	private boolean scanned = false;
	/** 当前派发给会话的目标村民 UUID（会话结束日志显示用；标记已处理经基类骨架钩子以会话锁定的 UUID 完成，与之一致） */
	private UUID dispatchedVillagerId = null;

	private int tradeCooldown = 0;
	private int containerIOCooldown;

	public StaticTradeMachine() {
		super();
		containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}

	/**
	 * 任务正常结束回调：先经基类任务结束骨架统一标记已处理/设置冷却，再输出会话日志（仅交易会话）， 最后交基类同步背包满暂停 （会话 blocked →
	 * 暂停交易；输出 IO 完成 → 解除暂停）。
	 */
	@Override
	protected void onTaskEnded(Task task, TaskResult result) {
		// 正常结束收尾：骨架按任务类型分派——村民会话 → markVillagerProcessed；容器 IO → onContainerTaskEnded
		handleTaskEnded(task, result);
		if (task instanceof TradeTask) {
			// 会话结束日志仅 TradeTask 分支输出（容器 IO 结束不产生该行——与重构前一致）；
			// blocked 值用基类谓词重算（与骨架内短路判断同源）
			AutoTrade.logger.info("[StaticMode] Trade session done (villager={}, blocked={})", dispatchedVillagerId,
					isInventoryBlockedResult(result));
		}
		// 基类同步背包满暂停状态（会话 blocked → 暂停交易；输出 IO 完成 → 解除暂停）
		super.onTaskEnded(task, result);
	}

	/**
	 * 任务被看门狗强杀（forceAbortTask）时的回调：先经基类强杀骨架统一标记已处理，再输出会话日志（仅交易会话）， 最后交基类——
	 * 防止卡死村民反复重派的活锁（强杀后不清除处理记录，则下一轮扫描会跳过该村民，不会无限重派同一卡死村民）。
	 */
	@Override
	protected void onTaskInterrupted(Task task) {
		// 骨架分派：村民会话 → markVillagerProcessed（背包满短路）；容器 IO →
		// onContainerTaskInterrupted（设置冷却）
		handleTaskInterrupted(task);
		if (task instanceof TradeTask ts) {
			// 会话结束日志仅 TradeTask 分支输出（容器 IO 强杀不产生该行——与重构前一致）；
			// 强杀无结果，blocked 用保留的 isInventoryBlocked 访问器判断（与正常结束的 result 判断等价）
			AutoTrade.logger.info("[StaticMode] Trade session done (villager={}, blocked={})", dispatchedVillagerId,
					ts.isInventoryBlocked());
		}
		super.onTaskInterrupted(task);
	}

	/** 已处理标记钩子（基类骨架调用）：加入本轮已处理名单 */
	@Override
	protected void markVillagerProcessed(UUID villagerId) {
		processedVillagers.add(villagerId);
	}

	/** 容器 IO 正常结束钩子（基类骨架调用）：设置 IO 间隔冷却（任意结果均设，与重构前一致） */
	@Override
	protected void onContainerTaskEnded(ContainerIOTask op, TaskResult result) {
		containerIOCooldown = Configs.Static.CONTAINER_IO_INTERVAL.getIntegerValue();
	}

	/** 容器 IO 被看门狗强杀钩子（基类骨架调用）：同样设置 IO 间隔冷却（看门狗强杀后不立即重试同一容器） */
	@Override
	protected void onContainerTaskInterrupted(ContainerIOTask op) {
		containerIOCooldown = Configs.Static.CONTAINER_IO_INTERVAL.getIntegerValue();
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		if (tradeCooldown > 0)
			tradeCooldown--;
		if (containerIOCooldown > 0)
			containerIOCooldown--;

		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 交易冷却结束 → 尝试派发村民（本分支恒 return：派发成功或本轮名单耗尽进入冷却）
		if (tradeCooldown == 0) {
			// 本轮名单尚未扫描 → 重新扫描建名单（新一轮开始：重建名单并清空已处理记录）
			if (!scanned) {
				targetVillagers.clear();
				processedVillagers.clear();
				scanVillagers(mc);
				// 9.8 附加收益：名单按缓存状态重排——已知命中的村民优先服务（未被未知/不匹配村民阻塞；不命中的派发时跳过）
				targetVillagers.sort(Comparator.comparingInt(id -> VillagerTradeCache.isMatch(id) ? 0 : 1));
				targetIndex = 0;
				scanned = true;
			}
			// 按名单顺序派发下一个未处理村民（实体已消失的跳过，不标记）
			while (targetIndex < targetVillagers.size()) {
				UUID id = targetVillagers.get(targetIndex++);
				if (!processedVillagers.contains(id)) {
					// 9.8 缓存：TTL 内已知不匹配 → 跳过不开窗（不标记已处理——缓存为唯一事实源，TTL 到期自动复查）
					if (isCachedMiss(id, mc.world.getTime())) {
						continue;
					}
					Entity e = VillagerHelper.findByUuid(mc, id);
					if (e != null) {
						dispatchedVillagerId = id;
						setTaskIfEmpty(new StaticTradeTask(id));
						AutoTrade.logger.info("[StaticMode] IDLE → TRADE_SESSION (villager={}) at tick {}",
								dispatchedVillagerId, mc.world.getTime());
						return;
					}
				}
			}
			// 名单空（无村民可派发：扫描无村民 或 全部已处理）→ 进入交易冷却（等价于现状「名单处理完 → COMPLETED →
			// 冷却」）；冷却期间允许立即检查容器 IO；冷却结束重新扫描
			tradeCooldown = Configs.Static.TRADE_INTERVAL.getIntegerValue();
			containerIOCooldown = 0;
			scanned = false;
			AutoTrade.logger.info("[StaticMode] Round done, cooldown={}", tradeCooldown);
			return;
		}

		// 交易冷却期间（非交易中）→ 尝试容器 IO；无 IO 需求则重置为闲置间隔
		if (containerIOCooldown == 0) {
			if (containerIOScheduler.startNearest(mc, this::setTaskIfEmpty))
				return;
			containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
		}
	}

	// 扫描范围内全部村民/流浪商人建立本轮名单（与现状 StaticTradeTask 首扫逻辑一致）
	// 流浪商人说明：findNearby 含流浪商人——无匹配交易的商人学到不匹配（TTL）是正确的（其交易终身固定）；
	// 已命中的商人若消失仅留下无害的死条目（UUID 永不复用），不做特殊处理
	private void scanVillagers(MinecraftClient mc) {
		double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			targetVillagers.add(e.getUuid());
		}
	}

	@Override
	public void reset() {
		super.reset();
		targetVillagers.clear();
		processedVillagers.clear();
		targetIndex = 0;
		scanned = false;
		dispatchedVillagerId = null;
		tradeCooldown = 0;
		containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}

	/** 返回本轮已处理村民数（HUD 只读展示用） */
	public int getProcessedCount() {
		return processedVillagers.size();
	}

	/** 返回本轮目标村民数（HUD 只读展示用） */
	public int getTargetCount() {
		return targetVillagers.size();
	}

	/** 返回交易冷却剩余 tick（HUD 只读展示用） */
	public int getTradeCooldown() {
		return tradeCooldown;
	}

	/** 返回容器 IO 间隔冷却剩余 tick（HUD 只读展示用） */
	public int getContainerIOCooldown() {
		return containerIOCooldown;
	}

	/** 返回当前派发的目标村民 UUID（HUD 只读展示用；HUD 预留，当前无调用者） */
	public UUID getDispatchedVillagerId() {
		return dispatchedVillagerId;
	}
}
