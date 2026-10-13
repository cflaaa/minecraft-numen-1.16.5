#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把反编译源码里残留的 intermediary 名（class_/method_/field_）换成 named 名。"""
import io
import os
import re
import sys

MAP = r"C:/Users/cfl/.gradle/caches/fabric-loom/1.16.5/loom.mappings.1_16_5.layered+hash.2198-v2/mappings.tiny"
DST = r"C:/Users/cfl/workbuddy-ai/我的世界/numen-1.16.5/core/src/main/java/com/dwinovo/numen/core"

cls = {}    # class_1234 -> net/minecraft/xxx/Yyy
mem = {}    # method_1234 / field_1234 -> namedSimple

with io.open(MAP, encoding="utf-8") as f:
    for line in f:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 4 and parts[0] == "c":
            inter, named = parts[2], parts[3]
            if inter.startswith("net/minecraft/class_"):
                cls[inter.split("/")[-1]] = named
        elif len(parts) >= 6 and parts[0] == "" and parts[1] in ("m", "f"):
            inter, named = parts[4], parts[5]
            if inter.startswith("method_") or inter.startswith("field_"):
                mem[inter] = named

print("class 映射 %d 条，成员映射 %d 条" % (len(cls), len(mem)))

cls_fqn = {("net.minecraft." + k): v.replace("/", ".") for k, v in cls.items()}
cls_simple = {k: v.split("/")[-1] for k, v in cls.items()}

# 长的先替换，避免前缀误伤
fqn_keys = sorted(cls_fqn, key=len, reverse=True)
simple_keys = sorted(cls_simple, key=len, reverse=True)
mem_keys = sorted(mem, key=len, reverse=True)

changed = 0
for sub in ["build", os.path.join("task", "build")]:
    d = os.path.join(DST, sub)
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".java"):
            continue
        p = os.path.join(d, fn)
        t = io.open(p, encoding="utf-8").read()
        o = t
        for k in fqn_keys:
            t = t.replace(k, cls_fqn[k])
        for k in simple_keys:
            t = t.replace(k, cls_simple[k])
        for k in mem_keys:
            t = t.replace(k, mem[k])
        if t != o:
            io.open(p, "w", encoding="utf-8", newline="").write(t)
            changed += 1
print("改写 %d 个文件" % changed)

# 复查还有没有残留
left = 0
for sub in ["build", os.path.join("task", "build")]:
    d = os.path.join(DST, sub)
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".java"):
            continue
        t = io.open(os.path.join(d, fn), encoding="utf-8").read()
        n = len(re.findall(r"\b(class_|method_|field_)\d+", t))
        if n:
            print("  残留 %d 处：%s" % (n, fn))
            left += n
print("总残留 %d 处" % left)
