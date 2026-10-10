#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""外接大脑（MCP）冒烟：起一个专用服务端，用裸 JSON-RPC 打它的 /mcp 端点。

这一层验的是「协议通不通、工具目录对不对、鉴权有没有生效」——
不需要客户端，也不需要世界里有玩家。

真正「驱动一只同伴」要世界里有玩家（同伴得有主人），那一步在单机里做：
用户进世界之后，由外接大脑（WorkBuddy 这边的 numen MCP 连接）直接派活。
见 numen-1.16.5/docs/PORTING.md 的「外接大脑」一节。

用法：python mcp-verify.py
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
SERVER_OUT = os.path.join(TS, "mcp-server-stdout.log")

MCP_PORT = 8766
MCP_URL = "http://127.0.0.1:%d/mcp" % MCP_PORT
TOKEN = open(os.path.join(TS, ".mcp-token"), encoding="utf-8").read().strip()

EXPECTED_TOOLS = ["list_companions", "create_companion", "delete_companion",
                  "get_events", "say",
                  "get_status", "inspect_block", "scan_blocks", "goto", "mine"]


# ---------------- 启动服务端（与 smoke-llm.py 同一套） ----------------

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


# ---------------- JSON-RPC ----------------

def rpc(method, params=None, rid=1, token=TOKEN):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    headers = {"Content-Type": "application/json",
               "Accept": "application/json, text/event-stream"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(MCP_URL, data=json.dumps(body).encode("utf-8"),
                                 headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read().decode("utf-8")
    # 端点回的是纯 JSON；万一将来改成 SSE，这里剥一层
    if raw.lstrip().startswith("data:"):
        for line in raw.splitlines():
            if line.startswith("data:"):
                raw = line[5:].strip()
                break
    return json.loads(raw)


def tool_text(resp):
    """从 tools/call 的回包里取第一段文字。"""
    try:
        return resp["result"]["content"][0]["text"]
    except (KeyError, IndexError, TypeError):
        return json.dumps(resp, ensure_ascii=False)[:400]


def main():
    if os.path.exists(SERVER_LOG):
        os.replace(SERVER_LOG, SERVER_LOG + ".mcpverifyprev")

    libs = libraries()
    missing = [p for p in libs if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)
        return 1

    print("=" * 70)
    print("[1/4] 起专用服务端（cwd=%s）…" % TS)
    print("=" * 70)
    sf = open(SERVER_OUT, "wb")
    server = subprocess.Popen([JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8",
                               "-cp", ";".join(libs + [SERVER_JAR]),
                               "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
                              cwd=TS, stdin=subprocess.PIPE,
                              stdout=sf, stderr=subprocess.STDOUT)

    deadline = time.time() + 300
    while time.time() < deadline:
        time.sleep(1.5)
        if "Done (" in read(SERVER_LOG):
            break
        if server.poll() is not None:
            print("服务端提前退出 code=%s" % server.returncode)
            print(read(SERVER_OUT)[-3000:])
            return 1
    else:
        print("服务端 300 秒没起来。")
        print(read(SERVER_OUT)[-3000:])
        server.kill()
        return 1
    print("  ✅ 服务端就绪")

    # MCP 服务器在模组初始化时起，服务端起来它就该在监听
    print("=" * 70)
    print("[2/4] 打 %s（端口 %d）" % (MCP_URL, MCP_PORT))
    print("=" * 70)

    results = {}

    # --- 鉴权：不带令牌必须 401 ---
    try:
        rpc("ping", token=None)
        results["auth_required"] = False
        print("  !! 不带令牌居然通了——鉴权没生效")
    except urllib.error.HTTPError as e:
        results["auth_required"] = (e.code == 401)
        print("  不带令牌 → HTTP %d（期望 401）" % e.code)
    except Exception as e:
        results["auth_required"] = False
        print("  不带令牌 → %r（期望 401）" % e)

    # --- initialize ---
    init = rpc("initialize", {"protocolVersion": "2025-06-18",
                              "capabilities": {},
                              "clientInfo": {"name": "mcp-verify", "version": "0.1"}})
    info = (init.get("result") or {}).get("serverInfo") or {}
    results["initialize"] = info.get("name") == "numen-mcp"
    print("  initialize → serverInfo=%s" % info)
    instr = (init.get("result") or {}).get("instructions") or ""
    print("  instructions %d 字，开头：%s" % (len(instr), instr[:80].replace("\n", " ")))

    # --- tools/list ---
    tl = rpc("tools/list", {})
    tools = [t["name"] for t in (tl.get("result") or {}).get("tools", [])]
    results["tools_list"] = all(t in tools for t in EXPECTED_TOOLS)
    print("  tools/list → %d 个工具：%s" % (len(tools), tools))

    # 每个带 companion 的工具都该有那个必填参数
    schemas = {t["name"]: t.get("inputSchema", {}) for t in (tl.get("result") or {}).get("tools", [])}
    bad = []
    for name in tools:
        if name in ("list_companions", "create_companion"):
            continue
        props = schemas[name].get("properties", {})
        req = schemas[name].get("required", [])
        if "companion" not in props or "companion" not in req:
            bad.append(name)
    results["companion_arg"] = not bad
    print("  companion 参数注入 → %s" % ("全部就位" if not bad else "缺：%s" % bad))

    # --- tools/call: list_companions（专用服上没玩家，应为空）---
    lc = rpc("tools/call", {"name": "list_companions", "arguments": {}})
    lc_text = tool_text(lc)
    results["list_companions"] = ("No companions are live" in lc_text)
    print("  list_companions → %s" % lc_text.replace("\n", " ")[:160])

    # --- tools/call: create_companion（没有玩家 → 应当是一条「失败」而不是异常）---
    cc = rpc("tools/call", {"name": "create_companion", "arguments": {"name": "Alice"}})
    cc_text = tool_text(cc)
    cc_err = bool((cc.get("result") or {}).get("isError"))
    results["create_graceful"] = cc_err and ("could not summon" in cc_text)
    print("  create_companion → isError=%s  %s" % (cc_err, cc_text[:160]))

    # --- 未知工具名：必须回失败而不是炸 ---
    # 注意顺序：handler 先解 companion 再找工具，所以专用服上（一只同伴都没有）
    # 先撞到的是「缺 companion」那条。两条都是「优雅失败」，都算过——
    # 真正走到「没有这个工具」那条要等世界里有同伴（单机那一步会验到）。
    ut = rpc("tools/call", {"name": "no_such_tool", "arguments": {"companion": "Alice"}})
    ut_text = tool_text(ut)
    ut_err = bool((ut.get("result") or {}).get("isError"))
    graceful = ("needs a 'companion'" in ut_text
                or "no such companion" in ut_text
                or "没有这个工具" in ut_text
                or "没有这只同伴" in ut_text)
    results["unknown_tool_graceful"] = ut_err and graceful
    print("  no_such_tool → isError=%s  %s" % (ut_err, ut_text[:160]))

    # --- 服务端还活着（没有被打崩）---
    results["server_alive"] = server.poll() is None
    print("  服务端进程还活着 → %s" % results["server_alive"])

    print("=" * 70)
    print("[3/4] 断言")
    print("=" * 70)
    checks = [
        ("不带令牌被 401 挡住", results.get("auth_required")),
        ("initialize 回 numen-mcp", results.get("initialize")),
        ("tools/list 含全部 10 个工具", results.get("tools_list")),
        ("每个工具都注入了必填 companion", results.get("companion_arg")),
        ("list_companions 通", results.get("list_companions")),
        ("create_companion 在没玩家时优雅失败", results.get("create_graceful")),
        ("未知工具名优雅失败（不炸服务端）", results.get("unknown_tool_graceful")),
        ("服务端没被打崩", results.get("server_alive")),
    ]
    ok = True
    for name, passed in checks:
        print("  [%s] %s" % ("PASS" if passed else "FAIL", name))
        ok = ok and bool(passed)

    print("=" * 70)
    print("[4/4] 服务端日志里的 [numen-mcp] 行")
    print("=" * 70)
    for l in read(SERVER_LOG).splitlines():
        if "numen-mcp" in l:
            print("  " + l[:220])

    server.stdin.write(b"stop\n")
    server.stdin.flush()
    time.sleep(6)
    if server.poll() is None:
        server.kill()
    print("\n已关闭服务端")
    print("\n总结论：" + ("全部通过 ✅" if ok else "有失败项 ❌"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
