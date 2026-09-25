"""AutoTradeVoidTest VOID 自动观测脚本（Minescript）。

用途：进入 AutoTradeVoidTest 世界后自动执行一次「真虚空交易」观测：
  1. 等待并校验装置就绪（HOME 地面/输入输出箱 + player_position，超时 60s）；
  2. 执行一次 /function autotrade_test:setup 幂等重置（同时把玩家传送回 HOME），
     随后重新校验装置，并反射复刻 Toggle Trading 热键自动启用 mod；
  3. 监听聊天栏 [AutoTradeTest] 打印行：`[ready]`、
     `[void out=N back=M uses=U rp=P rpt=R]`（U=-1 = 村民未加载；U=0 = 健康；U>0 = 交易次数已持久化，
     说明不是真虚空交易 → 判 FAIL；rp = 回程累计计数；rpt = 中继器检测器状态：
     -2 不在岛侧 / -1 方块缺失 / 0 未通电 / 1 通电）、`[cleared=N total=M restocks=K]`（总量兜底 ≈ 成交数）；
     岛侧另有 IO 容器（输入/输出箱）：用于验证「容器 IO 优先于回程触发」（mod 日志可见 IDLE → CONTAINER_IO 先于 IDLE → RETURN_TRIGGER）；
  4. 每次迭代按 x 坐标分类玩家区域（home/island/unknown），统计并打印
     `[cycle] home->island` / `[cycle] island->home` 转换；首次进入岛时截图并校验岛上
     装置（陷阱箱/中继器，仅在玩家 x > 2500 时检查）；
  5. 每 5s 记录状态行（位置/界面/背包/mod 统计/聊天计数）；
  6. 结束时输出 PASS/FAIL 汇总（回程周期数、成交数、uses 健康三项全部通过才 PASS）。

输出：echo（本地聊天，可见）+ log（logs/latest.log，供 agent/用户事后查看）。

用法：
  - 自动：run/minescript/config.txt 中 `autorun[AutoTradeVoidTest]=void_test 240`
  - 手动：游戏内聊天框输入 `\\void_test 240`（时长秒数，可省略，默认 240）

注意：
  - minescript 库由 Minescript mod 运行时注入 sys.path；IDE 静态检查报未知导入属预期现象。
  - java_* 反射的调用约定：方法要先经 `java_member(cls, "name")` 取句柄，再 `java_call_method(target, handle)`。
  - VOID 模式下村民会因区块卸载而从客户端消失属预期行为，不计入判定。
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

# 装置固定坐标（与 tools/testworld 的 VOID 测试世界生成脚本保持一致）
HOME_POS = (2000.5, -60.0, 0.5)
ISLAND_POS = (3000.5, -60.0, 0.5)
HOME_RIG_CHECKS = [
	("home ground(2000,-61,0)", (2000, -61, 0), "minecraft:grass_block"),
]
ISLAND_RIG_CHECKS = [
	("island ret-chest(3002,-60,0)", (3002, -60, 0), "minecraft:trapped_chest"),
	("island repeater(3003,-60,0)", (3003, -60, 0), "minecraft:repeater"),
	("island in-chest(3000,-60,2)", (3000, -60, 2), "minecraft:chest"),
	("island out-chest(2998,-60,0)", (2998, -60, 0), "minecraft:chest"),
]
REGION_RADIUS = 250.0  # home/island 区域判定半径（按 x 坐标）

# 装置打印行 ASCII 标记（中文部分可能经桥乱码，只用 ASCII 数值字段解析）
READY_TAG = "[ready]"
VOID_RE = re.compile(r"\[void out=(\d+) back=(\d+) uses=(-?\d+) rp=(-?\d+) rpt=(-?\d+)\]")
CLEAR_RE = re.compile(r"\[cleared=(\d+) total=(\d+) restocks=(\d+)\]")

DEFAULT_SECONDS = 240.0  # 默认观测时长（秒）
WAIT_RIG_TIMEOUT = 60.0  # 等待装置就绪上限（秒）
STATUS_INTERVAL = 5.0  # 状态行间隔（秒）
PASS_MIN_TRADES = 128  # 判定 PASS 所需最少成交数（真虚空应远超补货周期满量）
PASS_MIN_CYCLES = 3  # 判定 PASS 所需最少回程周期数
PASS_MIN_USES_SAMPLES = 3  # 判定 PASS 所需最少有效 uses 样本数


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


def _classify_region(pos):
	"""按 x 坐标分类玩家所在区域：home / island / unknown。"""
	if abs(pos[0] - HOME_POS[0]) < REGION_RADIUS:
		return "home"
	if abs(pos[0] - ISLAND_POS[0]) < REGION_RADIUS:
		return "island"
	return "unknown"


def _wait_for_rig():
	"""等待装置就绪（HOME 地面方块出现且 player_position 可读）；超时返回 False。"""
	deadline = time.time() + WAIT_RIG_TIMEOUT
	while time.time() < deadline:
		if _block_id(getblock(*HOME_RIG_CHECKS[0][1])) != HOME_RIG_CHECKS[0][2]:
			time.sleep(2.0)
			continue
		try:
			player_position()
			return True
		except Exception:  # noqa: BLE001
			time.sleep(2.0)
	return False


def _check_rig():
	"""校验装置（地面/箱子/站位），返回 (方块是否全部就位?, 摘要列表)。

	岛上装置仅在玩家 x > 2500（已在岛屿）时检查，避免远端区块未加载导致误报。
	"""
	lines = []
	blocks_ok = True
	checks = list(HOME_RIG_CHECKS)
	pos = None
	try:
		pos = player_position()
	except Exception as exc:  # noqa: BLE001
		lines.append(f"[rig] player_position() 失败: {exc}")
	if pos is not None and pos[0] > 2500:
		checks += ISLAND_RIG_CHECKS
	for name, block_pos, expected in checks:
		actual = getblock(*block_pos)
		ok = _block_id(actual) == expected
		blocks_ok = blocks_ok and ok
		lines.append(f"[rig] {name}: {actual} {'OK' if ok else 'FAIL(expect ' + expected + ')'}")
	if pos is not None:
		dist_home = max(abs(pos[0] - HOME_POS[0]), abs(pos[2] - HOME_POS[2]))
		dist_island = max(abs(pos[0] - ISLAND_POS[0]), abs(pos[2] - ISLAND_POS[2]))
		lines.append(
			f"[rig] player pos={[round(v, 2) for v in pos]} dist(home={round(dist_home, 2)}, island={round(dist_island, 2)})"
		)
	try:
		# 村民实体仅作记录（VOID 模式中会随时序消失/重现），不计入判定
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
	若 reset 落在派发之后，会把在途任务静默清空（无任何日志、机器静默空闲、无成交）；
	先 reset 时机器必然空闲，无此竞态。
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
	"""主流程：就绪检查 → setup 重置 → 自动启用 mod → 事件/轮询观测 → 汇总判定。"""
	log("=== AutoTradeVoidTest VOID 观测开始（Minescript autorun）===")
	echo(f"[Test] VOID 观测开始，最长 {int(seconds)}s；结果写入 logs/latest.log")

	if not _wait_for_rig():
		log("[rig] 等待装置就绪超时（HOME 地面方块未出现或 player_position 不可用）——继续尝试观测")
	rig_ok, rig_lines = _check_rig()
	for line in rig_lines:
		log(line)
	log(f"[rig] 装置检查（初始）: {'OK' if rig_ok else 'FAIL'}")

	# 幂等重置测试装置（同时把玩家传送回 HOME）；只执行一次，避免反复清零计数
	log("[rig] 执行 /function autotrade_test:setup（幂等重置 + 传送回 HOME）")
	try:
		execute("/function autotrade_test:setup")
	except Exception as exc:  # noqa: BLE001
		log(f"[rig] setup 执行失败: {exc}")
	time.sleep(2.0)
	rig_ok, rig_lines = _check_rig()
	for line in rig_lines:
		log(line)
	log(f"[rig] 装置检查（setup 后）: {'OK' if rig_ok else 'FAIL'}")

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

	stats = {
		"out": 0,
		"back": 0,
		"uses": -1,
		"rp": 0,
		"rpt": -2,
		"cleared": 0,
		"total": 0,
		"restocks": 0,
		"lines": 0,
		"ready_seen": False,
		"void_seen": False,
	}
	uses_samples = []  # 有效 uses 样本（>= 0；-1 = 村民未加载，不入样本）
	transitions = {"home->island": 0, "island->home": 0}
	shots = {"gui": False, "island": False}
	island_rig_ok = None  # 未到达岛屿则保持 None
	prev_region = None
	try:
		prev_region = _classify_region(player_position())
		if prev_region == "unknown":
			prev_region = None
		log(f"[cycle] 初始区域: {prev_region or 'unknown'}")
	except Exception as exc:  # noqa: BLE001
		log(f"[cycle] 初始区域读取失败: {exc}")
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
					match = VOID_RE.search(message)
					if match:
						stats["void_seen"] = True
						stats["out"] = int(match.group(1))
						stats["back"] = int(match.group(2))
						stats["uses"] = int(match.group(3))
						stats["rp"] = int(match.group(4))
						stats["rpt"] = int(match.group(5))
						if stats["uses"] >= 0:
							uses_samples.append(stats["uses"])
					match = CLEAR_RE.search(message)
					if match:
						stats["lines"] += 1
						stats["cleared"] = int(match.group(1))
						stats["total"] = int(match.group(2))
						stats["restocks"] = int(match.group(3))

			# 每次迭代分类玩家区域并统计 home/island 转换（本地回程计数是聊天兜底信号）
			pos = None
			try:
				pos = player_position()
			except Exception:  # noqa: BLE001
				pass
			region = _classify_region(pos) if pos is not None else "unknown"
			if region != "unknown":
				if prev_region is not None and region != prev_region:
					key = f"{prev_region}->{region}"
					if key in transitions:
						transitions[key] += 1
						log(
							f"[cycle] {key}（累计 home->island={transitions['home->island']} "
							f"island->home={transitions['island->home']}）"
						)
				prev_region = region

			# 截图：首次出现容器界面（商人界面标题为村民自定义名如 "AT-TestVillager"，不以类名出现 → 以「有界面打开」为条件）
			screen = ""
			try:
				screen = str(screen_name() or "")
			except Exception:  # noqa: BLE001
				pass
			if not shots["gui"] and screen:
				shots["gui"] = True
				try:
					screenshot("autotrade_void_gui")
					log("[shot] 已保存 run/screenshots/autotrade_void_gui.png")
				except Exception as exc:  # noqa: BLE001
					log(f"[shot] 交易界面截图失败: {exc}")

			# 首次到达岛屿：截图 + 校验岛上装置（陷阱箱/中继器）
			if region == "island" and not shots["island"]:
				shots["island"] = True
				try:
					screenshot("autotrade_void_island")
					log("[shot] 已保存 run/screenshots/autotrade_void_island.png")
				except Exception as exc:  # noqa: BLE001
					log(f"[shot] 岛上截图失败: {exc}")
				island_rig_ok, island_lines = _check_rig()
				for line in island_lines:
					log(line)
				log(f"[rig] 岛上装置检查: {'OK' if island_rig_ok else 'FAIL'}")

			if time.time() >= next_status:
				next_status = time.time() + STATUS_INTERVAL
				mod = _trade_stats()
				emerald = _count_item("minecraft:emerald")
				paper = _count_item("minecraft:paper")
				x_text = round(pos[0], 2) if pos is not None else "?"
				z_text = round(pos[2], 2) if pos is not None else "?"
				log(
					f"[status] t={int(time.time() - start)}s x={x_text} z={z_text} screen={screen!r} "
					f"inv(emerald={emerald}, paper={paper}) chat(out={stats['out']}, back={stats['back']}, "
					f"uses={stats['uses']}, cleared={stats['cleared']}, total={stats['total']}) "
					f"diag(rp={stats['rp']}, rpt={stats['rpt']}) mod={mod}"
				)

	# 汇总与判定（mod 反射为主信号；聊天计数离线可用，本地转换计数兜底）
	mod = _trade_stats()
	total = mod.get("total")
	io_in = mod.get("io_in")
	io_out = mod.get("io_out")
	reasons = []
	if "error" in mod or not (isinstance(total, int) and isinstance(io_in, int) and isinstance(io_out, int)):
		trades_ok = stats["total"] >= PASS_MIN_TRADES
		reasons.append(
			f"mod 反射不可用/返回空（{mod.get('error', 'value=None')}）→ 以聊天累计 total 为准: "
			f"{stats['total']}/{PASS_MIN_TRADES}"
		)
	else:
		trades_ok = total >= PASS_MIN_TRADES
		reasons.append(f"mod: totalTrades={total} lastSession={mod.get('last')} ioIn={io_in} ioOut={io_out}")
	if stats["void_seen"]:
		cycles_ok = stats["back"] >= PASS_MIN_CYCLES
	else:
		cycles_ok = transitions["island->home"] >= PASS_MIN_CYCLES
	reasons.append(
		f"chat: out={stats['out']} back={stats['back']} lastUses={stats['uses']} cleared={stats['cleared']} "
		f"total={stats['total']} restocks={stats['restocks']} lines={stats['lines']} ready={stats['ready_seen']} "
		f"diag(rp={stats['rp']}, rpt={stats['rpt']})"
		+ ("" if stats["void_seen"] else "（未解析到 [void ..] 行 → 回程以本地转换计数兜底）")
	)
	reasons.append(f"local: home->island={transitions['home->island']} island->home={transitions['island->home']}")
	if uses_samples:
		uses_min = min(uses_samples)
		uses_max = max(uses_samples)
		uses_ok = len(uses_samples) >= PASS_MIN_USES_SAMPLES and uses_max <= 0
		reasons.append(
			f"uses: min={uses_min} max={uses_max} samples={len(uses_samples)}"
			+ ("；观察到 uses>0 → 交易次数已持久化，非真虚空交易" if uses_max > 0 else "")
		)
	else:
		uses_ok = False
		reasons.append("uses: min=? max=? samples=0（无有效样本）")
	island_state = "n/a(未到达岛屿)" if island_rig_ok is None else ("OK" if island_rig_ok else "FAIL")
	reasons.append(f"rig: home={'OK' if rig_ok else 'FAIL'} island={island_state}")
	verdict_ok = cycles_ok and trades_ok and uses_ok
	reasons.append(
		f"criteria: cycles={'OK' if cycles_ok else 'FAIL'}(回程>={PASS_MIN_CYCLES}) "
		f"trades={'OK' if trades_ok else 'FAIL'}(成交>={PASS_MIN_TRADES}) "
		f"uses={'OK' if uses_ok else 'FAIL'}(0 值样本>={PASS_MIN_USES_SAMPLES})"
	)

	log(f"[verdict] {'PASS' if verdict_ok else 'FAIL'} | " + " | ".join(reasons))
	try:
		screenshot("autotrade_void_end")
		log("[verdict] 截图已保存 run/screenshots/autotrade_void_end.png")
	except Exception as exc:  # noqa: BLE001
		log(f"[verdict] 截图失败: {exc}")
	echo(f"[Test] VOID 观测结束：{'PASS' if verdict_ok else 'FAIL'}（详见 logs/latest.log）")
	log("=== AutoTradeVoidTest VOID 观测结束 ===")


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
