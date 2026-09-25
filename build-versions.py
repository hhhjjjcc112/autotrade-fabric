#!/usr/bin/env python3
"""AutoTrade-Fabric 多版本构建脚本（MC 1.20 - 1.20.4）。

按 malilib 官方构件分组产出 3 个 jar（代码零改动，仅切换依赖版本）：
	autotrade-fabric-1.20.1-<ver>.jar  覆盖 MC 1.20 + 1.20.1
	autotrade-fabric-1.20.2-<ver>.jar  覆盖 MC 1.20.2
	autotrade-fabric-1.20.4-<ver>.jar  覆盖 MC 1.20.3 + 1.20.4

用法（在 Gradle 项目目录 autotrade-fabric/ 下执行，即本脚本所在目录）：
	python build-versions.py            # 依次构建 3 个版本
	python build-versions.py --dry-run  # 仅打印将执行的命令，不实际构建

注意：gradlew 必须携带 --no-daemon（见 AGENTS.md 核心规则 #2）。
注意：CI 工作流（.github/workflows/build.yml / release.yml）内联了同一份版本矩阵，
	修改版本参数时须同步更新本脚本与工作流矩阵。
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

# 控制台按 UTF-8 输出，避免中文在默认代码页下抛 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
	_reconfigure = getattr(_stream, "reconfigure", None)
	if _reconfigure is not None:
		try:
			_reconfigure(encoding="utf-8", errors="replace")
		except Exception:
			pass

# 仓库根目录：本脚本位于 Gradle 项目根（autotrade-fabric/）
REPO_ROOT = Path(__file__).resolve().parent

# gradlew 启动脚本：Windows 用 gradlew.bat，其它平台用 gradlew
GRADLE = REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")

# 版本矩阵：唯一权威来源（原 build-versions.ps1 / build-versions.sh 的合并）
# 每项 = (构建名称, gradle 属性参数列表)；每个属性作为单个 argv 元素传递，
# 因此含空格的 -Pminecraft_version_range 无需任何引号处理
MATRIX = [
	(
		"1.20.1",
		[
			"-Pminecraft_version=1.20.1",
			"-Pmappings_version=1.20.1+build.10",
			"-Pminecraft_version_out=1.20.1",
			"-Pmalilib_version=0.16.1",
			"-Pfabric_api_version=0.92.6+1.20.1",
			"-Pfabric_api_version_min=0.83.0",
			"-Pmod_menu_version=7.2.2",
			"-Pitemscroller_version=0.20.0",
			"-Pminecraft_version_range=>=1.20 <1.20.2",
		],
	),
	(
		"1.20.2",
		[
			"-Pminecraft_version=1.20.2",
			"-Pmappings_version=1.20.2+build.4",
			"-Pminecraft_version_out=1.20.2",
			"-Pmalilib_version=0.17.0",
			"-Pfabric_api_version=0.91.6+1.20.2",
			"-Pfabric_api_version_min=0.86.1",
			"-Pmod_menu_version=8.0.1",
			"-Pitemscroller_version=0.21.0",
			"-Pminecraft_version_range=>=1.20.2 <1.20.3",
		],
	),
	(
		"1.20.4",
		[
			"-Pminecraft_version=1.20.4",
			"-Pmappings_version=1.20.4+build.3",
			"-Pminecraft_version_out=1.20.4",
			"-Pmalilib_version=0.18.0",
			"-Pfabric_api_version=0.92.1+1.20.4",
			"-Pfabric_api_version_min=0.91.1",
			"-Pmod_menu_version=9.0.0",
			"-Pitemscroller_version=0.22.0",
			"-Pminecraft_version_range=>=1.20.3 <1.20.5",
		],
	),
]


def _run_build(name: str, props: list) -> None:
	"""执行单个版本的构建；失败则打印错误并退出（中止后续构建）。"""
	print("")
	print(f"========== 构建 autotrade-fabric-{name} ==========")
	# 子进程直接继承控制台输出（不捕获），保持与旧脚本一致的实时构建日志
	result = subprocess.run([str(GRADLE), "--no-daemon", "build", *props], cwd=REPO_ROOT)
	if result.returncode != 0:
		print(f"构建 {name} 失败（exit={result.returncode}），中止后续构建", file=sys.stderr)
		sys.exit(1)
	print(f"========== 构建 {name} 成功 ==========")


def _list_artifacts() -> None:
	"""列出 build/libs/ 下的 jar 产物；目录不存在时给出提示。"""
	libs = REPO_ROOT / "build" / "libs"
	print("")
	print("全部构建完成，产物位于 build/libs/ 目录：")
	if not libs.is_dir():
		print(f"（未找到 {libs}，请确认构建是否成功）")
		return
	for jar in sorted(libs.glob("*.jar")):
		print(jar.name)


def main() -> int:
	"""入口：解析参数，按矩阵依次构建（--dry-run 仅打印命令）。"""
	parser = argparse.ArgumentParser(description="AutoTrade-Fabric 多版本构建脚本（MC 1.20 - 1.20.4）")
	parser.add_argument("--dry-run", action="store_true", help="仅打印将执行的命令，不实际构建")
	args = parser.parse_args()

	# 前置检查：必须在 Gradle 项目目录下执行
	if not (REPO_ROOT / "build.gradle").is_file():
		print(f"错误：未找到 {REPO_ROOT / 'build.gradle'}，请在 Gradle 项目目录（autotrade-fabric/）下执行本脚本", file=sys.stderr)
		return 2
	if not GRADLE.is_file():
		print(f"错误：未找到 {GRADLE}，请在 Gradle 项目目录（autotrade-fabric/）下执行本脚本", file=sys.stderr)
		return 2
	# POSIX 下确保 gradlew 带可执行位（git 检出可能丢失）
	if os.name != "nt" and not os.access(GRADLE, os.X_OK):
		os.chmod(GRADLE, 0o755)

	if args.dry_run:
		# 仅打印命令（shell 风格单行 + argv 列表），不执行任何构建
		for name, props in MATRIX:
			argv = [str(GRADLE), "--no-daemon", "build", *props]
			# shell 风格仅用于阅读：含空格的参数加双引号
			shell_line = " ".join(f'"{arg}"' if " " in arg else arg for arg in argv)
			print("")
			print(f"========== 构建 autotrade-fabric-{name} ==========")
			print(f"  shell: {shell_line}")
			print(f"  argv:  {argv}")
		return 0

	for name, props in MATRIX:
		_run_build(name, props)

	_list_artifacts()
	return 0


if __name__ == "__main__":
	sys.exit(main())
