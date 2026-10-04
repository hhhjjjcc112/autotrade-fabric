#!/usr/bin/env python3
"""AutoTrade 测试世界生成器。

从只读模板世界（默认 `New World`）派生一个固定布局的超平坦测试世界，
并渲染 datapack（固定交易的村民 + 输入/输出箱 + 周期补货/补满/清空统计），
同时写入一份测试用 mod 配置。

四种装置模式：
	static（默认）：STATIC 静止交易装置，世界默认 `AutoTradeTest`
	void：VOID 虚空交易装置（家侧交易点 + 岛侧着陆点 + 陷阱箱/中继器返航机关），
	      世界默认 `AutoTradeVoidTest`
	moving：MOVING 移动交易装置（环形矿车轨道持续带动玩家，沿途 12 村民 + 5 输入/输出箱），
	      世界默认 `AutoTradeMovingTest`
	capacity：CAPACITY 容量检测装置（复用 STATIC 坐标系单站台；无村民/容器基线，村民与
	          背包库存由 `test/lib/capacity_scenarios.py` 的 23 组用例逐组布置，生成
	          `autotrade_test:cap_<id>` 场景函数），世界默认 `AutoTradeCapacityTest`

用法：
	python test/lib/setup_testworld.py                         # static：生成世界 + 备份并写测试配置 + 校验
	python test/lib/setup_testworld.py --verify                # 仅校验（默认 static 世界）
	python test/lib/setup_testworld.py --mode void             # VOID：生成 AutoTradeVoidTest + VOID 配置
	python test/lib/setup_testworld.py --mode void --verify    # 仅校验 VOID 世界
	python test/lib/setup_testworld.py --mode moving           # MOVING：生成 AutoTradeMovingTest + MOVING 配置
	python test/lib/setup_testworld.py --mode moving --verify  # 仅校验 MOVING 世界
	python test/lib/setup_testworld.py --mode capacity         # CAPACITY：生成 AutoTradeCapacityTest + 23 场景函数 + 导出用例表
	python test/lib/setup_testworld.py --mode capacity --verify# 仅校验 CAPACITY 世界（含表↔脚本同步检查）
	python test/lib/setup_testworld.py --skip-config
	python test/lib/setup_testworld.py --fresh

说明：脚本从仓库根目录（autotrade-fabric/）运行；脚本会自行定位仓库根目录，任意 cwd 均可。

模式相关参数：
	--mode {static,void,moving,capacity}  装置模式（默认 static）
	--teleport-delay-seconds     仅 VOID：互动后延迟多少秒传送玩家至岛侧（默认 0.5 → TELEPORT_DELAY_TICKS=10）
	--world-name                 覆盖默认世界名
	                             （static=AutoTradeTest / void=AutoTradeVoidTest / moving=AutoTradeMovingTest / capacity=AutoTradeCapacityTest）

注意：本脚本绝不启动 Minecraft；游戏内验证由用户手动执行。
"""

from __future__ import annotations

import argparse
import ast
import gzip
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path
from typing import NamedTuple

# 控制台按 UTF-8 输出，避免中文在默认代码页下抛 UnicodeEncodeError
for _stream in (sys.stdout, sys.stderr):
	_reconfigure = getattr(_stream, "reconfigure", None)
	if _reconfigure is not None:
		try:
			_reconfigure(encoding="utf-8", errors="replace")
		except Exception:
			pass

# NBT 读写依赖第三方库 pynbt（无内建 gzip，需用标准库 gzip 包裹）
try:
	import pynbt
except ImportError:
	print("错误：缺少依赖 pynbt（请执行 pip install pynbt）")
	sys.exit(2)

# 同目录用例表模块（test/lib/capacity_scenarios.py）：CAPACITY 模式的单一数据源。
# 直跑脚本时脚本目录已在 sys.path[0]，此处再显式插入，兼容以其它方式加载模块的场景。
_THIS_DIR = str(Path(__file__).resolve().parent)
if _THIS_DIR not in sys.path:
	sys.path.insert(0, _THIS_DIR)
import capacity_scenarios  # noqa: E402  （需先就位 sys.path）


def _nbt_load(path):
	"""读 gzip 压缩的 .dat 为 pynbt.NBTFile（根为 TAG_Compound）。"""
	with gzip.open(path, "rb") as f:
		return pynbt.NBTFile(io=f)


def _nbt_save(doc: pynbt.NBTFile, path) -> None:
	"""把 pynbt.NBTFile 以 gzip 压缩写回 .dat。"""
	with gzip.open(path, "wb") as f:
		doc.save(f)


