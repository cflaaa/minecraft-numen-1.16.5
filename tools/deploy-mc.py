# -*- coding: utf-8 -*-
"""Deploy a Minecraft 1.16.5 Fabric test server (docker/itzg) on the NAS.

- Ports 25565-25585 are taken by mslx-daemon, so the server is exposed on host port 25586.
- Mods: bundled Fabric API 0.42.0+1.16 (assembled from gradle cache) + Numen 0.1.4.
- ⚠️ 镜像走 `docker.m.daocloud.io`（2026-10-11 与 NAS 上实际的 docker-compose.yml 核对过）。
  **`docker.mslmc.cn` 不代理 itzg**，用它 pull 会失败——本文件原先写的就是它，已改。
- 重新部署：`python deploy-mc.py`（上传 compose + mods、pull、compose up、等 Done）。
  只想更新模组时：SFTP 传 jar 到 `{RDIR}/data/mods/`，再 `docker compose restart`。
- ⚠️ 这个容器**平时是停着的**（`docker ps -a` 里连记录都没有，是 down 掉的），
  要用 NAS 那套测试就得先 `cd {RDIR} && sudo docker compose up -d`。
- ⚠️ RCON 关着、MCP 端口没发布 → **拿不到服务端控制台，也不能用 MCP 驱动同伴**。
  需要「召唤/驱动同伴」的用例请用本机方案（.testserver/）。
"""
import os
import sys
import time
import paramiko

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nas_creds  # noqa: E402

HOST, USER, PWD = nas_creds.host_user_pwd()
RDIR = "/vol1/1000/docker/mc1165-test"
SUDO = nas_creds.sudo_prefix()

COMPOSE = """\
services:
  mc:
    image: docker.m.daocloud.io/itzg/minecraft-server:java17
    container_name: mc1165-test
    environment:
      EULA: "TRUE"
      TYPE: "FABRIC"
      VERSION: "1.16.5"
      FABRIC_LOADER_VERSION: "0.14.25"
      MEMORY: "2G"
      MOTD: "Numen 1.16.5 Fabric test server"
      ONLINE_MODE: "FALSE"
      ENABLE_RCON: "FALSE"
      VIEW_DISTANCE: "10"
      TZ: "Asia/Shanghai"
    ports:
      - "25586:25565"
    volumes:
      - ./data:/data
    restart: unless-stopped
    stdin_open: true
    tty: true
"""

MODS = [
    (r"C:/Users/cfl/workbuddy-ai/我的世界/.workbuddy-ai/tmp/fabric-api-0.42.0+1.16-bundled.jar",
     "fabric-api-0.42.0+1.16.jar"),
    (r"C:/Users/cfl/workbuddy-ai/我的世界/numen-1.16.5/core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar",
     "Numen-fabric-1.16.5-0.1.4-1.16.5.jar"),
]


def run(ssh, cmd, timeout=300, show=True):
    _, out, err = ssh.exec_command(cmd, timeout=timeout)
    o = out.read().decode("utf-8", "replace")
    e = err.read().decode("utf-8", "replace")
    rc = out.channel.recv_exit_status()
    if show:
        print(f"$ {cmd}\n[rc={rc}]\n{o}")
        if e.strip():
            print("[stderr]", e[-2000:])
    return rc, o, e


def main():
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, password=PWD, timeout=10)

    print("== 1. create dirs ==")
    run(ssh, f"mkdir -p {RDIR}/data/mods")

    print("== 2. upload files (sftp) ==")
    sftp = ssh.open_sftp()
    sftp.putfo if False else None
    import io
    sftp.open(f"{RDIR}/docker-compose.yml", "w").write(COMPOSE)
    for local, name in MODS:
        dst = f"{RDIR}/data/mods/{name}"
        sftp.put(local, dst)
        print("uploaded", dst)
    sftp.close()
    run(ssh, f"ls -la {RDIR} {RDIR}/data/mods", show=True)

    print("== 3. pull image ==")
    rc, o, e = run(ssh, f"{SUDO} docker pull docker.m.daocloud.io/itzg/minecraft-server:java17",
                   timeout=600, show=False)
    print(o[-1500:], e[-1500:])
    if rc != 0:
        print("PULL FAILED")
        sys.exit(1)

    print("== 4. compose up ==")
    run(ssh, f"cd {RDIR} && {SUDO} docker compose up -d 2>&1 | grep -v password", timeout=120)

    print("== 5. wait & check logs ==")
    for i in range(12):
        time.sleep(10)
        rc, o, _ = run(ssh, f"{SUDO} docker logs --tail 5 mc1165-test 2>&1 | grep -v password", show=False)
        tail = o.strip().splitlines()
        print(f"[{i*10}s]", " | ".join(tail[-3:]) if tail else "(no logs)")
        if "Done" in o or "ERROR" in o or "Failed" in o:
            break
    run(ssh, f"{SUDO} docker logs mc1165-test 2>&1 | grep -v password | tail -40")
    ssh.close()


if __name__ == "__main__":
    main()
