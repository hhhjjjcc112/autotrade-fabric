"""Minescript 交易界面监视脚本。

用法（游戏内聊天框）：
    \\trade_probe 10      # 监视 10 秒（默认 10 秒）

作用：
  在监视期间，只要打开了村民交易 / 容器界面，就每秒打印一次界面里的物品，
  便于观察 autotrade 在交易界面上的行为（例如它是否打开了窗口、交易前后物品变化）。
"""

import sys
import time

from minescript import container_get_items, echo, screen_name


def main(seconds: float = 10.0):
    deadline = time.time() + float(seconds)
    echo(f"开始监视交易界面 {seconds} 秒 ...")
    while time.time() < deadline:
        name = screen_name()
        if name is not None:
            items = container_get_items()
            if items is not None:
                summary = ", ".join(f"[{it.slot}] {it.item} x{it.count}" for it in items)
                echo(f"界面={name!r} 物品: {summary}")
            else:
                echo(f"界面={name!r}（非容器界面）")
        time.sleep(1.0)
    echo("监视结束")


if __name__ == "__main__":
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0
    main(secs)
