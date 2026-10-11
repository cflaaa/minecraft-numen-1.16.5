# 归位计划 v3 —— 执行版（2026-10-11）

> **本文是自包含的完整执行计划：照着做不需要读 v1/v2。**
> 采用**方案乙**——`program/` 只搬**服务端半边** + `platform/`，`network/` 不搬。
> v1（[`RESTORE_PLAN.md`](./RESTORE_PLAN.md)）、v2（[`RESTORE_PLAN_V2.md`](./RESTORE_PLAN_V2.md)）
> 是决策过程记录，保留作历史；**有冲突以本文为准**。
>
> 规矩（[`UPSTREAM_COMPARISON.md`](./UPSTREAM_COMPARISON.md) §2 + v1 原则）：
> **对标原版不许结构删减**、**不引入本仓自创结构**。改动只有四类可接受——
> **A** 能力子集自写（须回头对齐）、**B** 薄壳自写（须消除）、**C** 版本/架构约束允许偏差、**D** 缺口（须回补）。

---

## 〇、与 A 计划的对比（乙 到底改了什么）

**A 计划**（2026-10-11 拍板）：大脑留服务端，`program/` 不搬，内置插件 API 逐阶段补；
部署目标 = A 计划 + 外接大脑。

| | A 计划原文 | **v3（乙）** |
|---|---|---|
| 大脑位置 | 服务端 | **服务端（不变）** |
| 部署目标 | + 外接大脑（MCP） | **不变** |
| `program/` **传输半边** | 不搬 | **不搬（不变）**——对端是客户端大脑，大脑不过去就没有对端 |
| `program/` **服务端半边** | ⚠️ 被「不搬」一起划掉了 | ✅ **要搬**（14 文件 / 1077 行） |
| `platform/`（main 侧，含 `services/`） | 未提 | ✅ **要搬**（8 文件 / 373 行） |
| `network/` | 不搬 | 不搬（只剩 **2 处**要落） |
| 多人服的每刻主线程预算 | 缺（记为优先项，见 `PORTING.md` §17.7） | ✅ **顺带补上**——`ProgramLimits` 就在那半边里 |
| 合规性 | — | 接缝从 3 处降到 **2 处**（都按 C 类登记） |

### 为什么这是「纠正口径」而不是「改主意」

`program/` 里两半的**用途不同**：

```
program/ 22 文件 / 1730 行
├─ 传输半边（8 文件）——存在的唯一理由是「客户端大脑 ⇄ 服务端身体」
│    ClientEndpoint · ClientTransport · NetworkTransport · ProgramUplink
│    ClientCalls · LoopbackClient · LoopbackTransport · ModuleSet
│  → A 计划要砍的是这一半。大脑在服务端，它没有对端。✅ 不搬
└─ 服务端半边（14 文件 / 1077 行）——程序管理与并发预算，与大脑在哪**毫无关系**
     ServerPrograms(288) · RunResult(118) · MainQueue(122) · ModuleCache(95)
     RunModules(90) · ProgramPort(63) · AnswerTable(56) · ProgramLimits(48)
     ModuleSync(46) · ServerCalls(46) · CallObserver(34) · ObservedCalls(29)
     RoutedCalls(23) · ProgramCalls(19)
   → A 计划**顺手把这一半也划掉了**，但它本来就该搬：
     `task/TaskPersistence.replay` 要用 `ServerPrograms.launch`，
     `ProgramLimits`（每位主人 8 段 / 全服 64 段 / 每刻 10ms·5ms）是多人服的必需品。
```

**所以乙不是妥协，是把 A 计划的口径修准。** 剩下的偏离只有 `network/`，而且只剩 2 处。

---

## 一、演进（v1 → v2 → v3，一句话各一段）

- **v1**：按**上游目录**估的计划（阶段 1 = sdk，阶段 2 = task；这一批 58 文件 / 5926 行）。
- **v2**：动手前逐文件**实测**依赖 → 发现 ① `sdk` ↔ `task` **循环依赖**（v1 顺序写反）
  ② 闭包 **69 文件 / ≈7360 行** ③ `program` 被踩到。按实测重订 + 拆刀。
