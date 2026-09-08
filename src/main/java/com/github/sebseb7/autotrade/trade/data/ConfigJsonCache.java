package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import fi.dy.masa.malilib.config.options.ConfigString;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 配置 JSON 缓存的抽象骨架：缓存字段 + 懒加载 + 外部变更回调 + 防重入落盘 + 只读访问。
 * 各缓存类（{@link TradePairCache} / {@link ItemIOCache}）以私有静态单例（本类的匿名子类实例） 承载骨架，公开
 * API 保持全静态门面形态，调用点零改动。
 */
public abstract class ConfigJsonCache<T> {
	/** 已解析的条目列表；null = 尚未加载（懒加载）；同包子类 CRUD 直接操作 */
	protected List<T> cache = null;
	/** 值变更回调是否已注册（防重复注册） */
	private boolean callbackRegistered = false;
	/** 本类自身写回配置时的防重入标志（persist 期间忽略回调重解析） */
	private boolean persisting = false;
	/** 本缓存对应的配置项（TRADE_PAIRS / ITEM_IO） */
	private final ConfigString config;
	/** 从配置 JSON 串解析条目列表 */
	private final Function<String, List<T>> parser;
	/** 把条目列表序列化为配置 JSON 串 */
	private final Function<List<T>, String> serializer;

	/** 构造：绑定配置项与解析/序列化函数（各缓存类以匿名子类单例形态调用） */
	protected ConfigJsonCache(ConfigString config, Function<String, List<T>> parser,
			Function<List<T>, String> serializer) {
		this.config = config;
		this.parser = parser;
		this.serializer = serializer;
	}

	/** 懒加载：首次访问时从配置解析并注册外部变更回调 */
	protected final void ensureLoaded() {
		if (cache == null) {
			cache = parser.apply(config.getStringValue());
			registerCallback();
		}
	}

	/** 注册配置值变更回调：配置串被外部修改（启动加载 / malilib 重载）时重解析缓存 */
	private void registerCallback() {
		if (callbackRegistered) {
			return;
		}
		callbackRegistered = true;
		// setValueFromString 内容变化时同步触发 onValueChanged → onExternalChange
		config.setValueChangeCallback(c -> onExternalChange(c.getStringValue()));
	}

	/** 外部变更处理：persisting 期间（本类自身写回）跳过，否则全量重解析 */
	private void onExternalChange(String json) {
		if (persisting) {
			return;
		}
		cache = parser.apply(json);
	}

	/** 写回配置：序列化 → 写入配置串（触发回调，persisting 防重入）→ 落盘 */
	protected final void persist() {
		persisting = true;
		try {
			config.setValueFromString(serializer.apply(cache));
			Configs.saveToFile();
		} finally {
			persisting = false;
		}
	}

	/** 返回全部条目（只读视图，外部不得修改） */
	protected final List<T> getAll() {
		ensureLoaded();
		return Collections.unmodifiableList(cache);
	}

	/** 返回指定下标条目；越界返回 null */
	protected final T get(int index) {
		ensureLoaded();
		if (index < 0 || index >= cache.size()) {
			return null;
		}
		return cache.get(index);
	}

	/** 返回条目数量 */
	protected final int size() {
		ensureLoaded();
		return cache.size();
	}
}