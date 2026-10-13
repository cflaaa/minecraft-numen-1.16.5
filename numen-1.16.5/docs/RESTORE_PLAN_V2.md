# 归位计划 v2（2026-10-11 重订）

> ⚠️ **本文是过程稿，已被 [`RESTORE_PLAN_V3.md`](./RESTORE_PLAN_V3.md) 取代。**
> v2 的价值是**实测依赖图**（§2，仍然准确）与**合规自检**（§0，仍然有效）；
> 但 v3 采纳了「方案乙」（`program/` 搬服务端半边），把这里的 3 处接缝降到 2 处，
> 并补全为自包含执行计划。**执行看 v3。**
> ⚠️ **2026-10-11 晚追加**：连 v3 采纳的「方案乙」也被实测证伪，实际走的是**方案甲**
> （`program/` 22 + `network/` 33 全搬，接缝归零，137 文件 / 13082 行）。
> **权威记录见 [`DECISIONS.md`](./DECISIONS.md) §四；当前进展看 [`STATUS.md`](./STATUS.md)。**

> **为什么重订**：v1（[`RESTORE_PLAN.md`](./RESTORE_PLAN.md)）是按**上游目录**估的；动手前逐文件实测依赖，
> 发现三处硬伤——① `sdk` 与 `task` **循环依赖**，v1 的阶段顺序写反了；② 真实闭包比 v1 估的**大 25%**；
> ③ v1 说 `program` 不在范围内，但没查过**谁依赖它**，实际 `sdk`/`task` 各有踩到，需要**三处接缝**。
>
> v1 保留作历史记录（它的分层判断、3 处更正、工作量表都还有效）；**本文是执行版**。
> 本文所有数字都是实测的，不是估的。

---

## 〇、与「仅移植」规矩的对照（合规自检，先看这个）

**规矩**（[`UPSTREAM_COMPARISON.md`](./UPSTREAM_COMPARISON.md) §2 + v1 的原则）：
**对标原版不许结构删减**、**不引入本仓自创结构**。改动只有四类可接受——
**A** 能力子集自写（进度性，须回头对齐）、**B** 薄壳自写（须消除）、**C** 版本/架构约束允许偏差、**D** 缺口（须回补）。

**v2 逐条自检**：

| v2 里的东西 | 合规 |
|---|---|
| 刀 1a：搬 69 个上游件，只改包名与 1.16.5 API | ✅ 就是 D 类回补 |
| 刀 1b：照上游接线 | ✅ |
| 刀 2/3/4/5：继续回补 + 删自写子集 | ✅ |
| **接缝 1**：`TaskPersistence.replay` → `CompanionScript.run` | ❌ **违规**——`CompanionScript` 正是 **A 类自写子集**，还是刀 3 要删掉的那个。拿一个待删的自写件去顶上游件，正好踩两条 |
| **接缝 2**：`NumenEvents` → 面板通道 / 只记日志 | ❌ **违规**——面板通道是本仓自写的（`net/NumenPanelNet`，B 类）；只记日志则是结构删减 |
| **接缝 3**：tick 驱动保留本仓 `NumenPlayer.tick()` | ❌ **违规**——上游有 `CompanionTickDispatcher`/`CompanionBrain` 而不用 = 结构删减。**但这条根本不需要接缝：照搬上游即可**，是 v2 写错了 |

**结论：v2 不完全符合。** 接缝 3 是**我写错**（照搬上游就合规）；接缝 1、2 是
**A 计划与「不许结构删减」的正面冲突**——`task`/`event` 真的需要 `program` 与 `network`，
而 A 计划明确不搬这两层。这个不是我能单方面定的。

### 接缝 1、2 的三条出路

| | 做法 | 代价 | 合规 |
|---|---|---|---|
| **甲** | `program/` 与 `network/` 都搬 | **+55 文件 / ≈4480 行**（program 22/1730、network 33/2754） | ✅ 完全合规，但等于把 A 计划砍掉的那两层补回来 |
| **乙** | 只搬 `program/` 的**服务端半边**（22 个里 **13 个不碰 network**）+ `platform/`；`network/` 不搬 | 中等。`ServerPrograms` 只剩 **2 处**要接缝：`ServerLifecycle.onStopped(...)`、`NumenNetwork.disconnected(owner)` | 🟡 部分合规（这 2 处按 C 类登记） |
| **丙** | 两层都不搬，接缝 1、2 改成**「功能不做 + 日志」** | 最小 | ❌ 功能缺失（不是自创结构，但确实是结构删减） |

