# 我的世界 — 项目长期笔记

## 项目：Numen 1.16.5 移植版（`numen-1.16.5/`，上游对照 `ref/numen-1.20.1/`）

分层：`ai/`（LLM 连接）、`agent/`（大脑内核）、`lua/`（沙箱）不依赖 MC、逐字照搬上游；
`core/` 是 1.16.5 适配层。与 MC 版本无关的逻辑不进 `core/`。
详细移植记录见 `docs/PORTING.md` 与每日日志（`.workbuddy-ai/memory/`）。

### 构建

- 工具链在工作区根：`JAVA_HOME=.toolchain/jdk-17.0.20.1+1` + `.toolchain/gradle-7.6.4/bin/gradle :core:build`。Java 17 + 官方 Mojang 映射（勿改 Yarn）。
- `maven.fabricmc.net` 是抖动不是被墙：aliyun 仓库排最前，报 `Could not download` 就重跑。
- ⚠️ 发行 jar 丢 pathing 类已定案（2026-10-11）：真凶是 **loom 0.12 跨项目共享 TinyRemapperService**（服务 id 不含项目路径）——core 平铺的 pathing 类与 compileClasspath 上的 pathing dev jar 被两个 prepareRemapJar 并发各读一次 input，duplicate-input 去重后 InputTag 归属不稳，core 的 apply(tag) 把 pathing 类筛光。**已修**：core 与 pathing 的 build.gradle 都加 `remapperIsolation.set(true)`。保险起见改完 pathing 仍要 clean 重建，并**把 jar 里的类名集合与 `pathing/build/classes`+`core/build/classes` 逐个比对**（2026-10-11 实测 346 = pathing 235 + core 111，无缺无多；别记死数字，会随代码变）。pathing 平铺写法定死不变：`dependsOn ':pathing:classes'` 字符串惰性 + `from({ ... })` 闭包。

### 硬约束（详见 docs/PORTING.md）

- `fabric.mod.json` 的 Fabric API 依赖写 `"fabric": "*"`；同文本报错另有「mod 被 java 谓词剔除」成因，两条都要查。
- 1.16.5 无 slf4j → 内嵌 slf4j-api 1.7.36 + log4j-slf4j-impl 2.8.1；gson 2.8.0 → 24 处兼容改写 + `JsonCompat`。**勿内嵌新版 gson**（双类加载器会炸 `LootJsonParser`）。
- 查 API 用 `docs/mm.py`（左可读右混淆；查不出修饰符；会列出被 ProGuard 裁掉的成员）→ 真要调用的成员一律在真实 jar 上 `javap` 核一遍。
- ProGuard 会裁「客户端才走」的成员，**只在专用服务端炸**（编译期看不出）；`server-api-check.py` 已覆盖 pathing 并处理 javap 折行。
- mixin：客户端类进 `core.client.mixin` + mixins.json `client` 数组；`compatibilityLevel` 必须 JAVA_17；「没崩」≠「生效」，要看到 `Mixing ... into ...` 日志。
- ⚠️ **mixin 配置的 `package` 是独占的**：`package` 指到的包下面**只许有 mixin 类**，否则宿主类一加载就 `IllegalClassLoadError: … is in a defined mixin package`，**服务端起不来**。
- ⚠️ **原版不许把方块放进玩家自己身体里**（`CollisionGetter.isUnobstructed` 把 `null` 当「谁放的」，不排除放置者）。写测试/脚本时别点同伴**脚下**那一格：`placeAt = hit.getBlockPos().relative(hit.getDirection())` 只要等于她自己那一格，`BlockItem.place` 必返 `FAIL`。详见 PORTING.md §16.3。
- 本机探针脚本取坐标一律 `math.floor`，**别用 `int()`**（截断，负坐标偏一格 → 会白跑几轮对照）。
- ⚠️ **core 的单元测试跨到 pathing 且带 MC 类型时会 `NoSuchMethodError`**：`testRuntimeClasspath` 上 `implementation project(':pathing')` 解析到的是 pathing 的 **remapJar（intermediary，`class_2248`）**，而编译期是 **named**（`net.minecraft.world.level.block.Block`）。已在 `core/build.gradle` 给 `Test` 任务加 `doFirst { classpath = files(project(':pathing').sourceSets.main.output) + classpath }` 兜底（Loom 0.12.5 无 `namedElements`）。新写这类测试别以为是自己的错。详见 PORTING.md §17.4。
- ⚠️ **`GateTerrain` 平时碰不到**：`RouteSpec.defaults()` 是 `dig=false/place=false`，而 `CostModel.admitDig/admitPlace` 在 `!spec.dig()` 时**直接早退**，不问 `TerrainPolicy`。只有搜索失败时 `Diagnosis.exhausted` 用 `spec.edit().changes(true)` 再搜一次才逐格问它（诊断只报不执行）。详见 PORTING.md §17.2。
- ⚠️ **真机用「埋目标」逼出挖/垫路径时，三件事必须先确认**：①场地**密闭**（否则可达格无限 → 搜索 `停因 BUDGET` → `Diagnosis.of` 直接 `OutOfBudget` 早退，GateTerrain 白测）；②`/fill` 在**世界高度内**（1.16.5 主世界 0..255，越界**静默失败**）；③目标埋在**脚下**（向下挖不用给脑袋留空间）。搭完用 `/execute if block …` 自检场地。详见 PORTING.md §17.3。

