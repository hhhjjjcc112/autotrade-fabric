package com.github.sebseb7.autotrade.runtime;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.VillagerTradeCache;
import com.github.sebseb7.autotrade.trade.machine.TradingMachine;
import com.github.sebseb7.autotrade.trade.mode.MovingTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.StaticTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.TradeMode;
import com.github.sebseb7.autotrade.trade.mode.VoidTradeMachine;
import com.github.sebseb7.autotrade.trade.stats.TradeStats;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

public class AutoTradeClientTick implements IClientTickHandler {

	private static final AutoTradeClientTick INSTANCE = new AutoTradeClientTick();
	private final Map<TradeMode, TradingMachine> machines = new EnumMap<>(TradeMode.class);
	// 上次 tick 所属世界：跨世界村民 UUID 无关联，世界切换即清缓存防误跳过
	private ClientWorld lastWorld;
	private AutoTradeClientTick() {
		machines.put(TradeMode.STATIC, new StaticTradeMachine());
		machines.put(TradeMode.MOVING, new MovingTradeMachine());
		machines.put(TradeMode.VOID, new VoidTradeMachine());
	}

	public static AutoTradeClientTick getInstance() {
		return INSTANCE;
	}

	public void reset() {
		for (TradingMachine m : machines.values()) {
			m.reset();
		}
		// 统计与机器状态同生命周期：仅热键 toggle-ON 触发 reset，设置页勾选启用不清零
		TradeStats.getInstance().reset();
		// 开关重置时清交易缓存：旧会话学到的“无匹配”结论不再可信
		VillagerTradeCache.clear();
	}

	@Override
	public void onClientTick(MinecraftClient mc) {
		// 世界切换守卫：须在 player/world 空检查之前，避免断开连接（world=null）时漏清
		if (mc.world != lastWorld) {
			VillagerTradeCache.clear();
			lastWorld = mc.world;
		}
		if (mc.player == null || mc.world == null) {
			return;
		}

		if (!Configs.Generic.ENABLED.getBooleanValue()) {
			return;
		}

		TradeMode mode = (TradeMode) Configs.Generic.TRADE_MODE.getOptionListValue();
		TradingMachine machine = machines.get(mode);
		if (machine != null) {
			machine.tick(mc);
		}
	}

	/** 返回当前启用的交易机器（mod 关闭时返回 null；仅查询，不 tick） */
	public TradingMachine getActiveMachine() {
		if (!Configs.Generic.ENABLED.getBooleanValue()) {
			return null;
		}
		return machines.get((TradeMode) Configs.Generic.TRADE_MODE.getOptionListValue());
	}
}
