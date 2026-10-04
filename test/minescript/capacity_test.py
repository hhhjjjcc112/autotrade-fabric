"""AutoTradeCapacityTest CAPACITY 自动观测脚本（Minescript）。

用途：进入 AutoTradeCapacityTest 世界后自动逐组执行 23 组「背包空位/容量检测」用例：
  1. 每组先禁用 mod 并 reset 机器，再执行 `/function autotrade_test:cap_<id>` 布置前置库存与村民；
  2. 校验前置库存是否与用例表（CAP_CASES）一致（不一致 → ok=0 并跳过启用）；
  3. reset + 启用 mod，采样至多 10s（0.5s/次）读取 TradeStats 累计/上次会话成交/已完成会话数、背包与机器空闲状态（正例与负例均在「已完成≥1 个会话 ∧ 机器空闲无在途任务 ∧ 背包==预期终态（负例且 total==0）」连续 2 次采样后退出；机器状态不可读则跑满窗口）；
  4. 禁用 mod 后采终态（背包 emerald/paper/iron_sword 计数 + 掉落实体数）。

原始数据行格式（Todo 4 `capacity_analysis.py` 逐字解析，字段序与分隔符不可改）：
  [cap] begin id=<id>
  [cap] setup id=<id> ok=<0|1> e=<n> p=<n> sw=<n>
  [cap] end id=<id> total=<n> last=<n> e=<n> p=<n> sw=<n> items=<n> enabled=<bool>
  [cap] error id=<id> err=<msg>（异常兜底，随后继续下一组）

CAP_CASES 为纯 Python 字面量（由 capacity_scenarios.CASES 离线生成后嵌入）：本脚本运行在
游戏内 Minescript 环境，**不得 import 任何仓库库**；数值供前置态校验与 Todo 2 表↔脚本同步检查。

输出：echo（本地聊天）+ log（logs/latest.log，供宿主 `test/capacity.py` 解析）。

用法：
  - 自动：run/minescript/config.txt 中 `autorun[AutoTradeCapacityTest]=capacity_test 600`
  - 手动：游戏内聊天框输入 `\\capacity_test 600`（时长秒数，可省略）
  - 保留客户端：`\\capacity_test 600 noquit`（默认观测结束后自动关闭客户端）

注意：
  - 观测结束后默认调用 MinecraftClient.scheduleStop() 关闭客户端；传 noquit 可保留。
  - minescript 库由 Minescript mod 运行时注入 sys.path；IDE 静态检查报未知导入属预期现象。
  - java_* 反射调用约定：方法先经 `java_member(cls, "name")` 取句柄，再 `java_call_method(target, handle)`。
"""

import sys
import time

from minescript import (
	echo,
	entities,
	execute,
	java_call_method,
	java_class,
	java_member,
	java_to_string,
	log,
	player_inventory,
	screen_name,
	screenshot,
)