def get_path(root, expr: str):
	"""按 "a.b.c"（列表用数字下标）取子标签，返回 pynbt 标签对象。

	注意：pynbt 的 TAG_Compound / TAG_List 的 `.value` 即自身，
	取标量的 Python 原生值请用 get_value()。
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


def get_value(root, expr: str):
	"""取路径终点的 Python 原生值（标量取 .value；容器返回自身）。"""
	return get_path(root, expr).value


def _set(compound: pynbt.TAG_Compound, key: str, value) -> None:
	"""在 Compound 上设置键（pynbt 会自动补标签名；保持既有键顺序，新键追加到末尾）。"""
	compound[key] = value

# 仓库根目录（autotrade-fabric/）：<repo>/test/lib/setup_testworld.py -> parents[2]
ROOT = Path(__file__).resolve().parents[2]
RUN_DIR = ROOT / "run"
SAVES_DIR = RUN_DIR / "saves"
CONFIG_PATH = RUN_DIR / "config" / "autotrade.json"
# datapack 源位于 test/datapack_src（lib 的兄弟目录）：<repo>/test/lib/setup_testworld.py -> parents[1] = test
DATAPACK_SRC = Path(__file__).resolve().parents[1] / "datapack_src"

class Layout(NamedTuple):
	"""一套测试装置主基地的固定坐标（VOID 岛侧坐标另见 VOID_* 常量）。"""

	player_pos: tuple  # 玩家初始位置（level.dat Player.Pos + setup 传送点）
	player_spawn: tuple  # 玩家重生点（level.dat Player.SpawnX/Y/Z）
	villager: tuple  # 测试村民召唤坐标（x 需小数）
	villager_block_x: int  # `if loaded` 门控使用的方块坐标
	input_chest: tuple  # 输入箱（绿宝石）
	output_chest: tuple  # 输出箱（交易产物）


# VOID 装置岛侧坐标（恒定；STATIC 模板不引用，但统一注册/替换以保证全部占位符生效）
VOID_ISLAND_POS = (3000.5, -60, 0.5)  # 岛侧着陆点（去程传送目标）
VOID_ISLAND_BLOCK = (3000, -60, 0)  # 回程检测的定位方块（仅玩家在附近时才读方块）
VOID_RET_CHEST = (3002, -60, 0)  # 岛侧返回触发陷阱箱（被模组打开 → 检测器通电）
VOID_RET_REPEATER = (3003, -60, 0)  # 中继器检测器（facing=west：闸门从 FACING 侧取电，须朝陷阱箱）

# STATIC 布局（与既有 AutoTradeTest 生成结果完全一致）
STATIC_LAYOUT = Layout(
	player_pos=(0.5, -60, 0.5),
	player_spawn=(0, -60, 0),
	villager=(3.5, -60, 0),
	villager_block_x=3,
	input_chest=(-2, -60, 0),
	output_chest=(0, -60, 2),
)
# VOID 布局：家侧 HOME（仅交易点，无容器）；IO 容器置于岛侧返回站（验证「容器 IO 优先于回程触发」）；
# 与岛侧相距 1000 格，去程后家侧区块卸载（无限交易前提）
VOID_LAYOUT = Layout(
	player_pos=(2000.5, -60, 0.5),
	player_spawn=(2000, -60, 0),
	villager=(2003.5, -60, 0),
	villager_block_x=2003,
	input_chest=(3000, -60, 2),
	output_chest=(2998, -60, 0),
)
# MOVING v2 容器 5 个（§2）：E 绿宝石输入 / W 小麦输入 / P 纸输出 / B 书输出 / G 玻璃输出
# 语义：每物品各自容器；W 紧贴 mv3、G 位于南簇内部 → 制造容器-村民竞争
MOVING_CONTAINERS = [
	("E", "minecraft:emerald", (2, -60, -2)),  # 绿宝石输入（贴 mv0(4,-60,-2)，间距 2）
	("W", "minecraft:wheat", (24, -60, -2)),  # 小麦输入（贴 mv9(22,-60,-2)，间距 2）
	("P", "minecraft:paper", (22, -60, 34)),  # 纸输出（贴 mv17(20,-60,34)，间距 2）
	("B", "minecraft:book", (66, -60, 16)),  # 书输出（贴 mv18(66,-60,18)，间距 2）
	("G", "minecraft:glass", (48, -60, 34)),  # 玻璃输出（嵌南簇 mv15(46)/mv14(50) 之间，间距 2）
]
# 交易对定义（§3）：T1 绿宝石→纸（单成本）/ T2 绿宝石→书（单成本）/ T3 绿宝石+小麦→玻璃（双成本，第二成本 wheat）
MOVING_PAIR_DEFS = {
	"T1": ("minecraft:paper", None),
	"T2": ("minecraft:book", None),
	"T3": ("minecraft:glass", "minecraft:wheat"),
}
# MOVING 村民会话上限（§2/§3 = 16；STATIC/VOID 仍用 --max-uses 默认 64）
MOVING_MAX_USES = 16
# MOVING 布局（§2）：玩家由环形矿车轨道带动；20 村民分列北/南**密集簇（间距 2，对齐真实交易所）**，
# 5 容器各自紧贴村民（间距 2）；目标数 ≫ 单次经过可服务数 → 未服务目标持续累积饥饿
MOVING_LAYOUT = Layout(
	player_pos=(2.5, -60, 0.5),
	player_spawn=(2, -60, 0),
	villager=(4, -60, -2),
	villager_block_x=4,
	input_chest=(2, -60, -2),
	output_chest=(22, -60, 34),
)
LAYOUTS = {"static": STATIC_LAYOUT, "void": VOID_LAYOUT, "moving": MOVING_LAYOUT}
# CAPACITY 布局：复用 STATIC 坐标（单站台超平坦；村民与背包库存由各用例函数逐组布置，无容器）
CAPACITY_LAYOUT = Layout(
	player_pos=(0.5, -60, 0.5),
	player_spawn=(0, -60, 0),
	villager=(3.5, -60, 0),
	villager_block_x=3,
	input_chest=(-2, -60, 0),
	output_chest=(0, -60, 2),
)
LAYOUTS["capacity"] = CAPACITY_LAYOUT
# 默认世界名（--world-name 可显式覆盖）
DEFAULT_WORLD_NAMES = {
	"static": "AutoTradeTest",
	"void": "AutoTradeVoidTest",
	"moving": "AutoTradeMovingTest",
	"capacity": "AutoTradeCapacityTest",
}

# ---- MOVING 环形轨道几何（§2）：铁轨矩形 x∈[0,64] / z∈[0,32] 于 y=-60（下方草地 y=-61 支承） ----
MOVING_TRACK_MIN_X, MOVING_TRACK_MAX_X = 0, 64
MOVING_TRACK_MIN_Z, MOVING_TRACK_MAX_Z = 0, 32
MOVING_TRACK_Y = -60
MOVING_POWER_Y = MOVING_TRACK_Y - 1  # 动力铁轨正下方的红石块层（供电）
MOVING_POWERED_RAIL_STRIDE = 6  # 沿环每 6 格放 1 个动力铁轨，维持矿车 ~8 m/s
def _moving_container(name: str) -> tuple[int, int, int]:
	"""按名称取 MOVING 容器坐标（E/W/P/B/G）。"""
	for cname, _item, pos in MOVING_CONTAINERS:
		if cname == name:
			return pos
	raise KeyError(f"未知 MOVING 容器：{name}")


# 20 个村民召唤点：(标签, x, z, 配方集)；配方集按 i % 4 循环 0→[T1] / 1→[T1,T2] / 2→[T1,T2,T3] / 3→[T2,T3]
# 密度对齐真实交易所：同簇间距 **2 格**（窗口大幅重叠 → 单次经过只能服务少数，其余进入饥饿记账）。
# 北簇 mv0..mv9 于 z=-2（x=4..22，矿车自西向东经过）；南簇 mv10..mv16 于 z=34（x=58..44，自东向西，
# 跳过 x=48 留给玻璃输出箱）；另有南单点 mv17(20,34)、东 mv18(66,18)、西 mv19(-2,16)。
MOVING_VILLAGERS = [
	("mv0", 4, -2, ("T1",)),
	("mv1", 6, -2, ("T1", "T2")),
	("mv2", 8, -2, ("T1", "T2", "T3")),
	("mv3", 10, -2, ("T2", "T3")),
	("mv4", 12, -2, ("T1",)),
	("mv5", 14, -2, ("T1", "T2")),
	("mv6", 16, -2, ("T1", "T2", "T3")),
	("mv7", 18, -2, ("T2", "T3")),
	("mv8", 20, -2, ("T1",)),
	("mv9", 22, -2, ("T1", "T2")),
	("mv10", 58, 34, ("T1", "T2", "T3")),
	("mv11", 56, 34, ("T2", "T3")),
	("mv12", 54, 34, ("T1",)),
	("mv13", 52, 34, ("T1", "T2")),
	("mv14", 50, 34, ("T1", "T2", "T3")),
	("mv15", 46, 34, ("T2", "T3")),
	("mv16", 44, 34, ("T1",)),
	("mv17", 20, 34, ("T1", "T2")),
	("mv18", 66, 18, ("T1", "T2", "T3")),
	("mv19", -2, 16, ("T2", "T3")),
]

# 两种模式共享的 datapack 相对路径（用于校验）
SHARED_DATAPACK_FILES = [
	"pack.mcmeta",
	"data/minecraft/tags/functions/load.json",
	"data/minecraft/tags/functions/tick.json",
	"data/autotrade_test/functions/load.mcfunction",
	"data/autotrade_test/functions/tick.mcfunction",
	"data/autotrade_test/functions/tick_periodic.mcfunction",
	"data/autotrade_test/functions/setup.mcfunction",
	"data/autotrade_test/functions/restock.mcfunction",
	"data/autotrade_test/functions/refill_input.mcfunction",
	"data/autotrade_test/functions/clear_output.mcfunction",
]

# 仅 VOID 模式渲染的 datapack 文件（STATIC 渲染时按此列表跳过，保证既有输出不变）
VOID_ONLY_DATAPACK_FILES = [
	"data/autotrade_test/advancements/outbound.json",
	"data/autotrade_test/functions/tick_void.mcfunction",
	"data/autotrade_test/functions/void_out_arm.mcfunction",
	"data/autotrade_test/functions/void_out_fire.mcfunction",
	"data/autotrade_test/functions/void_ret_check.mcfunction",
	"data/autotrade_test/functions/void_ret_diag.mcfunction",
	"data/autotrade_test/functions/void_ret_fire.mcfunction",
	"data/autotrade_test/functions/void_status.mcfunction",
]

# 仅 MOVING 模式渲染的 datapack 文件（STATIC/VOID 渲染时按此列表跳过，保证各自输出不含 MOVING 维护）
MOVING_ONLY_DATAPACK_FILES = [
	"data/autotrade_test/functions/moving_maintenance.mcfunction",
]

# 期望生成的 datapack 相对路径（按模式；用于校验）
EXPECTED_DATAPACK_FILES = {
	"static": SHARED_DATAPACK_FILES,
	"void": SHARED_DATAPACK_FILES + VOID_ONLY_DATAPACK_FILES,
	# MOVING v2：共享文件 + 专属 moving_maintenance.mcfunction
	"moving": SHARED_DATAPACK_FILES + MOVING_ONLY_DATAPACK_FILES,
	# CAPACITY：共享文件 + 23 个用例函数（由 capacity_scenarios.CASES 动态派生，保持单一来源）
	"capacity": SHARED_DATAPACK_FILES
	+ [f"data/autotrade_test/functions/cap_{case.id}.mcfunction" for case in capacity_scenarios.CASES],
}


def patch_level_dat(doc: pynbt.NBTFile, world_name: str, layout: Layout) -> None:
	"""按测试世界需求就地补丁 level.dat 的 Data（及可选的 Data.Player）；坐标随模式布局。"""
	data = doc["Data"]

	# 基础世界元数据
	_set(data, "LevelName", pynbt.TAG_String(world_name))
	_set(data, "GameType", pynbt.TAG_Int(1))  # 创造模式（村民交易仍会消耗成本物品）
	_set(data, "Difficulty", pynbt.TAG_Byte(0))  # 和平
	_set(data, "allowCommands", pynbt.TAG_Byte(1))
	_set(data, "hardcore", pynbt.TAG_Byte(0))
	_set(data, "initialized", pynbt.TAG_Byte(1))

	# 出生点：地面 (0, -60, 0)
	_set(data, "SpawnX", pynbt.TAG_Int(0))
	_set(data, "SpawnY", pynbt.TAG_Int(-60))
	_set(data, "SpawnZ", pynbt.TAG_Int(0))
	_set(data, "SpawnAngle", pynbt.TAG_Float(0.0))

	# 时间固定为白天正午
	_set(data, "Time", pynbt.TAG_Long(6000))
	_set(data, "DayTime", pynbt.TAG_Long(6000))

	# 天气固定晴朗
	_set(data, "raining", pynbt.TAG_Byte(0))
	_set(data, "thundering", pynbt.TAG_Byte(0))
	_set(data, "clearWeatherTime", pynbt.TAG_Int(1000000))
	_set(data, "rainTime", pynbt.TAG_Int(1000000))
	_set(data, "thunderTime", pynbt.TAG_Int(1000000))

	# 游戏规则（值均为字符串）
	rules = data.get("GameRules")
	if not isinstance(rules, pynbt.TAG_Compound):
		rules = pynbt.TAG_Compound()
		_set(data, "GameRules", rules)
	for key, value in {
		"doMobSpawning": "false",
		"doDaylightCycle": "false",
		"doWeatherCycle": "false",
		"doTraderSpawning": "false",
		"doPatrolSpawning": "false",
		"doWardenSpawning": "false",
		"doInsomnia": "false",
		"disableRaids": "true",
		"randomTickSpeed": "0",
		"doFireTick": "false",
		"keepInventory": "true",
		"mobGriefing": "false",
		"announceAdvancements": "false",
		"logAdminCommands": "false",
		"spawnRadius": "0",
		"sendCommandFeedback": "true",
		"doImmediateRespawn": "true",
		"naturalRegeneration": "true",
		"commandBlockOutput": "false",
	}.items():
		_set(rules, key, pynbt.TAG_String(value))

	# 世界生成：固定种子 + 超平坦（三层）+ 无结构
	wgs = data["WorldGenSettings"]
	_set(wgs, "seed", pynbt.TAG_Long(12345))
	_set(wgs, "generate_features", pynbt.TAG_Byte(0))
	generator = pynbt.TAG_Compound()
	generator["type"] = pynbt.TAG_String("minecraft:flat")
	settings = pynbt.TAG_Compound()
	settings["layers"] = pynbt.TAG_List(
		pynbt.TAG_Compound,
		[
			pynbt.TAG_Compound({"block": pynbt.TAG_String("minecraft:bedrock"), "height": pynbt.TAG_Int(1)}),
			pynbt.TAG_Compound({"block": pynbt.TAG_String("minecraft:dirt"), "height": pynbt.TAG_Int(2)}),
			pynbt.TAG_Compound({"block": pynbt.TAG_String("minecraft:grass_block"), "height": pynbt.TAG_Int(1)}),
		],
	)
	settings["biome"] = pynbt.TAG_String("minecraft:plains")
	settings["features"] = pynbt.TAG_Byte(0)
	settings["lakes"] = pynbt.TAG_Byte(0)
	settings["structure_overrides"] = pynbt.TAG_List(pynbt.TAG_End)
	generator["settings"] = settings
	wgs["dimensions"]["minecraft:overworld"]["generator"] = generator

	# 宿主玩家：单人模式加载 level.dat 中的 Player 状态（位置/背包/游戏模式）
	player = data.get("Player")
	if isinstance(player, pynbt.TAG_Compound):
		# 去掉模板 UUID：让主机玩家沿用自身档案 UUID，避免身份键（统计/进度）不一致
		player.pop("UUID", None)
		_set(player, "Pos", pynbt.TAG_List(pynbt.TAG_Double, [pynbt.TAG_Double(v) for v in layout.player_pos]))
		_set(player, "Dimension", pynbt.TAG_String("minecraft:overworld"))
		_set(player, "Rotation", pynbt.TAG_List(pynbt.TAG_Float, [pynbt.TAG_Float(0.0), pynbt.TAG_Float(0.0)]))
		_set(
			player,
			"Motion",
			pynbt.TAG_List(
				pynbt.TAG_Double,
				[pynbt.TAG_Double(0.0), pynbt.TAG_Double(-0.0784000015258789), pynbt.TAG_Double(0.0)],
			),
		)
		_set(player, "playerGameType", pynbt.TAG_Int(1))
		_set(player, "Health", pynbt.TAG_Float(20.0))
		_set(player, "foodLevel", pynbt.TAG_Int(20))
		_set(player, "foodSaturationLevel", pynbt.TAG_Float(5.0))
		_set(player, "foodExhaustionLevel", pynbt.TAG_Float(0.0))
		_set(player, "foodTickTimer", pynbt.TAG_Int(0))
		_set(player, "Inventory", pynbt.TAG_List(pynbt.TAG_End))
		_set(player, "EnderItems", pynbt.TAG_List(pynbt.TAG_End))
		_set(player, "XpLevel", pynbt.TAG_Int(0))
		_set(player, "XpTotal", pynbt.TAG_Int(0))
		_set(player, "XpP", pynbt.TAG_Float(0.0))
		_set(player, "Score", pynbt.TAG_Int(0))
		_set(player, "XpSeed", pynbt.TAG_Int(0))
		_set(player, "SelectedItemSlot", pynbt.TAG_Int(0))
		_set(player, "SpawnX", pynbt.TAG_Int(layout.player_spawn[0]))
		_set(player, "SpawnY", pynbt.TAG_Int(layout.player_spawn[1]))
		_set(player, "SpawnZ", pynbt.TAG_Int(layout.player_spawn[2]))
		_set(player, "SpawnAngle", pynbt.TAG_Float(0.0))
		_set(player, "SpawnDimension", pynbt.TAG_String("minecraft:overworld"))
		_set(player, "HurtTime", pynbt.TAG_Short(0))
		_set(player, "DeathTime", pynbt.TAG_Short(0))
		_set(player, "HurtByTimestamp", pynbt.TAG_Int(0))
		_set(player, "Fire", pynbt.TAG_Short(-20))
		_set(player, "Air", pynbt.TAG_Short(300))
		_set(player, "PortalCooldown", pynbt.TAG_Int(0))
		_set(player, "SleepTimer", pynbt.TAG_Short(0))
		_set(player, "FallDistance", pynbt.TAG_Float(0.0))
		_set(player, "OnGround", pynbt.TAG_Byte(1))
		_set(player, "seenCredits", pynbt.TAG_Byte(0))
		abilities = pynbt.TAG_Compound()
		abilities["invulnerable"] = pynbt.TAG_Byte(1)
		abilities["mayfly"] = pynbt.TAG_Byte(1)
		abilities["instabuild"] = pynbt.TAG_Byte(1)
		abilities["mayBuild"] = pynbt.TAG_Byte(1)
		abilities["flying"] = pynbt.TAG_Byte(0)
		abilities["walkSpeed"] = pynbt.TAG_Float(0.1)
		abilities["flySpeed"] = pynbt.TAG_Float(0.05)
		_set(player, "abilities", abilities)
	else:
		print("警告：模板 level.dat 中不存在 Data.Player，跳过玩家状态补丁（不视为失败）")


# 村民 summon 的共享 NBT 模板（各模式逐字节一致，仅 Tags/CustomName 不同）：
# 用哨兵串 __TAGS__ / __NAME__ 占位，避免 f-string 与 mcf 双大括号冲突；
# {{OUTPUT_ITEM}} / {{MAX_USES}} 保持为全局占位符，由渲染阶段统一替换
_VILLAGER_NBT_TEMPLATE = (
	'{Tags:[__TAGS__],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Age:0,'
	'CustomName:\'{"text":"__NAME__"}\',CustomNameVisible:0b,Health:20.0f,'
	'VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:5},'
	'Offers:{Recipes:[{buy:{id:"minecraft:emerald",Count:1b},sell:{id:"{{OUTPUT_ITEM}}",Count:1b},'
	'uses:0,maxUses:{{MAX_USES}},xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}]}}'
)


def _villager_summon(layout: Layout, name: str, tags: str, x: float, z: float) -> str:
	"""拼装单行村民 summon 命令（坐标/标签/名称可变，NBT 主体与既有模板逐字节一致）。"""
	vy = layout.villager[1]
	nbt = _VILLAGER_NBT_TEMPLATE.replace("__TAGS__", tags).replace("__NAME__", name)
	return f"summon minecraft:villager {x} {vy} {z} {nbt}"


def _recipe_nbt(pair: str) -> str:
	"""单条交易配方 NBT（§3）：emerald×1 → sell×1；T3 追加 buyB（wheat×1）。

	maxUses 保留 `{{MAX_USES}}` 占位符（MOVING 渲染为 16），确保嵌套占位符顺序约束生效。
	"""
	sell, buy2 = MOVING_PAIR_DEFS[pair]
	buy_b = 'buyB:{id:"' + buy2 + '",Count:1b},' if buy2 else ""
	return (
		'{buy:{id:"minecraft:emerald",Count:1b},'
		+ buy_b
		+ 'sell:{id:"' + sell + '",Count:1b},uses:0,maxUses:{{MAX_USES}},'
		+ 'xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}'
	)


def _moving_villager_nbt(tag: str, recipes) -> str:
	"""MOVING 村民 NBT：与静态模板同结构，Offers.Recipes 为该村民的配方列表（§3）。

	CustomName 形如 `AT-MV-mv2-123`（末段为配方对序号拼接，便于人查）。
	"""
	name = "AT-MV-" + tag + "-" + "".join(p[1] for p in recipes)
	recipe_list = ",".join(_recipe_nbt(p) for p in recipes)
	return (
		'{Tags:["autotrade_test","' + tag + '"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Age:0,'
		'CustomName:\'{"text":"' + name + '"}\',CustomNameVisible:0b,Health:20.0f,'
		'VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:5},'
		'Offers:{Recipes:[' + recipe_list + ']}}'
	)


def build_single_villager_summon(layout: Layout) -> str:
	"""STATIC/VOID：生成与既有输出逐字节相同的单行村民 summon（坐标为布局村民点）。"""
	vx, _, vz = layout.villager
	return _villager_summon(layout, "AT-TestVillager", '"autotrade_test"', vx, vz)


def build_moving_villager_summons(layout: Layout) -> str:
	"""MOVING v2：生成 12 行村民 summon（mv0..mv11；各自配方集，CustomName 带配方编码便于人查）。"""
	vy = layout.villager[1]
	lines = []
	for tag, x, z, recipes in MOVING_VILLAGERS:
		# 标签同时带 autotrade_test（供 restock/setup 的 @e 选择器命中）与各自的 mvN 标识
		lines.append(f"summon minecraft:villager {x} {vy} {z} {_moving_villager_nbt(tag, recipes)}")
	return "\n".join(lines)


def build_single_restock_line() -> str:
	"""STATIC/VOID：与既有输出逐字节相同的单行补货（保留 {{OUTPUT_ITEM}}/{{MAX_USES}} 嵌套占位符）。"""
	return (
		'execute as @e[type=minecraft:villager,tag=autotrade_test] run data modify entity @s Offers.Recipes set value '
		'[{buy:{id:"minecraft:emerald",Count:1b},sell:{id:"{{OUTPUT_ITEM}}",Count:1b},uses:0,maxUses:{{MAX_USES}},'
		'xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}]'
	)


def build_moving_restock_lines() -> str:
	"""MOVING v2：逐村民按其配方集重建 Offers.Recipes（12 行，选择器 tag=mvN）。"""
	lines = []
	for tag, _x, _z, recipes in MOVING_VILLAGERS:
		recipe_list = ",".join(_recipe_nbt(p) for p in recipes)
		lines.append(
			"execute as @e[type=minecraft:villager,tag="
			+ tag
			+ "] run data modify entity @s Offers.Recipes set value ["
			+ recipe_list
			+ "]"
		)
	return "\n".join(lines)


def _count_chest_slots_lines(chest: tuple[int, int, int], acc: str) -> list[str]:
	"""生成 27 槽计数行：该槽存在时把 Count 存入 #tmp，再累加到 acc（与 clear_output 的 COUNT_SLOTS 同构）。"""
	cx, cy, cz = chest
	lines = []
	for slot in range(27):
		selector = f"Items[{{Slot:{slot}b}}]"
		lines.append(
			f"execute if data block {cx} {cy} {cz} {selector} store result score #tmp autotrade_test run data get block {cx} {cy} {cz} {selector}.Count"
		)
		lines.append(
			f"execute if data block {cx} {cy} {cz} {selector} run scoreboard players operation {acc} autotrade_test += #tmp autotrade_test"
		)
	return lines


