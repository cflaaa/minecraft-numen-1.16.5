# 对比原版 mod（1.20.1）改动检查报告

- **生成日期**：2026-10-10
- **对比基线**：原版 = `ref/numen-1.20.1`（同作者 `com.dwinovo.numen` 的 1.20.1 版）；本仓 = git HEAD `eb6965d`（"新规则前"的状态）
- **性质**：**仅检查，未改动任何文件**（生成本报告时 git 工作树干净）

---

## 1. 总体结构对照

| 层 | 原版规模 | 本仓现状 | 性质 |
|---|---|---|---|
| engine：`lua` / `agent` / `ai` | 逐字照搬 | 逐字搬，465 单测通过 | ✅ 一致 |
| `pathing` | 117 文件 | 117 文件移植，真机跑通 | ✅ 一致 |
| `core` 本体 | — | 36 个 java 文件 | 部分自写 |
| `task/` 框架 | 20 文件 | **5 文件** + `act/Acts.java`(1) | 子集自写 |
| `sdk/` | 40+ 文件 | **0**（未搬） | 缺口 |
| `program/` | 24 文件 | **0**（判定不需要） | 缺口 |
| `script/` | 3 文件（Modules 524 行等） | `CompanionScript.java`(1, 307 行) | 自写替代 |
| `agent/tool/` | 13 文件 | `NumenTools.java` 扁平表 | 自写替代 |
| `permission/` | 17 文件 | **0**（曾搬，已回退） | 缺口 |
| `api/gear/` | 2 文件 | **0**（未搬） | 缺口 |

---

## 2. 改动清单（按"为什么不用原版"分类）

### A. 能力子集自写 —— 进度性，非版本、非创新

**① `core/task/act/Acts.java`** 替代原版 `core/task/interact/`(4) + `core/task/inventory/`(12) + `core/tools/interact/`(3) + `core/tools/inventory/`(4) 中"当前能落地的能力"。

- 原版这些任务长在 `AbstractCompanionTask`(637 行) + 导航 + 权限层 + SDK 上，**依赖闭包 >12000 行**；本仓只落地 5 个原子能力：`useBlock` / `bag` / `equip` / `eat` / `drop`。
- 具体差异（已写在文件注释里）：
  - `eat` 直接调 `ItemStack.finishUsingItem`，**跳过举起/咀嚼多刻**；
  - `equip` 直写 `inventory.setItem`，**不调原版 `Wardrobe.wear`**（属未搬的 `core/gear`），但穿的是同一槽，原版读到的装备状态仍正确；
  - `useBlock` 走 `body.hands().use`（原版服务端收右键包那条路），行为一致。
- **原因**：依赖闭包太大，先落地能力。待 TaskRecord / 权限 / SDK 补齐后需回头对齐上游写法。

**② `core/agent/NumenTools.java`** 替代原版 `agent/tool/`(13) + `sdk/`(40+) + `core/tools/*Api.java`。

- 原版用 `@Fn` 注解静态方法 + `Binder` 位置绑定 + `Dispatcher` + `LuaCodecs` + `ServerCall` 编译成 API 注册表，再由 `program/` 传输层送服务端。
- 本仓用一份扁平 `SPECS` 表（12 工具：say / get_status / inspect_block / scan_blocks / goto / mine / use_block / inventory / equip / eat / drop / lua）+ 极简 `Job` 串行执行器（UUID→正在跑的活，跨刻的 goto 交 `CompanionNav.tick`）。
- **原因**：sdk + program 两层约 64 文件尚未移植；扁平表是"召唤→说话→走→挖→报"最小实现。进度约束，非版本。

**③ `core/script/CompanionScript.java`** 替代原版 `script/`(3) + sdk 绑定层。

