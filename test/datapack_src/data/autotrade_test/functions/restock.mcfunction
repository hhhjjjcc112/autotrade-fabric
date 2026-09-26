# 村民补货：把交易 uses 重置为 0（模拟村民工作补货）；仅在村民存在时累计补货次数；复位计时器
execute if entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] run data modify entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] Offers.Recipes set value [{buy:{id:"minecraft:emerald",Count:1b},sell:{id:"{{OUTPUT_ITEM}}",Count:1b},uses:0,maxUses:{{MAX_USES}},xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}]
execute if entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] run scoreboard players add #restocks autotrade_test 1
scoreboard players set #t_restock autotrade_test 0
