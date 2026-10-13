#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""列出上游有、本仓缺的具体文件（按包），以及本仓独有（上游没有）的文件。"""
import os
from collections import defaultdict

ROOT = r"C:/Users/cfl/workbuddy-ai/我的世界"
UP = os.path.join(ROOT, "ref/numen-1.20.1")
DOWN = os.path.join(ROOT, "numen-1.16.5")

UP_ROOTS = [
    os.path.join(UP, "core/common/src/main/java/com/dwinovo/numen/core"),
    os.path.join(UP, "api/common/src/main/java/com/dwinovo/numen"),
]
DOWN_ROOT = os.path.join(DOWN, "core/src/main/java/com/dwinovo/numen/core")


def files_by_pkg(root):
    out = defaultdict(set)
    if not os.path.isdir(root):
        return out
    for dirpath, _d, files in os.walk(root):
        for f in files:
            if f.endswith(".java"):
                rel = os.path.relpath(os.path.join(dirpath, f), root).replace("\\", "/")
                seg = rel.split("/")
                pkg = seg[0] if len(seg) > 1 else "(顶层)"
                out[pkg].add(rel[len(pkg) + 1:] if len(seg) > 1 else rel)
    return out


up = defaultdict(set)
for r in UP_ROOTS:
    for pkg, fs in files_by_pkg(r).items():
        up[pkg] |= fs
down = files_by_pkg(DOWN_ROOT)

print("=" * 74)
print("① 上游有、本仓缺（按包；括号里是上游行数）")
print("=" * 74)
total_missing = 0
for pkg in sorted(up):
    miss = sorted(up[pkg] - down.get(pkg, set()))
    if not miss:
        continue
    print("\n[%s]  缺 %d / %d" % (pkg, len(miss), len(up[pkg])))
    for m in miss:
        p = os.path.join(UP_ROOTS[0] if pkg in files_by_pkg(UP_ROOTS[0]) else UP_ROOTS[1], pkg, m)
        n = 0
        for r in UP_ROOTS:
            q = os.path.join(r, pkg, m)
            if os.path.isfile(q):
                n = sum(1 for _ in open(q, encoding="utf-8", errors="ignore"))
                break
        print("    %-52s %4d 行" % (m, n))
        total_missing += 1
print("\n上游缺件合计：%d 个文件" % total_missing)

print()
print("=" * 74)
print("② 本仓独有（上游没有的包/文件）")
print("=" * 74)
for pkg in sorted(down):
    extra = sorted(down[pkg] - up.get(pkg, set()))
    if not extra:
        continue
    print("\n[%s]  本仓独有 %d 个" % (pkg, len(extra)))
    for e in extra:
        print("    " + e)
