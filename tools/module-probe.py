#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探针：my.probe 模块在不在同伴的模块目录里（tool-reg-e2e 第 5 步的排查）。"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import _perm_live_common as P   # noqa: E402

DEPLOYED = os.path.join(P.TS, "mods", "numen-0.1.4-1.16.5.jar")
BUILT = os.path.join(P.TS, "..", "numen-1.16.5", "core", "build", "libs",
                     "Numen-fabric-1.16.5-0.1.4-1.16.5.jar")


def main():
    import shutil
    shutil.copyfile(BUILT, DEPLOYED)
    d, libs = P.libraries()
    P.start_server(libs)
    if P.wait_new(r"Done \(", 0, 300, P.server) is None:
        print("!! 服务端没起来")
        return 1
    P.start_client(d, libs)
    P.wait_new(r"%s joined the game" % P.PLAYER, 0, 300, P.server)
    P.cmd("/execute as %s run numen summon %s" % (P.PLAYER, P.COMPANION))
    time.sleep(3)

    def lua(code):
        return P.call("lua", {"companion": P.COMPANION, "code": code})

    print("== 同一段：show 之后直接 ping ==")
    print(lua('print(numen.module.show("my.probe"))\nprint(numen.my.probe.ping())'))
    print("== help 列表 ==")
    print(lua('print(numen.api.help("numen.my.probe"))'))
    P.kill_all()
    return 0


if __name__ == "__main__":
    sys.exit(main() or 0)
