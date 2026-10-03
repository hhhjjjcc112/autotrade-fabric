r"""CAPACITY 判定库：解析 `[cap]` 用例块 + mod 日志锚点，逐组对照钉扎，合成全符合 golden 日志。

职责：
	- `parse_case_blocks(text)`：按 `[cap] begin id=` … `[cap] end id=` 切块（块外内容忽略），
	  收集 begin id / setup 字段 / end 字段 / 有序 EXECUTING 列表 / 首条 STOP / 首条会话收尾 /
	  moveout / stuck / error 标记；
	- `compare(blocks, cases)`：8 类断言（缺块或 error、setup.ok、execs 前 N 条 7 键、STOP 存在性与
	  四值、会话三元组、moveout/stuck 存在性、end e/p/sw 与掉落实体）；缺失一律 FAIL，绝不推断补齐；
	- `render_table(results)`：23 行逐组表 + `[overall] N/23 PASS` 汇总；
	- `make_golden(cases)`：由 `capacity_scenarios` 合成"全符合"日志（供离线校准判定机器）；
	- CLI：`--log <path> [--json <out>]`（退出码 0 当且仅当 23/23 PASS）与 `--make-golden <out>`。

双模导入：`from lib import capacity_analysis` / `import lib.capacity_analysis` /
直跑 `python test/lib/capacity_analysis.py` 均可用（下方 sys.path shim 照 `test/static.py` 范式）。

日志锚点（`AbstractTradeStrategy.java`，行号见计划）：
	- EXECUTING（:524-530）`[AutoTrade] EXECUTING trade offer {i} result={item} inputBatch=… need=…
	  capacity=… reservation=… candidate=… effectiveBatch=… remaining=…`
	- STOP（:517-519，全角括号）`[AutoTrade] STOP offer {i}: 空间满，结束会话待容器 IO（inputBatch=…
	  need=… capacity=… reservation=…）`
	- 会话收尾（:181-182）`[AutoTrade] 会话收尾: tradesTotal=… capacitySkips=… blocked=…`
	- moveOut 失败（:302）`[AutoTrade] 成本物品无法移回背包，标记背包阻塞`
	- STUCK（:459/494/502）`[AutoTrade] STUCK offer …`（仅判存在）
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# 双模导入 shim：包内相对导入优先；直跑时把本文件目录加入 sys.path 后按顶层模块导入。
try:  # 包导入（lib.capacity_analysis / from lib import capacity_analysis）
	from . import capacity_scenarios
except ImportError:  # 直跑（python test/lib/capacity_analysis.py）
	_HERE = Path(__file__).resolve().parent
	if str(_HERE) not in sys.path:
		sys.path.insert(0, str(_HERE))
	import capacity_scenarios  # type: ignore[no-redef]

# 期望钉扎单一来源（零复制）：直接 re-export capacity_scenarios.CASES。
CASES = capacity_scenarios.CASES
EXEC_KEYS = capacity_scenarios.EXEC_KEYS  # 7 键（inputBatch/need/capacity/reservation/candidate/effectiveBatch/remaining）

# ---------------------------------------------------------------------------
# 日志解析正则（匹配子串而非行首：真实 MC 日志带 [HH:MM:SS] [thread/INFO]: 前缀）
# ---------------------------------------------------------------------------
# Minescript debug 回显：`debug_output=true` 时脚本 `log()` 的消息会被原样镜像到日志，例如真实行
#   `[12:19:45] [Render thread/INFO] (Minescript) [cap] begin id=a1`
# 紧跟一条回显行
#   `[12:19:45] [Render thread/INFO] (Minescript) (debug) Script function 0 \`log\`: [[cap] begin id=a1]  ->  <no response>`
# 回显行同样含 `[cap] begin id=a1`，若被 `parse_case_blocks` 当作脚本标记会导致 `current_id` 被覆盖成
# `a1]`、块永不闭合（Run-1 实测 `0/23 PASS (block missing)`）。两层防护：
#   1) 脚本自打印标记（begin/end/setup/error）统一加 `(?<!\[)` 负向后顾（排除回显的 `[[cap] …`，
#      与 `test/lib/verdict.py:21` 的 `[[verdict]` 约定同源）；id 收紧为 `([A-Za-z0-9_]+)`，即便后顾被绕过，
#      尾随 `]` 也不会被吞；
#   2) 循环内 `DEBUG_MARKER in line → continue` 跳过一切回显行（防御纵深）。
# mod 侧标记（EXECUTING/STOP/会话收尾/moveout/STUCK）由 Java logger 直接写出、不经 script `log()`，无回显风险，保持不变。
DEBUG_MARKER = "(debug)"
RE_BEGIN = re.compile(r"(?<!\[)\[cap\] begin id=([A-Za-z0-9_]+)")
RE_END = re.compile(
	r"(?<!\[)\[cap\] end id=([A-Za-z0-9_]+)\s+total=(\d+)\s+last=(\d+)\s+e=(\d+)\s+p=(\d+)"
	r"\s+sw=(\d+)\s+items=(\d+)\s+enabled=(\S+)"
)
RE_SETUP = re.compile(r"(?<!\[)\[cap\] setup id=([A-Za-z0-9_]+)\s+ok=(\d+)\s+e=(\d+)\s+p=(\d+)\s+sw=(\d+)")
RE_ERROR = re.compile(r"(?<!\[)\[cap\] error")
RE_EXEC = re.compile(
	r"\[AutoTrade\] EXECUTING trade offer (\d+) result=(\S+) inputBatch=(\d+) need=(\d+)"
	r" capacity=(-?\d+) reservation=(-?\d+) candidate=(\w+) effectiveBatch=(\d+) remaining=(\d+)"
)
RE_STOP = re.compile(
	r"\[AutoTrade\] STOP offer (\d+).*?inputBatch=(\d+)\s+need=(\d+)\s+capacity=(-?\d+)\s+reservation=(-?\d+)"
)
RE_SESSION = re.compile(r"\[AutoTrade\] 会话收尾:\s*tradesTotal=(\d+)\s+capacitySkips=(\d+)\s+blocked=(\w+)")
MOVE_BLOCKED_MARKER = "成本物品无法移回背包"
STUCK_MARKER = "STUCK offer"


def _to_bool(text) -> bool:
	"""把日志布尔字面量（true/false/True/False/1/0）归一为 Python bool（大小写不敏感）。"""
	return str(text).strip().lower() in ("true", "1")


def parse_case_blocks(text: str) -> dict[str, dict]:
	"""按 `[cap] begin id=` … `[cap] end id=` 切块解析；返回 {id: block}（块外内容忽略）。

	block 字段：
		id/ setup {ok,e,p,sw}|None/ end {total,last,e,p,sw,items,enabled}|None/
		execs [{7 键}...]（有序）/ stop {inputBatch,need,capacity,reservation}|None/
		session {tradesTotal,capacitySkips,blocked}|None/ moveout bool/ stuck bool/ error bool。
	仅收集每块内首个 setup、首个 STOP、首个会话收尾；EXECUTING 全部按出现序收集。
	"""
	blocks: dict[str, dict] = {}
	current: dict | None = None
	current_id: str | None = None
	for line in text.splitlines():
		if DEBUG_MARKER in line:
			# Minescript debug 回显行（`(debug) Script function …`，镜像 script log() 消息）：整体跳过。
			continue
		begin = RE_BEGIN.search(line)
		if begin:
			current_id = begin.group(1)
			current = {
				"id": current_id,
				"setup": None,
				"end": None,
				"execs": [],
				"stop": None,
				"session": None,
				"moveout": False,
				"stuck": False,
				"error": False,
			}
			continue
		if current is None:
			# 块外内容一律忽略（预热/历史行）
			continue
		assert current_id is not None  # current 非 None 时 current_id 必已赋值（类型收窄）
		end = RE_END.search(line)
		if end and end.group(1) == current_id:
			current["end"] = {
				"total": int(end.group(2)),
				"last": int(end.group(3)),
				"e": int(end.group(4)),
				"p": int(end.group(5)),
				"sw": int(end.group(6)),
				"items": int(end.group(7)),
				"enabled": _to_bool(end.group(8)),
			}
			blocks[current_id] = current
			current = None
			current_id = None
			continue
		setup = RE_SETUP.search(line)
		if setup and setup.group(1) == current_id and current["setup"] is None:
			current["setup"] = {
				"ok": int(setup.group(2)),
				"e": int(setup.group(3)),
				"p": int(setup.group(4)),
				"sw": int(setup.group(5)),
			}
			continue
		if RE_ERROR.search(line):
			current["error"] = True
			continue
		exec_match = RE_EXEC.search(line)
		if exec_match:
			current["execs"].append(
				{
					"inputBatch": int(exec_match.group(3)),
					"need": int(exec_match.group(4)),
					"capacity": int(exec_match.group(5)),
					"reservation": int(exec_match.group(6)),
					"candidate": _to_bool(exec_match.group(7)),
					"effectiveBatch": int(exec_match.group(8)),
					"remaining": int(exec_match.group(9)),
				}
			)
			continue
		stop = RE_STOP.search(line)
		if stop and current["stop"] is None:
			current["stop"] = {
				"inputBatch": int(stop.group(2)),
				"need": int(stop.group(3)),
				"capacity": int(stop.group(4)),
				"reservation": int(stop.group(5)),
			}
			continue
		session = RE_SESSION.search(line)
		if session and current["session"] is None:
			current["session"] = {
				"tradesTotal": int(session.group(1)),
				"capacitySkips": int(session.group(2)),
				"blocked": _to_bool(session.group(3)),
			}
			continue
		if MOVE_BLOCKED_MARKER in line:
			current["moveout"] = True
			continue
		if STUCK_MARKER in line:
			current["stuck"] = True
			continue
	return blocks


def compare(blocks: dict[str, dict], cases=None) -> list[dict]:
	"""逐组对照钉扎；返回 [{id, ok, reasons}]（reasons 为空即 PASS）。缺失一律 FAIL，不补齐。"""
	if cases is None:
		cases = CASES
	results: list[dict] = []
	for case in cases:
		reasons: list[str] = []
		block = blocks.get(case.id)
		if block is None:
			results.append({"id": case.id, "ok": False, "reasons": ["block missing"]})
			continue
		# 1) error 行（存在即 FAIL，但仍继续收集其余差异以便复核）
		if block["error"]:
			reasons.append("error line present")
		# 2) setup.ok == 1
		setup = block["setup"]
		if setup is None:
			reasons.append("setup line missing")
		elif setup["ok"] != 1:
			reasons.append(f"setup.ok={setup['ok']} expected=1")
		# 3) execs 数量 ≥ N 且前 N 条 7 键逐一相等
		expected_execs = case.expect_exec
		actual_execs = block["execs"]
		if len(actual_execs) < len(expected_execs):
			reasons.append(f"execs count={len(actual_execs)} expected>={len(expected_execs)}")
		for index, expected in enumerate(expected_execs):
			if index >= len(actual_execs):
				break
			actual = actual_execs[index]
			for key in EXEC_KEYS:
				if actual.get(key) != expected.get(key):
					reasons.append(f"exec[{index}].{key}={actual.get(key)} expected={expected.get(key)}")
		# 4) STOP：expect_stop → 存在且四值 == expect_exec[0] 对应值；否则必须不存在
		if case.expect_stop:
			stop = block["stop"]
			if stop is None:
				reasons.append("STOP line missing (expected)")
			else:
				first = expected_execs[0]
				for key in ("inputBatch", "need", "capacity", "reservation"):
					if stop.get(key) != first.get(key):
						reasons.append(f"stop.{key}={stop.get(key)} expected={first.get(key)}")
		elif block["stop"] is not None:
			reasons.append("STOP line present (unexpected)")
		# 5) 会话收尾三元组
		session = block["session"]
		expected_session = case.expect_session
		if session is None:
			reasons.append("session line missing")
		else:
			if session["tradesTotal"] != expected_session["trades"]:
				reasons.append(f"session.tradesTotal={session['tradesTotal']} expected={expected_session['trades']}")
			if session["capacitySkips"] != expected_session["capacity_skips"]:
				reasons.append(f"session.capacitySkips={session['capacitySkips']} expected={expected_session['capacity_skips']}")
			if session["blocked"] != bool(expected_session["blocked"]):
				reasons.append(f"session.blocked={session['blocked']} expected={bool(expected_session['blocked'])}")
		# 6) moveout 出现性 == moveout_blocked
		expected_moveout = bool(expected_session["moveout_blocked"])
		if block["moveout"] != expected_moveout:
			reasons.append(f"moveout={block['moveout']} expected={expected_moveout}")
		# 7) stuck 出现性 == stuck
		expected_stuck = bool(expected_session["stuck"])
		if block["stuck"] != expected_stuck:
			reasons.append(f"stuck={block['stuck']} expected={expected_stuck}")
		# 8) end e/p/sw 与掉落实体（total/last 仅解析，不断言）
		end = block["end"]
		if end is None:
			reasons.append("end line missing")
		else:
			expected_final = case.expect_final
			for field, key in (("e", "emerald"), ("p", "paper"), ("sw", "iron_sword")):
				if end[field] != expected_final[key]:
					reasons.append(f"end.{field}={end[field]} expected={expected_final[key]}")
			if end["items"] != case.expect_entities:
				reasons.append(f"end.items={end['items']} expected={case.expect_entities}")
		results.append({"id": case.id, "ok": not reasons, "reasons": reasons})
	return results


def render_table(results: list[dict]) -> str:
	"""渲染逐组表：每行 `<id> PASS` / `<id> FAIL (reason; …)`，末尾 `[overall] N/23 PASS`。"""
	lines: list[str] = []
	passed = 0
	for result in results:
		if result["ok"]:
			passed += 1
			lines.append(f"{result['id']} PASS")
		else:
			lines.append(f"{result['id']} FAIL ({'; '.join(result['reasons'])})")
	lines.append(f"[overall] {passed}/{len(results)} PASS")
	return "\n".join(lines)


def analyze_text(text: str) -> list[dict]:
	"""解析文本 → 对照 CASES → 返回逐组结果（供入口复用，不重复实现解析逻辑）。"""
	return compare(parse_case_blocks(text), CASES)


def analyze_log(log_path) -> list[dict]:
	"""读取日志文件（UTF-8，容错替换）→ `analyze_text`。"""
	text = Path(log_path).read_text(encoding="utf-8", errors="replace")
	return analyze_text(text)


def _bool_text(value) -> str:
	"""布尔字面量文本（Python str：True/False；`_to_bool` 对大小写不敏感，真实日志的 true/false 亦可解析）。"""
	return str(bool(value))


def _exec_line(sell_item: str, entry: dict, offer_index: int = 0) -> str:
	"""按 mod 日志格式合成一条 EXECUTING 行。"""
	return (
		f"[AutoTrade] EXECUTING trade offer {offer_index} result={sell_item}"
		f" inputBatch={entry['inputBatch']} need={entry['need']} capacity={entry['capacity']}"
		f" reservation={entry['reservation']} candidate={_bool_text(entry['candidate'])}"
		f" effectiveBatch={entry['effectiveBatch']} remaining={entry['remaining']}"
	)


def make_golden(cases=None) -> str:
	"""由 `capacity_scenarios` 合成全符合 golden 日志文本（供离线校准判定机器）。

	每用例行序（契约固定）：begin → setup（ok=1 + 前置计数）→ EXECUTING（expect_exec 每项）→
	[STOP]（expect_stop 时，取 expect_exec[0] 四值）→ 会话收尾 → [moveout] → [STUCK] →
	end（total/last=会话 trades；e/p/sw=expect_final；items=expect_entities；enabled=False）。
	"""
	if cases is None:
		cases = CASES
	lines: list[str] = []
	for case in cases:
		pre = case.pre_counts
		lines.append(f"[cap] begin id={case.id}")
		lines.append(f"[cap] setup id={case.id} ok=1 e={pre['emerald']} p={pre['paper']} sw={pre['iron_sword']}")
		for entry in case.expect_exec:
			lines.append(_exec_line(case.offer.sell_item, entry))
		if case.expect_stop:
			first = case.expect_exec[0]
			lines.append(
				"[AutoTrade] STOP offer 0: 空间满，结束会话待容器 IO"
				f"（inputBatch={first['inputBatch']} need={first['need']}"
				f" capacity={first['capacity']} reservation={first['reservation']}）"
			)
		session = case.expect_session
		lines.append(
			f"[AutoTrade] 会话收尾: tradesTotal={session['trades']}"
			f" capacitySkips={session['capacity_skips']} blocked={_bool_text(session['blocked'])}"
		)
		if session["moveout_blocked"]:
			lines.append("[AutoTrade] 成本物品无法移回背包，标记背包阻塞")
		if session["stuck"]:
			lines.append(
				f"[AutoTrade] STUCK offer 0: exact-N/回退后结果滞留槽 2 1x{case.offer.sell_item}，结束会话"
			)
		final = case.expect_final
		lines.append(
			f"[cap] end id={case.id} total={session['trades']} last={session['trades']}"
			f" e={final['emerald']} p={final['paper']} sw={final['iron_sword']}"
			f" items={case.expect_entities} enabled=False"
		)
	return "\n".join(lines) + "\n"


def build_parser() -> argparse.ArgumentParser:
	"""构造 CLI 解析器。"""
	parser = argparse.ArgumentParser(description="CAPACITY 判定库：解析 [cap] 日志对照 23 组钉扎 / 合成 golden")
	parser.add_argument("--log", metavar="PATH", default=None, help="待判定日志路径（打印逐组表；exit 0 当且仅当 23/23 PASS）")
	parser.add_argument("--json", metavar="PATH", default=None, help="把逐组结果 JSON 写入该路径（需配合 --log）")
	parser.add_argument("--make-golden", metavar="PATH", default=None, help="由 capacity_scenarios 合成全符合 golden 日志")
	return parser


def main(argv=None) -> int:
	"""CLI：`--make-golden <out>` 与 `--log <path> [--json <out>]`；无参数打印帮助并返回 2。"""
	for stream in (sys.stdout, sys.stderr):
		reconfigure = getattr(stream, "reconfigure", None)
		if reconfigure is not None:
			try:
				reconfigure(encoding="utf-8", errors="replace")
			except Exception:  # noqa: BLE001
				pass
	args = build_parser().parse_args(argv)
	if not args.log and not args.make_golden:
		build_parser().print_help()
		return 2

	result = 0
	if args.make_golden:
		path = Path(args.make_golden)
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(make_golden(), encoding="utf-8", newline="\n")
		print(f"[golden] -> {args.make_golden}")

	if args.log:
		results = analyze_log(args.log)
		print(render_table(results))
		if args.json:
			path = Path(args.json)
			path.parent.mkdir(parents=True, exist_ok=True)
			path.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
			print(f"[json] -> {args.json}")
		all_pass = len(results) == len(CASES) and all(item["ok"] for item in results)
		result = 0 if all_pass else 1
	return result


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