# 23 组容量检测用例（矩阵序 a1..h2；纯字面量，供同步检查与前置态校验）
# pre/final 仅含受观注三种物品；items = 关窗后预期掉落实体数
CAP_CASES = [
	{"id": "a1", "fn": "cap_a1", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 64, "paper": 0, "iron_sword": 0}, "items": 0},
	{"id": "a2", "fn": "cap_a2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 0, "paper": 1024, "iron_sword": 0}, "items": 0},
	{"id": "a3", "fn": "cap_a3", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 64, "paper": 0, "iron_sword": 0}, "items": 0},
	{"id": "b1", "fn": "cap_b1", "pre": {"emerald": 64, "paper": 64, "iron_sword": 0}, "final": {"emerald": 52, "paper": 256, "iron_sword": 0}, "items": 0},
	{"id": "b2", "fn": "cap_b2", "pre": {"emerald": 64, "paper": 64, "iron_sword": 0}, "final": {"emerald": 52, "paper": 256, "iron_sword": 0}, "items": 0},
	{"id": "b3", "fn": "cap_b3", "pre": {"emerald": 64, "paper": 128, "iron_sword": 0}, "final": {"emerald": 64, "paper": 128, "iron_sword": 0}, "items": 0},
	{"id": "b4", "fn": "cap_b4", "pre": {"emerald": 64, "paper": 128, "iron_sword": 0}, "final": {"emerald": 64, "paper": 128, "iron_sword": 0}, "items": 0},
	{"id": "c1", "fn": "cap_c1", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 52, "paper": 0, "iron_sword": 12}, "items": 0},
	{"id": "c2", "fn": "cap_c2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 53, "paper": 0, "iron_sword": 11}, "items": 0},
	{"id": "c3", "fn": "cap_c3", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 52, "paper": 0, "iron_sword": 12}, "items": 0},
	{"id": "d1", "fn": "cap_d1", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 64, "paper": 0, "iron_sword": 0}, "items": 0},
	{"id": "d2", "fn": "cap_d2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 56, "paper": 128, "iron_sword": 0}, "items": 0},
	{"id": "e1", "fn": "cap_e1", "pre": {"emerald": 76, "paper": 0, "iron_sword": 0}, "final": {"emerald": 64, "paper": 192, "iron_sword": 0}, "items": 0},
	{"id": "e2", "fn": "cap_e2", "pre": {"emerald": 76, "paper": 0, "iron_sword": 0}, "final": {"emerald": 76, "paper": 0, "iron_sword": 0}, "items": 0},
	{"id": "e3", "fn": "cap_e3", "pre": {"emerald": 77, "paper": 0, "iron_sword": 0}, "final": {"emerald": 77, "paper": 0, "iron_sword": 0}, "items": 0},
	{"id": "f1", "fn": "cap_f1", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 52, "paper": 192, "iron_sword": 0}, "items": 0},
	{"id": "f2", "fn": "cap_f2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 40, "paper": 192, "iron_sword": 0}, "items": 0},
	{"id": "f3", "fn": "cap_f3", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 52, "paper": 96, "iron_sword": 0}, "items": 0},
	{"id": "g1", "fn": "cap_g1", "pre": {"emerald": 74, "paper": 0, "iron_sword": 0}, "final": {"emerald": 0, "paper": 704, "iron_sword": 0}, "items": 0},
	{"id": "g2", "fn": "cap_g2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 0, "paper": 320, "iron_sword": 0}, "items": 0},
	{"id": "g3", "fn": "cap_g3", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 61, "paper": 192, "iron_sword": 0}, "items": 0},
	{"id": "h1", "fn": "cap_h1", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 52, "paper": 192, "iron_sword": 0}, "items": 0},
	{"id": "h2", "fn": "cap_h2", "pre": {"emerald": 64, "paper": 0, "iron_sword": 0}, "final": {"emerald": 64, "paper": 0, "iron_sword": 0}, "items": 0},
]

DEFAULT_SECONDS = 600.0  # 默认整体时限（秒）；23 组 × ~12.5s ≈ 290s，留足余量
SETUP_WAIT = 1.2  # 执行用例函数后等待库存/村民就位的秒数
SAMPLE_INTERVAL = 0.5  # 采样间隔（秒）
SAMPLE_DURATION = 10.0  # 每组启用后的采样窗口上限（秒；会话数/机器空闲状态不可读等无提前退出证据时跑满）
EARLY_EXIT_STABLE = 2  # 提前退出判据「已完成≥1 会话 ∧ 机器空闲 ∧ 背包==预期终态」的连续成立次数（2 × 0.5s = 1s）
POST_DISABLE_WAIT = 0.6  # 停用后等待会话收尾/掉落生成的秒数
ENTITY_RANGE = 16  # 掉落实体统计半径（格）

# 受观注物品（前置/终态计数）
ITEM_IDS = ("minecraft:emerald", "minecraft:paper", "minecraft:iron_sword")

# mod 反射目标（启用/禁用 + 机器空闲状态读取用；见 _enable_mod / _disable_mod / _machine_idle）
CLS_GENERIC = "com.github.sebseb7.autotrade.config.Configs$Generic"
CLS_CONFIG_BOOLEAN = "fi.dy.masa.malilib.config.options.ConfigBoolean"
CLS_TICK = "com.github.sebseb7.autotrade.runtime.AutoTradeClientTick"
CLS_ABSTRACT = "com.github.sebseb7.autotrade.trade.machine.AbstractTradeMachine"

_CLASS_CACHE = {}  # java_class 结果缓存（类句柄稳定）；仅缓存类句柄，实例/成员句柄一律不缓存


def _cached_class(class_name):
	"""懒加载并缓存 java_class(name) 结果（类句柄稳定，避免每 0.5s 重复解析）。"""
	cls = _CLASS_CACHE.get(class_name)
	if cls is None:
		cls = java_class(class_name)
		_CLASS_CACHE[class_name] = cls
	return cls


def _as_int(value):
	"""把 java_* 返回值归一为 int。

	java_call_method 对原始类型返回值给出的是「Java 对象句柄」而非数值（如 getTotalTrades
	返回 Long 句柄），需先经 java_to_string() 解引用；句柄本身也是 int，故必须先解引用再回退。
	"""
	if value is None:
		return None
	if isinstance(value, bool):
		return int(value)
	try:
		text = str(java_to_string(value)).strip()
		if text.lstrip("-").isdigit():
			return int(text)
	except Exception:  # noqa: BLE001
		pass
	if isinstance(value, (int, float)):
		return int(value)
	return None


