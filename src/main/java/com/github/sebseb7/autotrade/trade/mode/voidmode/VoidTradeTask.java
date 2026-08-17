package com.github.sebseb7.autotrade.trade.mode.voidmode;

import com.github.sebseb7.autotrade.trade.task.TradeTask;

/**
 * VOID 模式会话：单村民直链 FSM。 虚空交易针对**同一个村民**反复卸载-加载（加载交互 → 玩家传送卸载 → 交易 → 传回重载 →
 * 再交易），目标村民由机器层取最近村民后通过构造器注入。 单村民语义下无需任何村民处理记录——交易完成后下轮会话自然再次选中同一村民，形成无限交易循环。 与
 * STATIC 的差别仅在于： ① 窗口打开后须等村民消失（玩家传送完成）才交易（等待传送确认时序见 TradeTask）； ②
 * 无会话间冷却，处理完立即重新决策（机器层 tickIdle）。
 */
public class VoidTradeTask extends TradeTask {

	public VoidTradeTask(int villagerActiveId) {
		super(villagerActiveId);
	}

	@Override
	protected boolean useVoidDelay() {
		return true;
	}
}
