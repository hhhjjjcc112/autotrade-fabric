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

	/**
	 * Gson 序列化用的 JSON 数据类（公有字段，直接映射）。
	 *
	 * <p>
	 * 迁移规则①：{@code locations} 为 null 表示旧格式（单坐标、无维度），读取时由 legacy {@code x/y/z}
	 * 迁移为单条记录；非 null（含空列表 {@code []}）表示新格式，空列表保持 0 记录不复活。
	 * </p>
	 */
	public static class ItemIOData {
		public String item;
		public boolean isInput;
		/** 旧格式坐标（Integer 可空：新格式写入时置 null，Gson 默认省略 null 字段，输出干净的新 schema） */
		public Integer x;
		public Integer y;
		public Integer z;
		public int threshold;
		public int takeAmount;
		/** 条目启用开关（行级总开关；默认 true；Gson 缺字段时保留构造默认值，旧配置无需迁移） */
		public boolean enabled = true;
		/** 位置记录列表（新格式；null = 旧格式单坐标，[] = 新格式 0 记录） */
		public List<LocationData> locations;

		public ItemIOData() {
			this.item = "";
			this.threshold = 1;
			this.takeAmount = 6;
			this.enabled = true;
		}

		/** 全参构造（序列化用）：locations 全量输出，legacy x/y/z 置 null（Gson 省略） */
		public ItemIOData(String item, boolean isInput, List<LocationData> locations, int threshold, int takeAmount,
				boolean enabled) {
			this.item = item;
			this.isInput = isInput;
			this.x = null;
			this.y = null;
			this.z = null;
			this.locations = locations;
			this.threshold = threshold;
			this.takeAmount = takeAmount;
			this.enabled = enabled;
		}

		/** 位置记录 JSON 数据类（公有字段，直接映射；dimension 缺省 null → 读取时归一为空串 = 任意维度） */
		public static class LocationData {
			public String dimension;
			public Integer x;
			public Integer y;
			public Integer z;
			/** 记录级启用开关（默认 true；Gson 缺字段时保留构造默认值） */
			public boolean enabled = true;

			public LocationData() {
				this.enabled = true;
			}

			/** 全参构造（序列化用） */
			public LocationData(String dimension, Integer x, Integer y, Integer z, boolean enabled) {
				this.dimension = dimension;
				this.x = x;
				this.y = y;
				this.z = z;
				this.enabled = enabled;
			}
		}
	}

	/** 将物品容器 IO 列表序列化为 JSON 字符串（仅输出新 schema：locations 全量，0 记录写 []） */
	public static String toJson(List<ItemIO> items) {
		List<ItemIOData> dataList = new ArrayList<>();
		for (ItemIO io : items) {
			List<ItemIOData.LocationData> locations = new ArrayList<>();
			for (ItemIOLocation loc : io.getLocations()) {
				locations.add(new ItemIOData.LocationData(loc.getDimension(), loc.getX(), loc.getY(), loc.getZ(),
						loc.isEnabled()));
			}
			dataList.add(new ItemIOData(io.getItem(), io.isInput(), locations, io.getThreshold(), io.getTakeAmount(),
					io.isEnabled()));
		}
		return GSON.toJson(dataList);
	}

	/** 从 JSON 字符串解析物品容器 IO 列表（双格式兼容：旧格式自动迁移）；非法数据会被过滤并修复默认值 */
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
					List<ItemIOLocation> locationsList = new ArrayList<>();
					// 迁移规则①：locations == null = 旧格式（单坐标、无维度）→ 由 legacy x/y/z（null→0）迁移为单条记录；
					// 非 null（含空列表 []）= 新格式 → 逐条构造；空列表保持 0 记录，不得回退 legacy 分支（否则复活 0 0 0 幻影记录）
					if (d.locations == null) {
						// 迁移规则②：旧条目 record.enabled 恒 true（不复制 d.enabled——行级总开关承载旧整行禁用语义，
						// 否则 AND 语义下旧禁用行重开行级后仍被记录级禁用卡住）；行级 enabled = d.enabled
						locationsList.add(new ItemIOLocation("", d.x == null ? 0 : d.x, d.y == null ? 0 : d.y,
								d.z == null ? 0 : d.z, true));
					} else {
						for (ItemIOData.LocationData ld : d.locations) {
							// dimension null → ""（任意维度）；enabled 缺省 true（Gson 缺字段保留构造默认值）
							locationsList.add(new ItemIOLocation(ld.dimension == null ? "" : ld.dimension,
									ld.x == null ? 0 : ld.x, ld.y == null ? 0 : ld.y, ld.z == null ? 0 : ld.z,
									ld.enabled));
						}
					}
					ItemIO io = new ItemIO(d.item, d.isInput, locationsList, d.threshold, d.takeAmount);
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