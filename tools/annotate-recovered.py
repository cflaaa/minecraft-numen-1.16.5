#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给反编译恢复的 21 个文件补注释（只加注释，绝不动逻辑）。

来源取上游 1.20.1 同名文件的**类 javadoc**（原注释在事故中丢失），外加一段出处说明。
"""
import io
import os
import re
import sys

ROOT = r"C:/Users/cfl/workbuddy-ai/我的世界"
UP = os.path.join(ROOT, "ref/numen-1.20.1/core/common/src/main/java/com/dwinovo/numen/core")
DOWN = os.path.join(ROOT, "numen-1.16.5/core/src/main/java/com/dwinovo/numen/core")
PKGS = ["build", os.path.join("task", "build")]

PROVENANCE = """/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的 `**/build/` 连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

"""


def upstream_javadoc(pkg, name):
    p = os.path.join(UP, pkg, name)
    if not os.path.isfile(p):
        return None
    t = io.open(p, encoding="utf-8", errors="ignore").read()
    m = re.search(r"/\*\*.*?\*/", t, re.S)
    return m.group(0) if m else None


def declaration_index(lines):
    """第一个「类/接口/枚举/记录」声明行的下标（列 0 开头）。"""
    for i, ln in enumerate(lines):
        # 不要求行尾是 {：反编译产物常把声明拆成两行（public final class X / extends Y {）
        if re.match(r"^(public\s+)?(final\s+|abstract\s+|static\s+)*(class|interface|enum|record)\s+\w", ln):
            return i
    return None


def main():
    done = skipped = 0
    for pkg in PKGS:
        d = os.path.join(DOWN, pkg)
        for name in sorted(os.listdir(d)):
            if not name.endswith(".java"):
                continue
            p = os.path.join(d, name)
            t = io.open(p, encoding="utf-8").read()
            if "反编译恢复" in t:
                skipped += 1
                continue
            doc = upstream_javadoc(pkg, name)
            if doc is None:
                print("!! 上游没有 %s/%s" % (pkg, name))
                skipped += 1
                continue
            lines = t.split("\n")
            idx = declaration_index(lines)
            if idx is None:
                print("!! 找不到类声明：%s/%s" % (pkg, name))
                skipped += 1
                continue
            head = PROVENANCE + doc + "\n\n"
            lines[idx:idx] = head.split("\n")[:-1]
            io.open(p, "w", encoding="utf-8", newline="").write("\n".join(lines))
            print("ok  %s/%s  (+%d 行注释)" % (pkg, name, head.count("\n")))
            done += 1
    print("\n注释 %d 个，跳过 %d 个" % (done, skipped))
    return 0 if skipped == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
