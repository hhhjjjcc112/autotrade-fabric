package com.github.sebseb7.autotrade.trade.mode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.machine.IdleReason;
import com.github.sebseb7.autotrade.trade.task.BlockTriggerTask;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TradeTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * VOID 模式：优先容器 IO，其次「会话完成后的返回触发」（把玩家传回原侧），最后取范围内第一个村民（findNearby
 * 首个）通过构造器锁定派发单村民会话。 单村民语义：无处理记录、无标记、无冷却——交易完成后下轮自然再次选中同一村民，形成无限交易循环；
 * 交易前的等待延迟（配合玩家传送/村民卸载）由 VoidTradeTask 处理。 返回触发（空间完成调度）：不保留机器层「待返回」标志， tickIdle
 * 以纯空间条件派发——返回块可达（区块加载且距玩家 ≤4.5 格 ⇔ 玩家在岛侧）即派发「交互返回机关」任务，不可达
 * （玩家在原侧）则落入找村民；BlockTriggerTask 以「玩家已传回」为完成（WAIT_TRANSIT 空间完成），transit 窗口
 * （触发成功→传送完成）由任务持有运行位覆盖，机器层无需记忆。
 *
 * <p>
 * 返回触发节流与门控：瞬态类失败（SCREEN_TIMEOUT/TRANSIT_TIMEOUT/看门狗强杀）后 100t 内不重派； STRICT
 * 开启时派发前做方块类型门控（类型不符不派发、不落村民，等待玩家修正后自动恢复），告警走状态边沿； 失败村民 100t 重试冷却（逐
 * UUID，跳过后尝试下一村民；看门狗强杀同样标记）。
 */
public class VoidTradeMachine extends AbstractTradeMachine {

	/** 返回触发坐标与 IO 容器坐标互斥校验结果缓存（null = 尚未校验；校验一次后避免每 tick 重复解析 ItemIOList，决策 4） */
	private Boolean returnTriggerConflict = null;

	/**
	 * 非法维度警告已提示标志（一次性防刷屏，同 MOVING hintedKeys 惯例；isReturnTriggerConfigured 每
	 * tick/每帧被调用，不设防会无限堆积消息）
	 */
	private static boolean invalidDimensionWarned = false;

	/** 返回触发失败后的重试节流时长：100 tick = 5 秒（瞬态类失败/看门狗强杀后显式等待，避免忙循环） */
	private static final int RETURN_TRIGGER_RETRY_TICKS = 100;

	/**
	 * 失败村民的重试冷却时长：100 tick = 5 秒；防「失败 → 下一 tick 立即重选同一村民」忙循环，并让选择循环有机会尝试其它村民。
	 */
	private static final int VILLAGER_FAIL_RETRY_TICKS = 100;

	/** 返回触发下次可派发的世界时间戳（世界时间基准；当前时间 < 该值 = 节流中，不重派） */
	private long returnTriggerRetryAtTick = 0;

	/** 失败村民重试冷却表（村民 UUID → 冷却截止世界 tick）；选择循环跳过冷却中的村民，到期惰性清除；reset 清空。 */
	private final Map<UUID, Long> villagerRetryAt = new HashMap<>();

