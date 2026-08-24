package com.github.sebseb7.autotrade.config;

import com.github.sebseb7.autotrade.Reference;
import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.config.options.ConfigOptionListValue;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.IConfigValue;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigDouble;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import java.io.File;

public class Configs implements IConfigHandler {
	private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";

	/** 通用设置页：与具体交易模式无关的选项 */
	public static class Generic {
		public static final ConfigBoolean ENABLED = new ConfigBoolean("enabled", false,
				"Trade with villagers in range when enabled");
		public static final ConfigOptionListValue TRADE_MODE = new ConfigOptionListValue("tradeMode", TradeMode.VOID,
				"Trade mode: Static Trade, Moving Trade, Void Trade");
		public static final ConfigOptionListValue TRADE_EXECUTOR_MODE = new ConfigOptionListValue("tradeExecutorMode",
				ExecutorMode.USE,
				"Trade executor strategy: USE (default, reads offer.getUses() directly; simpler but relies on the local click simulation) or OUTPUT_SLOT (does not read offer uses; snapshot-based remaining)");
		public static final ConfigInteger VILLAGER_SCAN_RANGE = new ConfigInteger("villagerScanRange", 4, 1, 10,
				"Villager search radius (blocks)");
		public static final ConfigInteger OPEN_TIMEOUT = new ConfigInteger("openTimeout", 10, 0, 200,
				"Timeout ticks waiting for a screen to open after interacting (trade screen, container screen, or return trigger block); shared by all screen-open waits");
		public static final ConfigInteger TASK_TIMEOUT = new ConfigInteger("taskTimeout", 400, 0, 300000,
				"Max ticks a single task may run before it is force-aborted and control returns to idle decisions (a failed void return trigger is retried next cycle). Prevents stuck states (e.g. trade offers never syncing). 0 = disabled. Raise if Void Teleport Timeout or Void Unload Delay is set above this value");

		public static final ConfigString TRADE_PAIRS = new ConfigString("tradePairs", "[]",
				"Trade pair list (JSON). Use the in-game GUI to manage.");
		public static final ConfigString ITEM_IO = new ConfigString("itemIO", "[]",
				"Item container IO list (JSON). Use the in-game GUI to manage.");
		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(ENABLED, TRADE_MODE,
				TRADE_EXECUTOR_MODE, VILLAGER_SCAN_RANGE, OPEN_TIMEOUT, TASK_TIMEOUT);
	}

	/** 静止交易设置页：仅静止模式生效的选项 */
	public static class Static {
		public static final ConfigInteger TRADE_INTERVAL = new ConfigInteger("tradeInterval", 100, 20, 1200,
				"Min ticks between trade rounds in static mode (100 ticks = 5 seconds)");
		public static final ConfigInteger CONTAINER_IO_INTERVAL = new ConfigInteger("containerIOInterval", 10, 0, 200,
				"Min ticks between container operations (0 = check every tick)");
		public static final ConfigInteger CONTAINER_IO_IDLE_INTERVAL = new ConfigInteger("containerIOIdleInterval", 5,
				0, 100, "Ticks to wait when no container operation is needed");
		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(TRADE_INTERVAL,
				CONTAINER_IO_INTERVAL, CONTAINER_IO_IDLE_INTERVAL);
	}

	/** 移动交易设置页：仅移动模式生效的选项 */
	public static class Moving {
		public static final ConfigDouble MOVING_RANGE_MULTIPLIER = new ConfigDouble("movingRangeMultiplier", 1.5, 0.5,
				5.0,
				"Multiplier applied to the villager scan range in moving mode (scan radius and the processed-villager invalidation threshold share it); 1.5 = 1.5x the base range");
		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(MOVING_RANGE_MULTIPLIER);
	}

	/** 虚空交易设置页：仅虚空模式生效的选项 */
	public static class Void {
		public static final ConfigInteger VOID_TELEPORT_TIMEOUT = new ConfigInteger("voidTeleportTimeout", 100, 0,
				10000,
				"Timeout ticks waiting for the villager to disappear (player teleport) after the trade screen opened (100 ticks = 5 seconds; 0 = wait indefinitely)");
		public static final ConfigInteger VOID_UNLOAD_DELAY = new ConfigInteger("voidUnloadDelay", 20, 0, 600,
				"Ticks to wait after the villager disappears (player teleport) before trading, letting the server unload the villager's chunk so trade counts are not persisted (20 ticks = 1 second; 0 = trade immediately)");
		public static final ConfigOptionListValue VOID_RETURN_TYPE = new ConfigOptionListValue("voidReturnType",
				ReturnTriggerType.NONE,
				"Void-mode block type used to teleport the player back after a trade round: NONE (disabled), TRAPPED_CHEST, BUTTON, LEVER");
		public static final ConfigCoordinate VOID_RETURN_POS = new ConfigCoordinate("voidReturnPos", "0 0 0",
				"Position of the return trigger block as \"x y z\" (e.g. -13 60 -1)");
		public static final ConfigBoolean VOID_RETURN_STRICT = new ConfigBoolean("voidReturnStrict", true,
				"Strictly validate that the return trigger block type matches the configured type; mismatch is skipped with a warning (default off = only check block existence and distance)");

		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(VOID_TELEPORT_TIMEOUT,
				VOID_UNLOAD_DELAY, VOID_RETURN_TYPE, VOID_RETURN_POS, VOID_RETURN_STRICT);
	}