### 已落地大件

- **归位阶段 1（A 计划，2026-10-11）：permission/ 17 件套**——行为许可层（Gate 三层 owner 表 + 工厂表、Rule 解析器、PlacedBlocks 账本、PermissionStore、ConsentDesk 征询）+ `/numen permission|consent` 命令树 + 55 条单元测试全绿。征询 UI 暂走聊天按钮（RUN_COMMAND），network/ 落地后换回。适配表见 PORTING.md §14.2。真机 smoke-test 实测通过。
- **归位阶段 2（2026-10-11）：Gate 接进 `mine`/`use_block`/`drop`**——ASK 时把这次调用**停在原地**发征询、答复后带回同一次调用重跑；Lua 的 `numen.inv.drop`/`numen.use.block` 同步过闸（ASK 抛 `NEEDS_CONSENT`，失败方向是关）；放置账本 mixin `BlockItemPlaceMixin` → `PlacedBlocks`。**真机 5/5 全通过**（放行/要问/放置账本/记住/拒绝），见 PORTING.md §16。
- 离线多人修复：`core/client/NumenOfflineMultiplayer` + mixin（PORTING.md §8）。
- G 面板：自绘界面，热区靠绘制时记 `hits`；布局验证用 `-Dnumen.panel=...` + `verify-panel.py`。
- **归位阶段 2 收尾（2026-10-11）：`GateTerrain` 接进寻路**——新增 `core/nav/GateTerrain.java`（`record GateTerrain(Gate) implements TerrainPolicy`，照上游搬），`CompanionPorts` 的 `TERRAIN=ALLOW_ALL` 换成 `new GateTerrain(Permission.gateFor(player))`；新增 `GateTerrainTest` 5 条，`core:test` 60 条全绿。真机 A/B/C 全过（A 不加规则=「要改 3 格地形」/ B `deny break(minecraft:stone)`=「按现在的方式走不过去」**决定性** / C 去掉规则又回到 A），见 PORTING.md §17。⚠️ 但 `RouteSpec` 目前只有 `defaults()` 一个出口，**真实行走里仍不会问到它**——等挖/垫的路线规格接出来才真正上工。
- pathing 寻路：55 条编译差异全修完（§10.1 对照表）；`Sight.march` 是手搬 DDA，改动须重跑 20000 射线比对；已真机跑通（绕坑验证）。⏭ 未做：`nav/` 其余件；默认 `dig=false place=false`。
- MCP 外接大脑：`core/mcp/`，10 个工具，`/numen tell` 是唯一说话通道；`mcp-drive.py` 驱动。
- 默认 API 预设 NVIDIA NIM；「获取模型」= `ModelCatalog` + `ModelCatalogState`（异步结果必须进轮询快照，不能只回一条消息）。

### 部署目标（2026-10-11 定）

**A 计划 + 外接大脑**（面向**多人联机服务器**）。依据（都在代码里核过）：

- 核心全在服务端（同伴=服务端假玩家、大脑在服务端、征询走聊天），`fabric.mod.json` **无 `custom` 载荷**
  → **vanilla 客户端能连、玩家不用装 mod**（客户端只剩 G 面板 + 离线账号解禁）。
- `config/numen.json` 是**服务端全局配置，里面就是 API key**（`NumenClientPrefs` 注释明说；客户端那份
  只存 UI 偏好，另存 `config/numen_client.txt`）。
- 外接大脑：`core/mcp/` 的 HTTP 服务端（JDK `HttpServer`）**在 MC 服务端里**，LLM 循环在**服外**；
  外脑驱动期间内置大脑停牌（`McpMode.driving()`）→ 服务端不存 key、不扛推理算力。
- 权限层（Gate/PermissionStore/PlacedBlocks）全在服务端、按 (主人, 同伴) 记账 → 服主集中管。

