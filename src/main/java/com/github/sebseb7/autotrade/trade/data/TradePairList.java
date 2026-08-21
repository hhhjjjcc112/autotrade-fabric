package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class TradePairList {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type PAIR_LIST_TYPE = new TypeToken<List<TradePairData>>() {
	}.getType();

	/** 旧版容器 IO 配置的 warn 已发送标志（进程内只提示一次，防止 fromJson 每 tick 调用时刷屏） */
	private static boolean legacyIoWarned = false;

	private TradePairList() {
	}

	public static class TradePairData {
		public String give;
		public String get;
		public int limit;
		public boolean enabled;
		public String give2;
		public int give2Count;
		public int getCount;
		public String note;
		public TradePairData() {
			this.give = "";
			this.get = "";
			this.limit = 64;
			this.enabled = true;
			this.give2 = "";
			this.give2Count = 0;
			this.getCount = 0;
			this.note = "";
		}
		public TradePairData(String give, String get, int limit, boolean enabled) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = enabled;
			this.give2 = "";
			this.give2Count = 0;
			this.getCount = 0;
			this.note = "";
		}
		/** 与 TradePair 构造一一对应的完整构造 */
		public TradePairData(String give, String get, int limit, boolean enabled, String give2, int give2Count,
				int getCount, String note) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = enabled;
			this.give2 = give2;
			this.give2Count = give2Count;
			this.getCount = getCount;
			this.note = note;
		}
	}

	/** 将交易对列表序列化为 JSON 字符串 */
	public static String toJson(List<TradePair> pairs) {
		List<TradePairData> dataList = new ArrayList<>();
		for (TradePair p : pairs) {
			TradePairData d = new TradePairData(p.getGiveItem(), p.getGetItem(), p.getLimit(), p.isEnabled(),
					p.getGiveItem2(), p.getGive2Count(), p.getGetCount(), p.getNote());
			dataList.add(d);
		}
		return GSON.toJson(dataList);
	}

	/** 从 JSON 字符串解析交易对列表；非法数据会被过滤并修复默认值 */
	public static List<TradePair> fromJson(String json) {
		if (json == null || json.isBlank())
			return new ArrayList<>();
		try {
			checkLegacyIoConfig(json);
			List<TradePairData> dataList = GSON.fromJson(json, PAIR_LIST_TYPE);
			if (dataList == null)
				return new ArrayList<>();
			List<TradePair> result = new ArrayList<>();
			for (TradePairData d : dataList) {
				if (d.give != null && !d.give.isBlank()) {
					// 兼容旧配置：give2 缺失时以空串兜底，避免后续 NPE
					String give2 = d.give2 != null ? d.give2 : "";
					String note = d.note != null ? d.note : "";
					TradePair pair = new TradePair(d.give, d.get != null ? d.get : "", d.limit, d.enabled, give2,
							d.give2Count, d.getCount, note);
					result.add(pair);
				}
			}
			return result;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse trade pair list JSON", e);
			return new ArrayList<>();
		}
	}

	/** 检测旧版容器 IO 配置（内嵌于交易对的 input/output/give2Input 字段），命中时进程内只 warn 一次 */
	private static void checkLegacyIoConfig(String json) {
		if (legacyIoWarned)
			return;
		try {
			JsonArray array = JsonParser.parseString(json).getAsJsonArray();
			int legacyCount = 0;
			for (JsonElement el : array) {
				if (el.isJsonObject() && hasLegacyIoConfig(el.getAsJsonObject()))
					legacyCount++;
			}
			if (legacyCount > 0) {
				legacyIoWarned = true;
				AutoTrade.logger.warn("[AutoTrade] 检测到旧版容器 IO 配置（" + legacyCount + " 个交易对），请打开 Item IO 界面重新配置");
			}
		} catch (Exception ignored) {
			// 解析失败时静默忽略，后续 GSON.fromJson 会处理并告警
		}
	}

	/** 判断单个交易对 JSON 对象是否含非默认值的旧版容器 IO 字段 */
	private static boolean hasLegacyIoConfig(JsonObject obj) {
		if (getBoolean(obj, "inputEnabled") || getBoolean(obj, "outputEnabled") || getBoolean(obj, "give2InputEnabled"))
			return true;
		return hasNonZeroCoord(obj, "inputX") || hasNonZeroCoord(obj, "inputY") || hasNonZeroCoord(obj, "inputZ")
				|| hasNonZeroCoord(obj, "outputX") || hasNonZeroCoord(obj, "outputY") || hasNonZeroCoord(obj, "outputZ")
				|| hasNonZeroCoord(obj, "give2InputX") || hasNonZeroCoord(obj, "give2InputY")
				|| hasNonZeroCoord(obj, "give2InputZ");
	}

	/** 读取布尔字段；缺失或非布尔时返回 false */
	private static boolean getBoolean(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
	}

	/** 读取整数坐标字段；缺失或非数字时视为 0 */
	private static boolean hasNonZeroCoord(JsonObject obj, String key) {
		JsonElement el = obj.get(key);
		return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() && el.getAsInt() != 0;
	}

	/** 在列表末尾新增一个默认启用的交易对，返回新的 JSON 字符串 */
	public static String addPair(String json, String give, String get, int limit) {
		List<TradePair> pairs = fromJson(json);
		pairs.add(new TradePair(give, get, limit, true, "", 0, 0, ""));
		return toJson(pairs);
	}

	/** 在列表末尾新增一个默认启用的双成本交易对（give2 为第二给出物品），返回新的 JSON 字符串 */
	public static String addPair(String json, String give, String give2, String get, int limit, int give2Count,
			int getCount) {
		List<TradePair> pairs = fromJson(json);
		pairs.add(new TradePair(give, get, limit, true, give2, give2Count, getCount, ""));
		return toJson(pairs);
	}

	/** 删除指定下标的交易对，返回新的 JSON 字符串 */
	public static String removePair(String json, int index) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			pairs.remove(index);
		}
		return toJson(pairs);
	}

	/** 用新的交易对替换指定下标，返回新的 JSON 字符串 */
	public static String updatePair(String json, int index, TradePair pair) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			pairs.set(index, pair);
		}
		return toJson(pairs);
	}

	/** 反转指定下标交易对的启用状态，返回新的 JSON 字符串 */
	public static String togglePair(String json, int index) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			TradePair old = pairs.get(index);
			old.setEnabled(!old.isEnabled());
		}
		return toJson(pairs);
	}

}