def _clr_tellraw(name: str, zh: str, count_score: str, total_score: str) -> str:
	"""生成含 [clr:<name>=N total=M] 标记的 tellraw（minescript 用正则按 name 提取清空/累计）。"""
	return (
		'tellraw @a [{"text":"[AutoTradeTest] 清空 ","color":"gray"},'
		'{"score":{"name":"' + count_score + '","objective":"autotrade_test"},"color":"yellow"},'
		'{"text":" 个' + zh + '（累计 ","color":"gray"},'
		'{"score":{"name":"' + total_score + '","objective":"autotrade_test"},"color":"yellow"},'
		'{"text":"）","color":"gray"},'
		'{"text":" [clr:' + name + '=","color":"dark_gray"},'
		'{"score":{"name":"' + count_score + '","objective":"autotrade_test"},"color":"dark_gray"},'
		'{"text":" total=","color":"dark_gray"},'
		'{"score":{"name":"' + total_score + '","objective":"autotrade_test"},"color":"dark_gray"},'
		'{"text":"]","color":"dark_gray"}]'
	)


def build_moving_maintenance_block() -> str:
	"""MOVING 专属维护（§5.3）：清空 book/glass 输出箱并记账 → 打印 [clr:book]/[clr:glass] → 补满 wheat 箱 → 复位 #t_maint。"""
	book = _moving_container("B")
	glass = _moving_container("G")
	bx, by, bz = book
	gx, gy, gz = glass
	lines = [
		"# —— MOVING 维护：清空 book/glass 输出箱并记账；补满 wheat 输入箱；复位维护计时 ——",
		"scoreboard players set #clr_b autotrade_test 0",
		"scoreboard players set #clr_g autotrade_test 0",
	]
	lines += _count_chest_slots_lines(book, "#clr_b")
	lines.append(f"data modify block {bx} {by} {bz} Items set value []")
	lines.append("scoreboard players operation #tot_b autotrade_test += #clr_b autotrade_test")
	lines.append(_clr_tellraw("book", "书", "#clr_b", "#tot_b"))
	lines += _count_chest_slots_lines(glass, "#clr_g")
	lines.append(f"data modify block {gx} {gy} {gz} Items set value []")
	lines.append("scoreboard players operation #tot_g autotrade_test += #clr_g autotrade_test")
	lines.append(_clr_tellraw("glass", "玻璃", "#clr_g", "#tot_g"))
	# wheat 输入箱重填：27 槽 × 64（占位符在渲染期替换）
	lines.append("data modify block {{W_CHEST_X}} {{W_CHEST_Y}} {{W_CHEST_Z}} Items set value [{{INPUT2_STACKS}}]")
	lines.append("scoreboard players set #t_maint autotrade_test 0")
	return "\n".join(lines)