⚠️ **优先项（未做）**：`program/ProgramLimits`（并发数 + **每刻主线程预算**：每位主人 8 段 / 全服 64 段 /
每刻 10ms·5ms）**没搬**，而内置大脑跑在**服务端主线程**（`CompanionBrain` 注释：「`AgentLoop` 的所有
状态只在一个线程上读写，在 Minecraft 里那个线程就是服务端主线程」；HTTP 是 `sendAsync` 不占主线程，
但 JSON 解析 / 工具派发 / Lua 执行都在主线程）→ 多人多同伴会掉刻。**做多人服必须先补这条闸。**

由于选了外接大脑，`program/`、`network/`、客户端大脑**永久不在归位范围内**——不是欠账，是这套架构
有意不要的一层（RESTORE_PLAN.md 的「🔴 要你拍板」已标为 ✅ 已拍板）。详见 PORTING.md §17.7。

### 测试方案（两种，按需选；2026-10-11 定）

**1. 本机（默认，绝大多数情况用这个）**
`.testserver/` 专用 Fabric 1.16.5 服务端（端口 **25565**）+ 本机客户端，全在本机。
公共驱动 `_perm_live_common.py`（起服/起客户端/MCP/日志等待），用例在 `.workbuddy-ai/tmp/`：
`perm-e2e.py`（权限层）、`nav-gate-e2e.py`（GateTerrain）、`mod-load-check.py`（元数据加载）、
`vanilla-client-e2e.py`（纯原版客户端）。**服务端控制台 stdin 可用**（`/execute as … run …`），
MCP 端口 **8766** 可直连 → 能召唤/驱动同伴。
→ **用**：需要服务端控制台或 MCP、需要受控地形/新世界、要测刚编出来的 jar、要快速迭代。

**2. NAS 192.168.1.149（跨机 / 真部署）**
容器 `mc1165-test`（itzg，Fabric 1.16.5，宿主端口 **25586**，online-mode=false），
数据 `/vol1/1000/docker/mc1165-test/data`。部署/更新：`ssh-run.py` + `.workbuddy-ai/tmp/deploy-mc.py`
（上传 compose+mods、pull、compose up）。本机客户端连 `192.168.1.149:25586`。
⚠️ **RCON 关着、MCP 端口没发布** → 没有服务端控制台、不能用 MCP 驱动同伴。
⚠️ **容器平时是 down 的**（`docker ps -a` 里连记录都没有），要用得先 `cd 该目录 && sudo docker compose up -d`。
⚠️ 镜像必须用 `docker.m.daocloud.io`（`docker.mslmc.cn` **不代理 itzg**）——`deploy-mc.py` 原先写错，已改。
→ **用**：验证真实部署 / 跨机连接、长时间挂着、不想占本机资源。

> ⚠️ **跑 `ssh-run.py` 要用带 paramiko 的那个 python**：
> `C:/Users/cfl/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`
> （managed 3.13.12 里没装 paramiko，直接跑会 `ModuleNotFoundError`）。

### 环境坑

- 本机 `http_proxy` 会代理 localhost → Python 用 `ProxyHandler({})`，curl 用 `--noproxy '*'`。
- 后台服务端用前台 + `run_in_background` 保活；验证客户端用 `verify-offline-mp.py` 换 gameDir（别动用户开着的游戏，PCL 占着 latest.log）。
- 日志占位符是 slf4j 的 `{}`，不是 `%s`。

### 测试服（NAS docker，2026-10-11 部署）

- NAS：192.168.1.149（fnOS，主机名 fn）。SSH 用工作区 `ssh-run.py`（paramiko，密码在脚本里）；docker 需要 sudo。
- 容器 `mc1165-test`：`itzg/minecraft-server:java17`（镜像走 `docker.m.daocloud.io`，docker.mslmc.cn 不代理 itzg），Fabric 1.16.5 + loader 0.14.25，**online-mode=false**，内存 2G，宿主端口 **25586**（25565-25585 被 mslx-daemon 占用）。
- 数据目录 `/vol1/1000/docker/mc1165-test/data`（compose 文件在其上层）。mods：fabric-api 0.42.0+1.16 bundled jar（本地 gradle 缓存自组装，脚本 `.workbuddy-ai/tmp/build-fabric-api-bundle.py`，Maven 上是壳、Modrinth/GitHub 连不上）+ Numen core jar。更新模组：SFTP 上传后 `docker compose restart`。

### git

仓库根=工作区根（初始提交 `12d6176`，仓库级身份 `user.name=cfl`）。大改动优先 git 回退：`checkout -- <路径>` 撤单文件 / `revert` 反向提交 / `reset --hard`（不可恢复）。忽略 `.toolchain/`、`.testserver/`、`ref/`、`**/build/`、`logs/`。
