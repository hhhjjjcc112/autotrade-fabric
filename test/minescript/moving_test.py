"""AutoTradeMovingTest MOVING 自动观测脚本（Minescript）。

用途：进入 AutoTradeMovingTest 世界后自动执行一次「移动交易」观测（玩家由矿车环形轨道带动）：
  1. 等待并校验装置就绪（v2：5 箱 = chest〔emerald/wheat 输入 + paper/book/glass 输出〕、
     轨道 = rail 族方块；超时 60s），装置不完整时自动执行一次 /function autotrade_test:setup 重建后复检；
  2. 反射复刻 Toggle Trading 热键自动启用 mod（**先 AutoTradeClientTick.reset() 再翻转
     Configs$Generic.ENABLED**，顺序不可颠倒，理由见 _enable_mod 注释）；
  3. 采样循环（0.5s/次，矿车自动移动，脚本不模拟移动键、不干预视角）：
     - 记录 player_position()，累计水平路径长度 dist；
     - 圈数 laps：玩家回到起点 (2.5,-60,0.5) 附近（水平 ≤4 格）且自上次计圈以来 dist 增量
       ≥120 → laps += 1；该圈段内 trades 也增长时 growth += 1；
     - 反射读取 TradeStats（累计成交 / IO 输入输出次数）与饥饿计数：
       AutoTradeClientTick.getActiveMachine() → MovingTradeMachine.getStarvationCount()，
       每采样一次（0.5s）取最大值；反射失败静默（最终按 0 计 → FAIL）；
     - 每 5s 打印一行 [status]（pos/trades/ioIn/ioOut/screen/laps/starve）；
     - 卡死看守：连续 4s 位置变化 <1.5 格且 screen_name() 为 None → stuck += 1 并重挂矿车；
  4. 结束时输出 §1 冻结格式的单行 [verdict]（v2，含 starve），截图并（默认）自动关闭客户端。

输出：echo（本地聊天，可见）+ log（logs/latest.log，供 agent/用户事后查看）。
verdict 单行格式（v2，勿改）：
  [verdict] PASS|FAIL | trades=<int> dist=<int> laps=<int> growth=<int> ioIn=<int> ioOut=<int> starve=<int> rig=<0|1> enabled=<0|1> stuck=<int>

用法：
  - 自动：run/minescript/config.txt 中 `autorun[AutoTradeMovingTest]=moving_test 180`
  - 手动：游戏内聊天框输入 `\\moving_test 180`（时长秒数，可省略，默认 180）
  - 保留客户端：`\\moving_test 180 noquit`（默认观测结束后自动关闭客户端）

注意：
  - 观测结束后默认自动调用 MinecraftClient.scheduleStop() 关闭客户端；传 noquit 可保留。
  - minescript 库由 Minescript mod 运行时注入 sys.path；IDE 静态检查报未知导入属预期现象。
  - java_* 反射的调用约定：方法要先经 java_member(cls, "name") 取句柄，再 java_call_method(target, handle)。
  - 矿车自动移动：脚本不模拟移动键，也不与 mod 的视角旋转对抗。
"""

import math
import sys
import time

from minescript import (
	echo,
	execute,
	getblock,
	java_call_method,
	java_class,
	java_member,
	java_to_string,
	log,
	player_position,
	screen_name,
	screenshot,
)

# 装置固定坐标（与 test/lib/setup_testworld.py 的 MOVING v3 布局 / 冻结规格 §2 一致）
RIG_CHECKS = [
	("in-emerald(2,-60,-2)", (2, -60, -2), "minecraft:chest"),
	("in-wheat(24,-60,-2)", (24, -60, -2), "minecraft:chest"),
	("out-paper(22,-60,34)", (22, -60, 34), "minecraft:chest"),
	("out-book(66,-60,16)", (66, -60, 16), "minecraft:chest"),
	("out-glass(48,-60,34)", (48, -60, 34), "minecraft:chest"),
]
TRACK_POS = (30, -60, 0)  # 轨道（北/南直道）抽检坐标
RAIL_IDS = {
	"minecraft:rail",
	"minecraft:powered_rail",
	"minecraft:detector_rail",
	"minecraft:activator_rail",
}
START_POS = (2.5, -60.0, 0.5)  # 计圈锚点（轨道北直道西端）
LAP_RADIUS = 4.0  # 回到起点附近的判定半径（水平，格）
LAP_MIN_DIST = 120.0  # 自上次计圈以来最小路径增量（环周长 ≈192）
STALL_WINDOW = 4.0  # 卡死看守窗口（秒）
STALL_MIN_MOVE = 1.5  # 窗口内位置变化阈值（格）
CART_SELECTOR = "@e[type=minecraft:minecart,tag=att_cart,limit=1]"  # 装置矿车选择器

