package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.TrappedChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class ContainerIOOperation extends Operation {

	private enum State {
		OPENING, TRANSFERRING, CLOSING, DONE
	}

	private State state = State.OPENING;
	private final TradePair pair;
	private final boolean isInput;
	private int containerTimeout = 0;
	private int trapChestDelay = 0;
	private int transferLimit = 0;
	private int transferred = 0;
	private int failCount = 0;
	private static final int MAX_FAILURES = 3;

	public ContainerIOOperation(TradePair pair, boolean isInput) {
		this.pair = pair;
		this.isInput = isInput;
		this.transferLimit = isInput ? pair.getInputTakeAmount() : 999;
	}

	@Override
	public void tick(MinecraftClient mc) {
		if (done)
			return;

		tickWait();
		if (isWaiting())
			return;

		switch (state) {
			case OPENING -> tickOpening(mc);
			case TRANSFERRING -> tickTransferring(mc);
			case CLOSING -> tickClosing(mc);
			case DONE -> done = true;
		}
	}

	private void tickOpening(MinecraftClient mc) {
		// 根据输入/输出选择目标容器坐标
		BlockPos pos = isInput
				? new BlockPos(pair.getInputX(), pair.getInputY(), pair.getInputZ())
				: new BlockPos(pair.getOutputX(), pair.getOutputY(), pair.getOutputZ());

		if (mc.world != null) {
			BlockState blockState = mc.world.getBlockState(pos);
			// 目标位置必须是容器方块，否则直接放弃
			if (!isContainerBlock(blockState)) {
				AutoTrade.logger.warn("[ContainerIO] 目标位置 {} 不是容器 ({})，放弃操作", pos.toShortString(),
						blockState.getBlock().getName().getString());
				failCount = MAX_FAILURES;
				state = State.CLOSING;
				return;
			}
			// 陷阱箱需要额外延迟等待红石信号稳定
			if (blockState.getBlock() instanceof TrappedChestBlock) {
				trapChestDelay = Configs.Generic.TRAP_CHEST_DELAY.getIntegerValue();
			} else {
				trapChestDelay = 0;
			}
		}

		if (mc.player != null && mc.interactionManager != null) {
			mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND,
					new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
		}

		containerTimeout = Configs.Generic.CONTAINER_TIMEOUT.getIntegerValue();
		state = State.TRANSFERRING;
	}

	private static boolean isContainerBlock(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
				|| state.getBlock() instanceof ShulkerBoxBlock;
	}

	// 判断当前打开的 GUI 是否为容器窗口（箱子/潜影盒）
	private static boolean isContainerScreen(Screen screen) {
		return screen instanceof ShulkerBoxScreen || screen instanceof GenericContainerScreen;
	}

	// 获取容器窗口对应的 ScreenHandler
	private static ScreenHandler screenHandlerOf(Screen screen) {
		return screen instanceof ShulkerBoxScreen s
				? s.getScreenHandler()
				: ((GenericContainerScreen) screen).getScreenHandler();
	}

	// 若当前打开的是容器窗口则将其关闭
	private static void closeScreenIfOpen(Screen screen) {
		if (screen instanceof ShulkerBoxScreen s) {
			s.close();
		} else if (screen instanceof GenericContainerScreen s) {
			s.close();
		}
	}

	private void tickTransferring(MinecraftClient mc) {
		if (!isContainerScreen(mc.currentScreen)) {
			if (containerTimeout > 0) {
				containerTimeout--;
				wait(1);
				return;
			}
			failCount++;
			if (failCount < MAX_FAILURES) {
				AutoTrade.logger.warn("[ContainerIO] 容器窗口未打开 (第 {}/{} 次)，重试", failCount, MAX_FAILURES);
				state = State.OPENING;
			} else {
				AutoTrade.logger.warn("[ContainerIO] 容器窗口打开失败已达上限 ({} 次)，放弃", MAX_FAILURES);
				state = State.CLOSING;
			}
			return;
		}
		failCount = 0;
		containerTimeout = 0;

		// 陷阱箱延迟：等待红石信号稳定后再操作
		if (trapChestDelay > 0) {
			trapChestDelay--;
			wait(1);
			return;
		}

		ScreenHandler handler = screenHandlerOf(mc.currentScreen);

		if (transferred >= transferLimit) {
			state = State.CLOSING;
			return;
		}

		boolean clicked = isInput ? transferItem(mc, handler, true) : transferItem(mc, handler, false);

		if (clicked) {
			transferred++;
		} else {
			state = State.CLOSING;
		}
	}

	// 移动单个匹配物品：
	// 输入操作：从容器槽位快速移动到玩家背包（遍历到玩家背包槽位即停止）；
	// 输出操作：从玩家背包槽位快速移动到容器（跳过非玩家背包槽位）。
	private boolean transferItem(MinecraftClient mc, ScreenHandler handler, boolean isInputOp) {
		if (mc.player == null || mc.interactionManager == null) {
			return false;
		}

		String targetItem = isInputOp
				? ItemStringHelper.getItemId(pair.getGiveItem())
				: ItemStringHelper.getItemId(pair.getGetItem());

		for (int i = 0; i < handler.slots.size(); i++) {
			Slot slot = handler.getSlot(i);
			if (isInputOp) {
				if (slot.inventory instanceof PlayerInventory)
					break;
			} else {
				if (!(slot.inventory instanceof PlayerInventory))
					continue;
			}
			if (!slot.hasStack())
				continue;

			String slotItemId = Registries.ITEM.getId(slot.getStack().getItem()).toString();
			if (slotItemId.equals(targetItem)) {
				try {
					mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
					return true;
				} catch (Exception e) {
					AutoTrade.logger.error("Error transferring item", e);
				}
			}
		}
		return false;
	}

	private void tickClosing(MinecraftClient mc) {
		closeScreenIfOpen(mc.currentScreen);
		state = State.DONE;
	}
}
