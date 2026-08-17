package com.github.sebseb7.autotrade.trade.helper;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * 村民/流浪商人扫描辅助类：统一构建以玩家为中心的扫描范围 Box， 并将「村民 + 流浪商人」两类实体的遍历合并为一次，
 * 避免三种交易模式各自重复编写相同的扫描逻辑。
 */
public final class VillagerHelper {

	private VillagerHelper() {
	}

	/**
	 * 构建以玩家为中心、边长为 2×range 的正方体扫描范围。 调用前需确保 mc.player 非空。
	 */
	public static Box scanBox(MinecraftClient mc, double range) {
		Vec3d pos = mc.player.getPos();
		return new Box(pos.subtract(range, range, range), pos.add(range, range, range));
	}

	/**
	 * 收集范围内所有村民与流浪商人（村民在前，流浪商人在后）。 调用前需确保 mc.player 与 mc.world 非空。
	 */
	public static List<Entity> findNearby(MinecraftClient mc, double range) {
		Box box = scanBox(mc, range);
		List<Entity> entities = new ArrayList<>();
		entities.addAll(mc.world.getEntitiesByClass(VillagerEntity.class, box, e -> true));
		entities.addAll(mc.world.getEntitiesByClass(WanderingTraderEntity.class, box, e -> true));
		return entities;
	}

	/** 范围内是否存在可交易的村民/流浪商人 */
	public static boolean hasVillagerInRange(MinecraftClient mc, double range) {
		if (mc.player == null || mc.world == null) {
			return false;
		}
		return !findNearby(mc, range).isEmpty();
	}

	/** 返回最近的村民/流浪商人距离；范围内没有可交易实体时返回 Double.MAX_VALUE */
	public static double nearestVillagerDistance(MinecraftClient mc, double range) {
		if (mc.player == null || mc.world == null) {
			return Double.MAX_VALUE;
		}
		double nearest = Double.MAX_VALUE;
		for (Entity e : findNearby(mc, range)) {
			nearest = Math.min(nearest, e.getPos().distanceTo(mc.player.getPos()));
		}
		return nearest;
	}
}
