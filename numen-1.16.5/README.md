# Numen — Minecraft 1.16.5 移植版

把 [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)（言出法随）移植到
**Minecraft 1.16.5 + Fabric**。

Numen 是一个**活在 Minecraft 里的 AI 同伴**：你召唤一个同伴（一具服务端的假玩家），
用自然语言跟它说话，它自己规划、自己走过去、自己动手。

## 前提：本 mod 分为服务端与客户端两侧（2026-10-24 起）

**同一个项目、同一份代码、同一个 mod id（`numen`），两个构建产物**：

- **服务端版**（`Numen-fabric-1.16.5-*.jar`，`environment: "*"`）：跑在 MC 服务器上，
  同伴（服务端假玩家）活在这里。**大脑固定用 pi agent（MCP 外接）**：服务端只暴露内置
  的 MCP 服务端（`config/numen/mcp_server.json`），真正的 LLM 循环跑在 Minecraft 之外的
  **pi（coding agent harness）**里，pi 以 MCP 客户端身份连到这个端点驱动同伴
  （`create_companion`/`get_events`/`lua`/`say`…，见本仓的测试记录仓：驱动方式与验收步骤）。
  内置大脑（服务端直连 LLM）也支持，但本仓的部署形态**默认且推荐外接**。
- **客户端版**（`Numen-Client-fabric-1.16.5-*.jar`，`environment: "client"`）：装在玩家客户端，
  玩家用它与同伴相会：**G 键**打开准星所指（或最近）同伴的背包——原版容器风格，可往里放、
  也可直接拿出；**B 键**打开设置面板；**原版聊天栏**打字直达同伴的 agent，
  而同伴的思考/在做/完成（含用时）会旁白回聊天栏。专用服务器不会加载这份 jar。

原版客户端（不装任何 mod）仍能连、能聊天；客户端版只是把「看得见、摸得着」的那半补齐。

**大脑跑在服务端**（与上游不同：上游的大脑在主人客户端，中间靠 `program/` 传输层连通）。
两种跑法，二选一：

- **内置大脑**：provider 与 API key 配在服务端 `config/numen.json`，服务端自己发起推理；
- **外接大脑**：MC 服务端只暴露一个内置的 MCP 服务端，真正的 LLM 循环跑在 **Minecraft 之外**
  （另一台机器/另一个进程），服务端不存 key、也不扛推理算力。

两种都**不需要玩家装客户端 mod**：同伴是服务端假玩家、征询走聊天按钮，
所以原版客户端连上就该能用（只少了自绘的 G 面板）。

> ✅ **已实测（2026-10-11）**：起专用服务端 + 一个**纯原版客户端**（同一份客户端 jar，
> 把 classpath 上那 3 个 fabric 库摘掉、主类换回 `net.minecraft.client.main.Main`）——
> 连得上、没被踢，而且**收到了同伴的聊天**：客户端自己的日志里有
> `[CHAT] Alice joined the game` 与 `[CHAT] <Alice> …`。服务端侧也没有任何自定义通道告警
> （`NumenPanelServer` 发之前先问 `ServerPlayNetworking.canSend`，没有通道的客户端就不发）。
> 所以「不需要客户端 mod」这条是**验过的**，不是推断。复现：`tools/vanilla-client-e2e.py`。

> 本仓是**非官方移植**。上游源码许可 LGPL-3.0-only。

---

## 现在的状态

