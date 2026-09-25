# 每次世界加载（含 /reload）执行：确保计分板目标存在，并把初始化标记清零，tick 中会重新执行一次 setup
scoreboard objectives add autotrade_test dummy
scoreboard players set #setup_done autotrade_test 0
