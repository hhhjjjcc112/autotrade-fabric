r"""CAPACITY 测试批次用例表（23 组，单一来源）+ 第一 pass 推导自校验 + 表导出。

职责：
	- `Case` / `CASES`：23 组受控用例（矩阵序 a1,a2,a3,b1..b4,c1..c3,d1,d2,e1..e3,f1..f3,g1..g3,h1,h2）
	  的精确钉扎值（pinned）；
	- `layout_case(case)`：按用例展开为 36 个背包槽（屏槽 3..38 的槽序）；
	- `derive_first_pass(case)`：纯函数——按 `CapacityModel` / `AbstractTradeStrategy` 语义推导第一 pass；
	- `validate()`：pinned（钉扎值）vs derived（推导值）逐项比对，打印 `[DIFF]` 并返回是否全部一致；
	- `export_table(md_path, json_path)` 与 CLI（`--check` / `--export-md` / `--export-json`，可组合）。

钉扎值即验收对象：推导与钉扎不一致时，先复核源码语义与计划勘误，禁止反向修改钉扎值迁就推导。

推导备忘（自计划用例矩阵，原样引用）：
1. 后置 autofill：`MerchantScreenHandler.autofill` 自屏槽 3→38 按序把槽 0 补至 64 后 break ⇒ 本表中仅前部成本堆叠被清空（e1/e2 保留次级 e12、e3 保留 e13、g1 保留 e10）；**空槽数** = 前置空槽 + 被清空堆叠数。**容量 = 空槽数 × resultMaxCount + 既有同物品结果堆叠的可合并余量 Σ(maxCount − count)**（对应 `CapacityModel.java:16-31` 的 `emptySlots` 与 `mergeSpace` 两部分；B 组 b1–b4 依赖合并项，如 b1：`1×64 + 4×(64−16) = 256`）。
2. 预留 R：`leftover = 槽0 − eff×cost`；cost≠result 时 `leftover > costMergeSpace(成本) ? R += result.maxCount : 0`（e1/e3 为 `52 > 52`/`52 > 51` 阈值两侧；c 组 result.maxCount=1）。
3. 分支：`cap−R ≥ need → QUICK_MOVE（trades=eff）`；否则候选且 `affordable=cap/sell≥1 → exact-N（n=min(affordable,eff)）`；候选且 affordable=0 → CAPACITY_SKIP；非候选 → STOP（会话 blocked 条件：skips>0 且 trades==0）；**STUCK**（exact-N 回退/点击后槽 2 滞留）亦置 `blocked=true`（此时 `skips` 可为 0、`trades` 可 >0，见勘误块 3 与备忘 4）。**候选门定义**（`CapacityModel.isStarvationCandidate`，`CapacityModel.java:112-121`）：`autofillBatch × sellCount > 36 × resultMaxCount`，其中 `autofillBatch = costMaxCount > 1 ? costMaxCount / costCount : 1`；即整批产出超过空背包理论容量（36 槽）时才为候选——故 A/B/D/E/F（sell=16/8，整批可放入空背包）`cand=false`，C（sell=1、结果 max=1 → 64>36）与 G（sell=64 → 4096>2304）`cand=true`；d1 虽 `affordable=12≥1` 但 `cand=false` ⇒ STOP（exact-N 分支仅在候选下成立）。
5. 用例函数槽位映射：`inventory.0..26` → 屏槽 3..29、`hotbar.0..8` → 屏槽 30..38；成本主堆叠放 `inventory.0` 以保证 autofill 先取。不使用区间槽位语法（历史陷阱）。

边界：本模块只推导第一 pass 的七键（inputBatch/need/capacity/reservation/candidate/effectiveBatch/remaining）
与 STOP 分支（expect_stop）；g1 第二 pass、会话（trades/capacity_skips/blocked/moveout_blocked/stuck）、
终态计数与掉落实体钉扎不在本模块推导，由 Todo 6 `capacity_sequence_ref.py` 独立序列模拟器复核。
"""

from __future__ import annotations

import argparse
import copy
import json
import sys
from dataclasses import dataclass
from pathlib import Path

# 物品 id（本表仅用 4 种；泥土为占位 junk）
EMERALD = "minecraft:emerald"
PAPER = "minecraft:paper"
IRON_SWORD = "minecraft:iron_sword"
DIRT = "minecraft:dirt"

# 各物品最大堆叠数（对应 Java 的 item.getMaxCount()；候选门与容量式用）
ITEM_MAX_COUNT = {EMERALD: 64, PAPER: 64, IRON_SWORD: 1, DIRT: 64}

