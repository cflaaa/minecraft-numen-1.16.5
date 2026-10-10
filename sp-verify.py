#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单人模式验证的收尾判定：读客户端日志 + 假端点请求日志，判定「模型 → 工具 → 回灌」整条链跑通没有。

与 smoke-llm.py 的区别：
  smoke-llm.py 自己起专用服务端 + 客户端，全自动，但客户端「自动连服」在本机上有偶发竞态；
  sp-verify.py 不起任何进程，只在**人已经在单人模式里玩过一遍之后**做判定，
  所以它不会因为 GUI 那点抖动而误报。

前提（在游戏里做完这两步）：
    单人游戏 → 进入「新的世界」（创造模式、已开作弊）
    /numen summon Alice
    /numen tell Alice 看看你脚下是什么方块

用法：python sp-verify.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION_DIR = r"C:\Users\cfl\Desktop\.minecraft\versions\1.16.5-Fabric 0.19.5-2"
CLIENT_LOG = os.path.join(VERSION_DIR, "logs", "latest.log")
CFG = os.path.join(VERSION_DIR, "config", "numen.json")
REQLOG = os.path.join(HERE, ".testserver", "fake-llm-requests.jsonl")

TOOLS = ["get_status", "inspect_block", "scan_blocks", "mine", "goto", "say"]

# 客户端日志里中文是 GBK 写进去的（PCL 启动时 file.encoding 是 GBK），
# 读成 UTF-8 会变问号——所以断言一律只认 ASCII 片段（工具名、success、异常类名）。
STARTED = re.compile(r"ToolStarted\[.*?name=(\w+)")
FINISHED = re.compile(r"ToolFinished\[.*?name=(\w+).*?resultJson=(\{.*?\})(?:\]|$)")

FATAL = ["java.lang.", "NoSuchMethodError", "ClassCastException",
         "NoClassDefFoundError", "NoSuchFieldError", "IncompatibleClassChangeError"]


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def main():
    log = read(CLIENT_LOG)
    cfg = read(CFG)
    reqs = []
    for line in read(REQLOG).splitlines():
        try:
            reqs.append(json.loads(line))
        except ValueError:
            pass

    print("=" * 72)
    print("客户端日志：%s（%d 字节）" % (CLIENT_LOG, len(log)))
    print("假端点请求：%d 轮" % len(reqs))
    print("=" * 72)

    # ---- 打印看得见的那部分：事件行 ----
    print("\n---- 日志里的「同伴事件」行 ----")
    n_ev = 0
    for l in log.splitlines():
        if "ToolStarted" in l or "ToolFinished" in l or "事件" in l or "TurnFailed" in l:
            n_ev += 1
            print("  " + l[:300])
    if n_ev == 0:
        print("  （一行都没有——说明要么没进世界，要么没召唤/没说那句话）")

    # ---- 打印假端点收到的剧本 ----
    print("\n---- 假端点收到的请求（第几轮 → 回了什么工具）----")
    for i, rec in enumerate(reqs, 1):
        b = rec.get("body", {}) or {}
        print("  #%d 回 %-14s 消息 %-3s 工具 %s 个  %s"
              % (i, rec.get("reply"), b.get("n_messages"), b.get("n_tools"), b.get("roles")))

    # ---- 汇总每个工具的 started / finished / success ----
    started = STARTED.findall(log)
    fin = FINISHED.findall(log)
    fin_ok, fin_bad = {}, {}
    for name, blob in fin:
        try:
            ok = json.loads(blob).get("success") is True
        except ValueError:
            ok = False
        (fin_ok if ok else fin_bad)[name] = (fin_ok if ok else fin_bad).get(name, 0) + 1

    print("\n---- 逐工具汇总 ----")
    print("  %-14s %-9s %-9s %s" % ("工具", "派出", "成功", "失败"))
    for t in TOOLS:
        print("  %-14s %-9d %-9d %d"
              % (t, started.count(t), fin_ok.get(t, 0), fin_bad.get(t, 0)))

    # ---- 断言 ----
    print("\n---- 断言 ----")
    checks = [
        ("模组已加载（numen 0.1.4-1.16.5）", "numen 0.1.4-1.16.5" in log),
        ("配置从 config\\numen.json 读取", "numen.json" in log),
        ("配置指向本地假端点", "127.0.0.1:18765" in cfg),
        ("假端点收到 >=5 轮请求", len(reqs) >= 5),
        ("六个工具都被派出过", all(started.count(t) >= 1 for t in TOOLS)),
        ("六个工具都成功结算", all(fin_ok.get(t, 0) >= 1 for t in TOOLS)),
        ("没有失败结算", not fin_bad),
        ("收尾发言 say 成功", fin_ok.get("say", 0) >= 1),
        ("无 java.lang 等致命异常", not any(f in log for f in FATAL)),
        ("无 TurnFailed", "TurnFailed" not in log),
    ]
    ok = True
    for name, passed in checks:
        print("  [%s] %s" % ("PASS" if passed else "FAIL", name))
        ok = ok and passed

    if not ok:
        print("\n---- 排查提示 ----")
        if not reqs:
            print("  · 假端点一轮都没收到 → 端点没起？游戏里没 /numen tell？配置没指向 18765？")
        if started and not fin_ok and not fin_bad:
            print("  · 有派出、没结算 → goto 是跨刻动作，等一下；或 Walker 卡住了")
        if any(f in log for f in FATAL):
            print("  · 有致命异常 → 抓上面的堆栈，多半又是「客户端独有成员」那一类")

    print("\n总结论：" + ("全部通过 ✅" if ok else "有失败项 ❌"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
