# 一次性初始化（每个会话首次进入时执行一次）：重建测试村民、放置双箱、装满输入、清空输出、重置计数并提示
kill @e[type=minecraft:villager,tag=autotrade_test]
summon minecraft:villager {{VILLAGER_X}} {{VILLAGER_Y}} {{VILLAGER_Z}} {Tags:["autotrade_test"],NoAI:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,Age:0,CustomName:'{"text":"AT-TestVillager"}',CustomNameVisible:0b,Health:20.0f,VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:5},Offers:{Recipes:[{buy:{id:"minecraft:emerald",Count:1b},sell:{id:"{{OUTPUT_ITEM}}",Count:1b},uses:0,maxUses:{{MAX_USES}},xp:0,priceMultiplier:0.0f,specialPrice:0,demand:0,rewardExp:0b}]}}
{{SETUP_CONTAINER_LINES}}
# 预置 1 组绿宝石：避免首轮会话因「背包无成本」被记入村民「不匹配」缓存（缓存 TTL 内跳过开窗）
item replace entity @a hotbar.0 with minecraft:emerald 64
scoreboard players set #t_restock autotrade_test 0
scoreboard players set #t_refill autotrade_test 0
scoreboard players set #t_clear autotrade_test 0
scoreboard players set #cleared autotrade_test 0
scoreboard players set #total autotrade_test 0
scoreboard players set #restocks autotrade_test 0
scoreboard players set #setup_done autotrade_test 1
{{VOID_SETUP_LINES}}
tellraw @a [{"text":"[AutoTradeTest] 装置就绪：1 绿宝石 → 1 {{OUTPUT_ITEM}}；{{RESTOCK_READY_HINT}}输出每 {{CLEAR_SECONDS}}s 清空并打印","color":"green"},{"text":" [ready]","color":"dark_gray"}]
