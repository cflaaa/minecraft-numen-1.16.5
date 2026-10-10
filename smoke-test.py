#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""numen 1.16.5 冒烟测试：起专用服务端 → 客户端直连 → 执行 /numen summon → 看结果。

为什么用服务端而不是单机：
  1. 服务端可以从 stdin 直接喂命令，不需要任何 GUI 操作；
  2. `/numen summon` 内部是 `source.getPlayerOrException()`，必须有玩家，
     所以控制台不能直接执行，要用 `/execute as <玩家> run ...` 借一个玩家身份；
  3. 客户端 1.16.5 的 Main 支持 `--server` / `--port`（实测常量池里有这两个选项名），
     所以客户端能自动直连，全程零 GUI 交互。

用法：python smoke-test.py
"""
import json
import os
import re
import subprocess
import sys
import time

MC = r"C:\Users\cfl\Desktop\.minecraft"
VER = "1.16.5-Fabric 0.19.5-2"
VDIR = os.path.join(MC, "versions", VER)
VJSON = os.path.join(VDIR, VER + ".json")
JAVA = (r"C:\Users\cfl\AppData\Roaming\.minecraft\runtime"
        r"\java-runtime-gamma-snapshot\bin\java.exe")
NATIVES = os.path.join(VDIR, VER + "-natives")
CLIENT_LOG = os.path.join(VDIR, "logs", "latest.log")

TS = r"C:\Users\cfl\workbuddy-ai\我的世界\.testserver"
SERVER_JAR = os.path.join(TS, "server.jar")
SERVER_LOG = os.path.join(TS, "logs", "latest.log")
SERVER_OUT = os.path.join(TS, "server-stdout.log")
CLIENT_OUT = os.path.join(TS, "client-stdout.log")

PLAYER = "T"
COMPANION = "Alice"
PORT = "25565"


def rules_match(rules):
    if not rules:
        return True
    allowed = False
    for r in rules:
        os_ = r.get("os", {}) or {}
        ok = True
        if "name" in os_ and os_["name"] != "windows":
            ok = False
        if "arch" in os_ and os_["arch"] != "x86_64":
            ok = False
        if "version" in os_ and not re.match(os_["version"], "10.0"):
            ok = False
        if "features" in r:
            ok = False
        if ok:
            allowed = r.get("action") == "allow"
    return allowed


def maven_path(name):
    group, artifact, version = name.split(":")
    return "/".join(group.split(".")) + "/%s/%s/%s-%s.jar" % (
        artifact, version, artifact, version)


def libraries():
    d = json.load(open(VJSON, encoding="utf-8"))
    out = []
    for lib in d["libraries"]:
        if not rules_match(lib.get("rules")):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        if art:
            out.append(os.path.join(MC, "libraries", art["path"]))
        else:
            out.append(os.path.join(MC, "libraries", maven_path(lib["name"])))
    return d, out


def flatten(values):
    out = []
    for v in values:
        if isinstance(v, list):
            out.extend(flatten(v))
        elif isinstance(v, str):
            out.append(v)
        elif isinstance(v, dict):
            if rules_match(v.get("rules")):
                out.extend(flatten([v.get("value")]))
    return out


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def wait_for(path, needles, timeout, proc=None, label=""):
    """等日志里出现 needles 之一；进程提前退出就报出来。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.0)
        text = read(path)
        for n in needles:
            if n in text:
                return n
        if proc is not None and proc.poll() is not None:
            print("  !! %s 进程提前退出 code=%s" % (label, proc.returncode))
            return None
    return None


def tail(path, n, title):
    print("\n---- %s 末 %d 行 ----" % (title, n))
    for l in read(path).splitlines()[-n:]:
        print(l)