def moving_perimeter_cells() -> list[tuple[int, int]]:
	"""按环序返回轨道矩形周界格（北→东→南→西；四角共格去重；总长 2*(65+33)-4=192）。"""
	cells: list[tuple[int, int]] = []
	cells.extend((x, MOVING_TRACK_MIN_Z) for x in range(MOVING_TRACK_MIN_X, MOVING_TRACK_MAX_X + 1))  # 北边（+x）
	cells.extend((MOVING_TRACK_MAX_X, z) for z in range(MOVING_TRACK_MIN_Z + 1, MOVING_TRACK_MAX_Z + 1))  # 东边（+z）
	cells.extend((x, MOVING_TRACK_MAX_Z) for x in range(MOVING_TRACK_MAX_X - 1, MOVING_TRACK_MIN_X - 1, -1))  # 南边（-x）
	cells.extend((MOVING_TRACK_MIN_X, z) for z in range(MOVING_TRACK_MAX_Z - 1, MOVING_TRACK_MIN_Z, -1))  # 西边（-z）
	return cells


def moving_powered_rail_cells() -> list[tuple[int, int]]:
	"""沿环每 6 格取 1 格作为动力铁轨位。

	从环序第 1 格起算（而非第 0 格）：四角环序为 0/64/96/160，与「从 0 起每 6 格」在第 0、96 处重叠；
	而 **PoweredRailBlock 禁止弯曲**（forbidCurves=true，shape 仅 STRAIGHT_RAIL_SHAPE），拐角动力铁轨
	永远保持直轨 → 矿车直行脱轨（实机已复现东南角脱轨）。错开 1 格保证四角全为普通铁轨（可自动成弯）。
	"""
	perimeter = moving_perimeter_cells()
	return [perimeter[i] for i in range(1, len(perimeter), MOVING_POWERED_RAIL_STRIDE)]


def build_moving_setup_lines() -> str:
	"""构造 MOVING 轨道装置行：铁轨矩形 + 每 6 格动力铁轨（下方红石块）+ 起速矿车 + 挂载玩家。"""
	x0, x1 = MOVING_TRACK_MIN_X, MOVING_TRACK_MAX_X
	z0, z1 = MOVING_TRACK_MIN_Z, MOVING_TRACK_MAX_Z
	y = MOVING_TRACK_Y
	lines = [
		"# —— MOVING 装置附加：环形矿车轨道（实体推动玩家，mod 不移动玩家） ——",
		# 幂等：setup 若被重复执行，先清掉残留矿车，避免召唤出第 2 辆
		"kill @e[type=minecraft:minecart,tag=att_cart]",
		# 铁轨矩形四边（角点共格，用 fill 紧凑铺设）
		f"fill {x0} {y} {z0} {x1} {y} {z0} minecraft:rail",  # 北边
		f"fill {x0} {y} {z1} {x1} {y} {z1} minecraft:rail",  # 南边
		f"fill {x0} {y} {z0 + 1} {x0} {y} {z1 - 1} minecraft:rail",  # 西边
		f"fill {x1} {y} {z0 + 1} {x1} {y} {z1 - 1} minecraft:rail",  # 东边
	]
	# 沿环每 6 格放动力铁轨，并在其正下方放红石块供电
	for px, pz in moving_powered_rail_cells():
		lines.append(f"setblock {px} {y} {pz} minecraft:powered_rail[powered=true]")
		lines.append(f"setblock {px} {MOVING_POWER_Y} {pz} minecraft:redstone_block")
	# 四角显式指定弯道形态（兜底，防任何更新顺序意外导致角轨为直轨；普通轨本可自动成弯）
	lines += [
		f"setblock {x0} {y} {z0} minecraft:rail[shape=south_east]",  # 角（接南/东）
		f"setblock {x1} {y} {z0} minecraft:rail[shape=south_west]",  # 角（接南/西）
		f"setblock {x1} {y} {z1} minecraft:rail[shape=north_west]",  # 角（接北/西）
		f"setblock {x0} {y} {z1} minecraft:rail[shape=north_east]",  # 角（接北/东）
	]
	# 随身预置（§2）：hotbar.0 由共享 setup 行填充绿宝石，这里补齐 hotbar.1..7 绿宝石（合计 8 组）
	# 与 hotbar.8 小麦（1 组，供首个 T3 双成本会话）。每会话最多耗尽 1 组。
	# 注意：/item 的槽位参数不支持区间语法（hotbar.0..8 会解析失败并使整个函数加载失败），须逐槽一行。
	lines.extend(f"item replace entity @a hotbar.{slot} with minecraft:emerald 64" for slot in range(1, 8))
	lines.append("item replace entity @a hotbar.8 with minecraft:wheat 64")
	# 起速矿车（向东入环，Motion 提供初速）与玩家挂载（/ride 的 target 必须单选——@a 会被解析器拒绝，
	# 故用 execute as @a 逐个以 @s 挂载）
	lines.append('summon minecraft:minecart 2.5 -59.5 0.5 {Tags:["att_cart"],Motion:[0.4d,0.0d,0.0d]}')
	lines.append("execute as @a run ride @s mount @e[type=minecraft:minecart,tag=att_cart,limit=1]")
	return "\n".join(lines)


def build_void_setup_lines() -> str:
	"""构造 VOID 模式插入 setup 的附加初始化行（家侧复位 + 随身库存；岛侧机关由 tick_void 惰性放置）。"""
	hx, hy, hz = VOID_LAYOUT.player_pos
	lines = [
		"# —— VOID 装置附加：家侧复位与随身库存（岛侧返回机关由 tick_void 惰性放置） ——",
		# 岛侧装置不在此放置：/setblock 要求区块已加载（getLoadedBlockPos），家侧 setup 时岛侧未加载会静默失败；
		# 改由 tick_void 在岛侧区块加载（玩家到达）时惰性补放，见 datapack_src/.../tick_void.mcfunction
		f"tp @a {hx} {hy} {hz}",
		# 撤销可能遗留的去程成就（跨会话残留会导致开局误触发一次去程传送）
		"advancement revoke @a only autotrade_test:outbound",
		"scoreboard players set #cycles_out autotrade_test 0",
		"scoreboard players set #cycles_back autotrade_test 0",
		"scoreboard players set #t_out autotrade_test -1",
		"scoreboard players set #ret_power autotrade_test 0",
		"scoreboard players set #t_status autotrade_test 0",
		"scoreboard players set #v_uses autotrade_test -1",
	]
	# hotbar.1..8 预置绿宝石（hotbar.0 由共享行填充）：VOID 交易期间岛侧无容器可达，需随身储备
	lines.extend(f"item replace entity @a hotbar.{slot} with minecraft:emerald 64" for slot in range(1, 9))
	return "\n".join(lines)


def build_capacity_case_function(case) -> str:
	"""生成单个 CAPACITY 用例的场景函数文本（`autotrade_test:cap_<id>`）。

	行序（勘误 #4 强制）：
	  1) 传送到站台；
	  2) 清掉掉落物（上一用例关窗余量）；
	  3) 清掉上一用例残留的 CAPACITY 村民（STATIC 每轮处理范围内全部村民，不清旧会命中
	     旧报价 → 预期外成交；见计划勘误 #4）；
	  4) 清空玩家背包；
	  5..) 逐非空槽写入精确库存（inventory.0..26 / hotbar.0..8；空槽跳过；不用区间语法）；
	  末) 召唤固定交易村民（Tags 含 autotrade_cap 与 cap_<id>，Offers 单配方）。
	返回以 LF 结尾的函数文本（UTF-8 写入）。
	"""
	layout = LAYOUTS["capacity"]
	px, py, pz = layout.player_pos
	vx, vy, vz = layout.villager
	case_id = case.id
	offer = case.offer
	lines = [
		f"tp @s {px} {py} {pz}",
		"kill @e[type=minecraft:item]",
		"kill @e[type=minecraft:villager,tag=autotrade_cap]",
		"clear @s",
	]
	# 槽位映射：索引 0..26 → inventory.N；27..35 → hotbar.(N-27)；空槽跳过
	for index, (item, count) in enumerate(capacity_scenarios.layout_case(case)):
		if not item or count <= 0:
			continue
		slot = f"inventory.{index}" if index < 27 else f"hotbar.{index - 27}"
		lines.append(f"item replace entity @s {slot} with {item} {count}")
	# 固定交易村民：单条配方（buy 恒为 1 绿宝石；sell 数量/maxUses 随用例）
	# 第二个标签用 `cap_<id>`（与函数名/文档一致，便于按用例精确选择器命中）
	nbt = (
		'{Tags:["autotrade_cap","cap_' + case_id + '"],NoAI:1b,Silent:1b,Invulnerable:1b,'
		'PersistenceRequired:1b,Age:0,CustomName:\'{"text":"AT-CAP-' + case_id + '"}\','
		'CustomNameVisible:0b,Health:20.0f,'
		'VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:5},'
		'Offers:{Recipes:[{buy:{id:"minecraft:emerald",Count:1b},'
		'sell:{id:"' + offer.sell_item + '",Count:' + str(offer.sell_count) + 'b},'
		'uses:0,maxUses:' + str(offer.max_uses) + ',xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}]}}'
	)
	lines.append(f"summon minecraft:villager {vx} {vy} {vz} {nbt}")
	return "\n".join(lines) + "\n"


