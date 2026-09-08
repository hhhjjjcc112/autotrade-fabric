package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * JSON 列表编解码共享骨架：Gson pretty 实例单点 + toJson 输出助手 + fromJson 防御骨架。
 *
 * <p>
 * 各 Codec 的 mapping 循环（TradePair→TradePairData、ItemIO→ItemIOData + 嵌套
 * LocationData）与 legacy 迁移分支 保留在各自类内，此处仅承载两 Codec 相同的 Gson 骨架。
 * </p>
 */
public final class JsonListCodec {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private JsonListCodec() {
	}

	/** 输出助手：将数据列表序列化为 JSON 字符串（仅一行 GSON.toJson + 类型参数） */
	public static <T> String toJson(List<T> dataList) {
		return GSON.toJson(dataList);
	}

	/**
	 * fromJson 防御骨架：null/blank 守卫 + try/catch 警告返回空表；空条目过滤、阈值/默认修复与数据→运行时对象映射由调用方
	 * mapper 完成
	 */
	public static <T, R> List<R> fromJson(String json, Type listType, String warnMessage,
			Function<List<T>, List<R>> mapper) {
		if (json == null || json.isBlank())
			return new ArrayList<>();
		try {
			List<T> dataList = GSON.fromJson(json, listType);
			if (dataList == null)
				return new ArrayList<>();
			return mapper.apply(dataList);
		} catch (Exception e) {
			AutoTrade.logger.warn(warnMessage, e);
			return new ArrayList<>();
		}
	}
}