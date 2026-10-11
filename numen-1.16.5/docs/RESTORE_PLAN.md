# 归位计划：把本仓带回到对标原版的结构

> ⚠️ **这是 v1（历史记录）。执行请看 [`RESTORE_PLAN_V2.md`](./RESTORE_PLAN_V2.md)。**
> v2 在动手前实测了依赖，更正了三处：`sdk`↔`task` 是**循环依赖**（v1 的阶段顺序写反）、
> 这一批的真实闭包是 **69 文件 / ≈7360 行**（v1 估 58/5926）、`program` 被踩到**三处**需要接缝。
> v1 的分层判断、下面这 3 处更正、工作量表**仍然有效**，别重做。

> 本计划是 [`UPSTREAM_COMPARISON.md`](./UPSTREAM_COMPARISON.md) 的后续——把其中"第 2 节 D 项"的缺口逐项归位。
> 目标：**消除本仓自写子集**（`Acts.java` / `NumenTools.java` / `CompanionScript.java` / `CompanionNav`·`CompanionPorts` 壳），用上游原版对应件替换，达到**结构对齐**原版（1.20.1）。
> 原则：分层自底向上搬；每搬一层就消除一层对它的"临时绕开"；不引入本仓自创结构。

---

## 校验结论（2026-10-10，对照真实上游代码核过）

逐条核对后，本计划的**分层顺序是对的**，但有 **3 处要更正、1 处要你拍板**：

**✅ 对的地方**
- 分层顺序正确。用 import 依赖核过：`permission` 不依赖 `sdk`；`sdk` 轻依赖 `permission`；`task` 框架依赖两者；`core/tools`(23 文件)、`core/task`(26 文件) 重依赖 `sdk`。所以"permission → sdk → task → tool/script → 任务族"成立，无环。
- 引擎侧现成：`agent` 模块已含完整 ReAct 循环 + 脚本运行时（`agent/script/*` 逐字搬），`sdk`/`script` 绑定可以直接插上。

**❌ 更正 1：阶段 0 的"脚本限流"是假问题。**
上游每个程序的调用数/指令数/墙钟上限在 `agent/script/ScriptLimits`（**200 次调用 / 20 分钟**），本仓的 `agent/script/ScriptCall.java`(214–220 行) **已经在强制执行**（逐字搬的引擎带进来的）。真正错的只是 `NumenTools` 里 `lua` 工具的**说明文字**——写的是"400 次调用与两分钟"，与实际（200/20 分钟）不符。所以阶段 0 不是"加限流"，只是**改一句说明**。上游的 `program/ProgramLimits` 管的是**并发数与每刻主线程预算**（每位主人 8 段、全服 64 段、每刻 10ms/5ms），跟 400/2 分钟无关，且本仓脚本是**同步跑在服务端主线程**的，没有那条并发通道。

**❌ 更正 2：工作量估小了。**
原写"约 12000+ 行 / 100+ 文件"。按真实上游统计，**只算计划点名的缺口**就已是：

| 缺口 | 原版规模 |
|---|---|
| `sdk/` | 39 文件 / 4026 行 |
| `permission/` | 17 文件 / 2216 行 |
| `task/` 框架缺件 | 约 19 文件 / 约 1900 行 |
| `agent/tool/` | 15 文件 / 1549 行 |
| `script/` | 3 文件 / 837 行 |
| `api/gear/` | 2 文件 / 66 行 |
| `core/tools/`（任务族的 API 层） | 36 文件 / 3757 行 |
| `core/task/`（13 族任务本体） | 63 文件 / 11014 行 |
| **小计** | **约 194 文件 / 约 25000 行** |

**❌ 更正 3：缺口清单漏了整片区域。**
对照上游 `api/common` 全部子包，除计划已列的外，**本仓还缺**（这些也是"原版有、本仓没有"）：`agent/`(26 文件/2952 行，大脑的 MC 侧胶水)、`api/`(8 文件，`NumenActuator`/`NumenGateway`/`CompanionPortrait`)、`area/`(2/785)、`data/`(2/1449)、`event/`(1/378)、`network/`(33/2754)、`platform/`(8/373)、`mixin/`(10)、`mc/`(5)。其中 `platform/` 是**本仓明确主动删掉的**（见 `NumenCore.java` 注释："把那些一并砍掉了"），`network/` 本仓只自写了 `net/NumenPanelNet`。

