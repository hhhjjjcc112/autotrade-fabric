#!/usr/bin/env python3
"""启动 dev 客户端并自动进入指定测试世界（正常窗口模式，跨平台，非 headless）。

用法（从仓库根目录 autotrade-fabric/ 运行）：
	python test/lib/launch_testclient.py                        # static：部署脚本 → 重建 AutoTradeTest 世界与 STATIC 配置 → 启动
	python test/lib/launch_testclient.py --world AutoTradeTest
	python test/lib/launch_testclient.py --world AutoTradeVoidTest   # 世界名含 Void → 自动按 void 模式
	python test/lib/launch_testclient.py --mode void                # 世界默认 AutoTradeVoidTest
	python test/lib/launch_testclient.py --mode moving              # 世界默认 AutoTradeMovingTest
	python test/lib/launch_testclient.py --mode capacity            # 世界默认 AutoTradeCapacityTest
	python test/lib/launch_testclient.py --mode void --no-rebuild   # 跳过重建（沿用现有世界/配置）
	python test/lib/launch_testclient.py --deploy-only              # 只部署 Minescript 脚本，不重建/不启动
	python test/lib/launch_testclient.py --headless                 # 经本地 HeadlessMC 无头启动（替代 gradlew runClient）
	python test/lib/launch_testclient.py --headless --stage-only    # 只暂存到 HeadlessMC 环境后退出（不启动）
	python test/lib/launch_testclient.py --dry-run                  # 只打印将执行的命令，无任何副作用

默认行为（依次执行）：
	1) 部署：把 test/minescript/*.py 复制到 run/minescript/；若 run/minescript/config.txt
	   不存在，则按 test/minescript/config.example.txt 生成（__PYTHON__ 替换为当前解释器）；
	   若已存在，则把 config.example.txt 中缺失的 autorun[<key>]=<cmd> 规则追加到 config.txt
	   末尾（**只增不改**：绝不修改或删除既有行，python= 等其它设置原样保留）。
	2) 重建：执行 setup_testworld.py --mode <mode> --world-name <world> --fresh
	   （删除并重新生成世界 level.dat + datapack，备份并重写 run/config/autotrade.json，自校验；
	   校验失败则中止启动）。
	3) 启动：
	   - 默认（窗口）：gradlew --no-daemon -Ptestworld.world=<world> -I test/lib/testworld.init.gradle runClient
	   - --headless：先经 headless.stage() 把 mod/世界/配置/Minescript 暂存到本地
	     .tools/headlessmc/game，再用 HeadlessMC 启动器无头启动（不跑 gradle）

参数：
	--world NAME     世界名（优先级：本参数 > 环境变量 TESTWORLD_WORLD > 按模式默认）
	--mode {static,void,moving,capacity}
	                 装置模式（默认由世界名推断：含 moving → moving，含 void → void，含 capacity → capacity，否则 static）
	--no-rebuild     跳过世界/配置重建（保留上次世界进度；此时 config 需自行保证与模式匹配）
	--no-deploy      跳过 Minescript 脚本部署
	--deploy-only    只执行部署并退出（不重建、不启动）
	--headless       经本地 HeadlessMC（.tools/headlessmc，非 git）无头启动，替代 gradlew runClient
	--headless-timeout SECONDS
	                 无头启动看门狗超时（默认 600s）；超时未退出则结束进程树并返回 2
	--stage-only     仅 --headless：完成暂存后退出 0（不启动游戏）
	--dry-run        只打印将执行的部署/重建/暂存/启动计划，不产生任何副作用

日志：重建与 Gradle 的子进程输出实时打印并追加写入 test/launch.log。

说明：世界名经 Gradle init script（-Ptestworld.world）注入 runClient 的 --quickPlaySingleplayer 程序参数，
	而非 `--args`——因为 `--args` 经「命令行 → cmd → Gradle/Loom」链路时值会丢失。另设环境变量
	TESTWORLD_WORLD 兜底。Gradle 必须带 --no-daemon（见 AGENTS.md 规则 2）。

注意：本脚本会启动 Minecraft dev 客户端；按项目规则，启动测试须先获用户批准。
"""

import argparse
import os
import re
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

# 仓库根目录（autotrade-fabric/）：<repo>/test/lib/launch_testclient.py -> parents[2]
TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = Path(__file__).resolve().parents[2]
# 日志与实时脚本源均在 test/ 下（lib 的上一级）：test/launch.log、test/minescript
LOG_PATH = Path(__file__).resolve().parents[1] / "launch.log"
MINESCRIPT_SRC = Path(__file__).resolve().parents[1] / "minescript"
MINESCRIPT_DST = REPO_ROOT / "run" / "minescript"
GRADLE = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")

