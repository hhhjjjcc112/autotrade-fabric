package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import java.util.Collections;
import java.util.List;

/**
 * 物品容器 IO 配置的主动缓存：与 {@link TradePairCache} 对称——所有增删改查集中在缓存类， 写路径统一经
 * {@link #persist()} 序列化落盘，外部修改（启动加载 / malilib 重载配置）经 ConfigString
 * 值变更回调自动重同步缓存。
 */
public final class ItemIOCache {
	/** 已解析的物品 IO 条目列表；null = 尚未加载（懒加载） */
	private static List<ItemIO> cache = null;
	/** 值变更回调是否已注册（防重复注册） */
	private static boolean callbackRegistered = false;
	/** 本类自身写回配置时的防重入标志（persist 期间忽略回调重解析） */
	private static boolean persisting = false;

	private ItemIOCache() {
	}

	/** 懒加载：首次访问时从配置解析并注册外部变更回调 */
	private static void ensureLoaded() {
		if (cache == null) {
			cache = ItemIOCodec.fromJson(Configs.Generic.ITEM_IO.getStringValue());
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
		Configs.Generic.ITEM_IO.setValueChangeCallback(c -> onExternalChange(c.getStringValue()));
	}

	/** 外部变更处理：persisting 期间（本类自身写回）跳过，否则全量重解析 */
	private static void onExternalChange(String json) {
		if (persisting) {
			return;
		}
		cache = ItemIOCodec.fromJson(json);
	}

	/** 写回配置：序列化 → 写入配置串（触发回调，persisting 防重入）→ 落盘 */
	private static void persist() {
		persisting = true;
		try {
			Configs.Generic.ITEM_IO.setValueFromString(ItemIOCodec.toJson(cache));
			Configs.saveToFile();
		} finally {
			persisting = false;
		}
	}

	/** 返回全部物品 IO 条目（只读视图，外部不得修改） */
	public static List<ItemIO> getAll() {
		ensureLoaded();
		return Collections.unmodifiableList(cache);
	}

	/** 返回指定下标条目；越界返回 null */
	public static ItemIO get(int index) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return null;
		}
		return cache.get(index);
	}

	/** 返回条目数量 */
	public static int size() {
		ensureLoaded();
		return cache.size();
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
		ensureLoaded();
		int index = findByKey(cache, item, isInput);
		if (index >= 0) {
			cache.set(index, new ItemIO(entry));
		} else {
			cache.add(new ItemIO(entry));
		}
		persist();
	}
}
