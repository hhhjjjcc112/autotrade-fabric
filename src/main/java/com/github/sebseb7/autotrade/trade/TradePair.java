package com.github.sebseb7.autotrade.trade;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.TradePairList;
import java.util.List;

/**
 * 表示一个交易对：玩家给出 {@code giveItem} 并收到 {@code getItem}，
 * 带有每次交易最大价格限制、可选的每对容器配置，以及启用/禁用开关。
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
