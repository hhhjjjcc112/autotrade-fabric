# 输出清空：统计输出箱内物品总数 → 清空箱子 → 累计总数 → 打印本次清空数/累计数/补货次数
scoreboard players set #cleared autotrade_test 0
scoreboard players set #tmp autotrade_test 0
{{COUNT_SLOTS}}
data modify block {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} Items set value []
scoreboard players operation #total autotrade_test += #cleared autotrade_test
tellraw @a [{"text":"[AutoTradeTest] 清空 ","color":"gray"},{"score":{"name":"#cleared","objective":"autotrade_test"},"color":"yellow"},{"text":" 个 {{OUTPUT_ITEM}}（累计 ","color":"gray"},{"score":{"name":"#total","objective":"autotrade_test"},"color":"yellow"},{"text":"，补货 ","color":"gray"},{"score":{"name":"#restocks","objective":"autotrade_test"},"color":"yellow"},{"text":" 次）","color":"gray"},{"text":" [cleared=","color":"dark_gray"},{"score":{"name":"#cleared","objective":"autotrade_test"},"color":"dark_gray"},{"text":" total=","color":"dark_gray"},{"score":{"name":"#total","objective":"autotrade_test"},"color":"dark_gray"},{"text":" restocks=","color":"dark_gray"},{"score":{"name":"#restocks","objective":"autotrade_test"},"color":"dark_gray"},{"text":"]","color":"dark_gray"}]
scoreboard players set #t_clear autotrade_test 0
