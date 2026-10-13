#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""归位阶段 2 的真机验证：动手的工具过权限层 + 征询往返 + 放置账本。

一趟跑完（每件都打印证据）：
  1) 放行路：挖脚下一格普通方块 —— 出厂 allow 行覆盖，不该弹征询
  2) 要问路：drop 一件东西 —— 出厂 ask 行 → 弹征询 → 控制台以 T 的身份点「允许」→ 东西掉出来
  3) 放置账本：让 Alice 自己放一个金块（过 BlockItem.place 的 mixin）→
     加一条 deny break(self_placed) → 再挖它。命中规则 = 账本真的记下了她放的这一格
  4) 记住：再问一次、答「记住」→ 主人的 allow 表里应多一行
  5) 拒绝路：mode observe → 挖 → 当场拒绝

用法：python perm-live-test.py
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402


def step(n, title):
    print("\n" + "=" * 68, flush=True)
    print("第 %s 步：%s" % (n, title), flush=True)
    print("=" * 68, flush=True)


def settle(name, tries=15):
    """等她/他落地：连续两次读到的坐标一样就当稳了。"""
    last = None
    for _ in range(tries):
        p = P.pos_of(name)
        if p is not None and last is not None and p == last:
            return p
        last = p
        time.sleep(1)
    return last


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

    step(1, "等主人落地（客户端可能从高空出生，没落地时召唤会把同伴一起带下去摔死）")
    tpos = settle(P.PLAYER, 25)
    print("   %s 落在 %s" % (P.PLAYER, tpos))

    step(2, "召唤同伴 Alice，并等她落地")
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    if P.wait_new(r"同伴 %s 已召唤" % P.COMPANION, 0, 20, P.server) is None:
        print("!! 召唤失败")
    apos = settle(P.COMPANION, 20)
    print("   Alice 落在 %s" % (apos,))
    if apos is None:
        print("!! 读不到 Alice 坐标")
        return 1
    ax, ay, az = apos

    # 权限模式按 (主人, 同伴UUID) 落盘，上一轮可能给她留下 observe/bypass；先清成 ask。
    # ⚠️ /numen permission 那几个子命令都要「玩家身份」的来源（内部 getPlayerOrException），
    # 控制台直接发会失败——必须 /execute as <主人> run 包一层。
    P.cmd("/execute as %s run numen permission mode %s ask" % (P.PLAYER, P.COMPANION))
    # 上一轮答过「记住」的话，主人 allow 表里会留下 drop(...)，这一轮就不会再问了——先清干净。
    P.cmd("/execute as %s run numen permission rules reset" % P.PLAYER)
    time.sleep(0.5)

    # ---- 1) 放行路 ----
    step(3, "放行路：挖脚下一格普通方块（出厂 allow 行，不该弹征询）")
    mark = len(P.read(P.SERVER_LOG))
    p = P.pos_of(P.COMPANION) or apos
    for dy in (1, 2, 3):
        r = P.call("mine", {"companion": P.COMPANION, "x": p[0], "y": p[1] - dy, "z": p[2]})
        print("   mine y-%d → %s" % (dy, r))
        if "空气" not in r:
            break
    time.sleep(1)
    print("   → 弹征询了吗：%s（期望「没有」）"
          % ("弹了" if re.search(r"ask #\d+", P.read(P.SERVER_LOG)[mark:]) else "没有"))

    # ---- 2) 要问路：drop ----
    step(4, "要问路：drop 一件东西（出厂 ask 行 → 应弹征询 → 点允许）")
    P.cmd("/give %s minecraft:gold_block 8" % P.COMPANION)
    time.sleep(1)
    mark = len(P.read(P.SERVER_LOG))
    th, box = P.call_async("drop", {"companion": P.COMPANION, "item": "gold_block", "count": 1})
    rid = P.next_consent(mark, 25)
    print("   → 征询号 #%s" % rid)
    if rid is None:
        print("   !! 没等到征询；MCP 回：%s" % P.wait_mcp(th, box, 10))
    else:
        print("   控制台以主人身份点「允许」")
        P.answer(rid, "allow")
        print("   → MCP 回：%s" % P.wait_mcp(th, box, 60))

    # ---- 3) 放置账本 ----
    step(5, "放置账本：Alice 自己放一个金块 → deny break(self_placed) → 挖它")
    # ⚠️ 她一直在动（被主人推挤/自己走），坐标必须现读，不能用第 2 步那份。
    # ⚠️ 千万别点她**脚下**那一格：Aim.use 会挑它的「上面」，算出来的放置点正是她站的那一格，
    #    而原版不许把方块放进自己身体里 —— BlockItem.canPlace → Level.isUnobstructed 的默认实现
    #    (CollisionGetter) 拿 null 当「谁放的」，**不会**把放置者自己排除掉。真玩家这时也放不下（要跳起来）。
    #    所以在旁边搭一格再点它，放置点落在它头顶、不压她。
    p = P.pos_of(P.COMPANION) or apos
    scan = P.call("scan_blocks", {"companion": P.COMPANION, "radius": 4})
    print("   放之前世界里有没有金块：%s" % (P.nearest(scan, "gold_block"),))
    pillar = (p[0] + 2, p[1], p[2])
    P.cmd("/setblock %d %d %d minecraft:stone" % pillar)
    time.sleep(0.4)
    print("   她站在 %s，旁边搭一格 %s，点它的上面（放置点 = 它头顶，不压她）" % (p, pillar))
    print("   use_block → %s" % P.call("use_block", {"companion": P.COMPANION,
                                                      "x": pillar[0], "y": pillar[1], "z": pillar[2]}))
    time.sleep(1)
    scan = P.call("scan_blocks", {"companion": P.COMPANION, "radius": 4})
    placed = P.nearest(scan, "gold_block")
    print("   放之后找到金块在：%s" % (placed,))
    if placed is None:
        print("   !! 没找到她放的金块：放置那一步没成，账本这步跳过")
    else:
        P.cmd('/execute as %s run numen permission rules add deny break(self_placed)' % P.PLAYER)
        time.sleep(0.5)
        r = P.call("mine", {"companion": P.COMPANION, "x": placed[0], "y": placed[1], "z": placed[2]})
        print("   加规则后挖 → %s" % r)
        print("   → %s" % ("命中规则 = 账本生效 ✅" if "deny" in r or "denied" in r
                           else "没命中规则 = 账本没生效 ❌"))
        P.cmd('/execute as %s run numen permission rules remove deny 1' % P.PLAYER)
        time.sleep(0.5)
        r = P.call("mine", {"companion": P.COMPANION, "x": placed[0], "y": placed[1], "z": placed[2]})
        print("   去掉规则后再挖 → %s（期望能挖掉）" % r)

    # ---- 4) 记住 ----
    step(6, "记住：再问一次 drop，答「允许并记住」")
    mark = len(P.read(P.SERVER_LOG))
    th, box = P.call_async("drop", {"companion": P.COMPANION, "item": "gold_block", "count": 1})
    rid = P.next_consent(mark, 25)
    print("   → 征询号 #%s" % rid)
    if rid is not None:
        P.answer(rid, "remember")
        print("   → MCP 回：%s" % P.wait_mcp(th, box, 60))
    P.cmd("/execute as %s run numen permission rules list" % P.PLAYER)

    # ---- 5) 拒绝路 ----
    step(7, "拒绝路：mode observe → 挖 → 当场拒绝")
    P.cmd("/execute as %s run numen permission mode %s observe" % (P.PLAYER, P.COMPANION))
    p = P.pos_of(P.COMPANION) or apos
    for dy in (1, 2, 3):
        r = P.call("mine", {"companion": P.COMPANION, "x": p[0], "y": p[1] - dy, "z": p[2]})
        print("   mine y-%d → %s" % (dy, r))
        if "空气" not in r:
            break
    P.cmd("/execute as %s run numen permission mode %s ask" % (P.PLAYER, P.COMPANION))

    step(8, "关服")
    P.cmd("/stop")
    t0 = time.time()
    try:
        P.server.wait(timeout=60)
        print(">> 服务端 %.1fs 后退出 code=%s" % (time.time() - t0, P.server.returncode))
    except subprocess.TimeoutExpired:
        print(">> !! 60s 未退出")

    print("\n---- 关服段 ----")
    lines = P.read(P.SERVER_LOG).splitlines()
    idx = next((i for i, l in enumerate(lines) if "Stopping the server" in l), max(0, len(lines) - 8))
    for l in lines[idx:]:
        print(l)

    print("\n---- 权限 / 征询 / 规则表相关日志 ----")
    for l in lines:
        if "[numen-consent]" in l or "[numen]" in l or "allow" in l.lower() or "deny" in l.lower():
            print("  " + l)

    P.kill_all()
    return 0


if __name__ == "__main__":
    sys.exit(main())
