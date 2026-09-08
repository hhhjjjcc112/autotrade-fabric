package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;

/**
 * 容器 IO 过滤谓词纯工具：调度器扫描等需要过滤候选位置记录时的共用判定单点。
 *
 * <p>
 * 仅收录「行级 + 记录级启用合取」与「0,0,0 占位」两个真正逐字共用的谓词；维度/距离判定因调用方语义不同不入此类—— 扫描的维度过滤是「记录维度
 * vs 玩家当前维度」两方关系，返回触发冲突判定是「记录维度 vs 回程维度」三方关系，各自保留在调用方。
 * </p>
 */
final class ContainerFilters {

	/** 条目行级开关与位置记录级开关的合取：任一级关闭即该位置记录不参与容器 IO */
	static boolean isLocationEnabled(ItemIO io, ItemIOLocation loc) {
		return io.isEnabled() && loc.isEnabled();
	}

	/** 占位坐标 0 0 0 判定（新增位置记录的占位默认值，占位记录不触发容器 IO） */
	static boolean isSentinelZero(ItemIOLocation loc) {
		return loc.getX() == 0 && loc.getY() == 0 && loc.getZ() == 0;
	}

	private ContainerFilters() {
	}
}
