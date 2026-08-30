package com.github.sebseb7.autotrade.trade.machine;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.TradePair;
import com.github.sebseb7.autotrade.trade.data.TradePairCache;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper;
import com.github.sebseb7.autotrade.trade.io.ContainerIOTask;
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

/**
 * 容器 IO 机器层调度器：容器 IO 的「要不要做、做哪个、什么时候做」调度决策归机器层（与村民调度同构）， 候选收集 + 需要 IO 判定 + 选择派发
 * + 扫描缓存全部集中于此。
 *
 * <p>
 * 扫描缓存语义：空闲时复用上次扫描结果（TTL = {@link Configs.Generic#IDLE_SCAN_INTERVAL} tick），
 * 任意任务结束/重置时经 {@link #invalidate()} 立即失效重扫——交易与转运都改变背包，必须重扫； AFK 最坏情况下容器变化最多延迟
 * idleScanInterval tick 才被感知。
 * </p>
 */
public class ContainerIOScheduler {

	/** 容器 IO 候选：物品 IO 条目 + 方向 + 距离（MOVING 模式饥饿评分用；距离 ≤ CONTAINER_REACH 格） */
	public record ContainerCandidate(ItemIO io, boolean isInput, double distance) {
		/** 饥饿记账用的稳定标识：容器坐标 + 方向（跨条目实例稳定，同一容器意图共享饥饿计数） */
		public String ioKey() {
			return io.getX() + "," + io.getY() + "," + io.getZ() + "#" + isInput;
		}
	}

	/** 扫描缓存：null = 未扫描/已失效（任务结束/重置后必须重扫） */
	private List<ContainerCandidate> cachedCandidates = null;
	/** 缓存生成时的世界 tick（配合 IDLE_SCAN_INTERVAL 做 TTL 复用） */
	private long cachedAtTick = Long.MIN_VALUE;

	/** 使扫描缓存失效：任何任务结束/重置时调用——交易与转运都改变背包，必须重扫 */
	public void invalidate() {
		cachedCandidates = null;
	}

	/**
	 * 返回当前需要容器 IO 的候选列表（只读契约：调用方不得修改返回的列表或其中的候选）。 玩家/世界缺失时直接返回空列表（不触碰缓存）；否则优先复用 TTL
	 * 内的扫描缓存， 未命中则重新扫描并记录缓存时间。
	 */
	public List<ContainerCandidate> findPendingContainers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}
		long now = mc.world.getTime();
		// 缓存未失效且未超 TTL：直接复用上次扫描结果（空闲时每 idleScanInterval tick 才全量扫描一次）
		if (cachedCandidates != null && now - cachedAtTick < Configs.Generic.IDLE_SCAN_INTERVAL.getIntegerValue()) {
			return cachedCandidates;
		}
		cachedCandidates = scanPendingContainers(mc);
		cachedAtTick = now;
		return cachedCandidates;
	}

	/** 全量扫描：收集所有 ≤ CONTAINER_REACH 格需要 IO 的容器候选（输入/输出各条目为独立候选），MOVING 饥饿评分用 */
	private List<ContainerCandidate> scanPendingContainers(MinecraftClient mc) {
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
			// 距离只算一次：同时用于可及检查与候选距离（替代旧 needsContainerIO 内的重复计算）
			double distance = ContainerIOHelper.containerDistance(mc, io);
			if (distance > Configs.Generic.CONTAINER_REACH.getIntegerValue()) {
				continue;
			}
			// 阈值判定内联：输入（从容器取货）槽位数不超过阈值时补货；输出（向容器出货）槽位数达到阈值时清出
			int slots = slotCounts.getOrDefault(io.getItem(), 0);
			boolean needed = isInput ? (slots <= io.getThreshold()) : (slots >= io.getThreshold());
			if (!needed) {
				continue;
			}
			result.add(new ContainerCandidate(io, isInput, distance));
		}
		return result;
	}

	/** 全部需 IO 候选（输入 + 输出）中取距离最近者启动（输入/输出各自最近再取更近 ≡ 全局最近） */
	public boolean startNearest(MinecraftClient mc, Consumer<ContainerIOTask> starter) {
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
		return startCandidate(best, starter);
	}

	/**
	 * 输出优先的容器 IO 启动：背包满（交易被阻塞）时优先把产出物品运往输出容器以释放空间； 无输出需求时再退回输入容器。逻辑与
	 * {@link #startNearest} 相同，仅候选优先级不同。
	 */
	public boolean startOutputFirst(MinecraftClient mc, Consumer<ContainerIOTask> starter) {
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
		return startCandidate(best, starter);
	}

	/** 按指定候选启动容器 IO（MOVING 模式饥饿评分选中后使用） */
	public boolean startCandidate(ContainerCandidate candidate, Consumer<ContainerIOTask> starter) {
		if (candidate == null) {
			return false;
		}
		starter.accept(new ContainerIOTask(new ContainerIOTask.IOIntent(candidate.io(), candidate.isInput())));
		logIOStart(candidate);
		return true;
	}

	// 统计玩家背包中活动物品（出现在条目中且命中活动物品集）的占用槽位数（而非数量）
	private static Map<String, Integer> buildInventorySlotCounts(PlayerEntity player, List<ItemIO> entries,
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
}