- **v2 的合规自检**：三处「接缝」都不合规（其中接缝 3 是我写错——照搬上游即可）。
  顺着追问发现「`program/` 不搬」这个口径太粗。
- **v3（本文）**：采用**乙**，把 `program` 服务端半边纳入；**接缝 3 处 → 2 处**；补全为自包含执行计划。

---

## 二、这一批搬什么（**90 文件 / 8814 行**，数字是干跑脚本数出来的）

| 件 | 文件 / 行 | 为什么在名单里 | 合规类别 |
|---|---|---|---|
| `sdk/` | 38 / 3718 | 本体（`ApiTester` 依赖 `program` 的**传输半边**，不搬） | D |
| `task/` 缺件 | 18 / 1705 | 上游 24 个，本仓已有 5 个（第一刀），`CompanionBrain` 不搬 | D |
| `program/` 服务端半边 | 14 / 1077 | `TaskPersistence.replay` 要用；`ProgramLimits` 是多人服必需品 | D |
| `script/` | 3 / 837 | `ApiDocs`、`HelpApi` 要 `Modules` | D |
| `entity/` 补件 | 4 / 441 | `CompanionRegistry`(197) / `EventOutbox`(135) / `InputDriver`(72) / `CompanionSpeech`(37) | D |
| `event/NumenEvents` | 1 / 378 | `TimerRegistry`、`ServerPrograms` 要它 | D |
| `platform/`（含 `services/`） | 8 / 373 | `ServerPrograms` 要 `ServerLifecycle`；`Services` 是平台抽象 | D |
| `mc/Echo` + `mc/Completions` | 2 / 237 | `OnHer` 要它们 | D |
| `api/Internal` | 1 / 30 | `Consents` 要它 | D |
| `agent/tool/api/ToolContext` | 1 / 18 | `TaskDispatch.ctx` 要它 | D |
| **合计** | **90 / 8814** | | |

> 数字来源：`port-sdk-task.py` 干跑一遍，`git status` 数的。**不是估的**——
> 我先前在 v2 里写 69/7360、在 v3 初稿里写 86/8533，两次都错（第一次漏了 `platform/services/` 的 5 个文件、
> 第二次漏了 `event/`、`entity/` 与 `ToolContext`）。**计划里的数字一律以脚本干跑为准。**

> 全部是 **D 类（缺口回补）**，即"原版有、本仓没有"——不是自创。包名一律
> `com.dwinovo.numen.core.*`（`agent`/`pathing` 两个独立模块的包名不动）。

---

## 三、完整刀表

### 刀 1a —— 搬件到编译绿（90 文件 / 8814 行）

**范围**：§2 那张表全部（90 文件 / 8814 行）。含 2 处接缝（见下）。

**机械部分**：`python .workbuddy-ai/tmp/port-sdk-task.py`（拷 + 改包名 + 改跨包 import，**1 秒重放**）。
该脚本实跑过两次：一次 60 文件（`core:compileJava` **只报 11 个错**，说明这条路通），
一次 90 文件（§2 的数字就是这么来的）。脚本已按方案乙把 `program/` 服务端半边 + `platform/`
+ `event/` + `entity/` 补件 + `ToolContext` 都写进名单，**一条命令重放**。

**已知要手改的（三类）**：
1. **MC API 差异**：`BuiltInRegistries` → `Registry`（×3）、`net.minecraft.core.UUIDUtil`、
   `network.chat.contents.TranslatableContents`（1.16.5 用 `TranslatableComponent`）；
   编译会报更多，逐个 `javap` 核。
2. **缺件补齐**：就是 §2 里 `entity/`、`mc/`、`api/`、`platform/`、`agent/tool/api` 那几行。
3. **2 处接缝**（**唯一**的偏离，按 C 类登记）：
   | # | 在哪 | 上游 | v3 怎么写 |
   |---|---|---|---|
   | 1 | `ServerPrograms.ownerLeft` | `NetworkTransport.INSTANCE.ownerLeft(owner)` | 删掉这一句 + 一行注释（"A 计划无传输半边"） |
   | 2 | `ServerPrograms.ownerLeft` | `NumenNetwork.disconnected(owner)` | 同上 |

   > `ServerPrograms` 里另外两处 `NumenEvents.watch(...)`、`ServerLifecycle.onStopped(...)`
   > **不再是接缝**——`event/` 与 `platform/` 都进名单了。