- 原版：Lua 模块从磁盘 `config/numen/lua/<主人>/` 加载，含安装/升级/账本/战绩/指纹全套；函数经 `ApiFunction` / `Binder` 位置绑定。
- 本仓：内存 `register(...)` 直接挂 Java 函数，5 个内置函数（`numen.inv.items` / `inv.eat` / `inv.drop` / `gear.hold` / `use.block`）；参数**只走选项表**；**无 JOB**（不能调跨刻动作，调了当场失败）；无磁盘模块、无战绩统计。

### B. 导航/网络薄壳自写（底层用已移植的 pathing）

**④ `core/nav/CompanionNav.java`**（替代原版 `core/task/nav/Trip` + `Journey` 导航任务，只是 pathing 的薄适配器）与 **`core/nav/CompanionPorts.java`**（替代原版 `nav` + `combat/Menace` 的端口/选区）。底层 A* 已随 pathing 移植，这两件是壳。

### C. 版本约束允许偏差（非创新）

**⑤ G 面板自绘**：`client/NumenPanelScreen` + `NumenField` + `NumenUi`。原版用原版 `Button` / `EditBox`，跨版本渲染同步不上；自绘反而稳。已记入 MEMORY.md 为"版本原因允许"。
**⑥ `llm/ModelCatalog`(+`State`)**：LLM 模型目录，用户明确要求加的（用户请求），非创新。

### D. 原版有、本仓缺口（非版本原因，需按"对标原版不许结构删减"规则回补）

> 这些目前是**未移植**，不是"主动删减"——但与"对标原版不许结构删减"的规则冲突，需排期归位。

1. **`sdk/`**（40+ 文件，`@Fn` API 函数框架）—— 未搬。
2. **`permission/`**（17 文件，同意/权限层）—— 曾搬、已回退，待归位。
3. **`api/gear/`**（2 文件，Wardrobe 穿脱）—— 未搬（`Acts.equip` 暂直写槽位顶替）。
4. **`task/` 框架缺 15 个文件**：`BrainChains` / `CompanionTickDispatcher` / `HandPinRelease` / `Preparing` / `TaskApi` / `TaskDispatch` / `TaskFactory` / `TaskPersistence` / `TaskRecord` / `TaskSessionHooks` / `TaskSlot` / `TimerRegistry` / `UnsupportedTask` / `chain/` / `reflex/`。
5. **其余任务族**（build / combat / dig / fish / locate / move / reflex / survival / wait / chain / perception / time 等 13 族，约 63 文件）—— 均未搬。

注：`TaskResult.java` 是**唯一一处逐字删改**（去掉了 `stoppedBy(TaskRecord.StopCause)` 方法，因为它引用未搬的 `TaskRecord`），其余 4 个框架文件为逐字搬。

---

## 3. 影响分析（对其他功能）

- **`Acts.java`**：只影响 interact / inventory 这一小撮能力本身，不动 engine / pathing / panel / mcp。`eat` 跳过咀嚼、`equip` 绕过 `Wardrobe` 这两处与原版细节不同；若日后要接 TaskRecord 的"谁改了装备"审计，需回头对齐。
- **`NumenTools` 扁平表**：决定"模型能做什么"的暴露面。原版 44 个 `@Fn` API 函数目前只暴露 12 工具子集，且**没有** sdk 的类型化返回值（原版 `Job<R>` / `Pending<R>` 在脚本里按 R 写值）。MCP 路（`McpActuator#invoke`）靠 `resolve(name)` 按名查，与原版一致。
- **`CompanionScript` 无 JOB**：脚本里调不了跨刻动作（goto / 走到 / 挖到碎）。原版 `numen.work.collect` 这类多步任务目前跑不了——影响"脚本上限"，但不影响已暴露的 5 个同步函数。
- **⚠️ 脚本限流缺口**：`lua` 工具描述里写着"400 次调用与两分钟墙钟上限"，但 `CompanionScript.run`(119–141 行) 的 `while` 循环**没有任何计数器/时钟保护**，原版 `ProgramLimits` 的安全限流并未落地。建议回补或在 `run` 里强制。
- **`permission` 未搬**：目前**任何动作都不经过同意层**——原版 `Gate` / `RuleSet` 对"破坏/放置方块"的拦截在本仓是敞开的。这是 revert 后留下的最大行为缺口，归位前同伴会无差别执行挖/放/用。
- **G 面板 / ModelCatalog**：仅影响客户端 UI 与模型选择，不影响游戏逻辑；属版本/用户原因允许的偏差。

