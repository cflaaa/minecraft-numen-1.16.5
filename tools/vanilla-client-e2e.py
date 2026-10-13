#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""原版（无模组）客户端能不能连上带 Numen 的服务端？

要验的就是 README 里那句话：「玩家不需要装客户端 mod」。
做法：用**同一份客户端 jar**（Fabric 不 patch jar，它就是原版 jar），
只把 classpath 上那 3 个 fabric 库摘掉、主类换回 `net.minecraft.client.main.Main`，
再 `--server/--port` 自动进服 —— 这就是一个纯原版客户端。

两侧证据都收：
  服务端：`Vanilla joined the game` / 召唤成功 / 没有任何自定义通道报错
  客户端：`Connecting to …` / 没被踢 / 没异常 / 进程活着

⚠️ 客户端用**独立 gameDir**（.testserver/vanilla-client），assets 与 natives 共用（只读），
   绝不碰用户开着的那个 .minecraft（PCL 占着它的 latest.log）。

用法：python vanilla-client-e2e.py
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402

VJSON = P.VJSON
JAVA = P.JAVA
NATIVES = P.NATIVES
ASSETS = os.path.join(P.MC, "assets")
VANILLA_DIR = os.path.join(P.TS, "vanilla-client")
CLIENT_LOG = os.path.join(VANILLA_DIR, "logs", "latest.log")
CLIENT_OUT = os.path.join(P.TS, "vanilla-client-stdout.log")
PLAYER = "Vanilla"

client = None


def rules_match(rules):
    return P.rules_match(rules)


def flatten(values):
    return P.flatten(values)


def maven_path(name):
    return P.maven_path(name)


