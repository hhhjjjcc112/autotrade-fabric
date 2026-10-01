# 一次性初始化（每个会话首次进入时执行一次）：重建测试村民、放置双箱、装满输入、清空输出、重置计数并提示
kill @e[type=minecraft:villager,tag=autotrade_test]
{{VILLAGER_SUMMON_LINES}}
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
{{MOVING_SETUP_LINES}}
tellraw @a [{"text":"[AutoTradeTest] 装置就绪：{{READY_TRADE_DESC}}；{{RESTOCK_READY_HINT}}输出每 {{CLEAR_SECONDS}}s 清空并打印","color":"green"},{"text":" [ready]","color":"dark_gray"}]
