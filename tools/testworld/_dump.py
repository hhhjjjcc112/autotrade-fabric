"""调试助手：打印 NBT 文件（level.dat）的可读结构。

用法:
  python _dump.py <file.dat> [路径表达式] [--depth N]
示例:
  python _dump.py "run/saves/New World/level.dat"                       # 全量（可能很长）
  python _dump.py "run/saves/New World/level.dat" Data.GameRules
  python _dump.py "run/saves/New World/level.dat" Data.WorldGenSettings --depth 3
"""

import sys

from nbt_min import format_value, get_path, load


def main(argv: list[str]) -> int:
	args: list[str] = []
	depth = None
	i = 1
	while i < len(argv):
		if argv[i] == "--depth":
			depth = int(argv[i + 1])
			i += 2
			continue
		args.append(argv[i])
		i += 1
	if not args:
		print(__doc__)
		return 2
	doc = load(args[0])
	root = doc.root
	if len(args) > 1:
		root = get_path(root, args[1])
	print(format_value(root, max_depth=depth))
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv))
