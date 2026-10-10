#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在独立 gameDir 里启动 1.16.5 Fabric 客户端，自动打开 Numen 面板的某一页，
然后（用文字日志）核对面板有没有画到状态栏上。

为什么需要它：面板的布局代码**只在那一页真的被画出来时才跑**。
光启动客户端、停在标题界面，是验证不到「按键重叠」这个问题的。
但真机截图要人先按 G 再点页签，没法进流水线。

所以客户端里埋了个（默认不生效的）开关：-Dnumen.panel=settings|companions
    客户端一进世界就自动 setScreen(new NumenPanelScreen().debugTab(...))
本脚本就是喂这个参数，再读日志。

用法：
    python verify-panel.py settings [超时秒数] [宽x高]
    python verify-panel.py companions [超时秒数] [宽x高]

    第三参数可选，用来钉死窗口尺寸（默认 427x240，是这台机器上的实测值）。
    面板高度 = min(280, 窗口高 - 20)，窗口越矮正文区越小 —— 「按键重叠」就是矮窗口才有的。

退出码：0 = 面板已打开且没有溢出告警；1 = 失败；2 = 超时
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

TAB = (sys.argv[1] if len(sys.argv) > 1 else "settings").lower()
TIMEOUT = int(sys.argv[2]) if len(sys.argv) > 2 else 300
# 可选第三参数：宽x高，例如 427x240。不写就用 options.txt 里记着的尺寸。
WINDOW = None
if len(sys.argv) > 3 and "x" in sys.argv[3]:
    w, h = sys.argv[3].lower().split("x", 1)
    WINDOW = (int(w), int(h))

# 启动成功的信号
SUCCESS = ["Sound engine started"]
# 我们真正要找的：面板被自动打开
WANT_OPEN = "[numen-panel] 排查开关生效"
# 面板画出来了
WANT_DRAWN = "页已绘制"
# 面板自己 report 出来的布局告警（fits() 拦下来时打印的）
WANT_OVERFLOW = "[numen-panel] 放不下"
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


def read_log():
    try:
        return open(LOG, encoding="utf-8", errors="replace").read()
    except OSError:
        return ""


def write_options(width, height):
    """把窗口尺寸钉死，好在多种窗口高度下重复验证布局。

    面板高度 = min(280, 窗口高 - 20)，所以窗口高度直接决定正文区还剩多少——
    「按键重叠」正是窗口矮的时候才出现的，只测一种尺寸没意义。
    overwriteWidth/Height 必须为 true，否则原版会用上次记住的尺寸。
    """
    path = os.path.join(GAMEDIR, "options.txt")
    keep = []
    if os.path.exists(path):
        for line in open(path, encoding="utf-8", errors="replace"):
            if not line.startswith(("overrideWidth:", "overrideHeight:")):
                keep.append(line)
    keep.append("overrideWidth:%d\n" % width)
    keep.append("overrideHeight:%d\n" % height)
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.writelines(keep)


def main():
    os.makedirs(os.path.join(GAMEDIR, "mods"), exist_ok=True)
    for j in JARS:
        src = os.path.join(MODS_SRC, j)
        if not os.path.exists(src):
            print("!! 缺 jar:", src)
            return 1
        shutil.copy2(src, os.path.join(GAMEDIR, "mods", j))
    print("独立 gameDir:", GAMEDIR)
    print("要打开的页签:", TAB)

    if WINDOW:
        write_options(WINDOW[0], WINDOW[1])
        print("钉死窗口尺寸:", "%dx%d" % WINDOW)

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
    # 关键：让客户端自己把面板开到指定页
    extra = ["-Dnumen.panel=" + TAB]
    # 顺带验证「设置页真的画得下」：客户端里 fits() 拦下来的块会打日志
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
        text = read_log()
        if WANT_OPEN in text and WANT_DRAWN in text:
            verdict, hit = "OK", "面板已打开并绘制"
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

    # 面板打开后还要一两帧才会画出来
    time.sleep(10)
    text = read_log()

    print("=" * 70)
    print("结果：%s  (%s)" % (verdict, hit))
    print("=" * 70)
    print("---- 与面板有关的日志行 ----")
    lines = [l for l in text.splitlines()
             if "[numen-panel]" in l or "[numen] 离线会话" in l]
    for line in lines:
        print(line)
    if not lines:
        print("（没有匹配行）")

    overflow = [l for l in lines if WANT_OVERFLOW in l]
    if overflow:
        print()
        print("!! 有块被判定为「放不下、未绘制」：")
        for l in overflow:
            print("   " + l)

    if proc.poll() is None:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       capture_output=True)
        time.sleep(1)
        print("已关闭验证用客户端")

    if verdict != "OK":
        return 2 if verdict == "TIMEOUT" else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
