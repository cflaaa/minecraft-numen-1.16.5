#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审计仓库：有没有「对 git 不可见的重要文件」。

背景（2026-10-13 实战事故）：`.gitignore` 的 `**/build/` 把源码里**真叫 build 的包**
（com.dwinovo.numen.core.build、core.task.build，21 个文件）连坐忽略了 —— 这些文件
**从未进过版本库**，工作树一丢就彻底找不回（最后靠反编译已部署的 jar 才救回来）。

这个脚本回答一句话：**磁盘上有没有「看着像源码/脚本/文档、但 git 根本没在管」的文件？**

用法：python tools/git-audit.py        # 有 SUSPECT 时退出码非 0（可挂进流程）
"""
import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

# 已知、**有意**忽略的重目录 / 产物（不算问题）
HEAVY = (".toolchain/", ".testserver/", "ref/", ".git/")
# 各模块的 gradle 输出目录（**精确列出**——不能用 `**/build/`，源码里就有同名包）
GRADLE_OUT = ("numen-1.16.5/build/",) + tuple(
    "numen-1.16.5/%s/build/" % m for m in ("core", "pathing", "agent", "ai", "lua"))
# 有意忽略的文件（含明文密钥，故意不入库）
ALLOW = ("ssh-run.py",)

SRC_EXT = (".java", ".py", ".sh", ".lua", ".md", ".json", ".gradle", ".yml", ".yaml",
           ".properties", ".toml", ".kt", ".groovy")


def git(*args):
    # -c core.quotePath=false：非 ASCII 文件名原样输出。
    # 否则 git 会把「accept-刀4.py」打成一堆 \345 转义，中文文件名就认不出来了（实测踩到）。
    return subprocess.run(["git", "-c", "core.quotePath=false"] + list(args), cwd=ROOT,
                          capture_output=True, text=True).stdout


def classify(path):
    if path.endswith(".class"):
        return "ok"
    if path.startswith(HEAVY):
        return "ok"
    if path.startswith(ALLOW):
        return "ok"
    if "/.gradle/" in path or path.startswith(".gradle/"):
        return "ok"
    if path.startswith("logs/") or "/logs/" in path:
        return "ok"
    if path.startswith(GRADLE_OUT):
        return "ok"
    if path.startswith(".workbuddy-ai/"):
        return "advisory"      # 工具脚本躺在一个被忽略的目录里
    return "suspect"


def main():
    ignored = [p for p in git("ls-files", "--others", "--ignored",
                              "--exclude-standard").splitlines() if p]
    untracked = [p for p in git("ls-files", "--others",
                                "--exclude-standard").splitlines() if p]

    suspects = [p for p in ignored if classify(p) == "suspect"]
    # advisory 只报 .workbuddy-ai/tmp 下的**顶层脚本**（scratch 子目录太吵）
    advis = [p for p in ignored if classify(p) == "advisory"
             and p.count("/") == 2 and p.endswith(".py")]
    src_suspects = [p for p in suspects if "/src/" in p]

    quiet = "--quiet" in sys.argv[1:]
    if not quiet:
        print("=" * 66)
        print("仓库可见性审计：%s" % ROOT)
        print("=" * 66)
        print("被忽略文件总数：%d（其中 %.0f%% 是 .class / 构建产物）"
              % (len(ignored), 100.0 * len([p for p in ignored if p.endswith('.class')])
                 / max(1, len(ignored))))

    if suspects:
        print("\n[!] SUSPECT —— 被忽略、但看着像该入库的文件（%d 个）：" % len(suspects))
        for p in suspects[:40]:
            print("    " + p)
        if len(suspects) > 40:
            print("    … 另有 %d 个" % (len(suspects) - 40))
        if src_suspects:
            print("\n[!!!] 其中 **在 src/ 树里**（最高危，源码可能从未入库）：%d 个"
                  % len(src_suspects))
            for p in src_suspects[:20]:
                print("    " + p)
    else:
        print("\n[OK] 没有「该入库却被忽略」的文件。")

    if advis and not quiet:
        print("\n[~] 提醒：这些**可复用工具**躺在被忽略的目录里（%d 个）：" % len(advis))
        for p in advis[:20]:
            print("    " + p)
        print("    → 快照会覆盖它们；若要长期留存，考虑挪进 tools/ 入库。")
    elif advis:
        print("[~] %d 个可复用工具在被忽略目录（快照已覆盖）" % len(advis))

    if untracked and not quiet:
        print("\n[?] 未跟踪（没被忽略，只是还没提交）的新文件：%d 个" % len(untracked))
        for p in untracked[:15]:
            print("    " + p)

    print("\n结论：%s" % ("有 SUSPECT，请处理 ❌" if suspects else "干净 ✅"))
    return 1 if suspects else 0


if __name__ == "__main__":
    sys.exit(main())
