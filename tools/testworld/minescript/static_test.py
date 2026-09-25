"""AutoTradeTest STATIC 自动观测脚本（Minescript）。

用途：进入 AutoTradeTest 世界后自动执行一次「静止交易」观测：
  1. 等待并校验装置就绪（超平坦地面 / 两个箱子方块 / 玩家站位；村民实体仅记录不判定），
     地面/箱子缺失时自动执行 /function autotrade_test:setup 重建；
  2. 监听聊天栏中测试装置的打印行（用 ASCII 标记解析：`[cleared=N total=M restocks=K]`；
     中文部分经 Minescript 桥可能乱码，故只用 ASCII 字段做数值解析）；
  3. 周期记录玩家背包中的绿宝石/纸数量与当前界面，并反射读取 mod TradeStats（累计成交/IO 次数）；
  4. 结束时输出 PASS/FAIL 汇总。

输出：echo（本地聊天，可见）+ log（logs/latest.log，供 agent/用户事后查看）。

用法：
  - 自动：run/minescript/config.txt 中 `autorun[AutoTradeTest]=static_test`
  - 手动：游戏内聊天框输入 `\\static_test 180`（时长秒数，可省略）

注意：
  - minescript 库由 Minescript mod 运行时注入 sys.path；IDE 静态检查报未知导入属预期现象。
  - java_* 反射的调用约定：方法要先经 `java_member(cls, "name")` 取句柄，再 `java_call_method(target, handle)`。
"""

import re
import sys
import time
from queue import Empty

from minescript import (
	EventQueue,
	EventType,
	echo,
	entities,
	execute,
	getblock,
	java_call_method,
	java_class,
	java_member,
	java_to_string,
	log,
	player_inventory,
	player_position,
	screen_name,
	screenshot,
)

# 装置固定坐标（与 tools/testworld/setup_testworld.py 保持一致）
RIG_CHECKS = [
	("ground(0,-61,0)", (0, -61, 0), "minecraft:grass_block"),
	("in-chest(-2,-60,0)", (-2, -60, 0), "minecraft:chest"),
	("out-chest(0,-60,2)", (0, -60, 2), "minecraft:chest"),
]
PLAYER_POS = (0.5, -60.0, 0.5)

# 装置打印行 ASCII 标记（中文部分可能经桥乱码，只用 ASCII 数值字段解析）
READY_TAG = "[ready]"
CLEAR_RE = re.compile(r"\[cleared=(\d+) total=(\d+) restocks=(\d+)\]")

DEFAULT_SECONDS = 180.0  # 默认观测时长（秒）
WAIT_RIG_TIMEOUT = 60.0  # 等待装置就绪上限（秒）
STATUS_INTERVAL = 10.0  # 状态行间隔（秒）
PASS_MIN_TRADES = 64  # 判定 PASS 所需最少成交数（一个补货周期满量）


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


def _trade_stats():
	"""反射读取 mod TradeStats 单例；失败返回 {'error': ...}（脚本继续走聊天/背包信号）。"""
	try:
		cls = java_class("com.github.sebseb7.autotrade.trade.stats.TradeStats")
		inst = java_call_method(cls, java_member(cls, "getInstance"))
		if inst is None:
			return {"error": "TradeStats.getInstance() -> None"}
		return {
			"total": _as_int(java_call_method(inst, java_member(cls, "getTotalTrades"))),
			"last": _as_int(java_call_method(inst, java_member(cls, "getLastSessionTrades"))),
			"io_in": _as_int(java_call_method(inst, java_member(cls, "getIoInputOps"))),
			"io_out": _as_int(java_call_method(inst, java_member(cls, "getIoOutputOps"))),
		}
	except Exception as exc:  # noqa: BLE001
		return {"error": f"{type(exc).__name__}: {exc}"}


def _count_item(item_id):
	"""统计玩家背包中某物品的总数量（item_id 可带或不带 minecraft: 前缀）。"""
	short = item_id.split(":", 1)[-1]
	total = 0
	for stack in player_inventory():
		if (stack.item or "").split(":", 1)[-1] == short:
			total += stack.count
	return total


def _block_id(block_state):
	"""取方块 id（去掉状态属性后缀，如 minecraft:grass_block[snowy=false] -> minecraft:grass_block）。"""
	return (block_state or "").split("[", 1)[0]


