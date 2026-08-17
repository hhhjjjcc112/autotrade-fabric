package com.github.sebseb7.autotrade.trade.task;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.helper.VillagerInteractHelper;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.util.Hand;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * 交易会话抽象基类：单村民直链有限状态机（FSM）。 目标村民由机器层选中后通过构造器注入（{@link #TradeTask(int)}）， 会话随后沿
 * INTERACTING（右键交互）→ WAITING_FOR_SCREEN（等待界面出现）→ TRADING（执行匹配交易）→
 * CLOSING_SCREEN（关闭界面）→ 完成 的直链推进，处理完单个村民即结束（done），由机器层决定下一目标或进入冷却。 各模式的差异（void
 * 延迟策略）由子类覆写的抽象策略方法提供；标记/冷却节奏由机器层 onTaskDone 统一处理。
 */
public abstract class TradeTask extends Task {

	public enum State {
		INTERACTING, WAITING_FOR_SCREEN, TRADING, CLOSING_SCREEN, COMPLETED
	}

	/** 当前目标村民实体 id（由机器层选中后通过构造器锁定传入，本会话不再自行扫描重选） */
	private final int villagerActive;
	private State state = State.INTERACTING;
	private final TradeExecutor executor = new TradeExecutor();
	private int interactTimeout = 0;
	/** VOID 模式标记（由 useVoidDelay 决定）：窗口打开后须等村民消失（玩家传送完成）才交易 */
	private boolean voidTrade = false;
	/** VOID 模式：窗口已开后等待村民消失的剩余超时（tick），到期仍未消失则跳过该村民 */
	private int teleportTimeout = 0;
	/** VOID 模式：村民消失后、开始交易前的卸载缓冲剩余 tick（覆盖服务端区块异步卸载窗口，0 = 立即交易） */
	private int unloadDelay = 0;
	/** 本次会话是否因背包空间不足提前结束（由 executor 在 TRADING 结束时同步） */
	private boolean inventoryBlocked = false;

	/** 锁定本会话要处理的目标村民（由机器层在派发前调用，修复「机器层评分选 A、会话内部重扫可能取到 B」的竞态） */
	public TradeTask(int villagerActiveId) {
		this.villagerActive = villagerActiveId;
	}

	@Override
	public void tick(MinecraftClient mc) {
		if (done)
			return;

		tickWait();
		if (isWaiting())
			return;

		switch (state) {
			case INTERACTING -> tickInteracting(mc);
			case WAITING_FOR_SCREEN -> tickWaitingForScreen(mc);
			case TRADING -> tickTrading(mc);
			case CLOSING_SCREEN -> tickClosingScreen(mc);
			case COMPLETED -> tickCompleted(mc);
		}
	}

	// ---- 抽象策略方法（各模式子类覆写，差异注入点） ----

	/** 是否启用 VOID 专属等待（窗口已开后须等村民消失再交易；仅 VOID 模式返回 true） */
	protected abstract boolean useVoidDelay();

	private void tickInteracting(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		Entity entity = mc.world.getEntityById(villagerActive);
		boolean tradable = entity instanceof VillagerEntity || entity instanceof WanderingTraderEntity;
		if (tradable) {
			// 看向村民并右键交互，触发交易界面打开
			VillagerInteractHelper.lookAtEntity(mc, entity);
			if (mc.interactionManager != null) {
				mc.interactionManager.interactEntity(mc.player, entity, Hand.MAIN_HAND);
			}
		}

		startWaitingForScreen();
		AutoTrade.logger.info("[TradeTask] INTERACTING → WAITING_FOR_SCREEN (villager={}, interactTimeout={})",
				tradable ? villagerActive : "gone", interactTimeout);
	}

	// 统一进入等待交易界面状态：重置交互超时、void 延迟与传送超时
	private void startWaitingForScreen() {
		interactTimeout = Configs.Generic.OPEN_TIMEOUT.getIntegerValue();
		// voidTrade 启用 VOID 专属等待（窗口已开后等村民消失）；teleportTimeout 为其兜底；unloadDelay 为消失后的卸载缓冲
		voidTrade = useVoidDelay();
		teleportTimeout = voidTrade ? Configs.Void.VOID_TELEPORT_TIMEOUT.getIntegerValue() : 0;
		unloadDelay = voidTrade ? Configs.Void.VOID_UNLOAD_DELAY.getIntegerValue() : 0;
		state = State.WAITING_FOR_SCREEN;
	}

	private void tickWaitingForScreen(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		if (mc.currentScreen instanceof MerchantScreen screen) {
			if (!voidTrade) {
				// 非 VOID（STATIC/MOVING）：窗口出现即交易
				state = State.TRADING;
				AutoTrade.logger.info("[TradeTask] WAITING_FOR_SCREEN → TRADING");
				return;
			}

			// VOID：窗口已开但须等玩家传送完成（村民实体消失 = 服务端已处理传送）再交易；
			// 村民尚未卸载时交易，次数会被正常同步，无限交易失效
			Entity entity = mc.world.getEntityById(villagerActive);
			if (entity == null) {
				// 村民已消失（= 服务端已处理玩家传送）→ 先递减卸载缓冲（覆盖服务端区块异步卸载窗口，防次数持久化），
				// 缓冲为 0 时立即交易（行为见 TRADE_MODES.md §二）
				if (unloadDelay > 0) {
					unloadDelay--;
					return;
				}
				// 耗尽检测告警：此时 offers 仍是开窗时服务端同步真值（尚未被本地点击模拟污染）——
				// 健康虚空装置中村民每次重载 uses 应为 0，任何 uses > 0 都是交易被持久化到村民的痕迹
				if (hasExhaustedTradeEvidence(screen)) {
					AutoTrade.logger.warn("[VoidMode] 村民交易已耗尽：卸载缓冲不足或装置区块被持续加载，无限交易失效（请调大 voidUnloadDelay 或检查装置位置）");
					InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.void.exhausted");
				}
				state = State.TRADING;
				AutoTrade.logger.info("[TradeTask] WAITING_FOR_SCREEN → TRADING (villager unloaded)");
			} else {
				// 村民一直未消失（装置断供/传送故障）→ 超时兜底：关窗跳过该村民，结束会话
				if (teleportTimeout > 0) {
					teleportTimeout--;
					return;
				}
				screen.close();
				AutoTrade.logger.warn("[TradeTask] Villager never teleported for {}, skipping", villagerActive);
				state = State.COMPLETED;
			}
			return;
		}

		// 窗口未开：交互超时后跳过该村民，结束会话
		if (interactTimeout > 0) {
			interactTimeout--;
			return;
		}

		AutoTrade.logger.warn("[TradeTask] Screen never appeared for villager {}, skipping", villagerActive);
		state = State.COMPLETED;
	}

	// VOID 耗尽证据：开窗 offers 快照（服务端同步真值）中任一 offer uses > 0。
	// 健康虚空装置中村民每次重载 uses 应为 0，任何 uses > 0 都是交易次数被持久化到村民的痕迹
	private boolean hasExhaustedTradeEvidence(MerchantScreen screen) {
		TradeOfferList offers = screen.getScreenHandler().getRecipes();
		if (offers == null || offers.isEmpty())
			return false;
		for (TradeOffer offer : offers) {
			if (offer.getUses() > 0)
				return true;
		}
		return false;
	}

	private void tickTrading(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		if (!(mc.currentScreen instanceof MerchantScreen screen)) {
			AutoTrade.logger.warn("[TradeTask] 交易窗口意外关闭");
			state = State.COMPLETED;
			return;
		}

		boolean hasMoreWork = executor.handleMerchantScreenTick(mc, screen);
		if (!hasMoreWork) {
			// 同步「背包空间不足」标志：由 executor 判断是正常无匹配还是结果放不下
			inventoryBlocked = executor.isInventoryBlocked();
			state = State.CLOSING_SCREEN;
			AutoTrade.logger.info("[TradeTask] TRADING → CLOSING_SCREEN{}",
					inventoryBlocked ? " (inventory full)" : "");
		}
	}

	private void tickClosingScreen(MinecraftClient mc) {
		if (mc.currentScreen instanceof MerchantScreen screen) {
			screen.close();
		}
		// 单村民直链下完成后无条件结束会话，机器层 onTaskDone 负责标记/冷却决策（背包满时不标记，下轮重试该村民）
		state = State.COMPLETED;
		AutoTrade.logger.info("[TradeTask] CLOSING_SCREEN → COMPLETED");
	}

	private void tickCompleted(MinecraftClient mc) {
		done = true;
	}

	/** 本次会话是否因背包空间不足提前结束（机器层据此暂停交易并优先容器 IO） */
	public boolean isInventoryBlocked() {
		return inventoryBlocked;
	}
}
