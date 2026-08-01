# AutoTrade Mod 源码指南

**包路径:** `com.github.sebseb7.autotrade`
**文件数:** 37 个 Java 源文件
**入口:** `AutoTrade.java` (Fabric ModInitializer)

## 包结构

```
src/main/java/com/github/sebseb7/autotrade/
├── AutoTrade.java              ← Fabric 入口（implements ModInitializer）
├── InitHandler.java            ← malilib 初始化注册
├── Reference.java              ← MOD_ID / MOD_NAME 常量
├── compat/modmenu/
│   └── ModMenuImpl.java        ← ModMenu 配置界面入口
├── config/
│   ├── Configs.java            ← 通用配置 + 交易对 JSON（IConfigHandler）
│   ├── Hotkeys.java            ← 3 个快捷键定义
│   ├── TradeMode.java          ← 交易模式枚举（STATIC/MOVING/VOID）
│   ├── ConfigItem.java         ← 自定义 IConfigValue（物品编码）
│   ├── ConfigOptionListValue.java
│   └── TradePairList.java      ← 从旧 util 包移入（Gson 序列化交易对）
├── handler/                    ← 原 event 包（malilib 处理器）
│   ├── InputHandler.java       ← IKeybindProvider（注册快捷键）
│   ├── KeybindCallbacks.java   ← IHotkeyCallback（快捷键回调）
│   └── AutoTradeClientTick.java ← IClientTickHandler（每 tick 分派到模式机器）
├── trade/                      ← 交易引擎核心
│   ├── Operation.java          ← 抽象基类（tick 驱动操作）
│   ├── TradePair.java          ← 交易对数据模型（give/get/limit/container坐标）
│   ├── TradeSessionBase.java   ← 核心 6 状态 FSM（SCANNING→TRADING→COMPLETED）
│   ├── MerchantTradeExecutor.java ← 交易执行（匹配/换货/快速移动）
│   ├── TradeSession.java       ← 交易会话接口
│   ├── TradingModeMachine.java ← 模式机器接口
│   ├── SessionHooks.java       ← 策略钩子接口
│   ├── ContainerIOOperation.java ← 容器开关转运状态机
│   ├── VillagerInteractHelper.java ← 射线追踪看向村民（yaw/pitch 计算）
│   ├── ContainerIOHelper.java  ← 判断容器 IO 时机（阈值 vs 库存计数）
│   ├── staticmode/             ← STATIC 模式（逐村交易 + 容器IO + 冷却）
│   │   ├── StaticTradeSession.java
│   │   └── StaticModeMachine.java
│   ├── movingmode/             ← MOVING 模式（优先容器IO ≤4格 + 最近村民）
│   │   ├── MovingTradeSession.java
│   │   └── MovingModeMachine.java
│   └── voidmode/               ← VOID 模式（容器IO + 任意村民 + 可配延迟）
│       ├── VoidTradeSession.java
│       └── VoidModeMachine.java
├── gui/
│   ├── GuiConfigs.java         ← malilib 配置界面（通用/快捷键页 + 管理交易对按钮）
│   ├── PairListScreen.java     ← 交易对列表管理器
│   ├── PairEditScreen.java     ← 单对编辑器（物品获取/坐标/阈值）
│   ├── MerchantScreenPairInjector.java ← 在交易界面按快捷键添加交易对
│   └── widget/
│       └── ItemIconWidget.java ← ItemStack 图标渲染
├── mixin/
│   ├── MerchantScreenHotkeyMixin.java ← @Mixin(Screen) 注入 keyPressed
│   └── accessor/
│       └── MerchantScreenAccessor.java ← indexStartOffset 字段访问器
└── util/
    └── ItemStringHelper.java   ← ItemStack ↔ JSON 字符串编码
```

## 何处查找

