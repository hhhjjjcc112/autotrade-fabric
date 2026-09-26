#!/usr/bin/env python3
"""AutoTrade VOID 交易测试入口（跨 OS）。

流程：
	1) 部署 Minescript 脚本（test/minescript/*.py → run/minescript/，并按需追加 autorun 规则）；
	2) 重建 AutoTradeVoidTest 世界与 VOID 配置（setup_testworld.py --mode void --fresh）；
	3) 启动 dev 客户端（进图后 Minescript autorun 运行 void_test 观测；观测结束后默认自动关游戏）；
	4) 等待游戏退出后，解析本轮 run/logs/latest.log 的 [verdict] 并据此返回退出码。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python test/void.py                 # 完整：部署 + 重建 + 启动 + 判定
	python test/void.py --no-rebuild    # 跳过世界/配置重建（沿用现有世界/配置）
	python test/void.py --no-deploy     # 跳过 Minescript 脚本部署
	python test/void.py --world NAME    # 覆盖世界名（默认 AutoTradeVoidTest）
	python test/void.py --dry-run       # 只打印将执行的命令，无任何副作用

退出码：0 = PASS / 1 = FAIL / 2 = 未见本轮 verdict（或启动前准备失败）。

注意：本脚本会启动 Minecraft dev 客户端；按项目规则（AGENTS.md 规则 7），启动测试须先获用户批准。
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

# 使 `python test/void.py` 能 import lib.*（脚本目录 test/ 即包父目录）
sys.path.insert(0, str(Path(__file__).resolve().parent))

import lib.launch_testclient as launcher  # noqa: E402
from lib.verdict import parse_verdict  # noqa: E402

# 仓库根目录（autotrade-fabric/）：<repo>/test/void.py -> parents[1]
REPO_ROOT = Path(__file__).resolve().parents[1]
LOG_PATH = REPO_ROOT / "run" / "logs" / "latest.log"
DEFAULT_WORLD = "AutoTradeVoidTest"


def build_parser() -> argparse.ArgumentParser:
	"""构造命令行解析器。"""
	parser = argparse.ArgumentParser(description="AutoTrade VOID 测试入口（部署 → 重建 → 启动 → 判定）")
	parser.add_argument("--world", default=DEFAULT_WORLD, help=f"世界名（默认 {DEFAULT_WORLD}）")
	parser.add_argument("--no-rebuild", action="store_true", help="跳过世界/配置重建")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，无任何副作用")
	return parser


def main(argv: list[str]) -> int:
	"""部署 → 启动 → 解析本轮 verdict，返回进程退出码。"""
	args = build_parser().parse_args(argv[1:])
	launch_argv = ["launch_testclient.py", "--mode", "void", "--world", args.world]
	if args.no_rebuild:
		launch_argv.append("--no-rebuild")
	if args.no_deploy:
		launch_argv.append("--no-deploy")
	if args.dry_run:
		launch_argv.append("--dry-run")

	# 起始时间戳须在启动之前记录：用于 stale-run 保护（只认本轮写出的 verdict）
	start = time.time()
	code = launcher.main(launch_argv)
	if args.dry_run:
		return code
	if code != 0:
		print(f"[warn] 启动器返回非零退出码 {code}（可能为重建/启动失败）")

	verdict = parse_verdict(LOG_PATH, since=start)
	if verdict == "PASS":
		print("[result] PASS")
		return 0
	if verdict == "FAIL":
		print("[result] FAIL")
		return 1
	print(f"[result] 未见本轮 [verdict]（日志：{LOG_PATH}）")
	print("提示：确认客户端已进入世界且 Minescript 已运行 void_test（见 run/minescript/config.txt 的 autorun 规则）")
	return 2


if __name__ == "__main__":
	sys.exit(main(sys.argv))
