package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.AutoTrade;
import com.github.sebseb7.autotrade.util.ItemStringHelper;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * 交易画面处理逻辑：将村民的 TradeOffer 与配置的交易对匹配，执行交易并收集结果。 由 TradeSession 在每个 tick 调用。
 */
public class MerchantTradeExecutor {
	private int tradeCooldownTicks = 0;

	private static final int TRADE_COOLDOWN_TICKS = 2;

	public MerchantTradeExecutor() {
	}

	/**
	 * 基于 tick 的交易画面处理。每个 tick 执行一步。
	 *
	 * @return true 表示可能还有更多工作（下个 tick 继续），false 表示无更多匹配交易（调用者应关闭画面）
	 */
	public boolean handleMerchantScreenTick(MinecraftClient mc, MerchantScreen screen) {
		// 玩家或世界为空（如退出世界/传送中）时无法继续交易，等待下个 tick
		if (mc.player == null || mc.world == null) {
			return true;
		}

		MerchantScreenHandler handler = screen.getScreenHandler();
		TradeOfferList offers = handler.getRecipes();

		// 第 1 步：交易间冷却（防止过快连点导致服务端拒绝）
		if (tradeCooldownTicks > 0) {
			tradeCooldownTicks--;
			return true;
		}

		// 第 2 步：如果结果槽有物品，先移走（附魔书需要特殊处理）
		Slot slot2 = handler.getSlot(2);
		if (slot2.hasStack()) {
			AutoTrade.logger.info("[AutoTrade] Slot 2 has item, quick-moving: {}x{}", slot2.getStack().getCount(),
					Registries.ITEM.getId(slot2.getStack().getItem()));
			quickMoveOrPickupResult(mc, handler, slot2);
			return true;
		}

		// 第 3 步：尝试执行一个匹配的交易
		List<TradePair> pairs = TradePair.loadAllPairs();

		if (offers == null || offers.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] Offers not yet synced, waiting...");
			return true;
		}

		if (pairs.isEmpty()) {
			AutoTrade.logger.info("[AutoTrade] No trade pairs configured");
			return false;
		}

		for (int i = 0; i < offers.size(); i++) {
			TradeOffer offer = offers.get(i);
			if (offer.isDisabled())
				continue;
			if (offer.getUses() >= offer.getMaxUses())
				continue;

			for (TradePair pair : pairs) {
				if (!pair.isEnabled())
					continue;
				if (!doesOfferMatchPair(offer, pair))
					continue;

				int price = offer.getAdjustedFirstBuyItem().getCount();
				if (price > pair.getLimit())
					continue;
				if (!playerHasMerchantCosts(mc.player, offer))
					continue;

				AutoTrade.logger.info("[AutoTrade] EXECUTING trade offer {} pair(give={} get={})", i,
						ItemStringHelper.getItemId(pair.getGiveItem()), ItemStringHelper.getItemId(pair.getGetItem()));
				executeOneTrade(mc, handler, i, offer, pair);
				return true;
			}
		}