	public static void loadFromFile() {
		File configFile = new File(FileUtils.getConfigDirectory(), CONFIG_FILE_NAME);

		if (configFile.exists() && configFile.isFile() && configFile.canRead()) {
			JsonElement element = JsonUtils.parseJsonFile(configFile);

			if (element != null && element.isJsonObject()) {
				JsonObject root = element.getAsJsonObject();

				ConfigUtils.readConfigBase(root, "Generic", Generic.OPTIONS);
				ConfigUtils.readConfigBase(root, "Static", Static.OPTIONS);
				ConfigUtils.readConfigBase(root, "Moving", Moving.OPTIONS);
				ConfigUtils.readConfigBase(root, "Void", Void.OPTIONS);
				ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);

				// Read TRADE_PAIRS separately (not in OPTIONS to hide from GUI)
				if (root.has("Generic") && root.getAsJsonObject("Generic").has("tradePairs")) {
					Generic.TRADE_PAIRS
							.setValueFromString(root.getAsJsonObject("Generic").get("tradePairs").getAsString());
				}

				// Read ITEM_IO separately (not in OPTIONS to hide from GUI)
				if (root.has("Generic") && root.getAsJsonObject("Generic").has("itemIO")) {
					Generic.ITEM_IO.setValueFromString(root.getAsJsonObject("Generic").get("itemIO").getAsString());
				}

				// 旧配置迁移：voidReturnX/Y/Z 三个整数合并为 voidReturnPos（"x y z" 字符串）；
				// 仅当新格式缺失而旧格式存在时合成，避免覆盖用户已填写的新坐标
				JsonObject voidGroup = root.has("Void") ? root.getAsJsonObject("Void") : null;
				boolean hasNewPos = voidGroup != null && voidGroup.has("voidReturnPos");
				if (!hasNewPos && root.has("Generic")) {
					JsonObject generic = root.getAsJsonObject("Generic");
					if (generic.has("voidReturnX") && generic.has("voidReturnY") && generic.has("voidReturnZ")) {
						Void.VOID_RETURN_POS.setValueFromString(generic.get("voidReturnX").getAsInt() + " "
								+ generic.get("voidReturnY").getAsInt() + " " + generic.get("voidReturnZ").getAsInt());
					}
				}

				// 旧配置迁移（容器 IO 间隔设置项从 Generic 归位到 Static）：仅当 Static 组缺失而 Generic 组存在时读取，避免覆盖新配置
				JsonObject staticGroup = root.has("Static") ? root.getAsJsonObject("Static") : null;
				JsonObject genericGroup = root.has("Generic") ? root.getAsJsonObject("Generic") : null;
				if (staticGroup != null && genericGroup != null) {
					if (!staticGroup.has("containerIOInterval") && genericGroup.has("containerIOInterval")) {
						Static.CONTAINER_IO_INTERVAL
								.setValueFromString(genericGroup.get("containerIOInterval").getAsString());
					}
					if (!staticGroup.has("containerIOIdleInterval") && genericGroup.has("containerIOIdleInterval")) {
						Static.CONTAINER_IO_IDLE_INTERVAL
								.setValueFromString(genericGroup.get("containerIOIdleInterval").getAsString());
					}
				}
			}
		}

		Generic.ENABLED.setBooleanValue(false);
	}

	public static void saveToFile() {
		File dir = FileUtils.getConfigDirectory();

		if ((dir.exists() && dir.isDirectory()) || dir.mkdirs()) {
			JsonObject root = new JsonObject();

			ConfigUtils.writeConfigBase(root, "Generic", Generic.OPTIONS);
			ConfigUtils.writeConfigBase(root, "Static", Static.OPTIONS);
			ConfigUtils.writeConfigBase(root, "Moving", Moving.OPTIONS);
			ConfigUtils.writeConfigBase(root, "Void", Void.OPTIONS);
			ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);

			// Write TRADE_PAIRS separately
			JsonObject generic = root.getAsJsonObject("Generic");
			if (generic == null) {
				generic = new JsonObject();
				root.add("Generic", generic);
			}
			generic.addProperty("tradePairs", Generic.TRADE_PAIRS.getStringValue());

			// Write ITEM_IO separately
			generic.addProperty("itemIO", Generic.ITEM_IO.getStringValue());

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
}
