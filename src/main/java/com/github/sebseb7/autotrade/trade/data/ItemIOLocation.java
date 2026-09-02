package com.github.sebseb7.autotrade.trade.data;

import net.minecraft.util.math.BlockPos;

/**
 * 表示一条物品 IO 的容器位置记录：维度 + 坐标 + 启用开关。
 *
 * <p>
 * {@code dimension} 为该容器所在的维度标识（如 {@code minecraft:overworld}）。
 * 留空字符串表示「任意维度」：容器在哪个维度都被视为可达，用于兼容旧配置 （旧版 ItemIO 只有坐标、无维度概念，迁移后维度为空串即自动匹配任意维度）。
 * </p>
 */
public final class ItemIOLocation {
	private String dimension = "";
	private int x;
	private int y;
	private int z;
	/** 位置记录启用开关（默认 true；关闭后该位置不参与容器 IO 候选） */
	private boolean enabled = true;

	/** 默认构造：dimension 为空串（任意维度）、enabled 默认 true */
	public ItemIOLocation() {
	}

	/** 全参构造：直接指定全部 5 个字段 */
	public ItemIOLocation(String dimension, int x, int y, int z, boolean enabled) {
		this.dimension = dimension;
		this.x = x;
		this.y = y;
		this.z = z;
		this.enabled = enabled;
	}

	/** 拷贝构造：复制全部 5 个字段（dimension 为不可变对象，直接引用复制即可） */
	public ItemIOLocation(ItemIOLocation other) {
		this.dimension = other.dimension;
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
		this.enabled = other.enabled;
	}

	/** 转为 Minecraft 坐标对象（供距离计算/方块查询使用） */
	public BlockPos toBlockPos() {
		return new BlockPos(x, y, z);
	}

	public String getDimension() {
		return dimension;
	}

	public void setDimension(String v) {
		this.dimension = v;
	}

	public int getX() {
		return x;
	}

	public void setX(int v) {
		this.x = v;
	}

	public int getY() {
		return y;
	}

	public void setY(int v) {
		this.y = v;
	}

	public int getZ() {
		return z;
	}

	public void setZ(int v) {
		this.z = v;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean v) {
		this.enabled = v;
	}
}
