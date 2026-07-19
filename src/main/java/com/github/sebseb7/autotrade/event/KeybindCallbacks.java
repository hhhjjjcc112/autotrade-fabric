package com.github.sebseb7.autotrade.event;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.GuiConfigs;
import com.github.sebseb7.autotrade.gui.MerchantScreenPairInjector;
import com.github.sebseb7.autotrade.gui.PairListScreen;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import com.github.sebseb7.autotrade.util.TradePairList;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * Handles all the mod's keybinds and the main auto-trading logic on client
 * ticks. This class is a singleton.
 */
public class KeybindCallbacks implements IHotkeyCallback, IClientTickHandler {
	private static final KeybindCallbacks INSTANCE = new KeybindCallbacks();

	// region Constants
	private static final int TRADE_COOLDOWN_TICKS = 2;
	private static final int RESULT_EMPTY_RETRY_TICKS = 2;
	private static final int RESULT_EMPTY_MAX_WAITS = 15;
	// endregion

	// region State Fields
	private final Vector<Entity> villagersInRange = new Vector<>();
	private int villagerActive = 0;

	private boolean inputInRange = false;
	private boolean inputOpened = false;
	private boolean outputInRange = false;
	private boolean outputOpened = false;

	private int tickCount = 0;
	private int voidDelay = 0;
	private int containerDelay = 0;

	// Cooldown between trades (QUICK_MOVE puts result directly into inventory)
	private int tradeCooldownTicks = 0;
	private int merchantResultQuickMoveOfferIndex = -1;
	private boolean merchantResultQuickMoveIsBuy = false;
	private int merchantResultEmptyWaits = 0;

	// Merchant screen button injection tracking
	// endregion

	private KeybindCallbacks() {
		// Private constructor for singleton
	}

	public static KeybindCallbacks getInstance() {
		return INSTANCE;
	}

	/**
	 * Registers the hotkey callbacks for all defined hotkeys.
	 */
	public void setCallbacks() {
		for (ConfigHotkey hotkey : Hotkeys.HOTKEY_LIST) {
			hotkey.getKeybind().setCallback(this);
		}
	}

	/**
	 * Checks if the main functionality of the mod is enabled.
	 * 
	 * @return true if the mod is enabled, false otherwise.
	 */
	public boolean functionalityEnabled() {
		return Configs.Generic.ENABLED.getBooleanValue();
	}

	// region Merchant Trade Helpers

	private void clearMerchantQuickMoveDefer() {
		tradeCooldownTicks = 0;
		merchantResultQuickMoveOfferIndex = -1;
		merchantResultEmptyWaits = 0;
	}

	/**
	 * Checks whether the player has enough items in their inventory to pay for the
	 * given trade offer.
	 */
	private static boolean playerHasMerchantCosts(PlayerEntity player, TradeOffer offer) {
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		if (!costA.isEmpty() && !hasEnough(player, costA)) {
			AutoTrade.logger.info("[AutoTrade] Cannot afford: need {}x{} (NBT: {}), not enough in inventory",
					costA.getCount(), Registries.ITEM.getId(costA.getItem()),
					costA.getNbt() != null ? costA.getNbt().toString() : "none");
			return false;
		}
		ItemStack costB = offer.getSecondBuyItem();
		if (!costB.isEmpty() && !hasEnough(player, costB)) {
			AutoTrade.logger.info("[AutoTrade] Cannot afford: need {}x{} (NBT: {}), not enough in inventory",
					costB.getCount(), Registries.ITEM.getId(costB.getItem()),
					costB.getNbt() != null ? costB.getNbt().toString() : "none");
			return false;
		}
		return true;
	}

	private static boolean hasEnough(PlayerEntity player, ItemStack required) {
		int need = required.getCount();
		int have = 0;
		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (stacksMatchExact(stack, required)) {
				have += stack.getCount();
				if (have >= need) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Checks if two ItemStacks match exactly: same item type AND same NBT data.
	 * This is critical for modded items where the same item ID may have different
	 * NBT tags (e.g., nether_star with different custom tags).
	 */
	private static boolean stacksMatchExact(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] stacksMatchExact: one is empty a={} b={}", a.isEmpty(), b.isEmpty());
			return false;
		}
		if (!a.isOf(b.getItem())) {
			AutoTrade.logger.info("[AutoTrade] stacksMatchExact: item mismatch a={} b={}",
					Registries.ITEM.getId(a.getItem()), Registries.ITEM.getId(b.getItem()));
			return false;
		}
		// Compare NBT data
		NbtCompound tagA = a.getNbt();
		NbtCompound tagB = b.getNbt();
		if (tagA == null && tagB == null) {
			AutoTrade.logger.info("[AutoTrade] stacksMatchExact: both no NBT -> match for {}",
					Registries.ITEM.getId(a.getItem()));
			return true;
		}
		if (tagA == null || tagB == null) {
			AutoTrade.logger.info("[AutoTrade] stacksMatchExact: NBT mismatch one is null a.hasNbt={} b.hasNbt={}",
					tagA != null, tagB != null);
			return false;
		}
		boolean match = tagA.equals(tagB);
		if (!match) {
			AutoTrade.logger.info("[AutoTrade] stacksMatchExact: NBT differs a={} b={}", tagA, tagB);
		}
		return match;
	}

