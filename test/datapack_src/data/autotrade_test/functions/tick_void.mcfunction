# VOID 每 tick 驱动：去程（成就 → 武装倒计时 → 传送岛侧）与回程（陷阱箱信号）检测
# 去程：成就授予 → 武装倒计时；倒计时递减；归零触发
execute as @a[advancements={autotrade_test:outbound=true}] run function autotrade_test:void_out_arm
execute if score #t_out autotrade_test matches 1.. run scoreboard players remove #t_out autotrade_test 1
execute if score #t_out autotrade_test matches 0 run function autotrade_test:void_out_fire
# 岛侧装置惰性放置：/setblock 要求区块已加载（getLoadedBlockPos），家侧 setup 时岛侧未加载 → 无法预放；
# 在岛侧区块加载（玩家到达）且装置缺失/朝向不符时补放（幂等，每 tick 三次轻量判定）
execute if loaded {{RET_CHEST_X}} {{RET_CHEST_Y}} {{RET_CHEST_Z}} unless block {{RET_CHEST_X}} {{RET_CHEST_Y}} {{RET_CHEST_Z}} minecraft:trapped_chest run setblock {{RET_CHEST_X}} {{RET_CHEST_Y}} {{RET_CHEST_Z}} minecraft:trapped_chest
# 中继器检测器：facing=west 朝陷阱箱（闸门从 FACING 侧取电；朝向不符时重放修正）
execute if loaded {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} unless block {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater[facing=west] run setblock {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater[facing=west,delay=1]
# 岛侧 IO 容器（输入/输出箱）：惰性放置；输入箱为空时立即补满（首放/清空后；周期补货亦见 tick_periodic）
execute if loaded {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} unless block {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} minecraft:chest run setblock {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} minecraft:chest
execute if loaded {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} unless block {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} minecraft:chest run setblock {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} minecraft:chest
execute if loaded {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} if block {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} minecraft:chest unless data block {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} Items run function autotrade_test:refill_input
# 回程：仅玩家位于岛侧附近时检测（避免读取未加载区块）
execute positioned {{ISLAND_BLOCK_X}} {{ISLAND_Y}} {{ISLAND_BLOCK_Z}} if entity @a[distance=..64] run function autotrade_test:void_ret_check