---

## 4. 结论

- **真正"自写替代原版"且非版本原因**的只有 4 处：`Acts.java`、`NumenTools.java`、`CompanionScript.java`、`CompanionNav` / `CompanionPorts`。
- 前 3 处是**进度性自写**（依赖闭包太大，先用更薄的等价物顶上），功能覆盖是**子集而非重构**，不属"创新"也不属"结构删减"。
- **版本允许偏差** 2 处：G 面板自绘、`ModelCatalog`。
- **真正属"原版有、本仓缺口、需按规则回补"** 的见第 2 节 D 项（sdk、permission、api/gear、task 框架 15 文件、其余 13 个任务族）。
- 两处需在回补时一并修正的隐患：`TaskResult.stoppedBy` 待 `TaskRecord` 归位时补回；`CompanionScript` 的脚本限流待落地。

---

## 附录：给非技术读者的答疑

### A-1. "依赖闭包 >12000 行"是什么？开源还是闭源？要适配 1.16.5 吗？适合移植吗？

- **闭包（依赖闭包）**：指"要想让那几件小功能（开箱子、吃东西、穿装备）真正按原版方式跑起来，必须一并搬过来的所有上游代码"——它不只是那几个文件，还拖着一整条公共底座（任务基类、自动寻路、同意/权限、API 框架），全部加起来超过 12000 行。
- **开源还是闭源**：**开源**。原版和本仓是同一个作者（dwinovo）的同一个 mod（Numen / 言出法随），`ref/numen-1.20.1` 就是它的源代码克隆，全部可读可搬。
- **要适配 1.16.5 吗**：**要**。原版跑在 1.20.1 上，它调用的很多 Minecraft 内部接口在 1.16.5 里名字、位置甚至有没有都不一样，搬过来时几乎每一处都要对照官方映射改一遍（这正是 PORTING.md 里记录的主要工作量）。
- **适合移植吗**：**适合，而且是计划内的**。它和本仓是同根，逻辑上能逐层搬。之所以先写"子集"而不是一次搬全，只是因为 12000 行的底座太大、且互相套娃，先把"能用"的能力做出来，底座补齐后再换回原版写法（见 A-2）。

### A-2. "回头对齐上游写法"是什么意思？未来还能回归原版移植方案吗？

**能，而且这就是计划。** 现在 `Acts.java` 是我们为了"尽快让能力跑起来"写的简化版（5 个动作，直来直去）。等上游那套底座——`TaskRecord`（任务记录）、权限层、SDK——被搬过来之后，我们会把 `Acts.java` 改写成和原版一样的结构（用原版那套"任务类 + 记录 + 权限"的写法），让本仓和原版在代码层面重新对齐。**所以现在走的"子集"是临时跳板，不是终点；最终仍回到原版移植方案。**

### A-3. 用大白话解释第 2、第 3 点（NumenTools / CompanionScript）

- **第 2 点（工具表 `NumenTools`）＝"AI 能对这个世界做什么"的清单。**
  上游做了一张很完整、很讲究的清单：44 种动作，还配了一整套复杂的"接线 + 传输"系统把动作送进游戏。我们暂时做成了一张**简单的清单：12 种动作**（说话、看状态、看方块、扫一圈、走过去、挖一格、用方块、看背包、穿、吃、丢、跑脚本）。**能用，但只是上游的缩小版**——等上游那套复杂系统搬过来，我们再换成原版的完整版。