	/**
	 * Checks if a villager trade offer matches a configured trade pair. A match
	 * occurs when: - The villager's cost item (what the player gives) matches the
	 * pair's giveItem (ID + optional NBT) - The villager's result item (what the
	 * player receives) matches the pair's getItem (ID + optional NBT)
	 */
	private static boolean doesOfferMatchPair(TradeOffer offer, TradePair pair) {
		ItemStack costItem = offer.getAdjustedFirstBuyItem();
		ItemStack resultItem = offer.getSellItem();
		boolean costMatch = ItemStringHelper.matches(costItem, pair.getGiveItem());
		boolean resultMatch = ItemStringHelper.matches(resultItem, pair.getGetItem());
		AutoTrade.logger.info(
				"[AutoTrade] Matching: offer({} cost={} result={}) vs pair(give={} get={}) -> costMatch={} resultMatch={}",
				Registries.ITEM.getId(costItem.getItem()).toString(),
				costItem.getNbt() != null ? costItem.getNbt().toString() : "noNbt",
				Registries.ITEM.getId(resultItem.getItem()).toString(), ItemStringHelper.getItemId(pair.getGiveItem()),
				ItemStringHelper.getItemId(pair.getGetItem()), costMatch, resultMatch);
		return costMatch && resultMatch;
	}

	/**
	 * Counts how many of a given item ID the player has in their inventory.
	 */
	private static int countInInventory(PlayerEntity player, String itemId) {
		if (player == null || itemId == null || itemId.isBlank())
			return 0;
		int total = 0;
		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static void showTradeNotice(MinecraftClient mc, String translationKey, Object... args) {
		InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, translationKey, args);
	}

	// endregion

	// region Hotkey Handling

	@Override
	public boolean onKeyAction(KeyAction action, IKeybind key) {
		// We only care about press actions
		if (action != KeyAction.PRESS) {
			return false;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.world == null) {
			return false;
		}

		if (handleToggleAndGuiKeys(key)) {
			return true;
		}

		return handleConfigHotkeys(key, mc);
	}

