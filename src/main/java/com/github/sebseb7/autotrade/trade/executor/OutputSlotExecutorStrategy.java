package com.github.sebseb7.autotrade.trade.executor;

import net.minecraft.village.TradeOffer;

// 输出槽推导策略（可选实现，不读 uses）：继承共享流程，保守语义（uses 仅扫描时刻快照读取，循环内零 uses）
final class OutputSlotExecutorStrategy extends AbstractTradeStrategy {
	@Override
	protected OfferState createOffer(TradeOffer offer, int index, boolean starvationCandidate) {
		return new SnapshotOfferState(offer, index, starvationCandidate);
	}

	// 非 use 实现的状态：扫描时刻快照 initialRemaining + 跨 pass 记账 tradesDone（现状推导语义逐字迁移）
	private static final class SnapshotOfferState extends OfferState {
		final int initialRemaining;
		int tradesDone;

		SnapshotOfferState(TradeOffer offer, int index, boolean starvationCandidate) {
			super(offer, index, starvationCandidate);
			this.initialRemaining = offer.getMaxUses() - offer.getUses();
		}

		@Override
		int remaining() {
			return initialRemaining - tradesDone;
		}

		@Override
		boolean exhausted(int batchTradesDone) {
			return initialRemaining - (tradesDone + batchTradesDone) == 0;
		}

		@Override
		void record(int tradesDone) {
			this.tradesDone += tradesDone;
		}
	}
}