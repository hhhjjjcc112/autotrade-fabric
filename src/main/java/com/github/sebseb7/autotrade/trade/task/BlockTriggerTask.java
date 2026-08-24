package com.github.sebseb7.autotrade.trade.task;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.ReturnTriggerType;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 「交互指定方块触发机关」任务（VOID 返回触发，附录 H）：与触发方块交互产生红石信号，由装置把玩家传回原侧。 三种类型共用 interactBlock
 * 交互原语，差异只在副作用处理（TRAPPED_CHEST 开箱后确认 GUI 出现即保持打开——信号持续到玩家传回时服务端自动关窗；
 * BUTTON/LEVER 交互即完成，无 GUI）。 完成语义（空间完成）：成功 = 信号已发 + 玩家已传回（WAIT_TRANSIT 每 tick
 * 校验：触发方块区块未加载或距离 > 64 格视为已传回）， 而非「信号已发」即完成——机关无反应时 300 tick
 * 超时后失败结束（结果由机器层决定后续处理）。
 */
public class BlockTriggerTask extends Task {

	private enum State {
		INTERACTING, CONFIRMING, WAIT_TRANSIT
	}

	/** 触发方块坐标 */
	private final BlockPos pos;
	/** 触发类型 */
	private final ReturnTriggerType type;
	private State state = State.INTERACTING;
	/** 交互后等待 GUI 出现的剩余超时（tick），仅 TRAPPED_CHEST 使用 */
	private int timeout = 0;
	/** GUI 是否已被确认出现过（= 服务端已登记 viewer count +1，信号已生效） */
	private boolean guiSeen = false;
	/** LEVER 类型：拉杆已处于打开状态，需「先还原再触发」两段交互（H.7 风险 4） */
	private boolean leverNeedsReset = false;
	/** LEVER 类型：两段交互的第一段（还原）是否已执行 */
	private boolean leverResetDone = false;
	/** LEVER 类型：两段交互间隔剩余 tick（第一段还原后置 2，递减到 0 才执行第二段触发） */
	private int leverResetWait = 0;
	/** WAIT_TRANSIT 已等待传送完成的 tick 数（超时后失败结束） */
	private int transitTicks = 0;
	/** 服务端实际交互距离上限（超距点击被服务端忽略） */
	private static final double MAX_INTERACT_DISTANCE = 4.5;
	/** 触发后等待玩家传回的超时（tick，15s > 任何合理机关反应时间）；超时 → 失败结束 */
	private static final int TRANSIT_TIMEOUT_TICKS = 300;
	/** 玩家距触发方块超过此距离（格）视为已传回（空间完成阈值；标准折跃门装置 ~1000 格） */
	private static final double COMPLETION_DISTANCE = 64.0;

	public BlockTriggerTask(BlockPos pos, ReturnTriggerType type) {
		this.pos = pos;
		this.type = type;
	}

	@Override
	public TaskResult tick(MinecraftClient mc) {
		// 空间完成探测：任意推进状态（INTERACTING/CONFIRMING）中检测到玩家已传回 → 直接进入 WAIT_TRANSIT
		// 收尾（覆盖等待期间被传送的情况）
		if (state != State.WAIT_TRANSIT && isPlayerReturned(mc)) {
			state = State.WAIT_TRANSIT;
			transitTicks = 0;
			return TaskResult.RUNNING;
		}

		// 每个 tick 由当前状态推进一步；各分支方法内部返回 RUNNING 或终态结果（SUCCEEDED / FAILED），switch 表达式无贯穿
		return switch (state) {
			case INTERACTING -> tickInteracting(mc);
			case CONFIRMING -> tickConfirming(mc);
			case WAIT_TRANSIT -> tickWaitTransit(mc);
		};
	}

