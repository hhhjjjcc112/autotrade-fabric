package com.github.sebseb7.autotrade.trade.mode.voidmode;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.ReturnTriggerType;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOList;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine;
import com.github.sebseb7.autotrade.trade.task.BlockTriggerTask;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

/**
 * VOID 模式：优先容器 IO，其次「会话完成后的返回触发」（把玩家传回原侧），最后取范围内第一个村民（findNearby
 * 首个）通过构造器锁定派发单村民会话。 单村民语义：无处理记录、无标记、无冷却——交易完成后下轮自然再次选中同一村民，形成无限交易循环；
 * 交易前的等待延迟（配合玩家传送/村民卸载）由 VoidTradeTask 处理。 返回触发（空间完成调度）：不保留机器层「待返回」标志， tickIdle
 * 以纯空间条件派发——返回块可达（区块加载且距玩家 ≤4.5 格 ⇔ 玩家在岛侧）即派发「交互返回机关」任务，不可达
 * （玩家在原侧）则落入找村民；BlockTriggerTask 以「玩家已传回」为完成（WAIT_TRANSIT 空间完成），transit 窗口
 * （触发成功→传送完成）由任务持有运行位覆盖，机器层无需记忆。
 */
public class VoidTradeMachine extends AbstractTradeMachine {

	/** 返回触发坐标与 IO 容器坐标互斥校验结果缓存（null = 尚未校验；校验一次后避免每 tick 重复解析 ItemIOList，决策 4） */
	private Boolean returnTriggerConflict = null;

	public VoidTradeMachine() {
		super();
	}

	@Override
	protected void tickIdle(MinecraftClient mc) {
		// 背包满暂停：期间只做输出优先的容器 IO，不启动交易会话
		if (tickInventoryPause(mc))
			return;

		// 优先容器 IO（先卸货/补货再返回，否则岛侧容器被「传回原侧」永久饿死，决策 2）
		if (ContainerIOHelper.startContainerIO(mc, this::setTaskIfEmpty))
			return;

		// 返回触发：已配置时先做交接与可达性判定（空间相位：玩家在岛侧 ⇔ 返回块可达），优先级高于找村民（决策 2）
		if (isReturnTriggerConfigured()) {
			// H.6 风险 3：当前 screen 必须已关闭（null）才能开箱，否则服务端会先 close 旧 handler
			if (mc.currentScreen != null)
				return;
			BlockPos pos = parseReturnPos();
			if (isReturnTriggerUsable() && isReturnBlockReachable(mc)) {
				ReturnTriggerType type = (ReturnTriggerType) Configs.Void.VOID_RETURN_TYPE.getOptionListValue();
				setTaskIfEmpty(new BlockTriggerTask(pos, type));
				AutoTrade.logger.info("[VoidMode] IDLE → RETURN_TRIGGER (pos={}, type={})", pos.toShortString(),
						type.getStringValue());
				return;
			}
			// 不可达（玩家在原侧）或不可用（冲突/坐标非法）→ 落入下方找村民
		}

		// 取范围内第一个村民/流浪商人（单村民语义：无需区分是否已处理，交易完成后下轮自然重选；
		// 无零进度冷却——启动条件本身保证「启动即有村民」，零进度仅剩 1-tick 竞态且不产生忙循环）
		double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		for (Entity e : VillagerHelper.findNearby(mc, range)) {
			setTaskIfEmpty(new VoidTradeTask(e.getId()));
			AutoTrade.logger.info("[VoidMode] IDLE → TRADE_SESSION (villager id={})", e.getId());
			return;
		}
	}

	/** 返回触发是否已配置（TYPE ≠ NONE 且坐标解析成功且非 0 哨兵值） */
	private boolean isReturnTriggerConfigured() {
		if (Configs.Void.VOID_RETURN_TYPE.getOptionListValue() == ReturnTriggerType.NONE)
			return false;
		BlockPos pos = parseReturnPos();
		return pos != null && !pos.equals(BlockPos.ORIGIN);
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
	 * 同谓词（空间相位：玩家在岛侧 ⇔ 可达）
	 */
	private boolean isReturnBlockReachable(MinecraftClient mc) {
		if (mc.world == null || mc.player == null)
			return false;
		BlockPos pos = parseReturnPos();
		if (pos == null)
			return false;
		if (mc.world.getBlockState(pos).isAir())
			return false; // 未加载区块亦返回 air → 玩家在原侧时恒 false
		return pos.toCenterPos().squaredDistanceTo(mc.player.getPos()) <= 4.5 * 4.5;
	}

	// 遍历全部 ItemIO 条目，检查触发坐标是否与任一条目容器坐标重叠（决策 4；空列表 = 无冲突）
	private boolean hasTriggerPosConflict() {
		BlockPos pos = parseReturnPos();
		// 坐标非法时视为不可用（true），避免以 (0,0,0) 参与冲突判断
		if (pos == null)
			return true;
		for (ItemIO io : ItemIOList.fromJson(Configs.Generic.ITEM_IO.getStringValue())) {
			if (io.getX() == pos.getX() && io.getY() == pos.getY() && io.getZ() == pos.getZ())
				return true;
		}
		return false;
	}

	@Override
	public void reset() {
		returnTriggerConflict = null;
		super.reset();
	}
}
