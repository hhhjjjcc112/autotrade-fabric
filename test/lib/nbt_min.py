"""无依赖的最小 NBT 读写库（仅供 AutoTrade 测试世界工具使用）。

- 每个标签都是携带显式类型信息的包装对象：Byte/Short/Int/Long/Float/Double/String/
  ByteArray/List/Compound/IntArray/LongArray；读写往返保留标签类型、列表元素类型与字段顺序
  （level.dat 补丁的基础）。
- 只处理 gzip 压缩的 .dat（level.dat / level.dat_old）。
- 字符串按 UTF-8 处理（测试世界数据为 ASCII/常规字符，未覆盖 Java modified-UTF8 的边角）。
"""

from __future__ import annotations

import gzip
import io as _io
import struct
import sys

# ---- 标签类型 ID（NBT 规范） ----
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Byte(int):
	tag_id = TAG_BYTE


class Short(int):
	tag_id = TAG_SHORT


class Int(int):
	tag_id = TAG_INT


class Long(int):
	tag_id = TAG_LONG


class Float(float):
	tag_id = TAG_FLOAT


class Double(float):
	tag_id = TAG_DOUBLE


class String(str):
	tag_id = TAG_STRING


class ByteArray(list):
	"""字节数组：元素为 int（-128..127）。"""

	tag_id = TAG_BYTE_ARRAY


class IntArray(list):
	tag_id = TAG_INT_ARRAY


class LongArray(list):
	tag_id = TAG_LONG_ARRAY


class List(list):
	"""NBT 列表：附带 elem_type（元素标签类型）；空列表默认 TAG_END（与原版写出行为一致）。"""

	tag_id = TAG_LIST

	def __init__(self, items=(), elem_type=None):
		items = list(items)
		super().__init__(items)
		if elem_type is None:
			elem_type = _tag_id_of(items[0]) if items else TAG_END
		self.elem_type = elem_type


class Compound(dict):
	"""NBT 复合标签：键 -> 标签对象（dict 保持插入顺序 = 字段顺序）。"""

	tag_id = TAG_COMPOUND


_TAG_IDS = {
	Byte: TAG_BYTE,
	Short: TAG_SHORT,
	Int: TAG_INT,
	Long: TAG_LONG,
	Float: TAG_FLOAT,
	Double: TAG_DOUBLE,
	String: TAG_STRING,
	ByteArray: TAG_BYTE_ARRAY,
	IntArray: TAG_INT_ARRAY,
	LongArray: TAG_LONG_ARRAY,
	List: TAG_LIST,
	Compound: TAG_COMPOUND,
}

_NUMERIC_FORMATS = {
	TAG_BYTE: (">b", 1),
	TAG_SHORT: (">h", 2),
	TAG_INT: (">i", 4),
	TAG_LONG: (">q", 8),
	TAG_FLOAT: (">f", 4),
	TAG_DOUBLE: (">d", 8),
}


def _tag_id_of(value) -> int:
	"""取包装对象的标签类型 ID；非包装类型抛 TypeError（fail fast，避免隐式类型错误）。"""
	tid = _TAG_IDS.get(type(value))
	if tid is None:
		raise TypeError(
			f"非 NBT 包装类型：{type(value).__name__}（请使用 Byte/Short/Int/Long/Float/Double/String/List/Compound 等包装）"
		)
	return tid


def _read_utf(io: _io.BytesIO) -> String:
	"""读 Java DataInput 风格字符串：无符号 short 长度 + UTF-8 字节。"""
	(n,) = struct.unpack(">H", io.read(2))
	return String(io.read(n).decode("utf-8", errors="surrogateescape"))


def _write_utf(buf: _io.BytesIO, s: str) -> None:
	"""写 Java DataInput 风格字符串。"""
	data = s.encode("utf-8", errors="surrogateescape")
	if len(data) > 0xFFFF:
		raise ValueError(f"字符串过长（{len(data)} 字节）")
	buf.write(struct.pack(">H", len(data)))
	buf.write(data)


