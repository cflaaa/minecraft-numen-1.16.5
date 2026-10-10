#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外接大脑（MCP）的驱动台 —— 把 numen 的 /mcp 端点当成一个普通 JSON-RPC 服务来用。

为什么需要它：WorkBuddy 内置的 MCP 客户端只在启动时连一次，
服务端（游戏）没开的时候连接就断了，`mcp__numen__*` 那批工具不会出现在工具列表里。
但 MCP 就是个 HTTP + JSON-RPC 的东西，**谁都能当客户端**——
这个脚本就是「我」当外接大脑的那双手：直接 POST /mcp，走 initialize → tools/call。

子命令：
    serve [--port N]   起专用服务端（分离后台），等到 MCP 端点能应答为止
    stop               关掉服务端（按 pid 文件）
    tools              列工具目录
    call <工具> [json] 调一个工具；json 是 arguments 对象，省缺为 {}
    status             端点 / 令牌 / 在线同伴
    log [n]            打印服务端日志尾部
    waitplayer [秒]    等一个玩家进服（同伴必须有主人）

用法示例：
    python mcp-drive.py serve
    python mcp-drive.py call create_companion '{"name":"Alice"}'
    python mcp-drive.py call goto '{"companion":"Alice","x":100,"y":64,"z":100}'
    python mcp-drive.py call get_events '{"companion":"Alice","wait_seconds":20}'
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

MC = r"C:\Users\cfl\Desktop\.minecraft"
VER = "1.16.5-Fabric 0.19.5-2"
VDIR = os.path.join(MC, "versions", VER)
VJSON = os.path.join(VDIR, VER + ".json")
JAVA = (r"C:\Users\cfl\AppData\Roaming\.minecraft\runtime"
        r"\java-runtime-gamma-snapshot\bin\java.exe")

TS = os.path.join(HERE, ".testserver")
SERVER_JAR = os.path.join(TS, "server.jar")
SERVER_LOG = os.path.join(TS, "logs", "latest.log")
SERVER_OUT = os.path.join(TS, "drive-server-stdout.log")
PID_FILE = os.path.join(TS, ".drive-pid")

DEFAULT_PORT = 8766
HOST = "127.0.0.1"


# ---------------- 服务端启停 ----------------

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
    return out


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def cmd_serve(port):
    if os.path.exists(PID_FILE):
        old = read(PID_FILE).strip()
        print("已有 pid 文件：%s（先 stop 或删掉它）" % old)
        return 1

    libs = libraries()
    missing = [p for p in libs if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)
        return 1

    if os.path.exists(SERVER_LOG):
        os.replace(SERVER_LOG, SERVER_LOG + ".driveprev")

    print("起专用服务端（分离后台，cwd=%s）…" % TS)
    sf = open(SERVER_OUT, "wb")
    flags = 0
    if os.name == "nt":
        flags = (subprocess.DETACHED_PROCESS
                 | subprocess.CREATE_NEW_PROCESS_GROUP)
    proc = subprocess.Popen(
        [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8",
         "-cp", ";".join(libs + [SERVER_JAR]),
         "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
        cwd=TS, stdin=subprocess.DEVNULL, stdout=sf,
        stderr=subprocess.STDOUT, creationflags=flags)
    open(PID_FILE, "w").write(str(proc.pid))
    print("pid=%d 已落盘" % proc.pid)

    deadline = time.time() + 300
    while time.time() < deadline:
        time.sleep(1.5)
        if "Done (" in read(SERVER_LOG):
            break
        if proc.poll() is not None:
            print("服务端提前退出 code=%s" % proc.returncode)
            print(read(SERVER_OUT)[-3000:])
            return 1
    else:
        print("服务端 300 秒没起来。")
        print(read(SERVER_OUT)[-3000:])
        return 1

    print("  ✅ 服务端就绪")
    # MCP 服务器在模组初始化时就起了，这里再确认端点真的应答
    for _ in range(20):
        try:
            rpc("ping", None, 1, port=port)
            print("  ✅ MCP 端点应答：http://%s:%d/mcp" % (HOST, port))
            return 0
        except Exception:
            time.sleep(1.0)
    print("  !! 服务端起来了，但 MCP 端点不应答")
    for l in read(SERVER_LOG).splitlines():
        if "numen" in l.lower():
            print("   " + l[:220])
    return 1


def cmd_serve_fg(port):
    """前台跑服务端，交给调用方（后台任务）保活。

    试过 DETACHED_PROCESS：脚本一退，服务端就被回收了。
    改成「让服务端挂在一个长命的父进程下面」，由父进程负责它的生死。
    """
    libs = libraries()
    missing = [p for p in libs if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)
        return 1
    if os.path.exists(SERVER_LOG):
        os.replace(SERVER_LOG, SERVER_LOG + ".driveprev")
    cmd = [JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8",
           "-cp", ";".join(libs + [SERVER_JAR]),
           "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"]
    print("前台起专用服务端（cwd=%s）…" % TS, flush=True)
    sf = open(SERVER_OUT, "wb")
    p = subprocess.Popen(cmd, cwd=TS, stdin=subprocess.DEVNULL,
                         stdout=sf, stderr=subprocess.STDOUT)
    open(PID_FILE, "w").write(str(p.pid))
    print("pid=%d" % p.pid, flush=True)
    return p.wait()


def cmd_stop():
    if not os.path.exists(PID_FILE):
        print("没有 pid 文件")
        return 0
    pid = read(PID_FILE).strip()
    subprocess.run(["taskkill", "/F", "/T", "/PID", pid], capture_output=True)
    time.sleep(1.5)
    os.remove(PID_FILE)
    print("已关掉 pid=%s" % pid)
    return 0


# ---------------- JSON-RPC ----------------

def token(port):
    cfg = os.path.join(TS, "config", "numen", "mcp_server.json")
    try:
        return json.load(open(cfg, encoding="utf-8")).get("token", "")
    except OSError:
        return read(os.path.join(TS, ".mcp-token")).strip()


# ⚠ 这个环境里 http_proxy / https_proxy 都指着 127.0.0.1:60608，
# urllib 默认会把**连 localhost 的请求也塞给代理**，于是拿到 502 Bad Gateway。
# 我们只跟本机的游戏进程说话，直接把代理关掉，别去动全局环境变量。
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def rpc(method, params=None, rid=1, port=DEFAULT_PORT, tok=None):
    url = "http://%s:%d/mcp" % (HOST, port)
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    t = tok if tok is not None else token(port)
    if t:
        headers["Authorization"] = "Bearer " + t
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"),
                                 headers=headers, method="POST")
    with _OPENER.open(req, timeout=90) as r:
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
        return json.dumps(resp, ensure_ascii=False)[:800]