**✅ 已拍板（2026-10-11）：一个架构分叉（比"补文件"大得多）。**
上游的大脑（LLM 那一侧）跑在**主人客户端**（`api/common/src/client/.../client/agent/EntityAgentLoop` + `ToolDispatcher` + `CompanionToolPort`），身体和任务在**服务端**，两者之间由 `program/`（22 文件）这条传输层连通；这也是 `program` 存在的原因。
**本仓把大脑整个搬到了服务端**（`core/agent/CompanionBrain`，直接 `server.execute(...)`），于是 `program` 那一层"不需要"了——但这不是"少搬了一层"，而是**换了一套架构**。按"对标原版不许结构删减"的规矩，这一处要么算"版本问题"保留，要么就得把客户端大脑 + `program` 传输补回来（那是**把本仓核心架构推倒重来**的量级，远大于本计划其余部分之和）。
→ **在动 25000 行之前，这个必须先定。**

> **拍板结果：A 计划 —— 大脑留在服务端，`program/` 不搬。**
> 并且**部署目标是「A 计划 + 外接大脑」**（2026-10-11 定）：MC 服务端只跑内置的 MCP 服务端，
> 真正的 LLM 循环在 Minecraft 之外。理由见 `PORTING.md` §17.7。
> 因此 `program/`、`network/`、客户端大脑**都不在归位范围内**；它们不是"欠账"，
> 而是这套架构**有意不要**的一层，以后不要在缺口清单里重复登记。

**❌ 更正 4（2026-10-11，动手前实测）：`sdk` 与 `task` 是**循环依赖**，阶段 1/2 必须合并。**
上面「分层顺序 permission → sdk → task」在 sdk 与 task 之间**写反了**。实测（import 与**同包引用**
都算——只看 import 会漏掉同包耦合，我第一次就是这么漏的）：

```
sdk  → task : Dispatcher / ServerCall / Consents / Job 用 TaskDispatch / TaskRecord / TaskResult
task → sdk  : TaskApi / TaskRecord 用 ServerCall / ApiFunction / Call + Fn/SeeAlso/Omitted/Note/Example/Doc
```

而且从 sdk 往下一走，闭包比估计的大得多：

| 件 | 实测 | 为什么被拖进来 |
|---|---|---|
| `sdk/` | 39 文件 / 4026 行 | 本体（`ApiTester` 依赖 `program`，A 计划下不搬） |
| `task/` 缺件 | 19 文件 | 另 5 个第一刀已搬（`Task`/`TaskResult`/`TaskSelector`/`TaskState`/`Preparation`） |
| `script/` | 3 文件 / 837 行 | `ApiDocs` / `HelpApi` 要 `Modules` |
| `event/NumenEvents` | 1 文件 / 378 行 | `TimerRegistry` 要它 |
| `mc/Echo`+`Completions` | 237 行 | `OnHer` 要它们 |
| `api/Internal` | 30 行 | `Consents` 要它 |
| `entity/` 补件 | `CompanionRegistry` / `CompanionSpeech` / `InputDriver` / `EventOutbox` | `TimerRegistry` / `SpeakingLookChain` / `NumenEvents` |
| `agent/tool/api/ToolContext` | — | `TaskDispatch.ctx` 要它 |
| **合计** | **≈ 75 文件 / ≈ 8000 行** | 计划原先估 58 文件 / 5926 行，**低估约 35%** |

**⚠️ 三处 A 计划接缝**（`program`/`network` 是 A 计划不要的那层，而 task 框架正好踩到）：

1. `task/TaskPersistence.replay`：上游用 `ServerPrograms.launch(...)` 把重启前那一行 Lua 再跑一遍
   → A 计划下改用本仓自己的 `CompanionScript.run`（或只记日志）。**3 个调用点。**
2. `event/NumenEvents`：上游把事件推给**主人客户端**（`NumenNetwork` + `NumenEventPayload`）
   → A 计划下没有 `network/`，改走本仓已有的面板通道或记日志。
3. `task/CompanionTickDispatcher` / `task/CompanionBrain`：上游由它们驱动 tick、管大脑注册表；
   本仓现在是 `NumenPlayer.tick()` 自己驱动（`ConsentDesk` + `NumenTools`）。
   要不要换成上游那套驱动，是这一批的**第二个决定**（第一个是接缝 1、2 的写法）。

> **建议拆成两刀**：
> **刀 A** = `sdk` + `script` + `api/Internal` + `mc/{Echo,Completions}` + `task` 缺件 + `entity` 补件
> + `event/NumenEvents`（含接缝 1、2）。跑通后 `@Fn` 框架才真正可用。
> **刀 B**（可选、之后）= 是否把 tick 驱动换成上游的 `CompanionTickDispatcher`（接缝 3）。

---

## 0. 为什么这么排（依赖关系）

