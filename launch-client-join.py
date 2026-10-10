#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""起 PCL 的 1.16.5 Fabric 客户端，并让它**自动连进**本机专用服（25565）。

和 launch-mc.py 的区别：
  - 追加 `--server 127.0.0.1 --port 25565` 两个游戏参数 → 客户端跳过主菜单直接进服；
  - **不杀进程**：客户端要一直开着，外接大脑（MCP）才有「主人」可以派活。

用法：python launch-client-join.py
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
LOG = os.path.join(VDIR, "logs", "latest.log")
OUT = os.path.join(VDIR, "join-stdout.log")

SERVER = "127.0.0.1"
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


def maven_path(name):
    group, artifact, version = name.split(":")
    return "/".join(group.split(".")) + "/%s/%s/%s-%s.jar" % (
        artifact, version, artifact, version)


def main():
    d = json.load(open(VJSON, encoding="utf-8"))
    cp = []
    for lib in d["libraries"]:
        if not rules_match(lib.get("rules")):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        if art:
            cp.append(os.path.join(MC, "libraries", art["path"]))
        else:
            cp.append(os.path.join(MC, "libraries", maven_path(lib["name"])))
    cp.append(os.path.join(VDIR, VER + ".jar"))

    subs = {
        "natives_directory": NATIVES,
        "launcher_name": "PCL",
        "launcher_version": "2.9.0",
        "classpath": ";".join(cp),
        "auth_player_name": "T",
        "version_name": VER,
        "game_directory": VDIR,
        "assets_root": os.path.join(MC, "assets"),
        "assets_index_name": d["assetIndex"]["id"],
        "auth_uuid": "00000000000000000000000000000000",
        "auth_access_token": "0",
        "user_type": "legacy",
        "version_type": "release",
    }

    def sub(s):
        for k, v in subs.items():
            s = s.replace("${" + k + "}", v)
        return s

    jvm = [sub(a) for a in flatten(d["arguments"]["jvm"])]
    game = [sub(a) for a in flatten(d["arguments"]["game"])]
    # 自动进服：这两个参数客户端认，等价于在主菜单点「多人游戏 → 直连」
    game += ["--server", SERVER, "--port", PORT]

    cmd = ([JAVA, "-Xmx2G", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8"]
           + jvm + [d["mainClass"]] + game)

    missing = [p for p in cp if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)

    # 不挪 latest.log：上一个客户端可能还开着，文件被占着（WinError 32）。
    # 改成记下当前长度，只看新追加的那一段。
    try:
        start_at = os.path.getsize(LOG)
    except OSError:
        start_at = 0

    print("起客户端并自动连 %s:%s …" % (SERVER, PORT), flush=True)
    with open(OUT, "wb") as fout:
        proc = subprocess.Popen(cmd, cwd=VDIR, stdin=subprocess.DEVNULL,
                                stdout=fout, stderr=subprocess.STDOUT)
    print("客户端 pid=%d，日志 %s（从偏移 %d 起看）" % (proc.pid, LOG, start_at),
          flush=True)
    return proc.wait()


if __name__ == "__main__":
    sys.exit(main())
