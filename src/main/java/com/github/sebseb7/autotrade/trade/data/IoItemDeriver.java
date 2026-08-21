package com.github.sebseb7.autotrade.trade.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从交易对数据派生物品 IO 配置项的纯数据辅助类（不依赖任何 Minecraft 客户端类）。
 *
 * <p>
 * IO 输入/输出选项卡的条目完全由交易对自动派生：输入组 = 全部交易对（含禁用）的 giveItem ∪ giveItem2，输出组 =
 * getItem，均跳过 blank/air 物品；每个物品附带 「被 N 个启用交易对 / M 个禁用交易对使用」的计数（同一交易对内 give 与
 * give2 相同只计 1 次）。
 * </p>
 *
 * <p>
 * 本类只负责派生与统计，不负责排序 —— 排序（启用数降序等）由 IO 选项卡负责； 派生结果保持物品首次出现顺序。
 * </p>
 */
public final class IoItemDeriver {

	private IoItemDeriver() {
	}

	/**
	 * 单个物品的派生统计：item 为 ItemStringHelper 编码串，enabledCount/disabledCount 为使用该物品的交易对数
	 */
	public record IoItemStat(String item, int enabledCount, int disabledCount) {
	}

	/** 派生结果：输入组（give ∪ give2）与输出组（getItem）的统计列表 */
	public record DerivedIo(List<IoItemStat> inputs, List<IoItemStat> outputs) {
	}

	/** 仅启用交易对的物品集合（运行时容器 IO 匹配用，语义与 ContainerIOHelper 一致） */
	public record ActiveItemSets(Set<String> inputs, Set<String> outputs) {
	}

	/**
	 * 派生输入/输出物品统计：输入组 = 全部交易对（含禁用）giveItem ∪ giveItem2，输出组 = getItem； 跳过
	 * blank/air，按物品首次出现顺序去重，不排序。
	 */
	public static DerivedIo derive(List<TradePair> pairs) {
		List<IoItemStat> inputs = buildStats(pairs, true);
		List<IoItemStat> outputs = buildStats(pairs, false);
		return new DerivedIo(inputs, outputs);
	}

	/**
	 * 派生仅启用交易对的物品集合：输入集 = 启用交易对 giveItem ∪ giveItem2，输出集 = getItem。 语义与
	 * ContainerIOHelper.findPendingContainers 的集合构建逐字对齐（供运行时复用）。
	 */
	public static ActiveItemSets deriveActiveSets(List<TradePair> pairs) {
		Set<String> inputItems = new HashSet<>();
		Set<String> outputItems = new HashSet<>();
		for (TradePair p : pairs) {
			if (!p.isEnabled())
				continue;
			inputItems.add(p.getGiveItem());
			inputItems.add(p.getGiveItem2());
			outputItems.add(p.getGetItem());
		}
		return new ActiveItemSets(inputItems, outputItems);
	}

	/**
	 * 按方向统计一组物品：input 为 true 统计输入组（give ∪ give2），false 统计输出组（getItem）。 order
	 * 保存物品首次出现顺序，counts 统计每物品 [启用数, 禁用数]。
	 */
	private static List<IoItemStat> buildStats(List<TradePair> pairs, boolean input) {
		LinkedHashSet<String> order = new LinkedHashSet<>();
		Map<String, int[]> counts = new HashMap<>();
		for (TradePair p : pairs) {
			// 同一交易对内去重后再计入统计（输入组 = give ∪ give2，输出组 = getItem；give 与 give2 相同只算 1 次）
			LinkedHashSet<String> unique = new LinkedHashSet<>();
			if (input) {
				addIfUsable(unique, p.getGiveItem());
				addIfUsable(unique, p.getGiveItem2());
			} else {
				addIfUsable(unique, p.getGetItem());
			}
			for (String item : unique) {
				order.add(item);
				int[] c = counts.computeIfAbsent(item, k -> new int[2]);
				if (p.isEnabled())
					c[0]++;
				else
					c[1]++;
			}
		}
		List<IoItemStat> result = new ArrayList<>(order.size());
		for (String item : order) {
			int[] c = counts.get(item);
			result.add(new IoItemStat(item, c[0], c[1]));
		}
		return result;
	}

	/** 收集有效物品（跳过 null/blank/air），供去重与统计使用 */
	private static void addIfUsable(Set<String> set, String item) {
		if (item != null && !item.isBlank() && !isAir(item)) {
			set.add(item);
		}
	}

	/** 判断编码串是否为空气物品（{"id":"minecraft:air"}，兼容旧版裸 id 格式）；解析失败按非 air 处理 */
	private static boolean isAir(String encoded) {
		if (encoded == null || encoded.isBlank())
			return true;
		if (encoded.equals("minecraft:air"))
			return true;
		try {
			JsonObject obj = JsonParser.parseString(encoded).getAsJsonObject();
			return obj.has("id") && "minecraft:air".equals(obj.get("id").getAsString());
		} catch (Exception e) {
			return false;
		}
	}
}
