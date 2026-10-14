#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""找出「编译得过、但在专用服务端上会炸」的 Minecraft API 引用。

为什么需要这个：
  Fabric Loom 给模组编译用的类路径是 **合并的客户端+服务端 jar**
  （minecraft-merged-intermediary.jar）。它里面有一些**只在客户端存在**的成员
  （最典型的：Item.getDescription() / Item.appendHoverText()）。
  于是「代码在服务端跑」这种用法**编译期完全看不出来**，一跑到就
  NoSuchMethodError / NoSuchFieldError —— 而且只在专用服务端上复现，单机没事。

  实测（2026-10-09）：NumenTools.status 里 `item.getDescription()` 就是这么炸的。

做法：
  1. 用 javap -c 把模组 jar 里每条 `// Method/Field net/minecraft/...` 引用抠出来；
     ⚠️ **构造器也要抠**：调用长这样 `// Method net/minecraft/class_2793."<init>":(I)V`，
     成员名带引号；早先正则的字符类没含 `"`，于是**所有构造器调用被整条跳过**（曾因此漏掉
     `new ServerboundAcceptTeleportationPacket(int)`，真机一传唤就 NoSuchMethodError）。已修。
  2. 从**运行时**那份 server-intermediary.jar 建一张「这个类（含其父类/接口链）
     到底有哪些成员」的表；
  3. 对不上的报出来，并**打印每条引用的来源类**（诊断"谁在调"、判可达性用）。

**客户端包例外**（2026-10-09 加）：
  `com.dwinovo.numen.core.client.*` 是**只该在客户端加载**的一层（由 fabric.mod.json 的
  client 入口点负责），它引用 `net.minecraft.client.*` 是本职，不该算失败。
  所以：
    - `core.client.*` 里的类，引用不参与「服务端有没有」的核对；
    - 但**反向要查得更严**：任何**非** client 包的类只要碰了 `net.minecraft.client.*`，
      就是真问题（专用服务端上会 NoClassDefFoundError），一律报错。

