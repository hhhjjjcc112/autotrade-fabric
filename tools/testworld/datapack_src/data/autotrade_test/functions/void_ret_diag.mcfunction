# 回程诊断（仅玩家在岛侧附近时由 void_status 调用）：把中继器检测器状态写入分数供状态行打印
# 调用方先置 -2（不在岛侧）；本函数置 -1（方块缺失）/ 0（存在未通电）/ 1（通电）
scoreboard players set #rpt_diag autotrade_test -1
execute if block {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater run scoreboard players set #rpt_diag autotrade_test 0
execute if block {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater[powered=true] run scoreboard players set #rpt_diag autotrade_test 1
