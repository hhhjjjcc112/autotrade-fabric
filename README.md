# AutoTrade-fabric

[![Build](https://github.com/hhhjjjcc112/autotrade-fabric/actions/workflows/build.yml/badge.svg)](https://github.com/hhhjjjcc112/autotrade-fabric/actions/workflows/build.yml)

AutoTrade 是一个 Fabric 客户端模组，用于 AFK（挂机）自动与村民交易。

玩家移动不在本模组职责内——你需要用矿车、水流或其他方式让玩家在村民与容器之间移动；使用光照传感器控制村民每天只被交易一次，可防止价格上涨。

问题反馈与讨论：https://github.com/hhhjjjcc112/autotrade-fabric/discussions

> **本仓库是 [sebseb7/autotrade-fabric](https://github.com/sebseb7/autotrade-fabric) 的 fork**（基于上游 v0.0.11，2024-03），按个人需求维护，重点覆盖上游未支持的低版本（MC 1.20 – 1.20.2），详见下文 [与原版差异](#与原版差异)。

## 与原版差异

与上游（sebseb7 的 autotrade-fabric，fork 时 v0.0.11）相比，本 fork 的主要差异：

- **支持更低版本**：上游仅支持 MC 1.20.3 – 1.20.4；本 fork 新增多版本构建，支持 MC 1.20 – 1.20.4（含 1.20 / 1.20.1 / 1.20.2），一键脚本 `build-versions.ps1`
- **多交易对**：可同时添加并执行多个交易对（上游仅单交易对），并新增交易对编辑 / 列表 GUI
- **双物品交易（give2）**：单个交易可包含两个输入物品，容器 IO 按物品配置（ItemIO 条目）
- **设置页选项卡化**：交易对与物品 IO 配置改为设置页内的选项卡（通用 / 交易对 / IO输入 / IO输出 / 静止交易 / 虚空交易 / 快捷键），不再弹出独立配置窗口
- **交易执行器重写**：一次性会话处理、公平轮询、精确右键次数、双成本交易、库存满检测、可切换执行策略（USE 默认 / OUTPUT_SLOT）
- **库存满自动暂停**：背包满时自动暂停交易，腾出空间后继续
- **零进度会话冷却**：MOVING / VOID 模式可配置长时间无交易进展时的会话冷却
- **容器操作延迟**：新增 Container Delay 设置，可控制容器操作的最小间隔

## 功能特性

- **三种交易模式**：STATIC（静止）、MOVING（移动）、VOID（虚空无限交易），详见下文
- **自动容器补货/出货**：按物品配置（ItemIO 条目），交易间隙自动与附近的箱子/漏斗等容器交互
- **交易对（Trade Pair）**：在村民交易界面用热键添加想要执行的交易组合，在设置页「交易对」选项卡中集中管理（列表 + 行内编辑，无弹窗）
- **物品/容器定位**：用**命名物品展示框**指定买卖物品；容器坐标在设置页「IO输入 / IO输出」选项卡中配置——条目由交易对数据自动派生，同一物品只需配置一次坐标
- **中英文双语**界面（i18n）
- **Mod Menu** 支持；设置界面默认热键 **Right-Shift+T**（打不开设置时可装 Mod Menu https://modrinth.com/mod/modmenu）
- **调试 HUD**：设置页「通用」选项卡开启 Debug HUD 后，屏幕角落显示半透明调试面板（启停/模式/机器/任务状态、会话与累计成交数、容器 IO 计数、模式特有数据），位置可选四角（默认左上）；打开交易/容器界面时面板被界面盖住（预期行为）

## 支持的版本

| 项目 | 版本 |
|------|------|
| AutoTrade Mod | 0.0.17 |
| Minecraft | 1.20 – 1.20.4（Fabric） |
| 前置 | malilib、fabric-api |

## 三种交易模式

| 模式 | 用途 | 行为 |
|------|------|------|
| **STATIC** 静止 | 固定位置自动交易（如村民交易所） | 每轮扫描范围内全部村民并逐个交易，一轮结束后冷却约 5 秒再开下一轮；交易间隙处理容器 |
| **MOVING** 移动 | 移动途中自动处理沿途村民与容器 | 每 tick 决策：先处理附近的容器，再交易附近未处理过的村民；已处理的村民离开其范围再回来才可再次交易。公平调度：村民候选限交互距离（远处只记账不派发）、驻留老化防饿死（范围内长期未被服务的目标饥饿增长）、安全点让位（更饿目标插队当前任务）、饥饿阈值一次性提示 |
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
| Generic | `Trade Executor Mode` | 交易执行策略：USE（默认，直接读取 offer uses，逻辑更简）/ OUTPUT_SLOT（可选，不读取 offer uses，按快照推导剩余次数） |
| Generic | `Villager Scan Range` | 村民搜索半径（格） |
| Generic | `Trade Pairs` | 交易对列表（配置键 `tradePairs`，原生 JSON 数组；0.0.16 及更早版本为 JSON 字符串，加载自动兼容并在下次保存时迁移为数组），在「交易对」选项卡管理（列表 + 行内编辑） |
| Generic | `Item IO` | 物品容器 IO 列表（配置键 `itemIO`，原生 JSON 数组；0.0.16 及更早版本为 JSON 字符串，加载自动兼容并在下次保存时迁移为数组，条目含 `enabled` 开关），在「IO输入 / IO输出」选项卡管理，条目由交易对自动派生 |
| Generic | `Idle Scan Interval` | 背包物品计数复用间隔（tick，1-20，默认 5；容器候选每次重算、距离实时；任意任务结束立即重算背包） |
| Generic | `Container Reach` | 触发容器 IO 的最大容器距离（格，2-8，默认 4；超过约 4.5 格服务端会忽略点击） |
| Generic | `Output Move Cap` | 输出容器 IO 单次搬运的最大组数（1-9999，默认 999；999 = 全部匹配物品） |
| Generic | `Debug HUD` | 调试 HUD 开关（默认关；开启后角落显示半透明调试面板） |
| Generic | `Debug HUD Position` | 调试 HUD 所在屏幕角落（左上/右上/左下/右下，默认左上） |
| Static | `Trade Interval` | 静止模式每轮交易间隔（tick，100 = 5 秒） |
| Static | `Container IO Interval` | 静止模式两次容器操作之间的最小间隔（tick，0 = 每 tick 都检查） |
| Static | `Container IO Idle Interval` | 静止模式无容器操作需要执行时等待的 tick 数 |
| Moving | `Moving Scan Range Multiplier` | 移动模式村民扫描范围乘数（作用于扫描半径与已处理村民记录失效阈值；1.5 = 基础范围的 1.5 倍） |
| Moving | `Moving Interact Range` | 村民候选/派发最大距离（格，3-10，默认 6；服务端实体交互上限 6 格，移动中点击有滞后可放宽） |
| Moving | `Moving Starvation Aging Interval` | 驻留老化周期（tick，20-600，默认 100）：范围内候选超过该时长未被服务 → 饥饿 +1（驻留目标饥饿增长的唯一途径，3 周期后必超容器加成插队） |
| Moving | `Moving Starvation Hint Threshold` | 饥饿阈值（1-10，默认 4）：scanRange 内目标饥饿 ≥ 该值 → 一次性游戏内提示（检查移动路径）；运行中任务让位给候选内更饿目标（提示与让位共用阈值） |
| Void | `Void Teleport Timeout` | 开窗后等待村民消失（玩家传送完成）的超时（tick） |
| Void | `Void Return Type` / `Void Return Pos` | 交易后传送玩家的返回触发块类型与坐标（陷阱箱/按钮/拉杆） |
| Void | `Void Return Dimension` | 回程触发方块所在维度（registry id，如 minecraft:overworld；默认留空 = 任意维度） |
| Hotkeys | `Toggle Trading` / `Open GUI Settings` / `Add Trade Pair` | 开关交易 / 打开设置 / 添加交易对 |

## 设置界面

设置界面（默认热键 **Right-Shift+T**）包含 **8 个选项卡**：**通用 / 交易对 / IO输入 / IO输出 / 静止交易 / 移动交易 / 虚空交易 / 快捷键**。交易对与物品 IO 配置均内嵌为选项卡，不再弹出独立窗口。

### 交易对选项卡

- 交易对列表：**单行布局**，每行显示 give/give2/get 物品图标、交易序号与状态、`give×limit [+give2] → get` 标签（**备注不占行内空间**，悬浮条目行可查看完整备注；悬浮物品图标仍显示物品详情），行尾为**等宽**的启停 / 编辑 / 删除按钮（宽度上限收窄至 40px，交易标签获得更多显示空间；启停按钮文案为「开/关」）；底部「新增交易对」按钮可添加新交易对。点击行内 give/give2/get 物品图标可跳转到对应的「IO输入 / IO输出」选项卡并定位到该物品行（give/give2 → IO输入，get → IO输出）。
- 编辑交易对：点击「编辑」打开**独立编辑屏**（旧版 PairEditScreen 布局）——顶部抓取按钮组（抓取主手物品写入成本/成本 2/产物），中部紧凑的 give / give2 / get / limit / note 配置行（物品行旁显示图标，悬停看详情），底部「返回」按钮回到交易对列表
- 列表刷新（启停 / 删除 / 新增 / 编辑返回）后自动恢复纵向滚动条位置
- 空列表时显示空态提示

### IO输入 / IO输出选项卡

条目**由交易对数据自动派生**（非手工添加）：IO输入 = 全部交易对 give ∪ give2 去重，IO输出 = get 去重（**包含禁用交易对**）。每个去重后的物品显示为**可变高行**（高度随记录数增长），同一物品可配置**多条容器记录**。

每行包含：

- **头部行**：条目级 `[开]`/`[关]` 状态文本（**最左**，绿色/红色，仅展示当前启用状态）+ 物品图标（悬停显示物品名）+「开 X · 关 Y」计数 + 启用/禁用总开关（按钮文案为「开/关」，显示点击后的动作，悬停提示当前状态）；**阈值**（单位：组：输入方向该物品占用 ≤ N 组时补货，输出方向占用 ≥ N 组时清出）与**每次拿取**（单次取放组数，**仅输入方向显示**——输出方向为全量搬运，无此概念）标签+输入框**右对齐**，标签用简写（`阈`/`拿取`），悬浮显示完整说明；行尾为「+ 添加」按钮（新增一条容器记录）。启停按钮宽度有硬上限（≤44px），右侧数量块（阈/拿取/添加）按行宽比例分配，窄窗口下不再互相挤压
- **记录行**（每条容器记录一行）：**记录序号**（灰色，`1.` 起，删除后自动重排）+ 记录级 `[开]`/`[关]` 状态文本（紧随序号，与头部条目级状态错位，表达层级从属）+ **维度**输入框（registry id，如 `minecraft:overworld`；**留空 = 任意维度**，兼容旧配置；**弹性宽度**，占弹性区大头（55%），长维度 id 可完整显示）+ `x y z` 坐标输入框（**Enter / 失焦提交**；坐标为 0 0 0 占位时该条记录不生效）+「抓取容器」按钮（同时填入玩家脚下坐标与当前维度）+ 该条记录的启用/禁用（文案「开/关」）+ 删除按钮（按钮组右对齐锚定行尾）；简写标签（`维`/`抓取`）与各操作按钮悬浮显示完整说明（层级语义：条目级总开关 vs 记录级开关 AND 生效）
- 启用数为 0 时高亮提示「当前不生效」（该物品暂未被任何启用交易对使用）

同一物品的多条记录**各自独立启用/禁用**；多条启用时从多条容器执行（无优先级差别，沿用现有选择策略）。运行时**仅玩家当前所在维度的记录参与**（维度留空 = 任意维度，始终参与）——防止跨维度同坐标误触发。

> **配置说明**：`itemIO` JSON 条目含 `enabled` 字段（条目级总开关）+ `locations` 数组（每条记录含 `dimension`/`x`/`y`/`z`/`enabled`）。**旧格式**（单坐标、无维度）加载时自动迁移——迁移为单条记录、维度留空（= 任意维度）；`locations` 为空列表 = 0 条记录。条目启用开关与运行时联动——关闭后该物品不再触发容器 IO；运行时输入/输出物品集仍派生自「已启用」的交易对。此外，`itemIO`/`tradePairs` 配置值现为原生 JSON 数组（0.0.16 及更早版本为 JSON 字符串），旧文件加载自动兼容、下次保存时迁移为数组。

## 构建

需要 JDK 17。

```powershell
# 构建默认版本（MC 1.20.4）
.\gradlew build

# 运行开发客户端
.\gradlew runClient

# 代码格式化检查 / 自动修复
.\gradlew spotlessCheck
.\gradlew spotlessApply
```

> 手动编译无需额外参数。若通过 opencode 等 agent 执行 Gradle 命令，请加上 `--no-daemon`（否则守护进程可能导致命令无法退出、卡死）。

### 多版本构建（MC 1.20 – 1.20.4）

一键脚本（推荐，依次构建 3 个版本）：

```powershell
.\build-versions.ps1        # Windows PowerShell
```

```bash
./build-versions.sh         # bash 环境（Git Bash / WSL / macOS / Linux）
```

手工执行（示例：MC 1.20.1）：

```powershell
.\gradlew build -Pminecraft_version=1.20.1 -Pmappings_version=1.20.1+build.10 -Pminecraft_version_out=1.20.1 -Pmalilib_version=0.16.1 -Pfabric_api_version=0.92.6+1.20.1 -Pfabric_api_version_min=0.83.0 -Pmod_menu_version=7.2.2 "-Pminecraft_version_range=>=1.20 <1.20.2"
```

代码对 1.20 – 1.20.4 零改动；各版本参数矩阵见 `build-versions.ps1` 与 `build-versions.sh`（两处须同步维护）。

## 持续集成（GitHub Actions）

仓库内置两个 CI 工作流（`.github/workflows/`）：

### Build（自动构建）

- **触发**：push / PR 到 `master` 分支
- **行为**：并行构建 3 个 MC 版本（1.20.1 / 1.20.2 / 1.20.4，参数与 `build-versions.ps1` 一致），将 jar 与 md5 校验文件上传为 Actions artifacts，可在工作流运行页面的 Summary 中下载
- 构建失败会在 PR 上直接显示状态标记（`spotlessCheck` 已接入 `build`，格式问题会导致失败）

### Release (Manual)（手动发布）

无需打 tag、无需命令行，在网页上点击即可发布：

1. 仓库页面 → **Actions** → 左侧 **Release (Manual)** → 右侧 **Run workflow**
2. 选择分支（默认 master）→ 点击绿色 **Run workflow** 按钮
3. 等待 3 个版本构建完成（并行，约 10-15 分钟）
4. 工作流自动创建 **Draft Release**（草稿）：版本号自动读取 `gradle.properties` 的 `mod_version`，release 命名为 `v<版本号>`，附件为 3 个 jar + md5 汇总文件
5. 前往 **Releases** 页面 → 编辑草稿补充发布说明 → 点击 **Publish release** 正式发布（GitHub 会随发布自动创建同名 tag，无需手动打 tag / push tag）

> 若重复发布同一版本号，工作流会先删除旧的同名 draft 再重建，无需手动清理。

## 已知问题

- **ItemScroller 交易收藏兼容**：已支持与 ItemScroller 的收藏交易同时使用——交易执行按 ItemScroller 真实交易顺序索引，收藏重排不再影响交易；ItemScroller 为可选依赖（无需安装也可使用本 mod）

## 许可

0BSD，见 [LICENSE.md](LICENSE.md)。

