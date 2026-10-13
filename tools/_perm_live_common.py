#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""perm-e2e.py 的公共部分：起服 / 起客户端 / MCP 调用 / 日志等待 / 坐标读取。

从 perm-live-test.py 里抽出来的（那个文件是单体版，留着当参考）。
"""
import json
import math
import os
import re
import subprocess
import threading
import time
import urllib.request

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
SERVER_OUT = os.path.join(TS, "perm-e2e-server.log")
CLIENT_OUT = os.path.join(TS, "perm-e2e-client.log")

PLAYER = "T"
COMPANION = "Alice"
PORT = "25565"
MCP_PORT = 8766

server = None
client = None


# ---------------------------------------------------------------- 基础

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


def wait_new(pattern, mark, timeout, proc=None):
    deadline = time.time() + timeout
    rx = re.compile(pattern)
    while time.time() < deadline:
        time.sleep(1.0)
        for line in read(SERVER_LOG)[mark:].splitlines():
            if rx.search(line):
                return line
        if proc is not None and proc.poll() is not None:
            return None
    return None


def cmd(line):
    print("   > " + line, flush=True)
    server.stdin.write((line + "\n").encode("utf-8"))
    server.stdin.flush()
    time.sleep(0.6)


def start_server(libs):
    global server
    for p in (SERVER_LOG, CLIENT_LOG):
        if os.path.exists(p):
            os.replace(p, p + ".e2eprev")
    server = subprocess.Popen(
        [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8", "-cp",
         ";".join(libs + [SERVER_JAR]),
         "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
        cwd=TS, stdin=subprocess.PIPE,
        stdout=open(SERVER_OUT, "wb"), stderr=subprocess.STDOUT)
    return server


def start_client(d, libs):
    global client
    subs = {
        "natives_directory": NATIVES, "launcher_name": "PCL", "launcher_version": "2.9.0",
        "classpath": ";".join(libs + [os.path.join(VDIR, VER + ".jar")]),
        "auth_player_name": PLAYER, "version_name": VER, "game_directory": VDIR,
        "assets_root": os.path.join(MC, "assets"), "assets_index_name": d["assetIndex"]["id"],
        "auth_uuid": "00000000000000000000000000000000", "auth_access_token": "0",
        "user_type": "legacy", "version_type": "release",
    }

    def sub(s):
        for k, v in subs.items():
            s = s.replace("${" + k + "}", v)
        return s

    jvm = [sub(a) for a in flatten(d["arguments"]["jvm"])]
    game = [sub(a) for a in flatten(d["arguments"]["game"])]
    game += ["--server", "127.0.0.1", "--port", PORT]
    client = subprocess.Popen(
        [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"] + jvm + [d["mainClass"]] + game,
        cwd=VDIR, stdout=open(CLIENT_OUT, "wb"), stderr=subprocess.STDOUT)
    return client


def kill_all():
    for p in (client, server):
        if p is not None and p.poll() is None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)


# ---------------------------------------------------------------- MCP

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def mcp_token():
    cfg = os.path.join(TS, "config", "numen", "mcp_server.json")
    try:
        return json.load(open(cfg, encoding="utf-8")).get("token", "")
    except OSError:
        return ""


def rpc(method, params=None, rid=1, timeout=300):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    t = mcp_token()
    if t:
        headers["Authorization"] = "Bearer " + t
    req = urllib.request.Request("http://127.0.0.1:%d/mcp" % MCP_PORT,
                                 data=json.dumps(body).encode("utf-8"),
                                 headers=headers, method="POST")
    with _OPENER.open(req, timeout=timeout) as r:
        raw = r.read().decode("utf-8")
    if raw.lstrip().startswith("data:"):
        for line in raw.splitlines():
            if line.startswith("data:"):
                raw = line[5:].strip()
                break
    return json.loads(raw)


def tool_text(resp):
    try:
        return resp["result"]["content"][0]["text"]
    except (KeyError, IndexError, TypeError):
        return json.dumps(resp, ensure_ascii=False)[:600]


def call(name, args, timeout=300):
    return tool_text(rpc("tools/call", {"name": name, "arguments": args}, timeout=timeout))


def call_async(name, args):
    box = {}

    def run():
        try:
            box["text"] = call(name, args)
        except Exception as ex:      # noqa: BLE001
            box["error"] = repr(ex)

    t = threading.Thread(target=run, daemon=True)
    t.start()
    return t, box


def wait_mcp(thread, box, timeout=120):
    thread.join(timeout)
    return box.get("text") or box.get("error") or "(超时没回)"


# ---------------------------------------------------------------- 世界

def pos_of(name):
    """她所在的**方块**坐标（floor，不是截断——负坐标截断会偏一格）。"""
    mark = len(read(SERVER_LOG))
    cmd("/data get entity %s Pos" % name)
    line = wait_new(r"has the following entity data: \[", mark, 10)
    if not line:
        return None
    m = re.search(r"\[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]", line)
    if not m:
        return None
    return tuple(int(math.floor(float(m.group(i)))) for i in (1, 2, 3))


def nearest(text, block_id):
    rx = re.compile(re.escape(block_id) + r"\s*×\d+，最近一个在\s*(-?\d+),(-?\d+),(-?\d+)")
    m = rx.search(text)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3))) if m else None


def next_consent(mark, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        ids = re.findall(r"\[numen-consent\][^\n]*?ask #(\d+)", read(SERVER_LOG)[mark:])
        if ids:
            return int(ids[-1])
        time.sleep(0.5)
    return None


def answer(request_id, decision, note=""):
    line = "/execute as %s run numen consent %s %d" % (PLAYER, decision, request_id)
    if note:
        line += " " + note
    cmd(line)
