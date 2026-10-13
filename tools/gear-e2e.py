#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 2 真机验收：Wardrobe 归位。

  1) 起服 + 进服 + 召唤 Alice
  2) /give 一顶钻石头盔 → MCP 调 equip → Wardrobe.wear 应把它穿到头上
     （经原版判据：Mob.getEquipmentSlotForItem → HEAD 槽）
  3) MCP 调 inv 看背包摘要 → 盔甲行 head=diamond_helmet = 真的穿上了
  4) 非穿戴物（圆石）equip → 应被拒并说明（Wardrobe 的 UNKNOWN 失败文案）

用法：python gear-e2e.py
"""
import os
import re
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402

BUILT = os.path.join(P.TS, "..", "numen-1.16.5", "core", "build", "libs",
                     "Numen-fabric-1.16.5-0.1.4-1.16.5.jar")
DEPLOYED = os.path.join(P.TS, "mods", "numen-0.1.4-1.16.5.jar")


def step(n, title):
    print("\n" + "=" * 68, flush=True)
    print("第 %s 步：%s" % (n, title), flush=True)
    print("=" * 68, flush=True)


def main():
    shutil.copyfile(BUILT, DEPLOYED)
    print(">> 新 jar 已部署", flush=True)

    d, libs = P.libraries()
    P.start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来")
        return 1
    P.start_client(d, libs)
    if P.wait_new(r"%s joined the game" % P.PLAYER, 0, 300, P.server) is None:
        print("!! 客户端没连进来")
        return 1
    print(">> 双方就绪", flush=True)

    step(1, "召唤 Alice")
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    time.sleep(3)

    step(2, "equip 一顶钻石头盔（走 Wardrobe.wear → 原版 HEAD 槽）")
    P.cmd("/give %s minecraft:diamond_helmet 1" % P.COMPANION)
    time.sleep(1)
    r = P.call("equip", {"companion": P.COMPANION, "item": "diamond_helmet"})
    print("   equip → %s" % r)
    print("   → 判定：%s" % ("过" if "diamond_helmet" in r and ("head" in r or "头" in r) else "!! 不符"))

    step(3, "inv 看背包摘要：盔甲行应有 head=diamond_helmet")
    bag = P.call("inventory", {"companion": P.COMPANION})
    print("   inv → %s" % bag.replace("\n", " | ")[:200])
    print("   → 判定：%s" % ("过" if "Diamond Helmet" in bag else "!! 没看到"))

    step(4, "非穿戴物（圆石）equip 应被拒")
    P.cmd("/give %s minecraft:stone 1" % P.COMPANION)
    time.sleep(1)
    r2 = P.call("equip", {"companion": P.COMPANION, "item": "stone"})
    print("   equip stone → %s" % r2)
    print("   → 判定：%s" % ("过" if ("not something you wear" in r2 or "hold" in r2) else "!! 没拒"))

    P.kill_all()
    print("\n>> 完事", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
