#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键快照：把整个仓库（**含未提交的改动**）备份到仓库外，用于对抗
「模型中断 / 误删 / 工作树损坏」。

为什么需要它：
  1. git 只保护**已提交**的东西。模型跑到一半被打断时，工作树里往往有一大堆
     还没提交的改动——那部分 git 一点都保护不了（2026-10-13 就吃过这个亏）。
  2. `git rm` / `clean` / `reset` / 批量删除脚本、反编译/搬运脚本，都可能误伤工作树。

做两件事（都不碰工作树，只读）：
  * `git bundle`：把**全部 ref**（HEAD + 所有分支）打成单文件，历史级备份；
  * `worktree.tar.gz`：把工作树**原样**打包（含未提交、含未跟踪、含被 .gitignore 忽略的
    工具脚本），排除只可再生的重目录。

用法：
  python tools/git-snapshot.py                # 打一份快照
  python tools/git-snapshot.py --keep 20      # 保留最近 20 份（默认 12）
  python tools/git-snapshot.py --list         # 列出现有快照

⚠️ 排除目录要**精确**，别用 `**/build/` 这种通配——源码里就有真叫 build 的包
   （com.dwinovo.numen.core.build），通配会把源码一起漏掉（2026-10-13 的教训）。
"""
import argparse
import io
import os
import shutil
import subprocess
import sys
import tarfile
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
BACKUP = os.path.abspath(os.path.join(ROOT, "..", "我的世界-backup"))

# 只可再生的重目录（精确列出，绝不用通配，免得漏掉源码里的同名包）
EXCLUDE_DIRS = {
    ".git", "logs",
    ".toolchain", ".testserver", "ref",
    os.path.join("numen-1.16.5", ".gradle"),
    os.path.join("numen-1.16.5", "build"),
    os.path.join("numen-1.16.5", "core", "build"),
    os.path.join("numen-1.16.5", "pathing", "build"),
    os.path.join("numen-1.16.5", "agent", "build"),
    os.path.join("numen-1.16.5", "ai", "build"),
    os.path.join("numen-1.16.5", "lua", "build"),
    os.path.join("numen-1.16.5", "ui", "build"),
}
EXCLUDE_SUFFIX = (".class",)


def run(args, **kw):
    return subprocess.run(args, cwd=ROOT, capture_output=True, text=True, **kw)


def tar_filter(ti):
    rel = os.path.relpath(ti.name, ROOT)
    parts = rel.split(os.sep)
    for i in range(len(parts)):
        if os.sep.join(parts[:i + 1]) in EXCLUDE_DIRS:
            return None
    if rel.endswith(EXCLUDE_SUFFIX):
        return None
    return ti


def snapshot():
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dest = os.path.join(BACKUP, "snapshot-" + stamp)
    os.makedirs(dest, exist_ok=True)

    # 1) 历史：全部 ref 打成一个 bundle
    bundle = os.path.join(dest, "repo.bundle")
    r = run(["git", "bundle", "create", bundle, "--all"])
    if r.returncode != 0:
        print("!! git bundle 失败：\n" + r.stderr)
        return 1

    # 2) 工作树：原样打包（含未提交/未跟踪/被忽略的脚本）
    tar_path = os.path.join(dest, "worktree.tar.gz")
    n = 0
    with tarfile.open(tar_path, "w:gz") as tf:
        for entry in sorted(os.listdir(ROOT)):
            p = os.path.join(ROOT, entry)
            if entry in EXCLUDE_DIRS:
                continue
            tf.add(p, arcname=entry, filter=tar_filter)
            n += 1

    # 3) 现场记录：状态 + 最近提交，便于回溯「当时在干什么」
    st = run(["git", "status", "--short"]).stdout
    lg = run(["git", "log", "--oneline", "-8"]).stdout
    with io.open(os.path.join(dest, "STATUS.txt"), "w", encoding="utf-8") as f:
        f.write("# 快照时间 %s\n# 仓库 %s\n\n## git status --short\n%s\n## git log -8\n%s\n"
                % (stamp, ROOT, st or "(工作树干净)", lg))

    size = sum(os.path.getsize(os.path.join(dest, x)) for x in os.listdir(dest))
    print("✅ 快照完成：%s（%.1f MB）" % (dest, size / 1048576.0))
    print("   repo.bundle     历史（全部 ref）")
    print("   worktree.tar.gz 工作树原样（含未提交改动）")
    print("   STATUS.txt      当时的 git 状态与最近提交")
    if st.strip():
        print("   ⚠️ 快照时工作树**有未提交改动**（这正是快照的价值）")
    return 0


def rotate(keep):
    if not os.path.isdir(BACKUP):
        return
    snaps = sorted(d for d in os.listdir(BACKUP) if d.startswith("snapshot-"))
    for old in snaps[:-keep] if len(snaps) > keep else []:
        shutil.rmtree(os.path.join(BACKUP, old), ignore_errors=True)
        print("   轮换删除旧快照：%s" % old)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", type=int, default=12)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    if a.list:
        if os.path.isdir(BACKUP):
            for d in sorted(os.listdir(BACKUP)):
                p = os.path.join(BACKUP, d)
                print("%s  %.1f MB" % (d, sum(os.path.getsize(os.path.join(p, x))
                                              for x in os.listdir(p)) / 1048576.0))
        else:
            print("(还没有快照)")
        return 0
    rc = snapshot()
    if rc == 0:
        rotate(a.keep)
    return rc


if __name__ == "__main__":
    sys.exit(main())
