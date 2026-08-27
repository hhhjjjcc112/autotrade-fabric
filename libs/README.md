# libs/ — vendored ItemScroller jar（编译期依赖）

本目录存放 ItemScroller 的 vendored jar，用途为 **compileOnly 编译依赖**（见 `build.gradle` 的
`modCompileOnly files(...)` 声明）：仅在编译期引用 `fi.dy.masa.itemscroller.villager` API，
**不会**随 autotrade 发布产物分发，也**不**引入运行时依赖——运行时类由用户自行安装的
ItemScroller mod 提供（mod 通过 `FabricLoader.isModLoaded("itemscroller")` 检测）。

## 文件清单

| 文件 | 对应 MC | malilib | 下载日期 |
|------|--------|---------|---------|
| `itemscroller-fabric-1.20.1-0.20.0.jar` | 1.20 / 1.20.1 | 0.16.x | 2026-08-27 |
| `itemscroller-fabric-1.20.2-0.21.0.jar` | 1.20.2 | 0.17.x | 2026-08-27 |
| `itemscroller-fabric-1.20.4-0.22.0.jar` | 1.20.3 / 1.20.4 | ≥0.18.0 | 2026-08-27 |

## 来源与许可

- **来源**：Modrinth 项目 [ItemScroller](https://modrinth.com/mod/itemscroller)（项目 ID `JygyCSA4`），
  官方 CDN 直链下载。
- **许可**：ItemScroller 为 **MIT License**（作者 masa）。
- **校验**：下载后已用 `jar tf` 抽查确认各 jar 含
  `fi/dy/masa/itemscroller/villager/IMerchantScreenHandler.class`（兼容层仅使用该接口）。

## 维护说明

- 三个 jar 的 `fi.dy.masa.itemscroller.villager` 包 API 经字节码级对比一致（diff 为空），
  本 mod 只依赖其中 `IMerchantScreenHandler` 一个接口。
- 若需升级 ItemScroller 版本：替换对应 jar 并同步更新 `gradle.properties` 的
  `itemscroller_version` 与 `build-versions.ps1` / `build-versions.sh` /
  `.github/workflows/build.yml` / `.github/workflows/release.yml` 四处矩阵（AGENTS.md 双维护规则）。
- 缺失本目录 jar 会导致编译失败（`modCompileOnly files(...)` 路径解析不到文件）。