本仓之所以先用"子集"顶着，是因为上游这几件小功能（开箱/吃/穿/用）长在一条公共底座上：

```
具体任务族 (interact / inventory / build / … 13 族, ~63 文件)
        │ 长在
        ▼
AbstractCompanionTask (task/base) + TaskRecord (task 框架)
        │ 调用
        ▼
sdk/  (@Fn API 函数框架)  +  permission/  (动作前的同意拦截 Gate)
        │ 经
        ▼
api/gear/  (Wardrobe 穿脱)   ← inventory 族 Equip 任务需要
```

- `sdk` 与 `permission` 是**所有 `@Fn` API 函数与"动作经同意层"的地基**——没有它们，具体任务族编译不过。
- `task/base` + `TaskRecord` 是**任务调度内核**——没有它们，具体任务族挂不上。
- `api/gear` 是 inventory 族 `Equip` 任务的依赖。
- `agent/tool` + `script` 坐在 `sdk` 之上，负责"把动作暴露给模型"和"脚本从磁盘加载"。
- `program/`（22 文件）在上游是**客户端大脑 ↔ 服务端身体**之间的传输层。本仓把大脑搬到了服务端，所以这一层目前"不需要"——**但这是架构选择，不是版本问题**（见上文"要你拍板"）。若决定保留服务端大脑，`program` 不搬；若决定回归上游，它是必须的一层。

---

## 阶段 0：小修（独立、可立即做，不依赖任何层）

| 项 | 动作 | 说明 |
|---|---|---|
| `lua` 工具说明 | 把 `NumenTools` 里 `lua` 工具描述中的"400 次调用与两分钟"改成实际值 **200 次调用 / 20 分钟** | 只是**改一句说明**——真正的限流已由引擎 `ScriptCall`+`ScriptLimits` 强制，不缺功能（详见"校验结论 更正 1"） |
| `TaskResult.stoppedBy` | 等 `TaskRecord` 归位后，把删掉的方法补回 | 一行删改回补，依赖阶段 2 的 `TaskRecord` |

**价值**：把一处与实际不符的说明改正；`stoppedBy` 待依赖到位后回补。不碰任何大结构。

---

## 阶段 1：基础层 —— `sdk/` + `permission/`（最大的一块底座）

- **搬 `sdk/`**（40+ 文件，约 4000 行）：`@Fn` 注解、`ApiFunction`、`Binder`、`Dispatcher`、`LuaCodecs`、`ServerCall`、`ApiRegistry`、`ApiDocs` 等。
  - 这是让"一个 Java 方法 = 脚本里的一个 API 函数"的声明式框架。
- **搬 `permission/`**（17 文件）：`Gate`、`RuleSet`、`ConsentDesk`、`Facts`、`Verdict`、`PermissionStore`、`Action` 等。
  - 这是动作前的同意/权限拦截——目前本仓挖/放/用**全敞开**，归位后由 `Gate` 管。
- **验收**：两层能独立编译；`sdk` 的 `ApiTester` / `permission` 的单元逻辑通过。
- **解锁**：后续所有 `@Fn` API 函数与"动作经同意层"才能落地。

> 这是真正的大头，建议作为**第一个正式归位 PR**。

---

## 阶段 2：任务内核 —— `task/` 框架缺件 + `task/base`

- **搬 `task/base`**（`AbstractCompanionTask` 等基础任务类）。
- **搬缺的 15 个框架文件**：`BrainChains` / `CompanionTickDispatcher` / `HandPinRelease` / `Preparing` / `TaskApi` / `TaskDispatch` / `TaskFactory` / `TaskPersistence` / `TaskRecord` / `TaskSessionHooks` / `TaskSlot` / `TimerRegistry` / `UnsupportedTask` / `chain/` / `reflex/`。
- 这一步顺带让阶段 0 的 `TaskResult.stoppedBy` 可以补回（依赖 `TaskRecord`）。
- **验收**：任务三层（反射 / 同步 / 当前任务）的调度 loop 能跑；任务能进 `TaskSlot` 串行。
- **解锁**：所有具体任务族可以挂上来。

---

## 阶段 3：装备层 —— `api/gear/`（Wardrobe）

- **搬 `api/gear/`**（2 文件：`GearSlot`、`GearSource`，及 `core` 侧 `Wardrobe`）。
- **替换 `Acts.equip` 的"直写槽位"** 为调用 `Wardrobe.wear`（穿的是同一槽，行为对齐原版，且能被权限层 / `TaskRecord` 审计）。
- **解锁**：inventory 族的 `Equip` / `Unequip` 任务。

---

## 阶段 4：动作暴露层 —— `agent/tool/` + `script/`（替换 `NumenTools` / `CompanionScript` 子集）

