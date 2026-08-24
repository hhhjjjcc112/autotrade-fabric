package com.github.sebseb7.autotrade.trade.executor;

import com.github.sebseb7.autotrade.config.Configs;
import com.github.sebseb7.autotrade.config.ExecutorMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;

/**
 * 交易执行策略门面：外部契约不变，按 TRADE_EXECUTOR_MODE 配置选择实现（USE 默认 / OUTPUT_SLOT 可选），
 * 实现与共享逻辑位于同包独立文件。
 */
public class TradeExecutor {
	/** 当前交易执行策略（按 TRADE_EXECUTOR_MODE 配置选择：USE 默认 / OUTPUT_SLOT 可选） */
	private final TradeStrategy strategy;

	public TradeExecutor() {
		// 按配置选择执行策略：USE（默认，直接读 uses，依赖本地点击模拟保真度）/ OUTPUT_SLOT（可选，快照推导不读
		// uses）；配置改动于下个会话（TradeTask 新建 executor 时）生效
		this.strategy = ((ExecutorMode) Configs.Generic.TRADE_EXECUTOR_MODE.getOptionListValue()) == ExecutorMode.USE
				? new UseBasedExecutorStrategy()
				: new OutputSlotExecutorStrategy();
	}

	/**
	 * 交易画面处理：委托给当前策略实现。
	 *
	 * @return true 表示画面数据未就绪（下个 tick 继续），false 表示会话结束（调用者应关闭画面）
	 */
	public boolean handleMerchantScreenTick(MinecraftClient mc, MerchantScreen screen) {
		return strategy.handleMerchantScreenTick(mc, screen);
	}

	/** 上次交易扫描是否因背包空间不足而跳过全部匹配交易（会话层据此提前结束并触发容器 IO） */
	public boolean isInventoryBlocked() {
		return strategy.isInventoryBlocked();
	}
}