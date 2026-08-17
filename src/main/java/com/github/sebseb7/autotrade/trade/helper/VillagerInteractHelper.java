package com.github.sebseb7.autotrade.trade.helper;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class VillagerInteractHelper {

	public static void lookAtEntity(MinecraftClient mc, Entity target) {
		if (mc.player == null || mc.world == null) {
			return;
		}

		Vec3d aimPoint = firstVisiblePoint(mc, target);
		if (aimPoint != null) {
			// 计算玩家眼睛到瞄准点的方向向量
			Vec3d delta = aimPoint.subtract(mc.player.getEyePos());
			// 水平距离（x/z 平面投影长度），用于计算俯仰角
			double horiz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
			// Minecraft 的 yaw 以 Z 负方向为 0°、顺时针增加；
			// atan2(dz, dx) 得到的是与 X 正方向的夹角，需减去 90° 转换为游戏坐标系
			float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
			// pitch 为负表示向下看，范围 -90°（垂直向下）到 +90°（垂直向上）
			float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horiz));
			mc.player.setYaw(yaw);
			mc.player.setPitch(pitch);
			mc.player.headYaw = yaw;
		}
	}

	public static Vec3d firstVisiblePoint(MinecraftClient mc, Entity target) {
		if (mc.player == null || mc.world == null) {
			return null;
		}

		Vec3d eye = mc.player.getEyePos();
		net.minecraft.util.math.Box box = target.getBoundingBox();
		double cx = (box.minX + box.maxX) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		// 候选瞄准点：优先取村民眼睛，再取身体中心/顶部/底部及四个角，
		// 逐个尝试直到某个点与玩家之间没有方块遮挡（即射线未被命中）
		Vec3d[] candidates = {target.getEyePos(), new Vec3d(cx, (box.minY + box.maxY) * 0.5, cz),
				new Vec3d(cx, box.maxY - 0.05, cz), new Vec3d(cx, box.minY + 0.1, cz),
				new Vec3d(box.minX + 0.05, box.maxY - 0.2, box.minZ + 0.05),
				new Vec3d(box.maxX - 0.05, box.maxY - 0.2, box.minZ + 0.05),
				new Vec3d(box.minX + 0.05, box.maxY - 0.2, box.maxZ + 0.05),
				new Vec3d(box.maxX - 0.05, box.maxY - 0.2, box.maxZ + 0.05),};
		for (Vec3d p : candidates) {
			// 从玩家眼睛向候选点发射碰撞射线（忽略流体）
			BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, p, RaycastContext.ShapeType.COLLIDER,
					RaycastContext.FluidHandling.NONE, mc.player));
			// 射线未命中方块，或命中点已到达候选点（目标本身），说明该点可见
			if (hit.getType() == HitResult.Type.MISS
					|| eye.squaredDistanceTo(hit.getPos()) >= eye.squaredDistanceTo(p) - 1.0E-4) {
				return p;
			}
		}
		return null;
	}

	private VillagerInteractHelper() {
	}
}