	public VoidTradeMachine() {
		super();
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 优先容器 IO（先卸货/补货再返回，否则岛侧容器被「传回原侧」永久饿死，决策 2）
		if (containerIOScheduler.startNearest(mc, this::setTaskIfEmpty))
			return;

		// 空闲原因尾部判定的局部标志：本 tick 未派发任何任务时，方法尾按优先级落原因（回程触发原因优先）
		// 单次调用 isReturnTriggerConfigured()（避免尾部重复调用；该访问器有一次性的非法维度告警副作用）
		boolean returnTriggerConfigured = isReturnTriggerConfigured();
		boolean returnTriggerWait = false;
		boolean sawCacheSkip = false;
		boolean sawVillagerRetry = false;

		// 返回触发：已配置时先做交接与可达性判定（空间相位：玩家在岛侧 ⇔ 返回块可达），优先级高于找村民（决策 2）
		if (returnTriggerConfigured) {
			// H.6 风险 3：当前 screen 必须已关闭（null）才能开箱，否则服务端会先 close 旧 handler
			if (mc.currentScreen != null) {
				// 屏幕未关闭（交易/容器界面仍开着）→ 空闲原因为「回程等待」
				setIdleReason(IdleReason.RETURN_TRIGGER_WAIT);
				return;
			}
			BlockPos pos = parseReturnPos();
			if (isReturnTriggerUsable()) {
				// 单次读取：可达性判定与 STRICT 类型门控共用同一 BlockState（无重复读取）
				BlockState state = mc.world.getBlockState(pos);
				if (isReturnBlockReachable(mc, pos, state)) {
					ReturnTriggerType type = (ReturnTriggerType) Configs.Void.VOID_RETURN_TYPE.getOptionListValue();
					if (Configs.Void.VOID_RETURN_STRICT.getBooleanValue()
							&& !BlockTriggerTask.matchesBlockType(type, state)) {
						// STRICT 门控：类型不符 → 不派发、不落村民（零失败循环），等待玩家修正；状态边沿告警
						showFaultAlert(AlertType.RETURN_TRIGGER_STRICT);
						setIdleReason(IdleReason.RETURN_TRIGGER_WAIT);
						return;
					}
					clearFaultAlert(AlertType.RETURN_TRIGGER_STRICT);
					// 显式重试节流：瞬态类失败/看门狗强杀后 100t 内不重派（冷却期同样 return，避免空转）
					boolean triggerStarted = false;
					if (mc.world.getTime() >= returnTriggerRetryAtTick) {
						triggerStarted = setTaskIfEmpty(new BlockTriggerTask(pos, type));
						AutoTrade.logger.info("[VoidMode] IDLE → RETURN_TRIGGER (pos={}, type={})", pos.toShortString(),
								type.getStringValue());
					}
					if (!triggerStarted) {
						// 节流中未派发任务 → 空闲原因为「回程等待」；派发成功则保持 BUSY（由 getter 派生）
						setIdleReason(IdleReason.RETURN_TRIGGER_WAIT);
					}
					return;
				}
			}
			// 不可达（玩家在原侧）或不可用（冲突/坐标非法）→ 落入下方找村民；
			// 本 tick 未派发时尾部落 RETURN_TRIGGER_WAIT（配置正常但本次等待移交）
			returnTriggerWait = true;
		}

		// 取范围内第一个村民/流浪商人（单村民语义：无需区分是否已处理，交易完成后下轮自然重选；
		// 无零进度冷却——启动条件本身保证「启动即有村民」，零进度仅剩 1-tick 竞态且不产生忙循环）
		double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			// 9.8 缓存：TTL 内已知不匹配 → 跳过取下一村民（全不匹配时自然落空，不产生忙循环）
			// 流浪商人说明：findNearby 含流浪商人——无匹配交易的商人学到不匹配（TTL）是正确的（其交易终身固定）；
			// 已命中的商人若消失仅留下无害的死条目（UUID 永不复用），不做特殊处理
			if (isCachedMiss(e.getUuid(), mc.world.getTime())) {
				sawCacheSkip = true;
				continue;
			}
			if (isVillagerOnRetryCooldown(e.getUuid(), mc.world.getTime())) {
				sawVillagerRetry = true;
				continue;
			}
			setTaskIfEmpty(new VoidTradeTask(e.getUuid()));
			AutoTrade.logger.info("[VoidMode] IDLE → TRADE_SESSION (villager id={})", e.getUuid());
			return;
		}

