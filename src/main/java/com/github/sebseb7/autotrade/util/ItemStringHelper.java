package com.github.sebseb7.autotrade.util;

import com.github.sebseb7.autotrade.AutoTrade;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.nbt.visitor.StringNbtWriter;
import net.minecraft.registry.Registries;

/**
 * Helper for serializing/deserializing items (with NBT) to/from config strings
 * using Gson JSON.
 *
 * <p>
 * Format: {@code {"id":"minecraft:nether_star"}} (no NBT) or
 * {@code {"id":"minecraft:nether_star","nbt":"{Damage:0}"}} (with NBT).
 *
 * <p>
 * For backward compatibility, the old {@code id||nbt} format is still accepted
 * by {@link #getItemId(String)} and {@link #getNbt(String)}.
 */
public final class ItemStringHelper {
	private static final String OLD_SEPARATOR = "||";
	private static final Gson GSON = new GsonBuilder().create();
	private static final StringNbtWriter nbtWriter = new StringNbtWriter();

	private ItemStringHelper() {
	}

	/**
	 * JSON data class for Gson serialization.
	 */
	private static final class ItemData {
		String id;
		String nbt; // null when there is no NBT
	}

	/**
	 * Encodes an ItemStack into a JSON config string, preserving NBT data.
	 */
	public static String encode(ItemStack stack) {
		if (stack.isEmpty()) {
			return "";
		}
		ItemData data = new ItemData();
		data.id = Registries.ITEM.getId(stack.getItem()).toString();
		NbtCompound nbt = stack.getNbt();
		if (nbt != null && !nbt.isEmpty()) {
			data.nbt = nbtWriter.apply(nbt);
		}
		return GSON.toJson(data);
	}

	/**
	 * Extracts the item ID portion (namespace:path) from an encoded string.
	 */
	public static String getItemId(String encoded) {
		if (encoded == null || encoded.isBlank())
			return "";
		// Backward compatibility: old "id||nbt" format
		if (encoded.contains(OLD_SEPARATOR)) {
			int sep = encoded.indexOf(OLD_SEPARATOR);
			return encoded.substring(0, sep).trim();
		}
		// New Gson JSON format
		try {
			ItemData data = GSON.fromJson(encoded, ItemData.class);
			return data != null && data.id != null ? data.id : "";
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to parse item ID from '{}'", encoded);
			return "";
		}
	}

	/**
	 * Extracts the NBT compound from an encoded string, or null if none.
	 */
	public static NbtCompound getNbt(String encoded) {
		if (encoded == null || encoded.isBlank())
			return null;
		String nbtStr = null;
		// Backward compatibility: old "id||nbt" format
		if (encoded.contains(OLD_SEPARATOR)) {
			int sep = encoded.indexOf(OLD_SEPARATOR);
			nbtStr = encoded.substring(sep + OLD_SEPARATOR.length()).trim();
		} else {
			// New Gson JSON format
			try {
				ItemData data = GSON.fromJson(encoded, ItemData.class);
				nbtStr = data != null ? data.nbt : null;
			} catch (Exception e) {
				AutoTrade.logger.warn("[AutoTrade] Failed to parse item NBT from '{}'", encoded);
				return null;
			}
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
	 * Checks if an ItemStack matches an encoded config string. Compares item ID and
	 * (if NBT is encoded) NBT data.
	 */
	public static boolean matches(ItemStack stack, String encoded) {
		if (stack.isEmpty() || encoded == null || encoded.isBlank()) {
			AutoTrade.logger.info("[AutoTrade] ItemStringHelper.matches: stack empty or encoded blank");
			return false;
		}

		String expectedId = getItemId(encoded);
		String actualId = Registries.ITEM.getId(stack.getItem()).toString();
		if (!actualId.equals(expectedId)) {
			AutoTrade.logger.info("[AutoTrade] ItemStringHelper.matches: item ID mismatch expected={} actual={}",
					expectedId, actualId);
			return false;
		}

		NbtCompound expectedNbt = getNbt(encoded);
		if (expectedNbt == null) {
			AutoTrade.logger.info("[AutoTrade] ItemStringHelper.matches: ID match {} (no NBT constraint) -> MATCH",
					actualId);
			return true; // No NBT constraint, match by ID only
		}

		NbtCompound actualNbt = stack.getNbt();

		if (actualNbt == null || actualNbt.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] ItemStringHelper.matches: expected NBT but stack has none for {}",
					actualId);
			return false;
		}

		return expectedNbt.equals(actualNbt);
	}
}