用法：python server-api-check.py [mod.jar] [server-intermediary.jar]
"""
import os
import re
import subprocess
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
JAVAP = os.path.join(HERE, ".toolchain", "jdk-17.0.20.1+1", "bin", "javap.exe")

# 只该在客户端加载的包前缀。这里的类可以随便引用 net.minecraft.client.*。
CLIENT_PKG = "com.dwinovo.numen.core.client."

# 要核对的包前缀。**pathing 也必须在内**：它不在 core 源码树里，但由 core/build.gradle
# 平铺进发行 jar（`from project(':pathing').sourceSets.main.output`），运行时是 core 的一部分，
# 一样只在专用服务端上炸。2026-10-10 实测踩到：pathing/body/Controls 调了
# `ServerPlayer.isVisuallyCrawling()`，合并 jar 里有、server-intermediary.jar 里没有，
# 一召唤同伴就 NoSuchMethodError 把服务端打崩——而当时这个脚本只扫 core/，没拦住。
PACKAGES = ("com/dwinovo/numen/core/", "com/dwinovo/numen/pathing/")

MOD_JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    HERE, ".testserver", "mods", "numen-0.1.4-1.16.5.jar")
SERVER_JAR = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    HERE, ".testserver", ".fabric", "remappedJars",
    "minecraft-1.16.5-0.19.5", "server-intermediary.jar")

# javap -c 里的引用长这样：
#   // Method net/minecraft/class_1792.method_7848:()Lnet/minecraft/class_2561;
#   // Field net/minecraft/class_1309.field_6004:D
#   // InterfaceMethod net/minecraft/class_1935.method_7853:(...)L...;
# ⚠️ 构造器调用长这样：// Method net/minecraft/class_2793."<init>":(I)V
#   ——成员名**带引号**。早先正则的字符类没含 `"`，于是**所有构造器调用被整条跳过**
#   （实测漏掉了 `new ServerboundAcceptTeleportationPacket(int)`：编译 jar 有 (int) 构造器、
#   专用服务端 jar 只剩无参构造，真机一传唤就 NoSuchMethodError）。故成员名允许引号。
REF_RE = re.compile(
    r"//\s*(?:Interface)?(?:Method|Field)\s+"
    r"(net/minecraft/[\w/$]+)\.([\w$<>\"]+):(\S+)")
CLASS_RE = re.compile(r"//\s*class\s+(net/minecraft/[\w/$]+)")

_HEADER_RE = re.compile(
    r"^(?:public |final |abstract |static |non-sealed )*"
    r"(?:class|interface|enum|@interface)\s+([\w/$\.]+)(.*)$")

_cache = {}


def javap(args):
    key = tuple(args)
    if key not in _cache:
        try:
            out = subprocess.run([JAVAP] + list(args), capture_output=True,
                                 text=True, encoding="utf-8", errors="replace",
                                 timeout=120).stdout
        except (OSError, subprocess.TimeoutExpired):
            out = ""
        _cache[key] = out
    return _cache[key]


def mod_classes(jar):
    """模组 jar 顶层（不含嵌套 jar）的类名。"""
    out = subprocess.run([JAVAP, "-cp", jar, "-p", "com.dwinovo.numen.core.NumenCore"],
                         capture_output=True, text=True, encoding="utf-8",
                         errors="replace").stdout
    names = []
    # 更直接：列出 jar 里的 .class 条目
    import zipfile
    with zipfile.ZipFile(jar) as z:
        for n in z.namelist():
            if n.endswith(".class") and n.startswith(PACKAGES):
                names.append(n[:-6].replace("/", "."))
    return sorted(names)


def refs_of(classes, jar):
    """→ {类名: (MC 成员引用集合, 用到的 MC 类集合)}"""
    per_class = {}
    for i, cls in enumerate(classes):
        txt = javap(["-c", "-p", "-cp", jar, cls])
        refs = set()
        used = set()
        for m in REF_RE.finditer(txt):
            owner, member, desc = m.group(1), m.group(2), m.group(3)
            member = member.strip('"')
            if member == "<init>":
                # 构造器：服务端成员表按「类简单名」记（javap -s 打的是 class_2793()），
                # 这里同步归一到类简单名，否则 `<init>` 永远对不上 → 漏报。
                member = owner.rsplit("/", 1)[-1]
            refs.add((owner, member, desc))
        for m in CLASS_RE.finditer(txt):
            used.add(m.group(1))
        per_class[cls] = (refs, used)
        if i % 25 == 0:
            print("    ...javap %d/%d" % (i, len(classes)))
    return per_class


def members_of(cls, jar):
    """→ (成员集合 {(name,desc)}, 直接父类/接口集合)。成员含字段与方法。

    ⚠️ **类头会被 javap 折行**，而且折行位置随名字长度变：
        public interface net.minecraft.class_1936 extends net.minecraft.class_1922,
            net.minecraft.class_2818 {
    早先的版本要求「类头在一行里以 { 结尾」，折行时整条父链就丢了，
    于是**把继承来的成员误报成「服务端没有」**（实测：Level.getMaxBuildHeight、
    Entity.getEyeHeight 都被冤枉过）。所以这里先把折行的类头拼回一行。
    `extends A, B` 的**逗号后续项**也要一并收下——早先只收了每个关键字后的第一个。
    """
    txt = javap(["-p", "-s", "-cp", jar, cls])
    if not txt.strip():
        return None, set()
    members = set()
    parents = set()
    name = None
    lines = txt.splitlines()
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        # 折行的类头：从第一条像类声明的行开始，一直拼到以 { 结尾
        if _HEADER_RE.match(s) and not s.endswith("{"):
            while i + 1 < len(lines) and not s.endswith("{"):
                i += 1
                s += " " + lines[i].strip()
        if s.endswith("{"):
            m = _HEADER_RE.match(s[:-1].strip())
            if m:
                name = m.group(1)
                for clause in re.finditer(
                        r"(?:extends|implements)((?:\s+[\w/$\.]+)(?:\s*,\s*[\w/$\.]+)*)", m.group(2)):
                    for t in re.findall(r"[\w/$\.]+", clause.group(1)):
                        parents.add(t)
            i += 1
            continue
        if s.startswith("descriptor:"):
            if name is not None:
                members.add((name, s.split(":", 1)[1].strip()))
            i += 1
            continue
        if s.endswith(";"):
            # 形如：public net.minecraft.class_2561 method_7848();
            mm = re.search(r"([\w$<>]+)\s*\(", s)
            if mm:
                name = mm.group(1)
            else:
                # 字段：public static final int field_8003;
                mm2 = re.search(r"([\w$]+)\s*;$", s)
                if mm2:
                    name = mm2.group(1)
        i += 1
    return members, parents


def closure_members(cls, jar, seen=None):
    """该类 + 父类/接口链上的全部成员。"""
    if seen is None:
        seen = set()
    if cls in seen:
        return set()
    seen.add(cls)
    members, parents = members_of(cls, jar)
    if members is None:
        return None                      # 类不存在
    out = set(members)
    for p in parents:
        sub = closure_members(p, jar, seen)
        if sub:
            out |= sub
    return out


def main():
    for p, what in ((MOD_JAR, "模组 jar"), (SERVER_JAR, "服务端 jar")):
        if not os.path.exists(p):
            print("!! 找不到%s：%s" % (what, p))
            return 1

    print("模组 jar   : %s" % MOD_JAR)
    print("服务端 jar : %s" % SERVER_JAR)

    classes = mod_classes(MOD_JAR)
    client_classes = [c for c in classes if c.startswith(CLIENT_PKG)]
    print("\ncore 模块类数：%d（其中客户端专属 %d 个，按例外处理）"
          % (len(classes), len(client_classes)))
    per_class = refs_of(classes, MOD_JAR)

    refs = set()
    used = set()
    # 共享类碰了 net.minecraft.client.* —— 这才是真正会让专用服务端炸的写法。
    shared_uses_client = []
    for cls, (r, u) in per_class.items():
        if cls.startswith(CLIENT_PKG):
            # 客户端专属包：它引用客户端成员是本分，拿服务端 jar 去核对没有意义。
            # 这里只保证它**不在**共享路径上（由下面的反向检查兜底）。
            continue
        refs |= r
        used |= u
        for c in u:
            if c.startswith("net/minecraft/client/"):
                shared_uses_client.append((cls, c))

    print("  抠到 MC 引用 %d 条，涉及类 %d 个" % (len(refs), len(used)))
    print("  共享类引用客户端专属类：%d 处" % len(shared_uses_client))

    by_owner = defaultdict(list)
    for owner, name, desc in refs:
        by_owner[owner].append((name, desc))

    # ⚠️ 追踪每个引用的来源类（诊断「谁在调」），缺成员时打印来源，便于判断可达性。
    # 在 refs_of 里同时记 refs_from：{(owner,name,desc): [来源类…]}
    refs_from = {}
    for cls, (r, u) in per_class.items():
        if cls.startswith(CLIENT_PKG):
            continue
        for (owner, name, desc) in r:
            refs_from.setdefault((owner, name, desc), []).append(cls)

    print("\n逐个核对（沿父类/接口链找）…")
    missing_classes = []
    missing_members = []
    for owner in sorted(by_owner):
        avail = closure_members(owner, SERVER_JAR)
        if avail is None:
            missing_classes.append(owner)
            continue
        for name, desc in sorted(set(by_owner[owner])):
            if (name, desc) not in avail:
                missing_members.append((owner, name, desc))

    print("\n" + "=" * 70)
    print("结果")
    print("=" * 70)

    if missing_classes:
        print("\n[!] 服务端 jar 里整个类都不存在（%d 个）：" % len(missing_classes))
        for c in missing_classes:
            print("    " + c)
    else:
        print("\n[OK] 引用的 MC 类在服务端 jar 里都有")

    if missing_members:
        print("\n[!] 类在、成员不在（%d 条）——这些就是会在专用服务端上炸的点："
              % len(missing_members))
        cur = None
        for owner, name, desc in missing_members:
            if owner != cur:
                print("  %s" % owner)
                cur = owner
            srcs = sorted(set(refs_from.get((owner, name, desc), [])))
            print("      %s %s" % (name, desc))
            print("          ← 被调于: %s" % (", ".join(srcs) if srcs else "?"))
    else:
        print("\n[OK] 所有引用的成员在服务端 jar 里都能找到")

    if shared_uses_client:
        print("\n[!] **共享类**引用了客户端专属类（%d 处）——专用服务端上会 NoClassDefFoundError："
              % len(shared_uses_client))
        cur = None
        for cls, c in shared_uses_client:
            if cls != cur:
                print("  %s" % cls)
                cur = cls
            print("      -> %s" % c)
    else:
        print("\n[OK] 没有任何共享类引用 net.minecraft.client.*")

    if missing_classes or missing_members or shared_uses_client:
        print("\n总结论：有 %d 处会在专用服务端上失败 ❌"
              % (len(missing_classes) + len(missing_members) + len(shared_uses_client)))
        return 2
    print("\n总结论：服务端 API 全部对得上 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
