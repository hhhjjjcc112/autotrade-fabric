package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

public final class ContainerIOHelper {

	/** 容器 IO 意图：交易对 + 输入/输出方向 + 输入侧 give 槽位（0=give1，1=give2；输出操作恒为 0） */
	public record IOIntent(TradePair pair, boolean isInput, int inputSlot) {
		/** 饥饿记账用的稳定标识：容器坐标 + 方向 + 槽位（跨 TradePair 实例稳定，同一容器意图共享饥饿计数） */
		public String ioKey() {
			return new ContainerCandidate(pair, isInput, inputSlot, 0).ioKey();
		}
	}

	/** 容器 IO 候选：交易对 + 方向 + 输入槽位 + 距离（MOVING 模式饥饿评分用；距离 ≤ 4 格） */
	public record ContainerCandidate(TradePair pair, boolean isInput, int inputSlot, double distance) {
		/** 饥饿记账用的稳定标识：容器坐标 + 方向 + 槽位（跨 TradePair 实例稳定，同一容器意图共享饥饿计数） */
		public String ioKey() {
			String pos;
			if (isInput) {
				pos = inputSlot == 1
						? pair.getGive2InputX() + "," + pair.getGive2InputY() + "," + pair.getGive2InputZ()
						: pair.getInputX() + "," + pair.getInputY() + "," + pair.getInputZ();
			} else {
				pos = pair.getOutputX() + "," + pair.getOutputY() + "," + pair.getOutputZ();
			}
			return pos + "#" + isInput + "#" + inputSlot;
		}
	}

	public static boolean startContainerIO(MinecraftClient mc, Consumer<ContainerIOTask> starter) {
		if (mc.player == null || mc.world == null) {
			return false;
		}

		// 全部需 IO 候选（输入 give1/give2 + 输出）中取距离最近者（输入/输出各自最近再取更近 ≡ 全局最近）
		List<ContainerCandidate> candidates = findPendingContainers(mc);
		if (candidates.isEmpty()) {
			return false;
		}
		ContainerCandidate best = candidates.stream().min(Comparator.comparingDouble(ContainerCandidate::distance))
				.orElse(null);
		return startContainerIO(best, starter);
	}

	/** 按指定候选启动容器 IO（MOVING 模式饥饿评分选中后使用） */
	public static boolean startContainerIO(ContainerCandidate candidate, Consumer<ContainerIOTask> starter) {
		if (candidate == null) {
			return false;
		}
		starter.accept(new ContainerIOTask(new IOIntent(candidate.pair(), candidate.isInput(), candidate.inputSlot())));
		logIOStart(candidate);
		return true;
	}

