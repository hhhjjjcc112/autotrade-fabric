"""空操作脚本（no-op）。

用途：Minescript 4.0-beta2 的 autorun 处理在缺少 `autorun[*]` 通配键时会抛
NullPointerException（`wildcardCommands is null`，Minescript.java:3764）导致客户端崩溃。
本脚本挂在 `autorun[*]` 上保证该键存在，且不做任何事（只有模块文档字符串，无副作用）。
"""
