package com.github.sebseb7.autotrade.config;

import com.github.sebseb7.autotrade.Reference;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import java.io.File;

public class Configs implements IConfigHandler {
	private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";

	public static class Generic {
		static boolean isLoading = false;

		public static final ConfigBoolean ENABLED = new ConfigBoolean("enabled", false,
				"Do auto trading with villagers in range");
		public static final ConfigInteger VOID_TRADING_DELAY = new ConfigInteger("voidTradingDelay", 0, 0, 30000000,
				"Delay in ticks for void trading");
		public static final ConfigBoolean VOID_TRADING_DELAY_AFTER_TELEPORT = new ConfigBoolean("delayAfterTeleport",
				false,
				"true: Start the delay after the villager was unloaded; false: Start the delay after the trade has been initiated");
		public static final ConfigInteger CONTAINER_TIMEOUT = new ConfigInteger("containerTimeout", 10, 0, 200,
				"Timeout ticks waiting for container screen to open");
		public static final ConfigInteger TRAP_CHEST_DELAY = new ConfigInteger("trapChestDelay", 5, 0, 100,
				"Extra ticks after opening a trapped chest (signal stabilization)");
		public static final ConfigInteger TRADE_INTERVAL = new ConfigInteger("tradeInterval", 100, 20, 1200,
				"Minimum ticks between villager trade sessions (100 ticks = 5 seconds)");
		public static final ConfigInteger CONTAINER_IO_INTERVAL = new SafeConfigInteger("containerIOInterval", 10, 0,
				200, "Min ticks between container IO operations (0=check every tick)");
		public static final ConfigInteger CONTAINER_IO_IDLE_INTERVAL = new SafeConfigInteger("containerIOIdleInterval",
				5, 0, 100, "Ticks to wait when no container IO is needed");
		public static final ConfigInteger INTERACT_TIMEOUT = new ConfigInteger("interactTimeout", 5, 0, 100,
				"Timeout ticks waiting for trade screen to open");

		public static final ConfigOptionListValue TRADE_MODE = new SafeConfigOptionListValue("tradeMode",
				TradeMode.STATIC, "Trade mode: STATIC, MOVING, VOID");
		public static final ConfigInteger VILLAGER_SCAN_RANGE = new SafeConfigInteger("villagerScanRange", 4, 1, 10,
				"Villager scan radius (blocks)");

		public static final ConfigString TRADE_PAIRS = new ConfigString("tradePairs", "[]",
				"Trade pair list (JSON). Use the in-game GUI to manage.");

		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(ENABLED, TRADE_MODE,
				VILLAGER_SCAN_RANGE, CONTAINER_IO_INTERVAL, CONTAINER_IO_IDLE_INTERVAL, VOID_TRADING_DELAY,
				VOID_TRADING_DELAY_AFTER_TELEPORT, CONTAINER_TIMEOUT, TRAP_CHEST_DELAY, TRADE_INTERVAL,
				INTERACT_TIMEOUT);
	}

	public static void loadFromFile() {
		Generic.isLoading = true;
		File configFile = new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);

		if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
			JsonElement element = JsonUtils.parseJsonFile(configFile);

			if (element != null && element.isJsonObject()) {
				JsonObject root = element.getAsJsonObject();

				ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
				ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);

				// Read TRADE_PAIRS separately (not in OPTIONS to hide from GUI)
				if (root.has("Generic") && root.getAsJsonObject("Generic").has("tradePairs")) {
					Generic.TRADE_PAIRS
							.setValueFromString(root.getAsJsonObject("Generic").get("tradePairs").getAsString());
				}
			}
		}

		Generic.isLoading = false;
		Generic.ENABLED.setBooleanValue(false);
	}

	public static void saveToFile() {
		File dir = FileUtils.getConfigDirectory();

		if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
			JsonObject root = new JsonObject();

			ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
			ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);

			// Write TRADE_PAIRS separately
			JsonObject generic = root.getAsJsonObject("Generic");
			if (generic == null) {
				generic = new JsonObject();
				root.add("Generic", generic);
			}
			generic.addProperty("tradePairs", Generic.TRADE_PAIRS.getStringValue());

			JsonUtils.writeJsonToFile(root, new File(dir, CONFIG_FILE_NAME));
		}
	}

	@Override
	public void load() {
		loadFromFile();
	}

	@Override
	public void save() {
		saveToFile();
	}

	/**
	 * 在启用时拒绝修改值的 ConfigInteger。
	 */
	private static class SafeConfigInteger extends ConfigInteger {
		SafeConfigInteger(String name, int defaultValue, int min, int max, String comment) {
			super(name, defaultValue, min, max, comment);
		}

		@Override
		public void setValueFromString(String value) {
			if (!Generic.isLoading && Generic.ENABLED.getBooleanValue()) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
						"autotrade.message.disable_before_change");
				return;
			}
			super.setValueFromString(value);
		}
	}

	/**
	 * 在启用时拒绝修改值的 ConfigOptionListValue。
	 */
	private static class SafeConfigOptionListValue extends ConfigOptionListValue {
		SafeConfigOptionListValue(String name, TradeMode defaultValue, String comment) {
			super(name, defaultValue, comment);
		}

		@Override
		public void setValueFromString(String value) {
			if (!Generic.isLoading && Generic.ENABLED.getBooleanValue()) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
						"autotrade.message.disable_before_change");
				return;
			}
			super.setValueFromString(value);
		}
	}
}
