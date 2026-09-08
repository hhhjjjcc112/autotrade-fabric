package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** 容器 IO 纯工具（距离计算 + 当前维度）；调度决策集中在 trade/io/ContainerIOScheduler */
public final class ContainerIOHelper {

	/** 计算位置记录坐标到玩家的距离（玩家缺失时返回最大值，调用方按不可达处理） */
	public static double containerDistance(MinecraftClient mc, ItemIOLocation loc) {
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}
		BlockPos pos = loc.toBlockPos();
		return pos.toCenterPos().distanceTo(mc.player.getPos());
	}

	/**
	 * 当前所在维度 id（如 minecraft:overworld）；世界缺失返回 null——调用方维度过滤 equals 天然安全（null
	 * 永不等于非空维度串）
	 */
	public static String currentDimensionId(MinecraftClient mc) {
		return mc.world != null ? mc.world.getRegistryKey().getValue().toString() : null;
	}

	private ContainerIOHelper() {
	}
}