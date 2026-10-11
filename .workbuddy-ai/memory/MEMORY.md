# 我的世界 — 项目长期笔记

## 项目：Numen 1.16.5 移植版（`numen-1.16.5/`，上游对照 `ref/numen-1.20.1/`）

分层：`ai/`（LLM 连接）、`agent/`（大脑内核）、`lua/`（沙箱）不依赖 MC、逐字照搬上游；
`core/` 是 1.16.5 适配层。与 MC 版本无关的逻辑不进 `core/`。
详细移植记录见 `docs/PORTING.md` 与每日日志（`.workbuddy-ai/memory/`）。

### 构建

- 工具链在工作区根：`JAVA_HOME=.toolchain/jdk-17.0.20.1+1` + `.toolchain/gradle-7.6.4/bin/gradle :core:build`。Java 17 + 官方 Mojang 映射（勿改 Yarn）。
- `maven.fabricmc.net` 是抖动不是被墙：aliyun 仓库排最前，报 `Could not download` 就重跑。
- ⚠️ 发行 jar 丢 pathing 类已定案（2026-10-11）：真凶是 **loom 0.12 跨项目共享 TinyRemapperService**（服务 id 不含项目路径）——core 平铺的 pathing 类与 compileClasspath 上的 pathing dev jar 被两个 prepareRemapJar 并发各读一次 input，duplicate-input 去重后 InputTag 归属不稳，core 的 apply(tag) 把 pathing 类筛光。**已修**：core 与 pathing 的 build.gradle 都加 `remapperIsolation.set(true)`。保险起见改完 pathing 仍要 clean 重建并数类（应 243 个，总类 339）。pathing 平铺写法定死不变：`dependsOn ':pathing:classes'` 字符串惰性 + `from({ ... })` 闭包。

### 硬约束（详见 docs/PORTING.md）

- `fabric.mod.json` 的 Fabric API 依赖写 `"fabric": "*"`；同文本报错另有「mod 被 java 谓词剔除」成因，两条都要查。
- 1.16.5 无 slf4j → 内嵌 slf4j-api 1.7.36 + log4j-slf4j-impl 2.8.1；gson 2.8.0 → 24 处兼容改写 + `JsonCompat`。**勿内嵌新版 gson**（双类加载器会炸 `LootJsonParser`）。
- 查 API 用 `docs/mm.py`（左可读右混淆；查不出修饰符；会列出被 ProGuard 裁掉的成员）→ 真要调用的成员一律在真实 jar 上 `javap` 核一遍。
- ProGuard 会裁「客户端才走」的成员，**只在专用服务端炸**（编译期看不出）；`server-api-check.py` 已覆盖 pathing 并处理 javap 折行。
- mixin：客户端类进 `core.client.mixin` + mixins.json `client` 数组；`compatibilityLevel` 必须 JAVA_17；「没崩」≠「生效」，要看到 `Mixing ... into ...` 日志。
- ⚠️ **mixin 配置的 `package` 是独占的**：`package` 指到的包下面**只许有 mixin 类**，否则宿主类一加载就 `IllegalClassLoadError: … is in a defined mixin package`，**服务端起不来**。
- ⚠️ **原版不许把方块放进玩家自己身体里**（`CollisionGetter.isUnobstructed` 把 `null` 当「谁放的」，不排除放置者）。写测试/脚本时别点同伴**脚下**那一格：`placeAt = hit.getBlockPos().relative(hit.getDirection())` 只要等于她自己那一格，`BlockItem.place` 必返 `FAIL`。详见 PORTING.md §16.3。
- 本机探针脚本取坐标一律 `math.floor`，**别用 `int()`**（截断，负坐标偏一格 → 会白跑几轮对照）。

### 已落地大件

- **归位阶段 1（A 计划，2026-10-11）：permission/ 17 件套**——行为许可层（Gate 三层 owner 表 + 工厂表、Rule 解析器、PlacedBlocks 账本、PermissionStore、ConsentDesk 征询）+ `/numen permission|consent` 命令树 + 55 条单元测试全绿。征询 UI 暂走聊天按钮（RUN_COMMAND），network/ 落地后换回。适配表见 PORTING.md §14.2。真机 smoke-test 实测通过。
- **归位阶段 2（2026-10-11）：Gate 接进 `mine`/`use_block`/`drop`**——ASK 时把这次调用**停在原地**发征询、答复后带回同一次调用重跑；Lua 的 `numen.inv.drop`/`numen.use.block` 同步过闸（ASK 抛 `NEEDS_CONSENT`，失败方向是关）；放置账本 mixin `BlockItemPlaceMixin` → `PlacedBlocks`。**真机 5/5 全通过**（放行/要问/放置账本/记住/拒绝），见 PORTING.md §16。
- 离线多人修复：`core/client/NumenOfflineMultiplayer` + mixin（PORTING.md §8）。
- G 面板：自绘界面，热区靠绘制时记 `hits`；布局验证用 `-Dnumen.panel=...` + `verify-panel.py`。
- pathing 寻路：55 条编译差异全修完（§10.1 对照表）；`Sight.march` 是手搬 DDA，改动须重跑 20000 射线比对；已真机跑通（绕坑验证）。⏭ 未做：`GateTerrain`（路径可行性闸，已随 permission/ 落地的是行为许可 Gate，两回事）、`nav/` 其余件；默认 `dig=false place=false`。
- MCP 外接大脑：`core/mcp/`，10 个工具，`/numen tell` 是唯一说话通道；`mcp-drive.py` 驱动。
- 默认 API 预设 NVIDIA NIM；「获取模型」= `ModelCatalog` + `ModelCatalogState`（异步结果必须进轮询快照，不能只回一条消息）。

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
