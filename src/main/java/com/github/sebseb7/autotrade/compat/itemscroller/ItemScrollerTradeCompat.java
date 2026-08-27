package com.github.sebseb7.autotrade.compat.itemscroller;

import com.github.sebseb7.autotrade.AutoTrade;
import fi.dy.masa.itemscroller.villager.IMerchantScreenHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

// ItemScroller 收藏交易兼容层：ItemScroller 通过 mixin 把 handler.getRecipes() 替换为「收藏优先」的
// 重排列表，本类提供真实（未重排）列表与可见/真实索引换算。无 ItemScroller 时全部方法回退原语义，
// 行为与现状逐字节等价。运行时类引用仅在 ITEMSCROLLER_LOADED 分支触达（懒加载，未装 ItemScroller 不加载
// 其类，无 NoClassDefFoundError）。
public final class ItemScrollerTradeCompat {
	// 静态常量：类首次被触达时求值（交易 tick 阶段，远晚于 mod 初始化），FabricLoader 可用
	private static final boolean ITEMSCROLLER_LOADED = FabricLoader.getInstance().isModLoaded("itemscroller");

	private ItemScrollerTradeCompat() {
	}

	/**
	 * 当前窗口的真实（服务端同步、未重排）交易列表：ItemScroller 加载时取 getOriginalList()，否则
	 * handler.getRecipes()
	 */
	public static TradeOfferList getOriginalRecipes(MerchantScreenHandler handler) {
		if (ITEMSCROLLER_LOADED) {
			try {
				if (handler instanceof IMerchantScreenHandler originalProvider) {
					TradeOfferList original = originalProvider.getOriginalList();
					if (original != null) {
						return original;
					}
				}
			} catch (Throwable t) {
				// 防御：极端情况（itemscroller 版本异常/类加载失败等）回退重排列表 = 现状行为
				AutoTrade.logger.warn("[ItemScrollerCompat] 获取原始交易列表失败，回退 getRecipes()", t);
			}
		}
		return handler.getRecipes();
	}

	// 真实索引 → 可见索引（用于 switchTo 装填：switchTo 内部读 getRecipes() = 重排列表，须用可见索引取到
	// 正确的装填物品）。重排列表与原列表元素保持同一对象引用（TradeOffer 未重写 equals，官方
	// getRealTradeIndexFor 亦用 indexOf 引用比较）→ 按引用反查可靠。原版环境恒等于 realIndex。
	public static int getVisibleIndex(MerchantScreenHandler handler, int realIndex, TradeOffer offer) {
		if (!ITEMSCROLLER_LOADED) {
			return realIndex;
		}
		TradeOfferList visible = handler.getRecipes();
		if (visible == null) {
			return realIndex;
		}
		for (int i = 0; i < visible.size(); i++) {
			if (visible.get(i) == offer) {
				return i;
			}
		}
		// 理论不可达（两列表元素同引用）：回退原索引，不改变现状行为
		return realIndex;
	}
}
