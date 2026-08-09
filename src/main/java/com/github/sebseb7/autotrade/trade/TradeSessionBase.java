package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.util.Hand;

/**
 * 交易会话的 6 状态有限状态机（FSM）： SCANNING（寻找下一村民）→ INTERACTING（右键交互打开交易界面）→
 * WAITING_FOR_SCREEN（等待界面出现）→ TRADING（执行匹配交易）→ CLOSING_SCREEN（关闭交易界面）→ 回到
 * SCANNING 处理下一村民或 COMPLETED 结束。 各模式的差异（选村民策略、冷却、扫描范围、void 延迟）由
 * {@link SessionHooks} 注入。
 */
public class TradeSessionBase extends Operation implements TradeSession {

	public enum State {
		SCANNING, INTERACTING, WAITING_FOR_SCREEN, TRADING, CLOSING_SCREEN, COMPLETED
	}

	private State state = State.SCANNING;
	private final SessionHooks hooks;
	private final MerchantTradeExecutor executor = new MerchantTradeExecutor();
	private int villagerActive = 0;
	private int interactTimeout = 0;
	private int voidDelay = 0;
	private boolean voidTrade = false;
	/** 本次会话运行中实际找到并开始交互的村民数（用于零进度冷却判定） */
	private int villagerInteracted = 0;
	/** 本次会话是否因背包空间不足提前结束（由 executor 在 TRADING 结束时同步） */
	private boolean inventoryBlocked = false;

	public TradeSessionBase(SessionHooks hooks) {
		this.hooks = hooks;
	}

	@Override
	public void tick(MinecraftClient mc) {
		if (done)
			return;

		tickWait();
		if (isWaiting())
			return;

		switch (state) {
			case SCANNING -> tickScanning(mc);
			case INTERACTING -> tickInteracting(mc);
			case WAITING_FOR_SCREEN -> tickWaitingForScreen(mc);
			case TRADING -> tickTrading(mc);
			case CLOSING_SCREEN -> tickClosingScreen(mc);
			case COMPLETED -> tickCompleted(mc);
		}
	}

	private void tickScanning(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		// 由各模式的 SessionHooks 决定下一个目标村民（策略差异：静态按预扫描列表，移动/虚空取最近）
		Entity villager = hooks.findNextVillager(mc);
		if (villager != null) {
			villagerActive = villager.getId();
			villagerInteracted++;
			state = State.INTERACTING;
			AutoTrade.logger.info("[TradeSession] SCANNING → INTERACTING (villager id={})", villagerActive);
		} else {
			AutoTrade.logger.info("[TradeSession] No more villagers, session complete");
			state = State.COMPLETED;
		}
	}

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
		AutoTrade.logger.info(
				"[TradeSession] INTERACTING → WAITING_FOR_SCREEN (villager={}, interactTimeout={}, voidDelay={})",
				tradable ? villagerActive : "gone", interactTimeout, voidDelay);
	}

	// 统一进入等待交易界面状态：重置交互超时、void 延迟，并清空交易执行器冷却
	private void startWaitingForScreen() {
		interactTimeout = Configs.Generic.INTERACT_TIMEOUT.getIntegerValue();
		voidDelay = hooks.useVoidDelay() ? Configs.Generic.VOID_TRADING_DELAY.getIntegerValue() : 0;
		voidTrade = voidDelay > 0;
		executor.clearDefer();
		state = State.WAITING_FOR_SCREEN;
	}

	private void tickWaitingForScreen(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		if (mc.currentScreen instanceof MerchantScreen) {
			state = State.TRADING;
			AutoTrade.logger.info("[TradeSession] WAITING_FOR_SCREEN → TRADING");
			return;
		}

		if (interactTimeout > 0) {
			interactTimeout--;
			return;
		}

		// 交互超时已过，处理 void 延迟（虚空模式下用于等待村民被传送/卸载后的缓冲）
		if (voidDelay > 0) {
			boolean canCount = false;
			// 若配置为「传送后开始计时」：只有村民实体已消失（被卸载/传送走）才开始倒计时
			if (Configs.Generic.VOID_TRADING_DELAY_AFTER_TELEPORT.getBooleanValue()) {
				Entity entity = mc.world.getEntityById(villagerActive);
				canCount = entity == null;
			} else {
				canCount = true;
			}
			if (canCount)
				voidDelay--;
			if (voidDelay > 0)
				return;
		}

		// 交互超时 + void 延迟都已到期，画面仍未打开 → 跳过该村民
		AutoTrade.logger.warn("[TradeSession] Screen never appeared for villager {}, skipping", villagerActive);
		hooks.onVillagerTimeout(villagerActive);
		state = State.SCANNING;
	}

	private void tickTrading(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			done = true;
			return;
		}

		if (!(mc.currentScreen instanceof MerchantScreen screen)) {
			AutoTrade.logger.warn("[TradeSession] 交易窗口意外关闭");
			state = State.COMPLETED;
			return;
		}

		boolean hasMoreWork = executor.handleMerchantScreenTick(mc, screen);
		if (!hasMoreWork) {
			// 同步「背包空间不足」标志：由 executor 判断是正常无匹配还是结果放不下
			inventoryBlocked = executor.isInventoryBlocked();
			state = State.CLOSING_SCREEN;
			AutoTrade.logger.info("[TradeSession] TRADING → CLOSING_SCREEN{}",
					inventoryBlocked ? " (inventory full)" : "");
		}
	}

	private void tickClosingScreen(MinecraftClient mc) {
		if (mc.currentScreen instanceof MerchantScreen screen) {
			screen.close();
		}
		// 背包满导致会话提前结束：不标记村民已处理（保留记录，背包清空后下轮会话重试该村民），直接结束会话
		if (!done && !inventoryBlocked && hooks.onVillagerDone(mc, villagerActive)) {
			state = State.SCANNING;
			AutoTrade.logger.info("[TradeSession] CLOSING_SCREEN → SCANNING (next villager)");
		} else {
			state = State.COMPLETED;
			AutoTrade.logger.info("[TradeSession] CLOSING_SCREEN → COMPLETED");
		}
	}

	private void tickCompleted(MinecraftClient mc) {
		done = true;
	}

	@Override
	public int getSessionCooldown() {
		return hooks.getSessionCooldown();
	}

	@Override
	public int getVillagersInteracted() {
		return villagerInteracted;
	}

	@Override
	public boolean isInventoryBlocked() {
		return inventoryBlocked;
	}

	@Override
	public void clear() {
		state = State.SCANNING;
		villagerActive = 0;
		interactTimeout = 0;
		voidDelay = 0;
		voidTrade = false;
		villagerInteracted = 0;
		inventoryBlocked = false;
		done = false;
		executor.clearDefer();
	}

	@Override
	public void resetForNextVillager() {
		state = State.SCANNING;
		villagerActive = 0;
		interactTimeout = 0;
		voidDelay = 0;
		villagerInteracted = 0;
		inventoryBlocked = false;
		done = false;
		executor.clearDefer();
	}
}