def _stats():
	"""反射读取 mod TradeStats 单例的累计/上次会话成交与已完成会话数；失败返回 {'error': ...}。"""
	try:
		cls = java_class("com.github.sebseb7.autotrade.trade.stats.TradeStats")
		inst = java_call_method(cls, java_member(cls, "getInstance"))
		if inst is None:
			return {"error": "TradeStats.getInstance() -> None"}
		return {
			"total": _as_int(java_call_method(inst, java_member(cls, "getTotalTrades"))),
			"last": _as_int(java_call_method(inst, java_member(cls, "getLastSessionTrades"))),
			"sessions": _as_int(java_call_method(inst, java_member(cls, "getSessionCount"))),
		}
	except Exception as exc:  # noqa: BLE001
		return {"error": f"{type(exc).__name__}: {exc}"}


def _machine_idle():
	"""反射判断当前机器是否空闲无在途任务：空闲 True / 有在途任务 False / 不可读 None。

	路径：AutoTradeClientTick.getInstance() -> getActiveMachine() 取机器句柄；getIdleReason 是
	AbstractTradeMachine 上的公共 getter，故方法句柄须从 AbstractTradeMachine 类获取。
	注意：Minescript 对 Java null 对象返回的是「对象句柄 int」而非 Python None（句柄 referent
	为 null），故不能用 `task is None` 之类身份判断；改为读 getIdleReason() 字符串：BUSY 三值
	（TRADING / CONTAINER_IO / RETURN_TRIGGER = 有在途任务）→ False，其余值（ROUND_COOLDOWN /
	INVENTORY_FULL / ALL_PROCESSED / IO_INTERVAL / NONE 等空闲态）→ True。getActiveMachine()
	返回 None、文本为空 / "null"（不可读）或反射失败均返回 None，由调用方退回「跑满采样窗口」。
	「机器空闲」是提前退出的必需守卫：会话刚结算但下一任务可能已派发，若屏幕仍打开时调用
	_disable_mod() 会打断在途会话，故仅当确认空闲才允许退出。
	"""
	try:
		cls_tick = _cached_class(CLS_TICK)
		inst = java_call_method(cls_tick, java_member(cls_tick, "getInstance"))
		if inst is None:
			return None
		machine = java_call_method(inst, java_member(cls_tick, "getActiveMachine"))
		if machine is None:
			return None
		cls_abstract = _cached_class(CLS_ABSTRACT)
		raw = java_to_string(java_call_method(machine, java_member(cls_abstract, "getIdleReason")))
		text = "" if raw is None else str(raw).strip()
		# java_to_string 可能对字符串值加包裹引号（先例：moving_test._hunger_stats），先剥引号再判读
		if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
			text = text[1:-1]
		if not text or text.lower() == "null":
			return None
		return text not in ("TRADING", "CONTAINER_IO", "RETURN_TRIGGER")
	except Exception:  # noqa: BLE001
		return None


def _enabled_handle():
	"""取 mod Configs.Generic.ENABLED 的 Java 对象句柄（java_access_field 供静态字段取值）。"""
	from minescript import java_access_field

	cls = java_class(CLS_GENERIC)
	return java_access_field(cls, java_member(cls, "ENABLED"))


def _enabled():
	"""读取 mod 启用状态（True/False）；反射失败返回 None。"""
	try:
		cb = java_class(CLS_CONFIG_BOOLEAN)
		value = java_call_method(_enabled_handle(), java_member(cb, "getBooleanValue"))
		text = str(java_to_string(value)).strip().lower()
		if text in ("true", "false"):
			return text == "true"
	except Exception:  # noqa: BLE001
		pass
	return None


def _reset_mod():
	"""重置 mod 机器与统计（AutoTradeClientTick.reset()）。"""
	cls_tick = java_class(CLS_TICK)
	inst = java_call_method(cls_tick, java_member(cls_tick, "getInstance"))
	java_call_method(inst, java_member(cls_tick, "reset"))


