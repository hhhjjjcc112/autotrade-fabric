#!/usr/bin/env bash
# AutoTrade-Fabric 多版本构建脚本（MC 1.20 - 1.20.4）— build-versions.ps1 的 bash 版本
#
# 按 malilib 官方构件分组产出 3 个 jar（代码零改动，仅切换依赖版本）：
#   autotrade-fabric-1.20.1-<ver>.jar  覆盖 MC 1.20 + 1.20.1
#   autotrade-fabric-1.20.2-<ver>.jar  覆盖 MC 1.20.2
#   autotrade-fabric-1.20.4-<ver>.jar  覆盖 MC 1.20.3 + 1.20.4
#
# 用法：在 autotrade-fabric/（Gradle 项目目录）下执行  ./build-versions.sh
# 环境：需要 bash（Windows 下用 Git Bash / WSL；macOS / Linux 直接可用）
# 注意：gradlew 必须携带 --no-daemon（见 AGENTS.md 核心规则 #2）
set -euo pipefail

# 版本矩阵：每行 = 构建名称 | gradle 属性参数（| 为分隔符；含空格的参数整体作为单个参数传递）
# 与 build-versions.ps1 保持一致，修改版本参数时两处必须同步
matrix=(
    "1.20.1|-Pminecraft_version=1.20.1|-Pmappings_version=1.20.1+build.10|-Pminecraft_version_out=1.20.1|-Pmalilib_version=0.16.1|-Pfabric_api_version=0.92.6+1.20.1|-Pfabric_api_version_min=0.83.0|-Pmod_menu_version=7.2.2|-Pitemscroller_version=0.20.0|-Pminecraft_version_range=>=1.20 <1.20.2"
    "1.20.2|-Pminecraft_version=1.20.2|-Pmappings_version=1.20.2+build.4|-Pminecraft_version_out=1.20.2|-Pmalilib_version=0.17.0|-Pfabric_api_version=0.91.6+1.20.2|-Pfabric_api_version_min=0.86.1|-Pmod_menu_version=8.0.1|-Pitemscroller_version=0.21.0|-Pminecraft_version_range=>=1.20.2 <1.20.3"
    "1.20.4|-Pminecraft_version=1.20.4|-Pmappings_version=1.20.4+build.3|-Pminecraft_version_out=1.20.4|-Pmalilib_version=0.18.0|-Pfabric_api_version=0.92.1+1.20.4|-Pfabric_api_version_min=0.91.1|-Pmod_menu_version=9.0.0|-Pitemscroller_version=0.22.0|-Pminecraft_version_range=>=1.20.3 <1.20.5"
)

# 前置检查：必须在 Gradle 项目目录下执行（与 build-versions.ps1 用法一致）
if [ ! -f ./gradlew ]; then
    echo "错误：未找到 ./gradlew，请在 Gradle 项目目录（autotrade-fabric/）下执行本脚本" >&2
    exit 1
fi

for entry in "${matrix[@]}"; do
    # 按 | 拆分矩阵行：第一个字段为构建名称，其余为 gradle 属性参数
    IFS='|' read -r -a parts <<< "$entry"
    name="${parts[0]}"
    props=("${parts[@]:1}")

    echo ""
    echo "========== 构建 autotrade-fabric-${name} =========="
    if ./gradlew --no-daemon build "${props[@]}"; then
        echo "========== 构建 ${name} 成功 =========="
    else
        echo "构建 ${name} 失败（exit=$?），中止后续构建" >&2
        exit 1
    fi
done

echo ""
echo "全部构建完成，产物位于 build/libs/ 目录："
ls -1 build/libs/*.jar