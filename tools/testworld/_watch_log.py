"""等待并过滤打印 run/logs/latest.log 中的测试关键行（供 agent 轮询，也便于用户自查）。

用法: python tools/testworld/_watch_log.py [等待秒数] [--all]
  - 默认等待到出现 "[verdict]"（或超时）后，打印过滤后的关键行（最后 150 行）
  - --all 时不等待，直接打印当前过滤结果
"""

import pathlib
import sys
import time

LOG = pathlib.Path(__file__).resolve().parents[2] / "run" / "logs" / "latest.log"

MARKERS = (
	"[rig]",
	"[status]",
	"[chat]",
	"[verdict]",
	"[Test]",
	"[StaticMode]",
	"[AutoTrade]",
	"[ContainerIO]",
	"[ModeMachine]",
	"Minescript",
	"Exception",
	"ERROR",
	"quickPlay",
	"Loading Minecraft",
	"Loaded 1 advancements",
)

WAIT_MARKER = "[verdict]"


def _read() -> str:
	"""读取日志（不存在返回空串）。"""
	if not LOG.exists():
		return ""
	return LOG.read_text(encoding="utf-8", errors="replace")


def main(argv: list[str]) -> int:
	args = [a for a in argv[1:] if not a.startswith("--")]
	wait_seconds = float(args[0]) if args else 120.0
	show_all = "--all" in argv
	deadline = time.time() + wait_seconds
	while not show_all and time.time() < deadline:
		if WAIT_MARKER in _read():
			break
		time.sleep(5)
	text = _read()
	lines = [line for line in text.splitlines() if any(m in line for m in MARKERS)]
	print(f"---- latest.log 关键行（共 {len(lines)} 行，显示最后 150 行）----")
	print("\n".join(lines[-150:]))
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