**建议：乙。** 理由：`program/` 的**传输半边**存在的唯一理由就是「客户端大脑 ↔ 服务端身体」——
大脑既然留在服务端，那半边本来就没有对端（这与 A 计划一致，不是删减）；
而**服务端半边**（`ServerPrograms`/`ProgramLimits`/`RunModules`/`ModuleCache`/`ServerCalls`…）
是程序管理与并发预算，**照上游搬进来**——于是 `TaskPersistence.replay` 用的是**上游的**
`ServerPrograms.launch`，接缝 1 消失。剩下 2 处（生命周期钩子、断线通知）是真·架构差异，按 C 类登记。

> ⚠️ **顺带纠正一处**：`ProgramLimits`（每位主人 8 段 / 全服 64 段 / 每刻 10ms·5ms）就在 `program/` 里。
> 走**乙**的话它会被一并搬进来——正好补上 `PORTING.md` §17.7 记的那个「多人服优先项」。
> 走甲/丙都补不上。这是选乙的第二个理由。

---

## 一、与 v1 的差别（先看这张）

| | v1 | **v2** |
|---|---|---|
| 阶段 1 / 2 的关系 | `permission` → `sdk` → `task`（线性） | ⚠️ **`sdk` ↔ `task` 循环依赖 → 合并成一刀** |
| 这一批的规模 | 58 文件 / 5926 行 | **69 文件 / ≈7360 行**（实测，且不含已搬的 5 个 task 件） |
| `program` 的处理 | 不在范围内（但没查谁依赖它） | ⚠️ **被 3 处踩到 → 写明接缝改法**（见 §3） |
| `script/` | 算在阶段 4 | **提前进这一批**——`ApiDocs`/`HelpApi` 要它，不是可选 |
| `event/` `mc/` `entity/` 补件 | 未列 / 算在后面 | **提前进这一批**——被拖进来的，不是可选 |
| tick 驱动 | 未提 | ⚠️ **要拍板**（本仓自驱动 vs 上游 `CompanionTickDispatcher`） |
| 验收标准 | 「能独立编译 / 逻辑通过」 | **加一条：真机不回归**（沿用现成的 e2e） |
| 交付物 | 没写清 | **每刀写明「这一刀之后什么才真正能用」** |
| 拆刀 | 阶段 1–5 各一个 PR | **阶段 1+2 合成「刀 1a（搬件）/ 刀 1b（接线）」**，其余顺延 |

**没变的**（v1 判断仍然有效，不要重做）：分层顺序（permission 是地基）、`Acts`/`NumenTools`/
`CompanionScript`/`CompanionNav·Ports` 这 4 处自写子集是终点要删的、阶段 3/4/5/6 的内容、
「不引入本仓自创结构」的原则、以及 `program`/`network`/客户端大脑**不在范围内**。

---

## 二、实测依赖图（v2 的事实基础）

### 2.1 循环依赖

```
        ┌──────────────────────────────────────────────────────┐
        │                                                      │
        ▼                                                      │
   ┌─────────┐   Dispatcher / ServerCall / Consents / Job      │
   │  sdk/   │ ──────────────────────────────────────────┐     │
   └─────────┘   用 TaskDispatch / TaskRecord / TaskResult│     │
        ▲                                                 ▼     │
        │                                          ┌──────────┐ │
        │  TaskApi / TaskRecord 用                  │  task/   │ │
        └──────────────────────────────────────────│          │─┘
           ServerCall / ApiFunction / Call +       └──────────┘
           Fn/SeeAlso/Omitted/Note/Example/Doc
```

**所以「阶段 1 先 sdk、阶段 2 再 task」不成立。**

> ⚠️ **方法论教训（我踩过）**：第一次只按 `import` 扫，得出「`TaskDispatch` 只依赖 `agent` + `entity`，
> 很干净」。**错了**——它**同包**调用 `CompanionTickDispatcher`（`syncSlotFor`/`prepare`/`assign`）
> 与 `TaskPersistence.remember`，而 `CompanionTickDispatcher` → `TaskPersistence` → **`program`**。
> **同包引用不出现在 import 里。扫依赖必须连它一起算。**

### 2.2 这一批的真实闭包（69 文件 / ≈7360 行）

| 件 | 文件 / 行 | 为什么在名单里 |
|---|---|---|
| `sdk/` | 38 / 3718 | 本体（`ApiTester` 依赖 `program`，不搬） |
| `task/` 缺件 | 19 / 1705 | 另 5 个第一刀已搬（`Task`/`TaskResult`/`TaskSelector`/`TaskState`/`Preparation`） |
| `script/` | 3 / 837 | `ApiDocs`、`HelpApi` 要 `Modules` |
| `event/NumenEvents` | 1 / 378 | `TimerRegistry` 要它 |
| `entity/` 补件 | 4 / 441 | `CompanionRegistry`(197) / `EventOutbox`(135) / `InputDriver`(72) / `CompanionSpeech`(37) |
| `mc/Echo` + `mc/Completions` | 2 / 237 | `OnHer` 要它们 |
| `api/Internal` | 1 / 30 | `Consents` 要它 |
| `agent/tool/api/ToolContext` | 1 / 18 | `TaskDispatch.ctx` 要它 |
| **合计** | **69 / ≈7360** | v1 估 58 / 5926 → **低估约 24%** |