def _read_payload(io: _io.BytesIO, tag_id: int):
	"""按标签类型读负载。"""
	if tag_id in _NUMERIC_FORMATS:
		fmt, size = _NUMERIC_FORMATS[tag_id]
		(v,) = struct.unpack(fmt, io.read(size))
		return {TAG_BYTE: Byte, TAG_SHORT: Short, TAG_INT: Int, TAG_LONG: Long, TAG_FLOAT: Float, TAG_DOUBLE: Double}[
			tag_id
		](v)
	if tag_id == TAG_BYTE_ARRAY:
		(n,) = struct.unpack(">i", io.read(4))
		return ByteArray(struct.unpack(f">{n}b", io.read(n)))
	if tag_id == TAG_STRING:
		return _read_utf(io)
	if tag_id == TAG_LIST:
		(elem_type,) = struct.unpack(">b", io.read(1))
		(n,) = struct.unpack(">i", io.read(4))
		return List((_read_payload(io, elem_type) for _ in range(n)), elem_type)
	if tag_id == TAG_COMPOUND:
		compound = Compound()
		while True:
			(child_type,) = struct.unpack(">b", io.read(1))
			if child_type == TAG_END:
				return compound
			name = _read_utf(io)
			compound[name] = _read_payload(io, child_type)
	if tag_id == TAG_INT_ARRAY:
		(n,) = struct.unpack(">i", io.read(4))
		return IntArray(struct.unpack(f">{n}i", io.read(n * 4)))
	if tag_id == TAG_LONG_ARRAY:
		(n,) = struct.unpack(">i", io.read(4))
		return LongArray(struct.unpack(f">{n}q", io.read(n * 8)))
	raise ValueError(f"未知标签类型：{tag_id}")


def _write_payload(buf: _io.BytesIO, value) -> None:
	"""按包装类型写负载。"""
	tag_id = _tag_id_of(value)
	if tag_id in _NUMERIC_FORMATS:
		fmt, _ = _NUMERIC_FORMATS[tag_id]
		buf.write(struct.pack(fmt, value))
		return
	if tag_id == TAG_BYTE_ARRAY:
		buf.write(struct.pack(">i", len(value)))
		buf.write(struct.pack(f">{len(value)}b", *[int(x) for x in value]))
		return
	if tag_id == TAG_STRING:
		_write_utf(buf, value)
		return
	if tag_id == TAG_LIST:
		elem_type = value.elem_type
		if len(value) > 0:
			actual = _tag_id_of(value[0])
			if elem_type in (None, TAG_END):
				elem_type = actual
			elif actual != elem_type:
				raise TypeError(f"列表元素类型不一致：声明 {elem_type}，实际 {actual}")
		buf.write(struct.pack(">b", elem_type if elem_type is not None else TAG_END))
		buf.write(struct.pack(">i", len(value)))
		for item in value:
			_write_payload(buf, item)
		return
	if tag_id == TAG_COMPOUND:
		for key, child in value.items():
			buf.write(struct.pack(">b", _tag_id_of(child)))
			_write_utf(buf, key)
			_write_payload(buf, child)
		buf.write(b"\x00")
		return
	if tag_id == TAG_INT_ARRAY:
		buf.write(struct.pack(">i", len(value)))
		buf.write(struct.pack(f">{len(value)}i", *[int(x) for x in value]))
		return
	if tag_id == TAG_LONG_ARRAY:
		buf.write(struct.pack(">i", len(value)))
		buf.write(struct.pack(f">{len(value)}q", *[int(x) for x in value]))
		return
	raise ValueError(f"未知标签类型：{tag_id}")


class Document:
	"""一份 NBT 文档：根标签名 + 根 Compound。"""

	def __init__(self, name: String, root: Compound):
		self.name = name
		self.root = root


def loads(data: bytes) -> Document:
	"""解析未压缩的 NBT 字节流（根必须为 Compound）。"""
	io = _io.BytesIO(data)
	(tag_id,) = struct.unpack(">b", io.read(1))
	if tag_id != TAG_COMPOUND:
		raise ValueError(f"根标签不是 Compound（实际 {tag_id}）")
	name = _read_utf(io)
	return Document(name, _read_payload(io, TAG_COMPOUND))


