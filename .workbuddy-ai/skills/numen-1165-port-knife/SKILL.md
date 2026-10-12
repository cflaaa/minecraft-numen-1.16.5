---
name: numen-1165-port-knife
description: 在「我的世界」项目里推进 Numen 模组 1.20.1→1.16.5 的归位移植（一「刀」一批）。适用于用户说「按计划继续移植」「执行刀 1a/1b」「搬 sdk/task/program」「接着归位」这类要求，也适用于用户问「计划是什么」「现在方向对不对」「要不要搬某块」——这类问题一律先读决策台账再答。
agent_created: true
---

# Numen 1.16.5 归位移植：一刀的执行流程

## 项目位置
- 仓库：`C:\Users\cfl\workbuddy-ai\我的世界`（工作区根 = git 根）
- 移植版：`numen-1.16.5/`；上游对照：`ref/numen-1.20.1/`
- 模块：`lua`/`agent`/`ai`（逐字搬，不依赖 MC）、`pathing`（MC 侧寻路）、`core`（1.16.5 适配层）

## 第 0 步（不可跳过）：先读决策台账

**任何「计划 / 方向 / 要不要搬某块」的讨论，先读 `numen-1.16.5/docs/DECISIONS.md`**，
不要靠回忆或翻历史对话。它是用户已拍板事项的唯一出处（长期方针 D-01~D-04、边界表、待拍板 Q-01~Q-05）。

配套：
- 当前执行计划 → `docs/RESTORE_PLAN_V3.md`（自包含；方案乙）
- 技术细节/踩坑 → `docs/PORTING.md`（引用写 §号）
- 结构差异实测 → `docs/UPSTREAM_COMPARISON.md`

**长期方针速记**（细节以 DECISIONS.md 为准）：
- 默认：没明确要求就**按上游的功能与结构移植**，不自创结构、不删结构。
- 已明确要求：**大脑跑在服务端**（唯一被批准的大架构例外）。
- **UI 允许与上游不同，但功能必须齐全**（UI 最后考虑）。
- 推进顺序：**先自己写一版跑通 → 全部功能完善后回头对齐上游**；自写子集是临时跳板。

## 第 1 步：确认这一刀的范围与规模

⚠️ **规模数字一律以「脚本干跑 + `git status` 数」为准，不许估**（本项目估过两次都错，误差 24%~35%）。

搬运脚本：`.workbuddy-ai/tmp/port-sdk-task.py`
- 一条命令重放（拷文件 + 改包名 + 改跨包 import，约 1 秒）
- 干跑一次后 `git status --short | wc -l` 数文件、`git diff --stat` 数行
- `program/` 传输半边 8 文件在排除名单；`network.*` **故意不改包名**（让编译错误直接指出「这层没有」）

## 第 2 步：机械搬运 → 编译 → 逐个修

```bash
cd "C:/Users/cfl/workbuddy-ai/我的世界/numen-1.16.5"
python ../.workbuddy-ai/tmp/port-sdk-task.py
JAVA_HOME=../.toolchain/jdk-17.0.20.1+1 ../.toolchain/gradle-7.6.4/bin/gradle :core:compileJava
```

错误分三类，逐个处理：
1. **MC API 差异**：`BuiltInRegistries`→`Registry`、`UUIDUtil`、`TranslatableContents`→`TranslatableComponent` 等。
   ⚠️ 对照映射用 `docs/mm.py`，但**真要调用的成员必须在真实 jar 上 `javap` 核**（ProGuard 会裁「客户端才走」的成员，只在专用服务端炸，编译期看不出）。
2. **缺件**：补进搬运脚本名单，重放。
3. **接缝**（按 C 类登记，写清理由）：`network/` 不搬带来的 2 处（`ServerPrograms.ownerLeft`），落法=删掉 + 一行注释。

⚠️ **级联可能没到底**：每加一批就编一次，别攒着。

## 第 3 步：统一验收（每刀都走，缺一不可）

1. `:core:build` 绿
2. `:core:test` 全绿（新搬的件该补测的补）
3. **发行 jar 类名逐个比对**：`pathing/build/classes` + `core/build/classes` 的并集 == jar 里的类名集合，**无缺无多**
   （改过 pathing 更要数；loom 0.12 有丢 pathing 类的历史问题，靠 `remapperIsolation.set(true)` 压住）
4. **真机不回归**：用**本机方案**（见下）
5. 文档 + 记忆：`PORTING.md` 记这一刀、`DECISIONS.md` 记新决策、当日日志记过程
6. git 提交（**验证过了才提交，一次一刀**）

## 测试方案（按需选，写死在 MEMORY.md）

1. **本机（默认）**：`.testserver/` Fabric 1.16.5 端口 **25565** + 本机客户端。
   驱动 `_perm_live_common.py`，用例在 `.workbuddy-ai/tmp/`。
   **有服务端控制台 stdin + MCP 端口 8766** → **凡要召唤/驱动同伴、要控制台、要受控地形、要测新 jar，只能用本机方案。**
2. **NAS 192.168.1.149**：容器 `mc1165-test` 端口 **25586**。
   ⚠️ RCON 关、MCP 未发布、容器平时 down。只用于验证真实部署 / 跨机连接。

## 移植期高频坑（详见 PORTING.md，别重新踩）

- **mixin 配置的 `package` 独占**：该包下只许有 mixin 类，否则服务端起不来。
- **core 单测跨到 pathing 带 MC 类型会 `NoSuchMethodError`**：已在 `core/build.gradle` 给 Test 加 `doFirst` 前置 named 输出（Loom 0.12.5 无 `namedElements`）。新写这类测试别以为是自己的错。
- **`GateTerrain` 平时碰不到**：`RouteSpec.defaults()` 是 dig=false/place=false，`CostModel` 直接早退。真机测挖/垫三前提：场地密闭 + `/fill` 在世界高度内(0..255) + 目标埋脚下。
- **原版不许把方块放进玩家自己身体里**：测试别点同伴脚下那格。
- 探针脚本取坐标用 `math.floor`，**别用 `int()`**。
- 本机 `http_proxy` 代理 localhost → Python 用 `ProxyHandler({})`，curl 用 `--noproxy '*'`。
- **批量替换脚本会误伤注释**：`buf.xxx(` / `JsonParser.parseString(` 这类替换会连 **javadoc 注释里的示例文字**
  一起改，把说明写废；还会给同包文件加多余 import。**每次批量替换后 `git diff` 只该有新搬进来的文件**，
  既有文件（`NumenCoreFabric`/`NumenTools`/`McpConfig` 这类）出现改动一律先怀疑脚本，`git checkout HEAD --` 还原。
- **搬运脚本会盖掉本仓改好的文件**：往 `DIRS` 里加新目录前，先想清楚该目录里有没有本仓已适配过的文件
  （踩过：`mixin/BlockItemPlaceMixin`）。有就加进 `EXISTING` 白名单。
- 跑 `ssh-run.py` 要用带 paramiko 的 python：`C:/Users/cfl/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe`。

## 每刀做完必须做

- 更新 `docs/PORTING.md`（新增一节，写清这一刀搬了什么、改了什么、验证结果）
- 若产生了新的用户决策 → **追加**到 `docs/DECISIONS.md`（作废的标 ❌ 保留，别删）
- 追加当日日志 `.workbuddy-ai/memory/YYYY-MM-DD.md`
- git 提交（一次一刀）
