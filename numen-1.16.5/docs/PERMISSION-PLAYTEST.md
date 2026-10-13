# 权限层 · 试跑报告 + 自己上手试的清单

> ## ⏳ 时效提醒（2026-10-13 补）
> 本文是 **2026-10-11「归位阶段 1」时点的试跑快照**。下面 §四/§五 里
> 「权限层**只有命令半边**、`Gate` 还没接到工具上、**接下来是 Stage 2**」这些**当时为真、现已作废**：
> **Stage 2 早已完成**——`Gate` 已接进 `mine` / `use_block` / `drop` 与 Lua（`numen.inv.drop` / `numen.use.block`），
> 寻路那条路也由 `GateTerrain` 逐格裁决，**真机 5/5 已验过**（见 `PORTING.md` §16 / §17）。
> 所以 §四 末尾「`consent` 点了也不会有东西弹出来」与 §五「接下来是 Stage 2」**都不再成立**——
> 现在同伴动手前会真发征询、点按钮真有用。
> **当前进展一律以 `STATUS.md` 为准。** 另：文中提到的 `.workbuddy-ai/tmp/*.py` 是一次性脚本；
> 可复用的真机验收脚本已搬到仓库根 `tools/`（入库）。

> 对应 `numen-1.16.5` 的「归位阶段 1：permission/ 17 件套」。
> 技术细节（改动点、踩坑、原版 bug 的完整栈）在 `PORTING.md` §14/§15。

## 一、这轮替你跑了什么

不是「启动一下看看」，是真刀真枪跑了一遍：

- 本机起 **1.16.5 专用服务端**（`.testserver/`，Fabric + fabric-api + numen 发行 jar）；
- 用**真客户端**（PCL 那套启动参数，独立的 `versions/1.16.5-Fabric 0.19.5-2`）直连进服，
  玩家名 `T`，UUID 固定；
- 进服后召唤同伴、跑权限命令、看持久化，最后 `/stop` 正常关服。
- 脚本：`.workbuddy-ai/tmp/so-probe.py`（`smoke-test.py` 的瘦身版）。

跑的是**修复后**的 jar（`7700b0df…`），已同步到测试服和你的客户端 `mods/` 里。

## 二、结果

| 项 | 结果 |
| --- | --- |
| 召唤 / 遣散 / `list` / `status` | ✅ 正常 |
| `/numen permission mode`、`rules add·list·remove·reset` | ✅ 正常，即时生效 |
| 权限**落盘** | ✅ 关服后写出 `world/data/numen_permissions_<主人UUID>.dat`（gzip NBT） |
| 关服 `/stop` | ✅ 1.2s 干净退出，`code=0` |
| 客户端异常 | 0（除下面第三条那个原版现象） |

落盘内容抽查：`allow:["take(*)"]`、`modes:{"…":"observe"}`，
deny 表里能看到 `1. break(placed & !contents)` —— 说明规则文本解析与写回都对。

## 三、两个发现（都已定性，别慌）

### 1. 🔴 专用服务端 `/stop` 永远不退出 —— **是我们的 bug，已修**

现象：世界存完、日志停在 `All chunks are saved`，进程就是不退，只能强杀。

原因：MCP 外接大脑的 HTTP 服务器（`com.sun.net.httpserver.HttpServer`）的
`HTTP-Dispatcher` **不是 daemon 线程**，而关服时从没调用过 `stop()`。

> ⚠️ 这条对 NAS 上那个 docker 测试服有实际意义：挂住会让 itzg 容器超时 SIGKILL，**有回档风险**。

修法：`SERVER_STOPPING` 里收掉它，并且**只对专用服务端收**（集成服务端「退到标题界面」
也会发同一个事件，留着才能跨世界重进）。改完实测 `/stop` 1.2s、`code=0`。

### 2. ⚠️ 关服瞬间客户端弹 `Internal Exception: java.lang.StackOverflowError` —— **原版 1.16.5 的行为，不修**

现象：`/stop` 时日志多一行
`T lost connection: Internal Exception: java.lang.StackOverflowError`。

一开始以为是移植引入的（模组在场 2/4 命中、移走模组挂机 4 轮 0/4）。但把 numen jar
**整个移走**、只留 fabric-api，再让客户端在关服窗口里持续发移动包，
**纯原版当轮立刻复现**。链路从头到尾都在 vanilla 里：

```
netty 线程收到移动包
 → handleMovePlayer:806  PacketUtils.ensureRunningOnSameThread
 → PacketUtils:18        「不在服务端线程」→ engine.execute(λ)
 → BlockableEventLoop:94 execute() 走「内联 r.run()」分支
      （MinecraftServer:1287 覆写成 super.scheduleExecutables() && !isStopped()）
 → λ 里 p.handle(l) 又回到开头 → 无限递归 → SO
```

`stopped = true` 在 `runServer()` 里**早于** `stopServer()`，而 `Saving players`
在 `stopServer()` 里 —— 这段「已标记停止、连接还没断」的窗口是**原版自带**的（实测约 1s）。
窗口内只要有一个移动包落在 netty 线程就必炸。模组在场只是让 `saveAll()` 多存一个同伴、
多收一个服务，窗口略长 → **撞上的概率上升**，不是因果。

**不影响存档**（SO 在 netty 线程，`saveAll()` 在服务端线程，各走各的；`/stop` 照样正常退出），
症状只是退出瞬间客户端弹一次。所以先不动；真要消掉只能改 vanilla（给 `PacketUtils`
挂 mixin），那是另一个决定。

## 四、你自己在游戏里怎么试

客户端 `mods/` 里已经是修好的 jar，直接启动进游戏就行（单人开作弊，或进有 OP 的服）。

> ⚠️ **先说清现状**：权限层现在**只有命令半边**是活的 —— 你能查、能设、能落盘，
> 但**还没有东西会真的去触发拦截/征询**。因为 `Gate`（行为许可闸）还没接到工具上，
> 那是下一阶段（Stage 2）的活。所以 `consent` 的聊天按钮现在**点了也不会有东西弹出来**。

```bash
# 0) 召唤一个同伴（以下命令都要 2 级权限，单人=开作弊）
/numen summon Alice
/numen list
/numen status Alice

# 1) 跟它说话（唯一说话通道）
/numen tell Alice 你好

# 2) 权限三档：ask=每次问 / bypass=直接放行 / observe=只旁观不动手
/numen permission mode Alice            # 不带模式 = 查当前值
/numen permission mode Alice observe

# 3) 规则表：三张（deny / ask / allow），规则是文本表达式
/numen permission rules list
/numen permission rules add deny "break(placed & !contents)"
/numen permission rules remove deny 1
/numen permission rules reset

# 4) 遣散
/numen dismiss Alice
```

**看落盘**：退出世界后，`<存档>/data/numen_permissions_<主人UUID>.dat`
（gzip 过的 NBT，普通 NBT 查看器能开）。

**看征询 UI**（现在还没东西触发，等 Stage 2 接上）：同伴真要动手时会用
ClickEvent 按钮在聊天里问，按钮背后是 `/numen consent allow|remember|deny <id>`。

## 五、接下来

按已批准的 A 计划，下一阶段是 **Stage 2**：把 `Gate` 接到工具/动作上，
让权限判断和游戏内征询按钮真正跑起来（连带把 `GateTerrain` 那条路径可行性闸也补上）。
