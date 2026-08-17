package com.github.sebseb7.autotrade.config.options;

import net.minecraft.util.math.BlockPos;

public class ConfigCoordinate extends ConfigStringValidated {

	/** 构造坐标配置项：值格式固定为「x y z」三个整数，校验沿用父类公开的 POSITION_PATTERN */
	public ConfigCoordinate(String name, String defaultValue, String comment) {
		super(name, defaultValue, comment, POSITION_PATTERN);
	}

	/** 将当前配置值解析为 BlockPos；值非法（格式不符）时返回 null */
	public BlockPos toBlockPos() {
		// 复用下方静态 parse：与调用 parse(getStringValue()) 等价，非法值统一返回 null，不抛异常
		return parse(this.getStringValue());
	}

	/**
	 * 静态解析「x y z」坐标字符串为 BlockPos；段数不符或数字非法返回 null。 用 Long.parseLong + 钳制 ±30000000
	 * 统一了两处旧解析语义：PairEditScreen.parsePos（Long 解析 + 钳制） 与
	 * VoidTradeMachine.parseReturnPos（按 int 直接解析，越界抛异常转 null）。钳制范围与 MC 世界边界一致， 数值超出
	 * int 范围时按钳制处理而非拒绝。
	 */
	public static BlockPos parse(String text) {
		try {
			String[] parts = text.trim().split("\\s+");
			if (parts.length != 3)
				return null;
			// 用 long 先解析：避免按 int 直接解析时越界坐标抛 NumberFormatException（旧 VoidTradeMachine 语义）
			long[] v = new long[3];
			for (int i = 0; i < 3; i++) {
				v[i] = Long.parseLong(parts[i]);
			}
			// 钳制到 MC 世界边界 ±30000000（旧 PairEditScreen 语义），超出 int 范围的值同样被钳制而非拒绝
			int x = (int) Math.max(-30000000, Math.min(30000000, v[0]));
			int y = (int) Math.max(-30000000, Math.min(30000000, v[1]));
			int z = (int) Math.max(-30000000, Math.min(30000000, v[2]));
			return new BlockPos(x, y, z);
		} catch (NumberFormatException e) {
			// 数字段非法：与旧实现一致返回 null，绝不抛异常
			return null;
		}
	}
}
