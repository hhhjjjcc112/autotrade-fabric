package com.github.sebseb7.autotrade.config;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.trade.TradePair;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class TradePairList {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type PAIR_LIST_TYPE = new TypeToken<List<TradePairData>>() {
	}.getType();

	private TradePairList() {
	}

	public static class TradePairData {
		public String give;
		public String get;
		public int limit;
		public boolean enabled;
		public boolean inputEnabled;
		public boolean outputEnabled;
		public int inputX, inputY, inputZ;
		public int outputX, outputY, outputZ;
		public int inputThreshold;
		public int inputTakeAmount;
		public int outputThreshold;
		/** 第二给出物品补货阈值（占用槽位数） */
		public int give2InputThreshold;
		public String give2;
		public boolean give2InputEnabled;
		public int give2InputX, give2InputY, give2InputZ;
		public int give2Count;
		public int getCount;
		public String note;
		public TradePairData() {
			this.give = "";
			this.get = "";
			this.limit = 64;
			this.enabled = true;
			this.inputEnabled = false;
			this.outputEnabled = false;
			this.inputThreshold = 1;
			this.inputTakeAmount = 6;
			this.outputThreshold = 1;
			this.give2InputThreshold = 1;
			this.give2 = "";
			this.give2InputEnabled = false;
			this.give2InputX = 0;
			this.give2InputY = 0;
			this.give2InputZ = 0;
			this.give2Count = 0;
			this.getCount = 0;
		}
		public TradePairData(String give, String get, int limit, boolean enabled) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = enabled;
			this.inputEnabled = false;
			this.outputEnabled = false;
			this.inputThreshold = 1;
			this.inputTakeAmount = 6;
			this.outputThreshold = 1;
			this.give2InputThreshold = 1;
			this.give2 = "";
			this.give2InputEnabled = false;
			this.give2InputX = 0;
			this.give2InputY = 0;
			this.give2InputZ = 0;
			this.give2Count = 0;
			this.getCount = 0;
		}
		public TradePairData(String give, String get, int limit, boolean enabled, boolean inputEnabled, int inputX,
				int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ,
				int inputThreshold, int inputTakeAmount, int outputThreshold) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = enabled;
			this.inputEnabled = inputEnabled;
			this.inputX = inputX;
			this.inputY = inputY;
			this.inputZ = inputZ;
			this.outputEnabled = outputEnabled;
			this.outputX = outputX;
			this.outputY = outputY;
			this.outputZ = outputZ;
			this.inputThreshold = inputThreshold;
			this.inputTakeAmount = inputTakeAmount;
			this.outputThreshold = outputThreshold;
			this.give2InputThreshold = 1;
			this.give2 = "";
			this.give2InputEnabled = false;
			this.give2InputX = 0;
			this.give2InputY = 0;
			this.give2InputZ = 0;
			this.give2Count = 0;
			this.getCount = 0;
		}
		/** 含可选第二给出物品的完整构造，与 TradePair 的 20 参构造一一对应 */
		public TradePairData(String give, String get, int limit, boolean enabled, boolean inputEnabled, int inputX,
				int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ,
				int inputThreshold, int inputTakeAmount, int outputThreshold, String give2, boolean give2InputEnabled,
				int give2InputX, int give2InputY, int give2InputZ) {
			this(give, get, limit, enabled, inputEnabled, inputX, inputY, inputZ, outputEnabled, outputX, outputY,
					outputZ, inputThreshold, inputTakeAmount, outputThreshold);
			this.give2 = give2;
			this.give2InputEnabled = give2InputEnabled;
			this.give2InputX = give2InputX;
			this.give2InputY = give2InputY;
			this.give2InputZ = give2InputZ;
		}
		/** 含每笔交易数量（give2/get）的完整构造，与 TradePair 的 22 参构造一一对应 */
		public TradePairData(String give, String get, int limit, boolean enabled, boolean inputEnabled, int inputX,
				int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ,
				int inputThreshold, int inputTakeAmount, int outputThreshold, String give2, boolean give2InputEnabled,
				int give2InputX, int give2InputY, int give2InputZ, int give2Count, int getCount) {
			this(give, get, limit, enabled, inputEnabled, inputX, inputY, inputZ, outputEnabled, outputX, outputY,
					outputZ, inputThreshold, inputTakeAmount, outputThreshold, give2, give2InputEnabled, give2InputX,
					give2InputY, give2InputZ);
			this.give2Count = give2Count;
			this.getCount = getCount;
		}
	}

	/** 将交易对列表序列化为 JSON 字符串 */
	public static String toJson(List<TradePair> pairs) {
		List<TradePairData> dataList = new ArrayList<>();
		for (TradePair p : pairs) {
			TradePairData d = new TradePairData(p.getGiveItem(), p.getGetItem(), p.getLimit(), p.isEnabled(),
					p.isInputEnabled(), p.getInputX(), p.getInputY(), p.getInputZ(), p.isOutputEnabled(),
					p.getOutputX(), p.getOutputY(), p.getOutputZ(), p.getInputThreshold(), p.getInputTakeAmount(),
					p.getOutputThreshold(), p.getGiveItem2(), p.isGive2InputEnabled(), p.getGive2InputX(),
					p.getGive2InputY(), p.getGive2InputZ(), p.getGive2Count(), p.getGetCount());
			d.note = p.getNote();
			d.give2InputThreshold = p.getGive2InputThreshold();
			dataList.add(d);
		}
		return GSON.toJson(dataList);
	}

	/** 从 JSON 字符串解析交易对列表；非法数据会被过滤并修复默认值 */
	public static List<TradePair> fromJson(String json) {
		if (json == null || json.isBlank())
			return new ArrayList<>();
		try {
			List<TradePairData> dataList = GSON.fromJson(json, PAIR_LIST_TYPE);
			if (dataList == null)
				return new ArrayList<>();
			List<TradePair> result = new ArrayList<>();
			for (TradePairData d : dataList) {
				if (d.give != null && !d.give.isBlank()) {
					if (d.inputThreshold <= 0)
						d.inputThreshold = 1;
					if (d.inputTakeAmount <= 0)
						d.inputTakeAmount = 6;
					if (d.outputThreshold <= 0)
						d.outputThreshold = 1;
					if (d.give2InputThreshold <= 0)
						d.give2InputThreshold = 1;
					// 兼容旧配置：give2 缺失时以空串兜底，避免后续 NPE
					String give2 = d.give2 != null ? d.give2 : "";
					// 防止空物品的补货循环：give2 为空却开启了输入容器时强制关闭
					if (give2.isBlank() && d.give2InputEnabled)
						d.give2InputEnabled = false;
					TradePair pair = new TradePair(d.give, d.get != null ? d.get : "", d.limit, d.enabled,
							d.inputEnabled, d.inputX, d.inputY, d.inputZ, d.outputEnabled, d.outputX, d.outputY,
							d.outputZ, d.inputThreshold, d.inputTakeAmount, d.outputThreshold, give2,
							d.give2InputEnabled, d.give2InputX, d.give2InputY, d.give2InputZ, d.give2Count, d.getCount);
					if (d.note != null)
						pair.setNote(d.note);
					pair.setGive2InputThreshold(d.give2InputThreshold);
					result.add(pair);
				}
			}
			return result;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse trade pair list JSON", e);
			return new ArrayList<>();
		}
	}

	/** 在列表末尾新增一个默认启用的交易对，返回新的 JSON 字符串 */
	public static String addPair(String json, String give, String get, int limit) {
		List<TradePair> pairs = fromJson(json);
		pairs.add(new TradePair(give, get, limit, true, false, 0, 0, 0, false, 0, 0, 0, 1, 6, 1));
		return toJson(pairs);
	}

	/** 在列表末尾新增一个默认启用的双成本交易对（give2 为第二给出物品），返回新的 JSON 字符串 */
	public static String addPair(String json, String give, String give2, String get, int limit, int give2Count,
			int getCount) {
		List<TradePair> pairs = fromJson(json);
		pairs.add(new TradePair(give, get, limit, true, false, 0, 0, 0, false, 0, 0, 0, 1, 6, 1, give2, false, 0, 0, 0,
				give2Count, getCount));
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
