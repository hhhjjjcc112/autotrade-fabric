#!/usr/bin/env python3
"""启动 dev 客户端并自动进入指定测试世界（正常窗口模式，跨平台，非 headless）。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python tools/testworld/launch_testclient.py                        # static：部署脚本 → 重建 AutoTradeTest 世界与 STATIC 配置 → 启动
	python tools/testworld/launch_testclient.py --world AutoTradeTest
	python tools/testworld/launch_testclient.py --world AutoTradeVoidTest   # 世界名含 Void → 自动按 void 模式
	python tools/testworld/launch_testclient.py --mode void                # 世界默认 AutoTradeVoidTest
	python tools/testworld/launch_testclient.py --mode void --no-rebuild   # 跳过重建（沿用现有世界/配置）
	python tools/testworld/launch_testclient.py --deploy-only              # 只部署 Minescript 脚本，不重建/不启动
	python tools/testworld/launch_testclient.py --dry-run                  # 只打印将执行的命令，无任何副作用

默认行为（依次执行）：
	1) 部署：把 tools/testworld/minescript/*.py 复制到 run/minescript/；若 run/minescript/config.txt
	   不存在，则按 tools/testworld/minescript/config.example.txt 生成（__PYTHON__ 替换为当前解释器）。
	2) 重建：执行 setup_testworld.py --mode <mode> --world-name <world> --fresh
	   （删除并重新生成世界 level.dat + datapack，备份并重写 run/config/autotrade.json，自校验；
	   校验失败则中止启动）。
	3) 启动：gradlew --no-daemon -Ptestworld.world=<world> -I tools/testworld/testworld.init.gradle runClient

参数：
	--world NAME     世界名（优先级：本参数 > 环境变量 TESTWORLD_WORLD > 按模式默认）
	--mode {static,void}
	                 装置模式（默认由世界名推断：含 void → void，否则 static）
	--no-rebuild     跳过世界/配置重建（保留上次世界进度；此时 config 需自行保证与模式匹配）
	--no-deploy      跳过 Minescript 脚本部署
	--deploy-only    只执行部署并退出（不重建、不启动）
	--dry-run        只打印将执行的部署/重建/启动命令，不产生任何副作用（不写日志、不复制文件）

日志：重建与 Gradle 的子进程输出实时打印并追加写入 tools/testworld/launch.log。

说明：世界名经 Gradle init script（-Ptestworld.world）注入 runClient 的 --quickPlaySingleplayer 程序参数，
	而非 `--args`——因为 `--args` 经「命令行 → cmd → Gradle/Loom」链路时值会丢失。另设环境变量
	TESTWORLD_WORLD 兜底。Gradle 必须带 --no-daemon（见 AGENTS.md 规则 2）。

注意：本脚本会启动 Minecraft dev 客户端；按项目规则，启动测试须先获用户批准。
"""

import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# 控制台按 UTF-8 输出，避免中文在默认代码页下抛 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
	_reconfigure = getattr(_stream, "reconfigure", None)
	if _reconfigure is not None:
		try:
			_reconfigure(encoding="utf-8", errors="replace")
		except Exception:
			pass

# 仓库根目录（autotrade-fabric/）：<repo>/tools/testworld/launch_testclient.py -> parents[2]
TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = Path(__file__).resolve().parents[2]
LOG_PATH = TOOL_DIR / "launch.log"
MINESCRIPT_SRC = TOOL_DIR / "minescript"
MINESCRIPT_DST = REPO_ROOT / "run" / "minescript"
GRADLE = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")

DEFAULT_WORLD_NAMES = {"static": "AutoTradeTest", "void": "AutoTradeVoidTest"}


def resolve_world_mode(raw_world: str, raw_mode: str | None) -> tuple[str, str]:
	"""解析世界名与模式：世界名优先级 参数 > 环境变量 > 默认；模式默认由世界名推断。"""
	world = raw_world or os.environ.get("TESTWORLD_WORLD", "")
	mode = raw_mode or ("void" if "void" in world.lower() else "static")
	if not world:
		world = DEFAULT_WORLD_NAMES[mode]
	return world, mode


def _minescript_files() -> list[Path]:
	"""返回 minescript 源目录下所有 .py 文件（按名排序）。"""
	if not MINESCRIPT_SRC.exists():
		return []
	return sorted(MINESCRIPT_SRC.glob("*.py"))


def print_deploy_plan() -> None:
	"""--dry-run：打印部署计划（不产生任何文件操作）。"""
	print(f"[dry-run] deploy: mkdir {MINESCRIPT_DST}")
	for src in _minescript_files():
		print(f"[dry-run] deploy: copy {src} -> {MINESCRIPT_DST / src.name}")
	config = MINESCRIPT_DST / "config.txt"
	if config.exists():
		print(f"[dry-run] deploy: 保留现有 {config}（不覆盖）")
	else:
		template = MINESCRIPT_SRC / "config.example.txt"
		python_path = sys.executable.replace("\\", "/")
		print(f"[dry-run] deploy: 由 {template} 生成 {config}（python={python_path}）")


