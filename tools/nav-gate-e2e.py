#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""归位阶段 2 收尾（GateTerrain）的真机验证：寻路的地形许可从 ALLOW_ALL 换成权限快照。

背景（为什么要这么测）：
  `CompanionNav.to` 用的是 `RouteSpec.defaults()`——dig=false / place=false / consent=true。
  `CostModel.admitDig`/`admitPlace` 在 `!spec.dig()`（`!spec.place()`）时**直接早退**，
  根本不问 TerrainPolicy。所以平时走路**碰不到 GateTerrain**。
  只有「搜不到路」时，`Diagnosis.exhausted` 会用 `spec.edit().changes(true)`（许挖许放）
  再搜一次——那一次才逐格问 GateTerrain。诊断结果只报不执行，世界不会被改。

⚠️ 第一版踩的坑（记下来别再犯）：目标埋在**开阔**地下的石头里 → 可达格无限，A* 两次搜索
  都 `停因 BUDGET` → `Diagnosis.of` 见 BUDGET 直接 `OutOfBudget` 早退，**走不到**「许挖再搜」
  那一步 → GateTerrain 一次都没被问过。必须把场地**密闭**，让搜索穷尽（EXHAUSTED）。
  另外目标埋在**脚下**：向下挖不必额外给脑袋留空间，横着挖穿 2 格高的墙还得再挖头顶那格。

据此把小室密闭、目标埋在她脚下的石头里，制造「不改地形就到不了」的局面，看诊断的结论：

  A) 不加规则 → GateTerrain 对天然石头答 ALLOW（出厂 allow 行）→ 找得到路
     → NeedsChanges → 工具回「要改 N 格地形（挖开或垫上）才过得去，而这一趟不许改地形」
  B) 加 deny break(minecraft:stone) → GateTerrain 对石头答 DENY → 找不着路
     → NoRoute → 工具回「按现在的方式走不过去（不挖不垫的话绕不过去）」
  C) 去掉规则 → 又回到 A（证明是规则在起作用，不是环境变了）

**B 是决定性的**：ALLOW_ALL 永远答 ALLOW，绝不可能出现 NoRoute。

用法：python nav-gate-e2e.py
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402

STONE = "minecraft:stone"


def step(n, title):
    print("\n" + "=" * 68, flush=True)
    print("第 %s 步：%s" % (n, title), flush=True)
    print("=" * 68, flush=True)


def settle(name, tries=15):
    last = None
    for _ in range(tries):
        p = P.pos_of(name)
        if p is not None and last is not None and p == last:
            return p
        last = p
        time.sleep(1)
    return last


def goto(x, y, z, timeout=300):
    return P.call("goto", {"companion": P.COMPANION, "x": x, "y": y, "z": z}, timeout=timeout)


def verdict(text, want):
    """want: 'changes' | 'noroute'"""
    if want == "changes":
        ok = ("要改" in text) or ("格地形" in text)
        return ok, "期望「要改 N 格地形…」（GateTerrain 答 ALLOW，诊断搜到了挖穿石头的路）"
    ok = ("走不过去" in text) or ("绕不过去" in text)
    return ok, "期望「按现在的方式走不过去」（GateTerrain 答 DENY，诊断搜不到路）"