	// 轻校验（决策 5）：方块存在（非 air）+ 距离在服务端交互范围内，不检查内容/不做物品转移
	private boolean validateTarget(MinecraftClient mc) {
		if (mc.world == null || mc.player == null)
			return false;
		BlockState blockState = mc.world.getBlockState(pos);
		if (blockState.isAir())
			return false;
		return pos.toCenterPos().squaredDistanceTo(mc.player.getPos()) <= MAX_INTERACT_DISTANCE * MAX_INTERACT_DISTANCE;
	}

	// 与指定方块交互（与 ContainerIOTask 相同的交互原语）
	private void interactBlock(MinecraftClient mc) {
		if (mc.player != null && mc.interactionManager != null) {
			mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
					new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
		}
	}

	// STRICT 模式：强制校验方块类型与配置类型一致；弱校验（默认）只做存在性 + 距离
	private boolean isBlockTypeMatched(MinecraftClient mc) {
		BlockState blockState = mc.world.getBlockState(pos);
		return switch (type) {
			case TRAPPED_CHEST -> blockState.getBlock() instanceof TrappedChestBlock;
			case BUTTON -> blockState.getBlock() instanceof ButtonBlock;
			case LEVER -> blockState.getBlock() instanceof LeverBlock;
			// NONE 不会派发本任务，防御性分支
			case NONE -> true;
		};
	}