def build_placeholders(args: argparse.Namespace) -> dict[str, str]:
	"""计算 datapack 模板占位符 -> 文本值（按模式分支：周期任务、虚空驱动行、环形轨道行不同）。"""
	layout = LAYOUTS[args.mode]
	input_stacks = ", ".join(
		f'{{Slot:{slot}b,id:"minecraft:emerald",Count:64b}}' for slot in range(27)
	)
	# MOVING v2 第二个输入箱（小麦）的 27 组预置内容
	input2_stacks = ", ".join(
		f'{{Slot:{slot}b,id:"minecraft:wheat",Count:64b}}' for slot in range(27)
	)
	ox, oy, oz = layout.output_chest
	count_slots_lines = []
	for slot in range(27):
		selector = f"Items[{{Slot:{slot}b}}]"
		count_slots_lines.append(
			f"execute if data block {ox} {oy} {oz} {selector} store result score #tmp autotrade_test run data get block {ox} {oy} {oz} {selector}.Count"
		)
		count_slots_lines.append(
			f"execute if data block {ox} {oy} {oz} {selector} run scoreboard players operation #cleared autotrade_test += #tmp autotrade_test"
		)
	ix, iy, iz = layout.input_chest
	vx, vy, vz = layout.villager
	wx, wy, wz = _moving_container("W")
	bx, by, bz = _moving_container("B")
	gx, gy, gz = _moving_container("G")
	is_void = args.mode == "void"
	is_moving = args.mode == "moving"
	is_capacity = args.mode == "capacity"
	# 周期任务占位符：STATIC/MOVING 保留村民补货计时器两行（坐标随布局）；VOID 置空补货并追加虚空驱动 + 状态打印
	if is_void:
		restock_timer_block = ""
		void_tick_line = "execute if score #setup_done autotrade_test matches 1 if entity @a run function autotrade_test:tick_void"
		void_status_block = "\n".join(
			[
				"scoreboard players add #t_status autotrade_test 1",
				"execute if score #t_status autotrade_test matches 100.. run function autotrade_test:void_status",
			]
		)
		void_setup_lines = build_void_setup_lines()
		# VOID 容器在岛侧：setup 在家侧执行时岛侧未加载无法就地放置/填充 → 由 tick_void 惰性处理
		setup_container_lines = "# 岛侧容器（输入/输出箱）由 tick_void 在区块加载时惰性放置并补满（setup 在家侧执行，岛侧未加载无法就地操作）"
	elif is_capacity:
		# CAPACITY：无容器/补货/虚空/移动维护；补货与清空计时器置为 2e9 使其在测试窗口内永不触发
		restock_timer_block = ""
		void_tick_line = ""
		void_status_block = ""
		void_setup_lines = ""
		setup_container_lines = ""
	else:
		restock_timer_block = "\n".join(
			[
				"scoreboard players add #t_restock autotrade_test 1",
				f"execute if score #t_restock autotrade_test matches {args.restock_seconds * 20}.. if loaded {layout.villager_block_x} {vy} {vz} run function autotrade_test:restock",
			]
		)
		void_tick_line = ""
		void_status_block = ""
		void_setup_lines = ""
		if is_moving:
			# MOVING v2（§5.2）：放 5 箱 + refill emerald 输入 + 装 wheat 输入 + 清空 P/B/G 三输出箱（坐标用占位符，渲染期替换）
			setup_container_lines = "\n".join(
				[
					"execute unless block {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} minecraft:chest run setblock {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} minecraft:chest",
					"execute unless block {{W_CHEST_X}} {{W_CHEST_Y}} {{W_CHEST_Z}} minecraft:chest run setblock {{W_CHEST_X}} {{W_CHEST_Y}} {{W_CHEST_Z}} minecraft:chest",
					"execute unless block {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} minecraft:chest run setblock {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} minecraft:chest",
					"execute unless block {{OUT2_CHEST_X}} {{OUT2_CHEST_Y}} {{OUT2_CHEST_Z}} minecraft:chest run setblock {{OUT2_CHEST_X}} {{OUT2_CHEST_Y}} {{OUT2_CHEST_Z}} minecraft:chest",
					"execute unless block {{OUT3_CHEST_X}} {{OUT3_CHEST_Y}} {{OUT3_CHEST_Z}} minecraft:chest run setblock {{OUT3_CHEST_X}} {{OUT3_CHEST_Y}} {{OUT3_CHEST_Z}} minecraft:chest",
					"function autotrade_test:refill_input",
					"data modify block {{W_CHEST_X}} {{W_CHEST_Y}} {{W_CHEST_Z}} Items set value [{{INPUT2_STACKS}}]",
					"data modify block {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} Items set value []",
					"data modify block {{OUT2_CHEST_X}} {{OUT2_CHEST_Y}} {{OUT2_CHEST_Z}} Items set value []",
					"data modify block {{OUT3_CHEST_X}} {{OUT3_CHEST_Y}} {{OUT3_CHEST_Z}} Items set value []",
				]
			)
		else:
			# STATIC 容器在家侧：放箱 + 装满输入 + 清空输出（坐标随布局）
			setup_container_lines = "\n".join(
				[
					f"execute unless block {ix} {iy} {iz} minecraft:chest run setblock {ix} {iy} {iz} minecraft:chest",
					f"execute unless block {ox} {oy} {oz} minecraft:chest run setblock {ox} {oy} {oz} minecraft:chest",
					"function autotrade_test:refill_input",
					f"data modify block {ox} {oy} {oz} Items set value []",
				]
			)
	# 村民召唤行 / 补货行：STATIC/VOID 单行（与既有输出逐字节相同）；MOVING 为 12 行；CAPACITY 全部置空
	if is_capacity:
		# CAPACITY 的村民与库存由 cap_<id> 用例函数逐组布置：setup 不召唤/不补货
		villager_summon_lines = ""
		restock_lines = ""
		moving_setup_lines = ""
		moving_tick_line = ""
		moving_status_block = ""
		moving_maint_block = ""
	elif is_moving:
		villager_summon_lines = build_moving_villager_summons(layout)
		restock_lines = build_moving_restock_lines()
		moving_setup_lines = build_moving_setup_lines()
		# 每 tick：玩家若已掉车则重新挂载最近的一辆装置矿车
		moving_tick_line = "execute as @a unless data entity @s RootVehicle run ride @s mount @e[type=minecraft:minecart,tag=att_cart,limit=1]"
		# 每 {{CLEAR_TICKS}} tick 触发一次 MOVING 维护（清空 book/glass、补满 wheat、复位 #t_maint）
		moving_status_block = "\n".join(
			[
				"scoreboard players add #t_maint autotrade_test 1",
				"execute if score #t_maint autotrade_test matches {{CLEAR_TICKS}}.. if loaded {{W_CHEST_X}} {{W_CHEST_Y}} {{W_CHEST_Z}} run function autotrade_test:moving_maintenance",
			]
		)
		moving_maint_block = build_moving_maintenance_block()
	else:
		villager_summon_lines = build_single_villager_summon(layout)
		restock_lines = build_single_restock_line()
		moving_setup_lines = ""
		moving_tick_line = ""
		moving_status_block = ""
		moving_maint_block = ""
	# VOID 专属坐标（值恒定）：STATIC 模板不引用，仍统一注册以保证「全部替换」
	ix2 = VOID_ISLAND_POS
	ib = VOID_ISLAND_BLOCK
	rc = VOID_RET_CHEST
	rp = VOID_RET_REPEATER
	# 就绪提示中的补货说明：STATIC/MOVING 保留；VOID/CAPACITY 不补货，提示置空
	restock_ready_hint = "" if (is_void or is_capacity) else f"村民每 {args.restock_seconds}s 补货；"
	# 就绪提示中的交易描述（§5.2）：STATIC/VOID 渲染结果与既有逐字节一致；MOVING/CAPACITY 各自描述
	ready_trade_desc = (
		"CAPACITY cases"
		if is_capacity
		else ("emerald/wheat → paper/book/glass" if is_moving else f"1 绿宝石 → 1 {args.output_item}")
	)
	# MOVING 会话上限固定 16（§2/§3）；STATIC/VOID/CAPACITY 用 --max-uses
	max_uses_value = MOVING_MAX_USES if is_moving else args.max_uses
	# 注意：结构性占位符的值内可能仍含嵌套占位符（如 {{OUTPUT_ITEM}}/{{MAX_USES}}/坐标），
	# 必须排在对应标量占位符之前，确保后续替换能命中。
	return {
		# —— 结构性（值内可能含嵌套占位符） ——
		"{{VILLAGER_SUMMON_LINES}}": villager_summon_lines,
		"{{RESTOCK_LINES}}": restock_lines,
		"{{SETUP_CONTAINER_LINES}}": setup_container_lines,
		"{{MOVING_MAINT_BLOCK}}": moving_maint_block,
		"{{MOVING_STATUS_BLOCK}}": moving_status_block,
		"{{MOVING_SETUP_LINES}}": moving_setup_lines,
		"{{MOVING_TICK_LINE}}": moving_tick_line,
		"{{RESTOCK_TIMER_BLOCK}}": restock_timer_block,
		"{{VOID_TICK_LINE}}": void_tick_line,
		"{{VOID_STATUS_BLOCK}}": void_status_block,
		"{{VOID_SETUP_LINES}}": void_setup_lines,
		"{{COUNT_SLOTS}}": "\n".join(count_slots_lines),
		# —— 标量 ——
		"{{RESTOCK_TICKS}}": str(args.restock_seconds * 20),
		# CAPACITY：周期补货/清空计时器拉满（2e9），使 refill_input/clear_output 在测试窗口内永不触发
		"{{CLEAR_TICKS}}": "2000000000" if is_capacity else str(args.clear_seconds * 20),
		"{{REFILL_TICKS}}": "2000000000" if is_capacity else str(args.refill_seconds * 20),
		"{{RESTOCK_SECONDS}}": str(args.restock_seconds),
		"{{RESTOCK_READY_HINT}}": restock_ready_hint,
		"{{READY_TRADE_DESC}}": ready_trade_desc,
		"{{CLEAR_SECONDS}}": str(args.clear_seconds),
		"{{MAX_USES}}": str(max_uses_value),
		"{{OUTPUT_ITEM}}": args.output_item,
		"{{VILLAGER_BLOCK_X}}": str(layout.villager_block_x),
		"{{VILLAGER_X}}": str(vx),
		"{{VILLAGER_Y}}": str(vy),
		"{{VILLAGER_Z}}": str(vz),
		"{{INPUT_CHEST_X}}": str(ix),
		"{{INPUT_CHEST_Y}}": str(iy),
		"{{INPUT_CHEST_Z}}": str(iz),
		"{{OUTPUT_CHEST_X}}": str(ox),
		"{{OUTPUT_CHEST_Y}}": str(oy),
		"{{OUTPUT_CHEST_Z}}": str(oz),
		"{{W_CHEST_X}}": str(wx),
		"{{W_CHEST_Y}}": str(wy),
		"{{W_CHEST_Z}}": str(wz),
		"{{OUT2_CHEST_X}}": str(bx),
		"{{OUT2_CHEST_Y}}": str(by),
		"{{OUT2_CHEST_Z}}": str(bz),
		"{{OUT3_CHEST_X}}": str(gx),
		"{{OUT3_CHEST_Y}}": str(gy),
		"{{OUT3_CHEST_Z}}": str(gz),
		"{{INPUT_STACKS}}": input_stacks,
		"{{INPUT2_STACKS}}": input2_stacks,
		"{{TELEPORT_DELAY_TICKS}}": str(int(round(args.teleport_delay_seconds * 20))),
		"{{HOME_TP_X}}": str(VOID_LAYOUT.player_pos[0]),
		"{{HOME_TP_Y}}": str(VOID_LAYOUT.player_pos[1]),
		"{{HOME_TP_Z}}": str(VOID_LAYOUT.player_pos[2]),
		"{{ISLAND_X}}": str(ix2[0]),
		"{{ISLAND_Y}}": str(ix2[1]),
		"{{ISLAND_Z}}": str(ix2[2]),
		"{{ISLAND_BLOCK_X}}": str(ib[0]),
		"{{ISLAND_BLOCK_Y}}": str(ib[1]),
		"{{ISLAND_BLOCK_Z}}": str(ib[2]),
		"{{RET_CHEST_X}}": str(rc[0]),
		"{{RET_CHEST_Y}}": str(rc[1]),
		"{{RET_CHEST_Z}}": str(rc[2]),
		"{{RET_RPT_X}}": str(rp[0]),
		"{{RET_RPT_Y}}": str(rp[1]),
		"{{RET_RPT_Z}}": str(rp[2]),
	}