	/**
	 * Handles the toggle and GUI opening hotkeys.
	 * 
	 * @param key
	 *            The keybind that was pressed.
	 * @return true if the key was handled, false otherwise.
	 */
	private boolean handleToggleAndGuiKeys(IKeybind key) {
		if (key == Hotkeys.TOGGLE_KEY.getKeybind()) {
			Configs.Generic.ENABLED.toggleBooleanValue();
			String msg = this.functionalityEnabled()
					? "autotrade.message.toggled_mod_on"
					: "autotrade.message.toggled_mod_off";
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, msg);
			if (this.functionalityEnabled()) {
				AutoTrade.sold = 0;
				AutoTrade.bought = 0;
				AutoTrade.sessionStart = System.currentTimeMillis() / 1000L;
			}
			return true;
		} else if (key == Hotkeys.OPEN_GUI_SETTINGS.getKeybind()) {
			GuiBase.openGui(new GuiConfigs());
			return true;
		}
		return false;
	}

	/**
	 * Handles hotkeys that configure the mod's behavior, like setting containers or
	 * items.
	 * 
	 * @param key
	 *            The keybind that was pressed.
	 * @param mc
	 *            The Minecraft client instance.
	 * @return false, as these actions shouldn't block further processing.
	 */
	private boolean handleConfigHotkeys(IKeybind key, MinecraftClient mc) {
		if (key == Hotkeys.SET_INPUT_KEY.getKeybind()) {
			HitResult result = mc.player.raycast(20.0D, 0.0F, false);
			if (result.getType() == HitResult.Type.BLOCK) {
				BlockHitResult blockHit = (BlockHitResult) result;
				Configs.Generic.INPUT_CONTAINER_X.setIntegerValue(blockHit.getBlockPos().getX());
				Configs.Generic.INPUT_CONTAINER_Y.setIntegerValue(blockHit.getBlockPos().getY());
				Configs.Generic.INPUT_CONTAINER_Z.setIntegerValue(blockHit.getBlockPos().getZ());
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.input_container_set",
						blockHit.getBlockPos().getX(), blockHit.getBlockPos().getY(), blockHit.getBlockPos().getZ());
			}
		} else if (key == Hotkeys.SET_OUTPUT_KEY.getKeybind()) {
			HitResult result = mc.player.raycast(20.0D, 0.0F, false);
			if (result.getType() == HitResult.Type.BLOCK) {
				BlockHitResult blockHit = (BlockHitResult) result;
				Configs.Generic.OUTPUT_CONTAINER_X.setIntegerValue(blockHit.getBlockPos().getX());
				Configs.Generic.OUTPUT_CONTAINER_Y.setIntegerValue(blockHit.getBlockPos().getY());
				Configs.Generic.OUTPUT_CONTAINER_Z.setIntegerValue(blockHit.getBlockPos().getZ());
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.output_container_set",
						blockHit.getBlockPos().getX(), blockHit.getBlockPos().getY(), blockHit.getBlockPos().getZ());
			}
		} else if (key == Hotkeys.SET_BUY_KEY.getKeybind()) {
			ItemStack held = mc.player.getInventory().getMainHandStack();
			String encoded = ItemStringHelper.encode(held);
			String json = Configs.Generic.TRADE_PAIRS.getStringValue();
			// Add as new pair with a placeholder give item
			Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.addPair(json, "minecraft:air", encoded, 64));
			Configs.saveToFile();
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.buy_item_set",
					ItemStringHelper.getItemId(encoded));
		} else if (key == Hotkeys.SET_SELL_KEY.getKeybind()) {
			ItemStack held = mc.player.getInventory().getMainHandStack();
			String encoded = ItemStringHelper.encode(held);
			String json = Configs.Generic.TRADE_PAIRS.getStringValue();
			// Add as new pair with a placeholder get item
			Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.addPair(json, encoded, "minecraft:air", 64));
			Configs.saveToFile();
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.sell_item_set",
					ItemStringHelper.getItemId(encoded));
		} else if (key == Hotkeys.ADD_TRADE_PAIR_KEY.getKeybind()) {
			if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen) {
				GuiBase.openGui(new PairListScreen());
			} else {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
						"autotrade.message.not_on_merchant_screen");
			}
		}
		return false;
	}

	// endregion

	// region Client Tick Handling

	// Track which screens have already had buttons injected
	private final Set<net.minecraft.client.gui.screen.Screen> injectedScreens = new HashSet<>();

	@Override
	public void onClientTick(MinecraftClient mc) {
		// Tick-based MerchantScreen button injection (offers arrive after server sync)
		if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen screen
				&& !injectedScreens.contains(screen)) {
			net.minecraft.village.TradeOfferList offers = screen.getScreenHandler().getRecipes();
			if (offers != null && offers.size() > 0) {
				MerchantScreenPairInjector.addPairButtons(mc, screen);
				injectedScreens.add(screen);
			}
		} else if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.MerchantScreen)) {
			injectedScreens.clear();
		}

		// Main entry point for the tick-based logic
		if (!shouldExecuteTick(mc)) {
			return;
		}

		handleTickLogic(mc);
	}

	/**
	 * Injects an "Add as Trade Pair" button into the MerchantScreen for each trade
	 * offer. Clicking a button adds that trade as a new pair. Uses Screen reference
	 * tracking to avoid re-adding every tick.
	 */
	private void addTradeAsPair(MinecraftClient mc, MerchantScreenHandler handler, int tradeIndex) {
		TradeOfferList offers = handler.getRecipes();
		if (offers == null || tradeIndex < 0 || tradeIndex >= offers.size())
			return;

		TradeOffer offer = offers.get(tradeIndex);
		if (offer.isDisabled())
			return;

		ItemStack costItem = offer.getAdjustedFirstBuyItem();
		ItemStack resultItem = offer.getSellItem();
		if (costItem.isEmpty() || resultItem.isEmpty())
			return;

		String giveEncoded = ItemStringHelper.encode(costItem);
		String getEncoded = ItemStringHelper.encode(resultItem);
		int limit = Math.max(costItem.getCount(), 1);

		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		Configs.Generic.TRADE_PAIRS.setValueFromString(TradePairList.addPair(json, giveEncoded, getEncoded, limit));
		Configs.saveToFile();

		InfoUtils.showGuiOrInGameMessage(Message.MessageType.SUCCESS, "autotrade.message.added_trade_pairs", 1);
	}

	/**
	 * Checks for preliminary conditions to decide if the tick logic should run.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @return true if the tick logic should proceed, false otherwise.
	 */
	private boolean shouldExecuteTick(MinecraftClient mc) {
		if (voidDelay > 0) {
			handleVoidDelay(mc);
			return false;
		}

		if (containerDelay > 0) {
			containerDelay--;
			return false;
		}

		return this.functionalityEnabled() && mc.player != null && mc.world != null;
	}

	/**
	 * Manages the delay after interacting with a villager, especially for void
	 * trading.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 */
	private void handleVoidDelay(MinecraftClient mc) {
		if (Configs.Generic.VOID_TRADING_DELAY_AFTER_TELEPORT.getBooleanValue()) {
			boolean found = false;
			for (Entity entity : mc.player.clientWorld.getEntities()) {
				if (entity.getId() == villagerActive) {
					found = true;
					break;
				}
			}
			if (!found) {
				voidDelay--;
			}
		} else {
			voidDelay--;
		}
	}

	/**
	 * Main logic handler called on every eligible client tick.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 */
	private void handleTickLogic(MinecraftClient mc) {
		// Reset state periodically to prevent getting stuck
		tickCount++;
		if (tickCount > 200) {
			resetState();
		}

		// Dynamic configuration based on in-world blocks and items
		if (Configs.Generic.GLASS_BLOCK.getBooleanValue()) {
			handleGlassBlockDetection(mc);
		}
		if (Configs.Generic.ITEM_FRAME.getBooleanValue()) {
			handleItemFrameDetection(mc);
		}

		// Handle interactions based on the currently open screen
		if (handleGuiScreens(mc)) {
			return; // Return if a screen was handled, as it's a blocking action
		}

		// If no screen is open, try to interact with the world
		if (handleVillagerInteraction(mc)) {
			return; // Return if a villager was interacted with
		}

		handleContainerInteraction(mc);
	}

	/**
	 * Resets the internal state of the auto-trader. Does NOT close the merchant
	 * screen (may be in the middle of multi-trade).
	 */
	private void resetState() {
		tickCount = 0;
		villagersInRange.clear();
		inputInRange = false;
		outputInRange = false;
		clearMerchantQuickMoveDefer();
		if (GuiUtils.getCurrentScreen() instanceof ShulkerBoxScreen
				|| GuiUtils.getCurrentScreen() instanceof GenericContainerScreen) {
			GuiUtils.getCurrentScreen().close();
		}
	}

	/**
	 * Detects colored glass blocks in the world to dynamically set input/output
	 * container positions.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 */
	private void handleGlassBlockDetection(MinecraftClient mc) {
		int playerX = (int) mc.player.getPos().getX();
		int playerY = (int) mc.player.getPos().getY();
		int playerZ = (int) mc.player.getPos().getZ();
		int selectorOffset = Configs.Generic.SELECTOR_OFFSET.getIntegerValue();
		int absSelectorOffset = Math.abs(selectorOffset);
		Box scanBox = new Box(playerX - (absSelectorOffset + 3), playerY - (absSelectorOffset + 3),
				playerZ - (absSelectorOffset + 3), playerX + (absSelectorOffset + 3), playerY + (absSelectorOffset + 3),
				playerZ + (absSelectorOffset + 3));

		for (BlockPos pos : BlockPos.iterate((int) scanBox.minX, (int) scanBox.minY, (int) scanBox.minZ,
				(int) scanBox.maxX, (int) scanBox.maxY, (int) scanBox.maxZ)) {
			if (mc.player.clientWorld.getBlockState(pos).isOf(Blocks.RED_STAINED_GLASS)) {
				BlockPos containerPos = pos.down(selectorOffset);
				if (containerPos.getX() != Configs.Generic.INPUT_CONTAINER_X.getIntegerValue()
						|| containerPos.getY() != Configs.Generic.INPUT_CONTAINER_Y.getIntegerValue()
						|| containerPos.getZ() != Configs.Generic.INPUT_CONTAINER_Z.getIntegerValue()) {
					Configs.Generic.INPUT_CONTAINER_X.setIntegerValue(containerPos.getX());
					Configs.Generic.INPUT_CONTAINER_Y.setIntegerValue(containerPos.getY());
					Configs.Generic.INPUT_CONTAINER_Z.setIntegerValue(containerPos.getZ());
					InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.input_container_set",
							containerPos.getX(), containerPos.getY(), containerPos.getZ());
				}
				return; // Found one, no need to check for others in the same tick
			}
			if (mc.player.clientWorld.getBlockState(pos).isOf(Blocks.BLUE_STAINED_GLASS)) {
				BlockPos containerPos = pos.down(selectorOffset);
				if (containerPos.getX() != Configs.Generic.OUTPUT_CONTAINER_X.getIntegerValue()
						|| containerPos.getY() != Configs.Generic.OUTPUT_CONTAINER_Y.getIntegerValue()
						|| containerPos.getZ() != Configs.Generic.OUTPUT_CONTAINER_Z.getIntegerValue()) {
					Configs.Generic.OUTPUT_CONTAINER_X.setIntegerValue(containerPos.getX());
					Configs.Generic.OUTPUT_CONTAINER_Y.setIntegerValue(containerPos.getY());
					Configs.Generic.OUTPUT_CONTAINER_Z.setIntegerValue(containerPos.getZ());
					InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.output_container_set",
							containerPos.getX(), containerPos.getY(), containerPos.getZ());
				}
				return; // Found one
			}
		}
	}

	/**
	 * Detects item frames with specially named items to dynamically set the
	 * buy/sell items.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 */
	private void handleItemFrameDetection(MinecraftClient mc) {
		Box searchBox = new Box(mc.player.getPos().subtract(3, 3, 3), mc.player.getPos().add(3, 3, 3));
		for (ItemFrameEntity entity : mc.player.clientWorld.getEntitiesByClass(ItemFrameEntity.class, searchBox,
				EntityPredicates.VALID_ENTITY)) {
			ItemStack stack = entity.getHeldItemStack();
			if (!stack.hasNbt() || !stack.getNbt().contains("display", 10)) {
				continue;
			}

			NbtCompound displayTag = stack.getNbt().getCompound("display");
			String name = displayTag.getString("Name");
			String itemId = Registries.ITEM.getId(stack.getItem()).toString();

			if (name.equals("\"sell\"") && !Configs.Generic.SELL_ITEM.getStringValue().equals(itemId)) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.sell_item_set", itemId);
				Configs.Generic.SELL_ITEM.setValueFromString(itemId);
				return; // Found one
			}
			if (name.equals("\"buy\"") && !Configs.Generic.BUY_ITEM.getStringValue().equals(itemId)) {
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.buy_item_set", itemId);
				Configs.Generic.BUY_ITEM.setValueFromString(itemId);
				return; // Found one
			}
		}
	}

	/**
	 * Handles logic when a GUI screen is open (Merchant, Shulker Box, or Chest).
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @return true if a screen was handled, false otherwise.
	 */
	private boolean handleGuiScreens(MinecraftClient mc) {
		if (mc.currentScreen instanceof MerchantScreen screen) {
			this.tickCount = 0;
			handleMerchantScreenTick(mc, screen);
			return true; // MerchantScreen is handled tick-by-tick, keep returning true
		}
		if (mc.currentScreen instanceof ShulkerBoxScreen screen) {
			this.tickCount = 0;
			if (inputOpened || outputOpened) {
				handleContainerScreen(screen.getScreenHandler());
				// Set the delay, don't close immediately
				this.containerDelay = Configs.Generic.CONTAINER_CLOSE_DELAY.getIntegerValue();
			} else if (this.containerDelay == 0) {
				screen.close();
			}
			return true;
		}
		if (mc.currentScreen instanceof GenericContainerScreen screen) {
			this.tickCount = 0;
			if (inputOpened || outputOpened) {
				handleContainerScreen(screen.getScreenHandler());
				// Set the delay, don't close immediately
				this.containerDelay = Configs.Generic.CONTAINER_CLOSE_DELAY.getIntegerValue();
			} else if (this.containerDelay == 0) {
				screen.close();
			}
			return true;
		}
		return false;
	}

	/**
	 * Tick-based merchant screen handler. Processes ONE step per tick: 1. If a
	 * deferred quick-move is pending, wait for the delay. 2. If the result slot
	 * (slot 2) has items, move them to the player inventory. 3. Try to execute ONE
	 * matching trade offer. 4. If no more matching trades, close the screen.
	 */
	private void handleMerchantScreenTick(MinecraftClient mc, MerchantScreen screen) {
		MerchantScreenHandler handler = screen.getScreenHandler();
		TradeOfferList offers = handler.getRecipes();

		if (offers != null && !offers.isEmpty()) {
			for (int oi = 0; oi < offers.size(); oi++) {
				TradeOffer of = offers.get(oi);
				if (!of.isDisabled()) {
					ItemStack c = of.getAdjustedFirstBuyItem();
					ItemStack r = of.getSellItem();
					AutoTrade.logger.info(
							"[AutoTrade] Available offer[{}]: cost={}x{} (NBT: {}) result={}x{} (NBT: {}) uses={}/{}",
							oi, c.getCount(), Registries.ITEM.getId(c.getItem()),
							c.getNbt() != null ? c.getNbt().toString() : "none", r.getCount(),
							Registries.ITEM.getId(r.getItem()), r.getNbt() != null ? r.getNbt().toString() : "none",
							of.getUses(), of.getMaxUses());
				}
			}
		} else {
			AutoTrade.logger.info("[AutoTrade] No offers available from merchant");
		}

		// Step 1: Cooldown between trades (QUICK_MOVE already moved result to
		// inventory)
		if (tradeCooldownTicks > 0) {
			tradeCooldownTicks--;
			return;
		}

		// Step 2: If the result slot has items (e.g. from server sync), move them
		Slot slot2 = handler.getSlot(2);
		if (slot2.hasStack()) {
			AutoTrade.logger.info("[AutoTrade] Slot 2 has item, quick-moving: {}x{}", slot2.getStack().getCount(),
					Registries.ITEM.getId(slot2.getStack().getItem()));
			quickMoveOrPickupResult(mc, handler, slot2);
			return;
		}

		// Step 3: Try to execute one matching trade
		List<TradePair> pairs = TradePair.loadAllPairs();
		AutoTrade.logger.info("[AutoTrade] Scanning {} offers, {} configured trade pairs",
				offers != null ? offers.size() : 0, pairs.size());
		if (pairs.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] No trade pairs configured, closing merchant screen");
			clearMerchantQuickMoveDefer();
			screen.close();
			inputInRange = false;
			outputInRange = false;
			return;
		}

		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			if (offer.isDisabled())
				continue;
			if (offer.getUses() >= offer.getMaxUses())
				continue; // No uses left

			for (TradePair pair : pairs) {
				if (!pair.isEnabled()) {
					continue; // Pair is disabled (toggled OFF)
				}
				if (!doesOfferMatchPair(offer, pair)) {
					AutoTrade.logger.info("[AutoTrade] Offer {} -> pair(give={} get={}) NO MATCH", i,
							ItemStringHelper.getItemId(pair.getGiveItem()),
							ItemStringHelper.getItemId(pair.getGetItem()));
					continue;
				}
				AutoTrade.logger.info("[AutoTrade] Offer {} matched pair! give={} get={} limit={}", i,
						ItemStringHelper.getItemId(pair.getGiveItem()), ItemStringHelper.getItemId(pair.getGetItem()),
						pair.getLimit());

				int price = offer.getAdjustedFirstBuyItem().getCount();
				if (price > pair.getLimit()) {
					AutoTrade.logger.info("[AutoTrade] Price {} > limit {}, skipping", price, pair.getLimit());
					continue;
				}

				if (!playerHasMerchantCosts(mc.player, offer)) {
					AutoTrade.logger.info("[AutoTrade] Cannot afford offer {}, skipping", i);
					continue;
				}

				// Found a matching, affordable trade ?execute it
				AutoTrade.logger.info("[AutoTrade] EXECUTING trade offer {} pair(give={} get={})", i,
						ItemStringHelper.getItemId(pair.getGiveItem()), ItemStringHelper.getItemId(pair.getGetItem()));
				executeOneTrade(mc, handler, i, offer, pair);
				return;
			}
		}

		// Step 4: No more matching trades ?close the screen
		AutoTrade.logger.info("[AutoTrade] No more matching/affordable trades, closing merchant screen");
		clearMerchantQuickMoveDefer();
		screen.close();
		inputInRange = false;
		outputInRange = false;
	}

	/**
	 * Selects a trade on the server and sets up the deferred quick-move. The actual
	 * result pickup happens in a later tick.
	 */
	private void executeOneTrade(MinecraftClient mc, MerchantScreenHandler handler, int tradeIndex, TradeOffer offer,
			TradePair pair) {
		// Log the exact NBT details of what's being traded
		ItemStack costItem = offer.getAdjustedFirstBuyItem();
		ItemStack resultItem = offer.getSellItem();
		AutoTrade.logger.info("[AutoTrade] EXECUTE tradeIndex={} cost={}x{} (NBT: {}) result={}x{} (NBT: {}) syncId={}",
				tradeIndex, costItem.getCount(), Registries.ITEM.getId(costItem.getItem()),
				costItem.getNbt() != null ? costItem.getNbt().toString() : "none", resultItem.getCount(),
				Registries.ITEM.getId(resultItem.getItem()),
				resultItem.getNbt() != null ? resultItem.getNbt().toString() : "none", handler.syncId);
		AutoTrade.logger.info("[AutoTrade] EXECUTE pair giveEncoded={} getEncoded={}", pair.getGiveItem(),
				pair.getGetItem());

		handler.switchTo(tradeIndex);
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(tradeIndex));
		}
		// Shift-click the result slot (slot 2) to execute the trade and move result to
		// inventory
		try {
			mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] Failed to click merchant result slot", e);
		}
		// Track statistics
		String resultId = Registries.ITEM.getId(offer.getSellItem().getItem()).toString();
		if (resultId.equals(pair.getGetItem())) {
			// This is a "get" operation ?player gives giveItem, receives getItem
			AutoTrade.bought += offer.getSellItem().getCount();
		} else {
			AutoTrade.sold += offer.getAdjustedFirstBuyItem().getCount();
		}
		// Set cooldown before next trade
		tradeCooldownTicks = TRADE_COOLDOWN_TICKS;
		merchantResultQuickMoveOfferIndex = tradeIndex;
		merchantResultQuickMoveIsBuy = resultId.equals(pair.getGetItem());
		merchantResultEmptyWaits = 0;
	}

	/**
	 * Moves the trade result from slot 2 to the player inventory. For enchanted
	 * books, uses PICKUP instead of QUICK_MOVE to avoid shift-click chaining other
	 * book trades.
	 */
	private void quickMoveOrPickupResult(MinecraftClient mc, MerchantScreenHandler handler, Slot slot) {
		ItemStack stack = slot.getStack();
		if (stack.isEmpty())
			return;

		// Enchanted books: use PICKUP to avoid shift-click chaining
		if (stack.isOf(Items.ENCHANTED_BOOK) && stack.hasNbt() && stack.getNbt().contains("StoredEnchantments")) {
			try {
				// Pick up from result slot
				mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
				// Place into a suitable player inventory slot
				ItemStack carried = handler.getCursorStack();
				if (!carried.isEmpty()) {
					for (int i = 0; i < handler.slots.size(); i++) {
						Slot s = handler.getSlot(i);
						if (s.inventory instanceof PlayerInventory) {
							ItemStack existing = s.getStack();
							if (existing.isEmpty()) {
								mc.interactionManager.clickSlot(handler.syncId, s.id, 0, SlotActionType.PICKUP,
										mc.player);
								break;
							}
							if (existing.isOf(carried.getItem()) && ItemStack.areEqual(existing, carried)
									&& existing.getCount() < existing.getMaxCount()) {
								mc.interactionManager.clickSlot(handler.syncId, s.id, 0, SlotActionType.PICKUP,
										mc.player);
								break;
							}
						}
					}
				}
			} catch (Exception e) {
				AutoTrade.logger.warn("[AutoTrade] enchanted book pickup failed", e);
			}
		} else {
			try {
				mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
			} catch (Exception e) {
				AutoTrade.logger.warn("[AutoTrade] quick-move result failed", e);
			}
		}
	}

	/**
	 * Quick helper: for internal use by the container close path to sync inventory
	 * after merchant operations. No mixin needed ?this is best-effort.
	 */
	private static class ContainerIoHelper {
		static void syncPlayerInventoryAfterMerchant(MinecraftClient mc) {
			// Without mixin, we skip ensureHasSentCarriedItem().
			// The network handler will catch up naturally.
			if (mc.player != null && mc.getNetworkHandler() != null) {
				// Flush any pending inventory changes by sending a dummy slot click
				// on the player's cursor slot (slot -1). This is a no-op but forces
				// the server to sync back.
				try {
					mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, -1, 0, SlotActionType.PICKUP,
							mc.player);
				} catch (Exception ignored) {
				}
			}
		}
	}

	/**
	 * Handles item transfer when a container screen is open.
	 * 
	 * @param handler
	 *            The screen handler for the container.
	 */
	private void handleContainerScreen(ScreenHandler handler) {
		if (inputOpened) {
			processInput(handler);
		}
		if (outputOpened) {
			processOutput(handler);
		}
	}

	/**
	 * Finds and interacts with a villager in range.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @return true if a villager was interacted with, false otherwise.
	 */
	private boolean handleVillagerInteraction(MinecraftClient mc) {
		// Remove villagers that are now out of range
		villagersInRange.removeIf(entity -> entity.getPos().distanceTo(mc.player.getPos()) >= 4.0f);

		Box interactionBox = new Box(mc.player.getPos().subtract(2.5, 2.5, 2.5), mc.player.getPos().add(2.5, 2.5, 2.5));
		for (Entity entity : mc.player.clientWorld.getEntities()) {
			if ((entity instanceof VillagerEntity || entity instanceof WanderingTraderEntity)
					&& entity.getBoundingBox().intersects(interactionBox) && !villagersInRange.contains(entity)) {

				this.tickCount = 0;
				if (mc.interactionManager != null) {
					mc.interactionManager.interactEntity(mc.player, entity, Hand.MAIN_HAND);
				}
				voidDelay = Configs.Generic.VOID_TRADING_DELAY.getIntegerValue();
				villagerActive = entity.getId();
				clearMerchantQuickMoveDefer();
				villagersInRange.add(entity); // Add to known villagers to avoid re-interacting
				return true;
			}
		}
		return false;
	}

	/**
	 * Handles interaction with input/output containers.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 */
	private void handleContainerInteraction(MinecraftClient mc) {
		BlockPos inputPos = new BlockPos(Configs.Generic.INPUT_CONTAINER_X.getIntegerValue(),
				Configs.Generic.INPUT_CONTAINER_Y.getIntegerValue(),
				Configs.Generic.INPUT_CONTAINER_Z.getIntegerValue());
		BlockPos outputPos = new BlockPos(Configs.Generic.OUTPUT_CONTAINER_X.getIntegerValue(),
				Configs.Generic.OUTPUT_CONTAINER_Y.getIntegerValue(),
				Configs.Generic.OUTPUT_CONTAINER_Z.getIntegerValue());

		// Reset flags if player moved away
		if (inputPos.toCenterPos().distanceTo(mc.player.getPos()) > 5) {
			inputOpened = false;
			inputInRange = false;
		}
		if (outputPos.toCenterPos().distanceTo(mc.player.getPos()) > 5) {
			outputOpened = false;
			outputInRange = false;
		}

		// Interact with containers if in range and not already interacted with
		if (!inputInRange && inputPos.toCenterPos().distanceTo(mc.player.getPos()) < 4) {
			this.tickCount = 0;
			interactWithContainer(mc, inputPos, true);
			return;
		}
		if (!outputInRange && outputPos.toCenterPos().distanceTo(mc.player.getPos()) < 4) {
			this.tickCount = 0;
			interactWithContainer(mc, outputPos, false);
		}
	}

	/**
	 * Performs the block interaction to open a container.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @param pos
	 *            The position of the container.
	 * @param isInput
	 *            true if it's the input container, false for output.
	 */
	private void interactWithContainer(MinecraftClient mc, BlockPos pos, boolean isInput) {
		if (mc.interactionManager != null) {
			mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
					new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
		}
		// containerDelay is now set after processing, not on interaction
		if (isInput) {
			inputInRange = true;
			inputOpened = true;
		} else {
			outputInRange = true;
			outputOpened = true;
		}
	}

	// endregion

	// region Container Processing

	/**
	 * Moves items from the player's inventory to the output container.
	 * 
	 * @param handler
	 *            The screen handler of the container.
	 */
	private void processOutput(ScreenHandler handler) {
		outputOpened = false;

		String itemToPlace = Configs.Generic.ENABLE_BUY.getBooleanValue()
				? Configs.Generic.BUY_ITEM.getStringValue()
				: "minecraft:emerald";

		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (slot.inventory instanceof PlayerInventory) {
				if (Registries.ITEM.getId(slot.getStack().getItem()).toString().equals(itemToPlace)) {
					try {
						if (MinecraftClient.getInstance().interactionManager != null) {
							MinecraftClient.getInstance().interactionManager.clickSlot(handler.syncId, slot.id, 0,
									SlotActionType.QUICK_MOVE, MinecraftClient.getInstance().player);
						}
					} catch (Exception e) {
						AutoTrade.logger.error("Error processing output", e);
					}
				}
			}
		}
	}

	/**
	 * Moves items from the input container to the player's inventory.
	 * 
	 * @param handler
	 *            The screen handler of the container.
	 */
	private void processInput(ScreenHandler handler) {
		inputOpened = false;

		String itemToTake = Configs.Generic.ENABLE_SELL.getBooleanValue()
				? Configs.Generic.SELL_ITEM.getStringValue()
				: "minecraft:emerald";

		// First, calculate current inventory count of the item
		int currentItemCount = 0;
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory) {
				if (Registries.ITEM.getId(slot.getStack().getItem()).toString().equals(itemToTake)) {
					currentItemCount += slot.getStack().getCount();
				}
			}
		}

		// Then, take items from the container
		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (!(slot.inventory instanceof PlayerInventory)) { // If it's the container's inventory
				if (Registries.ITEM.getId(slot.getStack().getItem()).toString().equals(itemToTake)) {
					if (currentItemCount < (Configs.Generic.MAX_INPUT_ITEMS.getIntegerValue() * 64)) {
						currentItemCount += slot.getStack().getCount();
						try {
							if (MinecraftClient.getInstance().interactionManager != null) {
								MinecraftClient.getInstance().interactionManager.clickSlot(handler.syncId, slot.id, 0,
										SlotActionType.QUICK_MOVE, MinecraftClient.getInstance().player);
							}
						} catch (Exception e) {
							AutoTrade.logger.error("Error processing input", e);
						}
					}
				}
			}
		}
	}
	// endregion
}
