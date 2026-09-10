package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import java.util.List;

/**
 * 交易对配置的主动缓存：所有增删改查集中在缓存类，写路径统一经 {@link ConfigJsonCache#persist()} 序列化落盘，
 * 外部修改（启动加载 / malilib 重载配置）经 ConfigString 值变更回调自动重同步缓存。 骨架（懒加载/回调/防重入落盘）收敛于
 * {@link ConfigJsonCache}，本类以静态门面 + 私有静态单例承载 CRUD。
 */
public final class TradePairCache {
	/** 私有静态单例：骨架承载于 ConfigJsonCache 实例，公开静态 API 直接操作其实例字段 */
	private static final ConfigJsonCache<TradePair> INSTANCE = new ConfigJsonCache<>(Configs.Generic.TRADE_PAIRS,
			TradePairCodec::fromJson, TradePairCodec::toJson) {
	};

	private TradePairCache() {
	}

	/** 返回全部交易对（只读视图，外部不得修改） */
	public static List<TradePair> getAll() {
		return INSTANCE.getAll();
	}

	/** 返回指定下标交易对；越界返回 null */
	public static TradePair get(int index) {
		return INSTANCE.get(index);
	}

	/** 返回交易对数量 */
	public static int size() {
		return INSTANCE.size();
	}

	/** 在列表末尾新增一个默认启用的单成本交易对，返回新条目下标 */
	public static int add(String give, String get, int limit) {
		INSTANCE.ensureLoaded();
		INSTANCE.cache.add(new TradePair(give, get, limit, true, "", 0, 0, ""));
		INSTANCE.persist();
		return INSTANCE.cache.size() - 1;
	}

	/** 在列表末尾新增一个默认启用的双成本交易对（give2 为第二给出物品），返回新条目下标 */
	public static int add(String give, String give2, String get, int limit, int give2Count, int getCount) {
		INSTANCE.ensureLoaded();
		INSTANCE.cache.add(new TradePair(give, get, limit, true, give2, give2Count, getCount, ""));
		INSTANCE.persist();
		return INSTANCE.cache.size() - 1;
	}

	/** 删除指定下标的交易对；越界忽略（不落盘） */
	public static void remove(int index) {
		INSTANCE.ensureLoaded();
		if (index < 0 || index >= INSTANCE.cache.size()) {
			return;
		}
		INSTANCE.cache.remove(index);
		INSTANCE.persist();
	}

	/** 用新交易对替换指定下标（存拷贝，外部对象后续修改不影响缓存）；越界忽略（不落盘） */
	public static void update(int index, TradePair pair) {
		INSTANCE.ensureLoaded();
		if (index < 0 || index >= INSTANCE.cache.size()) {
			return;
		}
		INSTANCE.cache.set(index, new TradePair(pair));
		INSTANCE.persist();
	}

	/** 反转指定下标交易对的启用状态；越界忽略（不落盘） */
	public static void toggle(int index) {
		INSTANCE.ensureLoaded();
		if (index < 0 || index >= INSTANCE.cache.size()) {
			return;
		}
		TradePair pair = INSTANCE.cache.get(index);
		pair.setEnabled(!pair.isEnabled());
		INSTANCE.persist();
	}

	/** 批量设置全部交易对的启用状态（幂等：统一覆盖写目标值；空列表按 no-op 返回不落盘）；持久化路径与 toggle 一致 */
	public static void enableAll(boolean enabled) {
		INSTANCE.ensureLoaded();
		if (INSTANCE.cache.isEmpty()) {
			return;
		}
		for (TradePair pair : INSTANCE.cache) {
			pair.setEnabled(enabled);
		}
		INSTANCE.persist();
	}
}