DEFAULT_SECONDS = 180.0  # 默认观测时长（秒）
WAIT_RIG_TIMEOUT = 60.0  # 等待装置就绪上限（秒）
STATUS_INTERVAL = 5.0  # 状态行间隔（秒）
SAMPLE_INTERVAL = 0.5  # 采样间隔（秒）
PASS_MIN_DIST = 200  # 判定 PASS 所需最少路径长度（格）
PASS_MIN_LAPS = 1  # 判定 PASS 所需最少圈数
PASS_MIN_TRADES = 640  # 判定 PASS 所需最少成交数（v3：密集簇下会话数减少，阈值下调）
PASS_MIN_GROWTH = 2  # 判定 PASS 所需最少「成交增长的圈数」（证明跨圈可再次交易）
PASS_MIN_IO_IN = 2  # 判定 PASS 所需最少输入容器 IO 次数（emerald / wheat 各 ≥1）
PASS_MIN_IO_OUT = 3  # 判定 PASS 所需最少输出容器 IO 次数（paper / book / glass 各 ≥1）
PASS_MIN_STARVE = 1  # 判定 PASS 所需最少饥饿记账条目数（证明饥饿记账生效；饥饿**阈值 ≥4** 由入口后置检查日志「长期未被服务」提示断言）


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
	"""反射读取 mod TradeStats 单例；失败返回 {'error': ...}（脚本继续走位置/方块信号）。"""
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


def _stat_int(stats, key):
	"""从 _trade_stats() 结果取 int（缺失/反射失败返回 None）。"""
	value = stats.get(key)
	return value if isinstance(value, int) else None


def _ori0(value):
	"""把可能为 None 的统计值归一为 int，便于展示与比较（None -> 0）。"""
	return value if isinstance(value, int) else 0


def _block_id(block_state):
	"""取方块 id（去掉状态属性后缀，如 minecraft:grass_block[snowy=false] -> minecraft:grass_block）。"""
	return (block_state or "").split("[", 1)[0]


def _is_rail(block_id):
	"""判断方块是否为轨道族（rail / powered_rail / detector_rail / activator_rail）。"""
	return block_id in RAIL_IDS


def _horizontal_distance(a, b):
	"""两个位置在水平面（xz）上的欧氏距离。"""
	if a is None or b is None:
		return 0.0
	dx = float(a[0]) - float(b[0])
	dz = float(a[2]) - float(b[2])
	return math.hypot(dx, dz)


def _wait_for_rig():
	"""等待装置就绪（输入箱方块出现）；超时返回 False。"""
	deadline = time.time() + WAIT_RIG_TIMEOUT
	while time.time() < deadline:
		if _block_id(getblock(*RIG_CHECKS[0][1])) == RIG_CHECKS[0][2]:
			return True
		time.sleep(2.0)
	return False


def _check_rig():
	"""校验装置（v2：5 箱〔emerald/wheat 输入 + paper/book/glass 输出〕= chest、轨道 = rail 族），返回 (是否全部就位?, 摘要列表)。"""
	lines = []
	blocks_ok = True
	for name, pos, expected in RIG_CHECKS:
		actual = getblock(*pos)
		ok = _block_id(actual) == expected
		blocks_ok = blocks_ok and ok
		lines.append(f"[rig] {name}: {actual} {'OK' if ok else 'FAIL(expect ' + expected + ')'}")
	actual_track = getblock(*TRACK_POS)
	track_ok = _is_rail(_block_id(actual_track))
	blocks_ok = blocks_ok and track_ok
	lines.append(f"[rig] track{TRACK_POS}: {actual_track} {'OK' if track_ok else 'FAIL(expect rail)'}")
	try:
		pos = player_position()
		lines.append(
			f"[rig] player pos={[round(v, 2) for v in pos]} "
			f"dist_start={round(_horizontal_distance(pos, START_POS), 2)}"
		)
	except Exception as exc:  # noqa: BLE001
		lines.append(f"[rig] player_position() failed: {exc}")
	return blocks_ok, lines


# mod 反射目标（自动启用 / 饥饿采样用；见 _enable_mod / _starvation_count）
CLS_GENERIC = "com.github.sebseb7.autotrade.config.Configs$Generic"
CLS_CONFIG_BOOLEAN = "fi.dy.masa.malilib.config.options.ConfigBoolean"
CLS_TICK = "com.github.sebseb7.autotrade.runtime.AutoTradeClientTick"
CLS_MOVING_MACHINE = "com.github.sebseb7.autotrade.trade.mode.MovingTradeMachine"


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


