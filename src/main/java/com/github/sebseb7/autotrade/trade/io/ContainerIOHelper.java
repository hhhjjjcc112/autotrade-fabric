package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.trade.data.ItemIO;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** 容器 IO 纯工具（距离计算）；调度决策已上移 trade/machine/ContainerIOScheduler */
public final class ContainerIOHelper {

	/** 计算条目容器坐标到玩家的距离 */
	public static double containerDistance(MinecraftClient mc, ItemIO io) {
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}
		BlockPos pos = new BlockPos(io.getX(), io.getY(), io.getZ());
		return pos.toCenterPos().distanceTo(mc.player.getPos());
	}

	private ContainerIOHelper() {
	}
}