- **搬 `agent/tool/`**（13 文件）：`ToolRegistry`、`NumenTool`、`ScriptTool`、`SkillTool`、`TodoTool`、`MemoryTool`、`ToolCall`、`ToolAnchor` 等。
- **搬 `script/`**（3 文件）：`Modules`（磁盘模块系统：安装/升级/账本/战绩/指纹）、`BuiltinModules`、`ModuleApi`）—— 替换 `CompanionScript` 的内存登记。
- **替换**：`NumenTools` 扁平表 → 上游 `ToolRegistry` 驱动；`lua` 工具 → 接 `ScriptTool` + `Modules`（脚本从磁盘加载、有战绩统计）。
- **解锁**：44 个 `@Fn` API 函数全量暴露 + 脚本多步任务（`numen.work.*` 等）。

---

## 阶段 5：任务族本体 —— 13 族逐一归位

> 每搬一族，就把 `Acts.java` 里对应的自写能力**删掉**，改用原版任务类（这是"消除子集"的关键动作）。

建议顺序（依赖少→多 / 价值高→低）：

1. **interact（4）+ inventory（12）** —— 直接替换 `Acts.java` 的 5 个原子能力；动作走原版 `CompanionTask` + `TaskRecord`。
2. **move / locate / perception** —— 导航与感知，复用已搬的 `pathing`。
3. **dig / build / combat / fish / survival / wait / chain / reflex / time** —— 其余各族。

- **验收**：动作经 `permission` 的 `Gate` 拦截；任务进 `TaskSlot` 串行；结果与 `TaskRecord` 对账。

---

## 阶段 6：收尾 —— 消除所有壳、复核偏差

- **删 `Acts.java`**（被 interact / inventory 族取代）。
- **删 `CompanionNav` / `CompanionPorts` 壳**（被 nav 任务族取代；或确认 `pathing` 直接驱动后移除适配层）。
- **复核 C 项偏差**（用户偏好：如可能优先移植原版）：
  - G 面板自绘 —— 评估改用原版（若 1.16.5 渲染可行），否则保留自绘并标注版本原因。
  - `ModelCatalog` —— 视上游对应件决定保留还是替换。
- **全量复核**：跑 `server-api-check.py` 复核 1.16.5 服务端 API 使用；真机冒烟。
- **更新文档**：在 `PORTING.md` / `UPSTREAM_COMPARISON.md` 标记已归位项。

---

## 工作量粗估（仅排序参考，非承诺）

| 层 | 原版规模 |
|---|---|
| `sdk/` | 39 文件 / 4026 行 |
| `permission/` | 17 文件 / 2216 行 |
| `task/` 框架缺件（24 总数 − 已搬 5） | 约 19 文件 / 约 1900 行 |
| `api/gear/` | 2 文件 / 66 行 |
| `agent/tool/` | 15 文件 / 1549 行 |
| `script/` | 3 文件 / 837 行 |
| `core/tools/`（任务族的 API 层） | 36 文件 / 3757 行 |
| `core/task/`（13 族任务本体） | 63 文件 / 11014 行 |
| **本计划点名部分小计** | **约 194 文件 / 约 25000 行** |
| （另缺，见校验结论更正 3） | `agent`/`api`/`area`/`data`/`event`/`network`/`platform`/`mixin`/`mc` 等，再加约 90 文件 / 约 12000 行 |
| **全量合计** | **约 280 文件 / 约 37000 行** |

> 更正：上一版写的"约 12000+ 行 / 100+ 文件"是按"仅 interact/inventory 的依赖闭包"说的，**低估了**。全量归位约 **37000 行**。

---

## 建议的起点

- **阶段 0 今天就能做**（改一句 `lua` 说明 + `stoppedBy` 占位）—— 零结构风险。
- ~~**先定架构分叉**~~ ✅ **已定：A 计划**（大脑留服务端，`program/` 不搬），部署目标 = **外接大脑**。
  所以阶段 1 及以后一律按「服务端大脑」写，不需要为 `program/` 留接缝。
- **阶段 1 的另一半（`sdk`）与阶段 2（`task`）要合成一刀做**（见更正 4：两者循环依赖）。
  这是所有后续的地基，越早搬越顺。**当前状态：`permission/` 已落地（2026-10-11），`sdk` 未搬。**
- 阶段 2–5 每阶段独立成 PR，逐步把"子集"替换掉，最终在阶段 6 清干净。
- ⚠️ 动手前先把更正 4 里那三处 A 计划接缝的写法定下来（尤其接缝 3：要不要换成上游的 tick 驱动）。
