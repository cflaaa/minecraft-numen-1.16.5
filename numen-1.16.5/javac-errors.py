#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 gradle 编译日志里的 javac 错误归纳出来。

用法：
    gradle :pathing:compileJava --console=plain > build.log 2>&1
    python javac-errors.py build.log [关键字]

为什么要单独一个脚本：
  1. javac 的输出是 **GBK**（中文 Windows 的默认编码），用 UTF-8 读会全是乱码；
  2. 错误行、源代码行、`符号:`/`位置:` 提示分三行，肉眼翻一百条很累；
  3. javac 默认只报前 100 条，所以要看的是**模式**而不是逐条。
"""
import io
import re
import sys
import collections

PATH_RE = re.compile(r"(?P<file>[^\\/:]+\.java):(?P<line>\d+): 错误: (?P<msg>.+)$")


def main():
    log = sys.argv[1] if len(sys.argv) > 1 else "build.log"
    needle = sys.argv[2] if len(sys.argv) > 2 else None
    text = io.open(log, encoding="gbk", errors="replace").read()
    lines = text.splitlines()

    rows = []
    for i, line in enumerate(lines):
        m = PATH_RE.search(line)
        if not m:
            continue
        src = lines[i + 1].strip() if i + 1 < len(lines) else ""
        rows.append((m.group("file"), int(m.group("line")), m.group("msg").strip(), src))

    print("错误条数（javac 上限 100，可能还有更多）：%d" % len(rows))

    print("\n=== 按消息归类 ===")
    by_msg = collections.Counter(r[2] for r in rows)
    for msg, n in by_msg.most_common(40):
        print("%3d  %s" % (n, msg))

    print("\n=== 按文件归类 ===")
    by_file = collections.Counter(r[0] for r in rows)
    for f, n in by_file.most_common(60):
        print("%3d  %s" % (n, f))

    if needle:
        print("\n=== 含 %r 的明细 ===" % needle)
        for f, ln, msg, src in rows:
            if needle in f or needle in msg or needle in src:
                print("%s:%d  %s" % (f, ln, msg))
                print("      %s" % src)


if __name__ == "__main__":
    main()