**交付物**：`@Fn` 框架 + 任务框架 + 程序管理**在代码层就位**（能登记、能绑定、能读参数、能派发）。
真正能用要等刀 1b。

**验收**：`:core:build` 绿；`:core:test` 全绿（给 `Binder`/`ApiRegistry`/`RecordCodec` 补几条单测，
上游有 `api/common/src/test` 可参照）；发行 jar 类数逐个比对；**真机不回归**。

### 刀 1b —— 接线（把框架挂进运行路径）

1. **tick 驱动照搬上游**：`NumenPlayer.tick()` 里调 `CompanionTickDispatcher.tick(server)`，
   替掉本仓现在的自驱动（`ConsentDesk.tick` + `NumenTools.tick` 那两行按上游写法收编）。
   ⚠️ **这一条不是接缝**——上游那套照搬即可。
2. **登记 `@Fn` 组**：至少 `task/TaskApi`（`numen.task.*`）、`sdk/HelpApi`（`api.help`）、`sdk/Consents`。
3. **把 `@Fn` 索引接进系统提示 / 帮助**：`ApiRegistry.catalog(...)` → `ScriptEngine`。
4. **程序管理接上**：`ServerPrograms` 的生命周期钩子（`NumenEvents.watch` / `ServerLifecycle.onStopped`）。

**交付物**：脚本里**真的能调到一个 `@Fn` 函数**（`numen.task.*` 或 `api.help`），
且权限层照旧在它前面拦（`ServerCall.authorize` → 已落地的 `Gate`）。

**验收**：真机跑一段 Lua 调到 `@Fn` 函数；权限闸对它的 ASK/DENY 行为与现有 `mine`/`drop` 一致。

> ⚠️ **刀 1a/1b 都不删任何自写子集**——`Acts`/`NumenTools`/`CompanionScript` 原样留着，新旧并存。
> 删它们是刀 3。

### 刀 2 —— 阶段 3：`api/gear/`（Wardrobe）

`GearSlot` / `GearSource`（2 文件 / 66 行）+ `core` 侧 `Wardrobe`。
把 `Acts.equip` 的「直写槽位」换成 `Wardrobe.wear`（穿的是同一槽，原版读到的状态仍正确，
但从此能被权限层与 `TaskRecord` 审计）。小、独立。
**交付物**：inventory 族 `Equip`/`Unequip` 的前置就位。

### 刀 3 —— 阶段 4：`agent/tool/` + `script/` 替换（**开始删自写子集**）

- 搬 `agent/tool/`（15 文件 / 1549 行）：`ToolRegistry`/`NumenTool`/`ScriptTool`/`SkillTool`/
  `TodoTool`/`MemoryTool`/`ToolCall`/`ToolAnchor` …
- 替换：`NumenTools` 扁平表 → `ToolRegistry` 驱动；`lua` 工具 → `ScriptTool` + `Modules`
  （脚本落磁盘、有战绩统计）。
- **删** `NumenTools.java`、`CompanionScript.java`（A 类自写子集）。
- **交付物**：44 个 `@Fn` 函数全量暴露 + 脚本多步任务可用。

### 刀 4 —— 阶段 5：13 族任务本体（**大头，逐族删 `Acts`**）

`core/tools/`(36 / 3757) + `core/task/`(63 / 11014)。每搬一族就删 `Acts.java` 里对应那一段。
建议顺序：interact + inventory → move/locate/perception → dig/build/combat/fish/survival/wait/chain/reflex/time。
**交付物**：`Acts.java` 删空；动作全部走上游 `CompanionTask` + `TaskRecord`。

### 刀 5 —— 阶段 6：收尾

删 `CompanionNav`/`CompanionPorts` 壳（若 nav 任务族到位）；复核 G 面板 / `ModelCatalog`
两处版本偏差（用户偏好：能用原版就用原版）；`server-api-check.py` 全量复核 + 真机冒烟；
更新文档，把 A/B 类自写子集从 `UPSTREAM_COMPARISON.md` 里划掉。

