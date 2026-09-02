package com.github.sebseb7.autotrade.trade.machine;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.data.IoItemDeriver;
import com.github.sebseb7.autotrade.trade.data.ItemIO;
import com.github.sebseb7.autotrade.trade.data.ItemIOCache;
import com.github.sebseb7.autotrade.trade.data.ItemIOLocation;
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
 * 扫描缓存语义：缓存对象 = 背包物品计数 map（{@link #buildInventorySlotCounts} 结果——36 槽遍历 +
 * Registry id 解析 + NBT 匹配，与玩家位置无关的慢变量），TTL 复用间隔 =
 * {@link Configs.Generic#IDLE_SCAN_INTERVAL} tick；容器候选每次调用重算 （距离/阈值/ioKey
 * 拼接，微秒级），距离每 tick 新鲜（MOVING 玩家移动），无 TTL 陈旧问题；任意任务结束/重置时经 {@link #invalidate()}
 * 强制清空背包计数缓存——交易与转运都改变背包，必须重算。
 * </p>
 */
public class ContainerIOScheduler {

	/** 容器 IO 候选：物品 IO 条目 + 位置记录 + 方向 + 距离（MOVING 模式饥饿评分用；距离 ≤ CONTAINER_REACH 格） */
	public record ContainerCandidate(ItemIO io, ItemIOLocation loc, boolean isInput, double distance) {
		/** 条目级饥饿标识：物品 + 维度/坐标 + 方向（同容器不同物品 = 不同 key，独立记账；键含维度与位置，dim 空 = 空串段，格式稳定） */
		public String ioKey() {
			return io.getItem() + "#" + loc.getDimension() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ()
					+ "#" + isInput;
		}

		/** 容器身份键（维度+坐标+方向）：L2 检查器排除同容器条目、CONFIG 失败冷却用 */
		public String containerKey() {
			return loc.getDimension() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ() + "#" + isInput;
		}
	}

	/**
	 * 让位检查器：检测范围内是否存在除 excludedContainerKey 外的「应让位」候选（MOVING 注入；null = 不检查）。
	 * 容器身份一律直接传值（不解析 ioKey 字符串——物品编码为 Gson JSON，NBT 字符串值可含任意字符，任何分隔符解析都不可靠）
	 */
	@FunctionalInterface
	public interface CompetitorChecker {
		boolean hasCompetitor(MinecraftClient mc, String excludedContainerKey);
	}

	/**
	 * 背包物品计数缓存（键 = 规范化物品编码，{@link #buildInventorySlotCounts} 产物）：null =
	 * 未扫描/已失效（任务结束/重置后必须重算）；候选列表不缓存
	 */
	private Map<String, Integer> cachedSlotCounts = null;
	/** 背包计数缓存生成时的世界 tick（配合 IDLE_SCAN_INTERVAL 做 TTL 复用） */
	private long cachedAtTick = Long.MIN_VALUE;
	/** 让位检查器（MOVING 注入；null = 不检查，STATIC/VOID 保持原行为） */
	private CompetitorChecker competitorChecker = null;

	/** 使扫描缓存失效：任何任务结束/重置时调用——交易与转运都改变背包，必须重算背包计数 */
	public void invalidate() {
		cachedSlotCounts = null;
	}

	/** 设置让位检查器（MOVING 模式构造器注入；STATIC/VOID 不设置，任务无让位检查点） */
	public void setCompetitorChecker(CompetitorChecker checker) {
		this.competitorChecker = checker;
	}

	/**
	 * 返回当前需要容器 IO 的候选列表（只读契约：调用方不得修改返回的列表或其中的候选）。 玩家/世界缺失时直接返回空列表（不触碰缓存）；
	 * 候选每次调用重算（距离实时，MOVING 玩家移动每 tick 新鲜）；背包计数 map 在 {@link #scanPendingContainers}
	 * 内按 TTL 复用。
	 */
	public List<ContainerCandidate> findPendingContainers(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) {
			return List.of();
		}
		return scanPendingContainers(mc);
	}

	/**
	 * 全量扫描：收集所有 ≤ CONTAINER_REACH 格需要 IO 的容器候选（输入/输出各位置记录为独立候选），MOVING 饥饿评分用；背包计数
	 * map 按 TTL 复用，双层循环无条件执行
	 */
	private List<ContainerCandidate> scanPendingContainers(MinecraftClient mc) {
		// 派生活动物品集：输入集 = enabled 交易对 giveItem ∪ giveItem2，输出集 = getItem
		// （复用 IoItemDeriver，语义与手工构建一致；编码字符串精确相等判定，与 buildInventorySlotCounts 键空间一致）
		List<TradePair> pairs = TradePairCache.getAll();
		IoItemDeriver.ActiveItemSets activeSets = IoItemDeriver.deriveActiveSets(pairs);
		Set<String> inputItems = activeSets.inputs();
		Set<String> outputItems = activeSets.outputs();

		// 缓存访问器：配置未变时跳过 JSON 解析（仅遍历读取，不改动条目）
		List<ItemIO> entries = ItemIOCache.getAll();
		// 背包计数是慢变量（36 槽遍历 + Registry id 解析 + NBT 匹配，与玩家位置无关）：TTL 内复用缓存，超时/失效后重算并记录时间；
		// 候选循环（距离/阈值/ioKey 拼接，微秒级）每次无条件执行——距离每 tick 新鲜（MOVING 玩家移动）
		long now = mc.world.getTime();
		if (cachedSlotCounts == null || now - cachedAtTick >= Configs.Generic.IDLE_SCAN_INTERVAL.getIntegerValue()) {
			cachedSlotCounts = buildInventorySlotCounts(mc.player, entries, inputItems, outputItems);
			cachedAtTick = now;
		}
		List<ContainerCandidate> result = new ArrayList<>();
		// 外层循环：条目级过滤（行级总开关 + 活动物品集命中），内层循环：位置记录级过滤（记录开关 + 占位 + 维度 + 距离 + 阈值）
		for (ItemIO io : entries) {
			// 条目级启用开关：禁用的条目不参与任何容器 IO（在方向命中检查之前）
			if (!io.isEnabled()) {
				continue;
			}
			boolean isInput = io.isInput();
			// 条目物品必须命中活动物品集：输入条目 ∈ 输入集、输出条目 ∈ 输出集，未命中跳过
			if (isInput ? !inputItems.contains(io.getItem()) : !outputItems.contains(io.getItem())) {
				continue;
			}
			// 内层循环：该条目下每条启用位置记录独立成候选（同一物品多个容器 = 多个候选，各自距离/维度过滤）
			for (ItemIOLocation loc : io.getLocations()) {
				// 位置记录级启用开关：关闭的位置不参与容器 IO
				if (!loc.isEnabled()) {
					continue;
				}
				// 占位坐标 0 0 0 的位置记录不触发容器 IO
				if (loc.getX() == 0 && loc.getY() == 0 && loc.getZ() == 0) {
					continue;
				}
				// 维度过滤：记录指定维度且与当前维度不符时跳过（空串 = 任意维度，恒通过；currentDimensionId 返回 null 时 equals
				// 天然不匹配）
				if (!loc.getDimension().isEmpty()
						&& !loc.getDimension().equals(ContainerIOHelper.currentDimensionId(mc))) {
					continue;
				}
				// 距离只算一次：同时用于可及检查与候选距离（替代旧 needsContainerIO 内的重复计算）
				double distance = ContainerIOHelper.containerDistance(mc, loc);
				if (distance > Configs.Generic.CONTAINER_REACH.getIntegerValue()) {
					continue;
				}
				// 阈值判定内联：输入（从容器取货）槽位数不超过阈值时补货；输出（向容器出货）槽位数达到阈值时清出
				int slots = cachedSlotCounts.getOrDefault(io.getItem(), 0);
				boolean needed = isInput ? (slots <= io.getThreshold()) : (slots >= io.getThreshold());
				if (!needed) {
					continue;
				}
				result.add(new ContainerCandidate(io, loc, isInput, distance));
			}
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

	/** 按指定候选启动容器 IO（MOVING 模式饥饿评分选中后使用；检查器随任务透传，L2 让位检查点用） */
	public boolean startCandidate(ContainerCandidate candidate, Consumer<ContainerIOTask> starter) {
		if (candidate == null) {
			return false;
		}
		// 意图携带位置记录（loc）：开箱坐标取自候选位置记录，与条目坐标解耦
		starter.accept(new ContainerIOTask(
				new ContainerIOTask.IOIntent(candidate.io(), candidate.loc(), candidate.isInput()), competitorChecker));
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
