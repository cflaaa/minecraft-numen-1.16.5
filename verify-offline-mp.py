#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在**独立的 gameDir** 里启动 1.16.5 Fabric 客户端，验证 mixin 是否生效。

为什么不用 launch-mc.py：用户的 PCL 客户端正开着，它占着
versions/<VER>/logs/latest.log（WinError 32），而且不该为了验证去关掉别人的游戏。
换个 --gameDir 就互不干扰：assets / libraries / versions 是只读共享的，
只有 logs、mods、options.txt 跟着 gameDir 走。

用法：
    python verify-offline-mp.py [超时秒数]

退出码：0 = 进到主界面且看到 mixin 日志；1 = 失败；2 = 超时
"""
import json
import os
import re
import shutil
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

GAMEDIR = r"C:\Users\cfl\AppData\Local\Temp\numen-verify"
MODS_SRC = os.path.join(VDIR, "mods")
JARS = ["numen-0.1.4-1.16.5.jar", "fabric-api-0.42.0+1.16.jar"]

LOG = os.path.join(GAMEDIR, "logs", "latest.log")
OUT = os.path.join(GAMEDIR, "launch-stdout.log")

TIMEOUT = int(sys.argv[1]) if len(sys.argv) > 1 else 240

SUCCESS = ["Sound engine started"]
WANT = ["[numen-panel] 客户端已就绪", "[numen] 离线会话"]
FATAL = [
    "Incompatible mods found",
    "A fatal error has been detected",
    "Failed to start Minecraft",
    'Exception in thread "main"',
    "Could not execute entrypoint",
    "java.lang.NoClassDefFoundError",
    "java.lang.NoSuchMethodError",
    "Mixin apply failed",
    "InvalidInjectionException",
    "---- Minecraft Crash Report ----",
]


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
    os.makedirs(os.path.join(GAMEDIR, "mods"), exist_ok=True)
    for j in JARS:
        src = os.path.join(MODS_SRC, j)
        if not os.path.exists(src):
            print("!! 缺 jar:", src)
            return 1
        shutil.copy2(src, os.path.join(GAMEDIR, "mods", j))
    print("独立 gameDir:", GAMEDIR)
    for j in JARS:
        print("   mods/" + j)

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
        "game_directory": GAMEDIR,
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
    extra = []
    if os.environ.get("NUMEN_MIXIN_DEBUG") == "1":
        # 看 mixin 配置有没有被读到、注入有没有成功
        extra = ["-Dmixin.debug=true", "-Dmixin.debug.verbose=true"]
    cmd = ([JAVA, "-Xmx2G", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8"]
           + extra + jvm + [d["mainClass"]] + game)

    if os.path.exists(LOG):
        try:
            os.replace(LOG, LOG + ".prev")
        except OSError as e:
            print("（旧日志挪不动，忽略）", e)

    print("启动中 … 日志：%s" % LOG)
    with open(OUT, "wb") as fout:
        proc = subprocess.Popen(cmd, cwd=GAMEDIR, stdout=fout,
                                stderr=subprocess.STDOUT)

    deadline = time.time() + TIMEOUT
    verdict, hit = None, None
    while time.time() < deadline:
        time.sleep(1.0)
        try:
            text = open(LOG, encoding="utf-8", errors="replace").read()
        except OSError:
            text = ""
        if any(m in text for m in SUCCESS):
            verdict, hit = "OK", "Sound engine started"
            break
        for m in FATAL:
            if m in text:
                verdict, hit = "FAIL", m
                break
        if verdict:
            break
        if proc.poll() is not None:
            verdict, hit = "FAIL", "进程退出 (code=%s)" % proc.returncode
            break

    if verdict is None:
        verdict, hit = "TIMEOUT", "超过 %ds" % TIMEOUT

    # 再多读一会儿，让 TitleScreen 初始化（那里才会调 allowsMultiplayer）与日志落盘
    time.sleep(25)
    try:
        text = open(LOG, encoding="utf-8", errors="replace").read()
    except OSError:
        text = ""

    print("=" * 70)
    print("结果：%s  (%s)" % (verdict, hit))
    print("=" * 70)
    print("---- 关心的几行 ----")
    found = False
    for line in text.splitlines():
        if any(w in line for w in WANT) or "mixin" in line.lower() or "[numen]" in line:
            print(line)
            found = True
    if not found:
        print("（没有匹配行）")

    if proc.poll() is None:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       capture_output=True)
        time.sleep(1)
        print("已关闭验证用客户端")

    return 0 if verdict == "OK" else (2 if verdict == "TIMEOUT" else 1)


if __name__ == "__main__":
    sys.exit(main())
