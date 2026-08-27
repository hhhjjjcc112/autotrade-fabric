package com.github.sebseb7.autotrade.trade.stats;

/**
 * 交易统计单例：仅用于记录调试 HUD 展示所需的统计数据（会话/累计成交数与容器 IO 操作数）。 纯记录模块——不参与任何交易/IO
 * 决策逻辑，全部调用均发生在客户端线程（无需线程同步）， 且不得抛异常（交易引擎在热路径上调用本类）。
 */
public class TradeStats {

	private static final TradeStats INSTANCE = new TradeStats();

	/** 累计成交数（跨全部会话） */
	private long totalTrades;
	/** 最近一次会话的成交数（含 0 笔会话） */
	private int lastSessionTrades;
	/** 成功完成的容器输入操作数 */
	private long ioInputOps;
	/** 成功完成的容器输出操作数 */
	private long ioOutputOps;

	private TradeStats() {
	}

	public static TradeStats getInstance() {
		return INSTANCE;
	}

	/**
	 * 记录一次会话结束：把本次成交数累加到累计成交数，并更新最近一次会话成交数。 所有会话结束出口（含 0 笔会话）都应调用，保证 HUD
	 * 会话计数不显示过期值。
	 */
	public void recordSession(int trades) {
		totalTrades += trades;
		lastSessionTrades = trades;
	}

	/**
	 * 记录一次成功完成的容器 IO 操作：input 为 true 计入输入计数，否则计入输出计数。 仅成功完成的搬运计入，失败/超时/强杀不计。
	 */
	public void recordIoOp(boolean input) {
		if (input) {
			ioInputOps++;
		} else {
			ioOutputOps++;
		}
	}

	/**
	 * 清零全部统计字段（热键关闭交易时调用，统计与机器状态同生命周期；设置页勾选启用不清零）。
	 */
	public void reset() {
		totalTrades = 0;
		lastSessionTrades = 0;
		ioInputOps = 0;
		ioOutputOps = 0;
	}

	public long getTotalTrades() {
		return totalTrades;
	}

	public int getLastSessionTrades() {
		return lastSessionTrades;
	}

	public long getIoInputOps() {
		return ioInputOps;
	}

	public long getIoOutputOps() {
		return ioOutputOps;
	}
}
