r"""共享的 [verdict] 解析器。

职责：从 run/logs/latest.log 中提取本次测试运行最后写出的判定结果
（`[verdict] PASS` / `[verdict] FAIL`），供 test/static.py、test/void.py、
test/smoke.py 三个入口脚本统一使用。

设计要点：
	- 只认真正的日志行标记（(?<!\[)\[verdict\] (PASS|FAIL)）：MC 日志行带时间/线程前缀，
	  不能行首锚定；Minescript 的 debug 回显写作 `[[verdict] …`，用负向后顾将其排除；
	- 取**最后一条**匹配：一次运行内可能多次输出（如异常前的旧行），最后一条才是本轮结论；
	- 可选 since（运行起始时间戳）——若日志文件的 mtime 早于 since，说明是上一轮遗留的
	  verdict，返回 None，避免把陈旧结果当成本轮结果（stale-run guard）。
"""

from __future__ import annotations

import re
from pathlib import Path

# 匹配真正的日志行 [verdict] PASS/FAIL；负向后顾排除 Minescript debug 回显的 [[verdict]
VERDICT_RE = re.compile(r"(?<!\[)\[verdict\] (PASS|FAIL)")


def parse_verdict(log_path: Path, since: float | None = None) -> str | None:
	"""返回日志中最后一条 [verdict] PASS/FAIL；无匹配或判定为陈旧运行则返回 None。

	参数：
		log_path: 日志文件路径（通常为 run/logs/latest.log）。
		since:    本轮运行的起始时间戳（time.time()）。给定且日志 mtime 早于 since 时，
		          视为上一轮遗留 → 返回 None（陈旧运行保护）。
	"""
	path = Path(log_path)
	if not path.is_file():
		return None
	# 陈旧运行保护：文件最后修改时间早于本轮起始 → 不是本轮产物
	if since is not None and path.stat().st_mtime < since:
		return None
	text = path.read_text(encoding="utf-8", errors="replace")
	matches = VERDICT_RE.findall(text)
	return matches[-1] if matches else None
