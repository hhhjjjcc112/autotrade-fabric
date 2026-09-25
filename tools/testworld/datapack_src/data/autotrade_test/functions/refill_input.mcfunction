# 输入补货：把输入箱填满 27 组绿宝石（「无限刷新」= 每次触发都恢复满箱）
data modify block {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} Items set value [{{INPUT_STACKS}}]
scoreboard players set #t_refill autotrade_test 0
