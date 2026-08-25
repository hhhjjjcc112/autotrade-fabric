package com.github.sebseb7.autotrade.trade.data;

/**
 * 表示一个交易对：玩家给出 {@code giveItem}（可选地再加 {@code giveItem2}）并收到 {@code getItem}，
 * 带有每次交易最大价格限制以及启用/禁用开关。
 *
 * <p>
 * {@code giveItem2} 是可选的双成本第二给出物品：留空表示单成本交易对（只匹配单成本交易）。
 * </p>
 *
 * <p>
 * 交易对以动态 JSON 列表形式存储在 config 中。使用 {@link TradePairCache#getAll()} 获取所有已配置的交易对。
 * </p>
 */
public final class TradePair {
	private String giveItem;
	private String getItem;
	private int limit;
	private boolean enabled;

	private String note = "";

	/** 可选第二给出物品；空串表示单成本交易对 */
	private String giveItem2 = "";
	/** 第二 give 每笔交易的数量（0 = 未记录/不显示） */
	private int give2Count;
	/** 产出物品每笔交易的数量（0 = 未记录/不显示） */
	private int getCount;

	public TradePair(String giveItem, String getItem, int limit, boolean enabled, String giveItem2, int give2Count,
			int getCount, String note) {
		this.giveItem = giveItem;
		this.getItem = getItem;
		this.limit = limit;
		this.enabled = enabled;
		this.giveItem2 = giveItem2;
		this.give2Count = give2Count;
		this.getCount = getCount;
		this.note = note;
	}

	/** 拷贝构造：复制全部 8 个字段（String 为不可变对象，直接引用复制即可） */
	public TradePair(TradePair other) {
		this.giveItem = other.giveItem;
		this.getItem = other.getItem;
		this.limit = other.limit;
		this.enabled = other.enabled;
		this.giveItem2 = other.giveItem2;
		this.give2Count = other.give2Count;
		this.getCount = other.getCount;
		this.note = other.note;
	}

	public void setGiveItem(String v) {
		this.giveItem = v;
	}
	public void setGetItem(String v) {
		this.getItem = v;
	}
	public void setLimit(int v) {
		this.limit = v;
	}
	public void setEnabled(boolean v) {
		this.enabled = v;
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
	public String getGiveItem2() {
		return giveItem2;
	}
	public void setGiveItem2(String v) {
		this.giveItem2 = v;
	}
	public int getGive2Count() {
		return give2Count;
	}
	public void setGive2Count(int v) {
		this.give2Count = v;
	}
	public int getGetCount() {
		return getCount;
	}
	public void setGetCount(int v) {
		this.getCount = v;
	}
	public String getNote() {
		return note;
	}
	public void setNote(String v) {
		this.note = v;
	}
}