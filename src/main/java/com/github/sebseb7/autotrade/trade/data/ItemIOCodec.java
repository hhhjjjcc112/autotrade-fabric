package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** 物品容器 IO JSON 序列化/反序列化编解码器（缓存与增删改查职责已迁移至 {@link ItemIOCache}） */
public final class ItemIOCodec {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type ITEM_IO_LIST_TYPE = new TypeToken<List<ItemIOData>>() {
	}.getType();

	private ItemIOCodec() {
	}

	/** Gson 序列化用的 JSON 数据类（公有字段，直接映射） */
	public static class ItemIOData {
		public String item;
		public boolean isInput;
		public int x;
		public int y;
		public int z;
		public int threshold;
		public int takeAmount;
		/** 条目启用开关（默认 true；Gson 缺字段时保留构造默认值，旧配置无需迁移） */
		public boolean enabled = true;

		public ItemIOData() {
			this.item = "";
			this.threshold = 1;
			this.takeAmount = 6;
			this.enabled = true;
		}

		public ItemIOData(String item, boolean isInput, int x, int y, int z, int threshold, int takeAmount) {
			this.item = item;
			this.isInput = isInput;
			this.x = x;
			this.y = y;
			this.z = z;
			this.threshold = threshold;
			this.takeAmount = takeAmount;
			this.enabled = true;
		}

		/** 含启用开关的完整构造（序列化用） */
		public ItemIOData(String item, boolean isInput, int x, int y, int z, int threshold, int takeAmount,
				boolean enabled) {
			this.item = item;
			this.isInput = isInput;
			this.x = x;
			this.y = y;
			this.z = z;
			this.threshold = threshold;
			this.takeAmount = takeAmount;
			this.enabled = enabled;
		}
	}

	/** 将物品容器 IO 列表序列化为 JSON 字符串 */
	public static String toJson(List<ItemIO> items) {
		List<ItemIOData> dataList = new ArrayList<>();
		for (ItemIO io : items) {
			dataList.add(new ItemIOData(io.getItem(), io.isInput(), io.getX(), io.getY(), io.getZ(), io.getThreshold(),
					io.getTakeAmount(), io.isEnabled()));
		}
		return GSON.toJson(dataList);
	}

	/** 从 JSON 字符串解析物品容器 IO 列表；非法数据会被过滤并修复默认值 */
	public static List<ItemIO> fromJson(String json) {
		if (json == null || json.isBlank())
			return new ArrayList<>();
		try {
			List<ItemIOData> dataList = GSON.fromJson(json, ITEM_IO_LIST_TYPE);
			if (dataList == null)
				return new ArrayList<>();
			List<ItemIO> result = new ArrayList<>();
			for (ItemIOData d : dataList) {
				// 过滤空 item 条目，避免后续空物品的容器操作
				if (d.item != null && !d.item.isBlank()) {
					if (d.threshold <= 0)
						d.threshold = 1;
					if (d.takeAmount <= 0)
						d.takeAmount = 6;
					// Gson 只覆盖 JSON 中存在的字段：旧配置缺失 enabled 时 d.enabled 保持构造默认 true（向后兼容）
					ItemIO io = new ItemIO(d.item, d.isInput, d.x, d.y, d.z, d.threshold, d.takeAmount);
					io.setEnabled(d.enabled);
					result.add(io);
				}
			}
			return result;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse item IO list JSON", e);
			return new ArrayList<>();
		}
	}
}