def render_datapack(target_dir: Path, placeholders: dict[str, str], mode: str) -> int:
	"""把 datapack_src 整棵树渲染到目标目录（替换占位符）。

	跳过规则：static/moving/capacity 跳过 VOID 专属文件；static/void/capacity 跳过 MOVING 专属文件。
	"""
	skip: set[str] = set()
	if mode in ("static", "moving", "capacity"):
		skip |= set(VOID_ONLY_DATAPACK_FILES)
	if mode in ("static", "void", "capacity"):
		skip |= set(MOVING_ONLY_DATAPACK_FILES)
	count = 0
	for src in sorted(DATAPACK_SRC.rglob("*")):
		if not src.is_file():
			continue
		rel = src.relative_to(DATAPACK_SRC)
		if rel.as_posix() in skip:
			continue
		text = src.read_text(encoding="utf-8")
		for token, value in placeholders.items():
			text = text.replace(token, value)
		dst = target_dir / rel
		dst.parent.mkdir(parents=True, exist_ok=True)
		# 统一以 LF 写回、UTF-8 无 BOM
		dst.write_text(text, encoding="utf-8", newline="\n")
		count += 1
	return count


def _io_location(pos: tuple[int, int, int]) -> dict:
	"""单条 IO 记录（overworld + 坐标 + 启用）。"""
	return {"dimension": "minecraft:overworld", "x": pos[0], "y": pos[1], "z": pos[2], "enabled": True}


def _item_io_entry(item_id: str, is_input: bool, threshold: int, take_amount: int, pos: tuple[int, int, int]) -> dict:
	"""构造一条 itemIO 条目（item 为 JSON 编码串，与既有序列化风格一致）。"""
	return {
		"item": json.dumps({"id": item_id}, separators=(",", ":")),
		"isInput": is_input,
		"threshold": threshold,
		"takeAmount": take_amount,
		"enabled": True,
		"locations": [_io_location(pos)],
	}


def build_moving_trade_pairs() -> list[dict]:
	"""MOVING v2 三交易对（§3/§4）：T1 emerald→paper / T2 emerald→book / T3 emerald+wheat→glass。"""
	emerald = json.dumps({"id": "minecraft:emerald"}, separators=(",", ":"))
	wheat = json.dumps({"id": "minecraft:wheat"}, separators=(",", ":"))

	def pair(get_id: str, give2: str = "", give2_count: int = 0, note: str = "") -> dict:
		return {
			"give": emerald,
			"get": json.dumps({"id": get_id}, separators=(",", ":")),
			"limit": 64,
			"enabled": True,
			"give2": give2,
			"give2Count": give2_count,
			"getCount": 1,
			"note": note,
		}

	return [
		pair("minecraft:paper", note="testworld: T1 1 emerald -> 1 paper"),
		pair("minecraft:book", note="testworld: T2 1 emerald -> 1 book"),
		pair("minecraft:glass", give2=wheat, give2_count=1, note="testworld: T3 1 emerald + 1 wheat -> 1 glass"),
	]


def build_moving_item_io() -> list[dict]:
	"""MOVING v2 五 itemIO 条目（§4）：2 输入（emerald/wheat，阈值 4 / 取 8）+ 3 输出（paper/book/glass，阈值 1）。"""
	return [
		_item_io_entry("minecraft:emerald", True, 4, 8, _moving_container("E")),
		_item_io_entry("minecraft:wheat", True, 4, 8, _moving_container("W")),
		_item_io_entry("minecraft:paper", False, 1, 6, _moving_container("P")),
		_item_io_entry("minecraft:book", False, 1, 6, _moving_container("B")),
		_item_io_entry("minecraft:glass", False, 1, 6, _moving_container("G")),
	]


def build_test_config(args: argparse.Namespace) -> str:
	"""按模式构造测试 mod 配置 JSON 文本（STATIC 快节奏 2s 联动；VOID 用虚空参数；MOVING 用移动参数）。"""
	layout = LAYOUTS[args.mode]
	is_void = args.mode == "void"
	is_moving = args.mode == "moving"
	is_capacity = args.mode == "capacity"
	is_static = args.mode == "static"
	emerald = json.dumps({"id": "minecraft:emerald"}, separators=(",", ":"))
	output = json.dumps({"id": args.output_item}, separators=(",", ":"))
	ix, iy, iz = layout.input_chest
	ox, oy, oz = layout.output_chest
	if is_moving:
		# MOVING v2（§4）：3 交易对 + 5 itemIO（2 输入 / 3 输出）
		trade_pairs = build_moving_trade_pairs()
		item_io = build_moving_item_io()
	elif is_capacity:
		# CAPACITY：仅需 2 个交易对（物品匹配即可，pair 匹配与数量无关）；无容器 IO（itemIO 置空）
		paper = json.dumps({"id": "minecraft:paper"}, separators=(",", ":"))
		iron_sword = json.dumps({"id": "minecraft:iron_sword"}, separators=(",", ":"))
		trade_pairs = [
			{
				"give": emerald,
				"get": paper,
				"limit": 64,
				"enabled": True,
				"give2": "",
				"give2Count": 0,
				"getCount": 1,
				"note": "testworld: capacity 1 emerald -> 1 paper",
			},
			{
				"give": emerald,
				"get": iron_sword,
				"limit": 64,
				"enabled": True,
				"give2": "",
				"give2Count": 0,
				"getCount": 1,
				"note": "testworld: capacity 1 emerald -> 1 iron_sword",
			},
		]
		item_io = []
	else:
		# 输入条目：STATIC = 阈值 1 组 / 每次取 --take-amount 组；VOID = 阈值 8 组 / 每次取 6 组（岛侧无容器可达，需随身储备）
		input_entry = {
			"item": emerald,
			"isInput": True,
			"threshold": 1,
			"takeAmount": args.take_amount,
			"enabled": True,
			"locations": [
				{"dimension": "minecraft:overworld", "x": ix, "y": iy, "z": iz, "enabled": True}
			],
		}
		if is_void:
			input_entry["threshold"] = 8
			input_entry["takeAmount"] = 6
		# 输出条目：STATIC/VOID 相同（阈值 1 组 / 每次出 6 组），坐标随布局
		output_entry = {
			"item": output,
			"isInput": False,
			"threshold": 1,
			"takeAmount": 6,
			"enabled": True,
			"locations": [
				{"dimension": "minecraft:overworld", "x": ox, "y": oy, "z": oz, "enabled": True}
			],
		}
		trade_pairs = [
			{
				"give": emerald,
				"get": output,
				"limit": 64,
				"enabled": True,
				"give2": "",
				"give2Count": 0,
				"getCount": 1,
				"note": "testworld: 1 emerald -> 1 output item",
			}
		]
		item_io = [input_entry, output_entry]
	# VOID 返回触发点 = 岛侧陷阱箱；STATIC/MOVING 保持旧默认 "0 -60 0"
	rx, ry, rz = VOID_RET_CHEST
	void_return_pos = f"{rx} {ry} {rz}" if is_void else "0 -60 0"
	config = {
		"Generic": {
			"enabled": True,
			"tradeMode": "VOID" if is_void else ("MOVING" if is_moving else "STATIC"),
			"tradeExecutorMode": "USE",
			"villagerScanRange": 8,
			"openTimeout": 10,
			"taskTimeout": 400,
			"debugHud": True,
			"debugHudPosition": "top_right",
			"idleScanInterval": 5,
			"containerReach": 4,
			"outputMoveCap": 999,
			"tradeCacheTtl": 0 if is_capacity else 3000,
			# 跳过开窗时间（skipOpenTtl）：有匹配交易对但本会话无可执行交易（已耗尽 / 成本不足）的村民跳过开窗
			# 的复查间隔；STATIC 快节奏设为 40（= 1 轮 2s）→ 耗尽后下一轮即重试，配合 2s 补货验证刷新节奏；
			# 其他模式（VOID/MOVING）保持 100（= 1 轮 5s）；CAPACITY 置 0（每轮都重试，逐用例期望不被缓存跳过干扰）
			"skipOpenTtl": 0 if is_capacity else (40 if is_static else 100),
			"tradePairs": trade_pairs,
			"itemIO": item_io,
		},
		# STATIC 快节奏：restock 2s = tradeInterval 40t = skipOpenTtl 40t，三者联动，保证每轮恰逢补货，避免空转；
		# 其他模式保持原节奏（tradeInterval 100t = 5s）；containerIO 间隔不变
		"Static": {"tradeInterval": 40 if is_static else 100, "containerIOInterval": 10, "containerIOIdleInterval": 5},
		"Moving": {
			# MOVING 测试用 1.5（默认值）：扫描范围 8×1.5=12 < 同侧村民间距 24 / 对侧间距 36
			"movingRangeMultiplier": 1.5 if is_moving else 1.0,
			"movingInteractRange": 6.0,
			"movingStarvationAgingInterval": 100,
			"movingStarvationHintThreshold": 4,
		},
		"Void": {
			"voidTeleportTimeout": 200 if is_void else 100,
			"voidUnloadDelay": 40 if is_void else 10,
			"voidReturnType": "TRAPPED_CHEST",
			"voidReturnPos": void_return_pos,
			"voidReturnDim": "minecraft:overworld",
			"voidReturnStrict": True,
		},
		"Hotkeys": {
			"toggleTrading": {"keys": "KP_4"},
			"openGuiSettings": {"keys": "RIGHT_SHIFT,T"},
			"addTradePair": {"keys": "KP_5"},
			"grabContainerCoordinate": {"keys": "KP_6"},
		},
	}
	return json.dumps(config, indent=2, ensure_ascii=False)


