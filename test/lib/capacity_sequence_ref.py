r"""CAPACITY 独立序列参考模拟器（槽级全序列复核钉扎，Todo 6）。

职责：
	- 按 Java/MC 源码语义**独立实现**完整交易会话序列：switchTo 自动装填 → 容量/预留判定 →
	  QUICK_MOVE / CAPACITY_SKIP / STOP 分支 → moveOut →
	  关窗 offerOrDrop 掉落 → 掉落实体拾取-创造模式销毁（勘误 #5）；输入 21 组用例（复用 `capacity_scenarios.CASES` 数据结构），
	  输出 EXECUTING 七键、会话五键、终态三计数与掉落实体数；
	- `--compare`：ref 输出 vs 表钉扎逐项比对（expect_exec 全七键、expect_stop 分支、
	  expect_session 五键、expect_final 三键、expect_entities）；全部一致 → stdout 含 `21/21 match`
	  且退出码 0，否则打印逐项 mismatch 明细并退出码 1。

独立性约束（验收硬性要求）：
	- 不调用 `capacity_scenarios.derive_first_pass`（被测推导器）；模拟逻辑全部由 Java/MC 源码语义
	独立实现，且以槽级操作（含服务端 insertItem/QUICK_MOVE while 循环/关窗掉落）让结果自然涌现，不硬编码钉扎；
	- 仅复用其数据结构与常量（`CASES` / `Case` 字段 / `layout_case` 槽展开 / `ITEM_MAX_COUNT` / 键序）。

源码依据（逐行，均为只读查阅）：
	- `MerchantScreenHandler.java:112-151`（quickMove 槽 0/1/2 的 insertItem 方向与提前返回）、
	  `:160-180`（onClosed offerOrDrop 掉落）、`:182-209`（switchTo 移出输入 + autofill 前置）、
	  `:211-230`（autofill 自屏槽 3→38 按序把输入槽补至 maxCount 后 break）；
	- `PlayerInventory.java:86-94`（getEmptySlot 仅遍历主背包）、`:245-259`（getOccupiedSlotWithRoomForStack
	  仅查主背包/快捷栏）、`:275-312`（insertStack；**:306-308 数量未变且 creativeMode → setCount(0) 返回 true**）、
	  `:328-344`（offer：主背包无空位 + 无可并入堆叠 → `player.dropItem(stack, false)`）；
	- `ItemEntity.java:333-347`（onPlayerCollision：pickupDelay==0 且 insertStack 成功 → 堆叠空则
	  `this.discard()`；创造模式兜底亦使堆叠清空 → 实体静默删除，见勘误 #5）；
	- `ScreenHandler.java:635-649`（QUICK_MOVE while 循环：quickMove 返回空或槽 2 物品不再等价即终止）、
	  `:894-959`（insertItem：先并入 canCombine 未满堆叠，再放第一个空槽且仅一次）；
	- `MerchantInventory.java:68-125`（setStack(0/1) → updateOffers；预览 = copySellItem，输入不匹配/禁用清空）、
	  `MerchantInventory.java:132-135`（setOfferIndex → updateOffers）；
	- `TradeOutputSlot.java:49-65`（onTakeItem：depleteBuyItems 成功才 merchant.trade → offer.use()）、
	  `TradeOffer.java:238-240,283-315`（isDisabled = uses ≥ maxUses；matchesBuyItems/depleteBuyItems）、
	  `TradeOfferList.java:27-42`（getValidOffer 单选）；
	- `CapacityModel.java`（容量/成本合并空间/预留/候选门公式，独立重写）；
	- `AbstractTradeStrategy.java:85-184`（会话七步：残留清理→扫描→批执行→moveOut→post-loop blocked）、
	  `:272-308`（moveOut 失败置 moveout_blocked）、
	  `:392-441`（runOneBatch）、`:451-521`（decideAndExecuteBatch 三出口：QUICK_MOVE / CAPACITY_SKIP / STOP）、
	  `:550-556`（tradeClick 快照差值计数）、
	  `OutputSlotExecutorStrategy.java:13-36`（SnapshotOfferState：remaining = initialRemaining − tradesDone；
	  本批次用例表按 OUTPUT_SLOT 语义钉扎）。

CLI：`--compare`（唯一入口，独立可跑）。导入零副作用（含包导入与直跑双模）。
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path

# 双模导入 shim：包内相对导入优先；直跑时把本文件目录加入 sys.path 后按顶层模块导入
# （照 capacity_analysis.py / test/static.py 范式）。
try:  # 包导入（lib.capacity_sequence_ref / from lib import capacity_sequence_ref）
	from . import capacity_scenarios
except ImportError:  # 直跑（python test/lib/capacity_sequence_ref.py）
	_HERE = Path(__file__).resolve().parent
	if str(_HERE) not in sys.path:
		sys.path.insert(0, str(_HERE))
	import capacity_scenarios  # type: ignore[no-redef]

# 唯一复用面：用例表数据结构与常量（禁止调用 derive_first_pass——独立性验收硬性要求）。
CASES = capacity_scenarios.CASES
EXEC_KEYS = capacity_scenarios.EXEC_KEYS
SESSION_KEYS = capacity_scenarios.SESSION_KEYS
FINAL_KEYS = capacity_scenarios.FINAL_KEYS
ITEM_MAX_COUNT = capacity_scenarios.ITEM_MAX_COUNT
EMERALD = capacity_scenarios.EMERALD
PAPER = capacity_scenarios.PAPER
IRON_SWORD = capacity_scenarios.IRON_SWORD
QUICK_MOVE = capacity_scenarios.QUICK_MOVE
CAPACITY_SKIP = capacity_scenarios.CAPACITY_SKIP
STOP = capacity_scenarios.STOP

# 测试世界恒为创造模式（`setup_testworld.patch_level_dat` 把 Data.GameType 与 Player.playerGameType
# 置 1；见 setup_testworld.py:297,386）。创造模式下背包放不下的掉落实体会被「拾取即销毁」（勘误 #5）。
CREATIVE = True

# 单批出口码（与 AbstractTradeStrategy.BatchResult 对齐；TRADED/DONE 为模拟器内部出口）
RESULT_TRADED = "TRADED"
RESULT_DONE = "DONE"

# 终态计数用物品映射（恰三键，顺序取 FINAL_KEYS）
_FINAL_ITEM_NAMES = {EMERALD: "emerald", PAPER: "paper", IRON_SWORD: "iron_sword"}


@dataclass
class Stack:
	"""槽位堆叠（空槽 = item None；count 0；数量语义与 Java ItemStack 一致）。"""

	item: str | None = None
	count: int = 0

	def is_empty(self) -> bool:
		"""空堆叠判定（与 ItemStack.isEmpty 对齐：item 为 None 或数量 ≤ 0）。"""
		return self.item is None or self.count <= 0

	def copy(self) -> "Stack":
		"""复制堆叠（模拟器的槽操作先复制再改写，保持对象关系清晰）。"""
		return Stack(self.item, self.count)


@dataclass
class BatchOutcome:
	"""单轮（runOneBatch）出口：结果码 + 本轮成交数 + 耗尽标志 + EXECUTING 七键 + 分支。"""

	result: str
	trades: int
	exhausted: bool
	exec_record: dict | None
	branch: str | None


@dataclass
class SimResult:
	"""单用例全序列模拟结果（对照钉扎的最小充分集）。"""

	case_id: str
	execs: list[dict] = field(default_factory=list)
	branches: list[str] = field(default_factory=list)
	session: dict = field(default_factory=dict)
	final: dict = field(default_factory=dict)
	entities: int = 0


class Handler:
	"""槽级交易界面模拟器：39 槽（0/1 输入、2 输出、3-38 玩家背包 36 槽）。

	全部方法按 Java/MC 源码逐语义实现（见模块 docstring 行号）；本类不读取任何钉扎值。
	"""

	def __init__(self, case):
		offer = case.offer
		self.max_uses = offer.max_uses
		self.cost_item = offer.cost_item
		self.cost_count = offer.cost_count
		self.sell_item = offer.sell_item
		self.sell_count = offer.sell_count
		self.slots: list[Stack] = [Stack() for _ in range(39)]
		# 屏槽 3..38 ← 用例布局展开（36 槽；空槽以 ("",0) 表示，非空才建 Stack）。
		for offset, (item, count) in enumerate(capacity_scenarios.layout_case(case)):
			if count > 0:
				self.slots[3 + offset] = Stack(item, count)
		# uses = 本地 offer.use() 计数（updateOffers 的 isDisabled 判定用）；
		# recorded_trades = SnapshotOfferState.tradesDone 记账（remaining 推导用）。
		# 两者在批边界一致；批内 tradeClick 期间 updateOffers 依赖 uses 的实时值。
		self.uses = 0
		self.recorded_trades = 0

	# ------------------------------------------------------------------
	# 基础谓词与点击原语
	# ------------------------------------------------------------------

	def _max(self, item) -> int:
		"""物品最大堆叠数（item.getMaxCount()）。"""
		return ITEM_MAX_COUNT[item]

	def _can_combine(self, first: Stack, second: Stack) -> bool:
		"""canCombine 判定（本用例集 NBT 恒同；仅比较物品与空态）。"""
		return not first.is_empty() and not second.is_empty() and first.item == second.item

	def _clear(self, index: int) -> None:
		"""把槽置为空堆叠（等价 Slot.setStack(EMPTY) 的槽状态）。"""
		self.slots[index] = Stack()

	def insert_item(self, stack: Stack, start: int = 3, end: int = 39, from_last: bool = False) -> bool:
		"""镜像 ScreenHandler.insertItem:894-959：先并入可合并未满堆叠，再放第一个空槽（仅一次）。

		返回是否发生了插入（stack 对象按 Java 语义被就地递减）。
		"""
		moved = False
		index = end - 1 if from_last else start
		# 合并阶段：仅可堆叠物品（isStackable = maxCount > 1）参与
		if self._max(stack.item) > 1:
			while stack.count > 0 and (index >= start if from_last else index < end):
				slot = self.slots[index]
				if self._can_combine(stack, slot):
					combined = slot.count + stack.count
					if combined <= self._max(stack.item):
						stack.count = 0
						slot.count = combined
						moved = True
					elif slot.count < self._max(stack.item):
						stack.count -= self._max(stack.item) - slot.count
						slot.count = self._max(stack.item)
						moved = True
				index += -1 if from_last else 1
		# 空槽阶段：放第一个空槽并整堆放入（等价 stack.split(slot max)），break
		if stack.count > 0:
			index = end - 1 if from_last else start
			while index >= start if from_last else index < end:
				if self.slots[index].is_empty():
					self.slots[index] = Stack(stack.item, stack.count)
					stack.count = 0
					moved = True
					break
				index += -1 if from_last else 1
		if stack.count <= 0:
			stack.count = 0
		return moved

	# ------------------------------------------------------------------
	# 交易项状态与预览（MerchantInventory.updateOffers）
	# ------------------------------------------------------------------

	def remaining(self) -> int:
		"""SnapshotOfferState.remaining():16-25：initialRemaining − tradesDone（扫描时 uses=0）。"""
		return self.max_uses - self.recorded_trades

	def record(self, trades: int) -> None:
		"""SnapshotOfferState.record():32-35：跨批累计成交记账。"""
		self.recorded_trades += trades

	def exhausted(self, batch_trades: int) -> bool:
		"""SnapshotOfferState.exhausted():27-30：本批成交后剩余次数是否恰为 0。"""
		return self.max_uses - (self.recorded_trades + batch_trades) == 0

	def is_disabled(self) -> bool:
		"""TradeOffer.isDisabled():238-240：uses ≥ maxUses（本地 use() 实时值）。"""
		return self.uses >= self.max_uses

	def matches_buy(self, first: Stack, second: Stack) -> bool:
		"""TradeOffer.matchesBuyItems:283-288（单成本用例集）：主成本数量足够；第二成本为空时第二槽须为空。"""
		if first.is_empty() or first.item != self.cost_item or first.count < self.cost_count:
			return False
		# acceptsBuy(second, EMPTY)：sample 为空且 given 非空 → 不匹配
		return second.is_empty()

	def deplete(self, first: Stack, second: Stack) -> bool:
		"""TradeOffer.depleteBuyItems:304-315：匹配则扣一单成本。"""
		if not self.matches_buy(first, second):
			return False
		first.count -= self.cost_count
		if first.count <= 0:
			# first 即 self.slots[0]/self.slots[1] 的对象本身 → 就地清空
			first.item = None
			first.count = 0
		return True

	def execute_trade(self) -> bool:
		"""镜像 TradeOutputSlot.onTakeItem:49-65 + merchant.trade → offer.use()。

		depleteBuyItems(first, second) || depleteBuyItems(second, first) 成功后 use()++，
		并由 setStack(0/1) 触发 updateOffers 重建/清空预览。
		"""
		first, second = self.slots[0], self.slots[1]
		if self.deplete(first, second) or self.deplete(second, first):
			self.uses += 1
			self.update_offers()
			return True
		return False

	def update_offers(self) -> None:
		"""镜像 MerchantInventory.updateOffers:89-125：按槽 0/1 当前输入重建槽 2 预览。"""
		if self.slots[0].is_empty():
			primary, secondary = self.slots[1], Stack()
		else:
			primary, secondary = self.slots[0], self.slots[1]
		if primary.is_empty():
			self._clear(2)
			return
		# 单 offer 表：getValidOffer 命中条件 = matchesBuyItems && !isDisabled（重试交换分支因第二槽为空不可达）
		if self.matches_buy(primary, secondary) and not self.is_disabled():
			self.slots[2] = Stack(self.sell_item, self.sell_count)
		else:
			self._clear(2)

	# ------------------------------------------------------------------
	# 装填（FillHelpers.refillOffer + MerchantScreenHandler.switchTo/autofill）
	# ------------------------------------------------------------------

	def autofill(self, index: int, cost_item: str | None, cost_count: int) -> None:
		"""镜像 MerchantScreenHandler.autofill:211-230：自屏槽 3→38 按序取成本并入输入槽。"""
		if cost_item is None:
			return
		limit = self._max(cost_item)
		for i in range(3, 39):
			slot = self.slots[i]
			if slot.is_empty() or slot.item != cost_item:
				continue
			current = self.slots[index].count
			take = min(limit - current, slot.count)
			total = current + take
			slot.count -= take
			if slot.count <= 0:
				self._clear(i)
			self.slots[index] = Stack(cost_item, total)
			# merchantInventory.setStack(index, ...) → updateOffers（index∈{0,1}）
			self.update_offers()
			if total >= limit:
				break

	def refill(self) -> None:
		"""镜像 FillHelpers.refillOffer:47-57 + MerchantScreenHandler.switchTo:182-209。

		setRecipeIndex → 槽 0/1 QUICK_MOVE 移回背包（insertItem 失败提前 return）→ 双槽空则 autofill。
		"""
		self.update_offers()  # setRecipeIndex(0) → setOfferIndex → updateOffers
		for index in (0, 1):
			slot = self.slots[index]
			if slot.is_empty():
				continue
			moved = self.insert_item(slot, 3, 39, True)
			if not moved:
				# switchTo 提前 return：不移出后续槽、不 autofill
				return
			if slot.count <= 0:
				self._clear(index)
			self.update_offers()  # setStack(0/1) → updateOffers
		if self.slots[0].is_empty() and self.slots[1].is_empty():
			self.autofill(0, self.cost_item, self.cost_count)
			self.autofill(1, None, 0)

	# ------------------------------------------------------------------
	# CapacityModel 公式（独立重写）
	# ------------------------------------------------------------------

	def compute_input_batch(self) -> int:
		"""镜像 CapacityModel.computeInputBatch:34-48：min(floor(槽0/costA), floor(槽1/costB))。"""
		trades = None
		if self.cost_count > 0:
			trades = self.slots[0].count // self.cost_count
		if trades is None:
			trades = 0
		return max(0, trades)

	def calculate_result_capacity(self) -> int:
		"""镜像 CapacityModel.calculateResultCapacity:16-31：空槽×resultMaxCount + 合并空间。"""
		result_max = self._max(self.sell_item)
		stackable = result_max > 1
		empty_slots = 0
		merge_space = 0
		for i in range(3, 39):
			slot = self.slots[i]
			if slot.is_empty():
				empty_slots += 1
			elif stackable and slot.item == self.sell_item:
				merge_space += self._max(slot.item) - slot.count
		return empty_slots * result_max + merge_space if stackable else empty_slots

	def cost_merge_space(self, cost_item: str) -> int:
		"""镜像 CapacityModel.costMergeSpace:80-89：3-38 中可并入成本的未满堆叠空间和。"""
		total = 0
		for i in range(3, 39):
			slot = self.slots[i]
			if not slot.is_empty() and slot.item == cost_item:
				total += self._max(slot.item) - slot.count
		return total

	def calculate_leftover_reservation(self, trades: int) -> int:
		"""镜像 CapacityModel.calculateLeftoverReservation:51-76：本次点击后槽 0/1 余量的回背包占位。"""
		reservation = 0
		costs = ((self.cost_item, self.cost_count), (None, 0))
		for index, (cost_item, cost_count) in enumerate(costs):
			if cost_item is None:
				continue
			leftover = self.slots[index].count - trades * cost_count
			if leftover <= 0:
				continue
			if cost_item == self.sell_item:
				# 成本与结果同物品：余量并入结果堆叠，按物品数精确扣容量
				reservation += leftover
			elif leftover > self.cost_merge_space(cost_item):
				# 余量无法全部并入成本堆叠 → 占 1 个空槽（价值 = 一个结果堆叠上限）
				reservation += self._max(self.sell_item)
		return reservation

	def is_starvation_candidate(self) -> bool:
		"""镜像 CapacityModel.isStarvationCandidate:112-121：autofillBatch × sellCount > 36 × resultMaxCount。"""
		cost_max = self._max(self.cost_item)
		autofill_batch = cost_max // self.cost_count if cost_max > 1 else 1
		return autofill_batch * self.sell_count > 36 * self._max(self.sell_item)

	# ------------------------------------------------------------------
	# 输入槽 QUICK_MOVE（moveOut）
	# ------------------------------------------------------------------

	def quick_move_input_slot(self, index: int) -> None:
		"""镜像 MerchantScreenHandler.quickMove:125-135 槽 0/1 分支（insertItem 方向 fromLast=false）。"""
		slot = self.slots[index]
		if slot.is_empty():
			return
		moved = self.insert_item(slot, 3, 39, False)
		if not moved:
			return
		if slot.count <= 0:
			self._clear(index)
		self.update_offers()  # setStack(0/1)/markDirty → updateOffers

	# ------------------------------------------------------------------
	# 点击槽 2 的 QUICK_MOVE while 循环 + tradeClick 差值计数
	# ------------------------------------------------------------------

	def count_sell_items(self) -> int:
		"""镜像 FillHelpers.countSellItemsInInventory:61-70：3-38 中与卖品可合并的物品总数。"""
		total = 0
		for i in range(3, 39):
			slot = self.slots[i]
			if not slot.is_empty() and slot.item == self.sell_item:
				total += slot.count
		return total

	def raw_quick_move_slot2(self) -> Stack:
		"""镜像 MerchantScreenHandler.quickMove:112-151 槽 2 分支（含 insertItem 失败提前返回）。"""
		slot = self.slots[2]
		if slot.is_empty():
			return Stack()
		original_count = slot.count
		copied = slot.copy()
		# 槽 2 分支：insertItem(itemStack2, 3, 39, true)（fromLast=true）
		moved = self.insert_item(slot, 3, 39, True)
		if not moved:
			# insertItem 失败 → quickMove 提前返回 EMPTY：交易不执行、槽 2 保留预览
			if slot.count <= 0:
				self._clear(2)
			return Stack()
		if slot.count <= 0:
			self._clear(2)
		else:
			# 部分插入（正常容量路径不触发）：markDirty → 预览重建，残余随旧对象丢弃
			self.update_offers()
		if slot.count == original_count:
			return Stack()
		# onTakeItem → 交易（deplete 成功才 use()）
		self.execute_trade()
		return copied

	def quick_move_result_slot(self) -> None:
		"""镜像 ScreenHandler.internalOnSlotClick:635-649 QUICK_MOVE while 循环（槽 2）。"""
		returned = self.raw_quick_move_slot2()
		while not returned.is_empty() and not self.slots[2].is_empty() and self.slots[2].item == returned.item:
			returned = self.raw_quick_move_slot2()

	def trade_click(self) -> int:
		"""镜像 AbstractTradeStrategy.tradeClick:550-556：点击前后 3-38 卖品计数差值 ÷ sellCount。"""
		before = self.count_sell_items()
		self.quick_move_result_slot()
		after = self.count_sell_items()
		return (after - before) // self.sell_count

	# ------------------------------------------------------------------
	# 容量分支执行（decideAndExecuteBatch）
	# ------------------------------------------------------------------

	def decide_and_execute(
		self, input_batch: int, effective_batch: int, need: int, capacity: int, reservation: int
	) -> tuple[str, int, str]:
		"""镜像 AbstractTradeStrategy.decideAndExecuteBatch:451-521 三出口（单成本准则；exact-N 已暂停，2026-10）。

		返回 (结果码, 本轮成交数, 分支名 QUICK_MOVE/CAPACITY_SKIP/STOP)。
		"""
		candidate = self.is_starvation_candidate()
		if capacity - reservation >= need:
			# QUICK_MOVE 优先路径：整批可容纳 → 一次点击整批成交
			return RESULT_TRADED, self.trade_click(), QUICK_MOVE
		if candidate:
			# exact-N 已暂停（2026-10）：候选整批放不下 → CAPACITY_SKIP（跳过等待容器 IO）；重启用见 docs/TASKS.md 6.9
			return "CAPACITY_SKIP", 0, CAPACITY_SKIP
		return STOP, 0, STOP

	# ------------------------------------------------------------------
	# runOneBatch / 会话收尾（moveOut / 关窗）
	# ------------------------------------------------------------------

	def run_one_batch(self) -> BatchOutcome:
		"""镜像 AbstractTradeStrategy.runOneBatch:392-441：装填 → 校验 → 容量 → 分支执行。"""
		self.refill()
		# 槽 2 canCombine(卖品) 校验失败 → DONE（耗尽/没货/装填失败）
		if self.slots[2].is_empty() or self.slots[2].item != self.sell_item:
			return BatchOutcome(RESULT_DONE, 0, False, None, None)
		input_batch = self.compute_input_batch()
		if input_batch <= 0:
			return BatchOutcome("CAPACITY_SKIP", 0, False, None, CAPACITY_SKIP)
		remaining = self.remaining()
		effective_batch = min(input_batch, remaining)
		if effective_batch <= 0:
			return BatchOutcome("CAPACITY_SKIP", 0, False, None, CAPACITY_SKIP)
		need = effective_batch * self.sell_count
		capacity = self.calculate_result_capacity()
		reservation = self.calculate_leftover_reservation(effective_batch)
		# logExecuting:524-530 七键（时序在 decide 之前，容量数据此刻快照）
		exec_record = {
			"inputBatch": input_batch,
			"need": need,
			"capacity": capacity,
			"reservation": reservation,
			"candidate": self.is_starvation_candidate(),
			"effectiveBatch": effective_batch,
			"remaining": remaining,
		}
		result, trades, branch = self.decide_and_execute(input_batch, effective_batch, need, capacity, reservation)
		exhausted = self.exhausted(trades) if result == RESULT_TRADED else False
		return BatchOutcome(result, trades, exhausted, exec_record, branch)

	def move_out_input_costs(self) -> bool:
		"""镜像 AbstractTradeStrategy.moveOutInputCosts:290-308：槽 0/1 QUICK_MOVE 回背包。

		返回 True = 两槽均清空（或本就为空）；False = 任一槽移出失败（moveout_blocked）。
		"""
		ok = True
		for index in (0, 1):
			if self.slots[index].is_empty():
				continue
			self.quick_move_input_slot(index)
			if not self.slots[index].is_empty():
				ok = False
		return ok

	def insert_stack_to_inventory(self, stack: Stack) -> bool:
		"""镜像 PlayerInventory.insertStack(-1, stack)：先并入未满堆叠（槽序 3→38），再放第一个空槽。"""
		if self._max(stack.item) > 1:
			for i in range(3, 39):
				slot = self.slots[i]
				if not slot.is_empty() and slot.item == stack.item and slot.count < self._max(slot.item):
					move = min(self._max(slot.item) - slot.count, stack.count)
					slot.count += move
					stack.count -= move
					if stack.count <= 0:
						return True
		if stack.count > 0:
			for i in range(3, 39):
				if self.slots[i].is_empty():
					self.slots[i] = Stack(stack.item, stack.count)
					stack.count = 0
					return True
		return False

	def close_window(self) -> int:
		"""镜像 MerchantScreenHandler.onClosed:160-180：槽 0/1 offerOrDrop → 掉落实体命运（勘误 #5）。

		放不进主背包的堆叠由 offer:328-344 走 `player.dropItem(stack, false)` 生成 1 个 ItemEntity；
		拾取延迟（40 tick ≈ 2s）后 ItemEntity.onPlayerCollision:333-347 触发 PlayerInventory.insertStack：
		创造模式（测试世界 GameType=1）且主背包无空间时命中 insertStack:306-308（数量未变 → setCount(0)
		返回 true），onPlayerCollision:340-343 见堆叠空 → `this.discard()`。收尾采样（capacity_test.py 的
		POST_DISABLE_WAIT 之前已过 ≥10s 采样窗口）远晚于拾取延迟，故被拾取实体均已删除。
		返回采样时刻仍存续的实体数（本表创造模式恒为 0）。
		"""
		dropped: list[Stack] = []
		for index in (0, 1):
			stack = self.slots[index]
			if stack.is_empty():
				continue
			self._clear(index)
			if not self.insert_stack_to_inventory(stack):
				# insert_stack_to_inventory 失败时 stack.count 保存未能放入的余量 → 掉落
				dropped.append(Stack(stack.item, stack.count))
		return self.resolve_dropped_entities(dropped)

	def resolve_dropped_entities(self, dropped: list[Stack]) -> int:
		"""模拟掉落实体在收尾采样时刻的存续数（勘误 #5；源码链见 close_window docstring）。

		每实体经拾取延迟后由 onPlayerCollision 调 PlayerInventory.insertStack：放入成功或（无空间）
		命中 insertStack:306-308 创造模式兜底，均使堆叠清空 → onPlayerCollision:340-343 discard()；
		创造模式下必被删除，仅「非创造 + 无空间」才存续。
		"""
		remaining = 0
		for stack in dropped:
			if not self.pickup_entity(stack):
				remaining += 1
		return remaining

	def pickup_entity(self, stack: Stack) -> bool:
		"""镜像 ItemEntity.onPlayerCollision:333-347 的拾取步：返回实体是否被删除。

		- 有空间：insertStack（PlayerInventory:275-312）并入/放入 → 返回 true → 堆叠空 → discard；
		- 无空间且创造模式：insertStack:306-308 `setCount(0); return true` → 同上 discard；
		- 无空间且非创造：insertStack 返回 false → onPlayerCollision 不 discard（实体存续）。
		"""
		before = stack.count
		self.insert_stack_to_inventory(stack)
		if stack.count < before:
			return True
		if CREATIVE:
			# insertStack:306-308：数量未变且 creativeMode → setCount(0) 返回 true → 实体删除
			stack.count = 0
			return True
		return False

	def final_counts(self) -> dict:
		"""终态背包计数（3-38；恰 FINAL_KEYS 三键，掉落实体不计入）。"""
		counts = {key: 0 for key in FINAL_KEYS}
		for i in range(3, 39):
			slot = self.slots[i]
			if not slot.is_empty() and slot.item in _FINAL_ITEM_NAMES:
				counts[_FINAL_ITEM_NAMES[slot.item]] += slot.count
		return counts


def simulate_case(case) -> SimResult:
	"""运行单个用例的序列模拟：单批执行 → moveOut/post-loop blocked → 关窗。

	流程镜像 AbstractTradeStrategy.handleMerchantScreenTick:85-184（残留清理无残留即通过；
	扫描语义：21 组用例均为单 offer、开窗 uses=0 未禁用、背包成本充足且单批耗尽 → 一次 runOneBatch 即收尾）。
	"""
	handler = Handler(case)
	result = SimResult(case_id=case.id)
	outcome = handler.run_one_batch()
	if outcome.exec_record is not None and outcome.branch is not None:
		result.execs.append(outcome.exec_record)
		result.branches.append(outcome.branch)
	# 镜像 runPassLoop:217-220：先累计成交，再 record 记账
	trades_total = outcome.trades
	handler.record(outcome.trades)
	capacity_skips = 1 if outcome.result in ("CAPACITY_SKIP", STOP) else 0
	# 第 5 步：moveOut（:166-168）
	moveout_blocked = not handler.move_out_input_costs()
	# 第 6 步：post-loop——全部跳过且 0 笔交易 → blocked（:170-173）
	blocked = capacity_skips > 0 and trades_total == 0
	# 关窗：槽 0/1 余量 offerOrDrop（:160-180）
	entities = handler.close_window()
	result.session = {
		"trades": trades_total,
		"capacity_skips": capacity_skips,
		"blocked": blocked,
		"moveout_blocked": moveout_blocked,
		"stuck": False,
	}
	result.final = handler.final_counts()
	result.entities = entities
	return result


# ---------------------------------------------------------------------------
# 对照输出与 --compare
# ---------------------------------------------------------------------------


def _exec_tuple(record: dict) -> tuple:
	"""EXECUTING 记录 → 七键定序元组（EXEC_KEYS 顺序）。"""
	return tuple(record[key] for key in EXEC_KEYS)


def _session_tuple(session: dict) -> tuple:
	"""会话记录 → 五键定序元组（SESSION_KEYS 顺序）。"""
	return tuple(session[key] for key in SESSION_KEYS)


def _final_tuple(counts: dict) -> tuple:
	"""终态计数 → 三键定序元组（FINAL_KEYS 顺序）。"""
	return tuple(counts[key] for key in FINAL_KEYS)


def _summarize_pinned(case) -> str:
	"""钉扎侧摘要串（对照行用）。"""
	return (
		f"exec=[{', '.join(str(_exec_tuple(entry)) for entry in case.expect_exec)}] "
		f"stop={bool(case.expect_stop)} session={_session_tuple(case.expect_session)} "
		f"final={_final_tuple(case.expect_final)} ent={case.expect_entities}"
	)


def _summarize_ref(result: SimResult) -> str:
	"""模拟器侧摘要串（对照行用）。"""
	return (
		f"exec=[{', '.join(str(_exec_tuple(entry)) for entry in result.execs)}] "
		f"stop={result.branches[0] == STOP if result.branches else False} "
		f"session={_session_tuple(result.session)} final={_final_tuple(result.final)} ent={result.entities}"
	)


def diff_case(case, result: SimResult) -> list[str]:
	"""ref vs 钉扎逐项比对；返回 mismatch 明细列表（空 = 完全一致）。

	比对面（计划 Todo 6 验收）：expect_exec 前 len(pinned) 条全七键按序、expect_stop 分支一致性、
	expect_session 五键、expect_final 三键、expect_entities。
	"""
	diffs: list[str] = []
	pinned_execs = case.expect_exec
	if len(result.execs) < len(pinned_execs):
		diffs.append(f"exec count ref={len(result.execs)} < pinned={len(pinned_execs)}")
	for index, pinned in enumerate(pinned_execs):
		if index >= len(result.execs):
			break
		ref_entry = result.execs[index]
		for key in EXEC_KEYS:
			if pinned.get(key) != ref_entry.get(key):
				diffs.append(f"exec[{index}].{key} pinned={pinned.get(key)} ref={ref_entry.get(key)}")
	ref_stop = bool(result.branches) and result.branches[0] == STOP
	if bool(case.expect_stop) != ref_stop:
		branch = result.branches[0] if result.branches else "N/A"
		diffs.append(f"expect_stop pinned={bool(case.expect_stop)} ref={ref_stop}（branch={branch}）")
	for key in SESSION_KEYS:
		if case.expect_session.get(key) != result.session.get(key):
			diffs.append(f"session.{key} pinned={case.expect_session.get(key)} ref={result.session.get(key)}")
	for key in FINAL_KEYS:
		if case.expect_final.get(key) != result.final.get(key):
			diffs.append(f"final.{key} pinned={case.expect_final.get(key)} ref={result.final.get(key)}")
	if case.expect_entities != result.entities:
		diffs.append(f"expect_entities pinned={case.expect_entities} ref={result.entities}")
	return diffs


def compare(verbose: bool = True) -> bool:
	"""ref 输出 vs 表钉扎逐组比对；全部一致打印 `21/21 match` 并返回 True，否则打印明细返回 False。"""
	matched = 0
	for case in CASES:
		result = simulate_case(case)
		diffs = diff_case(case, result)
		if not diffs:
			matched += 1
			if verbose:
				print(f"[OK] {case.id} pinned={_summarize_pinned(case)}")
				print(f"      ref   ={_summarize_ref(result)}")
		elif verbose:
			print(f"[MISMATCH] {case.id} pinned={_summarize_pinned(case)}")
			print(f"          ref   ={_summarize_ref(result)}")
			for detail in diffs:
				print(f"  [diff] {case.id}: {detail}")
	if verbose:
		print(f"[compare] {matched}/{len(CASES)} match")
	return matched == len(CASES)


def main(argv=None) -> int:
	"""CLI：`--compare`（唯一入口）；无参数打印帮助并返回 2。"""
	# Windows 默认代码页下中文输出会乱码：在 main 内重配置标准流（导入保持零副作用）
	for stream in (sys.stdout, sys.stderr):
		reconfigure = getattr(stream, "reconfigure", None)
		if reconfigure is not None:
			try:
				reconfigure(encoding="utf-8", errors="replace")
			except Exception:
				pass
	parser = argparse.ArgumentParser(description="CAPACITY 独立序列参考模拟器：--compare 复核 21 组钉扎")
	parser.add_argument(
		"--compare", action="store_true", help="ref 全序列输出 vs 表钉扎逐项比对（stdout 含 21/21 match；退出码 0/1）"
	)
	args = parser.parse_args(argv)
	if not args.compare:
		parser.print_help()
		return 2
	return 0 if compare() else 1


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
