package com.github.sebseb7.autotrade.event;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.util.TradePairList;
import java.util.List;

/**
 * Represents a single trade pair: the player gives {@code giveItem} and
 * receives {@code getItem}, with a maximum cost limit per trade.
 *
 * <p>
 * Pairs are stored as a dynamic JSON list in the config. Use
 * {@link #loadAllPairs()} to get all configured pairs.
 * </p>
 */
public final class TradePair {
	private final String giveItem;
	private final String getItem;
	private final int limit;
	private final boolean enabled;

	public TradePair(String giveItem, String getItem, int limit, boolean enabled) {
		this.giveItem = giveItem;
		this.getItem = getItem;
		this.limit = limit;
		this.enabled = enabled;
	}

	public String getGiveItem() {
		return giveItem;
	}

	public String getGetItem() {
		return getItem;
	}

	public int getLimit() {
		return limit;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Loads all trade pairs from the JSON config.
	 */
	public static List<TradePair> loadAllPairs() {
		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		return TradePairList.fromJson(json);
	}
}
