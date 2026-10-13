#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""环境B 专项：把刀 4 的新 jar（968 类）部署到 NAS 容器并重启验证。

复用 deploy-mc.py 的连接参数；不重新 pull / 不动 compose，只换 jar + restart。
成功判据：Done 行 + Numen 加载行 + 无 numen 相关 ERROR/ClassNotFound。
"""
import os
import sys
import time

import paramiko

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nas_creds  # noqa: E402

HOST, USER, PWD = nas_creds.host_user_pwd()
RDIR = "/vol1/1000/docker/mc1165-test"
SUDO = nas_creds.sudo_prefix()
LOCAL_JAR = (r"C:/Users/cfl/workbuddy-ai/我的世界/numen-1.16.5/core/build/libs/"
             r"Numen-fabric-1.16.5-0.1.4-1.16.5.jar")
REMOTE_JAR = f"{RDIR}/data/mods/Numen-fabric-1.16.5-0.1.4-1.16.5.jar"


def run(ssh, cmd, timeout=120, show=False):
    _, out, err = ssh.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    rc = out.channel.recv_exit_status()
    if show:
        print(f"$ {cmd}\n[rc={rc}]\n{o[-2000:]}")
        if e.strip():
            print("[stderr]", e[-800:])
    return rc, o, e


def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=10)

    print("== 1. 上传新 jar（968 类）==")
    sftp = ssh.open_sftp()
    sftp.put(LOCAL_JAR, REMOTE_JAR)
    sftp.close()
    rc, o, _ = run(ssh, f"ls -la {REMOTE_JAR}")
    print(o.strip())

    print("== 2. 重启容器 ==")
    run(ssh, f"cd {RDIR} && {SUDO} docker compose restart 2>&1 | grep -v password",
        timeout=180, show=True)

    print("== 3. 等 Done ==")
    done = False
    for i in range(18):
        time.sleep(10)
        _, o, _ = run(ssh, f"{SUDO} docker logs --since 2m mc1165-test 2>&1 | grep -v password")
        tail = o.strip().splitlines()
        print(f"[{i*10}s]", " | ".join(tail[-2:]) if tail else "(no logs)")
        if "Done (" in o:
            done = True
            break
        if any(x in o for x in ("ERROR", "Failed to start", "Exception in thread")):
            print("!! 启动期疑似报错，继续观察一轮")
    if not done:
        print("❌ 180s 内没等到 Done")
        return 1
    print("✅ Done")

    print("== 4. 验证 Numen 加载与报错扫描 ==")
    _, log, _ = run(ssh, f"{SUDO} docker logs --since 5m mc1165-test 2>&1 | grep -v password")
    ok_loaded = "Numen 1.16.5 移植版已加载" in log
    print("   模组加载行：", "✅" if ok_loaded else "❌（找一下变体）")
    if not ok_loaded:
        for l in log.splitlines():
            if "numen" in l.lower() and ("load" in l.lower() or "init" in l.lower()):
                print("      " + l)
    bad = [l for l in log.splitlines()
           if ("numen" in l.lower())
           and any(k in l for k in ("ERROR", "ClassNotFound", "NoClassDefFound",
                                    "Missing registry", "IllegalStateException",
                                    "Mixing", "mixin apply failed"))]
    print("   numen 相关异常行：", "✅ 无" if not bad else f"❌ {len(bad)} 行")
    for l in bad[:15]:
        print("      " + l)
    mixin_probe = [l for l in log.splitlines() if "mixin" in l.lower() and "numen" in l.lower()]
    if mixin_probe:
        print("   mixin 相关：")
        for l in mixin_probe[:5]:
            print("      " + l)
    ssh.close()
    print("\n小结：部署%s" % ("✅ 通过" if (ok_loaded and not bad) else "❌ 有问题，见上"))
    return 0 if (ok_loaded and not bad) else 2


if __name__ == "__main__":
    sys.exit(main())
