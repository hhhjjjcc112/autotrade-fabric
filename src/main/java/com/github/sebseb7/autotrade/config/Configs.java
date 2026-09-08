package com.github.sebseb7.autotrade.config;

import com.github.sebseb7.autotrade.Reference;
import com.github.sebseb7.autotrade.config.options.ConfigCoordinate;
import com.github.sebseb7.autotrade.config.options.ConfigJsonArray;
import com.github.sebseb7.autotrade.config.options.ConfigOptionListValue;
import com.github.sebseb7.autotrade.render.HudPosition;
import com.github.sebseb7.autotrade.trade.executor.TradeExecutorMode;
import com.github.sebseb7.autotrade.trade.mode.ReturnTriggerType;
import com.github.sebseb7.autotrade.trade.mode.TradeMode;
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
				TradeExecutorMode.USE,
				"Trade executor strategy: USE (default, reads offer.getUses() directly; simpler but relies on the local click simulation) or OUTPUT_SLOT (does not read offer uses; snapshot-based remaining)");
		public static final ConfigInteger VILLAGER_SCAN_RANGE = new ConfigInteger("villagerScanRange", 4, 1, 10,
				"Villager search radius (blocks)");
		public static final ConfigInteger OPEN_TIMEOUT = new ConfigInteger("openTimeout", 10, 0, 200,
				"Timeout ticks waiting for a screen to open after interacting (trade screen, container screen, or return trigger block); shared by all screen-open waits");
		public static final ConfigInteger TASK_TIMEOUT = new ConfigInteger("taskTimeout", 400, 0, 300000,
				"Max ticks a single task may run before it is force-aborted and control returns to idle decisions (a failed void return trigger is retried next cycle). Prevents stuck states (e.g. trade offers never syncing). 0 = disabled. Raise if Void Teleport Timeout or Void Unload Delay is set above this value");
		public static final ConfigBoolean DEBUG_HUD = new ConfigBoolean("debugHud", false,
				"Show the debug HUD overlay with live trade status and counters (small corner panel with a semi-transparent background; hidden while a screen is open)");
		public static final ConfigOptionListValue DEBUG_HUD_POSITION = new ConfigOptionListValue("debugHudPosition",
				HudPosition.TOP_LEFT, "Screen corner where the debug HUD is drawn");
		/** 背包物品计数复用间隔（tick）；容器候选每次重算（距离实时）；任意任务结束立即重算背包 */
		public static final ConfigInteger IDLE_SCAN_INTERVAL = new ConfigInteger("idleScanInterval", 5, 1, 20,
				"Ticks to reuse the backpack item-count map before recounting (container candidates are recomputed every tick with fresh distance; finishing any task always recounts immediately)");
		/** 触发容器 IO 的最大容器距离（格）；超过约 4.5 格服务端会忽略点击 */
		public static final ConfigInteger CONTAINER_REACH = new ConfigInteger("containerReach", 4, 2, 8,
				"Max distance in blocks to a container for container IO to trigger (the server ignores clicks beyond ~4.5 blocks, so higher values may not work)");
		/** 输出操作单次搬运的最大组数（999 = 全部匹配物品）；防极端场景同 tick 点击风暴 */
		public static final ConfigInteger OUTPUT_MOVE_CAP = new ConfigInteger("outputMoveCap", 999, 1, 9999,
				"Maximum item stacks moved in a single output container-IO operation (999 = move all matching stacks)");
		/** 9.8 村民交易缓存：已知无可执行匹配交易的村民跳过开窗的复查间隔（tick）；0 = 完全禁用缓存 */
		public static final ConfigInteger TRADE_CACHE_TTL = new ConfigInteger("tradeCacheTtl", 3000, 0, 36000,
				"Ticks a villager with no executable matching offer is skipped before re-checking its trades (3000 ticks = 2.5 min; 0 = disable the cache entirely)");

		public static final ConfigJsonArray TRADE_PAIRS = new ConfigJsonArray("tradePairs", "[]",
				"Trade pair list (JSON). Use the in-game GUI to manage.");
		public static final ConfigJsonArray ITEM_IO = new ConfigJsonArray("itemIO", "[]",
				"Item container IO list (JSON). Use the in-game GUI to manage.");
		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(ENABLED, TRADE_MODE,
				TRADE_EXECUTOR_MODE, VILLAGER_SCAN_RANGE, OPEN_TIMEOUT, TASK_TIMEOUT, DEBUG_HUD, DEBUG_HUD_POSITION,
				IDLE_SCAN_INTERVAL, CONTAINER_REACH, OUTPUT_MOVE_CAP, TRADE_CACHE_TTL);
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
		/** 村民候选/派发最大距离（格）：服务端实体交互上限 6 格（AABB-眼睛 < 36.0），默认 6.0 保守匹配；玩家移动中点击有滞后可放宽 */
		public static final ConfigDouble MOVING_INTERACT_RANGE = new ConfigDouble("movingInteractRange", 6.0, 3.0, 10.0,
				"Max distance (blocks) for villager candidates to be dispatched in moving mode (server entity-interaction limit is 6 blocks: AABB-to-eye < 6.0; raise if moving players fail to interact)");
		/**
		 * 驻留老化周期（tick）：候选目标超过该 tick 未被服务 → 饥饿 +1 并重置周期（驻留目标饥饿增长的唯一途径；3 周期后必超容器 bonus）
		 */
		public static final ConfigInteger MOVING_STARVATION_AGING_INTERVAL = new ConfigInteger(
				"movingStarvationAgingInterval", 100, 20, 600,
				"Ticks after which an unserved candidate in range gains +1 starvation (resident targets never leave range, so aging is their only hunger growth path; 3 cycles beat the container bonus)");
		/**
		 * 饥饿阈值（提示 + 让位共用）：scanRange 内 hunger ≥ 该值 → 一次性提示；候选内 hunger ≥ 该值 且 > 当前任务目标 →
		 * 让位抢占
		 */
		public static final ConfigInteger MOVING_STARVATION_HINT_THRESHOLD = new ConfigInteger(
				"movingStarvationHintThreshold", 4, 1, 10,
				"Starvation threshold: targets with hunger >= this value trigger a one-time in-game hint (check your movement path); running tasks yield to hungrier targets in interaction range");
		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(MOVING_RANGE_MULTIPLIER,
				MOVING_INTERACT_RANGE, MOVING_STARVATION_AGING_INTERVAL, MOVING_STARVATION_HINT_THRESHOLD);
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
		/**
		 * 返回触发方块所在维度（registry id，如 minecraft:overworld）；默认空串 = 任意维度（与 IO 记录空语义一致，避免旧
		 * Void 用户被默认 overworld 误过滤）
		 */
		public static final ConfigString VOID_RETURN_DIM = new ConfigString("voidReturnDim", "",
				"Dimension (registry id) where the void return trigger block is located, e.g. minecraft:overworld. Empty = any dimension");
		public static final ConfigBoolean VOID_RETURN_STRICT = new ConfigBoolean("voidReturnStrict", true,
				"Strictly validate that the return trigger block type matches the configured type; mismatch is skipped with a warning (default off = only check block existence and distance)");

		public static final ImmutableList<IConfigValue> OPTIONS = ImmutableList.of(VOID_TELEPORT_TIMEOUT,
				VOID_UNLOAD_DELAY, VOID_RETURN_TYPE, VOID_RETURN_POS, VOID_RETURN_DIM, VOID_RETURN_STRICT);
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

				// Read TRADE_PAIRS separately (not in OPTIONS to hide from GUI)；
				// JSON 数组格式原生读取，旧字符串格式由 ConfigJsonArray 兼容
				if (root.has("Generic") && root.getAsJsonObject("Generic").has("tradePairs")) {
					Generic.TRADE_PAIRS.setValueFromJsonElement(root.getAsJsonObject("Generic").get("tradePairs"));
				}

				// Read ITEM_IO separately (not in OPTIONS to hide from GUI)；
				// JSON 数组格式原生读取，旧字符串格式由 ConfigJsonArray 兼容
				if (root.has("Generic") && root.getAsJsonObject("Generic").has("itemIO")) {
					Generic.ITEM_IO.setValueFromJsonElement(root.getAsJsonObject("Generic").get("itemIO"));
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
			// 以 JSON 数组元素原生写入（getAsJsonElement 保证数组格式落盘）
			generic.add("tradePairs", Generic.TRADE_PAIRS.getAsJsonElement());

			// Write ITEM_IO separately
			generic.add("itemIO", Generic.ITEM_IO.getAsJsonElement());

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