	/** 收集所有 ≤4 格需要 IO 的容器候选（输入 give1/give2 + 输出各为独立候选），MOVING 饥饿评分用 */
	public static List<ContainerCandidate> findPendingContainers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}

		List<TradePair> pairs = TradePair.loadAllPairs();
		Map<String, Integer> slotCounts = buildInventorySlotCounts(mc.player, pairs);
		List<ContainerCandidate> result = new ArrayList<>();
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;
			// 输出候选：get 物品槽位数达到输出阈值，槽位恒为 0
			if (p.isOutputEnabled() && needsContainerIO(mc, p, false, slotCounts, 0)) {
				result.add(new ContainerCandidate(p, false, 0, containerDistance(mc, p, false, 0)));
			}
			// give1 输入候选：槽位 0
			if (p.isInputEnabled() && needsContainerIO(mc, p, true, slotCounts, 0)) {
				result.add(new ContainerCandidate(p, true, 0, containerDistance(mc, p, true, 0)));
			}
			// give2 输入候选：槽位 1，使用 give2 自有输入容器坐标
			if (p.isGive2InputEnabled() && needsContainerIO(mc, p, true, slotCounts, 1)) {
				result.add(new ContainerCandidate(p, true, 1, containerDistance(mc, p, true, 1)));
			}
		}
		return result;
	}

	/**
	 * 输出优先的容器 IO 启动：背包满（交易被阻塞）时优先把产出物品运往输出容器以释放空间； 无输出需求时再退回输入容器。逻辑与
	 * {@link #startContainerIO} 相同，仅候选优先级不同。
	 */
	public static boolean startOutputFirstContainerIO(MinecraftClient mc, Consumer<ContainerIOTask> starter) {
		if (mc.player == null || mc.world == null) {
			return false;
		}

		// 第一轮：仅输出候选；第二轮：仅输入候选（含 give1/give2 两个槽位候选）
		List<ContainerCandidate> candidates = findPendingContainers(mc);
		ContainerCandidate best = candidates.stream().filter(c -> !c.isInput())
				.min(Comparator.comparingDouble(ContainerCandidate::distance)).orElse(null);
		if (best == null) {
			best = candidates.stream().filter(ContainerCandidate::isInput)
					.min(Comparator.comparingDouble(ContainerCandidate::distance)).orElse(null);
		}
		return startContainerIO(best, starter);
	}

	public static double nearestContainerDistance(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return Double.MAX_VALUE;
		}

		List<TradePair> pairs = TradePair.loadAllPairs();
		Map<String, Integer> slotCounts = buildInventorySlotCounts(mc.player, pairs);
		double nearest = Double.MAX_VALUE;
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;
			// give1 输入候选（槽位 0）
			if (p.isInputEnabled() && needsContainerIO(mc, p, true, slotCounts, 0)) {
				nearest = Math.min(nearest, containerDistance(mc, p, true, 0));
			}
			// give2 输入候选（槽位 1）
			if (p.isGive2InputEnabled() && needsContainerIO(mc, p, true, slotCounts, 1)) {
				nearest = Math.min(nearest, containerDistance(mc, p, true, 1));
			}
			if (p.isOutputEnabled() && needsContainerIO(mc, p, false, slotCounts, 0)) {
				nearest = Math.min(nearest, containerDistance(mc, p, false, 0));
			}
		}
		return nearest;
	}

	/** 范围内是否存在村民/流浪商人 */
	public static boolean hasVillagerInRange(MinecraftClient mc) {
		return VillagerHelper.hasVillagerInRange(mc, Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue());
	}

	/** 返回最近的村民/流浪商人距离 */
	public static double nearestVillagerDistance(MinecraftClient mc) {
		return VillagerHelper.nearestVillagerDistance(mc, Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue());
	}

	public static double containerDistance(MinecraftClient mc, TradePair p, boolean isInput, int inputSlot) {
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}

		// 输入侧按 give 槽位选容器坐标：槽位 1（give2）用自有输入容器，其余用 give1 输入容器；输出用输出容器
		BlockPos pos;
		if (isInput) {
			pos = inputSlot == 1
					? new BlockPos(p.getGive2InputX(), p.getGive2InputY(), p.getGive2InputZ())
					: new BlockPos(p.getInputX(), p.getInputY(), p.getInputZ());
		} else {
			pos = new BlockPos(p.getOutputX(), p.getOutputY(), p.getOutputZ());
		}
		return pos.toCenterPos().distanceTo(mc.player.getPos());
	}

	// 判断某交易对当前是否需要容器 IO：
	// 1. 容器距离超过 4 格时不触发；
	// 2. 输入（give 物品，按槽位区分 give1/give2，各用独立阈值）：背包中该物品的槽位数不超过阈值（默认 1 = 只剩 1
	// 组时）需要从容器补充；
	// 3. 输出（get 物品）：背包中该物品的槽位数达到输出阈值时需要运往容器。
	static boolean needsContainerIO(MinecraftClient mc, TradePair p, boolean isInput, Map<String, Integer> slotCounts,
			int inputSlot) {
		if (containerDistance(mc, p, isInput, inputSlot) > 4)
			return false;

		// 输入槽位 1（give2）防御：give2 物品为空时不触发，避免空物品补充循环
		if (isInput && inputSlot == 1) {
			String give2 = p.getGiveItem2();
			if (give2 == null || give2.isBlank()) {
				return false;
			}
		}

		String encoded = isInput ? (inputSlot == 0 ? p.getGiveItem() : p.getGiveItem2()) : p.getGetItem();
		int slots = slotCounts.getOrDefault(encoded, 0);
		// 输入侧阈值按槽位区分：槽位 1（give2）用独立阈值，其余用 give1 阈值
		int threshold = isInput
				? (inputSlot == 1 ? p.getGive2InputThreshold() : p.getInputThreshold())
				: p.getOutputThreshold();

		// 输入：槽位数不超过阈值时补货（阈值 1 = 只剩 1 组时）；输出：槽位数达到阈值时清出
		return isInput ? (slots <= threshold) : (slots >= threshold);
	}

	// 统计玩家背包中每个已启用交易对所需物品的占用槽位数（而非数量）
	static Map<String, Integer> buildInventorySlotCounts(PlayerEntity player, List<TradePair> pairs) {
		Map<String, Integer> counts = new HashMap<>();
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;
			if (p.isInputEnabled())
				counts.putIfAbsent(p.getGiveItem(), 0);
			// give2 输入启用时也统计 give2 物品槽位（与 give1 同物品时共用同一键，符合语义）
			if (p.isGive2InputEnabled())
				counts.putIfAbsent(p.getGiveItem2(), 0);
			if (p.isOutputEnabled())
				counts.putIfAbsent(p.getGetItem(), 0);
		}
		if (counts.isEmpty())
			return counts;

		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (stack.isEmpty())
				continue;
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				if (ItemStringHelper.matches(stack, e.getKey())) {
					e.setValue(e.getValue() + 1);
					break;
				}
			}
		}
		return counts;
	}

	private static void logIOStart(ContainerCandidate candidate) {
		TradePair p = candidate.pair();
		// 按 give 槽位记录具体物品：输入槽位 1 用 give2，其余用 give1
		String giveItem = candidate.isInput() && candidate.inputSlot() == 1 ? p.getGiveItem2() : p.getGiveItem();
		AutoTrade.logger.info("[ContainerIO] IDLE → {} for pair(give={} get={})",
				candidate.isInput() ? "INPUT" : "OUTPUT", ItemStringHelper.getItemId(giveItem),
				ItemStringHelper.getItemId(p.getGetItem()));
	}

	private ContainerIOHelper() {
	}
}
