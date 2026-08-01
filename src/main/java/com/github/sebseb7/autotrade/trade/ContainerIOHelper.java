package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
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

	public static boolean startContainerIO(MinecraftClient mc, Consumer<ContainerIOOperation> starter) {
		if (mc.player == null || mc.world == null) {
			return false;
		}

		List<TradePair> pairs = TradePair.loadAllPairs();
		Map<String, Integer> slotCounts = buildInventorySlotCounts(mc.player, pairs);

		double bestDist = Double.MAX_VALUE;
		TradePair bestPair = null;
		boolean bestIsInput = false;

		// 遍历所有交易对，从「需要 IO 的输入/输出容器」中选出距离玩家最近的一个
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;

			if (p.isInputEnabled() && needsContainerIO(mc, p, true, slotCounts)) {
				double d = containerDistance(mc, p, true);
				if (d < bestDist) {
					bestDist = d;
					bestPair = p;
					bestIsInput = true;
				}
			}
			if (p.isOutputEnabled() && needsContainerIO(mc, p, false, slotCounts)) {
				double d = containerDistance(mc, p, false);
				if (d < bestDist) {
					bestDist = d;
					bestPair = p;
					bestIsInput = false;
				}
			}
		}

		if (bestPair != null && bestDist <= 4) {
			// 最近的待 IO 容器在 4 格以内才触发容器操作（避免远距离走动）
			starter.accept(new ContainerIOOperation(bestPair, bestIsInput));
			logIOStart(bestPair, bestIsInput);
			return true;
		}
		return false;
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
			if (p.isInputEnabled() && needsContainerIO(mc, p, true, slotCounts)) {
				nearest = Math.min(nearest, containerDistance(mc, p, true));
			}
			if (p.isOutputEnabled() && needsContainerIO(mc, p, false, slotCounts)) {
				nearest = Math.min(nearest, containerDistance(mc, p, false));
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

	public static double containerDistance(MinecraftClient mc, TradePair p, boolean isInput) {
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}

		BlockPos pos = isInput
				? new BlockPos(p.getInputX(), p.getInputY(), p.getInputZ())
				: new BlockPos(p.getOutputX(), p.getOutputY(), p.getOutputZ());
		return pos.toCenterPos().distanceTo(mc.player.getPos());
	}

	// 判断某交易对当前是否需要容器 IO：
	// 1. 容器距离超过 4 格时不触发；
	// 2. 输入（give 物品）：背包中该物品的槽位数低于输入阈值时需要从容器补充；
	// 3. 输出（get 物品）：背包中该物品的槽位数达到输出阈值时需要运往容器。
	static boolean needsContainerIO(MinecraftClient mc, TradePair p, boolean isInput, Map<String, Integer> slotCounts) {
		if (containerDistance(mc, p, isInput) > 4)
			return false;

		String encoded = isInput ? p.getGiveItem() : p.getGetItem();
		int slots = slotCounts.getOrDefault(encoded, 0);
		int threshold = isInput ? p.getInputThreshold() : p.getOutputThreshold();

		return isInput ? (slots < threshold) : (slots >= threshold);
	}

	// 统计玩家背包中每个已启用交易对所需物品的占用槽位数（而非数量）
	static Map<String, Integer> buildInventorySlotCounts(PlayerEntity player, List<TradePair> pairs) {
		Map<String, Integer> counts = new HashMap<>();
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;
			if (p.isInputEnabled())
				counts.putIfAbsent(p.getGiveItem(), 0);
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

	private static void logIOStart(TradePair p, boolean isInput) {
		AutoTrade.logger.info("[ContainerIO] IDLE → {} for pair(give={} get={})", isInput ? "INPUT" : "OUTPUT",
				ItemStringHelper.getItemId(p.getGiveItem()), ItemStringHelper.getItemId(p.getGetItem()));
	}

	private ContainerIOHelper() {
	}
}