def deploy() -> None:
	"""把 minescript 脚本复制进 run/minescript/，并在 config.txt 缺失时按模板生成。"""
	MINESCRIPT_DST.mkdir(parents=True, exist_ok=True)
	files = _minescript_files()
	for src in files:
		shutil.copy2(src, MINESCRIPT_DST / src.name)
	print(f"[deploy] 已复制 {len(files)} 个脚本到 {MINESCRIPT_DST}")
	config = MINESCRIPT_DST / "config.txt"
	if config.exists():
		print(f"[deploy] 保留现有 {config}（不覆盖）")
		return
	template = MINESCRIPT_SRC / "config.example.txt"
	python_path = sys.executable.replace("\\", "/")
	text = template.read_text(encoding="utf-8").replace("__PYTHON__", python_path)
	config.write_text(text, encoding="utf-8", newline="\n")
	print(f"[deploy] 生成 {config}（python={python_path}）")


def run_streamed(cmd: list[str], log_handle, env: dict[str, str] | None = None) -> int:
	"""运行子进程：合并 stdout/stderr，实时打印并追加写入日志文件，返回退出码。"""
	print(f"[launch] $ {' '.join(cmd)}")
	log_handle.write(f"[launch] $ {' '.join(cmd)}\n")
	log_handle.flush()
	proc = subprocess.Popen(
		cmd,
		cwd=str(REPO_ROOT),
		env=env,
		stdout=subprocess.PIPE,
		stderr=subprocess.STDOUT,
		text=True,
		encoding="utf-8",
		errors="replace",
		bufsize=1,
	)
	assert proc.stdout is not None
	for line in proc.stdout:
		print(line, end="")
		log_handle.write(line)
		log_handle.flush()
	return proc.wait()


def build_parser() -> argparse.ArgumentParser:
	"""构造命令行解析器。"""
	parser = argparse.ArgumentParser(
		description="启动 AutoTrade dev 客户端并自动进入测试世界（跨平台；不修改仓库 build 文件）",
	)
	parser.add_argument("--world", default="", help="世界名（默认按模式：static=AutoTradeTest / void=AutoTradeVoidTest）")
	parser.add_argument("--mode", choices=("static", "void"), default=None, help="装置模式（默认由世界名推断）")
	parser.add_argument("--no-rebuild", action="store_true", help="跳过世界/配置重建")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--deploy-only", action="store_true", help="只执行部署并退出（不重建、不启动）")
	parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，无任何副作用")
	return parser


def main(argv: list[str]) -> int:
	"""解析参数并按「部署 → 重建 → 启动」流程执行。"""
	args = build_parser().parse_args(argv[1:])
	world, mode = resolve_world_mode(args.world, args.mode)

	rebuild_cmd = [
		sys.executable,
		str(TOOL_DIR / "setup_testworld.py"),
		"--mode",
		mode,
		"--world-name",
		world,
		"--fresh",
	]
	launch_cmd = [
		str(GRADLE),
		"--no-daemon",
		f"-Ptestworld.world={world}",
		"-I",
		str(TOOL_DIR / "testworld.init.gradle"),
		"runClient",
	]

	# --dry-run 优先于其它动作：只打印计划，绝不产生副作用
	if args.dry_run:
		print(f"[dry-run] mode={mode} world={world} dir={REPO_ROOT}")
		if args.no_deploy:
			print("[dry-run] deploy: 跳过（--no-deploy）")
		else:
			print("[dry-run] ---- deploy 计划 ----")
			print_deploy_plan()
		if args.no_rebuild:
			print("[dry-run] rebuild: 跳过（--no-rebuild）")
		else:
			print("[dry-run] ---- rebuild 计划 ----")
			print(f"[dry-run] $ {' '.join(rebuild_cmd)}")
		print("[dry-run] ---- launch 计划 ----")
		print(f"[dry-run] $ {' '.join(launch_cmd)}")
		return 0

	with LOG_PATH.open("a", encoding="utf-8", newline="\n") as log:
		# 启动头：时间戳 + 模式 + 世界名 + 仓库根目录
		header = f"[launch] {datetime.now().isoformat(timespec='seconds')} mode={mode} world={world} dir={REPO_ROOT}"
		print(header)
		log.write(header + "\n")
		log.flush()

		if args.deploy_only:
			deploy()
			return 0

		if not args.no_deploy:
			deploy()

		if not args.no_rebuild:
			print(f"[launch] 重建测试世界与配置: --mode {mode} --world-name {world} --fresh")
			code = run_streamed(rebuild_cmd, log)
			if code != 0:
				print(f"[launch] 测试世界重建/校验失败（exit={code}），已中止启动")
				return 1

		print(f"[launch] 启动 dev 客户端 dir={REPO_ROOT}")
		env = os.environ.copy()
		# 世界名经 -Ptestworld.world 传入（命令行属性，避免在 Gradle 启动链路上丢失）；环境变量兜底
		env["TESTWORLD_WORLD"] = world
		# POSIX：确保 gradlew 具备可执行位
		if os.name != "nt" and GRADLE.exists() and not (GRADLE.stat().st_mode & 0o111):
			os.chmod(GRADLE, 0o755)
		return run_streamed(launch_cmd, log, env=env)


if __name__ == "__main__":
	sys.exit(main(sys.argv))