def build_cmd():
    d = json.load(open(VJSON, encoding="utf-8"))
    cp, skipped = [], []
    for lib in d["libraries"]:
        name = lib["name"]
        if name.startswith("net.fabricmc:"):        # ← 摘掉 fabric-loader / mixin / intermediary
            skipped.append(name)
            continue
        if not rules_match(lib.get("rules")):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        cp.append(os.path.join(P.MC, "libraries", art["path"]) if art
                  else os.path.join(P.MC, "libraries", maven_path(name)))
    cp.append(os.path.join(P.VDIR, P.VER + ".jar"))
    print("   摘掉的 fabric 库：%s" % skipped)

    subs = {
        "natives_directory": NATIVES, "launcher_name": "PCL", "launcher_version": "2.9.0",
        "classpath": ";".join(cp),
        "auth_player_name": PLAYER, "version_name": P.VER, "game_directory": VANILLA_DIR,
        "assets_root": ASSETS, "assets_index_name": d["assetIndex"]["id"],
        "auth_uuid": "00000000000000000000000000000000", "auth_access_token": "0",
        "user_type": "legacy", "version_type": "release",
    }

    def sub(s):
        for k, v in subs.items():
            s = s.replace("${" + k + "}", v)
        return s

    jvm = [sub(a) for a in flatten(d["arguments"]["jvm"])]
    game = [sub(a) for a in flatten(d["arguments"]["game"])]
    game += ["--server", "127.0.0.1", "--port", P.PORT]
    # ⭐ 同 vanilla-nas-e2e：1.16.5 自动进服被 serversAllowed() 门控，假 token 下必须逼离线。
    #   死代理 → 认证请求立即失败 → OfflineSocialInteractions(serversAllowed=true) → 稳定进服。
    game += ["--proxyHost", "127.0.0.1", "--proxyPort", "1"]
    # 主类换回原版
    return [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"] + jvm + \
           ["net.minecraft.client.main.Main"] + game


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def start_server(libs):
    """自己起服务端，**不用** P.start_server——它会顺手把 Fabric 客户端的 latest.log 挪走，
    而用户可能正开着那个游戏（文件被占，且不该动）。这一轮只用原版客户端，碰它没意义。"""
    if os.path.exists(P.SERVER_LOG):
        try:
            os.replace(P.SERVER_LOG, P.SERVER_LOG + ".e2eprev")
        except OSError as ex:
            print("   （服务端日志没能挪走，直接覆盖：%r）" % ex)
    P.server = subprocess.Popen(
        [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8", "-cp",
         ";".join(libs + [P.SERVER_JAR]),
         "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
        cwd=P.TS, stdin=subprocess.PIPE,
        stdout=open(P.SERVER_OUT, "wb"), stderr=subprocess.STDOUT)
    return P.server


def main():
    os.makedirs(VANILLA_DIR, exist_ok=True)

    print("=" * 68, flush=True)
    print("第 1 步：起专用服务端（带 Numen）", flush=True)
    print("=" * 68, flush=True)
    d, libs = P.libraries()
    start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来")
        return 1
    print(">> 服务端就绪")

    print("\n" + "=" * 68, flush=True)
    print("第 2 步：起**原版**客户端并自动进服（gameDir=%s）" % VANILLA_DIR, flush=True)
    print("=" * 68, flush=True)
    global client
    cmd = build_cmd()
    print("   mainClass = net.minecraft.client.main.Main（不是 KnotClient）")
    client = subprocess.Popen(cmd, cwd=VANILLA_DIR, stdin=subprocess.DEVNULL,
                              stdout=open(CLIENT_OUT, "wb"), stderr=subprocess.STDOUT)
    print("   客户端 pid=%d" % client.pid)

    joined = P.wait_new(r"%s joined the game" % PLAYER, 0, 300, P.server) is not None
    print("   → 服务端看到「%s joined the game」：%s" % (PLAYER, "✅" if joined else "❌"))
    if not joined:
        print("   !! 没连进来。客户端 stdout 末尾：")
        for l in read(CLIENT_OUT).splitlines()[-25:]:
            print("      " + l)
        P.kill_all()
        try:
            client.kill()
        except Exception:
            pass
        return 1

    print("\n" + "=" * 68, flush=True)
    print("第 3 步：在服务端干活，看原版客户端会不会被这些弄崩", flush=True)
    print("=" * 68, flush=True)
    P.cmd("/execute as %s run numen summon %s" % (PLAYER, P.COMPANION))
    ok_summon = P.wait_new(r"同伴 %s 已召唤" % P.COMPANION, 0, 25, P.server) is not None
    print("   召唤同伴：%s" % ("✅" if ok_summon else "❌"))

    # 用外接大脑（MCP）让她说一句 → 服务端往客户端发聊天
    try:
        print("   say → %s" % P.call("say", {"companion": P.COMPANION,
                                             "text": "我是服务端的同伴，原版客户端应该看得见我"}))
    except Exception as ex:      # noqa: BLE001
        print("   say 失败（不影响结论）：%r" % ex)

    # 让她走两步 → 服务端往客户端发实体位移
    try:
        p = P.pos_of(PLAYER)
        if p:
            print("   goto 走到主人附近 → %s"
                  % P.call("goto", {"companion": P.COMPANION,
                                    "x": p[0] + 2, "y": p[1], "z": p[2]}, timeout=180))
    except Exception as ex:      # noqa: BLE001
        print("   goto 失败（不影响结论）：%r" % ex)

    time.sleep(3)

    print("\n" + "=" * 68, flush=True)
    print("第 4 步：两侧日志取证", flush=True)
    print("=" * 68, flush=True)
    slog = P.read(P.SERVER_LOG)
    clog = read(CLIENT_LOG)
    cout = read(CLIENT_OUT)

    print("   【客户端】Connecting to …：%s"
          % ("✅" if "Connecting to 127.0.0.1" in clog or "Connecting to 127.0.0.1" in cout else "❌"))

    # 这两类是本机的**基线噪声**，与本模组无关（Fabric 客户端的日志里同样有，已对照过）：
    #   1) 假令牌 + 连不上 Mojang 认证 → Failed to verify authentication（就是 PORTING.md §8 那条）
    #   2) 本机 assets 缺 options_background.png（PCL 的 assets 不全）
    BENIGN = re.compile(r"options_background|AuthenticationUnavailable|Failed to verify authentication"
                        r"|Cannot contact authentication server|Connect timed out|Connection refused")
    lines = (clog + "\n" + cout).splitlines()
    bad = [l for l in lines if re.search(r"Disconnect|disconnect|Exception|Error|FATAL|crash", l)
           and not BENIGN.search(l)]
    benign = [l for l in lines if BENIGN.search(l)]
    print("   【客户端】真异常/断线行（已排除基线噪声）：%s"
          % ("✅ 无" if not bad else "❌ %d 行" % len(bad)))
    for l in bad[:8]:
        print("      " + l)
    print("   【客户端】基线噪声（与本模组无关，Fabric 客户端同样有）：%d 行" % len(benign))
    alive = client.poll() is None
    print("   【客户端】进程还活着：%s（pid=%d, rc=%s）"
          % ("✅" if alive else "❌", client.pid, client.poll()))

    # ⭐ 决定性证据：原版客户端把同伴的聊天打进了自己的日志
    chat = [l for l in lines if "[CHAT]" in l]
    print("   【客户端】收到的 [CHAT] 行：%d 条 %s" % (len(chat), "✅" if chat else "❌"))
    for l in chat[:10]:
        print("      " + l)

    warn = [l for l in slog.splitlines()
            if re.search(r"channel|payload|Payload|does not have|not registered", l)
            and "Using default channel type" not in l]
    print("   【服务端】自定义通道/载荷告警：%s" % ("✅ 无" if not warn else "❌ %d 行" % len(warn)))
    for l in warn[:8]:
        print("      " + l)
    print("   【服务端】同伴相关：")
    for l in slog.splitlines():
        if "[numen]" in l and ("召唤" in l or "同伴" in l):
            print("      " + l)

    print("\n   客户端 gameDir 里生成了什么：")
    for root, dirs, files in os.walk(VANILLA_DIR):
        for f in files:
            fp = os.path.join(root, f)
            print("      %s (%d B)" % (os.path.relpath(fp, VANILLA_DIR), os.path.getsize(fp)))
        if root.count(os.sep) - VANILLA_DIR.count(os.sep) >= 1:
            dirs[:] = []

    print("\n" + "=" * 68, flush=True)
    print("第 5 步：收尾", flush=True)
    print("=" * 68, flush=True)
    P.cmd("/stop")
    t0 = time.time()
    try:
        P.server.wait(timeout=60)
        print(">> 服务端 %.1fs 后退出 code=%s" % (time.time() - t0, P.server.returncode))
    except subprocess.TimeoutExpired:
        print(">> !! 服务端 60s 未退出")
    try:
        client.kill()
    except Exception:      # noqa: BLE001
        pass
    P.kill_all()

    ok = joined and alive and not bad and bool(chat)
    print("\n小结：%s" % ("✅ 原版客户端连得上、没被踢，还收到了同伴的聊天 → 不需要客户端 mod"
                        if ok else "❌ 见上"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
