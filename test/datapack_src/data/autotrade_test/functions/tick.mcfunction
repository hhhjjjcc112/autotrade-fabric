# 每 tick：区块已加载且有玩家时确保初始化一次；初始化完成后推进周期计时器
execute if entity @a if loaded {{VILLAGER_BLOCK_X}} {{VILLAGER_Y}} {{VILLAGER_Z}} unless score #setup_done autotrade_test matches 1 run function autotrade_test:setup
execute if score #setup_done autotrade_test matches 1 if entity @a run function autotrade_test:tick_periodic
{{VOID_TICK_LINE}}
