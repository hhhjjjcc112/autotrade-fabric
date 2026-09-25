#!/usr/bin/env python3
"""AutoTrade 测试世界生成器。

从只读模板世界（默认 `New World`）派生一个固定布局的超平坦测试世界，
并渲染 datapack（固定交易的村民 + 输入/输出箱 + 周期补货/补满/清空统计），
同时写入一份测试用 mod 配置。

两种装置模式：
	static（默认）：STATIC 静止交易装置，世界默认 `AutoTradeTest`
	void：VOID 虚空交易装置（家侧交易点 + 岛侧着陆点 + 陷阱箱/中继器返航机关），
	      世界默认 `AutoTradeVoidTest`

用法：
	python tools/testworld/setup_testworld.py                       # static：生成世界 + 备份并写测试配置 + 校验
	python tools/testworld/setup_testworld.py --verify              # 仅校验（默认 static 世界）
	python tools/testworld/setup_testworld.py --mode void           # VOID：生成 AutoTradeVoidTest + VOID 配置
	python tools/testworld/setup_testworld.py --mode void --verify  # 仅校验 VOID 世界
	python tools/testworld/setup_testworld.py --skip-config
	python tools/testworld/setup_testworld.py --fresh

说明：脚本从仓库根目录（autotrade-fabric/）运行；脚本会自行定位仓库根目录，任意 cwd 均可。

模式相关参数：
	--mode {static,void}      装置模式（默认 static）
	--teleport-delay-seconds  仅 VOID：互动后延迟多少秒传送玩家至岛侧（默认 0.5 → TELEPORT_DELAY_TICKS=10）
	--world-name              覆盖默认世界名（static=AutoTradeTest / void=AutoTradeVoidTest）

注意：本脚本绝不启动 Minecraft；游戏内验证由用户手动执行。
"""

from __future__ import annotations

import argparse
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

# 复用同目录下的最小 NBT 库
sys.path.insert(0, str(Path(__file__).resolve().parent))
import nbt_min as nbt  # noqa: E402

# 仓库根目录（autotrade-fabric/）：<repo>/tools/testworld/setup_testworld.py -> parents[2]
ROOT = Path(__file__).resolve().parents[2]
RUN_DIR = ROOT / "run"
SAVES_DIR = RUN_DIR / "saves"
CONFIG_PATH = RUN_DIR / "config" / "autotrade.json"
DATAPACK_SRC = Path(__file__).resolve().parent / "datapack_src"

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
LAYOUTS = {"static": STATIC_LAYOUT, "void": VOID_LAYOUT}
# 默认世界名（--world-name 可显式覆盖）
DEFAULT_WORLD_NAMES = {"static": "AutoTradeTest", "void": "AutoTradeVoidTest"}

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

# 期望生成的 datapack 相对路径（按模式；用于校验）
EXPECTED_DATAPACK_FILES = {
	"static": SHARED_DATAPACK_FILES,
	"void": SHARED_DATAPACK_FILES + VOID_ONLY_DATAPACK_FILES,
}


def _set(compound: nbt.Compound, key: str, value) -> None:
	"""在 Compound 上设置键（保持既有键顺序，新键追加到末尾）。"""
	compound[key] = value


