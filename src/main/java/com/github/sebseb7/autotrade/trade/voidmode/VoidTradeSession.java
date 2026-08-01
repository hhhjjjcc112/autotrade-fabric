package com.github.sebseb7.autotrade.trade.voidmode;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.SessionHooks;
import com.github.sebseb7.autotrade.trade.TradeSession;
import com.github.sebseb7.autotrade.trade.TradeSessionBase;
import com.github.sebseb7.autotrade.trade.VillagerHelper;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

public class VoidTradeSession implements TradeSession {

	private final TradeSessionBase state = new TradeSessionBase(new VoidHooks());
	private final Set<Integer> processedVillagers = new HashSet<>();

	@Override
	public void tick(MinecraftClient mc) {
		state.tick(mc);
	}

	@Override
	public boolean isDone() {
		return state.isDone();
	}

	@Override
	public int getSessionCooldown() {
		return state.getSessionCooldown();
	}

	@Override
	public void clear() {
		state.clear();
		processedVillagers.clear();
	}

	@Override
	public void resetForNextVillager() {
		state.resetForNextVillager();
	}

	private class VoidHooks implements SessionHooks {
		@Override
		public Entity findNextVillager(MinecraftClient mc) {
			if (mc.player == null || mc.world == null)
				return null;
			// 在扫描范围内返回第一个未处理过的村民/流浪商人
			double range = getScanRange();
			for (Entity e : VillagerHelper.findNearby(mc, range)) {
				if (!processedVillagers.contains(e.getId()))
					return e;
			}
			return null;
		}

		@Override
		public boolean onVillagerDone(MinecraftClient mc, int villagerActiveId) {
			processedVillagers.add(villagerActiveId);
			return false;
		}

		@Override
		public void onVillagerTimeout(int villagerActiveId) {
			processedVillagers.add(villagerActiveId);
		}

		@Override
		public boolean useVoidDelay() {
			return true;
		}

		@Override
		public int getSessionCooldown() {
			return 0;
		}

		@Override
		public double getScanRange() {
			return Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		}
	}
}