### 2.3 两处接缝，以及一处**我写错**的地方

| # | 在哪 | 上游怎么写 | 正确做法（见 §0） | 规模 |
|---|---|---|---|---|
| 1 | `task/TaskPersistence.replay` | `ServerPrograms.launch(her, id, lua, observer)` 把重启前那行 Lua 再跑一遍 | 走 §0 的**乙**：搬 `program/` 服务端半边，用**上游的** `ServerPrograms.launch`——**不是** `CompanionScript.run` | 3 个调用点 |
| 2 | `event/NumenEvents` | 事件推给**主人客户端**：`NumenNetwork` + `NumenEventPayload` | 走 §0 的**乙**：只有 2 处要落（`NumenNetwork.disconnected`、`ServerLifecycle.onStopped`），按 C 类登记 | 2 处 |
| ~~3~~ | ~~tick 驱动~~ | `CompanionTickDispatcher` / `CompanionBrain` | **照搬上游**（把本仓 `NumenPlayer.tick()` 自驱动换成调上游那套）。**不需要接缝** | 接线 |

> 这三处我一开始写成「A 计划接缝」——**接缝 3 是我写错**：上游那套照搬就行，根本没有冲突。
> 接缝 1、2 也不是「A 计划的必然结果」：A 计划只说不搬 `program/` 的**传输半边**，
> 服务端半边（`ServerPrograms` 等）本来就该搬——它跟大脑在不在客户端**无关**。详见 §0。

---

## 三、新的阶段表

### 刀 1a —— 搬件到编译绿（69 文件 / ≈7360 行；走 §0「乙」再加 ≈14 文件 / ≈1100 行）

把 §2.2 那张表里的件全部搬进 `core/`（包名 `com.dwinovo.numen.core.*`）。
**规模取决于 §0 挑哪条出路**：挑乙则再加 `program/` 服务端半边（13 个不碰 `network` 的）+ `platform/`，
总计约 **83 文件 / ≈8500 行**；挑甲再加 `network/` 的 33 个；挑丙就是 69 个但要接受两处功能缺失。

- **机械部分**：`python .workbuddy-ai/tmp/port-sdk-task.py`（拷 + 改包名/跨包 import，1 秒重放）。
  实跑过一次，**只报 11 个错**，说明这条路是通的。
- **手改部分**（已知的三类）：
  - MC API：`BuiltInRegistries` → `Registry`（×3）、`net.minecraft.core.UUIDUtil`、
    `network.chat.contents.TranslatableContents`（1.16.5 是 `TranslatableComponent`）；
  - 缺件补齐（就是 §2.2 里 `entity/`、`mc/`、`api/`、`agent/tool/api` 那几行）；
  - 接缝 1、2。
- **交付物**：`@Fn` 框架**在代码层就位**（能登记、能绑定、能读参数、能派发）。
  真正能用要等刀 1b。
- **验收**：`core:build` 绿；`core:test` 全绿；**真机不回归**（起专用服，模组加载 + 现有功能照旧）。
  顺手给 `Binder` / `ApiRegistry` / `RecordCodec` 补几条单测（上游有测试目录，可参照）。

### 刀 1b —— 接线（把框架挂进运行路径）

- `NumenPlayer.tick()` 里挂 `CompanionTickDispatcher.tick(server)`（按接缝 3 的拍板结果）。
- 登记 `@Fn` 组：至少 `task/TaskApi`（`numen.task.*`）、`sdk/HelpApi`（`api.help`）、`sdk/Consents`。
- 把 `@Fn` 索引接进系统提示 / 帮助（`ApiRegistry.catalog` → `ScriptEngine`）。
- **交付物**：脚本里**真的能调到一个 `@Fn` 函数**（例如 `numen.task.*` 或 `api.help`），
  且权限层照旧在它前面拦（`ServerCall.authorize` → 已落地的 `Gate`）。
- **验收**：真机上跑一段 Lua 调到 `@Fn` 函数；权限闸对它的 ASK/DENY 行为与现有 `mine`/`drop` 一致。

