package com.github.sebseb7.autotrade.trade.staticmode;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.SessionHooks;
import com.github.sebseb7.autotrade.trade.TradeSession;
import com.github.sebseb7.autotrade.trade.TradeSessionBase;
import com.github.sebseb7.autotrade.trade.VillagerHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

public class StaticTradeSession implements TradeSession {

	private final TradeSessionBase state = new TradeSessionBase(new StaticHooks());
	private final List<Integer> processedVillagers = new ArrayList<>();
	private final List<Integer> targetVillagers = new ArrayList<>();
	private int targetIndex = 0;
	private boolean scanned = false;

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
	public int getVillagersInteracted() {
		return state.getVillagersInteracted();
	}

	@Override
	public boolean isInventoryBlocked() {
		return state.isInventoryBlocked();
	}

	@Override
	public void clear() {
		state.clear();
		processedVillagers.clear();
		targetVillagers.clear();
		targetIndex = 0;
		scanned = false;
	}

	@Override
	public void resetForNextVillager() {
		state.resetForNextVillager();
	}

	private class StaticHooks implements SessionHooks {
		@Override
		public Entity findNextVillager(MinecraftClient mc) {
			if (mc.player == null || mc.world == null)
				return null;
			if (!scanned) {
				scanVillagers(mc);
				scanned = true;
			}
			while (targetIndex < targetVillagers.size()) {
				int id = targetVillagers.get(targetIndex++);
				if (!processedVillagers.contains(id)) {
					Entity e = mc.world.getEntityById(id);
					if (e != null)
						return e;
				}
			}
			return null;
		}

		// 首次扫描时记录范围内全部村民/流浪商人，之后按列表顺序依次处理
		private void scanVillagers(MinecraftClient mc) {
			double range = Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
			for (Entity e : VillagerHelper.findNearby(mc, range)) {
				targetVillagers.add(e.getId());
			}
		}

		@Override
		public boolean onVillagerDone(MinecraftClient mc, int villagerActiveId) {
			processedVillagers.add(villagerActiveId);
			return true;
		}

		@Override
		public void onVillagerTimeout(int villagerActiveId) {
			processedVillagers.add(villagerActiveId);
		}

		@Override
		public boolean useVoidDelay() {
			return false;
		}

		@Override
		public int getSessionCooldown() {
			return Configs.Generic.TRADE_INTERVAL.getIntegerValue();
		}

		@Override
		public double getScanRange() {
			return Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue();
		}
	}
}