- **第 3 点（脚本 `CompanionScript`）＝"让 AI 写一小段程序，一次做完很多步"。**
  比如 AI 想"先翻背包、找到面包、吃掉、再报告"，与其分四次调工具，不如写一段小程序一次跑完。上游的做法是：这些程序**存在硬盘文件里**，还有一套"安装 / 升级 / 记账 / 统计"的管理系统；函数通过一套绑定机制接到游戏。我们暂时把程序**放在内存里**，只提供 5 个最常用的内置命令，而且**不能写需要跨好几秒才能完成的动作**（比如"走过去"这种）。这也是因为上游那套"硬盘模块管理 + 绑定系统"还没搬。

### B. "导航/网络薄壳自写"是什么意思？重写自动寻路了吗？

**没有重写自动寻路。** 真正的自动寻路（绕开障碍、上下台阶、找路的 A* 算法）**早就作为 `pathing` 模块从原版搬过来了**（117 个文件，真机验证能跑）。

所谓的"薄壳"（`CompanionNav`）只是一小段**连接件**：它把"已经能用的寻路"接到同伴身上——每个游戏刻告诉同伴"朝目标再走一步"，走完了回报一声。它本身不含寻路算法，只是"调用已搬好的寻路"。

（`CompanionPorts` 同理，是把原版"选目标 / 端口"那部分接过来的一小层壳。）

### C. 关于 G 面板自绘 与 `ModelCatalog`（用户偏好）

- 这两处目前是"自写/自加"而非搬原版，属于被允许的偏差（G 面板因跨版本渲染、ModelCatalog 因用户要求）。
- **用户偏好**：可以允许，但**如果可能，更希望先移植原版版本**，而不是长期保留自写版。已记录此偏好，后续归位时优先用原版替换。

---

## 3. 全量结构对照（2026-10-11 复点）

> 第 1 节那张表是 2026-10-10 写的，当时**只点了本仓自己的东西**。这次把**上游整个仓库**点了一遍，
> 结论是：**上游比之前掌握的大得多，缺口清单有漏账**。下面数字全部实测（`find | wc`）。

### 3.1 上游有多大（只算 `src/main`）

| 模块 | 文件 / 行 | 本仓 |
|---|---|---|
| `lua` | 50 / 18936 | ✅ 已逐字搬（50 / 18936） |
| `agent` | 44 / 6662 | ✅ 已逐字搬（44 / 6663） |
| `ai` | 29 / 4383 | ✅ 已逐字搬（30 / 4499） |
| `pathing` | 116 / 12952 | ✅ 已搬（117 / 13316） |
| `api/common` | 226 / ≈26000 | 🟡 只搬了 `permission/`(17)；v3 再搬 ≈90 |
| `core/common` | 178 / ≈26000 | 🟡 只搬了 6（task 5 + act 1） |
| `core/fabric` | 6 | 🟡 本仓自己写了加载器胶水 |
| `core/forge` | 33 | ❌ 只做 Fabric |
| `plugins` | 48 / 5689 | ❌ **从未进过任何计划** |
| `ui` | 32 / 3891 | ❌ 用自绘 G 面板顶替 |
| `bench` | 11 / 998 | ❌ 基准测试 |
| **合计** | **≈796 / ≈107600** | **298 / 53881** |

**本仓 ≈298 文件 / 5.4 万行；上游 ≈796 文件 / 10.8 万行 → 按文件约 37%，按行数约 50%。**

### 3.2 两个模块的内部构成（这才是缺口的主体）

**`api/common`（226 文件）**

