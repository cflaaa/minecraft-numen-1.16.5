#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比上游（1.20.1）与本仓（1.16.5）的移植进度：按包统计文件数与行数。"""
import io
import os
from collections import defaultdict

ROOT = r"C:/Users/cfl/workbuddy-ai/我的世界"
UP = os.path.join(ROOT, "ref/numen-1.20.1")
DOWN = os.path.join(ROOT, "numen-1.16.5")

# 上游两个「服务端共用」源码根
UP_ROOTS = [
    ("core", os.path.join(UP, "core/common/src/main/java/com/dwinovo/numen/core")),
    ("api",  os.path.join(UP, "api/common/src/main/java/com/dwinovo/numen")),
]
# 本仓（api/common 与 core/common 都并进了 core）
DOWN_ROOT = os.path.join(DOWN, "core/src/main/java/com/dwinovo/numen/core")
# 本仓独立模块
MODULES = ["pathing", "agent", "ai", "lua"]


def walk(root, prefix_strip):
    """→ {pkg: (files, lines)}；pkg 是去掉 prefix_strip 后的第一段（或 '(顶层)'）。"""
    out = defaultdict(lambda: [0, 0])
    if not os.path.isdir(root):
        return out
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, f), root).replace("\\", "/")
            seg = rel.split("/")
            pkg = seg[0] if len(seg) > 1 else "(顶层)"
            out[pkg][0] += 1
            try:
                out[pkg][1] += sum(1 for _ in io.open(os.path.join(dirpath, f),
                                                      encoding="utf-8", errors="ignore"))
            except OSError:
                pass
    return out


def up_core_pkgs():
    """上游 core/common + api/common 合并后的 {pkg: [files, lines]}。"""
    merged = defaultdict(lambda: [0, 0])
    for _tag, root in UP_ROOTS:
        for pkg, (n, ln) in walk(root, root).items():
            merged[pkg][0] += n
            merged[pkg][1] += ln
    return merged


def main():
    up = up_core_pkgs()
    down = walk(DOWN_ROOT, DOWN_ROOT)

    print("=" * 74)
    print("按包对照（上游 core/common + api/common  ↔  本仓 core）")
    print("=" * 74)
    print("%-22s %14s %14s   %s" % ("包", "上游 文件/行", "本仓 文件/行", "覆盖"))
    print("-" * 74)

    tot_u = [0, 0]
    tot_d = [0, 0]
    rows = []
    for pkg in sorted(set(up) | set(down)):
        if pkg == "(顶层)":
            continue
        u = up.get(pkg, [0, 0])
        d = down.get(pkg, [0, 0])
        cov = 100.0 * d[0] / u[0] if u[0] else (100.0 if d[0] == 0 else float("inf"))
        rows.append((pkg, u, d, cov))
        tot_u[0] += u[0]; tot_u[1] += u[1]
        tot_d[0] += d[0]; tot_d[1] += d[1]

    # 按缺口大小排：缺得多的在前
    rows.sort(key=lambda r: (r[1][0] - r[2][0]), reverse=True)
    for pkg, u, d, cov in rows:
        mark = "✅" if u[0] and d[0] >= u[0] else ("⬜" if d[0] == 0 else "🟡")
        if not u[0]:
            mark = "➕"
            covs = "本仓独有"
        else:
            covs = "%.0f%%" % cov
        print("%-22s %6d/%-6d %6d/%-6d   %s %s" % (pkg, u[0], u[1], d[0], d[1], mark, covs))
    print("-" * 74)
    print("%-22s %6d/%-6d %6d/%-6d   %.0f%%" % ("合计", tot_u[0], tot_u[1], tot_d[0], tot_d[1],
                                              100.0 * tot_d[0] / max(1, tot_u[0])))

    print()
    print("=" * 74)
    print("独立模块对照")
    print("=" * 74)
    for m in MODULES:
        u = walk(os.path.join(UP, m, "src/main/java"), None)
        d = walk(os.path.join(DOWN, m, "src/main/java"), None)
        un = sum(v[0] for v in u.values()); ul = sum(v[1] for v in u.values())
        dn = sum(v[0] for v in d.values()); dl = sum(v[1] for v in d.values())
        print("%-10s 上游 %3d 文件 / %6d 行    本仓 %3d 文件 / %6d 行    %.0f%%"
              % (m, un, ul, dn, dl, 100.0 * dn / max(1, un)))
    # 上游有、本仓整个模块没有的
    for m in ["ui", "plugins"]:
        u = walk(os.path.join(UP, m, "src/main/java"), None)
        un = sum(v[0] for v in u.values()); ul = sum(v[1] for v in u.values())
        print("%-10s 上游 %3d 文件 / %6d 行    本仓   0 文件          0%%   ← 整模块缺口"
              % (m, un, ul))


if __name__ == "__main__":
    main()
