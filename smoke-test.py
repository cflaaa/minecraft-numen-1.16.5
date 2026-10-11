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
    server_cmd = [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"]
    # NUMEN_LOG4J=<path> 时用自定义 log4j2 配置（临时诊断用：把
    # net.minecraft.network.Connection 提到 debug，好让关服时的
    # StackOverflowError 栈被 LOGGER.debug("Failed to sent packet", cause) 打出来）。
    if os.environ.get("NUMEN_LOG4J"):
        server_cmd.append("-Dlog4j.configurationFile=" + os.environ["NUMEN_LOG4J"])
        server_cmd.append("-Dlog4j2.configurationFile=" + os.environ["NUMEN_LOG4J"])
    server_cmd += ["-cp", ";".join(server_cp),
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
    # 权限层全量命令（归位阶段 1）。
    # ⚠️ mode 的语法是 `mode <同伴名> [ask|bypass|observe]`——模式字面量挂在
    # **name 参数节点下面**（与上游 NumenCommands 完全一致）。所以：
    #   `mode Alice`          = 查
    #   `mode Alice observe`  = 设
    #   `mode observe`        ✗ 会被当成「名叫 observe 的同伴」，回 yours_not_here
    #   `mode 张三`           ✗ word() 参数不收中文，Brigadier 解析失败
    P = "/execute as %s run " % PLAYER
    for cmd in ("/numen list",
                P + "numen summon %s" % COMPANION,
                "/numen list",
                "/numen status %s" % COMPANION,
                # —— 出厂规则全貌 ——
                P + "numen permission rules list",
                # —— 模式：查 → 设 observe → 查 → 设 bypass → 查 → 设回 ask → 查 ——
                P + "numen permission mode %s" % COMPANION,
                P + "numen permission mode %s observe" % COMPANION,
                P + "numen permission mode %s" % COMPANION,
                P + "numen permission mode %s bypass" % COMPANION,
                P + "numen permission mode %s" % COMPANION,
                P + "numen permission mode %s ask" % COMPANION,
                P + "numen permission mode %s" % COMPANION,
                # —— 规则：加 deny → 加 ask → 列 → 删 deny 1 → 列 ——
                P + "numen permission rules add deny break(placed & !contents)",
                P + "numen permission rules add ask drop(*)",
                P + "numen permission rules list",
                P + "numen permission rules remove deny 1",
                P + "numen permission rules list",
                # —— 错误路径：垃圾规则、越界行号、不存在的同伴 ——
                P + "numen permission rules add deny 这不是一条规则",
                P + "numen permission rules remove ask 9",
                P + "numen permission mode Bob",
                # —— 收尾：清空三张表 → 列 ——
                P + "numen permission rules reset",
                P + "numen permission rules list"):
        print("  >>> " + cmd)
        server.stdin.write((cmd + "\n").encode("utf-8"))
        server.stdin.flush()
        time.sleep(2.5)

    # ================= 3b. 落盘测试（NUMEN_PERSIST=1）=================
    # 目的：验证 PermissionStore / PlacedBlocks 真的写进 SavedData。
    # 前几轮因为 taskkill /F 强杀，world/data 下没有 numen_*.dat——
    # 这里设一个非默认值（observe + 一条 allow 规则）、save-all、优雅 stop，
    # 然后直接把 .dat 打出来看内容。
    persist = bool(os.environ.get("NUMEN_PERSIST"))
    if persist:
        print("=" * 70)
        print("[3b/4] 落盘测试：设 observe + 加规则 → save-all → 优雅 stop")
        print("=" * 70)
        for cmd in (P + "numen permission mode %s observe" % COMPANION,
                    P + "numen permission rules add allow take(*)",
                    P + "numen permission rules list"):
            print("  >>> " + cmd)
            server.stdin.write((cmd + "\n").encode("utf-8"))
            server.stdin.flush()
            time.sleep(2.5)

    time.sleep(5)

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

    # 优雅收服务端：save-all 落盘 → stop 让 SavedData 写入后再退出。
    if server.poll() is None:
        print("\n>>> /save-all")
        server.stdin.write(b"/save-all\n")
        server.stdin.flush()
        time.sleep(4)
        print(">>> /stop")
        server.stdin.write(b"/stop\n")
        server.stdin.flush()
        try:
            server.wait(timeout=90)
            print("  ✅ 服务端已优雅退出")
        except subprocess.TimeoutExpired:
            print("  !! 服务端 90s 未退出，强杀")
    for p, name in ((client, "客户端"), (server, "服务端")):
        if p.poll() is None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                           capture_output=True)
    time.sleep(1)

    if persist:
        data_dir = os.path.join(TS, "world", "data")
        print("\n---- world/data 下的 numen 存档 ----")
        found = [f for f in os.listdir(data_dir) if f.startswith("numen")]
        if not found:
            print("  !! 没有 numen_*.dat —— SavedData 没落盘")
        for f in found:
            raw = open(os.path.join(data_dir, f), "rb").read().decode(
                "utf-8", "replace")
            print("  %s (%d 字节):" % (f, len(raw)))
            print("   ", raw[:400])

    print("\n已关闭客户端与服务端")
    return 0


if __name__ == "__main__":
    sys.exit(main())