---

## 四、统一验收清单（每刀都照这个走）

1. **编译**：`:core:build` 绿。
2. **单测**：`:core:test` 全绿（新搬的件该补测的补）。
3. **发行 jar**：类数 = `pathing` + `core` 的类名并集，**逐个比对无缺无多**（改过 pathing 更要数）。
4. **真机**：按「**本机方案**」起专用服（有控制台 + MCP 8766，能召唤/驱动同伴），
   **现有功能不回归**；新功能按该刀的交付物验一次。
5. **文档 + 记忆**：`PORTING.md` 记这一刀、`MEMORY.md` 记结论、当日日志记过程。
6. **git 提交**：验证过了才提交，**一次一刀**。

> 测试方案二选一（`.workbuddy-ai/memory/MEMORY.md` 有取舍表）：需要控制台/MCP → 本机；
> 只要连通性/真部署 → NAS。**凡涉及召唤或驱动同伴，只能用本机方案**（NAS 的 RCON 关着、MCP 端口没发布）。

---

## 五、剩余决定（只剩 1 个）

`ServerPrograms.ownerLeft` 里那 2 处（`NetworkTransport.INSTANCE.ownerLeft`、
`NumenNetwork.disconnected`）怎么落：

- **甲：删掉 + 一行注释**（推荐）——这两句的作用是"主人断线时收掉他名下的程序、清缓存、
  丢弃没收完的分片消息"。本仓没有传输半边，**分片消息本来就不存在**；
  而"收掉程序、清缓存"可以在**上层**（主人断线的监听点）调用 `ServerPrograms.ownerLeft` 的其余逻辑。
- **乙：把 `ServerPrograms.ownerLeft` 整个改成只做 `RUNNING`/`CACHE` 那两件**，网络那两行删掉。

两者差别很小，本质一样。**我按甲做**（改动最小、注释说清），除非你说别的。

---

## 六、边界表（**准确版**，别再照抄"program/network 都不搬"那句粗话）

| 层 | 搬不搬 | 理由 |
|---|---|---|
| 客户端大脑（`agent/` 26 文件里的客户端那半） | ❌ 不搬 | 大脑在服务端 |
| `program/` **传输半边**（8 文件） | ❌ 不搬 | 对端是客户端大脑，大脑不过去就没有对端 |
| `program/` **服务端半边**（14 文件） | ✅ **搬** | 程序管理与并发预算，与大脑在哪无关 |
| `network/`（33 文件 / 2754 行） | ❌ 不搬 | 只有 2 处要落（见 §五），按 C 类登记 |
| `platform/`（main 8 文件，含 `services/`） | ✅ **搬** | `ServerPrograms` 要 `ServerLifecycle`；`Services` 是平台抽象 |
| `platform/`（client 侧 `ClientServices`） | ❌ 不搬 | 客户端侧 |
| `sdk/ApiTester` | ❌ 不搬 | 依赖 `program` **传输半边** |
| `task/CompanionBrain` | ❌ 不搬 | 客户端大脑的注册表 |

---

## 七、风险与注意事项

1. **级联可能还没到底**。v2 的教训是「补一件带出下一层」。**每加一批就编一次**，
   别攒着——`port-sdk-task.py` 就是为了快速重放。
2. **`program/` 的 1.16.5 API 差异未知**。它是 `api/common` 里最贴近引擎的一层，
   可能碰 `MinecraftServer`/`ScheduledExecutor` 之类；`ServerPrograms` 用了
   `Executors.newSingleThreadExecutor` + `ServerLifecycle`，预计差异不大。
3. **`ProgramLimits` 搬进来不等于生效**：上游靠 `program/` 的主循环每刻查预算。
   搬进来后要确认它的调用点也接上了（刀 1b 的第 4 项）。
4. **`event/NumenEvents` 需要 `network`** —— 但按 §六，`network` 不搬。
   ⚠️ 这是**第 3 处待落点**（v2 只数了 `ServerPrograms` 的 2 处）。
   落法同 §五：`NumenEvents` 里推给客户端的那几行删掉 + 注释（本仓无客户端 mod）。
   刀 1a 编译时会暴露，届时按同一口径处理。