def main():
    # ---- 准备：清掉上一轮的日志，避免读到残留 ----
    for p in (SERVER_LOG, CLIENT_LOG):
        if os.path.exists(p):
            os.replace(p, p + ".prev")
    if os.path.exists(os.path.join(TS, "logs")):
        pass

    d, libs = libraries()
    missing = [p for p in libs if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件：" % len(missing))
        for m in missing[:5]:
            print("   ", m)
        return 1

    # ================= 1. 起服务端 =================
    print("=" * 70)
    print("[1/4] 启动专用服务端 …")
    print("=" * 70)
    server_cp = libs + [SERVER_JAR]
    server_cmd = [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8",
                  "-cp", ";".join(server_cp),
                  "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
    sf = open(SERVER_OUT, "wb")
    server = subprocess.Popen(server_cmd, cwd=TS, stdin=subprocess.PIPE,
                              stdout=sf, stderr=subprocess.STDOUT)

    got = wait_for(SERVER_LOG, ["Done ("], 300, server, "服务端")
    if got is None:
        print("服务端没起来。")
        tail(SERVER_OUT, 60, "server-stdout.log")
        tail(SERVER_LOG, 40, "server latest.log")
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(server.pid)],
                       capture_output=True)
        return 1
    print("  ✅ 服务端就绪（日志出现 'Done ('）")

    # ================= 2. 客户端直连 =================
    print("=" * 70)
    print("[2/4] 启动客户端并直连 127.0.0.1:%s …" % PORT)
    print("=" * 70)
    subs = {
        "natives_directory": NATIVES, "launcher_name": "PCL",
        "launcher_version": "2.9.0",
        "classpath": ";".join(libs + [os.path.join(VDIR, VER + ".jar")]),
        "auth_player_name": PLAYER, "version_name": VER,
        "game_directory": VDIR, "assets_root": os.path.join(MC, "assets"),
        "assets_index_name": d["assetIndex"]["id"],
        "auth_uuid": "00000000000000000000000000000000",
        "auth_access_token": "0", "user_type": "legacy",
        "version_type": "release",
    }

    def sub(s):
        for k, v in subs.items():
            s = s.replace("${" + k + "}", v)
        return s

    jvm = [sub(a) for a in flatten(d["arguments"]["jvm"])]
    game = [sub(a) for a in flatten(d["arguments"]["game"])]
    game += ["--server", "127.0.0.1", "--port", PORT]
    cf = open(CLIENT_OUT, "wb")
    client = subprocess.Popen([JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"]
                              + jvm + [d["mainClass"]] + game,
                              cwd=VDIR, stdout=cf, stderr=subprocess.STDOUT)

    joined = wait_for(SERVER_LOG, ["joined the game"], 300, server, "服务端")
    if joined is None:
        print("客户端没能连进来。")
        tail(SERVER_OUT, 30, "server-stdout.log")
        tail(CLIENT_LOG, 40, "客户端 latest.log")
        for p in (server, client):
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                           capture_output=True)
        return 1
    print("  ✅ 客户端已进服（日志出现 'joined the game'）")
    time.sleep(5)

    # ================= 3. 借玩家身份执行 summon =================
    print("=" * 70)
    print("[3/4] 执行 /execute as %s run numen summon %s" % (PLAYER, COMPANION))
    print("=" * 70)
    for cmd in ("/numen list",
                "/execute as %s run numen summon %s" % (PLAYER, COMPANION),
                "/numen list",
                "/numen status %s" % COMPANION):
        print("  >>> " + cmd)
        server.stdin.write((cmd + "\n").encode("utf-8"))
        server.stdin.flush()
        time.sleep(4)

    time.sleep(10)

    # ================= 4. 收结果 =================
    print("=" * 70)
    print("[4/4] 结果")
    print("=" * 70)
    sl = read(SERVER_LOG)
    print("\n---- 服务端日志里所有含 numen / Alice / 异常 的行 ----")
    for l in sl.splitlines():
        if re.search(r"numen|Alice|Exception|Error|Caused by|at com\.dwinovo", l, re.I):
            print(l[:250])

    tail(SERVER_LOG, 30, "server latest.log")

    for p, name in ((client, "客户端"), (server, "服务端")):
        if p.poll() is None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                           capture_output=True)
    time.sleep(1)
    print("\n已关闭客户端与服务端")
    return 0


if __name__ == "__main__":
    sys.exit(main())
