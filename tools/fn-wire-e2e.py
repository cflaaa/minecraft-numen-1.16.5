#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 1b 真机验收：接线之后的四件事。

  1) 起服日志：模组已加载 + 排程器心跳上线（证明 START 刻钩子接上了）
  2) /numen program：让 Alice 跑一段 Lua 调 @Fn 函数（numen.api.help / numen.task.status），
     回执经主线程车道交回玩家聊天（截图证据：客户端日志里的帮助正文）
  3) 权限闸回归（本刀把 ConsentDesk 从实体 tick 挪到排程器）：drop 一件东西
     → 出厂 ask 行应弹征询 → 以主人身份点「允许」→ 东西掉出来
  4) SPAWN/REMOVE 事件不炸（召唤Alice经 CompanionFactory.fire(SPAWN)）

用法：python fn-wire-e2e.py
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


def wait_client(pattern, timeout):
    """等玩家聊天里出现一行（命令回执只进执行者的聊天，服务端日志看不到）。"""
    deadline = time.time() + timeout
    rx = re.compile(pattern)
    while time.time() < deadline:
        time.sleep(1.0)
        for line in P.read(P.CLIENT_LOG).splitlines():
            if rx.search(line):
                return line
    return None


def main():
    shutil.copyfile(BUILT, DEPLOYED)
    print(">> 新 jar 已部署：%s" % DEPLOYED, flush=True)

    d, libs = P.libraries()
    P.start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来")
        return 1
    print(">> 服务端就绪", flush=True)

    # ---- 1) 起服日志：模组加载 + 排程器心跳 ----
    step(1, "起服日志：排程器心跳上线（START 刻钩子接上的证据）")
    log = P.read(P.SERVER_LOG)
    for pat, what in [(r"Numen 1\.16\.5 移植版已加载", "模组入口"),
                      (r"scheduler heartbeat online", "排程器心跳"),
                      (r"Loaded 5 for service", None)]:
        if what:
            print("   %s：%s" % (what, "有" if re.search(pat, log) else "!! 没有"))

    P.start_client(d, libs)
    if P.wait_new(r"%s joined the game" % P.PLAYER, 0, 300, P.server) is None:
        print("!! 客户端没连进来")
        return 1
    print(">> 客户端进服（主人 %s 在场）" % P.PLAYER, flush=True)
    time.sleep(3)   # JOIN 钩子：scheduleRestoreFor + syncRosterToOwner 跑完

    # ---- 2) 召唤 Alice（顺带验 SPAWN 事件不炸）----
    step(2, "召唤 Alice（SPAWN 事件 → 排程器接手，不炸）")
    mark = len(P.read(P.SERVER_LOG))
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    if P.wait_new(r"同伴 %s 已召唤" % P.COMPANION, 0, 20, P.server) is None:
        print("   （可能上一轮已在——接着用现有的）")
    time.sleep(2)

    # ---- 3) @Fn：Lua 调到 numen.api.help ----
    step(3, "@Fn 路：/numen program 让 Alice 跑 Lua 调 numen.api.help")
    P.cmd('/execute as %s run numen program %s print(numen.api.help("numen.task.status"))'
          % (P.PLAYER, P.COMPANION))
    line = P.wait_new(r"What you have in flight", 0, 30, P.server)
    print("   客户端聊天里的帮助正文：%s" % ("有" if line else "!! 没有"))
    if line:
        print("   " + line.strip()[:120])

    # ---- 4) @Fn：numen.task.status 的 JSON ----
    step(4, "@Fn 路：numen.task.status() 回执 JSON")
    P.cmd('/execute as %s run numen program %s print(numen.task.status())'
          % (P.PLAYER, P.COMPANION))
    line = P.wait_new(r'timers = \{\}|timers=', 0, 30, P.server)
    print("   客户端聊天里的状态 JSON：%s" % ("有" if line else "!! 没有"))
    if line:
        print("   " + line.strip()[:120])

    # ---- 5) 权限闸回归：drop → ask → allow ----
    step(5, "权限闸回归（ConsentDesk 现由排程器驱动）：drop → 应弹征询 → 允许 → 掉出")
    P.cmd("/execute as %s run numen permission mode %s ask" % (P.PLAYER, P.COMPANION))
    P.cmd("/execute as %s run numen permission rules reset" % P.PLAYER)
    time.sleep(0.5)
    P.cmd("/give %s minecraft:gold_block 8" % P.COMPANION)
    time.sleep(1)
    mark = len(P.read(P.SERVER_LOG))
    th, box = P.call_async("drop", {"companion": P.COMPANION, "item": "gold_block", "count": 1})
    rid2 = P.next_consent(mark, 25)
    print("   → 征询号 #%s" % rid2)
    if rid2 is None:
        print("   !! 没弹征询；MCP 回：%s" % P.wait_mcp(th, box, 10))
    else:
        P.answer(rid2, "allow")
        print("   → MCP 回：%s" % P.wait_mcp(th, box, 60))
    time.sleep(1)
    print("   服务端日志里的同意记录：%s"
          % ("有" if re.search(r"answered", P.read(P.SERVER_LOG)[mark:]) else "（换句话看下面）"))

    P.kill_all()
    print("\n>> 完事", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
