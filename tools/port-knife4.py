#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 4：把上游 13 任务族 + 工具本体 + 其支撑包搬进本仓 core/。

闭包（closure-knife4.py 实测）：core/common 的 act/blueprint/build/combat/init/route/
scan/nav/mixin/task/tools + api/common/area。

规则：
  * core/common 的件包名已是 com.dwinovo.numen.core.*（本仓同包）→ 只改跨包 import。
  * api/common 的件（area）包名 com.dwinovo.numen.<x> → com.dwinovo.numen.core.<x>。
  * agent. / pathing. 命名空间保留（独立模块）。
  * Constants → core.NumenCore（Constants.LOG → NumenCore.LOG）。
  * **dest 已存在则跳过**（绝不覆盖本仓已按 1.16.5 改好的文件）。

用法：python port-knife4.py           # dry-run，只打印
      python port-knife4.py --apply   # 真搬
"""
import os, re, sys

ROOT = r"C:\Users\cfl\workbuddy-ai\我的世界"
UP = os.path.join(ROOT, "ref", "numen-1.20.1")
UP_NUMEN_CORE = os.path.join(UP, "core", "common", "src", "main", "java", "com", "dwinovo", "numen")
UP_NUMEN_API  = os.path.join(UP, "api", "common", "src", "main", "java", "com", "dwinovo", "numen")
DOWN_NUMEN = os.path.join(ROOT, "numen-1.16.5", "core", "src", "main", "java", "com", "dwinovo", "numen")

# (源根, 相对该根的目录, 目标相对 numen/ 的前缀)
JOBS = [
    (UP_NUMEN_CORE, "core/act", "core/act"),
    (UP_NUMEN_CORE, "core/blueprint", "core/blueprint"),
    (UP_NUMEN_CORE, "core/build", "core/build"),
    (UP_NUMEN_CORE, "core/combat", "core/combat"),
    (UP_NUMEN_CORE, "core/init", "core/init"),
    (UP_NUMEN_CORE, "core/route", "core/route"),
    (UP_NUMEN_CORE, "core/scan", "core/scan"),
    (UP_NUMEN_CORE, "core/nav", "core/nav"),
    (UP_NUMEN_CORE, "core/mixin", "core/mixin"),
    (UP_NUMEN_CORE, "core/task", "core/task"),
    (UP_NUMEN_CORE, "core/tools", "core/tools"),
    (UP_NUMEN_API,  "area", "core/area"),
]

KEEP = ("com.dwinovo.numen.agent.", "com.dwinovo.numen.pathing.", "com.dwinovo.numen.core.")

# 本仓已有自己的掉落/触碰追踪 mixin（permission 层那套）；上游这 6 个事件 mixin 不搬。
# 只搬闭包里真正被引用的 3 个 @Accessor/@Invoker（FishCompanionTask/CraftOps/ScanApi 要）。
SKIP = {
    "core/mixin/ItemEntityDropsMixin.java",
    "core/mixin/LivingEntityTakeMixin.java",
    "core/mixin/ServerLevelBlockChangeMixin.java",
    "core/mixin/ServerLevelDropsMixin.java",
    "core/mixin/ServerPlayerGameModeAccessor.java",
    "core/mixin/ServerPlayerGameModeDropsMixin.java",
}

def rewrite(text):
    def pkg(m):
        p = m.group(1)
        if p.startswith(KEEP) or p == "com.dwinovo.numen.core" or p == "com.dwinovo.numen.Constants":
            return "package %s;" % p
        return "package com.dwinovo.numen.core.%s;" % p[len("com.dwinovo.numen."):]
    text = re.sub(r"^package\s+([\w.]+);", pkg, text, flags=re.M)
    text = text.replace("import com.dwinovo.numen.Constants;", "import com.dwinovo.numen.core.NumenCore;")
    text = text.replace("com.dwinovo.numen.Constants.LOG", "NumenCore.LOG")
    text = re.sub(r"(?<![\w.])Constants\.LOG", "NumenCore.LOG", text)
    def fix(m):
        whole = m.group(0)
        if whole.startswith(KEEP):
            return whole
        return "com.dwinovo.numen.core." + whole[len("com.dwinovo.numen."):]
    text = re.sub(r"com\.dwinovo\.numen\.(?!pathing\.|core\.)[a-z][\w.]*", fix, text)
    return text

def main():
    apply = "--apply" in sys.argv
    copied, skipped = [], []
    for base, sub, dstsub in JOBS:
        src_dir = os.path.join(base, sub.replace("/", os.sep))
        if not os.path.isdir(src_dir):
            print("!! 缺上游目录 %s" % src_dir); continue
        for root, _d, files in os.walk(src_dir):
            for name in sorted(files):
                if not name.endswith(".java"):
                    continue
                src = os.path.join(root, name)
                rel = os.path.relpath(src, base).replace("\\", "/")   # 如 core/task/move/X.java
                # 目标：把 rel 的 'core/xxx' 或 'area' 落到 DOWN_NUMEN/<dstsub 的父> 下
                # 统一：目标相对 numen/ 的路径 = dstsub + (rel 去掉 sub 前缀)
                tail = rel[len(sub):].lstrip("/")
                dst_rel = (dstsub + "/" + tail) if tail else dstsub
                if dst_rel in SKIP:
                    skipped.append((dst_rel, "上游事件 mixin（本仓自有）")); continue
                dst = os.path.join(DOWN_NUMEN, *dst_rel.split("/"))
                if os.path.exists(dst):
                    skipped.append((dst_rel, "本仓已有")); continue
                if apply:
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(src, encoding="utf-8") as f: t = f.read()
                    with open(dst, "w", encoding="utf-8", newline="\n") as f: f.write(rewrite(t))
                copied.append(dst_rel)
    print("=== %s：%d 个 ===" % ("已搬" if apply else "待搬(dry-run)", len(copied)))
    for r in copied: print("   + %s" % r)
    print("=== 跳过（本仓已有）%d 个 ===" % len(skipped))
    for r, w in skipped: print("   - %-46s %s" % (r, w))
    return 0

if __name__ == "__main__":
    sys.exit(main())