| 任务 | 位置 | 备注 |
|------|------|------|
| 添加快捷键 | `config/Hotkeys.java` | 定义 `ConfigHotkey`，在 `InputHandler` 注册 |
| 修改配置选项 | `config/Configs.java` | `Generic.OPTIONS` 列表 + `TRADE_PAIRS` JSON |
| 修改交易流程 | `trade/TradeSessionBase.java` | 6 状态 FSM |
| 添加新交易模式 | `trade/SessionHooks.java` + `trade/{newmode}/` | 实现 SessionHooks + 创建新的 ModeMachine + Session，在 `AutoTradeClientTick` 注册 |
| 修改容器 IO | `trade/ContainerIOOperation.java` | 状态机：OPENING→TRANSFERRING→CLOSING→DONE |
| 修改 GUI 配置界面 | `gui/GuiConfigs.java` | malilib ConfigScreen |
| 修改交易对管理 | `gui/PairListScreen.java` + `config/TradePairList.java` | 列表 UI + JSON 持久化 |
| 修改交易界面注入 | `mixin/MerchantScreenHotkeyMixin.java` + `gui/MerchantScreenPairInjector.java` | 快捷键添加交易对 |
| 注释中的国际化 | `src/main/resources/assets/autotrade/lang/` | en_us.json / zh_cn.json |

## 代码地图

### 入口层
| 类 | 角色 |
|----|------|
| `AutoTrade` | Fabric ModInitializer，注册 InitHandler，持有 sold/bought 计数器 |
| `InitHandler` | malilib IInitializationHandler，注册配置/快捷键/Tick处理器 |
| `Reference` | MOD_ID="autotrade" 常量 |

### 配置层
| 类 | 角色 |
|----|------|
| `Configs` | IConfigHandler：11 项通用选项 + TRADE_PAIRS JSON；SafeConfig* 包装阻止运行中修改 |
| `Hotkeys` | 3 个快捷键：TOGGLE_KEY / OPEN_GUI_SETTINGS / ADD_TRADE_PAIR_KEY |
| `TradeMode` | STATIC / MOVING / VOID 枚举 |

### 交易引擎（核心）
| 类 | 角色 |
|----|------|
| `AutoTradeClientTick` | 持有 `EnumMap<TradeMode, TradingModeMachine>`，每 tick 分派 |
| `TradeSessionBase` | 6 状态 FSM，管理 `MerchantTradeExecutor` 和超时 |
| `TradingModeMachine` | 模式机器接口（`tick()` / `reset()`） |
| `SessionHooks` | 策略钩子：找下个村民 / 冷却 / 扫描范围（多态差异化） |
| `StaticModeMachine` | STATIC 模式：一轮交易所有村民 + 冷却 + 容器 IO |
| `MovingModeMachine` | MOVING 模式：优先容器 IO + 最近村民 |
| `VoidModeMachine` | VOID 模式：容器 IO + 任意村民 + 可配延迟 |

### 辅助层
| 类 | 角色 |
|----|------|
| `MerchantTradeExecutor` | 匹配 TradeOffer → TradePair，执行交易（数据包+点击） |
| `ContainerIOHelper` | 判断容器 IO 时机（阈值 vs 库存计数），距离计算 |
| `VillagerInteractHelper` | 射线追踪看向村民（yaw/pitch 计算） |
| `ContainerIOOperation` | 容器开关转运状态机 |

### GUI 层
| 类 | 角色 |
|----|------|
| `GuiConfigs` | malilib 配置界面入口 |
| `PairListScreen` | 交易对列表（启用/编辑/删除按钮 + 物品图标 Tooltip） |
| `PairEditScreen` | 单对编辑器（用手上物品抓取、容器坐标抓取、自动保存） |

### Mixin 层
| 类 | 角色 |
|----|------|
| `MerchantScreenHotkeyMixin` | @Mixin(Screen) → keyPressed 注入 → 检测交易行 + 快捷键添加 |
| `MerchantScreenAccessor` | @Accessor("indexStartOffset") 暴露 MerchantScreen 私有字段 |

## 设计模式

- **策略模式**：3 种交易模式共享一个 `TradeSessionBase` FSM，差异通过 `SessionHooks` 注入（`StaticHooks` / `MovingHooks` / `VoidHooks`）
- **操作状态机**：`Operation` 基类 → `TradeSessionBase`（交易流程）、`ContainerIOOperation`（容器转运）
- **配置安全包装**：`SafeConfigInteger`/`SafeConfigOptionListValue` 阻止 Mod 启用时修改关键配置

## 注意事项

- MC/malilib 参考源码在父目录 `../minecraft-merged-.../` 和 `../malilib-fabric-.../`，只读查阅
- 无单元测试 — 测试通过 `runClient` 在游戏中手动进行
- `fabric.mod.json` 中 `"environment": "client"` — 客户端专用 Mod
- 多版本构建通过 `-P` 参数覆盖 gradle.properties 中的版本号