def backup_config() -> Path | None:
	"""备份既有配置（若存在）；绝不覆盖已有备份。返回备份路径或 None。"""
	if not CONFIG_PATH.exists():
		return None
	stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
	base = CONFIG_PATH.with_name(f"{CONFIG_PATH.name}.bak-testworld-{stamp}")
	candidate = base
	index = 1
	while candidate.exists():
		candidate = base.with_name(base.name + f"-{index}")
		index += 1
	shutil.copy2(CONFIG_PATH, candidate)
	return candidate


def _check(results: list[tuple[str, bool, str]], name: str, ok: bool, detail: str = "") -> None:
	"""记录一条断言结果。"""
	results.append((name, bool(ok), detail))


def _verify_capacity_sync(results: list[tuple[str, bool, str]]) -> None:
	"""校验 `test/minescript/capacity_test.py` 的 `CAP_CASES` 与用例表 CASES 完全同步。

	用 ast 解析脚本源码并 literal_eval `CAP_CASES` 字面量，断言数量/顺序/fn 命名/pre/final/items。
	脚本缺失或不同步 → 记 FAIL 并给出首个不匹配的明确信息（字段 + 两侧值）。
	"""
	script_path = ROOT / "test" / "minescript" / "capacity_test.py"
	if not script_path.is_file():
		_check(results, "CAP_CASES 同步：脚本存在", False, str(script_path))
		return
	try:
		tree = ast.parse(script_path.read_text(encoding="utf-8"))
	except Exception as exc:  # noqa: BLE001
		_check(results, "CAP_CASES 同步：脚本可解析", False, f"{type(exc).__name__}: {exc}")
		return
	# 顶层查找 CAP_CASES = [...] 赋值并求值为纯 Python 字面量
	cap_cases = None
	for node in tree.body:
		if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "CAP_CASES" for t in node.targets):
			cap_cases = ast.literal_eval(node.value)
			break
	if cap_cases is None:
		_check(results, "CAP_CASES 同步：找到字面量赋值", False, "未找到 CAP_CASES 赋值")
		return
	expected_ids = [case.id for case in capacity_scenarios.CASES]
	actual_ids = [entry.get("id") for entry in cap_cases]
	_check(results, "CAP_CASES 条数 == 23", len(cap_cases) == len(capacity_scenarios.CASES), f"len={len(cap_cases)}")
	_check(results, "CAP_CASES id 顺序 a1..h2", actual_ids == expected_ids, f"{actual_ids}")
	if len(cap_cases) != len(capacity_scenarios.CASES):
		return
	mismatches: list[str] = []
	for case, entry in zip(capacity_scenarios.CASES, cap_cases):
		if entry.get("fn") != "cap_" + case.id:
			mismatches.append(f"{case.id}: fn={entry.get('fn')!r} expected=cap_{case.id}")
		pre = entry.get("pre") or {}
		if pre != case.pre_counts:
			mismatches.append(f"{case.id}: pre={pre} expected={case.pre_counts}")
		final = entry.get("final") or {}
		if final != case.expect_final:
			mismatches.append(f"{case.id}: final={final} expected={case.expect_final}")
		if entry.get("items") != case.expect_entities:
			mismatches.append(f"{case.id}: items={entry.get('items')!r} expected={case.expect_entities}")
	_check(
		results,
		"CAP_CASES 与用例表 pre/final/items 一致",
		not mismatches,
		"; ".join(mismatches[:5]),
	)


def verify(world_name: str, mode: str, restock_seconds: int | None = None) -> bool:
	"""只读校验生成结果（按模式选择布局与期望文件）；打印每条断言，返回是否全部通过。

	restock_seconds：调用方已解析的补货间隔（generate/--verify 传入；None 时跳过补货计时器断言，兼容外部旧调用）。
	"""
	results: list[tuple[str, bool, str]] = []
	layout = LAYOUTS[mode]
	world_dir = SAVES_DIR / world_name
	level_path = world_dir / "level.dat"

	_check(results, "level.dat 存在", level_path.exists(), str(level_path))
	if not level_path.exists():
		_print_results(results)
		return False

	doc = _nbt_load(level_path)
	root = doc
	data = root["Data"]
	_get = get_value

	_check(results, "Data.LevelName", _get(root, "Data.LevelName") == world_name, repr(_get(root, "Data.LevelName")))
	_check(results, "Data.GameType == 1", _get(root, "Data.GameType") == 1, str(_get(root, "Data.GameType")))
	_check(results, "Data.allowCommands == 1", _get(root, "Data.allowCommands") == 1, str(_get(root, "Data.allowCommands")))
	# 世界出生点两种模式均保持 (0,-60,0)（VOID 的玩家重生点由 Player.SpawnX/Y/Z 承担）
	_check(results, "Data.SpawnX == 0", _get(root, "Data.SpawnX") == 0, str(_get(root, "Data.SpawnX")))
	_check(results, "Data.SpawnY == -60", _get(root, "Data.SpawnY") == -60, str(_get(root, "Data.SpawnY")))
	_check(results, "Data.SpawnZ == 0", _get(root, "Data.SpawnZ") == 0, str(_get(root, "Data.SpawnZ")))
	_check(results, "Data.DayTime == 6000", _get(root, "Data.DayTime") == 6000, str(_get(root, "Data.DayTime")))
	_check(results, "Data.initialized == 1", _get(root, "Data.initialized") == 1, str(_get(root, "Data.initialized")))

	# 游戏规则
	for rule, expected in {
		"doMobSpawning": "false",
		"doDaylightCycle": "false",
		"doWeatherCycle": "false",
		"doTraderSpawning": "false",
		"spawnRadius": "0",
		"randomTickSpeed": "0",
	}.items():
		actual = _get(root, f"Data.GameRules.{rule}")
		_check(results, f"GameRules.{rule} == {expected}", actual == expected, repr(actual))

	# 世界生成
	overworld_gen = _get(root, 'Data.WorldGenSettings.dimensions.minecraft:overworld.generator')
	_check(results, "overworld generator.type == minecraft:flat", _get(overworld_gen, "type") == "minecraft:flat", repr(_get(overworld_gen, "type")))
	overrides = _get(overworld_gen, "settings.structure_overrides")
	_check(results, "structure_overrides 为空列表", isinstance(overrides, pynbt.TAG_List) and len(overrides) == 0, f"{type(overrides).__name__} len={len(overrides)}")
	layers = _get(overworld_gen, "settings.layers")
	_check(results, "layers 有 3 层", isinstance(layers, pynbt.TAG_List) and len(layers) == 3, f"len={len(layers)}")

	# 宿主玩家（位置/重生点随模式布局）
	player = data.get("Player")
	_check(results, "Data.Player 存在", isinstance(player, pynbt.TAG_Compound))
	if isinstance(player, pynbt.TAG_Compound):
		_check(results, "Player.Dimension == minecraft:overworld", _get(player, "Dimension") == "minecraft:overworld", repr(_get(player, "Dimension")))
		pos = [float(x.value) for x in _get(player, "Pos")]
		expected_pos = [float(v) for v in layout.player_pos]
		_check(results, f"Player.Pos == {expected_pos}", pos == expected_pos, str(pos))
		inv = _get(player, "Inventory")
		_check(results, "Player.Inventory 为空", isinstance(inv, pynbt.TAG_List) and len(inv) == 0, f"len={len(inv)}")
		_check(results, "Player.playerGameType == 1", _get(player, "playerGameType") == 1, str(_get(player, "playerGameType")))
		spawn = layout.player_spawn
		_check(results, f"Player.SpawnX == {spawn[0]}", _get(player, "SpawnX") == spawn[0], str(_get(player, "SpawnX")))
		_check(results, f"Player.SpawnY == {spawn[1]}", _get(player, "SpawnY") == spawn[1], str(_get(player, "SpawnY")))
		_check(results, f"Player.SpawnZ == {spawn[2]}", _get(player, "SpawnZ") == spawn[2], str(_get(player, "SpawnZ")))

	# datapack 文件（按模式期望清单）
	dp_dir = world_dir / "datapacks" / "autotrade_test"
	for rel in EXPECTED_DATAPACK_FILES[mode]:
		_check(results, f"datapack 存在 {rel}", (dp_dir / rel).is_file(), str(dp_dir / rel))
	# VOID 专属文件：仅 void 渲染；static/moving/capacity 必须全部缺失
	if mode != "void":
		for rel in VOID_ONLY_DATAPACK_FILES:
			_check(results, f"{mode.upper()} 未渲染 VOID 文件 {rel}", not (dp_dir / rel).exists(), str(dp_dir / rel))
	# MOVING 专属文件：仅 moving 渲染；static/void/capacity 必须全部缺失
	if mode == "moving":
		for rel in MOVING_ONLY_DATAPACK_FILES:
			_check(results, f"MOVING 含专属文件 {rel}", (dp_dir / rel).is_file(), str(dp_dir / rel))
	else:
		for rel in MOVING_ONLY_DATAPACK_FILES:
			_check(results, f"{mode.upper()} 未渲染 MOVING 文件 {rel}", not (dp_dir / rel).exists(), str(dp_dir / rel))

	if mode == "static":
		# —— STATIC 快节奏配置断言：tradeInterval 40t = skipOpenTtl 40t（= 默认补货 2s 一轮，三者联动）——
		if CONFIG_PATH.is_file():
			static_cfg: dict = {}
			generic_cfg: dict = {}
			try:
				cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
				static_cfg = cfg.get("Static", {})
				generic_cfg = cfg.get("Generic", {})
				_check(results, "配置 JSON 可解析", True, "")
			except Exception as exc:  # noqa: BLE001
				_check(results, "配置 JSON 可解析", False, f"{type(exc).__name__}: {exc}")
			_check(
				results,
				"配置 Static.tradeInterval == 40",
				static_cfg.get("tradeInterval") == 40,
				repr(static_cfg.get("tradeInterval")),
			)
			_check(
				results,
				"配置 skipOpenTtl == 40",
				generic_cfg.get("skipOpenTtl") == 40,
				repr(generic_cfg.get("skipOpenTtl")),
			)
			if restock_seconds is not None:
				# datapack 补货计时器与调用方解析值联动（STATIC 默认 2s → matches 40..）
				tick_path = dp_dir / "data" / "autotrade_test" / "functions" / "tick_periodic.mcfunction"
				if tick_path.is_file():
					tick_text = tick_path.read_text(encoding="utf-8")
					_check(
						results,
						f"补货计时器 #t_restock matches {restock_seconds * 20}..",
						f"#t_restock autotrade_test matches {restock_seconds * 20}.." in tick_text,
						"",
					)
				else:
					_check(results, "tick_periodic.mcfunction 存在", False, str(tick_path))
		else:
			_check(results, "配置文件存在", False, str(CONFIG_PATH))

	if mode == "capacity":
		# —— CAPACITY 专属：23 个场景函数内容 + 配置 JSON + 表↔脚本同步 ——
		func_dir = dp_dir / "data" / "autotrade_test" / "functions"
		px, py, pz = CAPACITY_LAYOUT.player_pos
		for case in capacity_scenarios.CASES:
			rel = f"cap_{case.id}.mcfunction"
			path = func_dir / rel
			exists = path.is_file()
			_check(results, f"cap 函数存在 {rel}", exists, str(path))
			if not exists:
				continue
			text = path.read_text(encoding="utf-8")
			offer = case.offer
			_check(results, f"{rel} 含传送到站台", f"tp @s {px} {py} {pz}" in text, "")
			_check(results, f"{rel} 含 kill item 行", "kill @e[type=minecraft:item]" in text, "")
			_check(results, f"{rel} 含 kill 旧村民行", "kill @e[type=minecraft:villager,tag=autotrade_cap]" in text, "")
			_check(results, f"{rel} 含 clear @s", "clear @s" in text, "")
			_check(results, f"{rel} 含 maxUses:{offer.max_uses}", f"maxUses:{offer.max_uses}" in text, "")
			_check(
				results,
				f"{rel} 含 sell Count:{offer.sell_count}b",
				f'sell:{{id:"{offer.sell_item}",Count:{offer.sell_count}b}}' in text,
				"",
			)
			_check(
				results,
				f"{rel} 含 summon 标签 cap_{case.id}",
				f'Tags:["autotrade_cap","cap_{case.id}"]' in text,
				"",
			)
		cap_files = list(func_dir.glob("cap_*.mcfunction"))
		_check(results, "cap 函数数量 == 23", len(cap_files) == len(capacity_scenarios.CASES), f"{len(cap_files)}")
		# 配置 JSON 断言（mod 配置由 generate 写入 run/config/autotrade.json）
		if CONFIG_PATH.is_file():
			generic = None
			try:
				cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
				generic = cfg.get("Generic", {})
				_check(results, "配置 JSON 可解析", True, "")
			except Exception as exc:  # noqa: BLE001
				_check(results, "配置 JSON 可解析", False, f"{type(exc).__name__}: {exc}")
			if generic is not None:
				_check(results, "配置 tradeMode == STATIC", generic.get("tradeMode") == "STATIC", repr(generic.get("tradeMode")))
				_check(results, "配置 tradeCacheTtl == 0", generic.get("tradeCacheTtl") == 0, repr(generic.get("tradeCacheTtl")))
				_check(results, "配置 skipOpenTtl == 0", generic.get("skipOpenTtl") == 0, repr(generic.get("skipOpenTtl")))
				pairs = generic.get("tradePairs")
				pairs = pairs if isinstance(pairs, list) else []
				_check(results, "配置 tradePairs 数量 == 2", len(pairs) == 2, f"len={len(pairs)}")
				get_ids: list = []
				for entry in pairs:
					try:
						get_ids.append(json.loads(entry.get("get", "{}")).get("id"))
					except Exception:  # noqa: BLE001
						get_ids.append(None)
				_check(
					results,
					"配置 tradePairs get ids == paper+iron_sword",
					get_ids == ["minecraft:paper", "minecraft:iron_sword"],
					str(get_ids),
				)
				_check(results, "配置 itemIO 为空列表", generic.get("itemIO") == [], repr(generic.get("itemIO")))
		else:
			_check(results, "配置文件存在", False, str(CONFIG_PATH))
		# 表↔脚本同步检查（脚本缺失或 CAP_CASES 分歧 → FAIL）
		_verify_capacity_sync(results)

	# 全量扫描：JSON 可解析 + 任何文件都不得残留占位符
	if dp_dir.is_dir():
		for path in sorted(dp_dir.rglob("*")):
			if not path.is_file():
				continue
			rel = path.relative_to(dp_dir).as_posix()
			text = path.read_text(encoding="utf-8")
			if path.suffix in (".json", ".mcmeta"):
				try:
					json.loads(text)
					_check(results, f"JSON 可解析 {rel}", True, "")
				except Exception as exc:  # noqa: BLE001
					_check(results, f"JSON 可解析 {rel}", False, str(exc))
			_check(results, f"无残留占位符 {rel}", "{{" not in text, "")
	else:
		_check(results, "datapack 目录存在", False, str(dp_dir))

	_print_results(results)
	return all(ok for _, ok, _ in results)


