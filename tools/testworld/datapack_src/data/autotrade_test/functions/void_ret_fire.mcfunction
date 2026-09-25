# 归程触发：把 #ret_power 抬到 100（脱离 matches 10，防同一次信号重复触发）→ 传送回家侧 → 回程计数 +1
scoreboard players set #ret_power autotrade_test 100
tp @a {{HOME_TP_X}} {{HOME_TP_Y}} {{HOME_TP_Z}}
scoreboard players add #cycles_back autotrade_test 1
