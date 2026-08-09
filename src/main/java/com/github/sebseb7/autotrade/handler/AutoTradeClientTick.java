package com.github.sebseb7.autotrade.handler;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradeMode;
import com.github.sebseb7.autotrade.trade.TradingModeMachine;
import com.github.sebseb7.autotrade.trade.movingmode.MovingModeMachine;
import com.github.sebseb7.autotrade.trade.staticmode.StaticModeMachine;
import com.github.sebseb7.autotrade.trade.voidmode.VoidModeMachine;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;

public class AutoTradeClientTick implements IClientTickHandler {

	private static final AutoTradeClientTick INSTANCE = new AutoTradeClientTick();
	private final Map<TradeMode, TradingModeMachine> machines = new EnumMap<>(TradeMode.class);
	private AutoTradeClientTick() {
		machines.put(TradeMode.STATIC, new StaticModeMachine());
		machines.put(TradeMode.MOVING, new MovingModeMachine());
		machines.put(TradeMode.VOID, new VoidModeMachine());
	}

	public static AutoTradeClientTick getInstance() {
		return INSTANCE;
	}

	public void reset() {
		for (TradingModeMachine m : machines.values()) {
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
		TradingModeMachine machine = machines.get(mode);
		if (machine != null) {
			machine.tick(mc);
		}
	}
}
