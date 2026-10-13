#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""归位计划 v3 的刀 1a：把上游这些件搬到本仓 core/ 下。

**v2（2026-10-11 改）：改用方案甲。** 原方案乙（只搬 program 服务端半边）的前提被证伪——
`ServerPrograms` 直接依赖传输半边（`ClientCalls` / `ClientTransport` / `ModuleSet` /
`NetworkTransport.INSTANCE` / `NumenNetwork`），而传输半边又依赖 `network/` 的 `Wire` /
`ClientCallPayload` 等。按乙编不过（实测 35 个错，全出在这条链上）。

所以本版：
  * `program/` **整个** 22 文件都搬（不再排除传输半边）
  * `network/` 33 文件也搬（`NumenNetwork` / `Wire` / `Fragments` / 25 个 payload）
  * 补上编译期暴露的三处缺件：`task/CompanionBrain`（计划误判为「客户端大脑」，
    实际是**服务端**每刻调度器，被 `CompanionTickDispatcher` / `NumenPlayer` /
    `CurrentTaskPayload` / `BrainChains` 引用）、`entity/CompanionChunkLoader`、
    `mixin/BoatAccessor`（`InputDriver` 用它的 `@Invoker controlBoat`）
  * `sdk/ApiTester` 也搬（它的对端是 program 传输半边，现在有了）

包名映射（上游 → 本仓）：
  com.dwinovo.numen.<x>      → com.dwinovo.numen.core.<x>     （除下面两个例外）
  com.dwinovo.numen.agent.<模块子包>.* → 原样（agent 是独立模块，已逐字搬）
     模块子包 = conversation / goal / inbox / loop / memory / script
  com.dwinovo.numen.pathing.*→ 原样（pathing 是独立模块，已搬）
  com.dwinovo.numen.Constants→ com.dwinovo.numen.core.NumenCore（Constants.LOG → NumenCore.LOG）