| 包 | 文件 / 行 | 状态 |
|---|---|---|
| `sdk` | 39 / 4026 | ⏳ v3 刀 1a |
| `task` | 24 / 2377 | 🟡 已搬 5，v3 补 18 |
| `permission` | 17 / 2216 | ✅ 已搬 17 |
| `entity` | 21 / 3202 | 🟡 本仓自写 5；v3 补 4 |
| `agent`（大脑的 MC 侧胶水） | 26 / 2952 | ❌ **不在计划里** |
| `network` | 33 / 2754 | ❌ A 计划不搬 |
| `program` | 22 / 1730 | 🟡 v3 搬服务端半边 14 |
| `script` | 3 / 837 | ⏳ v3 刀 1a |
| `mc` | 5 / 561 | 🟡 v3 只搬 2 |
| `event` | 1 / 378 | ⏳ v3 刀 1a |
| `platform` | 8 / 373 | ⏳ v3 刀 1a |
| `mixin` | 10 / 375 | ❌ **不在计划里**（本仓自写 2 个） |
| `api` | 8 / 722 | 🟡 v3 只搬 `Internal`(1) |
| `area` | 2 / 785 | ❌ **不在计划里** |
| `data` | 2 / 1449 | 🟡 本仓只有词表子集 |
| `config` | 1 / 84 | ❌ 不在计划里 |

**`core/common`（178 文件）** —— 13 族任务与它们的底座都在这

| 包 | 文件 / 行 | 状态 |
|---|---|---|
| `task` | 63 / 11014 | ⏳ v3 刀 4 |
| `tools` | 36 / 3757 | ⏳ v3 刀 4 |
| `nav` | 13 / 2027 | ❌ 本仓只有 `CompanionNav` 壳 |
| `route` | 7 / 1504 | ❌ **不在计划里** |
| `act` | 6 / 1526 | 🟡 本仓自写 `Acts`(1) |
| `scan` | 9 / 1475 | ❌ **不在计划里** |
| `build` | 10 / 1502 | ❌ **不在计划里** |
| `combat` | 9 / 939 | ❌ **不在计划里** |
| `blueprint` | 2 / 622 | ❌ **不在计划里** |
| `gear` | 2 / 468 | ⏳ v3 刀 2 |
| `debug` | 3 / 167 | ❌ 不在计划里 |
| `init` | 1 / 91 | ❌ 不在计划里 |
| `mixin` | 9 / 253 | ❌ 本仓自写 2 个 |
| `data` | 2 / 172 | ❌ 不在计划里 |
| 顶层 5 件 | 5 / — | ❌ 不在计划里 |

### 3.3 结论一：**结构差异不小，而且计划漏了大约一半的缺口**

- 本仓还剩的缺口 ≈ **500 文件 / ≈5.4 万行**（上游 ≈796 减去已搬的 ≈239 个上游文件）。
- v3 全部刀加起来覆盖 ≈ **206 文件 / ≈2.5 万行**（刀1a 90 + 刀2 2 + 刀3 15 + 刀4 99）。
- → **计划覆盖约四成，剩下六成没进计划**。漏得最多的是：`plugins/`(48)、`core/forge`(33)、
  `ui/`(32)、`api/common` 的 `agent/`(26) 与 `network/`(33) 与 `mixin/`(10)、
  `core/common` 的 `route/`(7) `scan/`(9) `build/`(10) `combat/`(9) `blueprint/`(2) `debug/`(3) `init/`(1) `mixin/`(9)。

### 3.4 结论二：**除「大脑在服务端」外，还有 7 处偏离，所以「不符合仅移植」**

| # | 偏离 | 类别 | 现状 | 计划覆盖？ |
|---|---|---|---|---|
| 1 | 大脑在服务端、`program/` 传输半边不搬 | 架构（**用户已拍板**） | 在 | 是（A 计划） |
| 2 | `Acts` / `NumenTools` / `CompanionScript` / `CompanionNav`·`CompanionPorts` 4 处自写子集 | **A / B** | **仍在** | 是（刀 3/4/5 删） |
| 3 | `network/` 不搬 → `ServerPrograms`/`NumenEvents` 里 2~3 处接缝 | 架构（**新**） | 待做 | 是（v3 §五、§七.4） |
| 4 | `ui/`(32 文件) 不搬，自绘 G 面板顶替 | C（版本） | 在 | ❌ **从未列入任何计划** |
| 5 | `plugins/`(48 文件 / 5689 行) 不搬 | **?** | 无 | ❌ **从未进过计划**——而它正是上游 sdk 的对外出口（`NumenPlugins.register`） |
| 6 | 本仓 `mixin/` 自写 2 个（上游 10 个） | **B** | 在 | ❌ 未登记 |
| 7 | `data/ModLanguageData` 只搬词表子集；`mc/` 只搬 2/5；`api/` 只搬 1/8 | **A** | 在 | 🟡 部分（v3 只挑需要的几个） |
| — | `core/forge`(33)、`bench/`(11) 不搬 | C（平台/测试） | — | ❌ 未登记（但合理） |

