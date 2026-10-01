# 村民补货：把交易 uses 重置为 0（模拟村民工作补货）；仅在村民存在时累计补货次数；复位计时器
{{RESTOCK_LINES}}
execute if entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] run scoreboard players add #restocks autotrade_test 1
scoreboard players set #t_restock autotrade_test 0