# 背包槽数（屏槽 3..38）与泥土满堆叠（junk 占位；每栈 64）
INVENTORY_SLOTS = 36
JUNK_STACK = (DIRT, ITEM_MAX_COUNT[DIRT])

# 日志/导出字段顺序（比对与断言用；expect_exec 每项恰七键）
EXEC_KEYS = ("inputBatch", "need", "capacity", "reservation", "candidate", "effectiveBatch", "remaining")
SESSION_KEYS = ("trades", "capacity_skips", "blocked", "moveout_blocked", "stuck")
FINAL_KEYS = ("emerald", "paper", "iron_sword")

# 第一 pass 分支枚举（decideAndExecuteBatch 的四出口）
QUICK_MOVE = "QUICK_MOVE"
EXACT_N = "EXACT_N"
CAPACITY_SKIP = "CAPACITY_SKIP"
STOP = "STOP"


@dataclass
class Offer:
	"""单成本 offer 的用例描述（本表 cost_item 恒为绿宝石；全部用例无第二成本）。"""

	cost_item: str
	cost_count: int
	sell_item: str
	sell_count: int
	max_uses: int


@dataclass
class Case:
	"""一组受控用例：布局 + 钉扎期望（pinned 即验收对象）。"""

	id: str
	group: str
	title: str
	offer: Offer
	slots: str  # 人读布局描述（计划矩阵原样，如 "e + j31 + 空4"）
	stacks: list[tuple[str, int]]  # 非 junk 堆叠，按槽序（成本主堆叠在前，其后次级成本/结果堆叠）
	junk_stacks: int  # 泥土×64 堆叠数（排在 stacks 之后）
	empty_slots: int  # 结尾空槽数
	pre_counts: dict[str, int]  # 用例函数执行后、启用 mod 前的前置计数（由 stacks 汇总）
	expect_exec: list[dict]  # 每 pass 一项（g1 两项）；每项恰 EXEC_KEYS 七键
	expect_stop: bool  # 第一 pass 是否为 STOP 出口（非候选整批放不下）
	expect_session: dict  # 恰 SESSION_KEYS 五键
	expect_final: dict[str, int]  # 会话结束（关窗）后背包计数
	expect_entities: int  # 关窗时的掉落实体数（并入 onClosed offerOrDrop）
	notes: str


def _counts(emerald=0, paper=0, iron_sword=0):
	"""构造 {emerald, paper, iron_sword} 计数 dict（插入序固定，JSON 输出稳定）。"""
	return {"emerald": emerald, "paper": paper, "iron_sword": iron_sword}


def _counts_from(stacks):
	"""由布局堆叠汇总前置计数（避免手抄 pre_counts）。"""
	totals = _counts()
	for item, count in stacks:
		# 只统计三种受观注物品；泥土等占位物不计
		if item == EMERALD:
			totals["emerald"] += count
		elif item == PAPER:
			totals["paper"] += count
		elif item == IRON_SWORD:
			totals["iron_sword"] += count
	return totals


def _exec(input_batch, need, capacity, reservation, candidate, effective_batch, remaining):
	"""构造单 pass 的 EXECUTING 钉扎 dict（恰七键；必须为可变 dict——篡改自检依赖）。"""
	return {
		"inputBatch": input_batch,
		"need": need,
		"capacity": capacity,
		"reservation": reservation,
		"candidate": candidate,
		"effectiveBatch": effective_batch,
		"remaining": remaining,
	}


def _session(trades, capacity_skips, blocked, *, moveout_blocked=False, stuck=False):
	"""构造会话钉扎 dict（恰五键；g1/g2 的 STUCK 会话显式传 stuck=True）。"""
	return {
		"trades": trades,
		"capacity_skips": capacity_skips,
		"blocked": blocked,
		"moveout_blocked": moveout_blocked,
		"stuck": stuck,
	}


def _offer(cost_count, sell_item, sell_count, max_uses):
	"""构造单成本 offer（cost_item 恒为绿宝石）。"""
	return Offer(EMERALD, cost_count, sell_item, sell_count, max_uses)


def _case(
	*,
	case_id,
	group,
	title,
	offer,
	stacks,
	junk,
	empty,
	slots,
	execs,
	stop,
	session,
	final,
	entities=0,
	notes="",
):
	"""关键字构造 Case；pre_counts 由 stacks 自动汇总（防手抄错）。"""
	return Case(
		id=case_id,
		group=group,
		title=title,
		offer=offer,
		slots=slots,
		stacks=[tuple(stack) for stack in stacks],
		junk_stacks=junk,
		empty_slots=empty,
		pre_counts=_counts_from(stacks),
		expect_exec=execs,
		expect_stop=stop,
		expect_session=session,
		expect_final=final,
		expect_entities=entities,
		notes=notes,
	)


