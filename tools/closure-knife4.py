#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 4 依赖闭包分析（包粒度）。
种子 = 上游 core/common/task + core/common/tools 的全部包。
沿 import com.dwinovo.numen.* 解析，按包并入（Java 同包免 import，故按包整包并入）。
输出：闭包里的上游包 + 文件数 + 是否已在本仓存在。"""
import os, re, glob, collections

ROOT = r"C:\Users\cfl\workbuddy-ai\我的世界"
UP_ROOTS = [
    os.path.join(ROOT, "ref", "numen-1.20.1", "core", "common", "src", "main", "java"),
    os.path.join(ROOT, "ref", "numen-1.20.1", "api", "common", "src", "main", "java"),
]
PORT = os.path.join(ROOT, "numen-1.16.5", "core", "src", "main", "java")

files = glob.glob(os.path.join(UP_ROOTS[0], "**", "*.java"), recursive=True) + \
        glob.glob(os.path.join(UP_ROOTS[1], "**", "*.java"), recursive=True)

pkg_of = {}
pkgs = collections.defaultdict(list)
for f in files:
    t = open(f, encoding="utf-8", errors="ignore").read()
    m = re.search(r"^package\s+([\w.]+);", t, re.M)
    if not m:
        continue
    p = m.group(1)
    pkg_of[f] = p
    pkgs[p].append(f)

def port_pkg(p):
    if p.startswith("com.dwinovo.numen.core.") or p == "com.dwinovo.numen.core":
        return p
    if p.startswith("com.dwinovo.numen.agent.") or p.startswith("com.dwinovo.numen.pathing."):
        return p
    if p == "com.dwinovo.numen.Constants":
        return "com.dwinovo.numen.core.NumenCore"
    return "com.dwinovo.numen.core." + p[len("com.dwinovo.numen."):]

def imports_of(f):
    t = open(f, encoding="utf-8", errors="ignore").read()
    res = set()
    for m in re.finditer(r"^import\s+(?:static\s+)?(com\.dwinovo\.numen\.[\w.]+?)(?:\.\*)?;", t, re.M):
        res.add(m.group(1))
    return res

SEED = [p for p in pkgs if p.startswith("com.dwinovo.numen.core.task") or p.startswith("com.dwinovo.numen.core.tools")]
seen = set()
queue = collections.deque(SEED)
while queue:
    p = queue.popleft()
    if p in seen: continue
    seen.add(p)
    for f in pkgs.get(p, []):
        for imp in imports_of(f):
            # imp 可能是类 FQN 或包 FQN；包一般在 pkgs 里
            if imp in pkgs:
                if imp not in seen: queue.append(imp)
            else:
                # 类 FQN：剥掉最后一段当包
                parent = imp.rsplit(".", 1)[0]
                if parent in pkgs and parent not in seen:
                    queue.append(parent)

# 已在本仓存在的包判定：port_pkg 目录下是否有 .java
def exists_in_port(p):
    pp = port_pkg(p)
    d = os.path.join(PORT, "com", "dwinovo", "numen", *pp[len("com.dwinovo.numen."):].split("."))
    return os.path.isdir(d) and len(glob.glob(os.path.join(d, "**", "*.java"), recursive=True)) > 0

core_pkgs, api_pkgs, other = {}, {}, {}
for p in sorted(seen):
    n = len(pkgs[p])
    if p.startswith("com.dwinovo.numen.core."):
        core_pkgs[p] = n
    elif p.startswith("com.dwinovo.numen."):
        api_pkgs[p] = n
    else:
        other[p] = n

tot = sum(len(pkgs[p]) for p in seen)
print("=== 闭包：%d 个包 / %d 文件 ===" % (len(seen), tot))
print("\n--- core/common 侧 ---")
for p, n in core_pkgs.items():
    print("  %-52s %2d files  %s" % (p, n, "已在本仓" if exists_in_port(p) else "★需搬"))
print("\n--- api/common 侧（含 task 框架） ---")
for p, n in api_pkgs.items():
    print("  %-52s %2d files  %s" % (p, n, "已在本仓" if exists_in_port(p) else "★需搬"))
if other:
    print("\n--- 其他 ---")
    for p, n in other.items():
        print("  %-52s %2d files" % (p, n))
need = [p for p in seen if not exists_in_port(p)]
print("\n=== 需要搬的包 %d 个，共 %d 文件 ===" % (len(need), sum(len(pkgs[p]) for p in need)))