def _print_results(results: list[tuple[str, bool, str]]) -> None:
	"""打印断言表。"""
	print("---- 校验结果 ----")
	for name, ok, detail in results:
		status = "PASS" if ok else "FAIL"
		suffix = f"  [{detail}]" if (detail and not ok) else ""
		print(f"[{status}] {name}{suffix}")
	passed = sum(1 for _, ok, _ in results if ok)
	print(f"---- {passed}/{len(results)} 通过 ----")


def generate(args: argparse.Namespace) -> bool:
	"""默认模式：派生世界 + 渲染 datapack + 写测试配置，随后校验。"""
	layout = LAYOUTS[args.mode]
	world_dir = SAVES_DIR / args.world_name
	template_level = SAVES_DIR / args.template_world / "level.dat"
	if not template_level.is_file():
		print(f"错误：模板世界 level.dat 不存在：{template_level}")
		return False

	if args.fresh and world_dir.exists():
		print(f"删除已存在的目标世界：{world_dir}")
		shutil.rmtree(world_dir)

	# 1) 派生 level.dat（绝不修改模板）
	doc = _nbt_load(template_level)
	patch_level_dat(doc, args.world_name, layout)
	world_dir.mkdir(parents=True, exist_ok=True)
	_nbt_save(doc, world_dir / "level.dat")

	# 2) 渲染 datapack（static/moving/capacity 跳过 VOID 专属文件；static/void/capacity 跳过 MOVING 专属文件）
	placeholders = build_placeholders(args)
	rendered = render_datapack(world_dir / "datapacks" / "autotrade_test", placeholders, args.mode)

	# 2b) CAPACITY：渲染后写入 23 个 cap_<id>.mcfunction，并导出用例表（人读 md + 机读 json）到证据目录
	if args.mode == "capacity":
		func_dir = world_dir / "datapacks" / "autotrade_test" / "data" / "autotrade_test" / "functions"
		func_dir.mkdir(parents=True, exist_ok=True)
		for case in capacity_scenarios.CASES:
			(func_dir / f"cap_{case.id}.mcfunction").write_text(
				build_capacity_case_function(case), encoding="utf-8", newline="\n"
			)
		evidence_dir = ROOT.parent / ".omo" / "evidence" / "capacity-detection-testworld"
		evidence_dir.mkdir(parents=True, exist_ok=True)
		capacity_scenarios.export_table(
			md_path=evidence_dir / "case-table.md",
			json_path=evidence_dir / "case-table.json",
		)
		rendered += len(capacity_scenarios.CASES)
		print(f"CAPACITY 场景函数: 已生成 {len(capacity_scenarios.CASES)} 个 cap_<id>.mcfunction")
		print(f"用例表导出:   {evidence_dir / 'case-table.md'} / {evidence_dir / 'case-table.json'}")

	# 3) 备份并写测试配置
	backup_path = None
	if not args.skip_config:
		backup_path = backup_config()
		CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
		CONFIG_PATH.write_text(build_test_config(args), encoding="utf-8", newline="\n")

	# 4) 摘要
	print("---- 生成摘要 ----")
	print(f"装置模式:     {args.mode}")
	print(f"世界路径:     {world_dir}")
	print(f"datapack 路径: {world_dir / 'datapacks' / 'autotrade_test'}（渲染 {rendered} 个文件）")
	if args.mode == "void":
		print(f"传送延迟:     {args.teleport_delay_seconds}s（{int(round(args.teleport_delay_seconds * 20))} tick）")
	if args.skip_config:
		print("测试配置:     已跳过（--skip-config）")
	else:
		print(f"测试配置:     {CONFIG_PATH}")
		print(f"配置备份:     {backup_path if backup_path else '（原配置不存在，无备份）'}")
	print("手动启动（由用户执行，agent 不得启动游戏）：")
	print(f'  cd "{ROOT}"')
	print(f'  .\\gradlew --no-daemon runClient --args="--quickPlaySingleplayer {args.world_name}"')

	# 5) 校验
	return verify(args.world_name, args.mode, args.restock_seconds)


def resolve_world_name(args: argparse.Namespace) -> str:
	"""世界名：显式 --world-name 优先，否则按模式取默认名。"""
	return args.world_name or DEFAULT_WORLD_NAMES[args.mode]


def main(argv: list[str]) -> int:
	"""解析参数并执行。"""
	parser = argparse.ArgumentParser(description="AutoTrade 测试世界生成器（不启动游戏）")
	parser.add_argument("--mode", choices=("static", "void", "moving", "capacity"), default="static", help="装置模式（默认 static）")
	parser.add_argument(
		"--world-name",
		default=None,
		help="世界名（默认 static=AutoTradeTest / void=AutoTradeVoidTest / moving=AutoTradeMovingTest / capacity=AutoTradeCapacityTest）",
	)
	parser.add_argument("--template-world", default="New World")
	# 补货间隔默认按模式解析（见 main）：STATIC 快节奏 2s（= tradeInterval 40t），其他模式 5s（= 100t）；
	# 耗尽后下一轮即补货，避免出现空过轮次
	parser.add_argument("--restock-seconds", type=int, default=None, help="村民补货间隔秒数（默认 STATIC=2，其他模式=5）")
	parser.add_argument("--clear-seconds", type=int, default=10)
	parser.add_argument("--refill-seconds", type=int, default=20)
	parser.add_argument("--max-uses", type=int, default=64)
	parser.add_argument("--output-item", default="minecraft:paper")
	parser.add_argument("--take-amount", type=int, default=1)
	# VOID：互动后延迟传送至岛侧的秒数（可小数；TELEPORT_DELAY_TICKS = 秒 × 20）
	parser.add_argument("--teleport-delay-seconds", type=float, default=0.5)
	parser.add_argument("--skip-config", action="store_true")
	parser.add_argument("--fresh", action="store_true")
	parser.add_argument("--verify", action="store_true")
	args = parser.parse_args(argv)
	# 未显式指定补货间隔时按模式解析默认值：STATIC=2（快节奏，与 tradeInterval/skipOpenTtl 40t 联动），其他模式=5
	if args.restock_seconds is None:
		args.restock_seconds = 2 if args.mode == "static" else 5
	args.world_name = resolve_world_name(args)

	if args.verify:
		return 0 if verify(args.world_name, args.mode, args.restock_seconds) else 1
	return 0 if generate(args) else 1


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
