#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对 pathing 模块做「批量机械替换」——1.20.1 → 1.16.5 里那一类「方法变字段 / 无参变有参」的改写。

用法：
    python pathing-mech-fix.py            # 预览（只统计，不写）
    python pathing-mech-fix.py --write    # 真的写回

为什么做成脚本：这类改写有二十多处、散布在二十多个文件里，
手工点一遍既慢又容易漏；而它们**每一条都能由 1.16.5 的官方映射证伪**，
所以批量替换是安全的。不能批量的（语义变了、要重写的）一律留手工，见文件末尾的 TODO 列表。
"""
import io
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "pathing", "src", "main", "java", "com", "dwinovo", "numen", "pathing")

# (说明, 旧串, 新串)。旧串默认按**字面串**处理（re.escape），
# 只有标注 REGEX 的那条才当正则用。
# ⚠️ 踩过的坑：第一版把这些当正则直接 compile，于是 `getEyePosition()` 里的
#    `()` 被当成空分组吃掉、原文里的 `()` 却留在原地，产出 `getEyePosition(1.0F)()`
#    这种残渣，一编译就是几十条「需要';'」。字面串一律 re.escape。
REGEX = object()

RULES = [
    ("Entity.getEyePosition() 无参重载是 1.17+；1.16.5 只有 getEyePosition(float)",
     "getEyePosition()", "getEyePosition(1.0F)"),
    ("Entity.yRot 在 1.16.5 是 public 字段（getYRot() 是 1.17+）",
     ".getYRot()", ".yRot"),
    ("Entity.xRot 同理", ".getXRot()", ".xRot"),
    ("Player.inventory 是 public 字段（getInventory() 是 1.17+）",
     ".getInventory()", ".inventory"),
    ("Entity.maxUpStep 是 public 字段（maxUpStep() 是 1.17+）",
     ".maxUpStep()", ".maxUpStep"),
    ("Player.abilities 是 public 字段（getAbilities() 是 1.17+）",
     ".getAbilities()", ".abilities"),
    ("Entity.level 是 public 字段（level() 是 1.17+）。**注意排除 LiveWorld.level()**——"
     "那是本模块自己的方法，名字撞上了",
     r"(?<!world)\.level\(\)", ".level", REGEX),
    ("Entity.onGround 是 protected 字段（onGround() 是 1.17+），1.16.5 用 isOnGround()",
     ".onGround()", ".isOnGround()"),
    ("BlockPos.containing(...) 是 1.17+；1.16.5 直接 new BlockPos(...)",
     "BlockPos.containing(", "new BlockPos("),
]


def main():
    write = "--write" in sys.argv
    total = 0
    for rule in RULES:
        label, old, new = rule[0], rule[1], rule[2]
        as_regex = len(rule) > 3 and rule[3] is REGEX
        pat = re.compile(old if as_regex else re.escape(old))
        hits = []
        for dp, _dn, fn in os.walk(ROOT):
            for f in fn:
                if not f.endswith(".java"):
                    continue
                p = os.path.join(dp, f)
                t = io.open(p, encoding="utf-8").read()
                n = len(pat.findall(t))
                if not n:
                    continue
                hits.append((p, n, t))
        n_all = sum(h[1] for h in hits)
        total += n_all
        print("%3d 处  %-24s -> %-24s   (%s)" % (n_all, old, new, label))
        for p, n, t in hits:
            print("        %2d  %s" % (n, os.path.relpath(p, ROOT)))
            if write:
                io.open(p, "w", encoding="utf-8", newline="").write(pat.sub(new, t))
    print("\n合计 %d 处；%s" % (total, "已写回" if write else "（预览，加 --write 才写）"))


if __name__ == "__main__":
    main()
