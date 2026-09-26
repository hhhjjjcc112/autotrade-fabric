# 虚空状态打印：读村民 uses（未加载 = -1）与回程检测器状态 → 聊天栏输出 ASCII 标记 → 复位统计计时器
scoreboard players set #v_uses autotrade_test -1
execute if entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] store result score #v_uses autotrade_test run data get entity @e[type=minecraft:villager,tag=autotrade_test,limit=1] Offers.Recipes[0].uses
# 回程诊断：仅玩家在岛侧附近时检测（-2 = 不在岛侧；-1 = 方块缺失；0 = 存在未通电；1 = 通电）
scoreboard players set #rpt_diag autotrade_test -2
execute positioned {{ISLAND_BLOCK_X}} {{ISLAND_Y}} {{ISLAND_BLOCK_Z}} if entity @a[distance=..64] run function autotrade_test:void_ret_diag
tellraw @a [{"text":"[AutoTradeTest] [void out=","color":"gray"},{"score":{"name":"#cycles_out","objective":"autotrade_test"},"color":"yellow"},{"text":" back=","color":"gray"},{"score":{"name":"#cycles_back","objective":"autotrade_test"},"color":"yellow"},{"text":" uses=","color":"gray"},{"score":{"name":"#v_uses","objective":"autotrade_test"},"color":"yellow"},{"text":" rp=","color":"gray"},{"score":{"name":"#ret_power","objective":"autotrade_test"},"color":"yellow"},{"text":" rpt=","color":"gray"},{"score":{"name":"#rpt_diag","objective":"autotrade_test"},"color":"yellow"},{"text":"]","color":"gray"}]
scoreboard players set #t_status autotrade_test 0