		// 尾部：村民循环未派发任何任务 → 按严格优先级落空闲原因并显式 return（回程触发原因优先：
		// VOID 下缺回程/回程不可用是硬阻断根因，优先于村民侧原因展示）
		if (!returnTriggerConfigured) {
			setIdleReason(IdleReason.RETURN_TRIGGER_NOT_CONFIGURED);
		} else if (returnTriggerWait) {
			setIdleReason(IdleReason.RETURN_TRIGGER_WAIT);
		} else if (sawVillagerRetry) {
			setIdleReason(IdleReason.VILLAGER_RETRY);
		} else if (sawCacheSkip) {
			setIdleReason(IdleReason.CACHE_SKIP);
		} else {
			setIdleReason(IdleReason.NO_VILLAGER);
		}
	}

	/** 返回触发是否已配置（TYPE ≠ NONE 且坐标解析成功且非 0 哨兵值；维度空串 = 任意维度不过滤，非空且非法视为未配置） */
	public boolean isReturnTriggerConfigured() {
		if (Configs.Void.VOID_RETURN_TYPE.getOptionListValue() == ReturnTriggerType.NONE)
			return false;
		// 维度配置：空串 = 任意维度（不过滤、不视为未配置）；非空且非法 → 视为未配置 + 一次性警告（防刷屏，同 MOVING hintedKeys 惯例）
		String dim = Configs.Void.VOID_RETURN_DIM.getStringValue();
		if (!dim.isEmpty() && !isValidDimension(dim)) {
			if (!invalidDimensionWarned) {
				invalidDimensionWarned = true;
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
						"autotrade.message.void.invalid_dimension");
			}
			return false;
		}
		BlockPos pos = parseReturnPos();
		return pos != null && !pos.equals(BlockPos.ORIGIN);
	}

	/** 维度 registry id 是否合法（非空且可被 Identifier 解析） */
	private static boolean isValidDimension(String dim) {
		return dim != null && !dim.isBlank() && Identifier.tryParse(dim) != null;
	}

	/**
	 * 解析「x y z」格式的回程触发方块坐标字符串，委托 ConfigCoordinate.parse（Long 解析 + 钳制 ±30000000）；
	 * 格式非法或段数不符返回 null（视为未配置）。边界说明：超出 int 范围的长数字串（如 "99999999999 0 0"） 旧实现按 int
	 * 解析失败返回 null，现改为钳制到 ±30000000 —— 正常 GUI 输入（POSITION_PATTERN）不会产生该值，仅文档化边界。
	 */
	private static BlockPos parseReturnPos() {
		return Configs.Void.VOID_RETURN_POS.toBlockPos();
	}

	/** 返回触发是否可用（决策 4 互斥校验，结果缓存避免每 tick 重复解析 ItemIOList） */
	private boolean isReturnTriggerUsable() {
		if (returnTriggerConflict == null) {
			returnTriggerConflict = hasTriggerPosConflict();
			if (returnTriggerConflict) {
				AutoTrade.logger.warn("[VoidMode] VOID_RETURN 触发坐标与 IO 容器坐标重叠，返回触发未启用（请更换触发方块或容器坐标）");
				// 弹窗提示用户返回触发坐标与容器坐标冲突
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.void.return_overlap");
			}
		}
		return !returnTriggerConflict;
	}

	/**
	 * 返回块当前可达（区块加载且距玩家 ≤ 交互距离 4.5 格）——与 BlockTriggerTask.validateTarget
	 * 同谓词（空间相位：玩家在岛侧 ⇔ 可达）；BlockState 由调用方单次读取后传入（可达性判定与 STRICT 类型门控共用同一状态，避免重复读取）
	 *
	 * @param mc
	 *            Minecraft 客户端实例
	 * @param pos
	 *            返回触发方块坐标（调用方解析）
	 * @param state
	 *            该坐标的方块状态（调用方读取）
	 * @return true = 可达（玩家在岛侧）
	 */
	private boolean isReturnBlockReachable(MinecraftClient mc, BlockPos pos, BlockState state) {
		if (mc.world == null || mc.player == null)
			return false;
		// 维度过滤：VOID_RETURN_DIM 非空时玩家必须处于该维度才判定可达（空串 = 任意维度，跳过该过滤）
		String dim = Configs.Void.VOID_RETURN_DIM.getStringValue();
		if (!dim.isEmpty() && !dim.equals(mc.world.getRegistryKey().getValue().toString()))
			return false;
		if (pos == null)
			return false;
		if (state.isAir())
			return false; // 未加载区块亦返回 air → 玩家在原侧时恒 false
		return pos.toCenterPos().squaredDistanceTo(mc.player.getPos()) <= 4.5 * 4.5;
	}

	// 遍历全部 ItemIO 条目的位置记录，检查触发坐标是否与任一条启用记录容器坐标重叠（决策 4；空列表 = 无冲突）。
	// 按维判定：仅统计启用记录（行级 io.isEnabled() 且记录级 loc.isEnabled()——与 trade/io/ 的
	// ContainerFilters 共用谓词语义一致，但该类包私有跨包不可引用，此处保留独立判定路径）；
	// 同坐标 且（记录维度为空 = 任意维度，或与回程维度相同；回程维度空串时视为与任意记录同维）→ 冲突
	private boolean hasTriggerPosConflict() {
		BlockPos pos = parseReturnPos();
		// 坐标非法时视为不可用（true），避免以 (0,0,0) 参与冲突判断
		if (pos == null)
			return true;
		String returnDim = Configs.Void.VOID_RETURN_DIM.getStringValue();
		// 缓存访问器：仅遍历读取坐标，不改动条目
		for (ItemIO io : ItemIOCache.getAll()) {
			for (ItemIOLocation loc : io.getLocations()) {
				// 行级/记录级启用合取：禁用即「我确认不需要这个容器」，禁用后冲突提示消失
				if (!io.isEnabled() || !loc.isEnabled())
					continue;
				// 同坐标 + 按维关系命中（三方维度关系见 isTriggerPosOverlap）→ 冲突
				if (isTriggerPosOverlap(loc, pos, returnDim))
					return true;
			}
		}
		return false;
	}

	/**
	 * 位置记录与回程触发坐标是否重叠：同坐标 且（记录维度空 = 任意维度，或与回程维度相同；回程维度空串时视为与任意记录同维）。 注意：这是回程触发坐标与
	 * IO 容器记录间的三方维度关系（记录维度 vs 回程维度，任一方空串 = 不设限）， 与扫描的「记录维度 vs
	 * 玩家当前维度」两方过滤语义不同——本助手仅供冲突判定，禁止并入共享过滤谓词。
	 */
	private static boolean isTriggerPosOverlap(ItemIOLocation loc, BlockPos pos, String returnDim) {
		return loc.getX() == pos.getX() && loc.getY() == pos.getY() && loc.getZ() == pos.getZ()
				&& (loc.getDimension().isEmpty() || returnDim.isEmpty() || loc.getDimension().equals(returnDim));
	}

	/**
	 * 任务结束回调（在基类 10 值矩阵之上叠加 VOID 差异）：返回触发细分原因 100t 重试节流（含竞态 CONFIG_INVALID
	 * 告警兜底）与失败村民 100t 重试冷却（背包满暂停除外）；末尾必须委托 super（背包满暂停/告警解除/统计/日志均在基类矩阵）。
	 */
	@Override
	protected void onTaskEnded(Task task, TaskResult result) {
		if (task instanceof BlockTriggerTask) {
			if (result.isFailed()) {
				// TARGET_INVALID / SCREEN_TIMEOUT / TRANSIT_TIMEOUT / 竞态 CONFIG_INVALID：显式 100t
				// 重试节流
				returnTriggerRetryAtTick = lastWorldTime + RETURN_TRIGGER_RETRY_TICKS;
				if (result.reason() == TaskResult.FailReason.CONFIG_INVALID) {
					// 派发前门控已挡主要路径；此处为门控与任务内 STRICT 检查之间的竞态兜底（同一状态边沿告警）
					showFaultAlert(AlertType.RETURN_TRIGGER_STRICT);
				}
			} else if (result.isSucceeded()) {
				clearFaultAlert(AlertType.RETURN_TRIGGER_STRICT);
			}
		}
		if (task instanceof TradeTask ts && result.isFailed()
				&& result.reason() != TaskResult.FailReason.INVENTORY_BLOCKED) {
			// 失败村民逐 UUID 100t 重试冷却（背包满暂停已自带节奏，不叠加）
			villagerRetryAt.put(ts.getVillagerUuid(), lastWorldTime + VILLAGER_FAIL_RETRY_TICKS);
		}
		super.onTaskEnded(task, result);
	}

	/**
	 * 任务被看门狗强杀回调：返回触发无结果可判（强杀时任务状态不可信），统一按瞬态类失败处理——100t 内不重派（防忙循环）；
	 * 村民任务同样无结果可判，统一进入 100t 重试冷却。
	 */
	@Override
	protected void onTaskInterrupted(Task task) {
		if (task instanceof BlockTriggerTask) {
			returnTriggerRetryAtTick = lastWorldTime + RETURN_TRIGGER_RETRY_TICKS;
		}
		if (task instanceof TradeTask ts) {
			// 看门狗强杀 = 异常会话，同样进入重试冷却（无结果可判，统一冷却）
			villagerRetryAt.put(ts.getVillagerUuid(), lastWorldTime + VILLAGER_FAIL_RETRY_TICKS);
		}
		super.onTaskInterrupted(task);
	}

	/** 村民是否处于失败重试冷却（选择循环跳过；到期惰性清除） */
	private boolean isVillagerOnRetryCooldown(UUID uuid, long nowTick) {
		Long until = villagerRetryAt.get(uuid);
		if (until == null) {
			return false;
		}
		if (nowTick >= until) {
			villagerRetryAt.remove(uuid);
			return false;
		}
		return true;
	}

	@Override
	public void reset() {
		returnTriggerConflict = null;
		returnTriggerRetryAtTick = 0;
		villagerRetryAt.clear();
		super.reset();
	}
}
