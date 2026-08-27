# AutoTrade-Fabric 多版本构建脚本（MC 1.20 - 1.20.4）
#
# 按 malilib 官方构件分组产出 3 个 jar（代码零改动，仅切换依赖版本）：
#   autotrade-fabric-1.20.1-<ver>.jar  覆盖 MC 1.20 + 1.20.1
#   autotrade-fabric-1.20.2-<ver>.jar  覆盖 MC 1.20.2
#   autotrade-fabric-1.20.4-<ver>.jar  覆盖 MC 1.20.3 + 1.20.4
#
# 用法：在 autotrade-fabric/（Gradle 项目目录）下执行  .\build-versions.ps1
# 注意：gradlew 必须携带 --no-daemon（见 AGENTS.md 核心规则 #2）
$ErrorActionPreference = "Stop"

# 版本矩阵：每项 = 构建名称 + gradle 属性参数（数组元素含空格时由 PowerShell 自动加引号）
$builds = @(
    @{
        name  = "1.20.1"
        props = @(
            "-Pminecraft_version=1.20.1",
            "-Pmappings_version=1.20.1+build.10",
            "-Pminecraft_version_out=1.20.1",
            "-Pmalilib_version=0.16.1",
            "-Pfabric_api_version=0.92.6+1.20.1",
            "-Pfabric_api_version_min=0.83.0",
            "-Pmod_menu_version=7.2.2",
            "-Pitemscroller_version=0.20.0",
            "-Pminecraft_version_range=>=1.20 <1.20.2"
        )
    },
    @{
        name  = "1.20.2"
        props = @(
            "-Pminecraft_version=1.20.2",
            "-Pmappings_version=1.20.2+build.4",
            "-Pminecraft_version_out=1.20.2",
            "-Pmalilib_version=0.17.0",
            "-Pfabric_api_version=0.91.6+1.20.2",
            "-Pfabric_api_version_min=0.86.1",
            "-Pmod_menu_version=8.0.1",
            "-Pitemscroller_version=0.21.0",
            "-Pminecraft_version_range=>=1.20.2 <1.20.3"
        )
    },
    @{
        name  = "1.20.4"
        props = @(
            "-Pminecraft_version=1.20.4",
            "-Pmappings_version=1.20.4+build.3",
            "-Pminecraft_version_out=1.20.4",
            "-Pmalilib_version=0.18.0",
            "-Pfabric_api_version=0.92.1+1.20.4",
            "-Pfabric_api_version_min=0.91.1",
            "-Pmod_menu_version=9.0.0",
            "-Pitemscroller_version=0.22.0",
            "-Pminecraft_version_range=>=1.20.3 <1.20.5"
        )
    }
)

foreach ($b in $builds) {
    Write-Host ""
    Write-Host "========== 构建 autotrade-fabric-$($b.name) =========="
    & .\gradlew.bat --no-daemon build @($b.props)
    if ($LASTEXITCODE -ne 0) {
        Write-Error "构建 $($b.name) 失败（exit=$LASTEXITCODE），中止后续构建"
        exit 1
    }
    Write-Host "========== 构建 $($b.name) 成功 =========="
}

Write-Host ""
Write-Host "全部构建完成，产物位于 build/libs/ 目录："
Get-ChildItem .\build\libs\*.jar | Select-Object -ExpandProperty Name
