package com.github.sebseb7.autotrade.trade.mode.staticmode;

import com.github.sebseb7.autotrade.trade.task.TradeTask;

/**
 * STATIC 模式会话：单村民直链 FSM。 目标村民由机器层扫描名单后通过构造器注入，本会话不再自行扫描； 已处理标记与交易冷却由机器层
 * onTaskEnded/tickIdle 统一维护。
 */
public class StaticTradeTask extends TradeTask {

	public StaticTradeTask(int villagerActiveId) {
		super(villagerActiveId);
	}

	@Override
	protected boolean useVoidDelay() {
		return false;
	}
}