def _wait_for_rig():
	"""等待装置就绪（地面方块出现）；超时返回 False。"""
	deadline = time.time() + WAIT_RIG_TIMEOUT
	while time.time() < deadline:
		if _block_id(getblock(*RIG_CHECKS[0][1])) == RIG_CHECKS[0][2]:
			return True
		time.sleep(2.0)
	return False


def _check_rig():
	"""校验装置（地面/箱子/站位），返回 (地面与箱子是否全部就位?, 摘要列表)。"""
	lines = []
	blocks_ok = True
	for name, pos, expected in RIG_CHECKS:
		actual = getblock(*pos)
		ok = _block_id(actual) == expected
		blocks_ok = blocks_ok and ok
		lines.append(f"[rig] {name}: {actual} {'OK' if ok else 'FAIL(expect ' + expected + ')'}")
	try:
		pos = player_position()
		dist = max(abs(pos[0] - PLAYER_POS[0]), abs(pos[2] - PLAYER_POS[2]))
		lines.append(f"[rig] player pos={[round(v, 2) for v in pos]} dist_xz={round(dist, 2)}")
	except Exception as exc:  # noqa: BLE001
		lines.append(f"[rig] player_position() 失败: {exc}")
	try:
		# 村民实体仅作记录（type 过滤行为在 4.0-beta2 上不稳定），不计入判定
		nearby = entities(max_distance=16)
		types = {}
		for ent in nearby:
			types[ent.type] = types.get(ent.type, 0) + 1
		lines.append(f"[rig] 附近实体: {types}")
	except Exception as exc:  # noqa: BLE001
		lines.append(f"[rig] entities() 失败: {exc}")
	return blocks_ok, lines


# mod 反射目标（自动启用用；见 _enable_mod）
CLS_GENERIC = "com.github.sebseb7.autotrade.config.Configs$Generic"
CLS_CONFIG_BOOLEAN = "fi.dy.masa.malilib.config.options.ConfigBoolean"
CLS_TICK = "com.github.sebseb7.autotrade.runtime.AutoTradeClientTick"


def _enabled_handle():
	"""取 mod Configs.Generic.ENABLED 的 Java 对象句柄（java_access_field 供静态字段取值）。"""
	from minescript import java_access_field

	cls = java_class(CLS_GENERIC)
	return java_access_field(cls, java_member(cls, "ENABLED"))


def _mod_enabled():
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


def _enable_mod():
	"""启用 mod：等价 Toggle Trading 热键（重置机器 + 翻转 ENABLED）。

	顺序要求：**先 reset 再 toggle**——toggle 后机器会在下一个客户端 tick 立即派发任务，
	若 reset 落在派发之后，会把在途任务静默清空（无任何日志、机器静默空闲、无成交）。
	Configs.loadFromFile() 结尾会把 ENABLED 强制归为 false（防止启动即交易），故每次进图
	都需要一次显式启用；Minescript 无法触发 malilib 热键（malilib 不走原版 KeyMapping），
	因此用反射复刻热键回调的效果。
	"""
	cb = java_class(CLS_CONFIG_BOOLEAN)
	cls_tick = java_class(CLS_TICK)
	inst = java_call_method(cls_tick, java_member(cls_tick, "getInstance"))
	java_call_method(inst, java_member(cls_tick, "reset"))
	java_call_method(_enabled_handle(), java_member(cb, "toggleBooleanValue"))