> ⚠️ **这一刀不删任何自写子集**——`Acts`/`NumenTools`/`CompanionScript` 原样留着，
> 新旧两套并存。删它们是刀 3。

### 刀 2 —— 阶段 3：`api/gear/`（Wardrobe）

`GearSlot` / `GearSource` / `Wardrobe`（2 + core 侧 1），把 `Acts.equip` 的「直写槽位」换成 `Wardrobe.wear`。
小、独立。**交付物**：inventory 族 `Equip` 的前置就位。

### 刀 3 —— 阶段 4：`agent/tool/` + `script/` 替换（**开始删自写子集**）

- `agent/tool/`（15 文件）：`ToolRegistry` / `NumenTool` / `ScriptTool` / `SkillTool` / `TodoTool` / `MemoryTool` …
- 替换：`NumenTools` 扁平表 → `ToolRegistry` 驱动；`lua` 工具 → `ScriptTool` + `Modules`（脚本落磁盘、有战绩）。
- **删** `NumenTools.java`、`CompanionScript.java` 这两个自写子集。
- **交付物**：44 个 `@Fn` 函数全量暴露 + 脚本多步任务可用。

### 刀 4 —— 阶段 5：13 族任务本体（**大头，逐族删 `Acts`**）

每搬一族就删 `Acts.java` 里对应那一段。建议顺序同 v1：interact + inventory → move/locate/perception
→ dig/build/combat/fish/survival/wait/chain/reflex/time。
**交付物**：`Acts.java` 删空；动作全部走上游 `CompanionTask` + `TaskRecord`。

### 刀 5 —— 阶段 6：收尾

删 `CompanionNav`/`CompanionPorts` 壳（若 nav 任务族到位）；复核 G 面板 / `ModelCatalog` 两处版本偏差；
`server-api-check.py` 全量复核 + 真机冒烟；更新文档。

---

## 四、每刀的验收清单（统一模板，照做）

1. **编译**：`:core:build` 绿。
2. **单测**：`:core:test` 全绿（新搬的件该补测的补）。
3. **发行 jar**：类数 = pathing + core 的并集，**逐个比对无缺无多**（改过 pathing 更要数）。
4. **真机**：按「本机方案」起专用服（有控制台 + MCP 8766），**现有功能不回归**；
   新功能按该刀的交付物验一次。
5. **文档 + 记忆**：`PORTING.md` 记这一刀、`MEMORY.md` 记结论、当日日志记过程。
6. **git 提交**：验证过了才提交，一次一刀。

---

## 五、还欠的决定

1. **§0 的三条出路挑一条（甲 / 乙 / 丙）**——我建议 **乙**：`program/` 只搬**服务端半边**
   （22 个里 13 个不碰 `network`）+ `platform/`，`network/` 不搬。挑乙的附带好处：
   `ProgramLimits`（每位主人 8 段 / 全服 64 段 / 每刻 10ms·5ms）就在 `program/` 里，
   **一并补上 `PORTING.md` §17.7 记的那个「多人服优先项」**。甲/丙都补不上。
2. **`network/` 那 2 处怎么落**——`NumenNetwork.disconnected(owner)` 与 `ServerLifecycle.onStopped(...)`：
   走面板通道、只记日志、还是把 `platform/ServerLifecycle` 也一起搬（它很小）。

> ~~接缝 3（tick 驱动）~~ **不再是决定**：照搬上游 `CompanionTickDispatcher` + `CompanionBrain`。

---

## 六、A 计划边界（**已按 §0 收窄，别再照抄旧说法**）

「`program/`、`network/`、客户端大脑全都不搬」这句**说得太粗**。准确的边界是：

| 层 | 搬不搬 | 理由 |
|---|---|---|
| **客户端大脑**（`agent/` 26 文件里的客户端那半） | ❌ 不搬 | 大脑在服务端 |
| `program/` 的**传输半边**（`ClientEndpoint`/`ClientTransport`/`NetworkTransport`/`ProgramUplink`/`ClientCalls`/`Loopback*`） | ❌ 不搬 | 它的对端是客户端大脑，大脑不过去就没有对端 |
| `program/` 的**服务端半边**（`ServerPrograms`/`ProgramLimits`/`RunModules`/`ModuleCache`/`ServerCalls`/`RunResult`/`CallObserver`…） | ✅ **要搬**（§0 乙） | 程序管理与并发预算，与大脑在哪无关；`TaskPersistence.replay` 要用 |
| `network/` | 🟡 看 §0 的挑法 | 上游用来推事件给主人客户端；没有客户端 mod 时只有 2 处要落 |

> 记在 `PORTING.md` §17.7 的「不做」清单要按这张表改口径——之前那句太粗，会把该搬的也当成不欠。
