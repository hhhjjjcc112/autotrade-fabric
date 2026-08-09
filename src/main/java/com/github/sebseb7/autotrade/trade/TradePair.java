package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradePairList;
import java.util.List;

/**
 * 表示一个交易对：玩家给出 {@code giveItem}（可选地再加 {@code giveItem2}）并收到 {@code getItem}，
 * 带有每次交易最大价格限制、可选的每对容器配置，以及启用/禁用开关。
 *
 * <p>
 * {@code giveItem2} 是可选的双成本第二给出物品：留空表示单成本交易对（只匹配单成本交易）；非空时
 * 拥有独立的输入容器配置（{@code give2InputEnabled} + {@code give2InputX/Y/Z}）。
 * </p>
 *
 * <p>
 * 交易对以动态 JSON 列表形式存储在 config 中。使用 {@link #loadAllPairs()} 获取所有已配置的交易对。
 * </p>
 */
public final class TradePair {
	private String giveItem;
	private String getItem;
	private int limit;
	private boolean enabled;

	private String note = "";

	private boolean inputEnabled;
	private int inputX;
	private int inputY;
	private int inputZ;
	private boolean outputEnabled;
	private int outputX;
	private int outputY;
	private int outputZ;
	private int inputThreshold;
	private int inputTakeAmount;
	private int outputThreshold;

	/** 可选第二给出物品；空串表示单成本交易对 */
	private String giveItem2 = "";
	/** 第二给出物品的独立输入容器开关 */
	private boolean give2InputEnabled = false;
	/** 第二给出物品输入容器坐标 */
	private int give2InputX, give2InputY, give2InputZ;
	/** 第二给出物品补货阈值（占用槽位数，默认 1 = 剩 1 组时补货） */
	private int give2InputThreshold = 1;
	/** 第二 give 每笔交易的数量（0 = 未记录/不显示） */
	private int give2Count;
	/** 产出物品每笔交易的数量（0 = 未记录/不显示） */
	private int getCount;

	public TradePair(String giveItem, String getItem, int limit, boolean enabled, boolean inputEnabled, int inputX,
			int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ, int inputThreshold,
			int inputTakeAmount, int outputThreshold) {
		this.giveItem = giveItem;
		this.getItem = getItem;
		this.limit = limit;
		this.enabled = enabled;
		this.inputEnabled = inputEnabled;
		this.inputX = inputX;
		this.inputY = inputY;
		this.inputZ = inputZ;
		this.outputEnabled = outputEnabled;
		this.outputX = outputX;
		this.outputY = outputY;
		this.outputZ = outputZ;
		this.inputThreshold = inputThreshold;
		this.inputTakeAmount = inputTakeAmount;
		this.outputThreshold = outputThreshold;
	}

	/** 含可选第二给出物品的完整构造；giveItem2 为空串时表示单成本交易对 */
	public TradePair(String giveItem, String getItem, int limit, boolean enabled, boolean inputEnabled, int inputX,
			int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ, int inputThreshold,
			int inputTakeAmount, int outputThreshold, String giveItem2, boolean give2InputEnabled, int give2InputX,
			int give2InputY, int give2InputZ) {
		this(giveItem, getItem, limit, enabled, inputEnabled, inputX, inputY, inputZ, outputEnabled, outputX, outputY,
				outputZ, inputThreshold, inputTakeAmount, outputThreshold);
		this.giveItem2 = giveItem2;
		this.give2InputEnabled = give2InputEnabled;
		this.give2InputX = give2InputX;
		this.give2InputY = give2InputY;
		this.give2InputZ = give2InputZ;
	}

	/** 含每笔交易数量（give2/get）的完整构造；give2Count/getCount 为 0 表示未记录（列表不显示数量） */
	public TradePair(String giveItem, String getItem, int limit, boolean enabled, boolean inputEnabled, int inputX,
			int inputY, int inputZ, boolean outputEnabled, int outputX, int outputY, int outputZ, int inputThreshold,
			int inputTakeAmount, int outputThreshold, String giveItem2, boolean give2InputEnabled, int give2InputX,
			int give2InputY, int give2InputZ, int give2Count, int getCount) {
		this(giveItem, getItem, limit, enabled, inputEnabled, inputX, inputY, inputZ, outputEnabled, outputX, outputY,
				outputZ, inputThreshold, inputTakeAmount, outputThreshold, giveItem2, give2InputEnabled, give2InputX,
				give2InputY, give2InputZ);
		this.give2Count = give2Count;
		this.getCount = getCount;
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
	public void setInputEnabled(boolean v) {
		this.inputEnabled = v;
	}
	public void setInputX(int v) {
		this.inputX = v;
	}
	public void setInputY(int v) {
		this.inputY = v;
	}
	public void setInputZ(int v) {
		this.inputZ = v;
	}
	public void setOutputEnabled(boolean v) {
		this.outputEnabled = v;
	}
	public void setOutputX(int v) {
		this.outputX = v;
	}
	public void setOutputY(int v) {
		this.outputY = v;
	}
	public void setOutputZ(int v) {
		this.outputZ = v;
	}
	public void setInputThreshold(int v) {
		this.inputThreshold = v;
	}
	public void setInputTakeAmount(int v) {
		this.inputTakeAmount = v;
	}
	public void setOutputThreshold(int v) {
		this.outputThreshold = v;
	}
	public void setGive2InputThreshold(int v) {
		this.give2InputThreshold = v;
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
	public boolean isInputEnabled() {
		return inputEnabled;
	}
	public int getInputX() {
		return inputX;
	}
	public int getInputY() {
		return inputY;
	}
	public int getInputZ() {
		return inputZ;
	}
	public boolean isOutputEnabled() {
		return outputEnabled;
	}
	public int getOutputX() {
		return outputX;
	}
	public int getOutputY() {
		return outputY;
	}
	public int getOutputZ() {
		return outputZ;
	}
	public int getInputThreshold() {
		return inputThreshold;
	}
	public int getInputTakeAmount() {
		return inputTakeAmount;
	}
	public int getOutputThreshold() {
		return outputThreshold;
	}
	public int getGive2InputThreshold() {
		return give2InputThreshold;
	}
	public String getGiveItem2() {
		return giveItem2;
	}
	public void setGiveItem2(String v) {
		this.giveItem2 = v;
	}
	public boolean isGive2InputEnabled() {
		return give2InputEnabled;
	}
	public void setGive2InputEnabled(boolean v) {
		this.give2InputEnabled = v;
	}
	public int getGive2InputX() {
		return give2InputX;
	}
	public void setGive2InputX(int v) {
		this.give2InputX = v;
	}
	public int getGive2InputY() {
		return give2InputY;
	}
	public void setGive2InputY(int v) {
		this.give2InputY = v;
	}
	public int getGive2InputZ() {
		return give2InputZ;
	}
	public void setGive2InputZ(int v) {
		this.give2InputZ = v;
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

	/** 从 JSON 配置加载所有交易对 */
	public static List<TradePair> loadAllPairs() {
		String json = Configs.Generic.TRADE_PAIRS.getStringValue();
		return TradePairList.fromJson(json);
	}
}
