"""Minescript 探测脚本：查看当前界面、容器内容与玩家背包。

用法（游戏内聊天框，注意前缀是反斜杠）：
    \\probe

作用：
  1. 打印当前 GUI 界面名（无界面时为 None）
  2. 若打开了容器 / 村民交易界面，逐条打印其中的物品（slot / item / count）
  3. 打印玩家背包与主副手物品
  4. 演示按键绑定注入（默认注释，避免误触）
"""

# 说明：minescript 库由 Minescript mod 运行时安装到 minescript/system/lib/（本仓库已预解压）。
# 静态检查器若无法解析该导入属正常现象；运行时由 Minescript 注入 sys.path。
from minescript import (
    container_get_items,
    echo,
    player_hand_items,
    player_inventory,
    screen_name,
)


def main():
    # 1) 当前 GUI 界面名（有标题返回标题，否则返回类名，如 "MerchantScreen"；无界面为 None）
    name = screen_name()
    echo(f"screen_name() = {name!r}")

    # 2) 打开中的容器 / 村民交易界面里的物品
    if name is None:
        echo("当前没有打开任何 GUI 界面")
    else:
        items = container_get_items()
        if items is None:
            echo("当前界面不是容器/交易界面（container_get_items() 返回 None）")
        else:
            echo(f"容器物品数: {len(items)}")
            for it in items:
                echo(f"  slot={it.slot} selected={it.selected} {it.item} x{it.count}")

    # 3) 玩家背包与主副手（player_hand_items() 返回 HandItems 数据类：.main_hand / .off_hand）
    inv = player_inventory()
    echo(f"背包物品数: {len(inv)}")
    hands = player_hand_items()
    echo(
        f"主手: {hands.main_hand.item} x{hands.main_hand.count}; "
        f"副手: {hands.off_hand.item} x{hands.off_hand.count}"
    )

    # 4) 按键绑定注入演示（按名称操作原版按键绑定）
    #    例如按下/松开右键 "key.use"、打开背包 "key.inventory"
    # press_key_bind("key.use", True)    # 按下
    # press_key_bind("key.use", False)   # 松开


if __name__ == "__main__":
    main()
