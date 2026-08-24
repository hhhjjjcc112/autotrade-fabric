package com.github.sebseb7.autotrade.trade.executor;

import net.minecraft.village.TradeOffer;

// use 版策略（默认实现）：直接读 offer.getUses()（本地模拟同步值），逻辑更简；依赖本地点击模拟保真度（MC_MECHANISMS §一 1.4.2）
final class UseBasedExecutorStrategy extends AbstractTradeStrategy {
	@Override
	protected OfferState createOffer(TradeOffer offer, int index, boolean starvationCandidate) {
		return new LiveOfferState(offer, index, starvationCandidate);
	}

	// use 版实现的状态：直接读 offer.getUses()（本地点击模拟同步更新，无需快照/记账；record 用基类空实现）
	private static final class LiveOfferState extends OfferState {
		LiveOfferState(TradeOffer offer, int index, boolean starvationCandidate) {
			super(offer, index, starvationCandidate);
		}

		@Override
		int remaining() {
			return offer.getMaxUses() - offer.getUses();
		}

		@Override
		boolean exhausted(int batchTradesDone) {
			return offer.getUses() >= offer.getMaxUses();
		}
	}
}