	// 玩家是否已传回原侧（空间完成判定）：玩家/世界缺失视为完成（防御兜底）；触发方块区块未加载（距返回块超渲染距离）或距触发方块 > 64 格视为已传回
	private boolean isPlayerReturned(MinecraftClient mc) {
		if (mc.player == null || mc.world == null)
			return true;
		// ChunkManager.isChunkLoaded(int,int) 为公开 API（ChunkManager.java:27），经
		// World.getChunkManager() 获取
		if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4))
			return true;
		return pos.toCenterPos().squaredDistanceTo(mc.player.getPos()) > COMPLETION_DISTANCE * COMPLETION_DISTANCE;
	}

	private TaskResult tickInteracting(MinecraftClient mc) {
		// 轻校验失败（方块被拆/距离超限）→ 警告 + 瞬态失败结束（不再间隔重试，结果由机器层决定）
		if (!validateTarget(mc)) {
			AutoTrade.logger.warn("[BlockTrigger] 触发方块 {} 缺失或距离过远 (type={})", pos.toShortString(),
					type.getStringValue());
			return TaskResult.failed(TaskResult.FailReason.TRANSIENT);
		}

		// STRICT 模式类型不符 → 警告 + 配置失败结束（等待玩家修正配置或方块）
		if (Configs.Void.VOID_RETURN_STRICT.getBooleanValue() && !isBlockTypeMatched(mc)) {
			AutoTrade.logger.warn("[BlockTrigger] 触发方块类型与配置不符，STRICT 模式 (pos={}, type={})", pos.toShortString(),
					type.getStringValue());
			return TaskResult.failed(TaskResult.FailReason.CONFIG);
		}

		// LEVER 两段交互间隔：第一段（还原）后等待 2 tick 让服务端处理，期间跳过第一段分支并递减
		if (leverResetWait > 0) {
			leverResetWait--;
			return TaskResult.RUNNING;
		}

		// LEVER 状态翻转（H.6 风险 4）：若拉杆已处于打开状态，直接交互会把它关闭（无信号）——
		// 需先交互一次还原为关闭，再交互一次触发打开；两段交互之间留 2 tick 让服务端处理
		if (type == ReturnTriggerType.LEVER && !leverResetDone) {
			BlockState blockState = mc.world.getBlockState(pos);
			if (blockState.getBlock() instanceof LeverBlock && blockState.get(LeverBlock.POWERED)) {
				leverNeedsReset = true;
				leverResetDone = true;
				interactBlock(mc);
				AutoTrade.logger.info("[BlockTrigger] LEVER 已处于打开状态，先交互还原再触发 (pos={})", pos.toShortString());
				leverResetWait = 2;
				return TaskResult.RUNNING;
			}
		}

		// 第二段交互（LEVER 触发）或普通交互
		boolean wasLeverReset = leverNeedsReset;
		interactBlock(mc);
		leverNeedsReset = false;
		leverResetDone = false;
		AutoTrade.logger.info("[BlockTrigger] INTERACTING (pos={}, type={}, reset={})", pos.toShortString(),
				type.getStringValue(), wasLeverReset ? "lever reset" : "none");

		if (type == ReturnTriggerType.TRAPPED_CHEST) {
			// 开箱后：进入 CONFIRMING 确认 GUI 出现（信号已登记），确认后保持 GUI 打开进入 WAIT_TRANSIT——
			// 信号持续到玩家传回为止（服务端按距离自动关窗），无需固定保持时长
			timeout = Configs.Generic.OPEN_TIMEOUT.getIntegerValue();
			state = State.CONFIRMING;
		} else {
			// BUTTON/LEVER：无 GUI，交互完成后进入 WAIT_TRANSIT 等待玩家传回（信号已发 ≠ 完成，空间完成）
			state = State.WAIT_TRANSIT;
			transitTicks = 0;
		}
		return TaskResult.RUNNING;
	}

	private TaskResult tickConfirming(MinecraftClient mc) {
		// GUI 已打开：确认信号已登记（服务端 viewer count +1）→ 保持 GUI 打开进入 WAIT_TRANSIT，
		// 信号持续到玩家传回（服务端按距离自动关窗），机关反应窗口由此保证，无需固定保持时长
		if (mc.currentScreen instanceof GenericContainerScreen) {
			guiSeen = true;
			state = State.WAIT_TRANSIT;
			transitTicks = 0;
			return TaskResult.RUNNING;
		}

		// GUI 未打开：曾被确认但现已关闭（玩家已被机关传回，GUI 因位置变化失效）→ 尽快收尾，不必等待（空间完成探测的兜底）
		if (guiSeen) {
			state = State.WAIT_TRANSIT;
			transitTicks = 0;
			return TaskResult.RUNNING;
		}

		// 交互后 GUI 未出现：超时窗口内继续等待
		if (timeout > 0) {
			timeout--;
			return TaskResult.RUNNING;
		}

		// 超时兜底：警告 + 瞬态失败结束（不再回 INTERACTING 重试，结果由机器层决定）
		AutoTrade.logger.warn("[BlockTrigger] 陷阱箱窗口未在 {} tick 内打开", Configs.Generic.OPEN_TIMEOUT.getIntegerValue());
		return TaskResult.failed(TaskResult.FailReason.TRANSIENT);
	}

	private TaskResult tickWaitTransit(MinecraftClient mc) {
		// 玩家已传回（区块未加载或距离 > 64 格）→ 空间完成，任务成功结束。
		// 传回后服务端按容器距离检查（8 格）自动关闭陷阱箱窗口（ServerPlayerEntity canUse 检查），无需主动关窗
		if (isPlayerReturned(mc)) {
			AutoTrade.logger.info("[BlockTrigger] 玩家已传回，返回触发成功 (pos={})", pos.toShortString());
			return TaskResult.SUCCEEDED;
		}
		// 机关无反应（玩家一直未离开返回区）→ 失败前主动关闭残留窗口（挡住下一轮交互），再超时瞬态失败结束（不再回
		// INTERACTING 重新触发，结果由机器层决定）
		if (++transitTicks >= TRANSIT_TIMEOUT_TICKS) {
			if (mc.currentScreen instanceof GenericContainerScreen screen) {
				screen.close();
			}
			AutoTrade.logger.warn("[BlockTrigger] 触发后 {} tick 内玩家未离开返回区 (pos={})", TRANSIT_TIMEOUT_TICKS,
					pos.toShortString());
			return TaskResult.failed(TaskResult.FailReason.TRANSIENT);
		}
		return TaskResult.RUNNING;
	}
}