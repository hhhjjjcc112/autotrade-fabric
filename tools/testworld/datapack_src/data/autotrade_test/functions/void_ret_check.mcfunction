# 回程检测：陷阱箱被查看 → 中继器（facing=west 朝箱）持续通电累计；未通电（含缺失）清零；计满 10 视为模组请求返航
execute if block {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater[powered=true] run scoreboard players add #ret_power autotrade_test 1
execute unless block {{RET_RPT_X}} {{RET_RPT_Y}} {{RET_RPT_Z}} minecraft:repeater[powered=true] run scoreboard players set #ret_power autotrade_test 0
execute if score #ret_power autotrade_test matches 10 run function autotrade_test:void_ret_fire
