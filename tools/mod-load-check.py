#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只改了一处元数据（fabric.mod.json 的 description），但要真机确认两件事：

1. **jar 里带的确实是新描述**（直接从发行 jar 里把 fabric.mod.json 抠出来读）；
2. **服务端还能起来**——fabric.mod.json 是加载期解析的，写坏了模组直接不加载，
   而这种事在编译期完全看不出来。

用法：python mod-load-check.py
"""
import json
import os
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402

JAR = r"C:\Users\cfl\workbuddy-ai\我的世界\numen-1.16.5\core\build\libs\Numen-fabric-1.16.5-0.1.4-1.16.5.jar"


def jar_description():
    with zipfile.ZipFile(JAR) as z:
        with z.open("fabric.mod.json") as f:
            return json.loads(f.read().decode("utf-8"))


def main():
    print("=" * 68, flush=True)
    print("第 1 步：从发行 jar 里读出 fabric.mod.json", flush=True)
    print("=" * 68, flush=True)
    mod = jar_description()
    print("   id      = %s" % mod["id"])
    print("   version = %s" % mod["version"])
    print("   env     = %s" % mod["environment"])
    print("   desc    = %s" % mod["description"])
    bad = "your own machine" in mod["description"] or "your own API key" in mod["description"]
    print("   → 旧说法还在吗：%s（期望「不在」）" % ("在 ❌" if bad else "不在 ✅"))
    print("   → 提到 key 在服务端了吗：%s"
          % ("是 ✅" if "config/numen.json" in mod["description"] else "否 ❌"))
    print("   → 提到外接大脑 / MCP 了吗：%s"
          % ("是 ✅" if "MCP" in mod["description"] else "否 ❌"))

    print("\n" + "=" * 68, flush=True)
    print("第 2 步：真机起一次专用服务端，确认模组还能加载", flush=True)
    print("=" * 68, flush=True)
    d, libs = P.libraries()
    P.start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来（模组可能没加载）")
        tail = P.read(P.SERVER_LOG).splitlines()[-40:]
        for l in tail:
            print("   " + l)
        P.kill_all()
        return 1
    print(">> 服务端就绪")

    log = P.read(P.SERVER_LOG)
    loaded = "Numen 1.16.5 移植版已加载" in log
    meta_err = [l for l in log.splitlines()
                if "fabric.mod.json" in l or "mod.json" in l or "Metadata" in l
                or "Invalid" in l and "numen" in l.lower()]
    print("   模组加载行：%s" % ("✅ 有" if loaded else "❌ 没有"))
    print("   元数据报错：%s" % ("✅ 没有" if not meta_err else "❌ 有"))
    for l in meta_err:
        print("      " + l)
    for l in log.splitlines():
        if "[numen]" in l and ("加载" in l or "配置" in l):
            print("   " + l)

    print("\n" + "=" * 68, flush=True)
    print("第 3 步：关服", flush=True)
    print("=" * 68, flush=True)
    P.cmd("/stop")
    t0 = time.time()
    try:
        P.server.wait(timeout=60)
        print(">> 服务端 %.1fs 后退出 code=%s" % (time.time() - t0, P.server.returncode))
    except subprocess.TimeoutExpired:
        print(">> !! 60s 未退出")

    P.kill_all()
    ok = loaded and not meta_err and not bad
    print("\n小结：%s" % ("✅ 元数据与加载都正常" if ok else "❌ 有问题，见上"))
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
