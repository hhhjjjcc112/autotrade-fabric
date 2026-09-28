#!/usr/bin/env python3
"""HeadlessMC 无头测试启动支持（AutoTrade 测试工具链，跨 OS）。

职责：把 AutoTrade 测试所需的 mod / 世界 / 配置 / Minescript 脚本暂存到本地
非 git 的 HeadlessMC 环境（<workspace>/.tools/headlessmc/game），再经 HeadlessMC
启动器无头启动 MC 1.20.4，供 test/static.py、test/void.py、test/smoke.py 三个入口
在 --headless 模式下复用。

关键路径（工作区根 = 仓库根的上一级）：
	<workspace>/.tools/headlessmc/                     HeadlessMC 本地环境（非 git，须预先存在）
	<workspace>/.tools/headlessmc/game/                游戏目录（hmc.gamedir）
	<workspace>/.tools/headlessmc/game/mods/           暂存 mod
	<workspace>/.tools/headlessmc/game/saves/          暂存世界
	<workspace>/.tools/headlessmc/game/config/         暂存配置
	<workspace>/.tools/headlessmc/game/minescript/     暂存 Minescript 脚本与配置
	<workspace>/.tools/headlessmc/game/logs/latest.log 游戏日志（verdict 解析来源）

注意：HeadlessMC 会在「当前工作目录」下读取 HeadlessMC\\ 配置，故启动器必须以
.tools/headlessmc 为工作目录运行；本模块 launch() 已固定 cwd。不传 -quit / -stay，
以便把游戏进程的退出码原样透传（见 HEADLESSMC 退出行为）。
"""

import os
import shutil
import signal
import subprocess
import sys
import threading
from pathlib import Path

# 控制台按 UTF-8 输出，避免中文在默认代码页下抛 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
	_reconfigure = getattr(_stream, "reconfigure", None)
	if _reconfigure is not None:
		try:
			_reconfigure(encoding="utf-8", errors="replace")
		except Exception:
			pass

# 仓库根（autotrade-fabric/）：<repo>/test/lib/headless.py -> parents[2]
TOOL_DIR = Path(__file__).resolve().parent
REPO_ROOT = Path(__file__).resolve().parents[2]
# 工作区根 = 仓库根上一级（HeadlessMC 环境位于 <workspace>/.tools/headlessmc）
WORKSPACE_ROOT = REPO_ROOT.parent
HMC_DIR = WORKSPACE_ROOT / ".tools" / "headlessmc"
HMC_GAME_DIR = HMC_DIR / "game"
HMC_MODS = HMC_GAME_DIR / "mods"
HMC_SAVES = HMC_GAME_DIR / "saves"
HMC_VERSION = "fabric:1.20.4"
HEADLESS_LOG = HMC_GAME_DIR / "logs" / "latest.log"
DEFAULT_TIMEOUT = 600


def launcher_jar() -> Path | None:
	"""返回 HMC_DIR 下最新的 headlessmc-launcher-*.jar（按名排序取末位）；无则 None。"""
	jars = sorted(HMC_DIR.glob("headlessmc-launcher-*.jar"))
	return jars[-1] if jars else None


# 模块级常量：import 时解析一次（供展示/兼容直接引用）
HMC_LAUNCHER = launcher_jar()


def _newest(directory: Path, pattern: str) -> Path | None:
	"""返回目录下匹配 pattern 的最新文件（按名排序取末位，适配带版本号文件名）；无则 None。"""
	files = sorted(directory.glob(pattern)) if directory.is_dir() else []
	return files[-1] if files else None


def _load_launcher():
	"""延迟导入 launch_testclient（避免循环依赖；兼容包导入与脚本直跑两种方式）。"""
	try:
		from lib import launch_testclient as module
	except ImportError:
		import launch_testclient as module
	return module


def check_env() -> None:
	"""校验 HeadlessMC 本地环境存在；缺失则抛出带中文说明的 RuntimeError。"""
	if not HMC_DIR.is_dir():
		raise RuntimeError(f"未找到 HeadlessMC 本地环境：{HMC_DIR}\n（headless 模式需要本地 .tools/headlessmc，该目录不在 git 内）")
	if launcher_jar() is None:
		raise RuntimeError(f"未找到 HeadlessMC 启动器 jar（{HMC_DIR}\\headlessmc-launcher-*.jar）")
	if not HMC_GAME_DIR.is_dir():
		raise RuntimeError(f"未找到 HeadlessMC 游戏目录：{HMC_GAME_DIR}")