def handshake(port):
    """走一遍 initialize（有些客户端要求先握手；这里做全，顺便验证协议版本）。"""
    return rpc("initialize", {"protocolVersion": "2025-06-18",
                              "capabilities": {},
                              "clientInfo": {"name": "workbuddy-drive",
                                             "version": "0.1"}}, port=port)


def cmd_tools(port):
    tl = rpc("tools/list", {}, port=port)
    for t in (tl.get("result") or {}).get("tools", []):
        schema = t.get("inputSchema", {}) or {}
        req = schema.get("required", []) or []
        props = list((schema.get("properties") or {}).keys())
        print("- %s" % t["name"])
        print("    %s" % (t.get("description", "") or "").split("\n")[0][:150])
        print("    参数: %s   必填: %s" % (", ".join(props), ", ".join(req) or "无"))
    return 0


def cmd_call(port, name, args_json):
    try:
        args = json.loads(args_json) if args_json.strip() else {}
    except json.JSONDecodeError as e:
        print("arguments 不是合法 JSON：%s" % e)
        return 1
    resp = rpc("tools/call", {"name": name, "arguments": args}, port=port)
    res = resp.get("result") or {}
    err = res.get("isError")
    print("[%s] %s" % ("失败" if err else "成功", name))
    print(tool_text(resp))
    if resp.get("error"):
        print("RPC error: %s" % json.dumps(resp["error"], ensure_ascii=False))
    return 2 if err else 0


def cmd_status(port):
    init = handshake(port)
    info = (init.get("result") or {}).get("serverInfo") or {}
    print("端点   http://%s:%d/mcp" % (HOST, port))
    print("服务   %s %s" % (info.get("name"), info.get("version")))
    print("令牌   %s…（%d 位）" % (token(port)[:12], len(token(port))))
    tl = rpc("tools/list", {}, port=port)
    print("工具   %d 个" % len((tl.get("result") or {}).get("tools", [])))
    lc = rpc("tools/call", {"name": "list_companions", "arguments": {}}, port=port)
    print("同伴   %s" % tool_text(lc).replace("\n", " ")[:300])
    return 0


def cmd_log(n, port):
    lines = read(SERVER_LOG).splitlines()
    for l in lines[-n:]:
        print(l)
    return 0


def cmd_waitplayer(timeout, port):
    """等一个玩家进服：同伴必须有主人，专用服上没有人就 summon 不了。"""
    deadline = time.time() + timeout
    pat = re.compile(r"\]: ([A-Za-z0-9_]{3,16}) joined the game")
    seen = set()
    while time.time() < deadline:
        text = read(SERVER_LOG)
        m = pat.search(text)
        if m and m.group(1) not in seen:
            print("玩家进服：%s" % m.group(1))
            return 0
        time.sleep(1.0)
    print("等了 %ds 没有玩家进服" % timeout)
    return 1


def main():
    argv = sys.argv[1:]
    if not argv:
        print(__doc__)
        return 0

    port = DEFAULT_PORT
    if "--port" in argv:
        i = argv.index("--port")
        port = int(argv[i + 1])
        del argv[i:i + 2]

    sub = argv[0]
    rest = argv[1:]

    if sub == "serve":
        return cmd_serve(port)
    if sub == "serve-fg":
        return cmd_serve_fg(port)
    if sub == "stop":
        return cmd_stop()
    if sub == "tools":
        return cmd_tools(port)
    if sub == "call":
        if not rest:
            print("要工具名")
            return 1
        return cmd_call(port, rest[0], rest[1] if len(rest) > 1 else "")
    if sub == "status":
        return cmd_status(port)
    if sub == "log":
        return cmd_log(int(rest[0]) if rest else 60, port)
    if sub == "waitplayer":
        return cmd_waitplayer(int(rest[0]) if rest else 180, port)
    print("不认识的子命令：%s" % sub)
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main())
