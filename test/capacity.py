#!/usr/bin/env python3
"""AutoTrade CAPACITY 容量检测测试入口（跨 OS）。

流程：
	1) 经 `test/lib/launch_testclient.py --mode capacity` 部署 Minescript 脚本
	   （test/minescript/*.py → run/minescript/，并按需追加 autorun 规则）；
	2) 重建 AutoTradeCapacityTest 世界与 CAPACITY 配置（setup_testworld.py --mode capacity --fresh）；
	3) 启动客户端（进图后 Minescript autorun 运行 capacity_test 逐组执行 23 用例；
	   观测结束后默认自动关游戏）；默认 gradlew runClient，--headless 时经本地 HeadlessMC 无头启动；
	4) 等待游戏退出后，解析本轮日志的 [verdict]（dev：run/logs/latest.log；--headless：HeadlessMC
	   游戏日志），并用判定库 `test/lib/capacity_analysis.py` 对同一日志打印 23 行逐组符合性表；
	5) 退出码 = 游戏内 verdict PASS **且** 23 组全部符合才为 0；任一不满足为 1；
	   未见本轮 verdict 仍为 2（stale-run 保护同既有入口）。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python test/capacity.py                    # 完整：部署 + 重建 + 启动 + 判定
	python test/capacity.py --no-rebuild       # 跳过世界/配置重建（沿用现有世界/配置）
	python test/capacity.py --no-deploy        # 跳过 Minescript 脚本部署
	python test/capacity.py --world NAME       # 覆盖世界名（默认 AutoTradeCapacityTest）
	python test/capacity.py --headless         # 经本地 HeadlessMC 无头启动（替代 gradlew runClient），默认超时 600s
	python test/capacity.py --headless --headless-timeout 600
	python test/capacity.py --dry-run          # 只打印将执行的命令，无任何副作用

退出码：0 = PASS（游戏内 verdict PASS 且 23 组全部符合）/ 1 = FAIL（打印未过组）/
	2 = 未见本轮 verdict（或启动前准备失败）。

注意：本脚本会启动 Minecraft 客户端（dev 或 headless）；按项目规则（AGENTS.md 规则 7），启动测试须先获用户批准。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

# 使 `python test/capacity.py` 能 import lib.*（脚本目录 test/ 即包父目录）
sys.path.insert(0, str(Path(__file__).resolve().parent))

import lib.headless as headless  # noqa: E402
import lib.launch_testclient as launcher  # noqa: E402
from lib import capacity_analysis  # noqa: E402
from lib.verdict import parse_verdict  # noqa: E402

# 仓库根目录（autotrade-fabric/）：<repo>/test/capacity.py -> parents[1]
REPO_ROOT = Path(__file__).resolve().parents[1]
LOG_PATH = REPO_ROOT / "run" / "logs" / "latest.log"
DEFAULT_WORLD = "AutoTradeCapacityTest"
DEFAULT_HEADLESS_TIMEOUT = 600


def build_parser() -> argparse.ArgumentParser:
	"""构造命令行解析器（flags 对齐 test/moving.py）。"""
	parser = argparse.ArgumentParser(description="AutoTrade CAPACITY 测试入口（部署 → 重建 → 启动 → 判定）")
	parser.add_argument("--world", default=DEFAULT_WORLD, help=f"世界名（默认 {DEFAULT_WORLD}）")
	parser.add_argument("--no-rebuild", action="store_true", help="跳过世界/配置重建")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--headless", action="store_true", help="经本地 HeadlessMC（.tools/headlessmc）无头启动并解析其日志")
	parser.add_argument("--headless-timeout", type=int, default=DEFAULT_HEADLESS_TIMEOUT, help=f"无头启动看门狗超时秒数（默认 {DEFAULT_HEADLESS_TIMEOUT}）")
	parser.add_argument("--stage-only", action="store_true", help="仅 --headless：完成暂存后退出 0（不启动游戏）")
	parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，无任何副作用")
	return parser


def main(argv: list[str]) -> int:
	"""部署 → 启动 → 解析本轮 verdict 并逐组判定，返回进程退出码。"""
	args = build_parser().parse_args(argv[1:])
	launch_argv = ["launch_testclient.py", "--mode", "capacity", "--world", args.world]
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
		print(f"[dry-run] capacity mode=capacity world={args.world} headless={args.headless}")

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
		print("提示：确认客户端已进入世界且 Minescript 已运行 capacity_test（见 run/minescript/config.txt 的 autorun 规则）")
		return 2

	# 逐组符合性：复用判定库解析同一日志（不在入口复刻解析逻辑）
	results = capacity_analysis.analyze_log(verdict_log)
	print(f"[analysis] 本轮日志：{verdict_log}")
	print(capacity_analysis.render_table(results))
	all_pass = len(results) == len(capacity_analysis.CASES) and all(item["ok"] for item in results)

	if verdict == "PASS" and all_pass:
		print("[result] PASS")
		return 0

	reasons = [f"游戏内 verdict={verdict}"]
	if not all_pass:
		failed = ", ".join(item["id"] for item in results if not item["ok"])
		reasons.append(f"逐组符合性未过：{failed}")
	print(f"[result] FAIL（{'; '.join(reasons)}）")
	return 1


if __name__ == "__main__":
	sys.exit(main(sys.argv))