def patch_level_dat(doc: nbt.Document, world_name: str, layout: Layout) -> None:
	"""按测试世界需求就地补丁 level.dat 的 Data（及可选的 Data.Player）；坐标随模式布局。"""
	data = doc.root["Data"]

	# 基础世界元数据
	_set(data, "LevelName", nbt.String(world_name))
	_set(data, "GameType", nbt.Int(1))  # 创造模式（村民交易仍会消耗成本物品）
	_set(data, "Difficulty", nbt.Byte(0))  # 和平
	_set(data, "allowCommands", nbt.Byte(1))
	_set(data, "hardcore", nbt.Byte(0))
	_set(data, "initialized", nbt.Byte(1))

	# 出生点：地面 (0, -60, 0)
	_set(data, "SpawnX", nbt.Int(0))
	_set(data, "SpawnY", nbt.Int(-60))
	_set(data, "SpawnZ", nbt.Int(0))
	_set(data, "SpawnAngle", nbt.Float(0.0))

	# 时间固定为白天正午
	_set(data, "Time", nbt.Long(6000))
	_set(data, "DayTime", nbt.Long(6000))

	# 天气固定晴朗
	_set(data, "raining", nbt.Byte(0))
	_set(data, "thundering", nbt.Byte(0))
	_set(data, "clearWeatherTime", nbt.Int(1000000))
	_set(data, "rainTime", nbt.Int(1000000))
	_set(data, "thunderTime", nbt.Int(1000000))

	# 游戏规则（值均为字符串）
	rules = data.get("GameRules")
	if not isinstance(rules, nbt.Compound):
		rules = nbt.Compound()
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
		_set(rules, key, nbt.String(value))

	# 世界生成：固定种子 + 超平坦（三层）+ 无结构
	wgs = data["WorldGenSettings"]
	_set(wgs, "seed", nbt.Long(12345))
	_set(wgs, "generate_features", nbt.Byte(0))
	generator = nbt.Compound()
	generator["type"] = nbt.String("minecraft:flat")
	settings = nbt.Compound()
	settings["layers"] = nbt.List(
		[
			nbt.Compound({"block": nbt.String("minecraft:bedrock"), "height": nbt.Int(1)}),
			nbt.Compound({"block": nbt.String("minecraft:dirt"), "height": nbt.Int(2)}),
			nbt.Compound({"block": nbt.String("minecraft:grass_block"), "height": nbt.Int(1)}),
		],
		nbt.TAG_COMPOUND,
	)
	settings["biome"] = nbt.String("minecraft:plains")
	settings["features"] = nbt.Byte(0)
	settings["lakes"] = nbt.Byte(0)
	settings["structure_overrides"] = nbt.List([], nbt.TAG_END)
	generator["settings"] = settings
	wgs["dimensions"]["minecraft:overworld"]["generator"] = generator

	# 宿主玩家：单人模式加载 level.dat 中的 Player 状态（位置/背包/游戏模式）
	player = data.get("Player")
	if isinstance(player, nbt.Compound):
		# 去掉模板 UUID：让主机玩家沿用自身档案 UUID，避免身份键（统计/进度）不一致
		player.pop("UUID", None)
		_set(player, "Pos", nbt.List([nbt.Double(v) for v in layout.player_pos], nbt.TAG_DOUBLE))
		_set(player, "Dimension", nbt.String("minecraft:overworld"))
		_set(player, "Rotation", nbt.List([nbt.Float(0.0), nbt.Float(0.0)], nbt.TAG_FLOAT))
		_set(player, "Motion", nbt.List([nbt.Double(0.0), nbt.Double(-0.0784000015258789), nbt.Double(0.0)], nbt.TAG_DOUBLE))
		_set(player, "playerGameType", nbt.Int(1))
		_set(player, "Health", nbt.Float(20.0))
		_set(player, "foodLevel", nbt.Int(20))
		_set(player, "foodSaturationLevel", nbt.Float(5.0))
		_set(player, "foodExhaustionLevel", nbt.Float(0.0))
		_set(player, "foodTickTimer", nbt.Int(0))
		_set(player, "Inventory", nbt.List([], nbt.TAG_END))
		_set(player, "EnderItems", nbt.List([], nbt.TAG_END))
		_set(player, "XpLevel", nbt.Int(0))
		_set(player, "XpTotal", nbt.Int(0))
		_set(player, "XpP", nbt.Float(0.0))
		_set(player, "Score", nbt.Int(0))
		_set(player, "XpSeed", nbt.Int(0))
		_set(player, "SelectedItemSlot", nbt.Int(0))
		_set(player, "SpawnX", nbt.Int(layout.player_spawn[0]))
		_set(player, "SpawnY", nbt.Int(layout.player_spawn[1]))
		_set(player, "SpawnZ", nbt.Int(layout.player_spawn[2]))
		_set(player, "SpawnAngle", nbt.Float(0.0))
		_set(player, "SpawnDimension", nbt.String("minecraft:overworld"))
		_set(player, "HurtTime", nbt.Short(0))
		_set(player, "DeathTime", nbt.Short(0))
		_set(player, "HurtByTimestamp", nbt.Int(0))
		_set(player, "Fire", nbt.Short(-20))
		_set(player, "Air", nbt.Short(300))
		_set(player, "PortalCooldown", nbt.Int(0))
		_set(player, "SleepTimer", nbt.Short(0))
		_set(player, "FallDistance", nbt.Float(0.0))
		_set(player, "OnGround", nbt.Byte(1))
		_set(player, "seenCredits", nbt.Byte(0))
		abilities = nbt.Compound()
		abilities["invulnerable"] = nbt.Byte(1)
		abilities["mayfly"] = nbt.Byte(1)
		abilities["instabuild"] = nbt.Byte(1)
		abilities["mayBuild"] = nbt.Byte(1)
		abilities["flying"] = nbt.Byte(0)
		abilities["walkSpeed"] = nbt.Float(0.1)
		abilities["flySpeed"] = nbt.Float(0.05)
		_set(player, "abilities", abilities)
	else:
		print("警告：模板 level.dat 中不存在 Data.Player，跳过玩家状态补丁（不视为失败）")


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