def stage_mods() -> None:
	"""暂存运行所需 mod：清理旧 autotrade jar，复制新构建的 autotrade 1.20.4 jar 与 minescript jar，并校验依赖。"""
	HMC_MODS.mkdir(parents=True, exist_ok=True)
	# 清理上一轮的 autotrade jar，避免新旧版本共存导致 mod 冲突
	for stale in sorted(HMC_MODS.glob("autotrade-fabric-*.jar")):
		stale.unlink()
		print(f"[headless] 移除旧 autotrade jar：{stale.name}")
	autotrade = _newest(REPO_ROOT / "build" / "libs", "autotrade-fabric-1.20.4-*.jar")
	if autotrade is None:
		raise RuntimeError(f"未找到 autotrade 1.20.4 构建产物（{REPO_ROOT / 'build' / 'libs'}\\autotrade-fabric-1.20.4-*.jar）；请先运行：.\\gradlew --no-daemon build")
	shutil.copy2(autotrade, HMC_MODS / autotrade.name)
	print(f"[headless] 暂存 mod：{autotrade.name} -> {HMC_MODS}")
	minescript = _newest(REPO_ROOT / "run" / "mods", "minescript-*.jar")
	if minescript is None:
		raise RuntimeError(f"未找到 Minescript mod（{REPO_ROOT / 'run' / 'mods'}\\minescript-*.jar）")
	shutil.copy2(minescript, HMC_MODS / minescript.name)
	print(f"[headless] 暂存 mod：{minescript.name} -> {HMC_MODS}")
	# 运行依赖须预先存在于 game/mods（本地环境准备，不由本脚本下载）
	for pattern, hint in (("malilib-*.jar", "malilib"), ("fabric-api-*.jar", "fabric-api")):
		if _newest(HMC_MODS, pattern) is None:
			raise RuntimeError(f"缺少依赖 mod：{HMC_MODS}\\{pattern}（请先把 {hint} 放入 game/mods）")


def stage_world(world_name: str) -> None:
	"""把 run/saves/<world_name> 复制到 HeadlessMC 游戏目录（先删除同名旧世界）。源世界须已准备。"""
	src = REPO_ROOT / "run" / "saves" / world_name
	if not src.is_dir():
		raise RuntimeError(f"未找到待暂存的世界：{src}（请先运行入口脚本准备/重建该世界）")
	dst = HMC_SAVES / world_name
	if dst.exists():
		shutil.rmtree(dst)
		print(f"[headless] 移除旧世界：{dst}")
	HMC_SAVES.mkdir(parents=True, exist_ok=True)
	shutil.copytree(src, dst)
	print(f"[headless] 暂存世界：{src} -> {dst}")


def stage_config() -> None:
	"""把 run/config/autotrade.json 复制到 HeadlessMC 游戏目录的 config/。"""
	src = REPO_ROOT / "run" / "config" / "autotrade.json"
	if not src.is_file():
		raise RuntimeError(f"未找到配置文件：{src}（请先运行入口脚本生成/重建配置）")
	dst_dir = HMC_GAME_DIR / "config"
	dst_dir.mkdir(parents=True, exist_ok=True)
	shutil.copy2(src, dst_dir / "autotrade.json")
	print(f"[headless] 暂存配置：{src} -> {dst_dir / 'autotrade.json'}")


def stage_minescript() -> None:
	"""暂存 Minescript 脚本与配置到 HeadlessMC 游戏目录。

	- 复制 run/minescript/*.py（已部署脚本，缺失时回退 test/minescript）到 game/minescript/；
	- config.txt 不存在时按 test/minescript/config.example.txt 生成（替换 __PYTHON__）；
	- config.txt 已存在时复用 launch_testclient 的 autorun 合并逻辑，仅追加缺失规则。
	"""
	dst_dir = HMC_GAME_DIR / "minescript"
	dst_dir.mkdir(parents=True, exist_ok=True)
	script_src = REPO_ROOT / "run" / "minescript"
	if not script_src.is_dir():
		script_src = TOOL_DIR.parent / "minescript"
	scripts = sorted(script_src.glob("*.py"))
	for src in scripts:
		shutil.copy2(src, dst_dir / src.name)
	print(f"[headless] 暂存 Minescript 脚本：{len(scripts)} 个（{script_src} -> {dst_dir}）")
	config = dst_dir / "config.txt"
	template = TOOL_DIR.parent / "minescript" / "config.example.txt"
	if not config.is_file():
		python_path = sys.executable.replace("\\", "/")
		config.write_text(template.read_text(encoding="utf-8").replace("__PYTHON__", python_path), encoding="utf-8", newline="\n")
		print(f"[headless] 生成 Minescript 配置：{config}（python={python_path}）")
		return
	print(f"[headless] 保留现有 Minescript 配置：{config}（不覆盖）")
	launcher = _load_launcher()
	missing = launcher._missing_autorun_rules(config_path=config, template_path=template)
	if not missing:
		print("[headless] autorun 规则已齐全（无需追加）")
		return
	launcher._append_autorun_rules(config, missing)
	for key, value in missing.items():
		print(f"[headless] 追加 autorun 规则：{key}={value}")


def stage(world_name: str) -> None:
	"""完整暂存：mod + 世界 + 配置 + Minescript（无头运行前调用）。"""
	check_env()
	stage_mods()
	stage_world(world_name)
	stage_config()
	stage_minescript()


