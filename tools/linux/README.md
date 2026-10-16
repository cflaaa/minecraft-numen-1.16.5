# tools/linux —— Numen 1.16.5 的 Linux 测试环境

Windows 那套测试（`.testserver/` 借 PCL 客户端库 + `KnotServer -cp`、图形客户端当主人）
在 Linux 上跑不了。这里给一套**自包含**的等价物：标准 Fabric 专用服 + mineflayer 假主人。

> 这不是 mod 源码的一部分（不碰 `numen-1.16.5/`）。放在 `tools/` 里，跟其它可复用脚本一样入库。

## 前提

- **Linux JDK 17**：默认 `~/.local/share/numen-toolchain/jdk-17.0.20.1+1`（上一轮装的；`NUMEN_TOOLCHAIN` 可覆盖）。
- **Node.js**（owner-bot 用 mineflayer）。
- 首次 `setup` 需要网络（Fabric 安装器 + 原版 server；fabric-api 走 Gradle 缓存）。

## 四步跑起来

```bash
# 0) 构建 mod（Linux 工具链 + BMCLAPI 修正 init 脚本）
tools/linux/build.sh :core:build

# 1) 建测试服（下 Fabric + 原版 server；铺 fabric-api 模块；打开 MCP）
tools/linux/setup-testserver.sh

# 2) 起服（后台；控制台走命名管道）
tools/linux/server.sh start
tools/linux/server.sh status

# 3) 主人假玩家（mineflayer）——替代图形客户端
cd tools/linux/owner-bot && npm install && node owner-bot.js   # 常驻，自动重连
```

就绪后：服务端日志 `.testserver/logs/latest.log`，MCP 端点 `http://127.0.0.1:8766/mcp`。

## 跑既有 e2e（Windows 脚本现在跨平台了）

`tools/_perm_live_common.py` 已改成按操作系统分支：Windows 保留原样，Linux 走
`fabric-server-launch.jar` + owner-bot。所以原来的 e2e 脚本能直接跑：

```bash
# 端口/主人/同伴都能用环境变量覆盖（本机 25565/25566 被 MSLX 占着，试用 25987）
export NUMEN_PORT=25987 NUMEN_MCP_PORT=8766
# e2e 自己会 start_server/start_client（Linux 的 start_client = 起 owner-bot）
python3 tools/perm-e2e.py
python3 tools/nav-gate-e2e.py
python3 tools/tool-reg-e2e.py
python3 tools/gear-e2e.py
python3 tools/fn-wire-e2e.py
```

> 注：`.testserver/server.properties` 里的 `server-port` 才是服务端实际端口；e2e 的
> `NUMEN_PORT` 只决定 owner-bot 连哪个口，两者要一致。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `NUMEN_TOOLCHAIN` | `~/.local/share/numen-toolchain` | JDK + Gradle 缓存根 |
| `NUMEN_JAVA` | `$NUMEN_TOOLCHAIN/jdk-17.0.20.1+1/bin/java` | Java 可执行 |
| `NUMEN_TS` | `<repo>/.testserver` | 测试服目录 |
| `NUMEN_PORT` | `25565` | 服务端端口（本机建议改空闲口） |
| `NUMEN_MCP_PORT` | `8766` | MCP 端点端口 |
| `NUMEN_PLAYER` / `NUMEN_COMPANION` | `T` / `Alice` | 主人 / 同伴名 |
| `NUMEN_FABRIC_MAVEN` | `https://maven.fabricmc.net` | Fabric 仓（可换镜像） |

## 与 Windows 侧的差异（要点）

| | Windows | Linux |
|---|---|---|
| 服务端 | `KnotServer -cp <客户端库>;<server.jar>`（借 PCL 客户端库） | `java -jar fabric-server-launch.jar nogui`（自包含） |
| 主人 | 图形客户端（PCL） | mineflayer 假玩家 `owner-bot.js` |
| 杀进程 | `taskkill /F /T` | `kill`（进程组，防孤儿占 `session.lock`） |
| classpath 分隔 | `;` | `:`（但 Linux 不用拼接 classpath） |
| 控制台 | 子进程 stdin 管道 | 子进程 stdin 管道 / `server.sh console`（FIFO） |
| fabric-api | 完整 bundle jar | loom 缓存里的 45 个模块 jar（等价 bundle） |

## 坑（沿用 Windows 侧教训）

- ⚠️ **别在服务端运行中热替换 `mods/` 的 jar**：Fabric 懒加载，之后首次用到的类会
  `Failed to load class file`。**换 jar 先 `server.sh stop`**。
- ⚠️ **杀服务端要连看门狗/进程组一起杀**，否则孤儿 java 占 `world/session.lock`。
  `server.sh stop` 已按进程组杀；手动 `pkill -x java`（`pgrep -x java` 不会误伤 bash）。
- 本机若 25565–25586 被占（MSLX 群），换一个空闲口（如 `25987`）。
- `tools/linux/build.sh` 带 `bmclapi-fix.init.gradle`：BMCLAPI 会把未缓存的 `com.mojang`
  302 到失效主机，init 脚本把它排除，落到 `libraries.minecraft.net`。