def main(seconds=DEFAULT_SECONDS):
	"""主流程：就绪检查 → 自动启用 mod → 事件/轮询观测 → 汇总判定。"""
	log("=== AutoTradeTest STATIC 观测开始（Minescript autorun）===")
	echo(f"[Test] STATIC 观测开始，最长 {int(seconds)}s；结果写入 logs/latest.log")

	if not _wait_for_rig():
		log("[rig] 等待装置就绪超时（地面方块未出现）——继续尝试观测")
	rig_ok, rig_lines = _check_rig()
	for line in rig_lines:
		log(line)
	if not rig_ok:
		# 地面/箱子缺失时尝试重建（只做一次，避免反复清计数）
		log("[rig] 装置不完整 → 执行 /function autotrade_test:setup 重建")
		try:
			execute("/function autotrade_test:setup")
		except Exception as exc:  # noqa: BLE001
			log(f"[rig] 重建失败: {exc}")
		time.sleep(3.0)
		rig_ok, rig_lines = _check_rig()
		for line in rig_lines:
			log(line)
	log(f"[rig] 装置检查: {'OK' if rig_ok else 'FAIL'}")

	# 自动启用 mod（等价 Toggle Trading 热键；Configs.loadFromFile() 总会把 ENABLED 归为 false）
	enabled = _mod_enabled()
	if enabled is None:
		log("[mod] 无法读取 ENABLED（反射不可用）→ 请手动按 Toggle Trading 热键")
	elif enabled:
		log("[mod] 已处于启用状态（无需热键）")
	else:
		try:
			_enable_mod()
			time.sleep(1.0)
			log(f"[mod] 已自动启用（等价 Toggle Trading 热键）；enabled={_mod_enabled()}")
		except Exception as exc:  # noqa: BLE001
			log(f"[mod] 自动启用失败: {exc} → 请手动按 Toggle Trading 热键")

	stats = {"cleared": 0, "total": 0, "restocks": 0, "lines": 0, "ready_seen": False}
	deadline = time.time() + float(seconds)
	next_status = time.time() + STATUS_INTERVAL
	start = time.time()

	with EventQueue() as event_queue:
		event_queue.register_chat_listener()
		while time.time() < deadline:
			try:
				event = event_queue.get(block=True, timeout=1.0)
			except Empty:
				event = None
			if event is not None and getattr(event, "type", None) == EventType.CHAT:
				message = getattr(event, "message", "") or ""
				if "[AutoTradeTest]" in message:
					log(f"[chat] {message}")
					if READY_TAG in message:
						stats["ready_seen"] = True
					match = CLEAR_RE.search(message)
					if match:
						stats["lines"] += 1
						stats["cleared"] = int(match.group(1))
						stats["total"] = int(match.group(2))
						stats["restocks"] = int(match.group(3))
			if time.time() >= next_status:
				next_status = time.time() + STATUS_INTERVAL
				mod = _trade_stats()
				emerald = _count_item("minecraft:emerald")
				paper = _count_item("minecraft:paper")
				log(
					f"[status] t={int(time.time() - start)}s screen={screen_name()!r} "
					f"inv(emerald={emerald}, paper={paper}) chat(cleared={stats['cleared']}, "
					f"total={stats['total']}, restocks={stats['restocks']}) mod={mod}"
				)

	# 汇总与判定（mod 反射为主信号；反射不可用/取值为空时回退聊天累计）
	mod = _trade_stats()
	reasons = []
	total = mod.get("total")
	io_in = mod.get("io_in")
	io_out = mod.get("io_out")
	if "error" in mod or not (isinstance(total, int) and isinstance(io_in, int) and isinstance(io_out, int)):
		verdict_ok = stats["total"] >= PASS_MIN_TRADES
		reasons.append(f"mod 反射不可用/返回空（{mod.get('error', 'value=None')}）→ 以聊天累计为准")
	else:
		verdict_ok = total >= PASS_MIN_TRADES and io_in >= 1 and io_out >= 1
		reasons.append(f"mod: totalTrades={total} lastSession={mod.get('last')} ioIn={io_in} ioOut={io_out}")
	reasons.append(
		f"chat: 打印行={stats['lines']} 累计清空={stats['total']} 补货={stats['restocks']} 就绪提示={stats['ready_seen']}"
	)
	reasons.append(f"rig={'OK' if rig_ok else 'FAIL'}")

	log(f"[verdict] {'PASS' if verdict_ok else 'FAIL'} | " + " | ".join(reasons))
	try:
		screenshot("autotrade_static_test")
		log("[verdict] 截图已保存 run/screenshots/autotrade_static_test.png")
	except Exception as exc:  # noqa: BLE001
		log(f"[verdict] 截图失败: {exc}")
	echo(f"[Test] 观测结束：{'PASS' if verdict_ok else 'FAIL'}（详见 logs/latest.log）")
	log("=== AutoTradeTest STATIC 观测结束 ===")


if __name__ == "__main__":
	secs = DEFAULT_SECONDS
	if len(sys.argv) > 1:
		try:
			secs = float(sys.argv[1])
		except ValueError:
			pass
	try:
		main(secs)
	except Exception as exc:  # noqa: BLE001
		import traceback

		log(f"[fatal] 脚本异常: {exc}")
		log(traceback.format_exc())
		echo(f"[Test] 脚本异常，见 logs/latest.log：{exc}")