		// 第 4 步：没有更多匹配交易
		AutoTrade.logger.info("[AutoTrade] No more matching/affordable trades");
		clearDefer();
		return false;
	}

	// 执行单笔交易：切换到对应交易槽 → 发送选择包 → 快速移动结果 → 本地同步计数
	private void executeOneTrade(MinecraftClient mc, MerchantScreenHandler handler, int tradeIndex, TradeOffer offer,
			TradePair pair) {
		ItemStack resultItem = offer.getSellItem();
		AutoTrade.logger.info("[AutoTrade] EXECUTING trade offer {} result={}", tradeIndex,
				Registries.ITEM.getId(resultItem.getItem()));

		handler.switchTo(tradeIndex);
		if (mc.getNetworkHandler() != null) {
			mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(tradeIndex));
		}
		try {
			mc.interactionManager.clickSlot(handler.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] 点击交易结果槽失败", e);
		}

		// 本地同步：手动递增 uses 计数，避免下次 tick 重复匹配同一交易
		offer.use();

		String resultId = Registries.ITEM.getId(offer.getSellItem().getItem()).toString();
		if (resultId.equals(pair.getGetItem())) {
			AutoTrade.bought += offer.getSellItem().getCount();
		} else {
			AutoTrade.sold += offer.getAdjustedFirstBuyItem().getCount();
		}
		tradeCooldownTicks = TRADE_COOLDOWN_TICKS;
	}

	// 移走结果槽物品：附魔书不能直接快速移动（QUICK_MOVE 会丢失），需手动拿起再放入背包
	private void quickMoveOrPickupResult(MinecraftClient mc, MerchantScreenHandler handler, Slot slot) {
		ItemStack stack = slot.getStack();
		if (stack.isEmpty())
			return;

		if (stack.isOf(Items.ENCHANTED_BOOK) && stack.hasNbt() && stack.getNbt().contains("StoredEnchantments")) {
			pickupEnchantedBook(mc, handler, slot);
		} else {
			try {
				mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, mc.player);
			} catch (Exception e) {
				AutoTrade.logger.warn("[AutoTrade] quick-move result failed", e);
			}
		}
	}

	// 附魔书拾取：拿起结果 → 找到可堆叠的空位/同物品槽 → 放下
	private void pickupEnchantedBook(MinecraftClient mc, MerchantScreenHandler handler, Slot slot) {
		try {
			mc.interactionManager.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, mc.player);
			ItemStack carried = handler.getCursorStack();
			if (!carried.isEmpty()) {
				for (int i = 0; i < handler.slots.size(); i++) {
					Slot s = handler.getSlot(i);
					if (s.inventory instanceof net.minecraft.entity.player.PlayerInventory) {
						ItemStack existing = s.getStack();
						// 优先放入空格，其次放入可堆叠的同物品槽
						if (existing.isEmpty()) {
							mc.interactionManager.clickSlot(handler.syncId, s.id, 0, SlotActionType.PICKUP, mc.player);
							break;
						}
						if (existing.isOf(carried.getItem()) && ItemStack.areEqual(existing, carried)
								&& existing.getCount() < existing.getMaxCount()) {
							mc.interactionManager.clickSlot(handler.syncId, s.id, 0, SlotActionType.PICKUP, mc.player);
							break;
						}
					}
				}
			}
		} catch (Exception e) {
			AutoTrade.logger.warn("[AutoTrade] enchanted book pickup failed", e);
		}
	}

	// ---- 静态匹配辅助方法 ----

	// 判断交易与交易对是否匹配：成本物品等于 giveItem 且产出物品等于 getItem
	static boolean doesOfferMatchPair(TradeOffer offer, TradePair pair) {
		ItemStack costItem = offer.getAdjustedFirstBuyItem();
		ItemStack resultItem = offer.getSellItem();
		boolean costMatch = ItemStringHelper.matches(costItem, pair.getGiveItem());
		boolean resultMatch = ItemStringHelper.matches(resultItem, pair.getGetItem());
		return costMatch && resultMatch;
	}

	// 检查玩家背包是否足以支付该交易的全部成本槽（第一/第二成本物品）
	static boolean playerHasMerchantCosts(PlayerEntity player, TradeOffer offer) {
		ItemStack costA = offer.getAdjustedFirstBuyItem();
		if (!costA.isEmpty() && !hasEnough(player, costA)) {
			return false;
		}
		ItemStack costB = offer.getSecondBuyItem();
		if (!costB.isEmpty() && !hasEnough(player, costB)) {
			return false;
		}
		return true;
	}

	// 统计背包中与 required 精确匹配（含 NBT）的物品总数是否达到所需数量
	private static boolean hasEnough(PlayerEntity player, ItemStack required) {
		int need = required.getCount();
		int have = 0;
		PlayerInventory inv = player.getInventory();
		for (int s = 0; s < inv.size(); s++) {
			ItemStack stack = inv.getStack(s);
			if (stacksMatchExact(stack, required)) {
				have += stack.getCount();
				if (have >= need)
					return true;
			}
		}
		return false;
	}

	// 物品精确匹配：物品 ID 相同且 NBT 完全相同（空 NBT 与 null 视为等价）
	static boolean stacksMatchExact(ItemStack a, ItemStack b) {
		if (a.isEmpty() || b.isEmpty())
			return false;
		if (!a.isOf(b.getItem()))
			return false;
		NbtCompound tagA = a.getNbt();
		NbtCompound tagB = b.getNbt();
		if (tagA == null && tagB == null)
			return true;
		if (tagA == null || tagB == null)
			return false;
		return tagA.equals(tagB);
	}

	void clearDefer() {
		tradeCooldownTicks = 0;
	}
}