def print_stage_plan(world_name: str) -> None:
	"""--dry-run：打印暂存计划（只读，不做任何文件操作）。"""
	jar = launcher_jar()
	print(f"[dry-run] headless: gamedir={HMC_GAME_DIR} launcher={jar if jar else '(未找到 launcher jar)'}")
	autotrade = _newest(REPO_ROOT / "build" / "libs", "autotrade-fabric-1.20.4-*.jar")
	print(f"[dry-run] headless: 清理 {HMC_MODS}\\autotrade-fabric-*.jar")
	print(f"[dry-run] headless: 暂存 mod {autotrade if autotrade else '(未找到 autotrade 构建产物)'} -> {HMC_MODS}")
	minescript = _newest(REPO_ROOT / "run" / "mods", "minescript-*.jar")
	print(f"[dry-run] headless: 暂存 mod {minescript if minescript else '(未找到 minescript jar)'} -> {HMC_MODS}")
	print(f"[dry-run] headless: 校验依赖 malilib-*.jar / fabric-api-*.jar 存在于 {HMC_MODS}")
	print(f"[dry-run] headless: 暂存世界 {REPO_ROOT / 'run' / 'saves' / world_name} -> {HMC_SAVES / world_name}")
	print(f"[dry-run] headless: 暂存配置 {REPO_ROOT / 'run' / 'config' / 'autotrade.json'} -> {HMC_GAME_DIR / 'config' / 'autotrade.json'}")
	print(f"[dry-run] headless: 暂存 Minescript 脚本 -> {HMC_GAME_DIR / 'minescript'}")
	print(f"[dry-run] headless: 生成/合并 {HMC_GAME_DIR / 'minescript' / 'config.txt'}")


def build_launch_cmd(world_name: str) -> list[str]:
	"""构造 HeadlessMC 启动命令（不含 -quit/-stay，确保游戏退出码透传）。"""
	jar = launcher_jar()
	if jar is None:
		raise RuntimeError(f"未找到 HeadlessMC 启动器 jar（{HMC_DIR}\\headlessmc-launcher-*.jar）")
	command = f'launch {HMC_VERSION} -lwjgl --jvm "-Djava.awt.headless=true" --game-args "--quickPlaySingleplayer {world_name}"'
	return ["java", "-jar", str(jar), "--command", command]


def _stream_reader(proc: subprocess.Popen, log_handle) -> None:
	"""后台线程：把子进程（合并后的）输出逐行打印到控制台并追加写入日志。"""
	assert proc.stdout is not None
	for line in proc.stdout:
		print(line, end="")
		log_handle.write(line)
		log_handle.flush()


def _kill_tree(proc: subprocess.Popen) -> None:
	"""强制结束子进程及其派生进程树（Windows 用 taskkill /T；POSIX 杀进程组）。"""
	if os.name == "nt":
		subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
		return
	try:
		os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
	except OSError:
		proc.kill()


def launch(world_name: str, timeout: int, log_handle, dry_run: bool = False) -> int:
	"""经 HeadlessMC 无头启动客户端，带看门狗超时。

	返回游戏进程退出码；若超时仍未退出（疑似自动退出失败）则结束进程树并返回 2。
	"""
	check_env()
	cmd = build_launch_cmd(world_name)
	if dry_run:
		print(f"[dry-run] headless launch: cwd={HMC_DIR}")
		print(f"[dry-run] $ {' '.join(cmd)}")
		return 0
	print(f"[launch] headless: cwd={HMC_DIR}")
	print(f"[launch] $ {' '.join(cmd)}")
	log_handle.write(f"[launch] headless: cwd={HMC_DIR}\n")
	log_handle.write(f"[launch] $ {' '.join(cmd)}\n")
	log_handle.flush()
	# POSIX 下新建会话/进程组，便于超时时整组结束；Windows 由 taskkill /T 处理
	extra: dict = {"start_new_session": True} if os.name != "nt" else {}
	proc = subprocess.Popen(
		cmd,
		cwd=str(HMC_DIR),
		stdout=subprocess.PIPE,
		stderr=subprocess.STDOUT,
		text=True,
		encoding="utf-8",
		errors="replace",
		bufsize=1,
		**extra,
	)
	reader = threading.Thread(target=_stream_reader, args=(proc, log_handle), daemon=True)
	reader.start()
	try:
		code = proc.wait(timeout=timeout)
	except subprocess.TimeoutExpired:
		message = f"[launch] 超过看门狗超时 {timeout}s，游戏未自动退出（疑似自动退出失败），正在结束整个进程树 …"
		print(message)
		log_handle.write(message + "\n")
		log_handle.flush()
		_kill_tree(proc)
		try:
			proc.wait(timeout=15)
		except subprocess.TimeoutExpired:
			pass
		reader.join(timeout=5)
		return 2
	reader.join()
	# Windows 下退出码 -1 呈现为 4294967295，归一为 -1 便于阅读
	if os.name == "nt" and code == 4294967295:
		code = -1
	return code


def log_path() -> Path:
	"""返回 HeadlessMC 环境的游戏日志路径（入口脚本据此解析本轮 verdict）。"""
	return HEADLESS_LOG
