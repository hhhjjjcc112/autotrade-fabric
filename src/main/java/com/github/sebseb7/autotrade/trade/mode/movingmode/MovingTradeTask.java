package com.github.sebseb7.autotrade.trade.mode.movingmode;

import com.github.sebseb7.autotrade.trade.task.TradeTask;

/**
 * MOVING 模式会话：单村民直链 FSM。 目标村民由机器层评分选中后通过构造器注入（选中即锁定，修复机器层选 A、 会话内重扫取到 B
 * 的竞态）；候选收集与处理记录在机器层维护，本会话不再自行扫描。
 */
public class MovingTradeTask extends TradeTask {

	public MovingTradeTask(int villagerActiveId) {
		super(villagerActiveId);
	}

	@Override
	protected boolean useVoidDelay() {
		return false;
	}
}