def dumps(doc: Document) -> bytes:
	"""序列化为未压缩的 NBT 字节流。"""
	buf = _io.BytesIO()
	buf.write(struct.pack(">b", TAG_COMPOUND))
	_write_utf(buf, doc.name)
	_write_payload(buf, doc.root)
	return buf.getvalue()


def load(path) -> Document:
	"""读 gzip 压缩的 .dat 文件。"""
	with open(path, "rb") as f:
		return loads(gzip.decompress(f.read()))


def save(doc: Document, path) -> None:
	"""写 gzip 压缩的 .dat 文件。"""
	with open(path, "wb") as f:
		f.write(gzip.compress(dumps(doc)))


def equal(a, b) -> bool:
	"""类型感知的递归相等判定（含 List.elem_type）。"""
	if type(a) is not type(b):
		return False
	if isinstance(a, dict):
		return a.keys() == b.keys() and all(equal(a[k], b[k]) for k in a)
	if isinstance(a, list):
		if len(a) != len(b):
			return False
		if isinstance(a, List) and a.elem_type != b.elem_type:
			return False
		return all(equal(x, y) for x, y in zip(a, b))
	return a == b


def get_path(root, expr: str):
	"""按 "a.b.c"（列表用数字下标）取子节点；供调试与断言使用。"""
	current = root
	for part in expr.split("."):
		if isinstance(current, dict):
			if part not in current:
				raise KeyError(f"路径不存在：{expr}（在 {part} 处失败）")
			current = current[part]
		elif isinstance(current, list):
			current = current[int(part)]
		else:
			raise TypeError(f"路径 {expr} 在 {part} 处不可继续（{type(current).__name__}）")
	return current


def format_value(value, indent: int = 0, max_depth: int | None = None) -> str:
	"""可读格式化（带类型标记；max_depth 控制嵌套深度）。"""
	pad = "  " * indent
	value_type = type(value)
	if value_type is Compound:
		if not value:
			return "{}"
		if max_depth is not None and indent // 2 >= max_depth:
			return f"{{...{len(value)} 键...}}"
		lines = ["{"]
		for key, child in value.items():
			lines.append(f"{pad}  {key}: {format_value(child, indent + 1, max_depth)}")
		lines.append(pad + "}")
		return "\n".join(lines)
	if value_type is List:
		if not value:
			return "[]"
		if max_depth is not None and indent // 2 >= max_depth:
			return f"[...{len(value)} 项...]"
		inner = ", ".join(format_value(item, indent + 1, max_depth) for item in value)
		return f"[{inner}]"
	if value_type is String:
		return f'"{value}"'
	if value_type is Byte:
		return f"{int(value)}b"
	if value_type is Short:
		return f"{int(value)}s"
	if value_type is Int:
		return str(int(value))
	if value_type is Long:
		return f"{int(value)}L"
	if value_type is Float:
		return f"{float(value)}f"
	if value_type is Double:
		return f"{float(value)}d"
	if value_type in (ByteArray, IntArray, LongArray):
		return f"{value_type.__name__}{list(value)}"
	return repr(value)


def main(argv: list[str]) -> int:
	"""自测入口：读入 .dat -> 摘要 -> 内存往返 + 文件往返一致性校验。"""
	if len(argv) < 2:
		print("用法: python nbt_min.py <level.dat>")
		return 2
	path = argv[1]
	doc = load(path)
	print(f"文件: {path}")
	print(f"根标签名: {doc.name!r}")
	print(f"顶层键({len(doc.root)}): {list(doc.root.keys())}")

	# 内存往返
	doc2 = loads(dumps(doc))
	ok_mem = doc2.name == doc.name and equal(doc.root, doc2.root)
	# 文件往返（覆盖 gzip 路径）
	tmp_path = str(path) + ".roundtrip.tmp"
	save(doc, tmp_path)
	doc3 = load(tmp_path)
	ok_file = doc3.name == doc.name and equal(doc.root, doc3.root)
	import os

	os.remove(tmp_path)

	print(f"内存往返: {'OK' if ok_mem else 'FAILED'}")
	print(f"文件往返: {'OK' if ok_file else 'FAILED'}")
	return 0 if (ok_mem and ok_file) else 1


if __name__ == "__main__":
	sys.exit(main(sys.argv))