**回答「除了大脑外其他符合仅移植吗」：不符合。**
第 2、6、7 条是**自写子集**（A/B 类），第 3 条是**新的架构偏离**（不只是大脑那一条），
第 4、5 条是**从来没进过计划的整块缺口**。真正合规的只有「已搬的引擎层 + `pathing` + `permission`」这一块。

### 3.5 建议

1. **先修计划**：把 3.2 两张表里标 ❌ 的包**补进缺口清单**（至少 `plugins/`、`ui/`、`agent/`、`mixin/`、
   `route/`、`scan/`、`build/`、`combat/`、`blueprint/`、`debug/`、`init/`），并在计划里写明
   「搬 / 不搬 / 为什么」，别让它们继续当隐形账。
2. **再定口径**：`plugins/` 是上游的插件出口，A 计划说「内置插件 API 逐阶段补」——
   那 `plugins/` 到底搬不搬？这是**第 3 个要拍板的事**（前两个是 tick 驱动与事件去向，已定）。
3. **`ui/` 与 G 面板**：用户偏好是「能用原版就用原版」。要么搬 `ui/`(32 文件)，要么把自绘 G 面板
   正式登记为 C 类偏差并写清理由。


---

# 4. 现状复点（2026-10-13 下午，脚本实测）

> ⚠️ **上面 §1~§3 是 2026-10-11 的历史稿**（那时 Acts.java 还在、13 任务族未搬）。
> 以本节为准。口径：只算 `src/main/java` 的 `.java`；上游取 `core/common` + `api/common`，本仓取 `core`。

## 4.1 总进度

| 维度 | 上游 | 本仓 | 覆盖 |
|---|---|---|---|
| core/common + api/common | 394 文件 / 50,340 行 | 377 文件 / 50,034 行 | **96%** |
| pathing | 116 / 12,952 | 117 / 13,316 | **101%** |
| agent 模块 | 44 / 6,662 | 62 / 8,709 | **141%**（含上游 `core/agent` 胶水归位） |
| ai | 29 / 4,383 | 30 / 4,499 | **103%** |
| lua | 50 / 18,938 | 50 / 18,938 | **100%** |
| ui（整模块） | 32 / 3,891 | 0 | **0%** ⬜ |
| plugins（整模块） | 48 / 5,689 | 0 | **0%** ⬜ |

**@Fn API：66 个 / 14 组**（api/build/creative/fight/gear/gui/inv/locate/module/move/route/scan/status/task/time/use/work）；
任务框架 `task` 87 文件 100%；53 项单测文件 / **527 用例 0 失败**；jar **961 类 = core 726 + pathing 235**。

## 4.2 真实缺口（脚本按文件名比对，已排除「搬进 agent 模块」的误报）

