#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""安装 git 钩子：把「可见性审计」变成提交前的自动防线。

钩子只有一道：`pre-commit` 跑 `tools/git-audit.py`——一旦有「被 .gitignore 忽略、
却看着像该入库」的文件（尤其是 src/ 树里的），**直接拦住提交**。
这样「源码从未入库」这种事就不可能再悄悄发生。

用法（装一次即可）：
  python tools/install-hooks.py           # 安装/覆盖
  python tools/install-hooks.py --check   # 只查装没装

钩子文件在 .git/hooks/ 里（不入库），所以提供这个安装器；
换机器/重新 clone 后重跑一次就行。若 `python` 不在 PATH，钩子会**放行并提醒**（不阻断工作流）。
"""
import os
import shutil
import stat
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
HOOK = """#!/bin/sh
# ════════════════════════════════════════════════════════════════════════
# 提交前审计（2026-10-13 事故的自动化防线）
#   .gitignore 的一条 `**/build/` 曾把源码里真叫 build 的 21 个文件连坐忽略，
#   导致它们从未入库、工作树一丢就找不回。这道钩子保证那类事不可能再悄悄发生。
# 由 tools/install-hooks.py 安装；钩子本身不入库。
# ════════════════════════════════════════════════════════════════════════
PY=""
for c in python python3 py; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
    echo "[git-audit] 找不到 python，跳过审计（不拦提交）；装好后建议重跑 tools/install-hooks.py"
    exit 0
fi
ROOT="$(git rev-parse --show-toplevel)"
"$PY" "$ROOT/tools/git-audit.py" --quiet || {
    echo ""
    echo "[git-audit] ⛔ 有文件被忽略却像是该入库的 —— 已拦住本次提交。"
    echo "            看详情: python tools/git-audit.py"
    echo "            确认要入库: git add -f <文件>   （确认应忽略: 改进 .gitignore 的放行规则）"
    echo "            确认无害: 用 git commit --no-verify 跳过（想清楚再跳）"
    exit 1
}
exit 0
"""


def hooks_dir():
    out = subprocess.run(["git", "rev-parse", "--git-path", "hooks"], cwd=ROOT,
                          capture_output=True, text=True).stdout.strip()
    if not os.path.isabs(out):
        out = os.path.join(ROOT, out)
    return out


def main():
    check_only = "--check" in sys.argv[1:]
    hd = hooks_dir()
    target = os.path.join(hd, "pre-commit")
    installed = os.path.exists(target)

    if check_only:
        print("钩子 %s：%s" % (target, "已安装 ✅" if installed else "未安装 ❌"))
        return 0 if installed else 1
    if not os.path.isdir(hd):
        os.makedirs(hd, exist_ok=True)

    if installed:
        backup = target + ".bak"
        shutil.copy2(target, backup)
        print("旧钩子已备份为 %s" % backup)

    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(HOOK)
    os.chmod(target, os.stat(target).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    print("✅ pre-commit 审计钩子已安装：%s" % target)
    print("   → 以后每次提交前都会跑 tools/git-audit.py，有 SUSPECT 就拦住。")
    print("   → 跳过方式（想清楚再用）：git commit --no-verify")
    return 0


if __name__ == "__main__":
    sys.exit(main())
