# 去程武装：撤销成就（一次性触发，允许下次互动再次武装）；倒计时空闲态 -1 时装载固定传送延迟
advancement revoke @s only autotrade_test:outbound
execute if score #t_out autotrade_test matches -1 run scoreboard players set #t_out autotrade_test {{TELEPORT_DELAY_TICKS}}