# autorun 行匹配：autorun[<key>]=<cmd>（key 已含 autorun[...] 前缀）
AUTORUN_RE = re.compile(r"^\s*(autorun\[[^\]]+\])\s*=\s*(.*?)\s*$")

DEFAULT_WORLD_NAMES = {
	"static": "AutoTradeTest",
	"void": "AutoTradeVoidTest",
	"moving": "AutoTradeMovingTest",
	"capacity": "AutoTradeCapacityTest",
}


def resolve_world_mode(raw_world: str, raw_mode: str | None) -> tuple[str, str]:
	"""解析世界名与模式：世界名优先级 参数 > 环境变量 > 默认；模式默认由世界名推断（moving > void > capacity > static）。"""
	world = raw_world or os.environ.get("TESTWORLD_WORLD", "")
	# 按世界名推断模式，顺序：moving > void > capacity > static；显式 --mode 优先
	mode = raw_mode
	if mode is None:
		lowered = world.lower()
		if "moving" in lowered:
			mode = "moving"
		elif "void" in lowered:
			mode = "void"
		elif "capacity" in lowered:
			mode = "capacity"
		else:
			mode = "static"
	if not world:
		world = DEFAULT_WORLD_NAMES[mode]
	return world, mode


def _minescript_files() -> list[Path]:
	"""返回 minescript 源目录下所有 .py 文件（按名排序）。"""
	if not MINESCRIPT_SRC.exists():
		return []
	return sorted(MINESCRIPT_SRC.glob("*.py"))


def _parse_autorun_rules(text: str) -> dict[str, str]:
	"""解析文本中所有 `autorun[<key>]=<cmd>` 行，返回 {完整键: 命令值}（保持出现顺序）。"""
	rules: dict[str, str] = {}
	for line in text.splitlines():
		match = AUTORUN_RE.match(line)
		if match:
			rules[match.group(1)] = match.group(2)
	return rules


def _missing_autorun_rules(config_path: Path | None = None, template_path: Path | None = None) -> dict[str, str]:
	"""对比模板与现有 config.txt，返回需要追加的 autorun 规则 {完整键: 命令值}。

	规则：
		- 以模板（默认 test/minescript/config.example.txt）为准（模板顺序）；
		- 只返回现有 config（默认 run/minescript/config.txt）中**缺失**的键；已存在的键一律不动。
	若模板不存在或 config 不存在（首启由模板生成）则返回空。
	参数化 config_path/template_path 以复用给 headless 暂存路径（省略时保持 dev 默认行为）。
	"""
	template = template_path or (MINESCRIPT_SRC / "config.example.txt")
	config = config_path or (MINESCRIPT_DST / "config.txt")
	if not template.is_file() or not config.is_file():
		return {}
	template_rules = _parse_autorun_rules(template.read_text(encoding="utf-8"))
	existing_rules = _parse_autorun_rules(config.read_text(encoding="utf-8"))
	return {key: value for key, value in template_rules.items() if key not in existing_rules}


def _append_autorun_rules(config_path: Path, rules: dict[str, str]) -> None:
	"""仅追加：向 config.txt 末尾逐行写入缺失的 autorun 规则（绝不改动既有任何行）。"""
	raw = config_path.read_bytes()
	# 以字节追加，保持既有内容（含换行风格）原封不动
	with config_path.open("a", encoding="utf-8", newline="") as handle:
		if raw and not raw.endswith(b"\n"):
			handle.write("\n")
		for key, value in rules.items():
			handle.write(f"{key}={value}\n")


def print_deploy_plan() -> None:
	"""--dry-run：打印部署计划（不产生任何文件操作）。"""
	print(f"[dry-run] deploy: mkdir {MINESCRIPT_DST}")
	for src in _minescript_files():
		print(f"[dry-run] deploy: copy {src} -> {MINESCRIPT_DST / src.name}")
	config = MINESCRIPT_DST / "config.txt"
	if config.exists():
		print(f"[dry-run] deploy: 保留现有 {config}（不覆盖）")
		missing = _missing_autorun_rules()
		if missing:
			for key, value in missing.items():
				print(f"[dry-run] deploy: 将追加 autorun 规则: {key}={value}")
		else:
			print("[dry-run] deploy: autorun 规则已齐全（无需追加）")
	else:
		template = MINESCRIPT_SRC / "config.example.txt"
		python_path = sys.executable.replace("\\", "/")
		print(f"[dry-run] deploy: 由 {template} 生成 {config}（python={python_path}）")


