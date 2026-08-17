# AutoTrade-fabric

[![Build](https://github.com/sebseb7/autotrade-fabric/actions/workflows/build.yml/badge.svg)](https://github.com/sebseb7/autotrade-fabric/actions/workflows/build.yml)

AutoTrade 是一个 Fabric 客户端模组，用于 AFK（挂机）自动与村民交易。

玩家移动不在本模组职责内——你需要用矿车、水流或其他方式让玩家在村民与容器之间移动；使用光照传感器控制村民每天只被交易一次，可防止价格上涨。

问题反馈与讨论：https://github.com/sebseb7/autotrade-fabric/discussions

## 功能特性

- **三种交易模式**：STATIC（静止）、MOVING（移动）、VOID（虚空无限交易），详见下文
- **自动容器补货/出货**：交易间隙自动与附近的箱子/漏斗等容器交互
- **交易对（Trade Pair）**：在村民交易界面用热键添加想要执行的交易组合，GUI 集中管理
- **物品/容器定位**：用**命名物品展示框**指定买卖物品，用**彩色玻璃**指定输入/输出容器（v0.0.10+）
- **中英文双语**界面（i18n）
- **Mod Menu** 支持；设置界面默认热键 **Right-Shift+T**（打不开设置时可装 Mod Menu https://modrinth.com/mod/modmenu）

## 支持的版本

| 项目 | 版本 |
|------|------|
| Minecraft | 1.20 – 1.20.4（Fabric） |
| 前置 | malilib、fabric-api |

## 三种交易模式

| 模式 | 用途 | 行为 |
|------|------|------|
| **STATIC** 静止 | 固定位置自动交易（如村民交易所） | 每轮扫描范围内全部村民并逐个交易，一轮结束后冷却约 5 秒再开下一轮；交易间隙处理容器 |
| **MOVING** 移动 | 移动途中自动处理沿途村民与容器 | 每 tick 决策：先处理附近的容器，再交易附近未处理过的村民；已处理的村民离开其范围再回来才可再次交易 |
| **VOID** 虚空 | **无限交易**，交易次数永不耗尽 | 村民到达后打开交易窗口，**玩家**被传送离开（村民留在原位），待村民因超出视距从服务端卸载后立即交易——交易次数不会持久化，永不耗尽；交易后触发传送点（陷阱箱/按钮/拉杆）将玩家传回 |

> 详细流程与机制说明见 `docs/TRADE_MODES.md`。

## 快速上手

1. 安装 Fabric Loader 与前置 mod（malilib、fabric-api），把本 mod 放入 `mods/` 目录
2. 进入游戏，按 **Right-Shift+T** 打开设置界面
3. 选择交易模式（`Trade Mode`），设置扫描范围等参数
4. 在设置中开启 `Enabled`（或绑定 `Toggle Trading` 热键快速开关）
5. （可选）在村民交易界面，把鼠标悬停在想要执行的交易上，按 `Add Trade Pair` 热键添加交易对

### 常用设置项

| 分组 | 设置 | 说明 |
|------|------|------|
| Generic | `Enabled` | 总开关 |
| Generic | `Trade Mode` | STATIC / MOVING / VOID 三种模式 |
| Generic | `Villager Scan Range` | 村民搜索半径（格） |
| Generic | `Container IO Interval` | 容器操作的最小间隔（tick） |
| Generic | `Trade Pairs` | 交易对列表（JSON），建议用游戏内 GUI 管理 |
| Static | `Trade Interval` | 静止模式每轮交易间隔（tick，100 = 5 秒） |
| Void | `Void Teleport Timeout` | 开窗后等待村民消失（玩家传送完成）的超时（tick） |
| Void | `Void Return Type` / `Void Return Pos` | 交易后传送玩家的返回触发块类型与坐标（陷阱箱/按钮/拉杆） |
| Hotkeys | `Toggle Trading` / `Open GUI Settings` / `Add Trade Pair` | 开关交易 / 打开设置 / 添加交易对 |

## 构建

需要 JDK 17。所有 Gradle 命令请加 `--no-daemon`（否则守护进程收不到关闭信号）。

```powershell
# 构建默认版本（MC 1.20.4）
.\gradlew --no-daemon build

# 运行开发客户端
.\gradlew --no-daemon runClient

# 代码格式化检查 / 自动修复
.\gradlew --no-daemon spotlessCheck
.\gradlew --no-daemon spotlessApply
```

### 多版本构建（MC 1.20 – 1.20.4）

一键脚本（推荐，依次构建 3 个版本）：

```powershell
.\build-versions.ps1
```

手工执行（示例：MC 1.20.1）：

```powershell
.\gradlew --no-daemon build -Pminecraft_version=1.20.1 -Pmappings_version=1.20.1+build.10 -Pminecraft_version_out=1.20.1 -Pmalilib_version=0.16.1 -Pfabric_api_version=0.92.6+1.20.1 -Pfabric_api_version_min=0.83.0 -Pmod_menu_version=7.2.2 "-Pminecraft_version_range=>=1.20 <1.20.2"
```

代码对 1.20 – 1.20.4 零改动；各版本参数矩阵见 `build-versions.ps1`。

## 参考装置

- 输入用潜影盒卸载器、输出用潜影盒装载器，村民与容器之间用水流循环运载玩家
- 2b2t 上水流运载不可用时，可用矿车轨道，或此 EssentialClient 脚本：https://gist.github.com/sebseb7/6477190dd531d05991741ccb031c0684

演示视频：https://youtu.be/ZbxkZqb-VsU

虚空交易的示例 litematic 与演示存档（v0.0.10）：

- https://github.com/sebseb7/autotrade-fabric/releases/download/v0.0.10/void_trader_outer_island.litematic
- https://github.com/sebseb7/autotrade-fabric/releases/download/v0.0.10/void_trader_central_island.litematic
- https://github.com/sebseb7/autotrade-fabric/releases/download/v0.0.10/AutoTradeDemoWDL.zip

## 已知问题

- ItemScroller 的交易收藏功能会破坏交易，请勿与 ItemScroller 的交易收藏同时使用

## 许可

0BSD，见 [LICENSE.md](LICENSE.md)。

