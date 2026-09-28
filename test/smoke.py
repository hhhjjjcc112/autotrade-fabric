#!/usr/bin/env python3
"""AutoTrade 冒烟测试入口（跨 OS）。

冒烟目标：证明「专用冒烟世界能加载 + mod 能加载」——不做任何交易断言。

流程：
	1) 准备专用冒烟世界：删除并重建 run/saves/AutoTradeSmokeTest/，**只**从只读模板
	   run/saves/New World/level.dat 复制 level.dat（绝不修改模板；不使用 datapack）；
	2) 调用启动器（--world AutoTradeSmokeTest --no-rebuild）：部署 → 启动客户端
	   （进图后 Minescript autorun 运行 smoke_test 冒烟观测；结束后默认自动关游戏）；
	   默认 gradlew runClient，--headless 时改经本地 HeadlessMC 无头启动；
	3) 等待游戏退出后，解析本轮日志（dev：run/logs/latest.log；--headless：HeadlessMC 游戏日志）的 [verdict] 并据此返回退出码。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python test/smoke.py                  # 完整：准备世界 + 部署 + 启动 + 判定
	python test/smoke.py --prepare-only   # 只准备冒烟世界后退出（离线校验用）
	python test/smoke.py --dry-run        # 只打印世界准备与启动计划，无任何副作用
	python test/smoke.py --no-deploy      # 跳过 Minescript 脚本部署
	python test/smoke.py --headless       # 经本地 HeadlessMC 无头启动（替代 gradlew runClient），默认超时 180s
	python test/smoke.py --headless --headless-timeout 300
	python test/smoke.py --headless --stage-only   # 仅暂存到 HeadlessMC 环境后退出（离线校验）

退出码：0 = PASS（或 --prepare-only 成功）/ 1 = FAIL / 2 = 未见本轮 verdict（或世界准备失败）。

注意：本脚本会启动 Minecraft 客户端（dev 或 headless）；按项目规则（AGENTS.md 规则 7），启动测试须先获用户批准。
"""

from __future__ import annotations

import argparse
import shutil
import sys
import time
from pathlib import Path

# 使 `python test/smoke.py` 能 import lib.*（脚本目录 test/ 即包父目录）
sys.path.insert(0, str(Path(__file__).resolve().parent))

import lib.headless as headless  # noqa: E402
import lib.launch_testclient as launcher  # noqa: E402
from lib.verdict import parse_verdict  # noqa: E402

# 仓库根目录（autotrade-fabric/）：<repo>/test/smoke.py -> parents[1]
REPO_ROOT = Path(__file__).resolve().parents[1]
LOG_PATH = REPO_ROOT / "run" / "logs" / "latest.log"
SAVES_DIR = REPO_ROOT / "run" / "saves"
SMOKE_WORLD = "AutoTradeSmokeTest"
TEMPLATE_WORLD = "New World"
DEFAULT_HEADLESS_TIMEOUT = 180


def _template_level() -> Path:
	"""只读模板 level.dat 路径（run/saves/New World/level.dat）。"""
	return SAVES_DIR / TEMPLATE_WORLD / "level.dat"


def _smoke_world_dir() -> Path:
	"""专用冒烟世界目录（run/saves/AutoTradeSmokeTest）。"""
	return SAVES_DIR / SMOKE_WORLD


def print_prepare_plan() -> None:
	"""--dry-run：打印世界准备计划（不产生任何文件操作）。"""
	print(f"[dry-run] prep: 删除并重建 {_smoke_world_dir()}")
	print(f"[dry-run] prep: copy {_template_level()} -> {_smoke_world_dir() / 'level.dat'}（模板只读，不修改）")


def prepare_smoke_world() -> bool:
	"""删除并重建冒烟世界，只从只读模板复制 level.dat；成功返回 True。"""
	template = _template_level()
	world_dir = _smoke_world_dir()
	if not template.is_file():
		print(f"[prep] 错误：只读模板 level.dat 不存在：{template}")
		return False
	if world_dir.exists():
		print(f"[prep] 删除已存在的冒烟世界：{world_dir}")
		shutil.rmtree(world_dir)
	world_dir.mkdir(parents=True, exist_ok=True)
	shutil.copy2(template, world_dir / "level.dat")
	print(f"[prep] 已生成冒烟世界：{world_dir}（仅复制 level.dat；模板 {template} 未改动）")
	return True


def build_parser() -> argparse.ArgumentParser:
	"""构造命令行解析器。"""
	parser = argparse.ArgumentParser(description="AutoTrade 冒烟测试入口（准备世界 → 部署 → 启动 → 判定）")
	parser.add_argument("--prepare-only", action="store_true", help="只准备冒烟世界后退出（离线校验用）")
	parser.add_argument("--dry-run", action="store_true", help="只打印计划，无任何副作用")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--headless", action="store_true", help="经本地 HeadlessMC（.tools/headlessmc）无头启动并解析其日志")
	parser.add_argument("--headless-timeout", type=int, default=DEFAULT_HEADLESS_TIMEOUT, help=f"无头启动看门狗超时秒数（默认 {DEFAULT_HEADLESS_TIMEOUT}）")
	parser.add_argument("--stage-only", action="store_true", help="仅 --headless：完成暂存后退出 0（不启动游戏）")
	return parser


def main(argv: list[str]) -> int:
	"""准备世界 → 启动 → 解析本轮 verdict，返回进程退出码。"""
	args = build_parser().parse_args(argv[1:])

	launch_argv = ["launch_testclient.py", "--world", SMOKE_WORLD, "--no-rebuild"]
	if args.no_deploy:
		launch_argv.append("--no-deploy")
	if args.headless:
		launch_argv += ["--headless", "--headless-timeout", str(args.headless_timeout)]
	if args.stage_only:
		launch_argv.append("--stage-only")

	if args.dry_run:
		print(f"[dry-run] smoke world={SMOKE_WORLD} dir={REPO_ROOT} headless={args.headless}")
		print("[dry-run] ---- 世界准备计划 ----")
		print_prepare_plan()
		print("[dry-run] ---- launch 计划 ----")
		launch_argv.append("--dry-run")
		return launcher.main(launch_argv)

	if args.prepare_only:
		return 0 if prepare_smoke_world() else 2

	if not prepare_smoke_world():
		return 2

	# 起始时间戳须在启动之前记录：用于 stale-run 保护（只认本轮写出的 verdict）
	start = time.time()
	code = launcher.main(launch_argv)
	if code != 0:
		print(f"[warn] 启动器返回非零退出码 {code}（可能为部署/启动失败）")
	if args.stage_only:
		return code

	# 无头模式解析 HeadlessMC 游戏日志，dev 模式解析 run/logs/latest.log
	verdict_log = headless.log_path() if args.headless else LOG_PATH
	verdict = parse_verdict(verdict_log, since=start)
	if verdict == "PASS":
		print("[result] PASS")
		return 0
	if verdict == "FAIL":
		print("[result] FAIL")
		return 1
	print(f"[result] 未见本轮 [verdict]（日志：{verdict_log}）")
	print("提示：确认客户端已进入世界且 Minescript 已运行 smoke_test（见 run/minescript/config.txt 的 autorun 规则）")
	return 2


if __name__ == "__main__":
	sys.exit(main(sys.argv))