def deploy() -> None:
	"""把 minescript 脚本复制进 run/minescript/；config.txt 缺失时按模板生成、存在时按需追加 autorun。"""
	MINESCRIPT_DST.mkdir(parents=True, exist_ok=True)
	files = _minescript_files()
	for src in files:
		shutil.copy2(src, MINESCRIPT_DST / src.name)
	print(f"[deploy] 已复制 {len(files)} 个脚本到 {MINESCRIPT_DST}")
	config = MINESCRIPT_DST / "config.txt"
	if not config.exists():
		template = MINESCRIPT_SRC / "config.example.txt"
		python_path = sys.executable.replace("\\", "/")
		text = template.read_text(encoding="utf-8").replace("__PYTHON__", python_path)
		config.write_text(text, encoding="utf-8", newline="\n")
		print(f"[deploy] 生成 {config}（python={python_path}）")
		return
	print(f"[deploy] 保留现有 {config}（不覆盖）")
	missing = _missing_autorun_rules()
	if not missing:
		print("[deploy] autorun 规则已齐全（无需追加）")
		return
	_append_autorun_rules(config, missing)
	for key, value in missing.items():
		print(f"[deploy] 追加 autorun 规则: {key}={value}")


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
		description="启动 AutoTrade dev 客户端并自动进入测试世界（跨平台；--headless 经本地 HeadlessMC 无头启动）",
	)
	parser.add_argument(
		"--world",
		default="",
		help="世界名（默认按模式：static=AutoTradeTest / void=AutoTradeVoidTest / moving=AutoTradeMovingTest / capacity=AutoTradeCapacityTest）",
	)
	parser.add_argument("--mode", choices=("static", "void", "moving", "capacity"), default=None, help="装置模式（默认由世界名推断：moving > void > capacity > static）")
	parser.add_argument("--no-rebuild", action="store_true", help="跳过世界/配置重建")
	parser.add_argument("--no-deploy", action="store_true", help="跳过 Minescript 脚本部署")
	parser.add_argument("--deploy-only", action="store_true", help="只执行部署并退出（不重建、不启动）")
	parser.add_argument("--headless", action="store_true", help="经本地 HeadlessMC（.tools/headlessmc）无头启动，替代 gradlew runClient")
	parser.add_argument("--headless-timeout", type=int, default=None, help="无头启动看门狗超时秒数（默认 600）")
	parser.add_argument("--stage-only", action="store_true", help="仅 --headless：完成暂存后退出 0（不启动游戏）")
	parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令，无任何副作用")
	return parser


def _load_headless():
	"""延迟导入 headless 模块（避免循环依赖；兼容包导入与脚本直跑两种方式）。"""
	try:
		from lib import headless as module
	except ImportError:
		import headless as module
	return module


def main(argv: list[str]) -> int:
	"""解析参数并按「部署 → 重建 → 启动（gradle 或 headless）」流程执行。"""
	args = build_parser().parse_args(argv[1:])
	world, mode = resolve_world_mode(args.world, args.mode)
	if args.stage_only and not args.headless:
		build_parser().error("--stage-only 仅用于 --headless 模式")

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

	headless_mod = _load_headless()
	headless_timeout = args.headless_timeout if args.headless_timeout is not None else headless_mod.DEFAULT_TIMEOUT

	# --dry-run 优先于其它动作：只打印计划，绝不产生副作用
	if args.dry_run:
		print(f"[dry-run] mode={mode} world={world} dir={REPO_ROOT} headless={args.headless}")
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
		if args.headless:
			print(f"[dry-run] ---- headless 暂存计划（看门狗超时={headless_timeout}s）----")
			headless_mod.print_stage_plan(world)
			print("[dry-run] ---- headless launch 计划 ----")
			try:
				headless_cmd = headless_mod.build_launch_cmd(world)
				print(f"[dry-run] headless launch: cwd={headless_mod.HMC_DIR}")
				print(f"[dry-run] $ {' '.join(headless_cmd)}")
			except RuntimeError as exc:
				print(f"[dry-run] headless launch 不可用：{exc}")
		else:
			print("[dry-run] ---- launch 计划 ----")
			print(f"[dry-run] $ {' '.join(launch_cmd)}")
		return 0

	with LOG_PATH.open("a", encoding="utf-8", newline="\n") as log:
		# 启动头：时间戳 + 模式 + 世界名 + 仓库根目录 + 是否无头
		header = f"[launch] {datetime.now().isoformat(timespec='seconds')} mode={mode} world={world} dir={REPO_ROOT} headless={args.headless}"
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

		if args.headless:
			try:
				print(f"[launch] 无头暂存（HeadlessMC dir={headless_mod.HMC_DIR}）")
				headless_mod.stage(world)
			except RuntimeError as exc:
				print(f"[launch] 无头暂存失败：{exc}")
				return 1
			if args.stage_only:
				print("[launch] --stage-only：暂存完成，未启动客户端")
				return 0
			print(f"[launch] 无头启动客户端（超时 {headless_timeout}s）")
			return headless_mod.launch(world, headless_timeout, log)

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
