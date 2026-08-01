package com.github.sebseb7.autotrade.trade.movingmode;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.SessionHooks;
import com.github.sebseb7.autotrade.trade.TradeSession;
import com.github.sebseb7.autotrade.trade.TradeSessionBase;
import com.github.sebseb7.autotrade.trade.VillagerHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

public class MovingTradeSession implements TradeSession {

	private final TradeSessionBase state = new TradeSessionBase(new MovingHooks());
	private final List<Integer> processedVillagers = new ArrayList<>();

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

	private class MovingHooks implements SessionHooks {
		@Override
		public Entity findNextVillager(MinecraftClient mc) {
			if (mc.player == null || mc.world == null)
				return null;
			// 清理已消失（被交易/传送走）的已处理村民记录
			processedVillagers.removeIf(id -> mc.world.getEntityById(id) == null);
			// 移动模式下扫描范围放大 1.5 倍，优先返回遍历到的第一个未处理村民
			double range = getScanRange() * 1.5;
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
		public boolean useVoidDelay() {
			return false;
		}

		@Override
		public void onVillagerTimeout(int villagerActiveId) {
			processedVillagers.add(villagerActiveId);
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
