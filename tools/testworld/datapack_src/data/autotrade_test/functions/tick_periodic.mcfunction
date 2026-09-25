# 推进周期计时器；到点且目标区块加载时触发任务（STATIC 含村民补货；VOID 补货置空 + 追加虚空状态打印）
{{RESTOCK_TIMER_BLOCK}}
scoreboard players add #t_refill autotrade_test 1
scoreboard players add #t_clear autotrade_test 1
execute if score #t_refill autotrade_test matches {{REFILL_TICKS}}.. if loaded {{INPUT_CHEST_X}} {{INPUT_CHEST_Y}} {{INPUT_CHEST_Z}} run function autotrade_test:refill_input
execute if score #t_clear autotrade_test matches {{CLEAR_TICKS}}.. if loaded {{OUTPUT_CHEST_X}} {{OUTPUT_CHEST_Y}} {{OUTPUT_CHEST_Z}} run function autotrade_test:clear_output
{{VOID_STATUS_BLOCK}}