def build_placeholders(args: argparse.Namespace) -> dict[str, str]:
	"""计算 datapack 模板占位符 -> 文本值（STATIC/VOID 分支：周期任务与虚空驱动行不同）。"""
	layout = LAYOUTS[args.mode]
	input_stacks = ", ".join(
		f'{{Slot:{slot}b,id:"minecraft:emerald",Count:64b}}' for slot in range(27)
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
	# 周期任务占位符：STATIC 保留村民补货计时器两行；VOID 置空补货并追加虚空驱动 + 状态打印
	if args.mode == "void":
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
		# STATIC 容器在家侧：保持既有行为（放箱 + 装满输入 + 清空输出）
		setup_container_lines = "\n".join(
			[
				f"execute unless block {ix} {iy} {iz} minecraft:chest run setblock {ix} {iy} {iz} minecraft:chest",
				f"execute unless block {ox} {oy} {oz} minecraft:chest run setblock {ox} {oy} {oz} minecraft:chest",
				"function autotrade_test:refill_input",
				f"data modify block {ox} {oy} {oz} Items set value []",
			]
		)
	# VOID 专属坐标（值恒定）：STATIC 模板不引用，仍统一注册以保证「全部替换」
	ix2 = VOID_ISLAND_POS
	ib = VOID_ISLAND_BLOCK
	rc = VOID_RET_CHEST
	rp = VOID_RET_REPEATER
	# 就绪提示中的补货说明：STATIC 保留；VOID 不补货（补货会掩盖「无限交易」判定），提示置空
	restock_ready_hint = "" if args.mode == "void" else f"村民每 {args.restock_seconds}s 补货；"
	return {
		"{{RESTOCK_TICKS}}": str(args.restock_seconds * 20),
		"{{CLEAR_TICKS}}": str(args.clear_seconds * 20),
		"{{REFILL_TICKS}}": str(args.refill_seconds * 20),
		"{{RESTOCK_SECONDS}}": str(args.restock_seconds),
		"{{RESTOCK_READY_HINT}}": restock_ready_hint,
		"{{CLEAR_SECONDS}}": str(args.clear_seconds),
		"{{MAX_USES}}": str(args.max_uses),
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
		"{{INPUT_STACKS}}": input_stacks,
		"{{COUNT_SLOTS}}": "\n".join(count_slots_lines),
		"{{RESTOCK_TIMER_BLOCK}}": restock_timer_block,
		"{{VOID_TICK_LINE}}": void_tick_line,
		"{{VOID_STATUS_BLOCK}}": void_status_block,
		"{{VOID_SETUP_LINES}}": void_setup_lines,
		"{{SETUP_CONTAINER_LINES}}": setup_container_lines,
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
	"""把 datapack_src 整棵树渲染到目标目录（替换占位符）；STATIC 按跳过列表排除 VOID 专属文件。"""
	skip = set(VOID_ONLY_DATAPACK_FILES) if mode == "static" else set()
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


def build_test_config(args: argparse.Namespace) -> str:
	"""按模式构造测试 mod 配置 JSON 文本（STATIC 语义不变；VOID 使用虚空交易参数）。"""
	layout = LAYOUTS[args.mode]
	is_void = args.mode == "void"
	emerald = json.dumps({"id": "minecraft:emerald"}, separators=(",", ":"))
	output = json.dumps({"id": args.output_item}, separators=(",", ":"))
	ix, iy, iz = layout.input_chest
	ox, oy, oz = layout.output_chest
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
	# 输出条目：两种模式相同（阈值 1 组 / 每次出 6 组），坐标随布局
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
	# VOID 返回触发点 = 岛侧陷阱箱；STATIC 保持旧默认 "0 -60 0"
	rx, ry, rz = VOID_RET_CHEST
	void_return_pos = f"{rx} {ry} {rz}" if is_void else "0 -60 0"
	config = {
		"Generic": {
			"enabled": True,
			"tradeMode": "VOID" if is_void else "STATIC",
			"tradeExecutorMode": "USE",
			"villagerScanRange": 8,
			"openTimeout": 10,
			"taskTimeout": 400,
			"debugHud": True,
			"debugHudPosition": "top_right",
			"idleScanInterval": 5,
			"containerReach": 4,
			"outputMoveCap": 999,
			"tradeCacheTtl": 3000,
			# 跳过开窗时间（skipOpenTtl）：有匹配交易对但本会话无可执行交易（已耗尽 / 成本不足）的村民跳过开窗
			# 的复查间隔；测试设为 100（= 1 轮 5s）→ 耗尽后下一轮即重试，配合 5s 补货验证刷新节奏
			"skipOpenTtl": 100,
			"tradePairs": [
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
			],
			"itemIO": [input_entry, output_entry],
		},
		"Static": {"tradeInterval": 100, "containerIOInterval": 10, "containerIOIdleInterval": 5},
		"Moving": {
			"movingRangeMultiplier": 1.0,
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


def verify(world_name: str, mode: str) -> bool:
	"""只读校验生成结果（按模式选择布局与期望文件）；打印每条断言，返回是否全部通过。"""
	results: list[tuple[str, bool, str]] = []
	layout = LAYOUTS[mode]
	world_dir = SAVES_DIR / world_name
	level_path = world_dir / "level.dat"

	_check(results, "level.dat 存在", level_path.exists(), str(level_path))
	if not level_path.exists():
		_print_results(results)
		return False

	doc = nbt.load(level_path)
	data = doc.root["Data"]
	_get = nbt.get_path

	_check(results, "Data.LevelName", _get(doc.root, "Data.LevelName") == world_name, repr(_get(doc.root, "Data.LevelName")))
	_check(results, "Data.GameType == 1", _get(doc.root, "Data.GameType") == 1, str(_get(doc.root, "Data.GameType")))
	_check(results, "Data.allowCommands == 1", _get(doc.root, "Data.allowCommands") == 1, str(_get(doc.root, "Data.allowCommands")))
	# 世界出生点两种模式均保持 (0,-60,0)（VOID 的玩家重生点由 Player.SpawnX/Y/Z 承担）
	_check(results, "Data.SpawnX == 0", _get(doc.root, "Data.SpawnX") == 0, str(_get(doc.root, "Data.SpawnX")))
	_check(results, "Data.SpawnY == -60", _get(doc.root, "Data.SpawnY") == -60, str(_get(doc.root, "Data.SpawnY")))
	_check(results, "Data.SpawnZ == 0", _get(doc.root, "Data.SpawnZ") == 0, str(_get(doc.root, "Data.SpawnZ")))
	_check(results, "Data.DayTime == 6000", _get(doc.root, "Data.DayTime") == 6000, str(_get(doc.root, "Data.DayTime")))
	_check(results, "Data.initialized == 1", _get(doc.root, "Data.initialized") == 1, str(_get(doc.root, "Data.initialized")))

	# 游戏规则
	for rule, expected in {
		"doMobSpawning": "false",
		"doDaylightCycle": "false",
		"doWeatherCycle": "false",
		"doTraderSpawning": "false",
		"spawnRadius": "0",
		"randomTickSpeed": "0",
	}.items():
		actual = _get(doc.root, f"Data.GameRules.{rule}")
		_check(results, f"GameRules.{rule} == {expected}", actual == expected, repr(actual))

	# 世界生成
	overworld_gen = _get(doc.root, 'Data.WorldGenSettings.dimensions.minecraft:overworld.generator')
	_check(results, "overworld generator.type == minecraft:flat", _get(overworld_gen, "type") == "minecraft:flat", repr(_get(overworld_gen, "type")))
	overrides = _get(overworld_gen, "settings.structure_overrides")
	_check(results, "structure_overrides 为空列表", isinstance(overrides, nbt.List) and len(overrides) == 0, f"{type(overrides).__name__} len={len(overrides)}")
	layers = _get(overworld_gen, "settings.layers")
	_check(results, "layers 有 3 层", isinstance(layers, nbt.List) and len(layers) == 3, f"len={len(layers)}")

	# 宿主玩家（位置/重生点随模式布局）
	player = data.get("Player")
	_check(results, "Data.Player 存在", isinstance(player, nbt.Compound))
	if isinstance(player, nbt.Compound):
		_check(results, "Player.Dimension == minecraft:overworld", _get(player, "Dimension") == "minecraft:overworld", repr(_get(player, "Dimension")))
		pos = [float(x) for x in _get(player, "Pos")]
		expected_pos = [float(v) for v in layout.player_pos]
		_check(results, f"Player.Pos == {expected_pos}", pos == expected_pos, str(pos))
		inv = _get(player, "Inventory")
		_check(results, "Player.Inventory 为空", isinstance(inv, nbt.List) and len(inv) == 0, f"len={len(inv)}")
		_check(results, "Player.playerGameType == 1", _get(player, "playerGameType") == 1, str(_get(player, "playerGameType")))
		spawn = layout.player_spawn
		_check(results, f"Player.SpawnX == {spawn[0]}", _get(player, "SpawnX") == spawn[0], str(_get(player, "SpawnX")))
		_check(results, f"Player.SpawnY == {spawn[1]}", _get(player, "SpawnY") == spawn[1], str(_get(player, "SpawnY")))
		_check(results, f"Player.SpawnZ == {spawn[2]}", _get(player, "SpawnZ") == spawn[2], str(_get(player, "SpawnZ")))

	# datapack 文件（按模式期望清单）
	dp_dir = world_dir / "datapacks" / "autotrade_test"
	for rel in EXPECTED_DATAPACK_FILES[mode]:
		_check(results, f"datapack 存在 {rel}", (dp_dir / rel).is_file(), str(dp_dir / rel))
	if mode == "static":
		# STATIC 渲染必须跳过 VOID 专属文件（保证既有输出不变）
		for rel in VOID_ONLY_DATAPACK_FILES:
			_check(results, f"STATIC 未渲染 VOID 文件 {rel}", not (dp_dir / rel).exists(), str(dp_dir / rel))

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
	doc = nbt.load(template_level)
	patch_level_dat(doc, args.world_name, layout)
	world_dir.mkdir(parents=True, exist_ok=True)
	nbt.save(doc, world_dir / "level.dat")

	# 2) 渲染 datapack（STATIC 跳过 VOID 专属文件）
	placeholders = build_placeholders(args)
	rendered = render_datapack(world_dir / "datapacks" / "autotrade_test", placeholders, args.mode)

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
	return verify(args.world_name, args.mode)


def resolve_world_name(args: argparse.Namespace) -> str:
	"""世界名：显式 --world-name 优先，否则按模式取默认名。"""
	return args.world_name or DEFAULT_WORLD_NAMES[args.mode]


def main(argv: list[str]) -> int:
	"""解析参数并执行。"""
	parser = argparse.ArgumentParser(description="AutoTrade 测试世界生成器（不启动游戏）")
	parser.add_argument("--mode", choices=("static", "void"), default="static", help="装置模式（默认 static）")
	parser.add_argument("--world-name", default=None, help="世界名（默认 static=AutoTradeTest / void=AutoTradeVoidTest）")
	parser.add_argument("--template-world", default="New World")
	# 补货间隔默认 5s（= 静止模式轮间隔 tradeInterval 100t）：耗尽后下一轮即补货，避免出现空过轮次
	parser.add_argument("--restock-seconds", type=int, default=5)
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
	args.world_name = resolve_world_name(args)

	if args.verify:
		return 0 if verify(args.world_name, args.mode) else 1
	return 0 if generate(args) else 1


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
