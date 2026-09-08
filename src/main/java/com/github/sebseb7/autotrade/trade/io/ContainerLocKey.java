package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;

/**
 * 容器位置键（维度 + 坐标 + 输入/输出方向）：containerKey 格式 dim,x,y,z#isInput 的强类型载体。
 *
 * <p>
 * 键格式是跨模块契约——L2 让位检查器排除同容器条目、MOVING CONFIG 失败冷却、IOIntent/ContainerCandidate 的
 * containerKey() 均依赖该字符串，必须字节稳定（dim 为空串 = 任意维度，保留空段）。
 * </p>
 */
public record ContainerLocKey(String dimension, int x, int y, int z, boolean isInput) {

	/** 从位置记录构造键（维度/坐标/方向均取自记录；占位 0 0 0 的过滤在调度器完成，本类不校验） */
	public static ContainerLocKey from(ItemIOLocation loc, boolean isInput) {
		return new ContainerLocKey(loc.getDimension(), loc.getX(), loc.getY(), loc.getZ(), isInput);
	}

	/**
	 * 序列化为 containerKey 格式（维度+坐标+方向，逗号分隔 + # 方向后缀）；与旧
	 * ContainerCandidate.containerKey() 字节一致
	 */
	public String format() {
		return dimension + "," + x + "," + y + "," + z + "#" + isInput;
	}
}