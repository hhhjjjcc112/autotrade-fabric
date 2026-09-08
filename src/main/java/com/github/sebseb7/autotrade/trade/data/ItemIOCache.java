package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import java.util.List;

/**
 * 物品容器 IO 配置的主动缓存：与 {@link TradePairCache} 对称——所有增删改查集中在缓存类， 写路径统一经
 * {@link ConfigJsonCache#persist()} 序列化落盘，外部修改（启动加载 / malilib 重载配置）经
 * ConfigString 值变更回调自动重同步缓存。骨架收敛于 {@link ConfigJsonCache}。
 */
public final class ItemIOCache {
	/** 私有静态单例：骨架承载于 ConfigJsonCache 实例，公开静态 API 直接操作其实例字段 */
	private static final ConfigJsonCache<ItemIO> INSTANCE = new ConfigJsonCache<>(Configs.Generic.ITEM_IO,
			ItemIOCodec::fromJson, ItemIOCodec::toJson) {
	};

	private ItemIOCache() {
	}

	/** 返回全部物品 IO 条目（只读视图，外部不得修改） */
	public static List<ItemIO> getAll() {
		return INSTANCE.getAll();
	}

	/** 返回指定下标条目；越界返回 null */
	public static ItemIO get(int index) {
		return INSTANCE.get(index);
	}

	/** 返回条目数量 */
	public static int size() {
		return INSTANCE.size();
	}

	/** 查找首个 (item, 方向) 匹配的条目下标（物品编码串精确相等），无匹配时返回 -1 */
	public static int findByKey(List<ItemIO> items, String item, boolean isInput) {
		for (int i = 0; i < items.size(); i++) {
			ItemIO io = items.get(i);
			if (io.isInput() == isInput && io.getItem().equals(item)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 按 (item, 方向) 更新或追加条目：命中时用 entry 的拷贝替换该条目，未命中时把 entry 的拷贝追加到列表末尾，
	 * 然后统一落盘（存拷贝，外部对象后续修改不影响缓存）。
	 */
	public static void upsert(String item, boolean isInput, ItemIO entry) {
		INSTANCE.ensureLoaded();
		int index = findByKey(INSTANCE.cache, item, isInput);
		if (index >= 0) {
			INSTANCE.cache.set(index, new ItemIO(entry));
		} else {
			INSTANCE.cache.add(new ItemIO(entry));
		}
		INSTANCE.persist();
	}
}