| 部分 | 状态 |
|---|---|
| 引擎层 `lua/` `agent/` `ai/`（142 主源码 / 32,144 行） | ✅ **逐字照搬上游**，编译通过，单元测试全绿 |
| 寻路 `pathing/`（117 文件 / 13,329 行） | ✅ 已搬并适配（A* + 地形分析 + 手搬 DDA 视线），真机验证能绕障走通 |
| `core/` Minecraft 适配层（384 主源码 / 51,136 行） | ✅ 已编译；**真机跑通**（专用服务端 + 真实 1.16.5 客户端） |
| 行为许可层 `permission/`（17 文件） | ✅ 已搬：`Gate`（主人三层 + 出厂表）、`Rule` 解析器、`PlacedBlocks` 账本、`ConsentDesk` 征询，外加 `/numen permission` 与 `/numen consent` 命令树 |
| 权限闸接线 | ✅ `mine` / `use_block` / `drop` 过闸（ASK 时把这次调用**停在原地**发征询，答复后带回同一次调用重跑）；Lua 的 `numen.inv.drop` / `numen.use.block` 同步过闸；寻路那条路由 `GateTerrain` 逐格裁决 |
| 外接大脑（MCP） | ✅ `core/mcp/`：JDK `HttpServer` 上手写的 MCP 服务端（JSON-RPC 2.0），外部 agent 可全程驱动同伴；外脑驱动期间内置大脑停牌 |
| `sdk/`（`@Fn` API 框架） | ✅ 已搬（39/39 文件，与上游同名）；`@Fn` 三组（api/task/module）真机验通（help / status） |
| 完整任务集（建造 / 战斗 / 钓鱼 / 开箱 / 合成… 13 族） | ✅ **已搬（刀 4，`aff462f`）**：上游 `core/common` 主体整组归位，`Acts.java` 退役 |
| 工具表 | ✅ 上游 `agent/tool` 全套照搬（ToolRegistry / ScriptTool / SkillTool / TodoTool / MemoryTool），core `ToolRunner` 统一派发：**15 工具**（4 大脑 + 11 世界），MCP `tools/list` **19 个** |
| 世界工具驱动 | 🔵 **刀 5 进行中**：11 个世界工具正从自写 MCP-lua 驱动改成上游 `@Fn` 驱动（`a9a0116`）。真机 11/11 产品侧全过；**剩 7 处「专用服务端潜伏崩溃」待修**（换维度 / 回传聊天 / 爬行者 / 远程瞄准 / 合成 / 建造取物品 / Lua 查注册表，见 `docs/UPSTREAM_COMPARISON.md` §4.4） |
| 客户端界面 | 🟡 G 面板（**G** 键）是**自绘**的，不是上游原版 UI |
| 征询界面 | 🟡 聊天消息 + 可点击按钮（`RUN_COMMAND`）——原版客户端也能点，是 A 计划的**最终形态** |
| 多人服每刻主线程预算 | ❌ 未做——`program/ProgramLimits` 不在 A 计划内，做多人服要另补这条闸 |

**一句话**：引擎 + 适配层 + 寻路 + 权限层 + 工具统一派发（`ToolRunner`）都站在 1.16.5 上了，**真机跑得通**
（专用服务端与真实客户端的端到端测试都验过，见 `docs/PORTING.md` §11 / §16 / §17）；
**13 族任务本体也已归位（刀 4）**。当前在做**刀 5**：把 11 个世界工具从自写驱动换成上游 `@Fn` 驱动，
真机 11/11 已过，剩 7 处「专用服务端才崩」的潜伏问题待修。
再往后是**刀 6**（清理：导航壳 / G 面板 / MCP 版本漂移 / server-api-check / 文档）与
`ui/`、`plugins/` 两个整缺口。**逐刀进度与验收台账见 `docs/STATUS.md`。**

---

## 构建状态

**已编译、已在真机跑通。** 本机（Windows + Temurin JDK 17）实测：

