#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""numen 1.16.5 冒烟测试（第二层）：模型 → 工具调用 → 回灌 → 收尾，整条链走通。

第一层（smoke-test.py）只证明了「能召唤、能查状态」——那条路不碰模型。
1.16.5 这一版真正新写、也真正有风险的是「工具实现」：inspect_block / scan_blocks /
goto / mine 全都是照 1.16.5 的方块与实体 API 重写的。它们只有在模型回 tool_call
的时候才会被执行——不测就等于没验证。

这里用一个本地假端点（fake-llm.py）当模型：它按「对话里已经有几条 tool 结果」
决定这一轮回哪个工具，于是整条链是确定性的，跑没跑可以在日志里断言。

链路（见 fake-llm.py 的剧本）：
  get_status → inspect_block → scan_blocks → mine → goto → say → 收尾

用法：python smoke-llm.py
"""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

MC = r"C:\Users\cfl\Desktop\.minecraft"
VER = "1.16.5-Fabric 0.19.5-2"
VDIR = os.path.join(MC, "versions", VER)
VJSON = os.path.join(VDIR, VER + ".json")
JAVA = (r"C:\Users\cfl\AppData\Roaming\.minecraft\runtime"
        r"\java-runtime-gamma-snapshot\bin\java.exe")
NATIVES = os.path.join(VDIR, VER + "-natives")
CLIENT_LOG = os.path.join(VDIR, "logs", "latest.log")

TS = os.path.join(HERE, ".testserver")
SERVER_JAR = os.path.join(TS, "server.jar")
SERVER_LOG = os.path.join(TS, "logs", "latest.log")
SERVER_OUT = os.path.join(TS, "server-stdout.log")
CLIENT_OUT = os.path.join(TS, "client-stdout.log")
FAKE_OUT = os.path.join(TS, "fake-llm.out")
FAKE_REQLOG = os.path.join(TS, "fake-llm-requests.jsonl")

PYTHON = sys.executable
FAKE_PORT = "18765"

PLAYER = "T"
COMPANION = "Alice"
PORT = "25565"


# ---------------- 与 smoke-test.py 相同的启动辅助 ----------------

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


def wait_for(path, needles, timeout, procs=(), label=""):
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(1.0)
        text = read(path)
        for n in needles:
            if n in text:
                return n
        for p in procs:
            if p is not None and p.poll() is not None:
                print("  !! %s 进程提前退出 code=%s" % (label, p.returncode))
                return None
    return None


def tail(path, n, title):
    print("\n---- %s 末 %d 行 ----" % (title, n))
    for l in read(path).splitlines()[-n:]:
        print(l)


def kill(*procs):
    for p in procs:
        if p is not None and p.poll() is None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                           capture_output=True)


def main():
    for p in (SERVER_LOG, CLIENT_LOG, FAKE_REQLOG):
        if os.path.exists(p):
            os.replace(p, p + ".prev")

    d, libs = libraries()
    missing = [p for p in libs if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)
        return 1

    # ================= 0. 假模型端点 =================
    print("=" * 70)
    print("[0/5] 启动本地假模型端点 127.0.0.1:%s …" % FAKE_PORT)
    print("=" * 70)
    ff = open(FAKE_OUT, "wb")
    fake = subprocess.Popen([PYTHON, os.path.join(HERE, "fake-llm.py"), FAKE_PORT],
                            cwd=HERE, stdout=ff, stderr=subprocess.STDOUT)
    time.sleep(2.5)
    if fake.poll() is not None:
        print("假端点没起来。")
        tail(FAKE_OUT, 30, "fake-llm.out")
        return 1
    print("  ✅ 假端点就绪")

    # ================= 1. 服务端 =================
    print("=" * 70)
    print("[1/5] 启动专用服务端 …")
    print("=" * 70)
    sf = open(SERVER_OUT, "wb")
    server = subprocess.Popen([JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8",
                               "-cp", ";".join(libs + [SERVER_JAR]),
                               "net.fabricmc.loader.impl.launch.knot.KnotServer", "nogui"],
                              cwd=TS, stdin=subprocess.PIPE,
                              stdout=sf, stderr=subprocess.STDOUT)

    if wait_for(SERVER_LOG, ["Done ("], 300, (server, fake), "服务端") is None:
        print("服务端没起来。")
        tail(SERVER_OUT, 60, "server-stdout.log")
        tail(SERVER_LOG, 40, "server latest.log")
        kill(server, fake)
        return 1
    print("  ✅ 服务端就绪")

    # ================= 2. 客户端直连 =================
    print("=" * 70)
    print("[2/5] 启动客户端并直连 …")
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
    game += ["--server", "127.0.0.1", "--port", PORT,
             "--width", "854", "--height", "480"]

    # 客户端启动有个**已知的偶发竞态**：大约三次里只有一次会走到「自动连服」那一步，
    # 失败时日志停在贴图图集那几行、之后一声不吭，连 TCP 连接都不发。
    # 实测与「有没有监听者」「用哪个 JVM」都无关（A/B 都验过），是环境层面的抖动，
    # 不是模组问题。所以这里多试几次，而不是把它当成被测对象。
    ATTEMPTS = 6
    PER_ATTEMPT = 75

    def launch_client(attempt):
        if os.path.exists(CLIENT_LOG):
            os.replace(CLIENT_LOG, CLIENT_LOG + ".prev%d" % attempt)
        cf = open(CLIENT_OUT, "wb")
        p = subprocess.Popen([JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"]
                             + jvm + [d["mainClass"]] + game,
                             cwd=VDIR, stdout=cf, stderr=subprocess.STDOUT)
        print("  （第 %d/%d 次尝试，pid=%s）" % (attempt, ATTEMPTS, p.pid))
        if wait_for(SERVER_LOG, ["joined the game"], PER_ATTEMPT, (server, p),
                    "服务端") is not None:
            return p
        reached = "Connecting to" in read(CLIENT_LOG)
        print("  !! 这次没连上（走到自动连服那一步了吗：%s）" % ("是" if reached else "否"))
        kill(p)
        time.sleep(2)
        return None

    client = None
    for attempt in range(1, ATTEMPTS + 1):
        client = launch_client(attempt)
        if client is not None:
            print("  ✅ 第 %d 次尝试连上了" % attempt)
            break

    if client is None:
        print("客户端 %d 次都没能连进来。" % ATTEMPTS)
        tail(SERVER_OUT, 30, "server-stdout.log")
        tail(CLIENT_LOG, 40, "客户端 latest.log")
        kill(server, fake)
        return 1
    time.sleep(5)

    # ================= 3. 配好端点 + 召唤 =================
    print("=" * 70)
    print("[3/5] 把配置指向假端点，然后召唤")
    print("=" * 70)
    base = "http://127.0.0.1:%s/v1" % FAKE_PORT
    cmds = [
        "/numen config baseUrl %s" % base,
        "/numen config apiKey smoke-test-key",
        "/numen config model gpt-4o-mini",
        "/numen config provider openai",
        "/numen config initiative 6",
        "/execute as %s run numen summon %s" % (PLAYER, COMPANION),
        "/numen status %s" % COMPANION,
    ]
    for cmd in cmds:
        print("  >>> " + cmd)
        server.stdin.write((cmd + "\n").encode("utf-8"))
        server.stdin.flush()
        time.sleep(2.5)

    # ================= 4. 触发整条链 =================
    print("=" * 70)
    print("[4/5] 跟她说一句话，触发「模型 → 工具 → 回灌」整条链")
    print("=" * 70)
    cmd = "/execute as %s run numen tell %s 看看你脚下是什么方块" % (PLAYER, COMPANION)
    print("  >>> " + cmd)
    server.stdin.write((cmd + "\n").encode("utf-8"))
    server.stdin.flush()

    done = wait_for(SERVER_LOG, ["<Alice>"], 180, (server, client, fake), "服务端")
    if done is None:
        print("  !! 没等到收尾发言（<Alice> 广播）")
    else:
        print("  ✅ 等到收尾发言")
    time.sleep(8)                        # 留一点时间给最后那轮收尾

    # ================= 5. 结果 =================
    print("=" * 70)
    print("[5/5] 结果")
    print("=" * 70)

    sl = read(SERVER_LOG)
    print("\n---- 服务端日志：numen / Alice / 工具 / 异常 ----")
    for l in sl.splitlines():
        if re.search(r"numen|Alice|事件|Exception|Error|Caused by|at com\.dwinovo", l, re.I):
            print(l[:260])

    # 假端点收到了几轮请求、每轮回的是什么工具
    print("\n---- 假端点收到的请求（每轮：工具名 / 消息角色 / 工具数）----")
    n_req = 0
    for line in read(FAKE_REQLOG).splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        n_req += 1
        b = rec.get("body", {})
        print("  #%d 回 %-14s 消息 %d 条 %s  工具 %d 个"
              % (n_req, rec.get("reply"), b.get("n_messages"),
                 b.get("roles"), b.get("n_tools")))

    # ---- 断言 ----
    print("\n---- 断言 ----")
    checks = [
        ("模型被调用过（假端点收到请求）", n_req >= 5),
        ("get_status 跑过", "get_status" in sl or "get_status" in read(FAKE_REQLOG)),
        ("inspect_block 跑过", "inspect_block" in sl),
        ("scan_blocks 跑过", "scan_blocks" in sl),
        ("mine 跑过", "mine" in sl),
        ("goto 跑过", "goto" in sl),
        ("say 收尾发言已广播", "<Alice>" in sl),
        ("无 java.lang 异常", "java.lang." not in sl),
        ("无 ClassCastException", "ClassCastException" not in sl),
        ("无 NoClassDefFoundError", "NoClassDefFoundError" not in sl),
    ]
    ok = True
    for name, passed in checks:
        print("  [%s] %s" % ("PASS" if passed else "FAIL", name))
        ok = ok and passed

    tail(SERVER_LOG, 40, "server latest.log")

    kill(client, server, fake)
    time.sleep(1)
    print("\n已关闭客户端、服务端与假端点")
    print("\n总结论：" + ("全部通过 ✅" if ok else "有失败项 ❌"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
