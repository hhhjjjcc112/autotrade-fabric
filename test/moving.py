#!/usr/bin/env python3
"""AutoTrade MOVING 交易测试入口（跨 OS）。

流程：
	1) 部署 Minescript 脚本（test/minescript/*.py → run/minescript/，并按需追加 autorun 规则）；
	2) 重建 AutoTradeMovingTest 世界与 MOVING v2 配置（setup_testworld.py --mode moving --fresh）；
	3) 启动客户端（进图后 Minescript autorun 运行 moving_test 观测；观测结束后默认自动关游戏）；
	   默认 gradlew runClient，--headless 时改经本地 HeadlessMC 无头启动；
	4) 等待游戏退出后，解析本轮日志的 [verdict]（dev：run/logs/latest.log；--headless：HeadlessMC
	   游戏日志），并对同一日志做 v2 场景分析（§7：会话公平性 / 让位事件 / 多物品清空累计）；
	5) 退出码 = 游戏内 verdict PASS **且** 场景校验通过才为 0；任一不满足为 1（打印原因）；
	   未见本轮 verdict 仍为 2。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python test/moving.py                 # 完整：部署 + 重建 + 启动 + 判定
	python test/moving.py --no-rebuild    # 跳过世界/配置重建（沿用现有世界/配置）
	python test/moving.py --no-deploy     # 跳过 Minescript 脚本部署
	python test/moving.py --world NAME    # 覆盖世界名（默认 AutoTradeMovingTest）
	python test/moving.py --headless      # 经本地 HeadlessMC 无头启动（替代 gradlew runClient），默认超时 480s
	python test/moving.py --headless --headless-timeout 600
	python test/moving.py --dry-run       # 只打印将执行的命令，无任何副作用

退出码：0 = PASS（游戏内 verdict PASS 且场景校验通过）/ 1 = FAIL（含场景校验未过，打印原因）/
	2 = 未见本轮 verdict（或启动前准备失败）。

注意：本脚本会启动 Minecraft 客户端（dev 或 headless）；按项目规则（AGENTS.md 规则 7），启动测试须先获用户批准。
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from collections import Counter
from pathlib import Path

# 使 `python test/moving.py` 能 import lib.*（脚本目录 test/ 即包父目录）
sys.path.insert(0, str(Path(__file__).resolve().parent))

import lib.headless as headless  # noqa: E402
import lib.launch_testclient as launcher  # noqa: E402
from lib.verdict import parse_verdict  # noqa: E402

# 仓库根目录（autotrade-fabric/）：<repo>/test/moving.py -> parents[1]
REPO_ROOT = Path(__file__).resolve().parents[1]
LOG_PATH = REPO_ROOT / "run" / "logs" / "latest.log"
DEFAULT_WORLD = "AutoTradeMovingTest"
DEFAULT_HEADLESS_TIMEOUT = 480

# 本轮日志分析（冻结规格 §7；日志行由 MovingTradeMachine 与测试数据包写出）
RE_SESSION = re.compile(r"TRADE_SESSION \(villager uuid=([0-9a-f-]+)\)")  # 会话派发行
YIELD_MARKER = "让位抢占"  # 让位抢占事件标记（TradeTask 村民 / ContainerIOTask 容器）
HINT_MARKER = "长期未被服务"  # 饥饿提示（hunger≥4 一次性提示；证明饥饿阈值达成）
RE_PAPER_CLEARED = re.compile(r"\[cleared=(\d+) total=(\d+)")  # paper 清空行：单轮量 + 累计值
RE_BOOK_CLEARED = re.compile(r"\[clr:book=(\d+)")  # book 每轮清空量
RE_GLASS_CLEARED = re.compile(r"\[clr:glass=(\d+)")  # glass 每轮清空量
SCENARIO_MIN_DISTINCT = 14  # 场景校验：被服务村民 distinct 下限（v3 共 20 村民；留出饥饿未被服务者）
# 注：饥饿**阈值达成**（hunger≥4）以**让位抢占事件**为硬门控判据——让位检查器要求竞争者 hunger ≥ 阈值(4)
# 且 > 当前任务饥饿，故出现让位即证明 hunger≥4（v3 密集簇下实测 7 次）。饥饿提示文本是否落 latest.log
# 不稳定（malilib 提示路径），仅作信息项。


def analyze_log(log_path: Path) -> dict:
	"""读取本轮日志做 MOVING v2 场景统计（冻结规格 §7）。

	统计口径：
	- 会话：`TRADE_SESSION (villager uuid=...)` → 总数 / distinct / 每 UUID 次数（降序）；
	- 让位：含「让位抢占」标记的行计数；
	- paper 清空累计：`[cleared=N total=M ...]` 取 **max(total)**——数据包输出的是累计值，
	  取最大值即最终累计（逐行求和会把累计值重复累加）；
	- book / glass 清空累计：`[clr:book=N ...]` / `[clr:glass=N ...]` 对单轮清空量 N **求和**
	  （数据包每轮输出的是本轮清空量，累加即总清空量）。
	"""
	text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""
	uuid_counts = Counter(RE_SESSION.findall(text))
	# 次数降序；同次数按 UUID 排序保证输出稳定
	per_uuid = sorted(uuid_counts.items(), key=lambda kv: (-kv[1], kv[0]))
	paper_total = max((int(total) for _, total in RE_PAPER_CLEARED.findall(text)), default=0)
	book_total = sum(int(n) for n in RE_BOOK_CLEARED.findall(text))
	glass_total = sum(int(n) for n in RE_GLASS_CLEARED.findall(text))
	yields = text.count(YIELD_MARKER)
	hints = text.count(HINT_MARKER)
	distinct = len(uuid_counts)
	checks = [
		(f"distinct>={SCENARIO_MIN_DISTINCT}", distinct >= SCENARIO_MIN_DISTINCT, str(distinct)),
		("yields>=1 (hunger>=4)", yields >= 1, str(yields)),
		("paper>0", paper_total > 0, str(paper_total)),
		("book>0", book_total > 0, str(book_total)),
		("glass>0", glass_total > 0, str(glass_total)),
	]
	return {
		"log_path": log_path,
		"sessions_total": sum(uuid_counts.values()),
		"distinct": distinct,
		"per_uuid": per_uuid,
		"yields": yields,
		"hints": hints,
		"paper": paper_total,
		"book": book_total,
		"glass": glass_total,
		"checks": checks,
		"scenario_ok": all(ok for _, ok, _ in checks),
	}


def print_analysis(stats: dict) -> None:
	"""打印本轮日志分析摘要（会话公平性 / 让位 / 清空累计 + 场景校验逐项）。"""
	print(f"[analysis] 本轮日志：{stats['log_path']}")
	print(f"[analysis] 会话：total={stats['sessions_total']} distinct={stats['distinct']}")
	for uuid, count in stats["per_uuid"]:
		print(f"[analysis]   villager {uuid}: {count}")
	print(f"[analysis] 让位抢占（饥饿阈值 hunger≥4 的达成证据）：{stats['yields']}")
	print(f"[analysis] 饥饿提示条数：{stats['hints']}（信息项：malilib 提示是否落日志不稳定，不作为判据）")
	print(f"[analysis] 清空累计：paper={stats['paper']} book={stats['book']} glass={stats['glass']}")
	for name, ok, _ in stats["checks"]:
		print(f"[analysis] 场景校验 {name}: {'PASS' if ok else 'FAIL'}")
	print(f"[analysis] 场景校验合计：{'PASS' if stats['scenario_ok'] else 'FAIL'}")


def build_parser() -> argparse.ArgumentParser:
	"""构造命令行解析器。"""
	parser = argparse.ArgumentParser(description="AutoTrade MOVING 测试入口（部署 → 重建 → 启动 → 判定）")
	parser.add_argument("--world", default=DEFAULT_WORLD, help=f"世界名（默认 {DEFAULT_WORLD}）")
	parser.add_argument("--no-rebuild", action="store_true", help="跳过世界/配置重建")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--headless", action="store_true", help="经本地 HeadlessMC（.tools/headlessmc）无头启动并解析其日志")
	parser.add_argument("--headless-timeout", type=int, default=DEFAULT_HEADLESS_TIMEOUT, help=f"无头启动看门狗超时秒数（默认 {DEFAULT_HEADLESS_TIMEOUT}）")
	parser.add_argument("--stage-only", action="store_true", help="仅 --headless：完成暂存后退出 0（不启动游戏）")
	parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，无任何副作用")
	return parser


def main(argv: list[str]) -> int:
	"""部署 → 启动 → 解析本轮 verdict，返回进程退出码。"""
	args = build_parser().parse_args(argv[1:])
	launch_argv = ["launch_testclient.py", "--mode", "moving", "--world", args.world]
	if args.no_rebuild:
		launch_argv.append("--no-rebuild")
	if args.no_deploy:
		launch_argv.append("--no-deploy")
	if args.headless:
		launch_argv += ["--headless", "--headless-timeout", str(args.headless_timeout)]
	if args.stage_only:
		launch_argv.append("--stage-only")
	if args.dry_run:
		launch_argv.append("--dry-run")

	if args.dry_run:
		print(f"[dry-run] moving world={args.world} headless={args.headless}")

	# 起始时间戳须在启动之前记录：用于 stale-run 保护（只认本轮写出的 verdict）
	start = time.time()
	code = launcher.main(launch_argv)
	if args.dry_run:
		return code
	if code != 0:
		print(f"[warn] 启动器返回非零退出码 {code}（可能为重建/启动失败）")
	if args.stage_only:
		return code

	# 无头模式解析 HeadlessMC 游戏日志，dev 模式解析 run/logs/latest.log
	verdict_log = headless.log_path() if args.headless else LOG_PATH
	verdict = parse_verdict(verdict_log, since=start)
	if verdict not in ("PASS", "FAIL"):
		print(f"[result] 未见本轮 [verdict]（日志：{verdict_log}）")
		print("提示：确认客户端已进入世界且 Minescript 已运行 moving_test（见 run/minescript/config.txt 的 autorun 规则）")
		return 2

	# 后置分析：对与 verdict 同源的日志统计会话公平性 / 让位 / 多物品清空，并做 v2 场景校验
	#（dev 模式该日志即 run/logs/latest.log；--headless 为 HeadlessMC 游戏日志）
	stats = analyze_log(verdict_log)
	print_analysis(stats)

	if verdict == "PASS" and stats["scenario_ok"]:
		print("[result] PASS")
		return 0

	reasons = [f"游戏内 verdict={verdict}"]
	if not stats["scenario_ok"]:
		failed = ", ".join(name for name, ok, _ in stats["checks"] if not ok)
		reasons.append(f"场景校验未过：{failed}")
	print(f"[result] FAIL（{'; '.join(reasons)}）")
	return 1


if __name__ == "__main__":
	sys.exit(main(sys.argv))
