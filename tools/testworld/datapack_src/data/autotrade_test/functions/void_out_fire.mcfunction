# 去程触发：倒计时复位为空闲态 -1 → 全体玩家传送至岛侧着陆点 → 去程计数 +1
scoreboard players set #t_out autotrade_test -1
tp @a {{ISLAND_X}} {{ISLAND_Y}} {{ISLAND_Z}}
scoreboard players add #cycles_out autotrade_test 1
