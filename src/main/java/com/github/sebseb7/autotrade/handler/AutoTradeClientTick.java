package com.github.sebseb7.autotrade.handler;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradeMode;
import com.github.sebseb7.autotrade.trade.machine.TradingMachine;
import com.github.sebseb7.autotrade.trade.mode.movingmode.MovingTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.staticmode.StaticTradeMachine;
import com.github.sebseb7.autotrade.trade.mode.voidmode.VoidTradeMachine;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;

public class AutoTradeClientTick implements IClientTickHandler {

	private static final AutoTradeClientTick INSTANCE = new AutoTradeClientTick();
	private final Map<TradeMode, TradingMachine> machines = new EnumMap<>(TradeMode.class);
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
	}

	@Override
	public void onClientTick(MinecraftClient mc) {
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
}
