package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
import com.github.sebseb7.autotrade.trade.helper.VillagerHelper;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

public final class ContainerIOHelper {

	/** 容器 IO 意图：物品 IO 条目 + 输入/输出方向 */
	public record IOIntent(ItemIO io, boolean isInput) {
		/** 饥饿记账用的稳定标识：容器坐标 + 方向（跨条目实例稳定，同一容器意图共享饥饿计数） */
		public String ioKey() {
			return new ContainerCandidate(io, isInput, 0).ioKey();
		}
	}

	/** 容器 IO 候选：物品 IO 条目 + 方向 + 距离（MOVING 模式饥饿评分用；距离 ≤ 4 格） */
	public record ContainerCandidate(ItemIO io, boolean isInput, double distance) {
		/** 饥饿记账用的稳定标识：容器坐标 + 方向（跨条目实例稳定，同一容器意图共享饥饿计数） */
		public String ioKey() {
			return io.getX() + "," + io.getY() + "," + io.getZ() + "#" + isInput;
		}
	}

	public static boolean startContainerIO(MinecraftClient mc, Consumer<ContainerIOTask> starter) {
		if (mc.player == null || mc.world == null) {
			return false;
		}

		// 全部需 IO 候选（输入 + 输出）中取距离最近者（输入/输出各自最近再取更近 ≡ 全局最近）
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
		starter.accept(new ContainerIOTask(new IOIntent(candidate.io(), candidate.isInput())));
		logIOStart(candidate);
		return true;
	}

