"""AutoTradeSmokeTest 冒烟观测脚本（Minescript）。

用途：进入 AutoTradeSmokeTest 冒烟世界后执行一次最小冒烟检查：
  1. 轮询等待世界加载（player_position() 可读；超时 60s）；
  2. 校验 mod 已加载——反射 com.github.sebseb7.autotrade.Reference 并读取静态字段
     MOD_ID / MOD_NAME（能取到值即证明 mod 类可用、反射链路正常）；
  3. 输出 `[verdict] PASS/FAIL | <详情>`；
  4. 除非指定 noquit，自动关闭 Minecraft 客户端（默认开启，便于无人值守冒烟）。

输出：echo（本地聊天，可见）+ log（logs/latest.log，供 agent/用户事后查看）。

用法：
  - 自动：run/minescript/config.txt 中 `autorun[AutoTradeSmokeTest]=smoke_test 30`
  - 手动：游戏内聊天框输入 `\\smoke_test 30`（观测窗口秒数，可省略，默认 30）
  - 保留客户端：`\\smoke_test 30 noquit`

注意：
  - minescript 库由 Minescript mod 运行时注入 sys.path；IDE 静态检查报未知导入属预期现象。
  - java_* 反射的调用约定：方法要先经 `java_member(cls, "name")` 取句柄，再 `java_call_method(target, handle)`。
"""

import sys
import time

from minescript import (
	echo,
	java_access_field,
	java_call_method,
	java_class,
	java_member,
	java_to_string,
	log,
	player_position,
)

DEFAULT_SECONDS = 30.0  # 默认观测窗口（秒）；冒烟检查立即完成，此值仅作提示/上限
WAIT_WORLD_TIMEOUT = 60.0  # 等待世界加载上限（秒）
REFERENCE_CLASS = "com.github.sebseb7.autotrade.Reference"


def _wait_for_world() -> bool:
	"""轮询等待世界加载（player_position() 可读）；超时返回 False。"""
	deadline = time.time() + WAIT_WORLD_TIMEOUT
	while time.time() < deadline:
		try:
			if player_position() is not None:
				return True
		except Exception:  # noqa: BLE001
			pass
		time.sleep(2.0)
	return False


def _check_mod() -> tuple[bool, str]:
	"""反射校验 mod 已加载；返回 (是否通过, 详情/原因)。"""
	try:
		cls = java_class(REFERENCE_CLASS)
	except Exception as exc:  # noqa: BLE001
		return False, f"java_class({REFERENCE_CLASS}) 失败: {type(exc).__name__}: {exc}"
	try:
		mod_id = str(java_to_string(java_access_field(cls, java_member(cls, "MOD_ID"))))
		mod_name = str(java_to_string(java_access_field(cls, java_member(cls, "MOD_NAME"))))
	except Exception as exc:  # noqa: BLE001
		return False, f"读取 Reference 静态字段失败: {type(exc).__name__}: {exc}"
	if not mod_id or mod_id == "None":
		return False, f"Reference.MOD_ID 读取为空（{mod_id!r}）"
	return True, f"mod_id={mod_id!r} mod_name={mod_name!r}"


def _quit_client(noquit: bool) -> None:
	"""观测结束后自动关闭客户端（noquit=True 时跳过）；失败仅提示，绝不抛异常。"""
	if noquit:
		log("[quit] 已指定 noquit（保留客户端，不自动关闭）")
		return
	log("[quit] 2 秒后自动关闭 Minecraft 客户端…")
	try:
		time.sleep(2.0)
		cls = java_class("net.minecraft.client.MinecraftClient")
		inst = java_call_method(cls, java_member(cls, "getInstance"))
		java_call_method(inst, java_member(cls, "scheduleStop"))
		log("[quit] 已调用 MinecraftClient.scheduleStop()")
	except Exception as exc:  # noqa: BLE001
		log(f"[quit] 自动关闭失败: {exc} → 请手动关闭游戏窗口")


def main(seconds=DEFAULT_SECONDS, noquit=False):
	"""主流程：等待世界 → 校验 mod 加载 → 输出判定 →（默认）自动关闭客户端。"""
	log("=== AutoTradeSmokeTest 冒烟观测开始（Minescript autorun）===")
	echo(f"[Test] 冒烟观测开始，窗口 {int(seconds)}s；结果写入 logs/latest.log")

	if not _wait_for_world():
		verdict_ok = False
		reason = "等待世界加载超时（player_position() 在 60s 内不可用）"
	else:
		verdict_ok, reason = _check_mod()

	log(f"[verdict] {'PASS' if verdict_ok else 'FAIL'} | {reason}")
	echo(f"[Test] 冒烟观测结束：{'PASS' if verdict_ok else 'FAIL'}（详见 logs/latest.log）")
	log("=== AutoTradeSmokeTest 冒烟观测结束 ===")

	_quit_client(noquit)


if __name__ == "__main__":
	secs = DEFAULT_SECONDS
	noquit = "noquit" in sys.argv[1:]
	if len(sys.argv) > 1:
		try:
			secs = float(sys.argv[1])
		except ValueError:
			pass
	try:
		main(secs, noquit)
	except Exception as exc:  # noqa: BLE001
		import traceback

		log(f"[fatal] 脚本异常: {exc}")
		log(traceback.format_exc())
		echo(f"[Test] 脚本异常，见 logs/latest.log：{exc}")