| 包 | 缺件 | 判断 |
|---|---|---|
| `data` | ModBlockTagData、ModItemTagData、package-info | 数据包标签层，**D 类待补** |
| `debug` | DebugCommands、PathDebug、PathDebugRenderer | 调试命令/渲染，**D 类待补** |
| `mc` | BrigadierHelp、McApi、Problem | McApi 为刀 1b 已知未搬；**D 类待补** |
| `config` | ConfigMigrations | 配置迁移；**D 类待补** |
| `entity` | NumenCommands、package-info | 上游命令表；本仓用 `command/NumenCommand` 自写顶替 → **A 类** |
| `mixin` | 10 件（ ArchitecturyPlayerHooks / MixinEntityVehicleControl / MixinServerGamePacketListener / PlayerAdvancementsReward / ItemEntityDrops / LivingEntityTake / ServerLevelDrops / ServerPlayerGameModeDrops / ServerPlayerGameModeAccessor / package-info） | 掉落/触碰那 4 个是**本仓用 permission 层自有 mixin 替代**（C 类）；其余待逐件判 |
| `agent`（core 包内） | request/*（RuntimeState/AgentRequestContext/BodySnapshot/MemoryPreamble/SystemPromptComposer）、skill/*（SkillInfo/SkillInjection/SkillMarkdown）、tool/ToolArgs、tool/CompanionToolPort | 本仓用 `CompanionBrain` + `NumenSystemPrompt` 自写顶替 → **A 类**；ToolArgs/CompanionToolPort 依赖 MC，逻辑留 core（已登记） |
| 顶层 | CommonClass、NumenPaths、package-info | Fabric 入口合并进 `NumenCoreFabric`（C 类） |

## 4.3 本仓独有（差异）= A/B/C 三类偏差 + 外接大脑

| 类 | 位置 | 处置 |
|---|---|---|
| **A** 自写待对齐 | `CompanionBrain`、`NumenSystemPrompt`、`tool/ToolRunner`+`WorldTool`+`WorldTools`、`command/NumenCommand`、`config/NumenConfig`、`mc/ItemStacks`+`NbtTypes`、`mixin/SlotAccessor` | 排进归位计划 |
| **C** 版本/偏好允许 | `client/`（G 面板自绘 7 件）、`llm/ModelCatalog`（2 件，用户要求）、`mixin/client/NumenOfflineMultiplayerMixin` | 登记即可 |
| 新增能力 | `mcp/`（4 件，外接大脑）、`net/NumenPanelNet`+`NumenPanelServer` | 上游没有，属**新增**（D-06 外接大脑） |
| 平台入口 | `NumenCoreFabric` | 必要 |

## 4.4 已知问题（2026-10-13）

1. **刀 5 已真机验收（2026-10-13，`76c2f24` + `5fb61d1`）**：11 个世界工具逐一真机直调，**11/11 产品侧全过**
   （say/get_status/inventory/scan_blocks/inspect_block/equip/drop/eat/use_block/mine/goto；
   equip 回 `no diamond_helmet`、eat 回 `already full` 是上游正确报错，不是断言失败）。
   **`mine` 生存模式挖不动已修**（PORTING §19：`handleBlockBreakAction` 第 4 参误传 0，应为
   `body.getServer().getMaxBuildHeight()`）；真机 `accept5` 5-10 ✅ `{"dug":1,"left":0}`。
2. **「合并 jar 有、服务端运行 jar 没有」11 处 —— 已逐条 javap 核（2026-10-13）**：
   用**编译用的 named 合并 jar** vs **运行时服务端 intermediary jar**（`.testserver/.fabric/remappedJars/…/server-intermediary.jar`）
   逐类 javap 比对。**关键结论：真机 8/11 通过并不代表这些安全**——它们全在 11 个世界工具**没覆盖**的路径上
   （近战、远程瞄准、合成、建造细节、换维度、回传给同伴的聊天）。逐条判定：

   **❌ 真·潜伏崩溃（编译有、服务端运行 jar 无，路径可达）：**

   | intermediary | named | 调用点 | 触发路径 |
   |---|---|---|---|
   | `class_2708.method_11737 ()I` | `ClientboundPlayerPositionPacket.getId()` | `FakeClient.onOutbound:86` | 换维度传送握手回执——`awaitingTeleport` 靠它取编号 |
   | `class_2635.method_11388 ()Component` | `ClientboundChatPacket.getMessage()` | `FakeClient.onOutbound:90` | 服务端回传给同伴的话：死亡广播、指令回执、主人对同伴说话、动作栏 |
   | `class_1548.method_6872` | `Creeper.getSwellDir()/isIgnited()` | `Menace.fusing():107` | 同伴靠近爬行者做危险评估时 |
   | `class_1675.method_18075` | `ProjectileUtil.getEntityHitResult(...)` | `Ballistics:301`（`Crosshair:52` 注释已警告「客户端才有」） | 远程瞄准/弹道 |
   | `class_1856.method_8105 ()[ItemStack` | `Ingredient.getItems()` | `CraftOps:263/315`、`RecipeBook:99`、`RecipeProbe:50` | 合成配方探测 |
   | `class_2248.method_9574` | `Block.getCloneItemStack(BlockGetter,BlockPos,BlockState)ItemStack` | `BuildStates:130` | 建造规划取「这一格会变成什么物品」（方块拾取） |
   | `class_2348.method_10250 (ResourceLocation)Z` | `Registry.containsKey(ResourceLocation)` | `Builtin:195`、`Target:45` | Lua 内建/目标解析查注册表键 |

   **⚠️ 误报 / 非问题：**
   - `class_2378.iterator()`：**误报**。`class_2378`(Registry) implements `class_2359<T> extends java.lang.Iterable<T>`，
     `iterator()` 由 `Iterable` 继承，服务端运行 jar 照样有（脚本父类链解析的限制）。
   - 整类 `class_310`（客户端 `Minecraft`）：**非问题**。只在 `core/client/*`（客户端专属层，由 `fabric.mod.json`
     client 入口加载）里引用；`server-api-check.py` 自己也报 `[OK] 没有任何共享类引用 net.minecraft.client.*`。
   - `class_1690.method_7535 (ZZZZ)V`（`Boat.controlBoat`，经 `BoatAccessor` `@Invoker`）：**待现场确认**。
     它是 **required** `@Invoker`，但服务端**启动与 8/11 全程无 mixin 报错**——说明它已在服务端解析成功
     （要么服务端运行 jar 有该方法、要么 `BoatAccessor` 只在客户端 mixin 组）。仅在「同伴坐船驾驶」路径现场验。
   - `class_2248.method_9518 ()MutableComponent`：**调用点未确认**。该描述符不匹配 build 代码里明显的
     `getName()`(返 `Component`)/`getDescriptionId()`(返 `String`)，需 javap `-c` 反查字节码定位是哪个源调用。

   **修法（与 `76c2f24` 三处同一套路）**：每条在真机运行 jar 上 javap 核一个 1.16.5 等价写法——
   包没有的服务端成员走 `@Accessor` mixin 取私有字段（`getMessage`/`getId` 这类），或换 1.16.5 有的入口
   （`getSwellDir` → 读 `EntityData`，`getEntityHitResult` → `inputDDA`/手写射线，`getItems` → `Ingredient.values()`，
   `containsKey` → `registry.get(id)!=null`，`getCloneItemStack` → `block.asItem()`）。属**潜伏**，不挡当前提交，
   逐条修、每条真机验（因为只在专用服务端复现）。
3. **`scan_blocks` 语义变更**：原「扫全方块统计数量」→ `numen.scan.map`（地形俯视图）；按 id 找矿改用 Lua `numen.scan.blocks`。
4. **21 个反编译恢复的文件无注释**（`core.build`、`core.task.build`，见提交 `32c2280`）——逻辑与刀 4 一致，可读性差，待补注释。
5. `server-api-check.py` 报 12 处「专用服务端可能炸」，疑似把客户端包类引用误计入（输出自相矛盾），**未细查**。
6. jar 类数基线 968 → **962**（刀 5 删 NumenTools/Acts/CompanionNav 后 961，core 727 是事故反编译恢复后的实际值；
   pathing 235 未丢）。
7. **刀 5 的 9 条改动已落 WIP**，按 D-10 R2 需在验收后 `reset --soft` 压成一个正式刀提交。
