#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 3 真机验收：工具派道统一到 ToolRegistry + lua 换 ScriptTool。

  1) 起服日志：登记了几个工具（4 个大脑工具 + 世界工具）
  2) MCP tools/list：应有 lua / skill / todo / memory + 世界工具（+ 5 个管理工具）
  3) lua 工具（新路：ScriptTool → ServerPrograms → ApiRegistry 目录）：
     a. 调 numen.task.status()（@Fn 真执行）
     b. numen.module.save 存一个模块再调它（落磁盘 + Modules.of 读回）
     c. numen.api.help 拿帮助（ApiDocs 从登记表生成）
  4) 权限闸回归：drop（世界工具经 WorldTool → 旧 Job 机制）→ 征询 → 允许 → 掉出

用法：python tool-reg-e2e.py
"""
import json
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


def lua(code):
    return P.call("lua", {"companion": P.COMPANION, "code": code})


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

    step(1, "起服日志：工具登记")
    line = P.wait_new(r"登记 \d+ 个工具", 0, 20, P.server)
    print("   %s" % (line or "!! 没看到登记行"))

    step(2, "召唤 Alice")
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    time.sleep(3)

    step(3, "MCP tools/list：lua/skill/todo/memory 与世界工具都应在")
    tools = P.rpc("tools/list").get("result", {}).get("tools", [])
    names = [t["name"] for t in tools]
    print("   共 %d 个：%s" % (len(names), ", ".join(names)))
    for want in ["lua", "skill", "todo", "memory", "mine", "drop", "equip"]:
        print("   %-8s %s" % (want, "在" if want in names else "!! 缺"))

    step(4, "lua 新路 a：调 numen.task.status()（@Fn 执行）")
    r = lua('print(numen.task.status())')
    print("   → %s" % r.replace("\n", " | ")[:200])
    print("   判定：%s" % ("过" if "timers" in r else "!! 不符"))

    step(5, "lua 新路 b：存一个模块再调它（落磁盘 + Modules.of 读回）")
    # 存与用分两段：模块目录在程序启动时快照，同一段里看不见刚存的（上游同一行为）
    lua('numen.module.save("-- probe: answers pong\\nreturn {ping = function() return \'pong\' end}", {name = "my.probe"})')
    r = lua('print(numen.my.probe.ping())')
    print("   → %s" % r.replace("\n", " | ")[:200])
    print("   判定：%s" % ("过" if "pong" in r else "!! 不符"))

    step(6, "lua 新路 c：numen.api.help 拿帮助（ApiDocs 从登记表生成）")
    r = lua('print(numen.api.help("numen.task.status"))')
    print("   判定：%s" % ("过" if "What you have in flight" in r else "!! 不符"))

    step(7, "权限闸回归：drop（世界工具经 WorldTool → 旧 Job）→ 征询 → 允许")
    P.cmd("/execute as %s run numen permission mode %s ask" % (P.PLAYER, P.COMPANION))
    P.cmd("/execute as %s run numen permission rules reset" % P.PLAYER)
    time.sleep(0.5)
    P.cmd("/give %s minecraft:gold_block 8" % P.COMPANION)
    time.sleep(1)
    mark = len(P.read(P.SERVER_LOG))
    th, box = P.call_async("drop", {"companion": P.COMPANION, "item": "gold_block", "count": 1})
    rid = P.next_consent(mark, 25)
    print("   → 征询号 #%s" % rid)
    if rid is None:
        print("   !! 没等到征询；MCP 回：%s" % P.wait_mcp(th, box, 10))
    else:
        P.answer(rid, "allow")
        print("   → MCP 回：%s" % P.wait_mcp(th, box, 60))

    P.kill_all()
    print("\n>> 完事", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