```
# 在 numen-1.16.5/ 下跑；工具链在仓库根 .toolchain/，故用 ../ 前缀
JAVA_HOME=../.toolchain/jdk-17.0.20.1+1 ../.toolchain/gradle-7.6.4/bin/gradle :core:build    ✅ BUILD SUCCESSFUL
  产出 core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar    (≈2.6 MB)
  └─ META-INF/jars/  五件套嵌套 jar 已就位（agent + ai + lua + slf4j-api 1.7.36 + log4j-slf4j-impl 2.8.1）
  └─ 平铺 962 个 class = core 727 + pathing 235
     ⚠️ 改完 pathing 必须 clean 重建并**逐个比对类名**：loom 0.12 跨项目共享 TinyRemapper 会丢 pathing 类，
        已在 core + pathing 加 remapperIsolation.set(true) 压住（脚注见 docs/PORTING.md）

JAVA_HOME=… gradle :core:test    ✅ 全绿（527 单测；最新基线见 docs/STATUS.md）
```

真机验证（专用 Fabric 1.16.5 服务端 + 真实 1.16.5 客户端 + 外接大脑）跑通过：召唤同伴、
寻路走过去、挖方块（**生存模式渐进挖掘也已通**，`docs/PORTING.md` §19）、放置账本生效、
权限征询往返（放行 / 要问 / 记住 / 拒绝）、`GateTerrain` 拒绝挖石头。
另在 **NAS 容器真实部署 + 跨机纯原版客户端（环境 B）全过**。
细节见 `docs/PORTING.md` §11 / §16 / §17 / §18 / §19。

`maven.fabricmc.net` 这个 Cloudflare 域名**时通时不通**（第一次全超时，第二次通）。
解法不是代理，是把**阿里云公共仓排到仓库列表最前面**——loom 那些量大的传递依赖
（asm / guava / jackson / netty）全由它整包接住，Cloudflare 只剩 fabric 独有的几个坐标。
配置已经写进 `settings.gradle` 与 `build.gradle`。

若第一次仍报 `Could not download`，**直接重跑**：Gradle 会保留已下到的部分，
通常第二三次就过（本次是第 3 次拉全 fabric-loom 的）。

---

## 构建

```bash
cd numen-1.16.5
# 工具链（JDK 17 + Gradle 7.6.4）在仓库根 .toolchain/，故用 ../ 前缀。
# Java 17 + 官方 Mojang 映射，勿改 Yarn。
JAVA_HOME=../.toolchain/jdk-17.0.20.1+1 ../.toolchain/gradle-7.6.4/bin/gradle :core:build   # → core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar
JAVA_HOME=… gradle :core:test                                                                  # 527 单测
```

日常验证不用 `runClient`/`runServer`：真机测试服在 `.testserver/`（已部署 Fabric + 本模组），
可复用脚本在仓库根 **`tools/`**（e2e 验收 / NAS 部署 / 映射核对，已入库）。
**动很多文件之前先跑 `python tools/git-snapshot.py`**，规矩见 `docs/GIT_SAFETY.md`。

| 项 | 值 |
|---|---|
| JDK | **17**（引擎层用了 record，压不回 8；理由见 `docs/PORTING.md` 第三节） |
| Gradle | 7.6.4（wrapper 已带） |
| fabric-loom | 0.12.5 |
| Minecraft | 1.16.5 |
| 映射 | `officialMojangMappings()` —— 与上游同一套命名 |
| Fabric Loader | 0.14.25 |
| Fabric API | 0.42.0+1.16 |

> ⚠️ **运行要求 Java 17**。Mojang 给 1.16.5 声明的运行时是 Java 8，所以这一点要在
> 服务器/启动器里显式指定，别用默认的 Java 8。

### ⚠️ 装进启动器后报「不兼容的模组 / 缺 fabric-api」的话，是 JVM 选错了

**症状**（极易误诊）：

```
Immediate reason: [HARD_DEP_NO_CANDIDATE numen ... {depends fabric-api @ [*]}]
确定了一种可能的解决方法：安装 fabric-api，任意版本。
```

**这条报错在骗你。** fabric-api 大概率装得好好的。真正的因果是：

