package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import java.util.Collections;
import java.util.List;

/**
 * 交易对配置的主动缓存：所有增删改查集中在缓存类，写路径统一经 {@link #persist()} 序列化落盘， 外部修改（启动加载 / malilib
 * 重载配置）经 ConfigString 值变更回调自动重同步缓存。
 */
public final class TradePairCache {
	/** 已解析的交易对列表；null = 尚未加载（懒加载） */
	private static List<TradePair> cache = null;
	/** 值变更回调是否已注册（防重复注册） */
	private static boolean callbackRegistered = false;
	/** 本类自身写回配置时的防重入标志（persist 期间忽略回调重解析） */
	private static boolean persisting = false;

	private TradePairCache() {
	}

	/** 懒加载：首次访问时从配置解析并注册外部变更回调 */
	private static void ensureLoaded() {
		if (cache == null) {
			cache = TradePairCodec.fromJson(Configs.Generic.TRADE_PAIRS.getStringValue());
			registerCallback();
		}
	}

	/** 注册配置值变更回调：配置串被外部修改（启动加载 / malilib 重载）时重解析缓存 */
	private static void registerCallback() {
		if (callbackRegistered) {
			return;
		}
		callbackRegistered = true;
		// setValueFromString 内容变化时同步触发 onValueChanged → onExternalChange
		Configs.Generic.TRADE_PAIRS.setValueChangeCallback(c -> onExternalChange(c.getStringValue()));
	}

	/** 外部变更处理：persisting 期间（本类自身写回）跳过，否则全量重解析 */
	private static void onExternalChange(String json) {
		if (persisting) {
			return;
		}
		cache = TradePairCodec.fromJson(json);
	}

	/** 写回配置：序列化 → 写入配置串（触发回调，persisting 防重入）→ 落盘 */
	private static void persist() {
		persisting = true;
		try {
			Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairCodec.toJson(cache));
			Configs.saveToFile();
		} finally {
			persisting = false;
		}
	}

	/** 返回全部交易对（只读视图，外部不得修改） */
	public static List<TradePair> getAll() {
		ensureLoaded();
		return Collections.unmodifiableList(cache);
	}

	/** 返回指定下标交易对；越界返回 null */
	public static TradePair get(int index) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return null;
		}
		return cache.get(index);
	}

	/** 返回交易对数量 */
	public static int size() {
		ensureLoaded();
		return cache.size();
	}

	/** 在列表末尾新增一个默认启用的单成本交易对，返回新条目下标 */
	public static int add(String give, String get, int limit) {
		ensureLoaded();
		cache.add(new TradePair(give, get, limit, true, "", 0, 0, ""));
		persist();
		return cache.size() - 1;
	}

	/** 在列表末尾新增一个默认启用的双成本交易对（give2 为第二给出物品），返回新条目下标 */
	public static int add(String give, String give2, String get, int limit, int give2Count, int getCount) {
		ensureLoaded();
		cache.add(new TradePair(give, get, limit, true, give2, give2Count, getCount, ""));
		persist();
		return cache.size() - 1;
	}

	/** 删除指定下标的交易对；越界忽略（不落盘） */
	public static void remove(int index) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return;
		}
		cache.remove(index);
		persist();
	}

	/** 用新交易对替换指定下标（存拷贝，外部对象后续修改不影响缓存）；越界忽略（不落盘） */
	public static void update(int index, TradePair pair) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return;
		}
		cache.set(index, new TradePair(pair));
		persist();
	}

	/** 反转指定下标交易对的启用状态；越界忽略（不落盘） */
	public static void toggle(int index) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return;
		}
		TradePair pair = cache.get(index);
		pair.setEnabled(!pair.isEnabled());
		persist();
	}
}