def main():
    d, libs = P.libraries()
    P.start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来")
        return 1
    print(">> 服务端就绪", flush=True)

    P.start_client(d, libs)
    if P.wait_new(r"%s joined the game" % P.PLAYER, 0, 300, P.server) is None:
        print("!! 客户端没连进来")
        return 1
    print(">> 客户端进服（主人 %s 在场）" % P.PLAYER, flush=True)

    step(1, "等主人落地 + 召唤 Alice")
    tpos = settle(P.PLAYER, 25)
    print("   %s 落在 %s" % (P.PLAYER, tpos))
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    if P.wait_new(r"同伴 %s 已召唤" % P.COMPANION, 0, 20, P.server) is None:
        print("!! 召唤失败")
    apos = settle(P.COMPANION, 20)
    print("   Alice 落在 %s" % (apos,))
    if apos is None:
        print("!! 读不到 Alice 坐标")
        return 1

    # 清干净：模式 ask、规则表空（上一轮可能留了 drop(...) 之类的 allow 行）
    P.cmd("/execute as %s run numen permission mode %s ask" % (P.PLAYER, P.COMPANION))
    P.cmd("/execute as %s run numen permission rules reset" % P.PLAYER)
    time.sleep(0.6)

    # ---- 0) 回归：GateTerrain 接线后，普通走路照旧能走通 ----
    step(2, "回归：GateTerrain 接线后，普通走路照旧（CompanionPorts 现在每次都取 gateFor 快照）")
    p = P.pos_of(P.COMPANION) or apos
    ax, ay, az = p
    target = (ax + 4, ay, az)
    r = goto(*target)
    print("   goto %s → %s" % (target, r))
    print("   → %s" % ("走到了 = 寻路没被这次接线弄坏 ✅" if "走到了" in r else "没走到（看下面地形测试）⚠️"))

    # ---- 1) 搭一个**密闭**石头小室，把她 tp 进去，目标埋在脚下石头里 ----
    #
    # ⚠️ 必须密闭。第一版把目标埋在开阔地下的石头里，结果两次搜索都 `停因 BUDGET`：
    # 地面开阔、可达格无限，A* 搜到全局预算耗尽也没得出结论；而 `Diagnosis.of` 见 BUDGET
    # 直接 `OutOfBudget` 早退（`case BUDGET -> new Outcome.OutOfBudget()`），**根本走不到**
    # 「许挖再搜一次」那一步——于是 GateTerrain 一次都没被问过，测了个寂寞。
    # 密闭小室把可达格压到十几个，搜索**穷尽**（EXHAUSTED）才会进 diagnosis。
    #
    # ⚠️ 目标埋在**脚下**而不是墙后：向下挖不用额外给脑袋留空间（挖开的上一格本来就是空气），
    # 而横着挖穿 2 格高的墙还得再挖头顶那一格，多一个不确定因素。
    step(3, "搭密闭石头小室并把 Alice tp 进去；目标埋在她脚下的实心石头里（不改地形够不着）")
    p = P.pos_of(P.COMPANION) or apos
    ax, ay, az = p
    # ⚠️ 小室要建在**世界里**：1.16.5 主世界高度是 0..255，`/fill` 碰到 y<0 会**静默失败**
    # （第一版把盒子从她脚下的 y=4 往下挖到 -2，整条 fill 报错没生效，她其实站在开阔地上，
    #  于是搜索又是 BUDGET）。所以固定在一个舒服的高度悬空搭盒子，再把她 tp 进去。
    bx, by, bz = ax + 12, 60, az
    P.cmd("/fill %d %d %d %d %d %d %s" % (bx - 2, by - 6, bz - 2, bx + 2, by + 2, bz + 2, STONE))
    P.cmd("/fill %d %d %d %d %d %d minecraft:air" % (bx - 1, by, bz - 1, bx + 1, by + 1, bz + 1))
    P.cmd("/tp %s %s %d %s" % (P.COMPANION, bx + 0.5, by, bz + 0.5))
    time.sleep(0.8)
    # 确认小室真的搭好了（/fill 越界或落在未加载区块都会静默不生效）
    m = len(P.read(P.SERVER_LOG))
    P.cmd("/execute if block %d %d %d %s run say CHK_FLOOR" % (bx, by - 1, bz, STONE))
    P.cmd("/execute if block %d %d %d minecraft:air run say CHK_ROOM" % (bx, by, bz))
    P.cmd("/execute if block %d %d %d %s run say CHK_CEIL" % (bx, by + 2, bz, STONE))
    P.cmd("/execute if block %d %d %d %s run say CHK_DEEP" % (bx, by - 4, bz, STONE))
    time.sleep(0.8)
    seg = P.read(P.SERVER_LOG)[m:]
    checks = {k: (k in seg) for k in ("CHK_FLOOR", "CHK_ROOM", "CHK_CEIL", "CHK_DEEP")}
    print("   小室自检：地板石头=%s 空腔空气=%s 天花板石头=%s 目标处石头=%s"
          % tuple("✅" if checks[k] else "❌"
                  for k in ("CHK_FLOOR", "CHK_ROOM", "CHK_CEIL", "CHK_DEEP")))
    if not all(checks.values()):
        print("   !! 小室没搭成，后面的结论不作数")
    ipos = settle(P.COMPANION, 15)
    if ipos != (bx, by, bz):
        print("   !! tp 没落在小室中心：她在 %s，期望 %s" % (ipos, (bx, by, bz)))
    print("   小室 %s..%s（%d 宽 × 9 高 × %d 深），里面 3x2x3 空腔；她在 %s"
          % ((bx - 2, by - 6, bz - 2), (bx + 2, by + 2, bz + 2), 5, 5, ipos))
    dig_target = (bx, by - 4, bz)
    print("   目标 %s（她脚下 by-1 起往下 6 层全是石头，要挖穿 3 格才够得着）" % (dig_target,))
    print("   （诊断只搜不走，世界不会被改）")

    # ---- 2) A：不加规则 → 诊断许挖重搜 → GateTerrain 答 ALLOW → NeedsChanges ----
    step(4, "A：不加规则 → 期望「要改 N 格地形…」（GateTerrain 对天然石头答 ALLOW）")
    mark = len(P.read(P.SERVER_LOG))
    rA = goto(*dig_target)
    print("   goto %s → %s" % (dig_target, rA))
    okA, whyA = verdict(rA, "changes")
    print("   %s（%s）" % ("✅" if okA else "❌", whyA))

    # ---- 3) B：deny break(minecraft:stone) → GateTerrain 答 DENY → NoRoute ----
    step(5, "B：加 deny break(minecraft:stone) → 期望「按现在的方式走不过去」（决定性证据）")
    P.cmd("/execute as %s run numen permission rules add deny break(minecraft:stone)" % P.PLAYER)
    time.sleep(0.6)
    P.cmd("/execute as %s run numen permission rules list" % P.PLAYER)
    time.sleep(0.4)
    mark = len(P.read(P.SERVER_LOG))
    rB = goto(*dig_target)
    print("   goto %s → %s" % (dig_target, rB))
    okB, whyB = verdict(rB, "noroute")
    print("   %s（%s）" % ("✅" if okB else "❌", whyB))

    # ---- 4) C：去掉规则 → 又回到 A ----
    step(6, "C：去掉规则 → 期望又回到「要改 N 格地形…」（证明是规则在起作用）")
    P.cmd("/execute as %s run numen permission rules remove deny 1" % P.PLAYER)
    time.sleep(0.6)
    mark = len(P.read(P.SERVER_LOG))
    rC = goto(*dig_target)
    print("   goto %s → %s" % (dig_target, rC))
    okC, whyC = verdict(rC, "changes")
    print("   %s（%s）" % ("✅" if okC else "❌", whyC))

    print("\n" + "=" * 68)
    print("小结：A=%s  B=%s  C=%s" % ("✅" if okA else "❌", "✅" if okB else "❌", "✅" if okC else "❌"))
    print("=" * 68)

    step(7, "关服")
    P.cmd("/stop")
    t0 = time.time()
    try:
        P.server.wait(timeout=60)
        print(">> 服务端 %.1fs 后退出 code=%s" % (time.time() - t0, P.server.returncode))
    except subprocess.TimeoutExpired:
        print(">> !! 60s 未退出")

    print("\n---- 寻路 / 诊断相关日志 ----")
    for l in P.read(P.SERVER_LOG).splitlines():
        if any(k in l for k in ("路径", "诊断", "结局", "numen-nav", "[numen]")):
            print("  " + l)

    P.kill_all()
    return 0 if (okA and okB and okC) else 2


if __name__ == "__main__":
    sys.exit(main())
