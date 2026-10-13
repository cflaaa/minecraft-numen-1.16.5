#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""刀 1a 第五批：补上编译期暴露的缺件（D 类回补）。

都是「上游有、本仓没有」，用与 port-sdk-task.py **同一套包名规则**搬进来：
  com.dwinovo.numen.<x> → com.dwinovo.numen.core.<x>
  例外：agent 模块（含 ai 模块的 agent.llm）与 pathing 原样；
        只有 agent.tool.api（api/common 的胶水）跟着改。
"""
import os
import re
import sys

ROOT = r"C:\Users\cfl\workbuddy-ai\我的世界"
UP = os.path.join(ROOT, "ref", "numen-1.20.1", "api", "common", "src", "main", "java", "com", "dwinovo", "numen")
DOWN = os.path.join(ROOT, "numen-1.16.5", "core", "src", "main", "java", "com", "dwinovo", "numen", "core")

FILES = [
    # entity/：本仓只有 10 个（自写 5 + 新搬 5），补齐被引用到的
    "entity/MojangSkins.java",
    "entity/FakeClient.java",
    "entity/CompanionEvents.java",
    "entity/CompanionRoster.java",
    "entity/CompanionStateWatch.java",
    "entity/OwnerHurtWatch.java",
    "entity/BodyDelta.java",
    "entity/Belongings.java",
    "entity/ServerMessages.java",
    # api/：插件出口（上游 sdk 的对外面）+ 刀 2 的 gear
    "api/CompanionEvent.java",
    "api/Delivery.java",
    "api/NumenApi.java",
    "api/NumenPlugin.java",
    "api/NumenPlugins.java",
    "api/gear/GearSlot.java",
    "api/gear/GearSource.java",
]

AGENT_GLUE = "com.dwinovo.numen.agent.tool.api."
KEEP_PREFIX = ("com.dwinovo.numen.agent.", "com.dwinovo.numen.pathing.", "com.dwinovo.numen.core.")


def rewrite(text):
    text = re.sub(r"^package com\.dwinovo\.numen\.", "package com.dwinovo.numen.core.", text, flags=re.M)
    text = text.replace("import com.dwinovo.numen.Constants;", "import com.dwinovo.numen.core.NumenCore;")
    text = text.replace("com.dwinovo.numen.Constants.LOG", "NumenCore.LOG")
    text = re.sub(r"(?<![\w.])Constants\.LOG", "NumenCore.LOG", text)
    text = text.replace("Constants.MOD_ID", "NumenCore.MOD_ID")

    def fix(m):
        whole = m.group(0)
        if whole.startswith(AGENT_GLUE):
            return "com.dwinovo.numen.core." + whole[len("com.dwinovo.numen."):]
        if whole.startswith(KEEP_PREFIX):
            return whole
        return "com.dwinovo.numen.core." + whole[len("com.dwinovo.numen."):]

    return re.sub(r"com\.dwinovo\.numen\.(?!pathing\.|core\.)[a-z][\w.]*", fix, text)


copied = []
for rel in FILES:
    src = os.path.join(UP, rel.replace("/", os.sep))
    dst = os.path.join(DOWN, rel.replace("/", os.sep))
    if not os.path.exists(src):
        print("!! 上游没有 %s" % rel)
        continue
    if os.path.exists(dst):
        print("== 本仓已有，跳过 %s" % rel)
        continue
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(src, encoding="utf-8") as f:
        text = f.read()
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(rewrite(text))
    copied.append(rel)

print("补了 %d 个文件：" % len(copied))
for r in copied:
    print("   + " + r)