	/** 收集所有 ≤4 格需要 IO 的容器候选（输入/输出各条目为独立候选），MOVING 饥饿评分用 */
	public static List<ContainerCandidate> findPendingContainers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}

		// 派生活动物品集：输入集 = enabled 交易对 giveItem ∪ giveItem2，输出集 = getItem
		// （复用 IoItemDeriver，语义与手工构建一致；编码字符串精确相等判定，与 buildInventorySlotCounts 键空间一致）
		List<TradePair> pairs = TradePairCache.getAll();
		IoItemDeriver.ActiveItemSets activeSets = IoItemDeriver.deriveActiveSets(pairs);
		Set<String> inputItems = activeSets.inputs();
		Set<String> outputItems = activeSets.outputs();

		// 缓存访问器：配置未变时跳过 JSON 解析（仅遍历读取，不改动条目）
		List<ItemIO> entries = ItemIOCache.getAll();
		Map<String, Integer> slotCounts = buildInventorySlotCounts(mc.player, entries, inputItems, outputItems);
		List<ContainerCandidate> result = new ArrayList<>();
		for (ItemIO io : entries) {
			// 条目级启用开关：禁用的条目不参与任何容器 IO（在方向命中检查之前）
			if (!io.isEnabled()) {
				continue;
			}
			// 占位坐标 0 0 0 的条目不触发容器 IO
			if (io.getX() == 0 && io.getY() == 0 && io.getZ() == 0) {
				continue;
			}
			boolean isInput = io.isInput();
			// 条目物品必须命中活动物品集：输入条目 ∈ 输入集、输出条目 ∈ 输出集，未命中跳过
			if (isInput ? !inputItems.contains(io.getItem()) : !outputItems.contains(io.getItem())) {
				continue;
			}
			if (needsContainerIO(mc, io, isInput, slotCounts)) {
				result.add(new ContainerCandidate(io, isInput, containerDistance(mc, io)));
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

		// 第一轮：仅输出候选；第二轮：仅输入候选
		List<ContainerCandidate> candidates = findPendingContainers(mc);
		ContainerCandidate best = candidates.stream().filter(c -> !c.isInput())
				.min(Comparator.comparingDouble(ContainerCandidate::distance)).orElse(null);
		if (best == null) {
			best = candidates.stream().filter(ContainerCandidate::isInput)
					.min(Comparator.comparingDouble(ContainerCandidate::distance)).orElse(null);
		}
		return startContainerIO(best, starter);
	}

	/** 范围内是否存在村民/流浪商人 */
	public static boolean hasVillagerInRange(MinecraftClient mc) {
		return VillagerHelper.hasVillagerInRange(mc, Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue());
	}

	/** 返回最近的村民/流浪商人距离 */
	public static double nearestVillagerDistance(MinecraftClient mc) {
		return VillagerHelper.nearestVillagerDistance(mc, Configs.Generic.VILLAGER_SCAN_RANGE.getIntegerValue());
	}

	/** 计算条目容器坐标到玩家的距离 */
	public static double containerDistance(MinecraftClient mc, ItemIO io) {
		if (mc.player == null) {
			return Double.MAX_VALUE;
		}
		BlockPos pos = new BlockPos(io.getX(), io.getY(), io.getZ());
		return pos.toCenterPos().distanceTo(mc.player.getPos());
	}

	// 判断某条目当前是否需要容器 IO：
	// 1. 容器距离超过 4 格时不触发；
	// 2. 输入（从容器取货）：背包中该物品的槽位数不超过阈值（默认 1 = 只剩 1 组时）需要从容器补充；
	// 3. 输出（向容器出货）：背包中该物品的槽位数达到阈值时需要运往容器。
	static boolean needsContainerIO(MinecraftClient mc, ItemIO io, boolean isInput, Map<String, Integer> slotCounts) {
		if (containerDistance(mc, io) > 4)
			return false;

		int slots = slotCounts.getOrDefault(io.getItem(), 0);
		int threshold = io.getThreshold();

		// 输入：槽位数不超过阈值时补货（阈值 1 = 只剩 1 组时）；输出：槽位数达到阈值时清出
		return isInput ? (slots <= threshold) : (slots >= threshold);
	}

	// 统计玩家背包中活动物品（出现在条目中且命中活动物品集）的占用槽位数（而非数量）
	static Map<String, Integer> buildInventorySlotCounts(PlayerEntity player, List<ItemIO> entries,
			Set<String> inputItems, Set<String> outputItems) {
		Map<String, Integer> counts = new HashMap<>();
		// 索引阶段：对命中活动集的条目物品编码串预解析一次（同 id 多条目 → 列表），热循环内不再做 JSON 解析
		Map<String, List<ItemStringHelper.ParsedItem>> byId = new HashMap<>();
		for (ItemIO io : entries) {
			// 键 = 出现在条目中的活动物品（输入条目 ∈ 输入集、输出条目 ∈ 输出集）
			if (io.isInput() ? inputItems.contains(io.getItem()) : outputItems.contains(io.getItem())) {
				ItemStringHelper.ParsedItem parsed = ItemStringHelper.parse(io.getItem());
				if (parsed == null) {
					// 非法编码条目不参与匹配（等价旧 getItemId 返回 "" 的落空行为）
					continue;
				}
				counts.putIfAbsent(parsed.encoded(), 0);
				byId.computeIfAbsent(parsed.id(), k -> new ArrayList<>()).add(parsed);
			}
		}
		if (counts.isEmpty())
			return counts;

		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (stack.isEmpty())
				continue;
			// 每槽只计算一次实际 ID，内层仅遍历同 id 的预解析条目做 NBT 比较
			String actualId = Registries.ITEM.getId(stack.getItem()).toString();
			List<ItemStringHelper.ParsedItem> candidates = byId.get(actualId);
			if (candidates == null)
				continue;
			for (ItemStringHelper.ParsedItem parsed : candidates) {
				if (ItemStringHelper.matches(stack, parsed)) {
					counts.merge(parsed.encoded(), 1, Integer::sum);
					break;
				}
			}
		}
		return counts;
	}

	private static void logIOStart(ContainerCandidate candidate) {
		ItemIO io = candidate.io();
		AutoTrade.logger.info("[ContainerIO] IDLE → {} for item={}", candidate.isInput() ? "INPUT" : "OUTPUT",
				ItemStringHelper.getItemId(io.getItem()));
	}

	private ContainerIOHelper() {
	}
}