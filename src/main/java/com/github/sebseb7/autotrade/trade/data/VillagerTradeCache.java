package com.github.sebseb7.autotrade.trade.data;

import com.github.sebseb7.autotrade.config.Configs;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民交易记忆缓存：记住每个村民最近一次会话的交易结果，TTL 内跳过已知不匹配村民的开窗。 分两类跳过（各自 TTL）：
 * 完全无匹配交易对（TRADE_CACHE_TTL，主开关）与有匹配交易对但本会话无可执行交易（SKIP_OPEN_TTL，耗尽 / 成本不足）。
 */
public final class VillagerTradeCache {
	/** 村民 UUID → 学习条目；ConcurrentHashMap 保证 HUD 渲染线程读 size() 与 tick 线程写并发安全 */
	private static final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
	/** 交易对配置串指纹：配置变更即整表失效 */
	private static String learnedPairsJson = null;
	/** 实际跳过次数；volatile 保证渲染线程读与 tick 线程写可见性 */
	private static volatile long skipCount = 0;

	private VillagerTradeCache() {
	}

	/**
	 * 单条学习记录：是否命中 + 是否有匹配交易对但无执行 + 学习时点 tick（命中条目无 TTL，仅不命中用 learnTick
	 * 判定过期；pairMatched 区分两类不命中，决定用哪个 TTL）
	 */
	record Entry(boolean matched, boolean pairMatched, long learnTick) {
	}

	/** 缓存总开关：TTL 为 0 即完全禁用 */
	private static boolean isCacheEnabled() {
		return Configs.Generic.TRADE_CACHE_TTL.getIntegerValue() > 0;
	}

	/** 指纹比对：交易对配置串变化则整表清空重学（不用 malilib 回调，避免顶掉 TradePairCache 的单槽回调） */
	private static void ensureFingerprintFresh() {
		String current = Configs.Generic.TRADE_PAIRS.getStringValue();
		// 首次访问只记录指纹，不清空
		if (learnedPairsJson == null) {
			learnedPairsJson = current;
			return;
		}
		// 配置变更 → 整表失效
		if (!learnedPairsJson.equals(current)) {
			entries.clear();
			learnedPairsJson = current;
		}
	}

	/** 学习一次会话结果：写入村民的命中 / 不命中（pairMatched = 有匹配交易对但无执行）记录 */
	public static void learn(UUID uuid, boolean matched, boolean pairMatched, long learnTick) {
		if (!isCacheEnabled()) {
			return;
		}
		ensureFingerprintFresh();
		entries.put(uuid, new Entry(matched, pairMatched, learnTick));
	}

	/** 是否为 TTL 内已知不匹配：纯函数，无计数副作用（未知/命中/过期均返回 false = 需要开窗） */
	public static boolean isNotMatch(UUID uuid, long nowTick) {
		if (!isCacheEnabled()) {
			return false;
		}
		ensureFingerprintFresh();
		Entry e = entries.get(uuid);
		// 未知村民 = 开窗学习
		if (e == null) {
			return false;
		}
		// 命中村民 = 正常开窗
		if (e.matched()) {
			return false;
		}
		// 按不命中情形选 TTL：有匹配但无执行（耗尽/成本不足）用 SKIP_OPEN_TTL；完全无匹配用 TRADE_CACHE_TTL
		int ttl = e.pairMatched()
				? Configs.Generic.SKIP_OPEN_TTL.getIntegerValue()
				: Configs.Generic.TRADE_CACHE_TTL.getIntegerValue();
		// 该情形 TTL 为 0 = 不跳过：移除条目并复查开窗
		if (ttl <= 0) {
			entries.remove(uuid);
			return false;
		}
		// TTL 到期 → 惰性移除并复查开窗
		if (nowTick - e.learnTick() >= ttl) {
			entries.remove(uuid);
			return false;
		}
		// 已知不匹配且 TTL 内 → 跳过
		return true;
	}

	/** 是否为已知命中：供 STATIC 名单重排，未知/不命中均返回 false */
	public static boolean isMatch(UUID uuid) {
		if (!isCacheEnabled()) {
			return false;
		}
		ensureFingerprintFresh();
		Entry e = entries.get(uuid);
		return e != null && e.matched();
	}

	/** 记录一次实际跳过：由三个跳过点显式调用（HUD 跳过数 = 实际跳过，非过滤评估次数） */
	public static void recordSkip() {
		if (!isCacheEnabled()) {
			return;
		}
		skipCount++;
	}

	/** 清空条目与跳过计数：指纹保留（指纹属于配置串生命周期，与条目无关） */
	public static void clear() {
		entries.clear();
		skipCount = 0;
	}

	/** 返回缓存条目数：HUD 只读展示 */
	public static int size() {
		return entries.size();
	}

	/** 返回自清空以来的实际跳过次数：HUD 只读展示 */
	public static long getSkipCount() {
		return skipCount;
	}
}