def _build_cases() -> list[Case]:
	"""构建 23 组用例（矩阵序；h1/h2 为 f1/d1 的深拷贝副本、id 独立）。"""
	e64 = (EMERALD, 64)

	cases = [
		# A 组：空槽计数（结果可堆叠；容量 = 空槽数 × 64）
		_case(
			case_id="a1",
			group="A",
			title="空槽计数",
			offer=_offer(1, PAPER, 16, 64),
			stacks=[e64],
			junk=31,
			empty=4,
			slots="e + j31 + 空4",
			execs=[_exec(64, 1024, 320, 0, False, 64, 64)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=64),
			notes="修正后布局（勘误 #1）：推导恰为 capacity=320 < need=1024；非候选 → STOP。",
		),
		_case(
			case_id="a2",
			group="A",
			title="空槽计数（等号）",
			offer=_offer(1, PAPER, 16, 64),
			stacks=[e64],
			junk=20,
			empty=15,
			slots="e + j20 + 空15",
			execs=[_exec(64, 1024, 1024, 0, False, 64, 64)],
			stop=False,
			session=_session(64, 0, False),
			final=_counts(paper=1024),
			notes="修正后布局（勘误 #1）：cap == need == 1024；QUICK_MOVE 整批 64 笔。",
		),
		_case(
			case_id="a3",
			group="A",
			title="空槽计数（等号−1）",
			offer=_offer(1, PAPER, 16, 64),
			stacks=[e64],
			junk=21,
			empty=14,
			slots="e + j21 + 空14",
			execs=[_exec(64, 1024, 960, 0, False, 64, 64)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=64),
			notes="capacity=960 < need=1024；非候选 → STOP。",
		),
		# B 组：结果堆叠合并空间（b1/b2 依赖合并项；b3/b4 不足）
		_case(
			case_id="b1",
			group="B",
			title="合并空间（等号）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (PAPER, 16), (PAPER, 16), (PAPER, 16), (PAPER, 16)],
			junk=31,
			empty=0,
			slots="e + p16×4 + j31 + 空0",
			execs=[_exec(64, 192, 256, 64, False, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, paper=256),
			notes="合并空间等号：cap = 1×64 + 4×(64−16) = 256，扣除 R=64 后 == need=192；QUICK_MOVE 12 笔。",
		),
		_case(
			case_id="b2",
			group="B",
			title="合并空间（严格）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (PAPER, 16), (PAPER, 16), (PAPER, 16), (PAPER, 16)],
			junk=30,
			empty=1,
			slots="e + p16×4 + j30 + 空1",
			execs=[_exec(64, 192, 320, 64, False, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, paper=256),
			notes="空槽 +1 → cap=320，扣除预留后有余量；QUICK_MOVE 12 笔。",
		),
		_case(
			case_id="b3",
			group="B",
			title="合并空间（不足）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (PAPER, 32), (PAPER, 32), (PAPER, 32), (PAPER, 32)],
			junk=31,
			empty=0,
			slots="e + p32×4 + j31 + 空0",
			execs=[_exec(64, 192, 192, 64, False, 12, 12)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=64, paper=128),
			notes="合并空间不足（4×(64−32)=128 → cap=192）；扣除 R=64 → 128 < 192；非候选 → STOP。",
		),
		_case(
			case_id="b4",
			group="B",
			title="合并空间（负例：满堆叠不贡献）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (PAPER, 64), (PAPER, 64)],
			junk=31,
			empty=2,
			slots="e + p64×2 + j31 + 空2",
			execs=[_exec(64, 192, 192, 64, False, 12, 12)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=64, paper=128),
			notes="满堆叠（64×2）不贡献合并空间 → cap=192；同 b3 → STOP。",
		),
		# C 组：不可堆叠结果（capacity = 空槽数；reservation 每成本占 1 空槽）
		_case(
			case_id="c1",
			group="C",
			title="不可堆叠（等号）",
			offer=_offer(1, IRON_SWORD, 1, 12),
			stacks=[e64],
			junk=23,
			empty=12,
			slots="e + j23 + 空12",
			execs=[_exec(64, 12, 13, 1, True, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, iron_sword=12),
			notes="不可堆叠：cap=空槽 13，预留占 1 空槽 → 12 ≥ 12 等号通过（候选门 64>36=true）。",
		),
		_case(
			case_id="c2",
			group="C",
			title="不可堆叠（另一尺度等号）",
			offer=_offer(1, IRON_SWORD, 1, 11),
			stacks=[e64],
			junk=24,
			empty=11,
			slots="e + j24 + 空11",
			execs=[_exec(64, 11, 12, 1, True, 11, 11)],
			stop=False,
			session=_session(11, 0, False),
			final=_counts(emerald=53, iron_sword=11),
			notes="另一尺度等号：cap=12，预留 1 → 11 ≥ 11 通过。",
		),
		_case(
			case_id="c3",
			group="C",
			title="不可堆叠（严格）",
			offer=_offer(1, IRON_SWORD, 1, 12),
			stacks=[e64],
			junk=0,
			empty=35,
			slots="e + 空35",
			execs=[_exec(64, 12, 36, 1, True, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, iron_sword=12),
			notes="全空背包（36 槽）：cap=36，预留 1 → 通过。",
		),
		# D 组：剩余成本预留阻断（容量单看 == need，但扣 R 后不足）
		_case(
			case_id="d1",
			group="D",
			title="预留阻断（容量单看==need）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64],
			junk=33,
			empty=2,
			slots="e + j33 + 空2",
			execs=[_exec(64, 192, 192, 64, False, 12, 12)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=64),
			notes="容量单看 == need（192）但预留 64 后 cap−R=128 < 192；非候选 → STOP（exact-N 仅候选可走）。",
		),
		_case(
			case_id="d2",
			group="D",
			title="预留后等号通过",
			offer=_offer(1, PAPER, 16, 8),
			stacks=[e64],
			junk=33,
			empty=2,
			slots="e + j33 + 空2",
			execs=[_exec(64, 128, 192, 64, False, 8, 8)],
			stop=False,
			session=_session(8, 0, False),
			final=_counts(emerald=56, paper=128),
			notes="预留后等号：192−64=128 == need（8×16）→ QUICK_MOVE 8 笔。",
		),
		# E 组：成本与结果同堆叠合并（R=0 阈值两侧）
		_case(
			case_id="e1",
			group="E",
			title="成本合并（R=0 等号）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (EMERALD, 12)],
			junk=32,
			empty=2,
			slots="e + e12 + j32 + 空2",
			execs=[_exec(64, 192, 192, 0, False, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=64, paper=192),
			notes="成本合并 R=0 阈值相等（leftover 52 > costMerge 52 为假）→ 余量恰好覆盖；QUICK_MOVE 12 笔。",
		),
		_case(
			case_id="e2",
			group="E",
			title="成本合并（R=0 但容量不足）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (EMERALD, 12)],
			junk=33,
			empty=1,
			slots="e + e12 + j33 + 空1",
			execs=[_exec(64, 192, 128, 0, False, 12, 12)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=76),
			notes="同 R=0 但 cap=128 < 192 → STOP；终态计入保留的次级 e12 → 64+12=76（勘误 #2）。",
		),
		_case(
			case_id="e3",
			group="E",
			title="成本合并（阈值对侧 R=64）",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64, (EMERALD, 13)],
			junk=32,
			empty=2,
			slots="e + e13 + j32 + 空2",
			execs=[_exec(64, 192, 192, 64, False, 12, 12)],
			stop=True,
			session=_session(0, 1, True),
			final=_counts(emerald=77),
			notes="阈值对侧：costMerge=51，52 > 51 → R=64 → 192−64=128 < 192 → STOP；终态 64+13=77（勘误 #2）。",
		),
		# F 组：有效批封顶 / A6 假空位回归
		_case(
			case_id="f1",
			group="F",
			title="有效批 / A6 假空位回归",
			offer=_offer(1, PAPER, 16, 12),
			stacks=[e64],
			junk=31,
			empty=4,
			slots="e + j31 + 空4",
			execs=[_exec(64, 192, 320, 64, False, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, paper=192),
			notes="有效批 12 封顶；A6 假空位回归位：容量 320 与预留 64 区分。",
		),
		_case(
			case_id="f2",
			group="F",
			title="有效批 / A6（另一组数）",
			offer=_offer(1, PAPER, 8, 24),
			stacks=[e64],
			junk=32,
			empty=3,
			slots="e + j32 + 空3",
			execs=[_exec(64, 192, 256, 64, False, 24, 24)],
			stop=False,
			session=_session(24, 0, False),
			final=_counts(emerald=40, paper=192),
			notes="另一组数：eff=24（maxUses 封顶），need=192，cap=256，R=64 → 等号通过。",
		),
		_case(
			case_id="f3",
			group="F",
			title="有效批 / A6（严格窗口）",
			offer=_offer(1, PAPER, 8, 12),
			stacks=[e64],
			junk=33,
			empty=2,
			slots="e + j33 + 空2",
			execs=[_exec(64, 96, 192, 64, False, 12, 12)],
			stop=False,
			session=_session(12, 0, False),
			final=_counts(emerald=52, paper=96),
			notes="严格窗口：need=96 < cap−R=128 → QUICK_MOVE 12 笔。",
		),
		# G 组：候选门 / exact-N / 防饿死 fallback / STUCK
		_case(
			case_id="g1",
			group="G",
			title="候选/exact-N 容量受限",
			offer=_offer(1, PAPER, 64, 64),
			stacks=[e64, (EMERALD, 10)],
			junk=25,
			empty=9,
			slots="e + e10 + j25 + 空9",
			execs=[
				_exec(64, 4096, 640, 0, True, 64, 64),
				_exec(64, 3456, 64, 64, True, 54, 54),
			],
			stop=False,
			session=_session(11, 0, True, stuck=True),
			final=_counts(paper=704),
			entities=0,
			notes=(
				"pass1 exact-N n=10（affordable=10）；pass2 cap=64/R=64 守卫 s−m=63>8 → fallback 1 笔后槽 2 滞留 → STUCK，跳过 moveOut，关窗掉 63；"
				"掉落实体命运见勘误 #5：关窗 onClosed（MerchantScreenHandler.java:160-180）offerOrDrop 槽 0 余量；主背包满（j25 + 11×p64）→ "
				"PlayerInventory.offer:328-344 dropItem；40 tick 后 ItemEntity.onPlayerCollision:333-347 → insertStack:275-312，"
				"创造模式（测试世界 GameType=1）无空间命中 insertStack:306-308 setCount(0) → 实体 discard → 收尾采样实体数 0；两 pass 钉扎由 Todo 6 复核。"
			),
		),
		_case(
			case_id="g2",
			group="G",
			title="候选/防饿死 fallback",
			offer=_offer(1, PAPER, 64, 64),
			stacks=[e64],
			junk=31,
			empty=4,
			slots="e + j31 + 空4",
			execs=[_exec(64, 4096, 320, 0, True, 64, 64)],
			stop=False,
			session=_session(5, 0, True, stuck=True),
			final=_counts(paper=320),
			entities=0,
			notes=(
				"唯一源 s−m=59>8 → fallback 空间封顶 5 笔后槽 2 滞留 → STUCK，关窗掉 59；"
				"掉落实体命运同 g1（勘误 #5）：主背包满（j31 + 5×p64）→ offer:328-344 dropItem → 40 tick 后 onPlayerCollision:333-347 "
				"→ insertStack:306-308 创造模式 setCount(0) → discard → 收尾采样实体数 0；钉扎由 Todo 6 复核。"
			),
		),
		_case(
			case_id="g3",
			group="G",
			title="候选但整批可容纳",
			offer=_offer(1, PAPER, 64, 3),
			stacks=[e64],
			junk=16,
			empty=19,
			slots="e + j16 + 空19",
			execs=[_exec(64, 192, 1280, 64, True, 3, 3)],
			stop=False,
			session=_session(3, 0, False),
			final=_counts(emerald=61, paper=192),
			notes="候选但整批可容纳（cap=1280 ≥ need=192，R=64）→ QUICK_MOVE 3 笔（有效批 = maxUses 封顶）。",
		),
	]
	# H 组：复跑幂等（f1/d1 的深拷贝副本；独立 id，钉扎值一致）
	by_id = {case.id: case for case in cases}
	h1 = copy.deepcopy(by_id["f1"])
	h1.id, h1.group, h1.title = "h1", "H", "复跑幂等"
	h1.notes = "= f1 复跑幂等组（独立 id，值深拷贝自 f1）。"
	h2 = copy.deepcopy(by_id["d1"])
	h2.id, h2.group, h2.title = "h2", "H", "复跑幂等（blocked 复跑）"
	h2.notes = "= d1 blocked 复跑幂等组（独立 id，值深拷贝自 d1）。"
	cases.append(h1)
	cases.append(h2)
	return cases


CASES = _build_cases()

# 模块级形状自检（导入零副作用——仅校验内存数据；防漏抄/错序/键集漂移）
assert len(CASES) == 23, f"用例数 {len(CASES)} != 23"
assert [case.id for case in CASES] == [
	"a1", "a2", "a3",
	"b1", "b2", "b3", "b4",
	"c1", "c2", "c3",
	"d1", "d2",
	"e1", "e2", "e3",
	"f1", "f2", "f3",
	"g1", "g2", "g3",
	"h1", "h2",
], "用例顺序与计划矩阵不一致"
for _case_item in CASES:
	assert len(_case_item.expect_exec) >= 1, f"{_case_item.id} 缺少 expect_exec"
	assert set(_case_item.expect_exec[0]) == set(EXEC_KEYS), f"{_case_item.id} expect_exec 键集不匹配"
	assert set(_case_item.expect_session) == set(SESSION_KEYS), f"{_case_item.id} expect_session 键集不匹配"
	assert set(_case_item.expect_final) == set(FINAL_KEYS), f"{_case_item.id} expect_final 键集不匹配"


def layout_case(case: Case) -> list[tuple[str, int]]:
	"""展开用例为 36 个背包槽（屏槽 3..38 顺序）：非 junk 堆叠 → 泥土堆叠 → 空槽。"""
	slots: list[tuple[str, int]] = list(case.stacks)  # 元素为不可变 tuple，浅拷贝即可
	slots += [JUNK_STACK] * case.junk_stacks
	slots += [("", 0)] * case.empty_slots
	assert len(slots) == INVENTORY_SLOTS, f"用例 {case.id} 槽位总数 {len(slots)} != {INVENTORY_SLOTS}"
	return slots


def derive_first_pass(case: Case) -> dict:
	"""纯函数：按源码语义推导第一 pass 的容量判定（autofill 清空 → 容量 → 预留 → 候选门 → 有效批 → 分支）。

	仅覆盖第一 pass：remaining 取初始剩余次数（max_uses），不含跨 pass 累计与会话收尾；
	推导依据：`CapacityModel.java:16-31,51-76,94-107,112-121` 与
	`AbstractTradeStrategy.java:392-441,451-521`（见模块 docstring 备忘 1-3）。
	返回 dict：EXEC_KEYS 七键 + "branch"（QUICK_MOVE/EXACT_N/CAPACITY_SKIP/STOP）。
	"""
	offer = case.offer
	cost_max = ITEM_MAX_COUNT[offer.cost_item]
	result_max = ITEM_MAX_COUNT[offer.sell_item]
	stackable = result_max > 1

	# 1) autofill（MerchantScreenHandler.autofill）：自槽序 3→38 按序把槽 0 补至 cost_max 后 break；
	#    被取空的堆叠成为空槽（备忘 1）。槽 0 起始为空（首次 switchTo 装填）。
	post = [[item, count] for item, count in layout_case(case)]
	slot0 = 0
	for stack in post:
		if slot0 >= cost_max:
			break
		item, count = stack
		if item == offer.cost_item and count > 0:
			take = min(cost_max - slot0, count)
			slot0 += take
			stack[1] = count - take

	# 2) 容量 = 空槽数 × resultMaxCount + 既有同物品结果堆叠的可合并余量（可堆叠）；不可堆叠 = 空槽数
	empty_slots = 0
	merge_space = 0
	for item, count in post:
		if count <= 0:
			empty_slots += 1
		elif stackable and item == offer.sell_item:
			merge_space += max(0, result_max - count)
	capacity = empty_slots * result_max + merge_space if stackable else empty_slots

	# 3) 候选门（CapacityModel.isStarvationCandidate）：整批产出 > 空背包理论容量（36 槽）
	autofill_batch = cost_max // offer.cost_count if cost_max > 1 else 1
	candidate = autofill_batch * offer.sell_count > INVENTORY_SLOTS * result_max

	# 4) inputBatch / effectiveBatch：槽 0 数量整除单笔成本；有效批按剩余次数封顶（双成本本表恒无）
	input_batch = slot0 // offer.cost_count
	remaining = offer.max_uses
	effective_batch = min(input_batch, remaining)
	if input_batch <= 0 or effective_batch <= 0:
		# 防御分支（本表 23 组均有 64 装填量与 maxUses≥3，不可达）：源码语义为 CAPACITY_SKIP
		return {
			"inputBatch": input_batch,
			"need": 0,
			"capacity": capacity,
			"reservation": 0,
			"candidate": candidate,
			"effectiveBatch": 0,
			"remaining": remaining,
			"branch": CAPACITY_SKIP,
		}
	need = effective_batch * offer.sell_count

	# 5) 预留 R（备忘 2）：槽 0 剩余成本回背包所需空间；cost≠result 时超出成本合并空间才占结果容量
	reservation = 0
	leftover = slot0 - effective_batch * offer.cost_count
	if leftover > 0:
		if offer.cost_item == offer.sell_item:
			reservation += leftover
		else:
			cost_merge = 0
			for item, count in post:
				if item == offer.cost_item and count > 0:
					cost_merge += max(0, cost_max - count)
			if leftover > cost_merge:
				reservation += result_max

	# 6) 分支（备忘 3）：整批可容纳 → QUICK_MOVE；否则候选 exact-N / CAPACITY_SKIP；非候选 STOP
	if capacity - reservation >= need:
		branch = QUICK_MOVE
	elif candidate and capacity // offer.sell_count >= 1:
		branch = EXACT_N
	elif candidate:
		branch = CAPACITY_SKIP
	else:
		branch = STOP

	return {
		"inputBatch": input_batch,
		"need": need,
		"capacity": capacity,
		"reservation": reservation,
		"candidate": candidate,
		"effectiveBatch": effective_batch,
		"remaining": remaining,
		"branch": branch,
	}


def validate(verbose: bool = True) -> bool:
	"""pinned vs derive_first_pass 逐项比对（第一 pass 七键 + STOP 分支）；全部一致返回 True。

	不一致时打印 `[DIFF] <id>: <field> pinned=<a> derived=<b>`；
	会话/终态/第二 pass 钉扎不在本函数范围（由 Todo 6 独立复核）。
	"""
	passed = 0
	for case in CASES:
		derived = derive_first_pass(case)
		pinned = case.expect_exec[0]
		case_ok = True
		for key in EXEC_KEYS:
			if pinned.get(key) != derived[key]:
				print(f"[DIFF] {case.id}: {key} pinned={pinned.get(key)} derived={derived[key]}")
				case_ok = False
		derived_stop = derived["branch"] == STOP
		if bool(case.expect_stop) != derived_stop:
			print(
				f"[DIFF] {case.id}: expect_stop pinned={bool(case.expect_stop)} derived={derived_stop}"
				f"（branch={derived['branch']}）"
			)
			case_ok = False
		if case_ok:
			passed += 1
			if verbose:
				print(
					f"[OK] {case.id} exec(in={derived['inputBatch']} need={derived['need']} "
					f"cap={derived['capacity']} res={derived['reservation']} cand={derived['candidate']} "
					f"eff={derived['effectiveBatch']} rem={derived['remaining']}) "
					f"branch={derived['branch']} stop={derived_stop}"
				)
	if verbose:
		print(f"[check] {passed}/{len(CASES)} cases consistent")
	return passed == len(CASES)


def case_to_dict(case: Case) -> dict:
	"""把 Case 转为 JSON 可序列化 dict（含全部字段；expect_exec 完整列表、会话恰五键）。"""
	offer = case.offer
	return {
		"id": case.id,
		"group": case.group,
		"title": case.title,
		"offer": {
			"cost_item": offer.cost_item,
			"cost_count": offer.cost_count,
			"sell_item": offer.sell_item,
			"sell_count": offer.sell_count,
			"max_uses": offer.max_uses,
		},
		"slots": case.slots,
		"stacks": [list(stack) for stack in case.stacks],
		"junk_stacks": case.junk_stacks,
		"empty_slots": case.empty_slots,
		"pre_counts": dict(case.pre_counts),
		"expect_exec": [dict(entry) for entry in case.expect_exec],
		"expect_stop": case.expect_stop,
		"expect_session": dict(case.expect_session),
		"expect_final": dict(case.expect_final),
		"expect_entities": case.expect_entities,
		"notes": case.notes,
	}


def _layout_text(case: Case) -> str:
	"""布局人读串：堆叠明细 → 泥土组数 → 空槽数。"""
	parts = [f"{item}×{count}" for item, count in case.stacks]
	if case.junk_stacks:
		parts.append(f"{DIRT}×{case.junk_stacks} 组（每组 64）")
	if case.empty_slots:
		parts.append(f"空槽 {case.empty_slots}")
	return " + ".join(parts) if parts else "（全空）"


def _counts_text(counts: dict) -> str:
	"""计数 dict 人读串（emerald/paper/iron_sword 固定序）。"""
	return "，".join(f"{name}={counts[name]}" for name in FINAL_KEYS)


def _render_md(cases: list[Case]) -> str:
	"""渲染人读 Markdown 表（每用例一条记录，共 23 条）。"""
	lines = [
		"# CAPACITY 用例表（23 组）",
		"",
		"> 本文件由 `test/lib/capacity_scenarios.py` 导出（单一来源）；勿手工编辑。",
		"> 重新生成：`python test/lib/capacity_scenarios.py --export-md <path> --export-json <path>`",
		"",
		f"- 用例总数：{len(cases)}",
		"- id 序列：" + ", ".join(case.id for case in cases),
	]
	for case in cases:
		offer = case.offer
		lines += [
			"",
			f"## {case.id} — {case.group} {case.title}",
			"",
			f"- 布局（36 槽）：{case.slots}",
			f"- 堆叠明细：{_layout_text(case)}",
			f"- offer：{offer.cost_count}×{offer.cost_item} → {offer.sell_count}×{offer.sell_item}"
			f"（maxUses={offer.max_uses}）",
			f"- 前置计数：{_counts_text(case.pre_counts)}",
		]
		for index, executing in enumerate(case.expect_exec, start=1):
			branch = derive_first_pass(case)["branch"] if index == 1 else "第二 pass（钉扎由 Todo 6 复核）"
			lines.append(
				f"- 期望 EXECUTING（pass{index}）：inputBatch={executing['inputBatch']} need={executing['need']} "
				f"capacity={executing['capacity']} reservation={executing['reservation']} "
				f"candidate={executing['candidate']} effectiveBatch={executing['effectiveBatch']} "
				f"remaining={executing['remaining']} → 分支={branch}"
			)
		lines.append(f"- 期望 STOP：{'是' if case.expect_stop else '否'}")
		session = case.expect_session
		lines.append(
			f"- 期望会话：trades={session['trades']} capacity_skips={session['capacity_skips']} "
			f"blocked={session['blocked']} moveout_blocked={session['moveout_blocked']} stuck={session['stuck']}"
		)
		lines.append(f"- 期望终态：{_counts_text(case.expect_final)}；掉落实体={case.expect_entities}")
		if case.notes:
			lines.append(f"- 备注：{case.notes}")
	return "\n".join(lines) + "\n"


def export_table(md_path=None, json_path=None) -> list[dict]:
	"""导出用例表：JSON（机读全字段）与 Markdown（人读逐条记录，23 条）。

	参数为 None 的格式不导出；返回 23 条 payload（JSON 结构）供调用方复用。
	"""
	payload = [case_to_dict(case) for case in CASES]
	if json_path is not None:
		path = Path(json_path)
		path.parent.mkdir(parents=True, exist_ok=True)
		# newline="\n"：跨平台生成 LF（与仓库既有文本文件一致）
		with path.open("w", encoding="utf-8", newline="\n") as handle:
			json.dump(payload, handle, ensure_ascii=False, indent=2)
			handle.write("\n")
	if md_path is not None:
		path = Path(md_path)
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(_render_md(CASES), encoding="utf-8", newline="\n")
	return payload


def main(argv=None) -> int:
	"""CLI：`--check` / `--export-md <path>` / `--export-json <path>`（可组合）；无参数打印帮助并返回 2。"""
	# Windows 默认代码页下中文输出会乱码：在 main 内重配置标准流（导入保持零副作用）
	for stream in (sys.stdout, sys.stderr):
		reconfigure = getattr(stream, "reconfigure", None)
		if reconfigure is not None:
			try:
				reconfigure(encoding="utf-8", errors="replace")
			except Exception:
				pass
	parser = argparse.ArgumentParser(description="CAPACITY 用例表（23 组）：第一 pass 推导自校验与表导出")
	parser.add_argument("--check", action="store_true", help="pinned vs 推导逐项校验（stdout 含 23/23；退出码 0/1）")
	parser.add_argument("--export-md", metavar="PATH", default=None, help="导出人读 Markdown 表")
	parser.add_argument("--export-json", metavar="PATH", default=None, help="导出机读 JSON 表")
	args = parser.parse_args(argv)
	if not (args.check or args.export_md or args.export_json):
		parser.print_help()
		return 2
	result = 0
	if args.check:
		result = 0 if validate() else 1
	if args.export_md or args.export_json:
		export_table(md_path=args.export_md, json_path=args.export_json)
		if args.export_md:
			print(f"[export] md -> {args.export_md}")
		if args.export_json:
			print(f"[export] json -> {args.export_json}")
	return result


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