1. 大部分启动器（PCL 等）看到 MC 1.16.5，**自动挑 Java 8**（1.16.5 官方就要求 8）；
2. 本模组 `fabric.mod.json` 里写着 `"java": ">=17"`，在 Java 8 上判定失败；
3. 于是 **numen 自己被 loader 剔除**，`fabric-api` 这条依赖随之无人满足；
4. Fabric 就把「numen 需要 fabric-api」原样报出来 —— 指向了完全错误的模组。

**验证过的事实**（用 loader 自带的 `VersionPredicate` 实测）：

| 启动用 JVM | `numen` 的 `java>=17` | 结果 |
|---|---|---|
| Java 8 (`1.8.0_51`) | **REJECT** | 报「缺 fabric-api」 |
| Java 17 (`17.0.15`) | PASS | 正常加载 |

**修法：把该实例的 Java 显式指向 17。**

- **PCL**：版本设置 → 设置 → 「Java 选择」改到 Java 17；
  或直接写实例的 `versions/<实例名>/PCL/Setup.ini`：
  ```ini
  Java:<你的Java17路径>\bin\javaw.exe
  ```
- **官方启动器**：编辑配置 → 更多选项 → 「Java 可执行文件」指向 17。
- **HMCL**：版本设置 → 「Java 路径」。

> 别去换 fabric-api 版本 —— 本次排查中 fabric-api 的 sha1 与官方发布逐字节相同，
> 自始至终都是好的。

---

## 用法

```
/numen config apiKey sk-xxxxxx          填服务端的 key（内置大脑用）
/numen config model gpt-4o-mini         或 claude-sonnet-4-... 等
/numen config provider openai           openai / anthropic / deepseek / moonshot
/numen config baseUrl https://api.openai.com/v1
/numen config persona 你是一只话少的猫

/numen summon 小满                       召唤
/numen tell 小满 去把前面那棵树砍了        跟她说话
/numen status 小满                       看她此刻在干什么
/numen list                              看看有谁
/numen dismiss 小满                      遣散

/numen permission mode 小满 ask           许可模式：ask / bypass / observe
/numen permission rules list             看主人这一层写了哪些规则
/numen permission rules add deny break(placed)     加一条（deny / ask / allow 三张表）
/numen permission rules remove deny 1    按行号删一条
/numen permission rules reset            清空
/numen consent allow|remember|deny <id>  答复一条征询（也可以直接点聊天里的按钮）
```

配置落在**服务端** `config/numen.json`。**key 只存在这个文件里**，只发给选定的服务商。
用**外接大脑**时打开 `config/numen/mcp_server.json` 的开关，服务端就不再需要 key。

同伴现在会这 **12 个**动作：`say`、`get_status`、`inspect_block`、`scan_blocks`、`goto`、`mine`、
`use_block`、`inventory`、`equip`、`eat`、`drop`、`lua`。
`mine` 走的是原版「玩家挖方块」那条路（`ServerPlayerGameMode.destroyBlock`），
破坏速度、掉落、统计都照玩家来，不是 `setblock`。

挖 / 放 / 丢之前一律先过权限层：**放行**就做；**要问**就把这次调用**停在原地**发一条征询，
主人答复后带回同一次调用重跑；**拒绝**就回一句为什么。

---

## 仓库结构