⚠️ 注意 `com.dwinovo.numen.agent.tool.api.ToolContext` 属于 `api/common` 的
「MC 侧胶水」而不是 agent 模块，所以要改写成 `core.agent.tool.api`——KEEP 规则因此
不能写成「凡 agent. 就保留」。
"""
import os
import re
import sys

ROOT = r"C:\Users\cfl\workbuddy-ai\我的世界"
UP = os.path.join(ROOT, "ref", "numen-1.20.1", "api", "common", "src", "main", "java", "com", "dwinovo", "numen")
DOWN = os.path.join(ROOT, "numen-1.16.5", "core", "src", "main", "java", "com", "dwinovo", "numen", "core")

# 上游目录 → 本仓子目录（相对 core/）
DIRS = [("sdk", "sdk"), ("script", "script"), ("task", "task"), ("mc", "mc"),
        ("api", "api"), ("program", "program"), ("platform", "platform"),
        ("event", "event"), ("entity", "entity"), ("network", "network"),
        ("mixin", "mixin")]

# 不在上面这些目录里、但要单独拎出来的件（上游相对 numen/ 的路径）
EXTRA_FILES = ["agent/tool/api/ToolContext.java"]

# 单个文件级排除
SKIP_FILES = {
    # 本仓已有自己的 mixin 层：只挑编译期被引用到的三个，其余等各自的刀
    "mixin/ArchitecturyPlayerHooksMixin.java",
    "mixin/ItemEntityTouchMixin.java",
    "mixin/MixinEntityVehicleControl.java",
    "mixin/MixinServerGamePacketListener.java",
    "mixin/PlayerAdvancementsRewardMixin.java",
    "mixin/package-info.java",
}
# 只挑这几个 mc / api / entity 文件（其余依赖未搬的层）
ALLOW_FILES = {"mc/Echo.java", "mc/Completions.java", "api/Internal.java",
               # entity/ 上游 21 个，本仓只缺这几个
               "entity/CompanionRegistry.java", "entity/EventOutbox.java",
               "entity/InputDriver.java", "entity/CompanionSpeech.java",
               "entity/CompanionChunkLoader.java"}
# 本仓已有的、已按 1.16.5 改好的文件，**不要覆盖**
#   task/ 那 5 个是第一刀搬的
#   mixin/BlockItemPlaceMixin 是第一刀就把放置记账接进权限层时改好的
#   （上游那份是 1.20.1 的原始版，直接盖回来会把 1.16.5 适配冲掉）
EXISTING = {"task/Task.java", "task/TaskResult.java", "task/TaskSelector.java",
            "task/TaskState.java", "task/Preparation.java",
            "mixin/BlockItemPlaceMixin.java"}

# 保留原包的 numen 命名空间：agent 独立模块 + ai 模块里的 agent.llm + pathing + 本仓 core 自身。
# ⚠️ 唯一的例外是 `agent.tool.api`——那是 `api/common` 的「MC 侧胶水」（ToolContext），不是 agent 模块，
#    要跟着改到 core.agent.tool.api。
# ⚠️ network. 不再保留——network/ 这一版要搬，包名要跟着改到 core.network。
AGENT_GLUE = "com.dwinovo.numen.agent.tool.api."
KEEP_PREFIX = ("com.dwinovo.numen.agent.", "com.dwinovo.numen.pathing.", "com.dwinovo.numen.core.")


def rewrite(text):
    # 1) 包声明
    text = re.sub(r"^package com\.dwinovo\.numen\.", "package com.dwinovo.numen.core.", text, flags=re.M)
    # 2) Constants → NumenCore
    text = text.replace("import com.dwinovo.numen.Constants;", "import com.dwinovo.numen.core.NumenCore;")
    text = text.replace("com.dwinovo.numen.Constants.LOG", "NumenCore.LOG")
    text = re.sub(r"(?<![\w.])Constants\.LOG", "NumenCore.LOG", text)

    # 3) 跨包 import / 全限定引用：com.dwinovo.numen.<x> → com.dwinovo.numen.core.<x>
    def fix(m):
        whole = m.group(0)
        if whole.startswith(AGENT_GLUE):
            return "com.dwinovo.numen.core." + whole[len("com.dwinovo.numen."):]
        if whole.startswith(KEEP_PREFIX):
            return whole
        return "com.dwinovo.numen.core." + whole[len("com.dwinovo.numen."):]

    text = re.sub(r"com\.dwinovo\.numen\.(?!pathing\.|core\.)[a-z][\w.]*", fix, text)
    return text


def main():
    copied, skipped = [], []
    for up_dir, down_dir in DIRS:
        src_dir = os.path.join(UP, up_dir)
        if not os.path.isdir(src_dir):
            print("!! 没有上游目录 %s" % src_dir)
            continue
        for root, _dirs, files in os.walk(src_dir):
            for name in sorted(files):
                if not name.endswith(".java"):
                    continue
                src = os.path.join(root, name)
                rel = os.path.relpath(src, UP).replace("\\", "/")   # 如 sdk/ApiFunction.java
                if rel in SKIP_FILES:
                    skipped.append((rel, "排除名单"))
                    continue
                if up_dir in ("mc", "api", "entity") and rel not in ALLOW_FILES:
                    skipped.append((rel, "只挑需要的几个"))
                    continue
                if rel in EXISTING:
                    skipped.append((rel, "本仓已有（第一刀搬的）"))
                    continue
                dst = os.path.join(DOWN, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(src, encoding="utf-8") as f:
                    text = f.read()
                with open(dst, "w", encoding="utf-8", newline="\n") as f:
                    f.write(rewrite(text))
                copied.append(rel)

    # 目录之外的散件
    for rel in EXTRA_FILES:
        src = os.path.join(UP, rel.replace("/", os.sep))
        dst = os.path.join(DOWN, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(src, encoding="utf-8") as f:
            text = f.read()
        with open(dst, "w", encoding="utf-8", newline="\n") as f:
            f.write(rewrite(text))
        copied.append(rel)

    print("=== 搬了 %d 个文件 ===" % len(copied))
    for rel in copied:
        print("   + %s" % rel)
    print("=== 跳过 %d 个 ===" % len(skipped))
    for rel, why in skipped:
        print("   - %-34s %s" % (rel, why))
    return 0


if __name__ == "__main__":
    sys.exit(main())
