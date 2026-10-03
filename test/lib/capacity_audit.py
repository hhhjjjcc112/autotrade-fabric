r"""CAPACITY 生成物独立审计脚本（只读）。

职责：
	- 对 `setup_testworld.py --mode capacity` 生成的四类产物做只读交叉审计：
	  datapack 函数 / 用例表 JSON / mod 配置 JSON；
	- 逐条打印 `[OK]` / `[FAIL]`，全部通过输出 `[audit] ALL OK (N checks)` 并 exit 0；
	  任一失败 exit 1（FAIL 行点名违规产物与原因）；
	- 绝不修改任何产物（唯一写路径留给外部 QA 篡改/重生成，不在本脚本内）。

CLI：
	python test/lib/capacity_audit.py \
	  --world-dir run/saves/AutoTradeCapacityTest \
	  --cases .omo/evidence/capacity-detection-testworld/case-table.json

断言分组：
	1. datapack 恰有 23 个 `cap_<id>.mcfunction`，id 集合 == 用例表 id 集合；
	2. 每个函数：首个非注释行（tp 行）之后存在 `kill @e[type=minecraft:item]`；
	   另有 `kill @e[type=minecraft:villager,tag=autotrade_cap]` 与 `clear @s`；
	3. 每个函数 `item replace` 行数 == 用例表非空槽数（len(stacks) + junk_stacks）；
	4. 每个函数 sell item id + `Count:<n>b`、`maxUses:<n>` 与表一致（全 23 组，表驱动；
	   cap_a2/cap_f1/cap_g1 为显式抽查样本）；
	5. 用例表 JSON：恰 23 条、id 序列 a1..h2、`expect_session` 键集恰 5 键、
	   `expect_exec` 为列表；
	6. datapack 目录下任何文件不得残留 `{{` 占位符；
	7. 配置 `run/config/autotrade.json`：tradeMode STATIC / tradeCacheTtl 0 /
	   skipOpenTtl 0 / tradePairs 长度 2 / itemIO == []。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# 仓库根（autotrade-fabric/）：test/lib/capacity_audit.py -> parents[2]
ROOT = Path(__file__).resolve().parents[2]
RUN_DIR = ROOT / "run"

# 默认路径（显式 CLI 参数优先）
DEFAULT_WORLD_DIR = RUN_DIR / "saves" / "AutoTradeCapacityTest"
DEFAULT_CASES = (
	ROOT.parent / ".omo" / "evidence" / "capacity-detection-testworld" / "case-table.json"
)
DEFAULT_CONFIG = RUN_DIR / "config" / "autotrade.json"

# 期望 id 序列（矩阵序）
EXPECTED_IDS = [
	"a1", "a2", "a3",
	"b1", "b2", "b3", "b4",
	"c1", "c2", "c3",
	"d1", "d2",
	"e1", "e2", "e3",
	"f1", "f2", "f3",
	"g1", "g2", "g3",
	"h1", "h2",
]

# 显式抽查样本（其余 20 组同样表驱动校验）
SAMPLE_IDS = ["a2", "f1", "g1"]

SESSION_KEYS = {"trades", "capacity_skips", "blocked", "moveout_blocked", "stuck"}


class Audit:
	"""收集并打印断言结果（不修改任何外部文件）。"""

	def __init__(self) -> None:
		self.results: list[tuple[str, bool, str]] = []

	def check(self, name: str, ok: bool, detail: str = "") -> bool:
		"""记录一条断言并即时打印 OK/FAIL（FAIL 附带 detail）。"""
		ok = bool(ok)
		self.results.append((name, ok, detail))
		status = "OK" if ok else "FAIL"
		suffix = f"  [{detail}]" if (detail and not ok) else ""
		print(f"[{status}] {name}{suffix}")
		return ok

	def summary(self) -> int:
		"""打印汇总行并返回进程退出码（0 = 全过）。"""
		passed = sum(1 for _, ok, _ in self.results if ok)
		total = len(self.results)
		if passed == total:
			print(f"[audit] ALL OK ({total} checks)")
			return 0
		print(f"[audit] {total - passed} FAILED / {total} checks")
		return 1


def _significant_lines(text: str) -> list[str]:
	"""返回去掉空行与 `#` 注释后的有效行（strip 后）。"""
	lines: list[str] = []
	for raw in text.splitlines():
		stripped = raw.strip()
		if not stripped or stripped.startswith("#"):
			continue
		lines.append(stripped)
	return lines


def audit_functions(audit: Audit, func_dir: Path, cases: list[dict]) -> None:
	"""断言组 1–4：函数文件集合、行序、item replace 计数、sell/maxUses 内容。"""
	cap_files = sorted(func_dir.glob("cap_*.mcfunction"))
	file_ids = [path.stem[len("cap_"):] for path in cap_files]
	table_ids = [record["id"] for record in cases]

	audit.check(
		f"函数数量 cap_*.mcfunction == 23（实际 {len(cap_files)}）",
		len(cap_files) == 23,
		str(func_dir),
	)
	audit.check(
		"函数 id 集合 == 用例表 id 集合",
		set(file_ids) == set(table_ids),
		f"files-only={sorted(set(file_ids) - set(table_ids))} table-only={sorted(set(table_ids) - set(file_ids))}",
	)

	by_id = {record["id"]: record for record in cases}
	for case in cases:
		case_id = case["id"]
		path = func_dir / f"cap_{case_id}.mcfunction"
		if not path.is_file():
			audit.check(f"cap_{case_id}.mcfunction 存在", False, str(path))
			continue
		text = path.read_text(encoding="utf-8")
		lines = _significant_lines(text)
		label = f"cap_{case_id}.mcfunction"

		# 组 2：首个非注释行为 tp 行；其后含 kill item 行；另有 kill 旧村民与 clear @s
		first_ok = bool(lines) and lines[0].startswith("tp @s ")
		audit.check(f"{label} 首行是 tp @s 行", first_ok, lines[0] if lines else "<空文件>")
		kill_item_ok = "kill @e[type=minecraft:item]" in lines[1:]
		audit.check(f"{label} tp 行之后含 kill item 行", kill_item_ok, "未在首个非注释行之后找到")
		audit.check(
			f"{label} 含 kill 旧村民行",
			"kill @e[type=minecraft:villager,tag=autotrade_cap]" in lines,
			"缺失",
		)
		audit.check(f"{label} 含 clear @s", "clear @s" in lines, "缺失")

		# 组 3：item replace 行数 == 非空槽数
		item_replace_count = sum(1 for line in lines if line.startswith("item replace"))
		expected_slots = len(case["stacks"]) + case["junk_stacks"]
		audit.check(
			f"{label} item replace 行数 == 非空槽数（实际 {item_replace_count} / 期望 {expected_slots}）",
			item_replace_count == expected_slots,
			f"{path}",
		)

		# 组 4：sell item id + Count:<n>b 与 maxUses 与表一致（表驱动，全 23 组）
		offer = case["offer"]
		sell_ok = f'sell:{{id:"{offer["sell_item"]}",Count:{offer["sell_count"]}b}}' in text
		audit.check(
			f"{label} sell == {offer['sell_item']} Count:{offer['sell_count']}b",
			sell_ok,
			f"{path}",
		)
		max_uses_ok = f"maxUses:{offer['max_uses']}" in text
		audit.check(
			f"{label} maxUses == {offer['max_uses']}",
			max_uses_ok,
			f"{path}",
		)

	# 显式抽查样本点名打印（内容断言已在上方全量覆盖）
	for sample_id in SAMPLE_IDS:
		record = by_id.get(sample_id)
		if record is None:
			audit.check(f"抽查样本 {sample_id} 存在于用例表", False, "表缺失该 id")
			continue
		offer = record["offer"]
		print(
			f"[sample] cap_{sample_id}: sell={offer['sell_item']} Count:{offer['sell_count']}b "
			f"maxUses:{offer['max_uses']}（已表驱动校验）"
		)


def audit_cases_json(audit: Audit, cases_path: Path) -> list[dict]:
	"""断言组 5：用例表 JSON 结构（23 条 / id 序列 / 键集 / 类型）。"""
	if not cases_path.is_file():
		audit.check("用例表 JSON 存在", False, str(cases_path))
		return []
	audit.check("用例表 JSON 存在", True)
	try:
		cases = json.loads(cases_path.read_text(encoding="utf-8"))
	except Exception as exc:  # noqa: BLE001
		audit.check("用例表 JSON 可解析", False, f"{type(exc).__name__}: {exc}")
		return []
	audit.check("用例表 JSON 可解析", True)
	if not isinstance(cases, list):
		audit.check("用例表 JSON 顶层为列表", False, type(cases).__name__)
		return []
	audit.check("用例表记录数 == 23", len(cases) == 23, f"实际 {len(cases)}")
	ids = [record.get("id") if isinstance(record, dict) else None for record in cases]
	audit.check("用例表 id 序列 == a1..h2", ids == EXPECTED_IDS, str(ids))

	session_bad: list[str] = []
	exec_bad: list[str] = []
	for record in cases:
		if not isinstance(record, dict):
			continue
		case_id = record.get("id")
		session = record.get("expect_session")
		if not isinstance(session, dict) or set(session) != SESSION_KEYS:
			session_bad.append(str(case_id))
		execs = record.get("expect_exec")
		if not isinstance(execs, list):
			exec_bad.append(str(case_id))
	audit.check(
		"每条 expect_session 键集恰 5 键",
		not session_bad,
		f"违例 id={session_bad}",
	)
	audit.check(
		"每条 expect_exec 为列表",
		not exec_bad,
		f"违例 id={exec_bad}",
	)
	return cases


def audit_placeholders(audit: Audit, dp_dir: Path) -> None:
	"""断言组 6：datapack 下任何文件不得残留 `{{`。"""
	if not dp_dir.is_dir():
		audit.check("datapack 目录存在", False, str(dp_dir))
		return
	audit.check("datapack 目录存在", True)
	offenders: list[str] = []
	for path in sorted(dp_dir.rglob("*")):
		if not path.is_file():
			continue
		text = path.read_text(encoding="utf-8")
		if "{{" in text:
			offenders.append(path.relative_to(dp_dir).as_posix())
	audit.check("datapack 无 `{{` 残留", not offenders, f"违例文件={offenders}")


def audit_config(audit: Audit, config_path: Path) -> None:
	"""断言组 7：mod 配置关键字段（STATIC / ttl 0 / skip 0 / 2 交易对 / itemIO 空）。"""
	if not config_path.is_file():
		audit.check("配置文件存在", False, str(config_path))
		return
	audit.check("配置文件存在", True)
	try:
		cfg = json.loads(config_path.read_text(encoding="utf-8"))
	except Exception as exc:  # noqa: BLE001
		audit.check("配置文件可解析", False, f"{type(exc).__name__}: {exc}")
		return
	audit.check("配置文件可解析", True)
	generic = cfg.get("Generic", {}) if isinstance(cfg, dict) else {}
	audit.check(
		"配置 Generic.tradeMode == STATIC",
		generic.get("tradeMode") == "STATIC",
		f"{config_path}: tradeMode={generic.get('tradeMode')!r}",
	)
	audit.check(
		"配置 Generic.tradeCacheTtl == 0",
		generic.get("tradeCacheTtl") == 0,
		f"{config_path}: tradeCacheTtl={generic.get('tradeCacheTtl')!r}",
	)
	audit.check(
		"配置 Generic.skipOpenTtl == 0",
		generic.get("skipOpenTtl") == 0,
		f"{config_path}: skipOpenTtl={generic.get('skipOpenTtl')!r}",
	)
	pairs = generic.get("tradePairs")
	audit.check(
		"配置 Generic.tradePairs 长度 == 2",
		isinstance(pairs, list) and len(pairs) == 2,
		f"{config_path}: len={len(pairs) if isinstance(pairs, list) else type(pairs).__name__}",
	)
	audit.check(
		"配置 Generic.itemIO == []",
		generic.get("itemIO") == [],
		f"{config_path}: itemIO={generic.get('itemIO')!r}",
	)


def run_audit(world_dir: Path, cases_path: Path, config_path: Path) -> int:
	"""执行全部断言并返回退出码（0 全过 / 1 有失败）。"""
	audit = Audit()
	cases = audit_cases_json(audit, cases_path)
	func_dir = world_dir / "datapacks" / "autotrade_test" / "data" / "autotrade_test" / "functions"
	dp_dir = world_dir / "datapacks" / "autotrade_test"
	if cases:
		audit_functions(audit, func_dir, cases)
	else:
		audit.check("函数审计可执行（用例表可用）", False, str(cases_path))
	audit_placeholders(audit, dp_dir)
	audit_config(audit, config_path)
	return audit.summary()


def main(argv=None) -> int:
	"""CLI 入口；UTF-8 重配置标准流（Windows 代码页中文显示）。"""
	for stream in (sys.stdout, sys.stderr):
		reconfigure = getattr(stream, "reconfigure", None)
		if reconfigure is not None:
			try:
				reconfigure(encoding="utf-8", errors="replace")
			except Exception:  # noqa: BLE001
				pass
	parser = argparse.ArgumentParser(description="CAPACITY 生成物只读审计（datapack / 用例表 / 配置）")
	parser.add_argument(
		"--world-dir",
		default=str(DEFAULT_WORLD_DIR),
		help="测试世界目录（默认 run/saves/AutoTradeCapacityTest）",
	)
	parser.add_argument(
		"--cases",
		default=str(DEFAULT_CASES),
		help="用例表 JSON 路径（默认 .omo/evidence/capacity-detection-testworld/case-table.json）",
	)
	parser.add_argument(
		"--config",
		default=str(DEFAULT_CONFIG),
		help="mod 配置 JSON 路径（默认 run/config/autotrade.json）",
	)
	args = parser.parse_args(argv)
	return run_audit(Path(args.world_dir), Path(args.cases), Path(args.config))


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
