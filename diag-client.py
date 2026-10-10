#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断：客户端卡在「贴图图集之后、连服务器之前」时，主线程到底停在哪。

只起客户端（不连服务器），跑够时间后 jstack 一下，把 main 线程的栈打出来。
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MC = r"C:\Users\cfl\Desktop\.minecraft"
VER = "1.16.5-Fabric 0.19.5-2"
VDIR = os.path.join(MC, "versions", VER)
VJSON = os.path.join(VDIR, VER + ".json")
JDK = os.path.join(HERE, ".toolchain", "jdk-17.0.20.1+1")
JAVA = os.path.join(JDK, "bin", "java.exe")
JSTACK = os.path.join(JDK, "bin", "jstack.exe")
NATIVES = os.path.join(VDIR, VER + "-natives")
CLIENT_LOG = os.path.join(VDIR, "logs", "latest.log")
OUT = os.path.join(HERE, ".testserver", "diag-client.out")
WAIT = int(sys.argv[1]) if len(sys.argv) > 1 else 75
# 第二个参数：换一个 java（留空 = 用 toolchain 的 JDK）
if len(sys.argv) > 2 and sys.argv[2].strip():
    JAVA = sys.argv[2]
# 第三个参数：要不要连服务器（默认连）
CONNECT = (len(sys.argv) <= 3) or (sys.argv[3] != "noserver")
# 第四个参数：要不要先把专用服务端起起来（默认不起）
WITH_SERVER = len(sys.argv) > 4 and sys.argv[4] == "withserver"
TS = os.path.join(HERE, ".testserver")
SERVER_LOG = os.path.join(TS, "logs", "latest.log")
SERVER_JAR = os.path.join(TS, "server.jar")
JAVA_FOR_SERVER = os.path.join(JDK, "bin", "java.exe")


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
    g, a, v = name.split(":")
    return "/".join(g.split(".")) + "/%s/%s/%s-%s.jar" % (a, v, a, v)


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


d = json.load(open(VJSON, encoding="utf-8"))
libs = []
for lib in d["libraries"]:
    if not rules_match(lib.get("rules")):
        continue
    art = (lib.get("downloads") or {}).get("artifact")
    libs.append(os.path.join(MC, "libraries",
                             art["path"] if art else maven_path(lib["name"])))

subs = {
    "natives_directory": NATIVES, "launcher_name": "PCL", "launcher_version": "2.9.0",
    "classpath": ";".join(libs + [os.path.join(VDIR, VER + ".jar")]),
    "auth_player_name": "T", "version_name": VER, "game_directory": VDIR,
    "assets_root": os.path.join(MC, "assets"),
    "assets_index_name": d["assetIndex"]["id"],
    "auth_uuid": "00000000000000000000000000000000",
    "auth_access_token": "0", "user_type": "legacy", "version_type": "release",
}


def sub(s):
    for k, v in subs.items():
        s = s.replace("${" + k + "}", v)
    return s


dummy = None
if len(sys.argv) > 4 and sys.argv[4] == "dummyserver":
    import socket as _s
    import threading as _t
    srv = _s.socket()
    srv.setsockopt(_s.SOL_SOCKET, _s.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", 25565))
    srv.listen(8)
    print("哑监听器已绑定 0.0.0.0:25565")

    def _accept():
        while True:
            try:
                c, _ = srv.accept()
                print("   哑监听器收到一条连接")
            except OSError:
                return
    _t.Thread(target=_accept, daemon=True).start()

server = None
if WITH_SERVER:
    if os.path.exists(SERVER_LOG):
        os.replace(SERVER_LOG, SERVER_LOG + ".diagprev")
    so = open(os.path.join(TS, "server-diag.out"), "wb")
    server = subprocess.Popen(
        [JAVA_FOR_SERVER, "-Xmx2G", "-Dfile.encoding=UTF-8",
         "-cp", ";".join(libs + [SERVER_JAR]),
         "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
        cwd=TS, stdin=subprocess.PIPE, stdout=so, stderr=subprocess.STDOUT)
    print("起服务端 pid=%s，等 Done …" % server.pid)
    for _ in range(120):
        time.sleep(1)
        try:
            if "Done (" in open(SERVER_LOG, encoding="utf-8", errors="replace").read():
                break
        except OSError:
            pass
    print("服务端就绪")
    # 顺手验一下端口真的可连
    import socket
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect(("127.0.0.1", 25565))
        print("TCP 127.0.0.1:25565 可连 ✅")
    except OSError as e:
        print("TCP 127.0.0.1:25565 连不上 ❌ %s" % e)
    finally:
        s.close()

if os.path.exists(CLIENT_LOG):
    os.replace(CLIENT_LOG, CLIENT_LOG + ".diagprev")
f = open(OUT, "wb")
extra = ["--server", "127.0.0.1", "--port", "25565"] if CONNECT else []
# 试另一种写法：地址带端口，且不带 --port
if len(sys.argv) > 4 and sys.argv[4] == "combined":
    extra = ["--server", "127.0.0.1:25565"]
# 试：把 --server/--port 放到最前面（紧跟主类）
if len(sys.argv) > 4 and sys.argv[4] == "early":
    extra = ["--server", "127.0.0.1", "--port", "25565"]
print("extra  = %s" % extra)
p = subprocess.Popen([JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"]
                     + [sub(a) for a in flatten(d["arguments"]["jvm"])]
                     + [d["mainClass"]]
                     + [sub(a) for a in flatten(d["arguments"]["game"])]
                     + extra,
                     cwd=VDIR, stdout=f, stderr=subprocess.STDOUT)
print("java   = %s" % JAVA)
print("connect= %s" % CONNECT)
print("client pid = %s，等 %ds …" % (p.pid, WAIT))

# 20 秒后问操作系统要一次「真实命令行」——排查 --server 到底有没有传进去
time.sleep(20)
try:
    cmdline = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "(Get-CimInstance Win32_Process -Filter \"ProcessId=%d\").CommandLine" % p.pid],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=60).stdout.strip()
    print("=== OS 眼里这条 java 命令行（截断到 1200 字）===")
    print(cmdline[:1200])
    print("=== 里面有没有 --server ===")
    print("   --server 出现次数：", cmdline.count("--server"))
    print("   --port   出现次数：", cmdline.count("--port"))
except Exception as e:
    print("查命令行失败：%s" % e)

time.sleep(max(0, WAIT - 20))

log = ""
try:
    log = open(CLIENT_LOG, encoding="utf-8", errors="replace").read()
except OSError:
    pass
print("latest.log 末 5 行：")
for l in log.splitlines()[-5:]:
    print("   " + l)

print("\n===== jstack（只看 main 线程）=====")
out = subprocess.run([JSTACK, str(p.pid)], capture_output=True, text=True,
                     encoding="utf-8", errors="replace").stdout
blocks = re.split(r'\n(?=")', out)
for b in blocks:
    if b.startswith('"main"') or b.startswith('"Render thread"'):
        print(b.strip()[:3000])
        print("-" * 60)

print("\n=== 客户端日志里 Connecting / 连接结果 ===")
for l in log.splitlines():
    if re.search(r"Connecting to|logged in|joined|Disconnect|Couldn't connect|Timed out", l):
        print("   " + l[:200])
print("=== 服务端日志里有没有玩家进来 ===")
try:
    for l in open(SERVER_LOG, encoding="utf-8", errors="replace").read().splitlines():
        if re.search(r"logged in|joined|T\[", l):
            print("   " + l[:200])
except OSError:
    pass

subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
if server is not None:
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(server.pid)], capture_output=True)
print("\n已关闭客户端" + ("与服务端" if server else ""))
