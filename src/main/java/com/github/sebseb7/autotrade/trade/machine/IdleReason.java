package com.github.sebseb7.autotrade.trade.machine;

import java.util.Locale;

/**
 * 机器空闲原因枚举（调试 HUD 只读展示用）：描述「当前为何没有在交易」—— BUSY 三值由
 * {@code AbstractTradeMachine#getIdleReason()} 按运行中任务类型派生，其余值由各空闲分支就地写入。 键名格式由
 * {@link #translationKey()} 单点实现（禁止在别处拼接 i18n 键）。
 */
public enum IdleReason {
	/** 无：无任务且本 tick 未命中任何具体空闲原因（瞬态兜底） */
	NONE,
	/** 正在交易：TradeTask 运行中（BUSY，按任务类型派生） */
	TRADING,
	/** 正在容器搬运：ContainerIOTask 运行中（BUSY，按任务类型派生） */
	CONTAINER_IO,
	/** 正在回程触发：BlockTriggerTask 运行中（BUSY，按任务类型派生） */
	RETURN_TRIGGER,
	/** 背包满暂停：背包满退避冷却剩余 >0，暂停启动交易会话 */
	INVENTORY_FULL,
	/** 轮冷却：STATIC 一轮交易结束后的轮间隔（Trade Interval）尚未到期 */
	ROUND_COOLDOWN,
	/** IO 间隔：STATIC 两次容器操作之间的最小间隔（Container IO Interval）未到期 */
	IO_INTERVAL,
	/** 无村民：扫描范围内没有任何可交易村民 */
	NO_VILLAGER,
	/** 全部已处理：范围内村民本局均已处理完（无可再交易目标） */
	ALL_PROCESSED,
	/** 缓存跳过：TTL 内已知不匹配的村民被村民交易缓存跳过 */
	CACHE_SKIP,
	/** 村民消失：名单中的村民在派发前从世界消失（实体缺失） */
	VILLAGER_GONE,
	/** 无候选：MOVING 范围内当前没有可服务的目标（含范围/距离过滤后为空） */
	NO_CANDIDATE,
	/** 村民重试：VOID 失败村民处于重试冷却期内 */
	VILLAGER_RETRY,
	/** 未配回程：VOID 未配置返回触发块（类型/坐标），回程链路硬阻断 */
	RETURN_TRIGGER_NOT_CONFIGURED,
	/** 回程等待：VOID 已配回程但当前不可用/不可达/被屏幕阻挡/处于重试节流 */
	RETURN_TRIGGER_WAIT;

	/** i18n 键名唯一实现点：autotrade.debug.idle.<name lowercase> */
	public String translationKey() {
		return "autotrade.debug.idle." + name().toLowerCase(Locale.ROOT);
	}
}