def _enable_mod():
	"""启用 mod：等价 Toggle Trading 热键（先 reset 再翻转 ENABLED）。

	顺序要求：**先 reset 再 toggle**——toggle 后机器会在下一个客户端 tick 立即派发任务，
	若 reset 落在派发之后，会把在途任务静默清空（无任何日志、机器静默空闲、无成交）；
	先 reset 时机器必然空闲，无此竞态。
	Configs.loadFromFile() 结尾会把 ENABLED 强制归为 false（防止启动即交易），故每次进图
	都需要一次显式启用；Minescript 无法触发 malilib 热键（malilib 不走原版 KeyMapping），
	因此用反射复刻热键回调的效果。
	"""
	cb = java_class(CLS_CONFIG_BOOLEAN)
	_reset_mod()
	java_call_method(_enabled_handle(), java_member(cb, "toggleBooleanValue"))


def _disable_mod():
	"""停用 mod：仅当当前处于启用态才翻转 ENABLED（避免在已停用时误开）。"""
	if _enabled() is True:
		cb = java_class(CLS_CONFIG_BOOLEAN)
		java_call_method(_enabled_handle(), java_member(cb, "toggleBooleanValue"))


def _count_items():
	"""聚合玩家背包中 emerald/paper/iron_sword 的总数量，返回 (e, p, sw)。"""
	totals = {item.split(":", 1)[-1]: 0 for item in ITEM_IDS}
	try:
		for stack in player_inventory():
			short = (stack.item or "").split(":", 1)[-1]
			if short in totals:
				totals[short] += stack.count
	except Exception:  # noqa: BLE001
		pass
	return totals["emerald"], totals["paper"], totals["iron_sword"]


def _item_entity_count():
	"""统计 16 格内掉落的 `minecraft:item` 实体数（关窗后成本余量掉落）；失败按 0 计。"""
	try:
		return sum(1 for ent in entities(max_distance=ENTITY_RANGE) if (ent.type or "") == "minecraft:item")
	except Exception:  # noqa: BLE001
		return 0


def _quit_client(noquit: bool) -> None:
	"""观测结束后自动关闭客户端（noquit=True 时跳过）；失败仅提示，绝不抛异常。"""
	if noquit:
		log("[quit] noquit specified - keep client alive")
		return
	log("[quit] auto-closing Minecraft client in 2s ...")
	try:
		time.sleep(2.0)
		cls = java_class("net.minecraft.client.MinecraftClient")
		inst = java_call_method(cls, java_member(cls, "getInstance"))
		java_call_method(inst, java_member(cls, "scheduleStop"))
		log("[quit] MinecraftClient.scheduleStop() called")
	except Exception as exc:  # noqa: BLE001
		log(f"[quit] auto-close failed: {exc} - please close the game window manually")


def _emit_end(case_id, total, last):
	"""采集终态并打印 `[cap] end` 行（前置态不符与正常路径共用）。返回 None。"""
	e, p, sw = _count_items()
	items = _item_entity_count()
	enabled = _enabled()
	log(f"[cap] end id={case_id} total={total} last={last} e={e} p={p} sw={sw} items={items} enabled={enabled}")


