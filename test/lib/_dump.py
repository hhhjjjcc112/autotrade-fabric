"""调试助手：打印 NBT 文件（level.dat）的可读结构。

用法（在仓库根目录运行；参数中的路径相对当前工作目录）:
  python test/lib/_dump.py <file.dat> [路径表达式] [--depth N]
  python test/lib/_dump.py --roundtrip <file.dat>
示例:
  python test/lib/_dump.py "run/saves/New World/level.dat"                       # 全量（可能很长）
  python test/lib/_dump.py "run/saves/New World/level.dat" Data.GameRules
  python test/lib/_dump.py "run/saves/New World/level.dat" Data.WorldGenSettings --depth 3
  python test/lib/_dump.py --roundtrip "run/saves/New World/level.dat"           # 读→写→重读深比较

依赖第三方库 pynbt（无内建 gzip，需用标准库 gzip 包裹）。
"""

import gzip
import os
import sys

# 控制台按 UTF-8 输出，避免中文在默认代码页下抛 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
	_reconfigure = getattr(_stream, "reconfigure", None)
	if _reconfigure is not None:
		try:
			_reconfigure(encoding="utf-8", errors="replace")
		except Exception:
			pass

# NBT 读写依赖第三方库 pynbt
try:
	import pynbt
except ImportError:
	print("错误：缺少依赖 pynbt（请执行 pip install pynbt）")
	sys.exit(2)


def load(path):
	"""读 gzip 压缩的 .dat 为 pynbt.NBTFile（根为 TAG_Compound）。"""
	with gzip.open(path, "rb") as f:
		return pynbt.NBTFile(io=f)


def save(doc: pynbt.NBTFile, path) -> None:
	"""把 pynbt.NBTFile 以 gzip 压缩写回 .dat。"""
	with gzip.open(path, "wb") as f:
		doc.save(f)


def get_path(root, expr: str):
	"""按 "a.b.c"（列表用数字下标）取子标签，返回 pynbt 标签对象。

	注意：pynbt 的 TAG_Compound / TAG_List 的 `.value` 即自身；
	标量取值用 `.value`。
	"""
	current = root
	for part in expr.split("."):
		if isinstance(current, pynbt.TAG_Compound):
			if part not in current:
				raise KeyError(f"路径不存在：{expr}（在 {part} 处失败）")
			current = current[part]
		elif isinstance(current, pynbt.TAG_List):
			current = current[int(part)]
		else:
			raise TypeError(f"路径 {expr} 在 {part} 处不可继续（{type(current).__name__}）")
	return current


def format_value(value, indent: int = 0, max_depth: int | None = None) -> str:
	"""可读格式化（带类型标记；max_depth 控制嵌套深度）。"""
	pad = "  " * indent
	if isinstance(value, pynbt.TAG_Compound):
		if not value:
			return "{}"
		if max_depth is not None and indent // 2 >= max_depth:
			return f"{{...{len(value)} 键...}}"
		lines = ["{"]
		for key, child in value.items():
			lines.append(f"{pad}  {key}: {format_value(child, indent + 1, max_depth)}")
		lines.append(pad + "}")
		return "\n".join(lines)
	if isinstance(value, pynbt.TAG_List):
		if not value:
			return "[]"
		if max_depth is not None and indent // 2 >= max_depth:
			return f"[...{len(value)} 项...]"
		inner = ", ".join(format_value(item, indent + 1, max_depth) for item in value)
		return f"[{inner}]"
	if isinstance(value, pynbt.TAG_String):
		return f'"{value.value}"'
	if isinstance(value, pynbt.TAG_Byte):
		return f"{int(value.value)}b"
	if isinstance(value, pynbt.TAG_Short):
		return f"{int(value.value)}s"
	if isinstance(value, pynbt.TAG_Int):
		return str(int(value.value))
	if isinstance(value, pynbt.TAG_Long):
		return f"{int(value.value)}L"
	if isinstance(value, pynbt.TAG_Float):
		return f"{float(value.value)}f"
	if isinstance(value, pynbt.TAG_Double):
		return f"{float(value.value)}d"
	if isinstance(value, pynbt.TAG_Byte_Array):
		return f"ByteArray{list(value.value)}"
	if isinstance(value, pynbt.TAG_Int_Array):
		return f"IntArray{list(value.value)}"
	if isinstance(value, pynbt.TAG_Long_Array):
		return f"LongArray{list(value.value)}"
	return repr(value.value)


def _deep_equal(a, b) -> bool:
	"""类型感知的递归深比较（含标签名与列表元素类型）。"""
	if type(a) is not type(b):
		return False
	if isinstance(a, pynbt.TAG_Compound):
		if a.name != b.name or list(a.keys()) != list(b.keys()):
			return False
		return all(_deep_equal(a[key], b[key]) for key in a)
	if isinstance(a, pynbt.TAG_List):
		if a.name != b.name or a.type_ is not b.type_ or len(a) != len(b):
			return False
		return all(_deep_equal(x, y) for x, y in zip(a, b))
	return a.name == b.name and a.value == b.value


def roundtrip(path: str) -> int:
	"""读入 -> 写临时文件 -> 重读 -> 深比较（文件往返一致性校验）。"""
	doc = load(path)
	tmp_path = str(path) + ".roundtrip.tmp"
	try:
		save(doc, tmp_path)
		doc2 = load(tmp_path)
	finally:
		if os.path.exists(tmp_path):
			os.remove(tmp_path)
	ok = _deep_equal(doc, doc2)
	print(f"往返: {'OK' if ok else 'FAILED'}")
	return 0 if ok else 1


def main(argv: list[str]) -> int:
	args: list[str] = []
	depth = None
	do_roundtrip = False
	i = 1
	while i < len(argv):
		if argv[i] == "--depth":
			depth = int(argv[i + 1])
			i += 2
			continue
		if argv[i] == "--roundtrip":
			do_roundtrip = True
			i += 1
			continue
		args.append(argv[i])
		i += 1
	if not args:
		print(__doc__)
		return 2
	if do_roundtrip:
		return roundtrip(args[0])
	doc = load(args[0])
	root = doc
	if len(args) > 1:
		root = get_path(root, args[1])
	print(format_value(root, max_depth=depth))
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