def _starvation_count():
	"""反射读取当前 MOVING 机器的饥饿计数（getStarvationCount）；失败静默返回 None。

	路径：AutoTradeClientTick.getInstance() -> getActiveMachine() 取机器句柄；机器声明类型为
	TradingMachine 基类，getStarvationCount 是 MovingTradeMachine 上的方法，故方法句柄须从
	MovingTradeMachine 类获取。任何一步失败（未启用 / 非 MOVING / 反射不可用）都返回 None，
	由调用方按「静默失败」处理（最终按 0 计 → FAIL），不抛异常、不打断观测。
	"""
	try:
		cls_tick = java_class(CLS_TICK)
		inst = java_call_method(cls_tick, java_member(cls_tick, "getInstance"))
		if inst is None:
			return None
		machine = java_call_method(inst, java_member(cls_tick, "getActiveMachine"))
		if machine is None:
			return None
		cls_mv = java_class(CLS_MOVING_MACHINE)
		return _as_int(java_call_method(machine, java_member(cls_mv, "getStarvationCount")))
	except Exception:  # noqa: BLE001
		return None


def _remount_cart():
	"""把玩家重新挂到装置矿车上（卡死看守的自救动作）。"""
	execute(f"/ride @s mount {CART_SELECTOR}")


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


def main(seconds=DEFAULT_SECONDS, noquit=False):
	"""主流程：就绪检查 → 自动启用 mod → 采样观测 → 汇总判定 →（默认）自动关闭客户端。"""
	log("=== AutoTradeMovingTest MOVING observation start (Minescript autorun) ===")
	echo(f"[Test] MOVING observation start, max {int(seconds)}s; result -> logs/latest.log")

	if not _wait_for_rig():
		log("[rig] wait for rig timeout (input chest not found) - keep observing")
	rig_ok, rig_lines = _check_rig()
	for line in rig_lines:
		log(line)
	if not rig_ok:
		# 装置不完整时尝试重建（只做一次，避免反复清计数 / 重置矿车）
		log("[rig] rig incomplete -> run /function autotrade_test:setup once")
		try:
			execute("/function autotrade_test:setup")
		except Exception as exc:  # noqa: BLE001
			log(f"[rig] setup failed: {exc}")
		time.sleep(3.0)
		rig_ok, rig_lines = _check_rig()
		for line in rig_lines:
			log(line)
	log(f"[rig] rig check: {'OK' if rig_ok else 'FAIL'}")

	# 自动启用 mod（等价 Toggle Trading 热键；Configs.loadFromFile() 总会把 ENABLED 归为 false）
	enabled = _mod_enabled()
	if enabled is None:
		log("[mod] cannot read ENABLED (reflection unavailable) - press Toggle Trading hotkey manually")
	elif enabled:
		log("[mod] already enabled (no hotkey needed)")
	else:
		try:
			_enable_mod()
			time.sleep(1.0)
			log(f"[mod] auto-enabled (equiv Toggle Trading hotkey); enabled={_mod_enabled()}")
		except Exception as exc:  # noqa: BLE001
			log(f"[mod] auto-enable failed: {exc} - press Toggle Trading hotkey manually")

	dist = 0.0  # 累计水平路径长度（格）
	laps = 0  # 完成圈数
	growth = 0  # 成交发生增长的圈数
	stuck = 0  # 卡死看守触发次数
	prev_pos = None  # 上一采样点（用于累计路径长度）
	lap_marker_dist = 0.0  # 上次计圈时的 dist
	lap_marker_trades = None  # 上次计圈时的成交数（首采样后初始化）
	starve_max = None  # 饥饿计数最大值（每 0.5s 采样；反射失败保持 None → 最终按 0 处理）

	watch_pos = None  # 卡死看守窗口起点
	watch_time = time.time()

	deadline = time.time() + float(seconds)
	next_status = time.time() + STATUS_INTERVAL
	start = time.time()

	while time.time() < deadline:
		now = time.time()

		# 采样玩家位置并累计水平路径长度
		pos = None
		try:
			pos = player_position()
		except Exception:  # noqa: BLE001
			pass
		if pos is not None:
			if prev_pos is not None:
				dist += _horizontal_distance(pos, prev_pos)
			prev_pos = pos

		# 读取界面名（None = 无界面）
		screen = None
		try:
			screen = screen_name()
		except Exception:  # noqa: BLE001
			screen = None

		# 反射读取 mod 统计（每采样一次；laps/growth 需要圈段成交增量）
		stats = _trade_stats()
		trades = _stat_int(stats, "total")
		io_in = _stat_int(stats, "io_in")
		io_out = _stat_int(stats, "io_out")
		if lap_marker_trades is None and trades is not None:
			lap_marker_trades = trades

		# 饥饿采样（每 0.5s）：成功时更新最大值；反射失败静默（None，最终按 0 处理 → FAIL）
		starve_now = _starvation_count()
		if starve_now is not None and (starve_max is None or starve_now > starve_max):
			starve_max = starve_now

		# 卡死看守：4s 窗口内位置变化 <1.5 格且无界面 → 计数并重挂矿车
		if now - watch_time >= STALL_WINDOW:
			moved = _horizontal_distance(pos, watch_pos) if pos is not None else 0.0
			if pos is not None and moved < STALL_MIN_MOVE and screen is None:
				stuck += 1
				log(f"[stall] no movement ({round(moved, 2)} blocks in {int(STALL_WINDOW)}s) - remount cart #{stuck}")
				try:
					_remount_cart()
				except Exception as exc:  # noqa: BLE001
					log(f"[stall] remount failed: {exc}")
			watch_pos = pos
			watch_time = now

		# 圈检测：回到起点附近且路径增量足够 → 计圈；该圈段成交增长则计入 growth
		if pos is not None and _horizontal_distance(pos, START_POS) <= LAP_RADIUS:
			if dist - lap_marker_dist >= LAP_MIN_DIST:
				laps += 1
				if trades is not None and lap_marker_trades is not None and trades > lap_marker_trades:
					growth += 1
				lap_marker_dist = dist
				lap_marker_trades = trades if trades is not None else lap_marker_trades
				log(f"[lap] laps={laps} growth={growth} dist={int(dist)} trades={_ori0(trades)}")

		# 每 STATUS_INTERVAL 秒打印状态行
		if now >= next_status:
			next_status = now + STATUS_INTERVAL
			pos_text = [round(v, 2) for v in pos] if pos is not None else None
			log(
				f"[status] t={int(now - start)}s pos={pos_text} trades={_ori0(trades)} "
				f"ioIn={_ori0(io_in)} ioOut={_ori0(io_out)} screen={screen!r} laps={laps} "
				f"dist={int(dist)} growth={growth} starve={_ori0(starve_max)} stuck={stuck}"
			)

		time.sleep(SAMPLE_INTERVAL)

	# 汇总与判定（最终值以最后一帧反射结果与累计量为准）
	final = _trade_stats()
	trades_v = _stat_int(final, "total")
	io_in_v = _stat_int(final, "io_in")
	io_out_v = _stat_int(final, "io_out")
	enabled_now = _mod_enabled()

	rig_flag = 1 if rig_ok else 0
	enabled_flag = 1 if enabled_now is True else 0
	trades_out = _ori0(trades_v)
	io_in_out = _ori0(io_in_v)
	io_out_out = _ori0(io_out_v)
	starve_out = _ori0(starve_max)
	dist_out = int(dist)

	verdict_ok = (
		rig_flag == 1
		and enabled_flag == 1
		and dist_out >= PASS_MIN_DIST
		and laps >= PASS_MIN_LAPS
		and trades_out >= PASS_MIN_TRADES
		and io_in_out >= PASS_MIN_IO_IN
		and io_out_out >= PASS_MIN_IO_OUT
		and growth >= PASS_MIN_GROWTH
		and starve_out >= PASS_MIN_STARVE
	)

	log(
		f"[verdict] {'PASS' if verdict_ok else 'FAIL'} | trades={trades_out} dist={dist_out} "
		f"laps={laps} growth={growth} ioIn={io_in_out} ioOut={io_out_out} starve={starve_out} "
		f"rig={rig_flag} enabled={enabled_flag} stuck={stuck}"
	)
	log(
		f"[criteria] dist>={PASS_MIN_DIST} laps>={PASS_MIN_LAPS} trades>={PASS_MIN_TRADES} "
		f"growth>={PASS_MIN_GROWTH} ioIn>={PASS_MIN_IO_IN} ioOut>={PASS_MIN_IO_OUT} "
		f"starve>={PASS_MIN_STARVE} rig=1 enabled=1 "
		f"(mod={final.get('error', 'ok')})"
	)
	try:
		screenshot("autotrade_moving_test")
		log("[verdict] screenshot saved run/screenshots/autotrade_moving_test.png")
	except Exception as exc:  # noqa: BLE001
		log(f"[verdict] screenshot failed: {exc}")
	echo(f"[Test] observation end: {'PASS' if verdict_ok else 'FAIL'} (see logs/latest.log)")
	log("=== AutoTradeMovingTest MOVING observation end ===")

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