def _run_case(case):
	"""执行单个用例，返回 (setup_ok:int, ended:bool)。

	异常时打印 `[cap] error` 并返回 (0, False)，由调用方继续下一组；
	`ended` 仅在成功打印 `[cap] end` 行时为 True（供 verdict 计数）。
	"""
	case_id = case["id"]
	try:
		log(f"[cap] begin id={case_id}")
		# 1) 确保 mod 停用并复位机器（每组从干净状态起）
		_disable_mod()
		_reset_mod()
		# 2) 布置前置库存与村民
		execute(f"/function autotrade_test:cap_{case_id}")
		time.sleep(SETUP_WAIT)
		# 3) 前置态校验（不匹配 → ok=0，跳过启用，仍打印 end）
		e, p, sw = _count_items()
		pre = case["pre"]
		setup_ok = e == pre["emerald"] and p == pre["paper"] and sw == pre["iron_sword"]
		log(f"[cap] setup id={case_id} ok={1 if setup_ok else 0} e={e} p={p} sw={sw}")
		if not setup_ok:
			_reset_mod()
			_emit_end(case_id, 0, 0)
			return 0, True
		# 4) reset + 启用 mod（等价 Toggle Trading 热键）
		_enable_mod()
		enable_time = time.time()
		# 5) 采样窗口：内部记录 total/last/sessions/cur/screen，不打印采样行（避免污染 [cap] 解析）
		total = 0
		last = 0
		screen_seen = None
		streak = 0  # 提前退出判据「已完成≥1 会话 ∧ 机器空闲 ∧ 背包==预期终态」的连续成立次数
		is_positive = case["final"] != case["pre"]
		expected = (case["final"]["emerald"], case["final"]["paper"], case["final"]["iron_sword"])
		sample_deadline = enable_time + SAMPLE_DURATION
		while time.time() < sample_deadline:
			stats = _stats()
			if "error" not in stats:
				sampled_total = stats.get("total")
				if isinstance(sampled_total, int):
					total = sampled_total
				if isinstance(stats.get("last"), int):
					last = stats["last"]
			try:
				screen_seen = screen_name()
			except Exception:  # noqa: BLE001
				screen_seen = None
			# 会话数与机器空闲状态（任一不可读 → 判据不成立，退回跑满窗口）
			sessions = stats.get("sessions") if "error" not in stats else None
			idle = _machine_idle()
			# 采样背包与用例预期终态一致方满足判据（_count_items 失败返回 (0,0,0) 自然不命中）
			cur = _count_items()
			# 判据：已完成≥1 个会话 ∧ 机器空闲无在途任务（守卫：避免 _disable_mod 打断在途会话）
			# ∧ 背包==预期终态；负例另要求 total==0（证明确实未成交），正例以会话+终态为准
			evidence = (
				isinstance(sessions, int)
				and sessions >= 1
				and idle is True
				and cur == expected
				and (is_positive or total == 0)
			)
			if evidence:
				streak += 1
			else:
				streak = 0
			if streak >= EARLY_EXIT_STABLE:
				log(
					f"[cap] early-exit id={case_id} after {time.time() - enable_time:.1f}s "
					f"sessions={sessions} (final reached, total={total})"
				)
				break
			time.sleep(SAMPLE_INTERVAL)
		# 6) 停用 mod，等待会话收尾 / 掉落生成
		_disable_mod()
		time.sleep(POST_DISABLE_WAIT)
		# 7) 终态原始数据行
		_emit_end(case_id, total, last)
		return 1, True
	except Exception as exc:  # noqa: BLE001
		log(f"[cap] error id={case_id} err={type(exc).__name__}: {exc}")
		# 异常兜底：尽量停用 mod，避免污染下一组
		try:
			_disable_mod()
		except Exception:  # noqa: BLE001
			pass
		return 0, False


def main(seconds=DEFAULT_SECONDS, noquit=False):
	"""主流程：初始复位 → 逐用例执行（begin/setup/end）→ 汇总 verdict →（默认）自动关闭客户端。"""
	log("=== AutoTradeCapacityTest CAPACITY observation start (Minescript autorun) ===")
	echo(f"[Test] CAPACITY observation start, max {int(seconds)}s, {len(CAP_CASES)} cases; result -> logs/latest.log")

	# 起始确保 mod 停用（Configs.loadFromFile() 会把 ENABLED 强制归 false，仍显式复位一次）
	try:
		_disable_mod()
		_reset_mod()
	except Exception as exc:  # noqa: BLE001
		log(f"[cap] initial disable/reset failed: {exc}")

	deadline = time.time() + float(seconds)
	setup_ok_count = 0
	ends = 0
	for case in CAP_CASES:
		if time.time() >= deadline:
			log(f"[cap] deadline reached before id={case['id']}; remaining cases skipped")
			break
		ok, ended = _run_case(case)
		setup_ok_count += ok
		ends += 1 if ended else 0

	cases_total = len(CAP_CASES)
	verdict_ok = setup_ok_count == cases_total and ends == cases_total

	log(f"[verdict] {'PASS' if verdict_ok else 'FAIL'} | cases={cases_total} setup_ok={setup_ok_count} ends={ends}")
	log(f"[criteria] cases={cases_total} setup_ok={cases_total} ends={cases_total} (setup_ok={setup_ok_count} ends={ends})")
	try:
		screenshot("autotrade_capacity_test")
		log("[verdict] screenshot saved run/screenshots/autotrade_capacity_test.png")
	except Exception as exc:  # noqa: BLE001
		log(f"[verdict] screenshot failed: {exc}")
	echo(f"[Test] observation end: {'PASS' if verdict_ok else 'FAIL'} (see logs/latest.log)")
	log("=== AutoTradeCapacityTest CAPACITY observation end ===")

	_quit_client(noquit)


if __name__ == "__main__":
	secs = DEFAULT_SECONDS
	noquit = "noquit" in sys.argv[1:]
	if len(sys.argv) > 1:
		try:
			secs = float(sys.argv[1])
		except ValueError:
			pass
	try:
		main(secs, noquit)
	except Exception as exc:  # noqa: BLE001
		import traceback

		log(f"[fatal] script exception: {exc}")
		log(traceback.format_exc())
		echo(f"[Test] script exception, see logs/latest.log: {exc}")
