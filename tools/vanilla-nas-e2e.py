#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""环境B 专项：**纯原版**客户端跨机连 NAS 上的 docker 服（192.168.1.149:25586）。

验证「真实部署 + 跨机访问 + vanilla 客户端兼容」三合一。客户端构造同 vanilla-client-e2e
（同一份 jar、摘掉 fabric 库、主类换回 net.minecraft.client.main.Main），
只是 --server 指向 NAS，服务端不自起（容器已在跑）。
判据：NAS 日志看到 `Vanilla joined the game` + 客户端进程活着无断线异常。
"""
import json
import os
import re
import subprocess
import sys
import threading
import time

import paramiko

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402
import nas_creds                # noqa: E402

HOST, USER, PWD = nas_creds.host_user_pwd()
SUDO = nas_creds.sudo_prefix()
SERVER_IP, SERVER_PORT = HOST, "25586"
PLAYER = "Vanilla"

VANILLA_DIR = os.path.join(P.TS, "vanilla-client")
CLIENT_OUT = os.path.join(P.TS, "vanilla-nas-stdout.log")
CLIENT_LOG = os.path.join(VANILLA_DIR, "logs", "latest.log")

client = None
ssh = None

JPS = os.path.join(os.path.dirname(P.JAVA), "jps.exe")
JSTACK = os.path.join(os.path.dirname(P.JAVA), "jstack.exe")


def kill_stale_clients():
    """起新客户端前清场：杀掉上一次失败运行泄漏的 net.minecraft.client.main.Main 进程，
    否则它们会抢占 gameDir/logs/latest.log，污染本次日志（实测踩过）。"""
    try:
        out = subprocess.run([JPS, "-l"], capture_output=True, text=True, timeout=20).stdout
    except Exception:
        return
    for line in out.splitlines():
        if "net.minecraft.client" in line:
            pid = line.split()[0]
            print("   清场：结束残留客户端 pid=%s" % pid)
            subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)


def dump_render_stack(tag="Render thread"):
    """失败时抓当前客户端主线程栈，直接看卡在哪（省一轮往返）。"""
    if client is None:
        return
    try:
        st = subprocess.run([JSTACK, str(client.pid)], capture_output=True, text=True,
                            timeout=60).stdout
    except Exception as ex:      # noqa: BLE001
        print("   (jstack 失败 %r)" % ex)
        return
    lines = st.splitlines()
    for i, l in enumerate(lines):
        if '"%s"' % tag in l:
            print("   !! %s 栈（前 22 帧）：" % tag)
            for x in lines[i:i + 22]:
                print("      " + x)
            return
    print("   (没找到 %s，线程名清单：)" % tag)
    for l in lines:
        if l.startswith('"'):
            print("      " + l)


def nas_log(since="3m"):
    _, out, _ = ssh.exec_command(
        f"{SUDO} docker logs --since {since} mc1165-test 2>&1 | grep -v password", timeout=30)
    return out.read().decode("utf-8", "replace")


def wait_nas(pattern, timeout=300):
    rx = re.compile(pattern)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if rx.search(nas_log("5m")):
            return True
        time.sleep(3)
    return False


def build_cmd():
    d = json.load(open(P.VJSON, encoding="utf-8"))
    cp, skipped = [], []
    for lib in d["libraries"]:
        name = lib["name"]
        if name.startswith("net.fabricmc:"):
            skipped.append(name)
            continue
        if not P.rules_match(lib.get("rules")):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        cp.append(os.path.join(P.MC, "libraries", art["path"]) if art
                  else os.path.join(P.MC, "libraries", P.maven_path(name)))
    cp.append(os.path.join(P.VDIR, P.VER + ".jar"))
    print("   摘掉的 fabric 库：%s" % skipped)
    subs = {
        "natives_directory": P.NATIVES, "launcher_name": "PCL", "launcher_version": "2.9.0",
        "classpath": ";".join(cp),
        "auth_player_name": PLAYER, "version_name": P.VER, "game_directory": VANILLA_DIR,
        "assets_root": os.path.join(P.MC, "assets"), "assets_index_name": d["assetIndex"]["id"],
        "auth_uuid": "00000000000000000000000000000000", "auth_access_token": "0",
        "user_type": "legacy", "version_type": "release",
    }
    def sub(s):
        for k, v in subs.items():
            s = s.replace("${" + k + "}", v)
        return s
    jvm = [sub(a) for a in P.flatten(d["arguments"]["jvm"])]
    game = [sub(a) for a in P.flatten(d["arguments"]["game"])]
    game += ["--server", SERVER_IP, "--port", SERVER_PORT]
    # ⭐ 关键：1.16.5 的 --server 自动进服被 socialInteractionsService.serversAllowed() 门控
    #   （Minecraft ctor line 429：allowsMultiplayer() 为假则 string=null，只建 TitleScreen）。
    #   我们用假 token；若认证请求**能连上** Mojang，createSocialInteractionsService 成功返回
    #   真实 service，其 serversAllowed()=false → 不自动进服、停在主菜单（实测踩过）。
    #   指向死代理让认证请求**立即失败** → 抛 AuthenticationException → 走 OfflineSocialInteractions
    #   (serversAllowed()=true) → 稳定自动进服。游戏本体走裸 socket，不受此代理影响。
    game += ["--proxyHost", "127.0.0.1", "--proxyPort", "1"]
    return [P.JAVA, "-Xmx2G", "-Dfile.encoding=UTF-8"] + jvm + \
           ["net.minecraft.client.main.Main"] + game


def read(path):
    try:
        return open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def main():
    global client, ssh
    os.makedirs(VANILLA_DIR, exist_ok=True)
    kill_stale_clients()
    if os.path.exists(CLIENT_LOG):
        try:
            os.replace(CLIENT_LOG, CLIENT_LOG + ".nasprev")
        except OSError:
            pass

    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=10)

    print("=" * 68, flush=True)
    print("跨机 vanilla 客户端 → %s:%s（docker mc1165-test）" % (SERVER_IP, SERVER_PORT), flush=True)
    print("=" * 68, flush=True)
    cmd = build_cmd()
    print("   mainClass = net.minecraft.client.main.Main（纯原版）")
    client = subprocess.Popen(cmd, cwd=VANILLA_DIR, stdin=subprocess.DEVNULL,
                              stdout=open(CLIENT_OUT, "wb"), stderr=subprocess.STDOUT)
    print("   客户端 pid=%d，连 %s:%s" % (client.pid, SERVER_IP, SERVER_PORT))

    joined = wait_nas(r"%s joined the game" % PLAYER, 300)
    print("   → NAS 日志「%s joined the game」：%s" % (PLAYER, "✅" if joined else "❌"))
    if not joined:
        print("   !! 客户端 stdout 末尾：")
        for l in read(CLIENT_OUT).splitlines()[-25:]:
            print("      " + l)
        print("   !! 客户端 latest.log 里 Connecting/服务器相关：")
        for l in read(CLIENT_LOG).splitlines():
            if re.search(r"connect|server|join|refus|timeout", l, re.I):
                print("      " + l)
        print("   !! 客户端 latest.log 末尾：")
        for l in read(CLIENT_LOG).splitlines()[-20:]:
            print("      " + l)
        dump_render_stack()
        try:
            client.kill()
        except Exception:
            pass
        ssh.close()
        return 1

    time.sleep(8)  # 让世界渲染一会儿，看会不会被异步包踢掉

    clog = read(CLIENT_LOG) + "\n" + read(CLIENT_OUT)
    BENIGN = re.compile(r"options_background|AuthenticationUnavailable|Failed to verify authentication"
                        r"|Cannot contact authentication server|Connect timed out|Connection refused")
    lines = clog.splitlines()
    bad = [l for l in lines if re.search(r"Disconnect|disconnect|Exception|Error|FATAL|crash", l)
           and not BENIGN.search(l)]
    print("   【客户端】真异常/断线行（排除基线噪声）：%s" % ("✅ 无" if not bad else "❌ %d 行" % len(bad)))
    for l in bad[:8]:
        print("      " + l)
    alive = client.poll() is None
    print("   【客户端】进程还活着：%s（pid=%d, rc=%s）" % ("✅" if alive else "❌", client.pid, client.poll()))

    slog = nas_log("5m")
    warn = [l for l in slog.splitlines()
            if re.search(r"channel|payload|does not have|not registered", l) and "Vanilla" not in l]
    warn = [l for l in warn if "Using default channel type" not in l]
    print("   【服务端】自定义通道/载荷告警：%s" % ("✅ 无" if not warn else "❌ %d 行" % len(warn)))
    for l in warn[:8]:
        print("      " + l)
    chat = [l for l in lines if "[CHAT]" in l]
    print("   【客户端】[CHAT] 行：%d 条" % len(chat))
    for l in chat[:6]:
        print("      " + l)

    try:
        client.kill()
    except Exception:
        pass
    ssh.close()

    ok = joined and alive and not bad
    print("\n小结：%s" % ("✅ 跨机部署可用，原版客户端无需 mod 进服成功"
                        if ok else "❌ 见上"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
