package com.github.sebseb7.autotrade.trade.mode.staticmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * STATIC 模式：站在固定位置逐村交易 + 容器 IO + 交易/IO 冷却。 扫描/名单/已处理记录全部在机器层维护：每轮扫描范围内全部村民建立
 * 名单（targetVillagers），按顺序逐个派发单村民会话，名单处理完后进入交易冷却（约 5 秒），冷却结束重新扫描开始新一轮
 * （每轮都重新处理全部村民，不记忆上一轮谁已耗尽）。 容器 IO 在交易冷却期间按 IO 间隔尝试，交易进行中不插队。
 */
public class StaticTradeMachine extends AbstractTradeMachine {

	/** 本轮交易名单（扫描到的村民 id，按扫描顺序） */
	private final List<Integer> targetVillagers = new ArrayList<>();
	/** 本轮已处理村民（派发完成或超时，均标记；背包满时不标记） */
	private final List<Integer> processedVillagers = new ArrayList<>();
	/** 名单遍历游标 */
	private int targetIndex = 0;
	/** 本轮名单是否已扫描（冷却期间置 false，冷却结束重新扫描建名单） */
	private boolean scanned = false;
	/** 当前派发给会话的目标村民 id（任务结束钩子标记已处理用（完成与强杀统一）） */
	private int dispatchedVillagerId = 0;

	private int tradeCooldown = 0;
	private int containerIOCooldown;

	public StaticTradeMachine() {
		super();
		containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}

	/**
	 * 任务正常完成回调：先经 handleTaskEnded 统一标记已处理/设置冷却，再交基类同步背包满暂停 （会话 blocked → 暂停交易；输出 IO
	 * 完成 → 解除暂停）。
	 */
	@Override
	protected void onTaskDone(Task task) {
		// 完成与强杀共用收尾：统一标记已处理/设置冷却
		handleTaskEnded(task);
		// 基类同步背包满暂停状态（会话 blocked → 暂停交易；输出 IO 完成 → 解除暂停）
		super.onTaskDone(task);
	}

	/**
	 * 任务被看门狗强杀（forceAbortTask）时的回调：与正常完成统一标记已处理—— 防止卡死村民反复重派的活锁
	 * （强杀后不清除处理记录，则下一轮扫描会跳过该村民，不会无限重派同一卡死村民）。
	 */
	@Override
	protected void onTaskInterrupted(Task task) {
		handleTaskEnded(task);
		super.onTaskInterrupted(task);
	}

	/**
	 * 任务结束统一收尾（正常完成 onTaskDone 与看门狗强杀 onTaskInterrupted 共用）： 标记已处理/设置冷却，
	 * 两个结束路径语义一致。
	 */
	private void handleTaskEnded(Task task) {
		if (task instanceof TradeTask ts) {
			// 背包满不标记（保留现状 inventoryBlocked 时不 add 的短路语义：保留记录，背包清空后下轮重试该村民）；
			// 否则标记已处理（完成与超时路径均在此统一标记）。
			// 交易冷却由 tickIdle 在「本轮名单耗尽」时统一设置（单村民派发制，会话间无冷却）
			if (!ts.isInventoryBlocked()) {
				processedVillagers.add(dispatchedVillagerId);
			}
			AutoTrade.logger.info("[StaticMode] Trade session done (villager={}, blocked={})", dispatchedVillagerId,
					ts.isInventoryBlocked());
		} else {
			// 容器 IO 结束 → 设置 IO 间隔冷却
			containerIOCooldown = Configs.Static.CONTAINER_IO_INTERVAL.getIntegerValue();
		}
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
				targetIndex = 0;
				scanned = true;
			}
			// 按名单顺序派发下一个未处理村民（实体已消失的跳过，不标记）
			while (targetIndex < targetVillagers.size()) {
				int id = targetVillagers.get(targetIndex++);
				if (!processedVillagers.contains(id)) {
					Entity e = mc.world.getEntityById(id);
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
			if (ContainerIOHelper.startContainerIO(mc, this::setTaskIfEmpty))
				return;
			containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
		}
	}

	// 扫描范围内全部村民/流浪商人建立本轮名单（与现状 StaticTradeTask 首扫逻辑一致）
	private void scanVillagers(MinecraftClient mc) {
		double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			targetVillagers.add(e.getId());
		}
	}

	@Override
	public void reset() {
		super.reset();
		targetVillagers.clear();
		processedVillagers.clear();
		targetIndex = 0;
		scanned = false;
		dispatchedVillagerId = 0;
		tradeCooldown = 0;
		containerIOCooldown = Configs.Static.CONTAINER_IO_IDLE_INTERVAL.getIntegerValue();
	}
}
