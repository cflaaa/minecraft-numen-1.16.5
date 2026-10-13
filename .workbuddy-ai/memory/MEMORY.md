# 我的世界 — 项目长期笔记

## 项目
Numen 模组 **1.20.1 → 1.16.5 Fabric** 移植。仓库 `numen-1.16.5/`，上游对照 `ref/numen-1.20.1/`。
分层：`lua`/`agent`/`ai` 逐字照搬（不依赖 MC）；`pathing` MC 侧寻路；`core` 1.16.5 适配层。

## ⭐ 先读这三份（别再去翻海量上下文找决策）
- **`numen-1.16.5/docs/STATUS.md`** —— **状态台账（活文档）**：逐刀进度 + 真机验收台账。**用户拍板（2026-10-12）：每次真机测试完成后必须及时更新它并提交，不等攒批。**
- **`numen-1.16.5/docs/DECISIONS.md`** —— **决策台账**，用户已拍板事项的唯一出处。讨论计划/方向前**先读它**。
- **`numen-1.16.5/docs/RESTORE_PLAN_V3.md`** —— 当前执行计划（自包含）。
  ⚠️ 其中「方案乙」已被证伪 → 实际走**方案甲**（`program/` 22 + `network/` 33 全搬，接缝归零）。见 DECISIONS §四、PORTING §17.9。
- **`numen-1.16.5/docs/PORTING.md`** —— 技术细节与全部踩坑（引用写 §号）。
- 另：`UPSTREAM_COMPARISON.md`（结构审计）、`RESTORE_PLAN.md`(v1)/`_V2.md`(v2) 为历史稿。

## 构建
`.toolchain/` 在**仓库根**（`我的世界/.toolchain/`），不在 `numen-1.16.5/` 里；gradle 要在 `numen-1.16.5/` 下跑：
`JAVA_HOME="../.toolchain/jdk-17.0.20.1+1" ../.toolchain/gradle-7.6.4/bin/gradle :core:build`
（Java 17 + 官方 Mojang 映射，勿改 Yarn）。报 `Could not download` 是 maven.fabricmc.net 抖动，重跑即可。
**jar 类数基线随刀更新并记入 STATUS.md**（当前 620 = core 385 + pathing 235）。

## 硬约束（详见 PORTING.md）
- **发行 jar 丢 pathing 类**：loom 0.12 跨项目共享 TinyRemapperService 所致，已在 core+pathing 都加 `remapperIsolation.set(true)`。改完 pathing 要 clean 重建 + **逐个比对 jar 类名**（曾实测 346 = pathing 235 + core 111，数字随代码变）。
- 1.16.5 无 slf4j → 内嵌 slf4j-api 1.7.36 + log4j-slf4j-impl 2.8.1；gson 2.8.0 → `JsonCompat` 24 处改写。**勿内嵌新版 gson**（双类加载器炸 `LootJsonParser`）。
- `fabric.mod.json` 的 Fabric API 依赖写 `"fabric": "*"`。
- **mixin 配置的 `package` 独占**：该包下只许有 mixin 类，否则服务端起不来。`compatibilityLevel` 必须 JAVA_17。「没崩」≠「生效」，要看到 `Mixing ... into ...`。
- 查 API 用 `docs/mm.py`，但**真要调用的成员一律在真实 jar 上 `javap` 核**（ProGuard 裁客户端成员，只在专用服务端炸）。`server-api-check.py` 兜底。
- **core 单测跨到 pathing 且带 MC 类型会 `NoSuchMethodError`**：已在 `core/build.gradle` 给 Test 加 `doFirst { classpath = files(project(':pathing').sourceSets.main.output) + classpath }`（Loom 0.12.5 无 `namedElements`）。
- 探针脚本取坐标用 `math.floor`，**别用 `int()`**（负坐标截断偏一格）。
- **`GateTerrain` 平时碰不到**：`RouteSpec.defaults()` 是 dig=false/place=false，`CostModel` 直接早退；只有 `Diagnosis.exhausted` 才逐格问它。真机测挖/垫三前提：场地密闭 + `/fill` 在世界高度内(0..255) + 目标埋脚下（§17）。
- **原版不许把方块放进玩家自己身体里**：测试别点同伴脚下那格（`BlockItem.place` 必返 FAIL，§16.3）。

## 已落地大件
- **permission/ 17 件套**（Gate/Rule/PlacedBlocks/PermissionStore/ConsentDesk）+ `/numen permission|consent`，55 单测全绿；**Gate 已接进 `mine`/`use_block`/`drop` + Lua**（ASK 抛 `NEEDS_CONSENT`），真机 5/5（§14.2、§16）。
- **`core/nav/GateTerrain` 接进寻路**（`CompanionPorts.TERRAIN`），真机 A/B/C 全过（§17）。
- pathing：55 条编译差异全修（§10.1）；`Sight.march` 手搬 DDA，改动须重跑 20000 射线比对。
- MCP 外接大脑 `core/mcp/` 10 工具，`/numen tell` 唯一说话通道，`mcp-drive.py` 驱动。
- 离线多人修复、G 面板自绘（`-Dnumen.panel=` + `verify-panel.py`）、默认预设 NVIDIA NIM、`ModelCatalog`。

