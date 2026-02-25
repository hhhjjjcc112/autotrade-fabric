package com.github.sebseb7.autotrade.event;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.Hotkeys;
import com.github.sebseb7.autotrade.gui.GuiConfigs;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;
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
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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

	// region State Fields
	private final Vector<Entity> villagersInRange = new Vector<>();
	private int villagerActive = 0;

	private boolean state = false;
	private boolean inputInRange = false;
	private boolean inputOpened = false;
	private boolean outputInRange = false;
	private boolean outputOpened = false;

	private int tickCount = 0;
	private int voidDelay = 0;
	private int containerDelay = 0;
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
			String buyItem = Registries.ITEM.getId(mc.player.getInventory().getMainHandStack().getItem()).toString();
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.buy_item_set", buyItem);
			Configs.Generic.BUY_ITEM.setValueFromString(buyItem);
		} else if (key == Hotkeys.SET_SELL_KEY.getKeybind()) {
			String sellItem = Registries.ITEM.getId(mc.player.getInventory().getMainHandStack().getItem()).toString();
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.INFO, "autotrade.message.sell_item_set", sellItem);
			Configs.Generic.SELL_ITEM.setValueFromString(sellItem);
		}
		return false;
	}

	// endregion

	// region Client Tick Handling

	@Override
	public void onClientTick(MinecraftClient mc) {
		// Main entry point for the tick-based logic
		if (!shouldExecuteTick(mc)) {
			return;
		}

		handleTickLogic(mc);
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
	 * Resets the internal state of the auto-trader.
	 */
	private void resetState() {
		tickCount = 0;
		villagersInRange.clear();
		inputInRange = false;
		outputInRange = false;
		if (GuiUtils.getCurrentScreen() instanceof MerchantScreen
				|| GuiUtils.getCurrentScreen() instanceof ShulkerBoxScreen
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
			handleMerchantScreen(mc, screen);
			return true;
		}
		if (mc.currentScreen instanceof ShulkerBoxScreen screen) {
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
	 * Handles the trading logic when the merchant screen is open.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @param screen
	 *            The merchant screen.
	 */
	private void handleMerchantScreen(MinecraftClient mc, MerchantScreen screen) {
		if (state) { // Already processed this screen
			return;
		}
		state = true;

		String sellItemStr = Configs.Generic.SELL_ITEM.getStringValue();
		String buyItemStr = Configs.Generic.BUY_ITEM.getStringValue();
		MerchantScreenHandler handler = screen.getScreenHandler();
		TradeOfferList offers = handler.getRecipes();

		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			if (offer.isDisabled())
				continue;

			String sellId = Registries.ITEM.getId(offer.getSellItem().getItem()).toString();
			String buyId = Registries.ITEM.getId(offer.getAdjustedFirstBuyItem().getItem()).toString();

			// Buy logic
			if (Configs.Generic.ENABLE_BUY.getBooleanValue() && sellId.equals(buyItemStr)
					&& offer.getAdjustedFirstBuyItem().getCount() <= Configs.Generic.BUY_LIMIT.getIntegerValue()) {
				executeTrade(mc, handler, i);
				AutoTrade.sold += offer.getMaxUses() - offer.getUses();
			}

			// Sell logic
			if (Configs.Generic.ENABLE_SELL.getBooleanValue() && buyId.equals(sellItemStr)
					&& offer.getAdjustedFirstBuyItem().getCount() <= Configs.Generic.SELL_LIMIT.getIntegerValue()) {
				executeTrade(mc, handler, i);
				AutoTrade.bought += offer.getMaxUses() - offer.getUses();
			}
		}

		screen.close();
		inputInRange = false;
		outputInRange = false;
	}

	/**
	 * Executes a trade by selecting the offer and quick-moving the result.
	 * 
	 * @param mc
	 *            The Minecraft client instance.
	 * @param handler
	 *            The merchant screen handler.
	 * @param tradeIndex
	 *            The index of the trade to execute.
	 */
	private void executeTrade(MinecraftClient mc, MerchantScreenHandler handler, int tradeIndex) {
		handler.switchTo(tradeIndex);
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(tradeIndex));
		}
		try {
			if (mc.interactionManager != null) {
				mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
			}
		} catch (Exception e) {
			AutoTrade.logger.error("Error executing trade", e);
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

				if (mc.interactionManager != null) {
					mc.interactionManager.interactEntity(mc.player, entity, Hand.MAIN_HAND);
				}
				voidDelay = Configs.Generic.VOID_TRADING_DELAY.getIntegerValue();
				villagerActive = entity.getId();
				state = false;
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
			interactWithContainer(mc, inputPos, true);
			return;
		}
		if (!outputInRange && outputPos.toCenterPos().distanceTo(mc.player.getPos()) < 4) {
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
