# -*- coding: utf-8 -*-
"""NAS 凭据读取器（共享）—— 供 tools/ 下的 NAS 相关脚本使用。

**仓库里不存明文口令。** 解析顺序（先命中先用）：
  1. 环境变量 NUMEN_NAS_HOST / NUMEN_NAS_USER / NUMEN_NAS_PWD
  2. 仓库根目录的 `ssh-run.py`（本机私有、已 gitignore，里面写着 HOST/USER/PASSWORD）
  3. 仓库根目录的 `.nas-creds.env`（KEY=VALUE 文本，同样 gitignore）

拿不到就抛 RuntimeError，并提示怎么配。

注：LAN IP 192.168.1.149 已在 docs/PORTING.md、STATUS.md 等入库文件中出现，
不算秘密；真正要护住的是口令。所以这里 IP 有默认值，口令没有。
"""
import os
import re

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_HOST = "192.168.1.149"
DEFAULT_USER = "cfl"


def _from_env():
    h = os.environ.get("NUMEN_NAS_HOST")
    u = os.environ.get("NUMEN_NAS_USER")
    p = os.environ.get("NUMEN_NAS_PWD")
    if h and u and p:
        return h, u, p
    return None


def _grab(txt, name):
    m = re.search(rf'^{name}\s*=\s*["\']([^"\']+)["\']', txt, re.M)
    return m.group(1) if m else None


def _from_ssh_run():
    path = os.path.join(REPO_ROOT, "ssh-run.py")
    if not os.path.exists(path):
        return None
    try:
        txt = open(path, encoding="utf-8").read()
    except OSError:
        return None
    h, u, p = _grab(txt, "HOST"), _grab(txt, "USER"), _grab(txt, "PASSWORD")
    if h and u and p:
        return h, u, p
    return None


def _from_envfile():
    path = os.path.join(REPO_ROOT, ".nas-creds.env")
    if not os.path.exists(path):
        return None
    d = {}
    try:
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        return None
    h, u, p = (d.get("NUMEN_NAS_HOST"), d.get("NUMEN_NAS_USER"),
               d.get("NUMEN_NAS_PWD"))
    if h and u and p:
        return h, u, p
    return None


def load():
    """返回 (host, user, password)。拿不到口令时抛 RuntimeError。"""
    for fn in (_from_env, _from_ssh_run, _from_envfile):
        r = fn()
        if r:
            return r
    raise RuntimeError(
        "拿不到 NAS 凭据：请设置 NUMEN_NAS_HOST/USER/PWD 环境变量，"
        "或在仓库根放置 .nas-creds.env，或保留本机私有 ssh-run.py。"
    )


def host_user_pwd():
    return load()


def host(default=DEFAULT_HOST):
    """只取 host；口令缺失时退回默认 LAN IP（用于只连不需要 sudo 的场景）。"""
    try:
        return load()[0]
    except RuntimeError:
        return default


def sudo_prefix():
    """远端 sudo 口令前缀。"""
    _, _, p = load()
    return "echo %s | sudo -S " % p
