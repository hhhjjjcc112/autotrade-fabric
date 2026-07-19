package com.github.sebseb7.autotrade.util;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.event.TradePair;
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
		public TradePairData() {
			this.give = "";
			this.get = "";
			this.limit = 64;
			this.enabled = true;
		}
		public TradePairData(String give, String get, int limit) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = true;
		}
		public TradePairData(String give, String get, int limit, boolean enabled) {
			this.give = give;
			this.get = get;
			this.limit = limit;
			this.enabled = enabled;
		}
	}

	public static String toJson(List<TradePair> pairs) {
		List<TradePairData> dataList = new ArrayList<>();
		for (TradePair p : pairs) {
			dataList.add(new TradePairData(p.getGiveItem(), p.getGetItem(), p.getLimit(), p.isEnabled()));
		}
		return GSON.toJson(dataList);
	}

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
					result.add(new TradePair(d.give, d.get != null ? d.get : "", d.limit, d.enabled));
				}
			}
			return result;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse trade pair list JSON", e);
			return new ArrayList<>();
		}
	}

	public static String addPair(String json, String give, String get, int limit) {
		List<TradePair> pairs = fromJson(json);
		pairs.add(new TradePair(give, get, limit, true));
		return toJson(pairs);
	}

	public static String removePair(String json, int index) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			pairs.remove(index);
		}
		return toJson(pairs);
	}

	public static String updatePair(String json, int index, String give, String get, int limit) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			pairs.set(index, new TradePair(give, get, limit, pairs.get(index).isEnabled()));
		}
		return toJson(pairs);
	}

	public static String togglePair(String json, int index) {
		List<TradePair> pairs = fromJson(json);
		if (index >= 0 && index < pairs.size()) {
			TradePair old = pairs.get(index);
			pairs.set(index, new TradePair(old.getGiveItem(), old.getGetItem(), old.getLimit(), !old.isEnabled()));
		}
		return toJson(pairs);
	}
}