## 测试（两方案，按需选）
1. **本机（默认，绝大多数情况）**：`.testserver/` Fabric 1.16.5 端口 **25565** + 本机客户端；**服务端控制台 stdin 可用**、**MCP 端口 8766** 可直连。驱动 `_perm_live_common.py`，用例在 `.workbuddy-ai/tmp/`（`perm-e2e.py`/`nav-gate-e2e.py`/`mod-load-check.py`/`vanilla-client-e2e.py`）。
   → **凡要召唤/驱动同伴、要控制台、要受控地形、要测新 jar，只能用本机方案。**
2. **NAS 192.168.1.149**：容器 `mc1165-test` 端口 **25586**，online-mode=false，数据 `/vol1/1000/docker/mc1165-test/data`。部署 `ssh-run.py` + `tmp/deploy-mc.py`。
   ⚠️ **RCON 关着、MCP 端口没发布、容器平时是 down 的**（先 `cd 该目录 && sudo docker compose up -d`）。镜像必须 `docker.m.daocloud.io`（`docker.mslmc.cn` 不代理 itzg）。
   → 只用于验证真实部署 / 跨机连接。

> ⚠️ 跑 `ssh-run.py` 要用**带 paramiko 的那个 python**：`C:/Users/cfl/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`（managed 3.13.12 里没装）。

## 环境坑
- 本机 `http_proxy` 会代理 localhost → Python 用 `ProxyHandler({})`，curl 用 `--noproxy '*'`。
- 后台服务端用前台 + `run_in_background` 保活；验证客户端换 gameDir（别动用户开着的游戏，PCL 占 `latest.log`）。
- 日志占位符是 slf4j 的 `{}`，不是 `%s`。

## git
仓库根 = 工作区根（`user.name=cfl`）。忽略 `.toolchain/`、`.testserver/`、`ref/`、`**/build/`、`logs/`、`ssh-run.py`、`.workbuddy-ai/tmp/`。大改动优先 git 回退；**验证过了才提交，一次一刀**。

## ⚠️ 硬教训：`.gitignore` 的 `**/build/` 会误伤源码包（2026-10-13）

源码树里有**真叫 `build` 的包**（`com.dwinovo.numen.core.build`、`core.task.build`）。
`**/build/` 会把它们连坐忽略 → **从未入库** → 工作树一丢就找不回。
已加 `!**/src/**/build/` 放行。**凡加通配忽略，先确认源码树里没有同名目录。**

**恢复手法（已沉淀）**：源码丢了但**已部署的 jar** 还在时——
`jar xf` 抽 class → `tiny-remapper`(intermediary→named，带 ASM 组 classpath) → CFR 反编译 →
按 `mappings.tiny` 文本重映射残留 `method_*/field_*`。比重新适配上游快且准。

## ⭐ git 保存方式（2026-10-13 事故后定稿，细则看 `numen-1.16.5/docs/GIT_SAFETY.md`）

**背景**：一次 `git rm` 后 `core/src` 379 文件被整体删除（诱因不明）；其中 21 个文件因 `.gitignore`
的 `**/build/` 误伤源码里真叫 build 的包而**从未入库**，只能反编译已部署 jar 救回。

**规矩（按优先级）**：
1. **动很多文件前先快照**：`python tools/git-snapshot.py`（历史 bundle + 工作树原样，备份到仓库外
   `../我的世界-backup/`）。适用于 `git rm/clean/reset`、批量改写/搬运脚本、反编译流水线。
2. **一刀内编译一绿就提 `WIP:` 提交**；收尾 `git reset --soft <刀起点>` 压成一个刀提交
   —— 中间状态有备份，最终历史仍"一次一刀"。（原"验证过了才提交"被误用成攒一大批，是这次差点全丢的主因。）
3. **改 `.gitignore` 后必跑 `python tools/git-audit.py`**；`pre-commit` 钩子已装，SUSPECT 直接拦提交。
4. **模型中断回来后先体检再继续**：`git status` + `tools/git-audit.py`。
5. **恢复路径**：工作树被删 → `git restore --worktree -- <路径>`；误 reset → `git reflog`；
   全毁+有未提交 → 解开快照里的 tarball + bundle。
6. **可复用脚本进 `tools/`（入库）**，一次性脚本才留 `.workbuddy-ai/tmp/`。
7. 钩子不入库，换机器重跑 `python tools/install-hooks.py`。
