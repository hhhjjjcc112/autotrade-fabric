package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.AutoTrade;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;

/**
 * 交易模式机器的公共基类：封装「当前任务（交易会话/容器 IO）的生命周期管理」。 三种模式（STATIC/MOVING/VOID）只需实现
 * {@link #tickIdle(MinecraftClient)} 与可选的
 * {@link #onTaskDone()}，即可复用任务切换、状态命名与重置逻辑。
 */
public abstract class AbstractModeMachine implements TradingModeMachine {

	protected Object currentTask;
	protected final TradeSession session;

	/** 背包满暂停交易的退避冷却（tick）；>0 期间不启动交易会话，只尝试输出优先的容器 IO */
	private int inventoryPauseCooldown = 0;
	/** 背包满后的暂停时长：100 tick = 5 秒，到期后重新探测背包空间 */
	private static final int INVENTORY_PAUSE_TICKS = 100;

	protected AbstractModeMachine(TradeSession session) {
		this.session = session;
	}

	@Override
	public void tick(MinecraftClient mc) {
		// 玩家/世界可能为空（退出世界等），此时不执行任何任务
		if (mc.player == null || mc.world == null) {
			return;
		}

		// 当前任务完成 → 回调并清空，准备选择下一个任务
		if (currentTask != null) {
			boolean done = (currentTask instanceof TradeSession ts && ts.isDone())
					|| (currentTask instanceof ContainerIOOperation op && op.isDone());
			if (done) {
				onTaskDone();
				currentTask = null;
			}
		}

		if (currentTask != null) {
			// 继续推进当前任务
			if (currentTask instanceof TradeSession ts) {
				ts.tick(mc);
			} else if (currentTask instanceof ContainerIOOperation op) {
				op.tick(mc);
			}
			return;
		}

		// 空闲：由子类决定下一个任务
		tickIdle(mc);
	}

	/**
	 * 当前任务完成后的回调。默认将交易会话重置为「下一村民」； STATIC 模式覆写为设置交易/容器 IO 冷却。
	 * 基类统一在此同步「背包满暂停」状态：会话因背包满提前结束 → 暂停交易 + 游戏内提示； 输出容器 IO 完成（背包空间释放）→ 解除暂停。
	 */
	protected void onTaskDone() {
		if (currentTask instanceof TradeSession ts) {
			if (ts.isInventoryBlocked()) {
				inventoryPauseCooldown = INVENTORY_PAUSE_TICKS;
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.inventory.full");
			}
			session.resetForNextVillager();
		} else if (currentTask instanceof ContainerIOOperation op && !op.isInputOp() && inventoryPauseCooldown > 0) {
			// 输出 IO 把产出物品运走后背包应有空间 → 立即恢复交易探测
			AutoTrade.logger.info("[ModeMachine] Output container IO done, inventory pause released");
			inventoryPauseCooldown = 0;
		}
	}

	/**
	 * 背包满暂停逻辑（子类在 tickIdle 开头调用）：暂停期间不启动交易会话，只尝试「输出优先」的容器 IO （释放背包空间）；返回 true 表示本
	 * tick 已被暂停逻辑消费，调用方应直接 return。
	 */
	protected boolean tickInventoryPause(MinecraftClient mc) {
		if (inventoryPauseCooldown <= 0)
			return false;

		// 暂停期间每 tick 尝试输出优先容器 IO（本地零成本检查，无 IO 需求时不发包）
		if (ContainerIOHelper.startOutputFirstContainerIO(mc, op -> currentTask = op))
			return true;

		inventoryPauseCooldown--;
		return true;
	}

	/** 空闲状态下选择下一个任务的逻辑（子类实现） */
	protected abstract void tickIdle(MinecraftClient mc);

	@Override
	public void reset() {
		currentTask = null;
		session.clear();
		inventoryPauseCooldown = 0;
	}

	@Override
	public String getStateName() {
		if (currentTask == null)
			return "IDLE";
		if (currentTask instanceof TradeSession)
			return "TRADE_SESSION";
		return "CONTAINER_IO";
	}
}
