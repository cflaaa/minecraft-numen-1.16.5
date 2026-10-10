#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手工启动 PCL 的 1.16.5 Fabric 实例，用于「改完 → 拷 jar → 启动 → 看日志」的迭代。

用法：
    python launch-mc.py [超时秒数]

退出码：
    0 = 成功进到主界面（日志里出现 'Sound engine started'）
    1 = 崩溃 / 启动失败
    2 = 超时（进程还活着但没走到主界面）
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
OUT = os.path.join(VDIR, "launch-stdout.log")

TIMEOUT = int(sys.argv[1]) if len(sys.argv) > 1 else 240

# 日志里出现这些就算「已经进到主界面」
SUCCESS = ["Sound engine started"]
# 出现这些就是失败，不用等进程退出
FATAL = [
    "Incompatible mods found",
    "A fatal error has been detected",
    "Failed to start Minecraft",
    "Exception in thread \"main\"",
    "Could not execute entrypoint",
    "java.lang.NoClassDefFoundError",
    "java.lang.NoSuchMethodError",
    "Mixin apply failed",
    "---- Minecraft Crash Report ----",
]


def rules_match(rules):
    """按 Mojang 的 rules 语义判断这条库/参数在当前平台是否生效。"""
    if not rules:
        return True
    allowed = False
    for r in rules:
        os_ = r.get("os", {}) or {}
        ok = True
        if "name" in os_ and os_["name"] != "windows":
            ok = False
        if "arch" in os_ and os_["arch"] != "x86_64":
            ok = False          # 本机是 x86_64，json 里的 "x86" 规则不适用
        if "version" in os_ and not re.match(os_["version"], "10.0"):
            ok = False
        if "features" in r:
            ok = False          # demo / 自定义分辨率都不开
        if ok:
            allowed = r.get("action") == "allow"
    return allowed


def flatten(values):
    """json 里的 arguments 是字符串与数组混排的。"""
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
    """`group:artifact:version` → libraries 下的相对路径。

    PCL 生成的 version json 里，Fabric 那几个库（fabric-loader / sponge-mixin /
    intermediary）和 asm 只有 Maven 坐标、没有 downloads.artifact，
    要让启动器自己按路径约定拼出来。
    """
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

    cmd = ([JAVA, "-Xmx2G", "-XX:+UseG1GC", "-Dfile.encoding=UTF-8"]
           + jvm + [d["mainClass"]] + game)

    missing = [p for p in cp if not os.path.exists(p)]
    if missing:
        print("!! classpath 缺失 %d 个文件，前几个：" % len(missing))
        for m in missing[:5]:
            print("   ", m)

    print("启动中 … 日志：%s" % LOG)
    print("命令长度：%d 个参数" % len(cmd))

    # 把上一轮的 latest.log 挪开，保证后面读到的每一行都是本次启动产生的
    # （否则可能读到上一轮残留的 "Sound engine started" 而误判成功）。
    if os.path.exists(LOG):
        os.replace(LOG, LOG + ".prev")

    with open(OUT, "wb") as fout:
        proc = subprocess.Popen(cmd, cwd=VDIR, stdout=fout,
                                stderr=subprocess.STDOUT)

    deadline = time.time() + TIMEOUT
    verdict, hit = None, None
    while time.time() < deadline:
        time.sleep(1.0)
        try:
            text = open(LOG, encoding="utf-8", errors="replace").read()
        except OSError:
            text = ""
        for m in SUCCESS:
            if m in text:
                verdict, hit = "OK", m
                break
        if verdict:
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

    print("=" * 60)
    print("结果：%s  (%s)" % (verdict, hit))
    print("=" * 60)

    # 关掉游戏（连子进程一起）
    if proc.poll() is None:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       capture_output=True)
        time.sleep(1)
        print("已关闭游戏进程")

    # 打印日志尾部，方便直接看
    try:
        lines = open(LOG, encoding="utf-8", errors="replace").read().splitlines()
        print("---- latest.log 末 45 行 ----")
        for l in lines[-45:]:
            print(l)
    except OSError:
        pass

    return 0 if verdict == "OK" else (2 if verdict == "TIMEOUT" else 1)


if __name__ == "__main__":
    sys.exit(main())