```
numen-1.16.5/
├── lua/        ← 逐字照搬：Lua 5.2 沙箱虚拟机（纯 JVM，50 文件）
├── agent/      ← 逐字照搬：ReAct 循环内核 + 事件队列 + 工具/技能表（纯 JVM，62 文件）
├── ai/         ← 逐字照搬：LLM 连接层，5 套协议（纯 JVM，30 文件）
├── pathing/    ← 照搬并适配：寻路（A* + 地形分析 + 视线），要对着 MC 编译（117 文件）
├── core/       ← Minecraft 1.16.5 适配层（模组本体，384 文件 / 51136 行）
│   └── src/main/java/com/dwinovo/numen/core/
│       ├── NumenCoreFabric.java    入口
│       ├── entity/                 假玩家身体、工厂、花名册
│       ├── act/                    挖掘/放置/使用执行器（BlockDigger / Interaction 等）
│       ├── agent/                  大脑与工具表
│       ├── nav/                    寻路接入（GateTerrain 逐格裁决；`CompanionNav` 已于刀 5 删除）
│       ├── permission/             行为许可层（Gate / Rule / RuleSet / ConsentDesk / PlacedBlocks…，17 文件）
│       ├── mcp/                    外接大脑（MCP 服务端 + 配置 + 驱动闸）
│       ├── task/                   13 任务族（上游 core/common 归位，刀 4）+ 公共框架
│       │                             base/chain/build/combat/dig/fish/interact/inventory/
│       │                             locate/move/reflex/survival/wait
│       ├── program/ network/        程序管理与传输层（方案甲：22 + 33 全搬，接缝归零）
│       ├── sdk/                    `@Fn` API 框架（照搬上游，39 文件）
│       ├── scan/ route/ blueprint/ area/ init/  combat/  build/  tools/  gear/  platform/
│       └── script/ client/ mixin/ command/ config/ event/ data/ api/ llm/ mc/ net/ work/
└── docs/
    ├── STATUS.md             ← 状态台账（活文档）：逐刀进度 + 真机验收台账 + 基线
    ├── DECISIONS.md          ← 决策台账：用户已拍板事项的唯一出处
    ├── GIT_SAFETY.md         ← 保存规矩：快照 / WIP 提交 / 可见性审计（tools/ 三件套）
    ├── PORTING.md            ← 逐条核过的 1.20.1 → 1.16.5 API 差异表 + 各阶段实录
    ├── RESTORE_PLAN_V3.md    ← 归位计划（当前执行版；⚠️ 其中的「方案乙」已被证伪，实走方案甲）
    ├── UPSTREAM_COMPARISON.md ← 与上游的结构差异对照 + 已知问题清单
    ├── RESTORE_PLAN.md / _V2.md  ← 历史稿（v1 / v2，勿作执行依据）
    ├── PERMISSION-PLAYTEST.md ← 权限层的真机试跑清单
    └── mm.py                 ← 查官方映射的小工具（本文所有 API 结论靠它核的）
```

> 仓库根还有 `tools/`（可复用脚本：e2e 验收、NAS 部署、映射核对、git 安全三件套）与
> `ref/numen-1.20.1/`（上游源码克隆，仅作对照，不入库）。

---

## 想接着做

先读 `docs/STATUS.md`（活台账）与 `docs/DECISIONS.md`（用户已拍板事项的唯一出处）。
执行计划在 `docs/RESTORE_PLAN_V3.md`，但要留意：**其中的「方案乙」已被实测证伪，实际走「方案甲」**
（`program/` 22 + `network/` 33 全搬，接缝归零），见 `DECISIONS.md` §四。

**当前状态（2026-10-13）**：刀 1a ~ 4 已完成（框架归位 / 接线 / 穿脱 / 工具派发 / **13 任务族**）；
**刀 5 进行中**——11 个世界工具改上游 `@Fn` 驱动，真机 11/11 已过，剩 **7 处「专用服务端才崩」的潜伏问题待修**
（清单 + javap 判据 + 修法见 `UPSTREAM_COMPARISON.md` §4.4 条2）；
再往后是**刀 6**（清理：导航壳 / G 面板 / MCP 版本漂移 / server-api-check / 文档）与
`ui/`、`plugins/` 两个整缺口（Q-01 / Q-02，见 `UPSTREAM_COMPARISON.md` §4）。

`docs/PORTING.md` 第二节那张表是接着做的人最该先读的东西：
里面每一条都是「上游那样写、1.16.5 上编译不过」的坑，对着官方映射核过，不用再踩一遍。

---

## 许可

上游源码 LGPL-3.0-only；截图/示意图 CC BY 4.0；其余美术资源保留所有权利。
本移植沿用同样的许可。
