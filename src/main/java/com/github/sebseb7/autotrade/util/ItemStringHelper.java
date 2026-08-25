package com.github.sebseb7.autotrade.util;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.nbt.visitor.StringNbtWriter;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * 物品（含 NBT）与配置字符串之间序列化/反序列化的辅助类，基于 Gson JSON。
 *
 * <p>
 * 格式：{@code {"id":"minecraft:nether_star"}}（无 NBT）或
 * {@code {"id":"minecraft:nether_star","nbt":"{Damage:0}"}}（带 NBT）。
 *
 * <p>
 * 为兼容旧版本，{@link #getItemId(String)} 与 {@link #getNbt(String)} 仍接受旧的
 * {@code id||nbt} 格式。
 */
public final class ItemStringHelper {
	private static final Gson GSON = new GsonBuilder().create();

	private ItemStringHelper() {
	}

	/** Gson 序列化用的 JSON 数据类 */
	private static final class ItemData {
		String id;
		String nbt; // 无 NBT 时为 null
	}

	/** 将 ItemStack 编码为 JSON 配置字符串，保留 NBT 数据 */
	public static String encode(ItemStack stack) {
		if (stack.isEmpty()) {
			return "";
		}
		ItemData data = new ItemData();
		data.id = Registries.ITEM.getId(stack.getItem()).toString();
		NbtCompound nbt = stack.getNbt();
		if (nbt != null && !nbt.isEmpty()) {
			data.nbt = new StringNbtWriter().apply(nbt);
		}
		return GSON.toJson(data);
	}

	/** 从编码字符串中提取物品 ID（namespace:path）部分 */
	public static String getItemId(String encoded) {
		if (encoded == null || encoded.isBlank())
			return "";
		// 新的 Gson JSON 格式
		try {
			ItemData data = GSON.fromJson(encoded, ItemData.class);
			return data != null && data.id != null ? data.id : "";
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse item ID from '{}'", encoded);
			return "";
		}
	}

	/** 从编码字符串中提取 NBT 复合标签；无 NBT 或解析失败时返回 null */
	public static NbtCompound getNbt(String encoded) {
		if (encoded == null || encoded.isBlank())
			return null;
		String nbtStr;
		try {
			ItemData data = GSON.fromJson(encoded, ItemData.class);
			nbtStr = data != null ? data.nbt : null;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse item NBT from '{}'", encoded);
			return null;
		}
		if (nbtStr == null || nbtStr.isEmpty())
			return null;
		try {
			return StringNbtReader.parse(nbtStr);
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse NBT from '{}'", encoded, e);
			return null;
		}
	}

	/**
	 * 将 encoded 字符串解码为 ItemStack。无效或无法解析时返回 EMPTY。
	 */
	public static ItemStack decode(String encoded) {
		if (encoded == null || encoded.isBlank())
			return ItemStack.EMPTY;
		try {
			ItemData data = GSON.fromJson(encoded, ItemData.class);
			if (data == null || data.id == null)
				return ItemStack.EMPTY;
			Identifier id = Identifier.tryParse(data.id);
			if (id == null)
				return ItemStack.EMPTY;
			Item item = Registries.ITEM.get(id);
			if (item == null)
				return ItemStack.EMPTY;
			ItemStack stack = new ItemStack(item);
			if (data.nbt != null && !data.nbt.isEmpty()) {
				try {
					stack.setNbt(StringNbtReader.parse(data.nbt));
				} catch (Exception e) {
					// NBT 解析失败，返回无 NBT 的 ItemStack
				}
			}
			return stack;
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	/**
	 * 预解析的物品匹配数据：encoded 为原始编码串（供调用方回填 slotCounts 键），id 为物品 ID，nbt 可为 null（无 NBT）。
	 */
	public record ParsedItem(String encoded, String id, NbtCompound nbt) {
	}

	/**
	 * 将编码串一次性预解析为 ParsedItem（ID + 可选 NBT），供循环外复用；非法编码（null/空白/非 JSON）返回 null。 解析语义与
	 * {@link #getItemId}/{@link #getNbt} 等价（含 NBT 解析失败视为无 NBT 的旧行为）。
	 */
	public static ParsedItem parse(String encoded) {
		if (encoded == null || encoded.isBlank()) {
			return null;
		}
		String id;
		String nbtStr;
		try {
			// 单次 GSON 反序列化同时取出 id 与 nbt 字符串
			ItemData data = GSON.fromJson(encoded, ItemData.class);
			if (data == null || data.id == null) {
				return null;
			}
			id = data.id;
			nbtStr = data.nbt;
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse item from '{}'", encoded);
			return null;
		}
		NbtCompound nbt = null;
		if (nbtStr != null && !nbtStr.isEmpty()) {
			try {
				nbt = StringNbtReader.parse(nbtStr);
			} catch (Exception e) {
				// NBT 解析失败：与 getNbt 的失败语义一致，视为无 NBT（仅按 ID 匹配）
				AutoTrade.logger.warn("[AutoTrade] Failed to parse NBT from '{}'", encoded, e);
			}
		}
		return new ParsedItem(encoded, id, nbt);
	}

	/**
	 * 判断 ItemStack 是否与预解析后的物品匹配：比较物品 ID，若条目带 NBT 则同时比较 NBT 数据。
	 */
	public static boolean matches(ItemStack stack, ParsedItem expected) {
		if (stack.isEmpty() || expected == null) {
			return false;
		}

		String actualId = Registries.ITEM.getId(stack.getItem()).toString();
		if (!actualId.equals(expected.id())) {
			return false;
		}

		if (expected.nbt() == null) {
			return true;
		}

		NbtCompound actualNbt = stack.getNbt();
		return actualNbt != null && !actualNbt.isEmpty() && expected.nbt().equals(actualNbt);
	}

	/**
	 * 判断 ItemStack 是否与编码后的配置字符串匹配（委托预解析路径，行为与直接比较等价）。
	 */
	public static boolean matches(ItemStack stack, String encoded) {
		return matches(stack, parse(encoded));
	}
}
