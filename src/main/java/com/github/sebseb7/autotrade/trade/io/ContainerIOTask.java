package com.github.sebseb7.autotrade.trade.io;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.trade.io.ContainerIOHelper.IOIntent;
import com.github.sebseb7.autotrade.trade.task.Task;
import com.github.sebseb7.autotrade.trade.task.TaskResult;
import com.github.sebseb7.autotrade.trade.task.TaskResult.FailReason;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
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
import net.minecraft.world.World;

public class ContainerIOTask extends Task {

	private enum State {
		OPENING, TRANSFERRING, CLOSING
	}

	private State state = State.OPENING;
	private final IOIntent intent;
	private int containerTimeout = 0;
	private int transferLimit = 0;
	private int transferred = 0;

	public ContainerIOTask(IOIntent intent) {
		this.intent = intent;
		// 输入操作按条目单次取放数量转移，输出操作一次性清空（999 上限）
		this.transferLimit = intent.isInput() ? intent.io().getTakeAmount() : 999;
	}

	/** 是否为输入操作（从容器取货，give1/give2 均算输入）；输出操作完成后背包空间释放，机器层可据此解除交易暂停 */
	public boolean isInputOp() {
		return intent.isInput();
	}

	/** 返回本次 IO 意图（MOVING 饥饿记账清零用，见 {@link IOIntent#ioKey()}） */
	public IOIntent getIntent() {
		return intent;
	}

	@Override
	public TaskResult tick(MinecraftClient mc) {
		return switch (state) {
			case OPENING -> tickOpening(mc);
			case TRANSFERRING -> tickTransferring(mc);
			case CLOSING -> tickClosing(mc);
		};
	}

	private TaskResult tickOpening(MinecraftClient mc) {
		// 目标容器坐标直接取自条目自身（输入/输出共用条目坐标）
		BlockPos pos = new BlockPos(intent.io().getX(), intent.io().getY(), intent.io().getZ());

		if (mc.world != null) {
			// 目标方块所在区块可能尚未加载（VOID 模式传送回岛后异步加载窗口）：未加载时 getBlockState 会返回空气，
			// 若据此判定「不是容器」会误报。这里先确认区块已加载；未加载则返回瞬态失败（不交互、不报错），
			// 由上层状态机下一 tick 重新派发容器 IO，实现自动重试
			if (!isChunkLoaded(mc.world, pos)) {
				return TaskResult.failed(FailReason.TRANSIENT);
			}
			BlockState blockState = mc.world.getBlockState(pos);
			// 区块已加载但目标位置仍非容器方块 → 真实配置错误，直接放弃
			if (!isContainerBlock(blockState)) {
				AutoTrade.logger.warn("[ContainerIO] 目标位置 {} 不是容器 ({})，放弃操作", pos.toShortString(),
						blockState.getBlock().getName().getString());
				// 弹窗提示用户容器坐标配置错误
				InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.io.not_container",
						pos.toShortString(), blockState.getBlock().getName().getString());
				return TaskResult.failed(FailReason.CONFIG);
			}
		}

		if (mc.player != null && mc.interactionManager != null) {
			mc.interactionManager.interactBlock(mc.player, net.minecraft.util.Hand.MAIN_HAND,
					new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false));
		}

		containerTimeout = Configs.Generic.OPEN_TIMEOUT.getIntegerValue();
		state = State.TRANSFERRING;
		return TaskResult.RUNNING;
	}

	public static boolean isContainerBlock(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
				|| state.getBlock() instanceof ShulkerBoxBlock;
	}

	/** 目标方块所在区块是否已加载完成（未加载时 getBlockState 返回空气，据此避免误判为「不是容器」） */
	private static boolean isChunkLoaded(World world, BlockPos pos) {
		return world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
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

	private TaskResult tickTransferring(MinecraftClient mc) {
		if (!isContainerScreen(mc.currentScreen)) {
			if (containerTimeout > 0) {
				// 窗口尚未打开：递减超时计数作为必要等待
				containerTimeout--;
				return TaskResult.RUNNING;
			}
			// 超时仍未打开窗口：单次失败，弹窗提示后直接以瞬态失败结束（不再重试），由上层状态机重新派发
			AutoTrade.logger.warn("[ContainerIO] 容器窗口打开失败（超时 {} tick），放弃本次操作",
					Configs.Generic.OPEN_TIMEOUT.getIntegerValue());
			InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "autotrade.message.io.open_failed", 1);
			return TaskResult.failed(FailReason.TRANSIENT);
		}
		containerTimeout = 0;

		ScreenHandler handler = screenHandlerOf(mc.currentScreen);

		if (transferred >= transferLimit) {
			state = State.CLOSING;
			return TaskResult.RUNNING;
		}

		boolean clicked = intent.isInput() ? transferItem(mc, handler, true) : transferItem(mc, handler, false);

		if (clicked) {
			transferred++;
		} else {
			state = State.CLOSING;
		}
		return TaskResult.RUNNING;
	}

	// 移动单个匹配物品：
	// 输入操作：从容器槽位快速移动到玩家背包（遍历到玩家背包槽位即停止）；
	// 输出操作：从玩家背包槽位快速移动到容器（跳过非玩家背包槽位）。
	private boolean transferItem(MinecraftClient mc, ScreenHandler handler, boolean isInputOp) {
		if (mc.player == null || mc.interactionManager == null) {
			return false;
		}

		// 转移目标物品直接取自条目自身（输入/输出共用条目物品）
		String targetItem = ItemStringHelper.getItemId(intent.io().getItem());

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

	private TaskResult tickClosing(MinecraftClient mc) {
		closeScreenIfOpen(mc.currentScreen);
		return TaskResult.SUCCEEDED;
	}

	/** 返回当前任务状态枚举（HUD 只读展示用） */
	public State getState() {
		return state;
	}
}