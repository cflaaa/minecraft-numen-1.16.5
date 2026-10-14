# 决策台账 —— 项目方向与计划的唯一出处

> **这是本项目的「决策记忆」。** 任何关于**计划 / 方向 / 要不要搬某块 / 能不能改原版**的讨论，
> **先读这一份**，不要再去翻历史对话。
>
> 配套文档：
> - 当前执行计划 → [`RESTORE_PLAN_V3.md`](./RESTORE_PLAN_V3.md)（自包含，照着做不用读 v1/v2）
> - 技术细节与踩坑 → [`PORTING.md`](./PORTING.md)（引用时写 §号）
> - 与上游的结构差异实测 → [`UPSTREAM_COMPARISON.md`](./UPSTREAM_COMPARISON.md)
> - 历史计划稿 → [`RESTORE_PLAN.md`](./RESTORE_PLAN.md)(v1) · [`RESTORE_PLAN_V2.md`](./RESTORE_PLAN_V2.md)(v2)
>
> **规矩**：新增决策**追加**一条（编号 + 日期 + 出处 + 状态）；作废的**标 ❌ 保留**（别删，否则以后又要重新讨论一遍）。
> **最后更新：2026-10-11**

---

## 一、长期方针（Standing —— 优先级最高，任何时候都成立）

| # | 决策 | 状态 | 出处 |
|---|---|---|---|
| **D-01** | **默认口径：没有明确要求时，一律按上游（1.20.1）的功能与结构移植。** 不引入本仓自创结构，不做结构删减。 | ✅ 生效 | 用户 2026-10-11 明确 |
| **D-02** | **大脑跑在服务端。**（这是对 D-01 的**明确例外**） | ✅ 生效 | 早前会话，用户明确 |
| **D-03** | **UI 允许与上游不同，但该有的功能必须齐全。** UI 是**最后**才考虑的一层。 | ✅ 生效 | 用户 2026-10-11 明确 |
| **D-04** | **推进顺序：先自己写一版把功能跑通 → 全部功能完善后回头对齐上游**（补回原版写法）。<br>所以现在那几处自写子集是**临时跳板，不是终点**。 | ✅ 生效 | 用户 2026-10-11 明确 |
| **D-10** | **保存方式（2026-10-13 事故后定稿）**：① 动很多文件前先 `tools/git-snapshot.py` 快照；② 一刀内编译一绿就提 `WIP:` 提交，收尾 `reset --soft` 压成一个刀提交；③ 改 `.gitignore` 后必跑 `tools/git-audit.py`（`pre-commit` 钩子自动拦）；④ 模型中断回来先体检再继续；⑤ 可复用脚本进 `tools/` 入库。细则：`docs/GIT_SAFETY.md` | ✅ 生效 | 用户 2026-10-13 要求「吸取教训，更新 git 保存方式」 |

> **D-01 与 D-03/D-04 的关系**：默认按上游搬；UI 是唯一被允许「先自写、功能齐就行」的层；
> 任何为了「先跑通」而写的自写件，都必须登记为 **A/B 类**并排进归位计划（D-04 的「回头对齐」）。

### 变更的分类法（判断一个偏差合不合规，只看这四类）

| 类 | 含义 | 处理 |
|---|---|---|
| **A** | 能力子集自写（进度性，非版本/非创新） | 须回头对齐上游 |
| **B** | 薄壳自写（本可用上游件顶替） | 须消除 |
| **C** | 版本 / 架构约束允许的偏差 | 登记 + 写清理由即可 |
| **D** | 缺口（上游有、本仓没有） | 须回补 |

出处：`UPSTREAM_COMPARISON.md` §2 + v1 原则「对标原版不许结构删减 / 不引入本仓自创结构」。

---

## 二、部署目标与形态

| # | 决策 | 状态 | 说明 |
|---|---|---|---|
| **D-05** | 部署目标 = **A 计划 + 外接大脑（MCP）**，面向**多人联机服务器**。 | ✅ 已拍板 2026-10-11 | A 计划：大脑留服务端 |
| **D-06** | **玩家不需要装 mod**（vanilla 客户端能连）。 | ✅ 已实测 2026-10-11 | 核心全在服务端，`fabric.mod.json` 无 `custom` 载荷 |
| **D-07** | 外接大脑：`core/mcp/` 的 HTTP 服务端**在 MC 服务端里**，LLM 循环在**服外**；外脑驱动期间内置大脑停牌（`McpMode.driving()`）→ 服务端不存 key、不扛推理算力。 | ✅ 生效 | 代码已核 |
| **D-08** | 权限层（Gate / PermissionStore / PlacedBlocks）全在服务端、按 (主人, 同伴) 记账 → 服主集中管。 | ✅ 生效 | 代码已核 |

依据（都在代码里核过）：
- 核心全在服务端（同伴 = 服务端假玩家、大脑在服务端、征询走聊天）→ vanilla 客户端能连（客户端只剩 G 面板 + 离线账号解禁）。
- `config/numen.json` 是**服务端全局配置**（`NumenClientPrefs` 注释明说里面就是 API key）；客户端那份只存 UI 偏好，另存 `config/numen_client.txt`。

---

## 三、边界：搬 / 不搬（**准确版** —— 别再照抄「program/network 都不搬」那句粗话）

| 层 | 搬不搬 | 理由 |
|---|---|---|
| 客户端大脑（`agent/` 26 文件里的客户端那半） | ❌ 不搬 | 大脑在服务端（D-02） |
| `program/` **传输半边**（8 文件） | ✅ **搬**（2026-10-11 改） | 服务端半边**直接依赖它**（`ServerPrograms` 用 `ClientCalls`/`ClientTransport`/`ModuleSet`/`NetworkTransport.INSTANCE`），不搬编不过 |
| `program/` **服务端半边**（14 文件 / 1077 行） | ✅ **搬** | 程序管理与并发预算，与大脑在哪**无关**；`ProgramLimits` 是多人服必需品 |
| `network/`（33 文件 / 2754 行） | ✅ **搬**（2026-10-11 改） | 传输半边依赖 `Wire`/`ClientCallPayload` 等；随 `program/` 一起搬，**接缝归零** |
| `platform/`（main 8 文件，含 `services/`） | ✅ **搬** | `ServerPrograms` 要 `ServerLifecycle`；`Services` 是平台抽象 |
| `platform/`（client 侧 `ClientServices`） | ❌ 不搬 | 客户端侧 |
| `sdk/ApiTester` | ❌ 不搬 | 依赖 `program` **传输半边** |
| `task/CompanionBrain` | ❌ 不搬 | 客户端大脑的注册表 |
| `core/forge`(33) · `bench/`(11) | ❌ 不搬 | 平台/测试原因（C 类，合理） |

> ⚠️ **口径更正（2026-10-11）**：A 计划原文说的「`program/` 不搬」**说得太粗**——它把**服务端半边**
> 也一起划掉了，而那半边本来就该搬。方案乙就是把这条口径修准（详见 `RESTORE_PLAN_V3.md` §0）。

---

## 四、当前计划（进展：刀 1a~4 ✅ 完成，刀 5 🔵 进行中，刀 6 ⬜ 未开始）

**`docs/RESTORE_PLAN_V3.md`（方案乙）已作历史过程稿** —— 其「方案乙」被实测证伪，顶部已加作废横幅；
**执行口径以本文件 + `STATUS.md` 为准。**

> ⚠️ **2026-10-11 晚：方案乙被实测证伪，已改走方案甲（用户拍板）。**
> 动手编译后发现 `program/` 的**服务端半边紧耦合传输半边**（`ServerPrograms` 直接用
> `ClientCalls` / `ClientTransport` / `ModuleSet` / `NetworkTransport.INSTANCE` / `NumenNetwork`），
> 而传输半边又依赖 `network/` 的 `Wire` / `ClientCallPayload`。按乙「只留 2 处接缝」编不过
> （实测 35 个错全出在这条链上）。
> **最终做法 = 方案甲**：`program/` 全部 22 文件 + `network/` 全部 33 文件都搬，**接缝归零**。
> 本批实际规模 **137 文件 / 13082 行**（乙估 90/8814）；落地提交为刀 1a `bc18bbf`（见 §六 流水）。

- **本批（刀 1a）**：下面是**方案乙**估的构成（90 文件 / 8814 行，**数字已作废**）；按方案甲实际落了
  **137 文件 / 13082 行**，差别就在 `program` 22 + `network` 33 全搬。
  乙估构成：`sdk` 38/3718 · `task` 缺件 18/1705 · `program` 服务端半边 14/1077 · `script` 3/837 ·
  `entity` 补件 4/441 · `event/NumenEvents` 1/378 · `platform` 8/373 · `mc` 2/237 ·
  `api/Internal` 1/30 · `agent/tool/api/ToolContext` 1/18。
  ⚠️ **数字来源**：`port-sdk-task.py` 干跑 + `git status` 数出来的，**不是估的**（估过两次都错）。
- **刀表与实际进度**：1a 搬件到编译绿 → 1b 接线（挂 `CompanionTickDispatcher.tick`、登记 `@Fn` 组、接系统提示、接程序生命周期）
  → 2 `api/gear`(Wardrobe) → 3 `agent/tool/` + `script/` 替换（**开始删自写子集**）
  → 4 十三族任务本体（**大头**，逐族删 `Acts`）→ 5 收尾。
  **实际进展：1a / 1b / 2 / 3 / 4 全部 ✅ 完成**（刀 4 = `aff462f`，`Acts.java` 退役）；
  **刀 5 =「11 个世界工具改上游 `@Fn` 驱动」重接，🔵 进行中**（真机 11/11 产品侧已过，剩 7 处专用服务端潜伏崩溃待修）；
  **刀 6 = 清理（导航壳 / G 面板 / MCP 版本漂移 / server-api-check / 文档），⬜ 未开始**。逐刀台账见 `STATUS.md`。
- **接缝**：方案乙设想从 3 处降到 2 处（都在 `ServerPrograms.ownerLeft`）→ **方案甲后接缝归零**
  （`network/` 与传输半边都搬，`ownerLeft` 照上游原样）。
- **统一验收**：`:core:build` 绿 → `:core:test` 全绿 → 发行 jar 类名逐个比对无缺无多 →
  **本机方案真机不回归** → 文档 + 记忆 → git 提交（一次一刀）。
- **搬运脚本**：`tools/port-sdk-task.py`（一条命令重放，1 秒）。

### ⚠️ 已知：计划覆盖不全（2026-10-11 全量审计）

本仓还剩 ≈**500 文件 / ≈5.4 万行**缺口；v3 全部刀加起来只覆盖 ≈**206 文件 / ≈2.5 万行**
→ **计划覆盖约四成，六成没进计划**。漏得最多的见 Q-04。

---

## 五、待拍板（Open —— 需要用户定，或我给出建议等确认）

| # | 事项 | 我的建议 | 状态 |
|---|---|---|---|
| **Q-01** | `plugins/`（48 文件 / 5689 行）搬不搬？**它是上游 sdk 的对外出口**（`NumenPlugins.register`），A 计划说「内置插件 API 逐阶段补」。 | 先登记为**未决缺口**；等刀 1b 把 sdk 接通后，看 `NumenPlugins` 是否被 sdk 反向依赖再定。 | 🟡 待定 |
| **Q-02** | `ui/`（32 文件 / 3891 行）搬不搬？还是把自绘 G 面板**正式登记为 C 类偏差**？ | 按 D-03（UI 允许不同、功能齐即可）+ 用户偏好「能用原版就用原版」→ **先登记 C 类**，收尾刀再评估搬 `ui/`。 | 🟡 待定 |
| ~~Q-03~~ | ~~`ServerPrograms.ownerLeft` 的 2 处接缝怎么落？~~ | **已作废**：走方案甲后 `network/` 与传输半边都搬，`ownerLeft` 照上游原样，**没有接缝**。 | ❌ 作废 |
| **Q-04** | 计划外的整块缺口（`core/common` 的 `route`/`scan`/`build`/`combat`/`blueprint`/`debug`/`init`/`mixin`/`nav`、`api/common` 的 `agent`/`mixin`/`area`/`config`/`data`、`plugins`、`ui`）要不要补进计划？ | **补进缺口清单并逐条写明「搬 / 不搬 / 为什么」**，别让它们继续当隐形账。 | 🟡 待定 |
| **Q-05** | `program/ProgramLimits` 搬进来**不等于生效**（上游靠主循环每刻查预算）——调用点要不要一并接？ | 要，列进刀 1b 第 4 项。 | 🟡 待做 |

---

## 六、决策流水（时间倒序，简版；细节看当日日志）

| 日期 | 决策 / 事件 | 落地 |
|---|---|---|
| 2026-10-14 | **刀 5 收尾：7 处真·潜伏崩溃全部修完**。`server-api-check.py` 补两处网（① 构造器调用也抠——成员名带引号；② 输出带来源类）后一次扫出 **13 条真缺 + 1 误报**，逐条在运行时服务端 intermediary jar javap 核真、全部修完（PORTING §20）：`FakeClient` 换维度/回传聊天（packet 字段 accessor）、爬行者 `getSwellDir/isIgnited/isPowered`（实体数据键）、远程 `getEntityHitResult(Level,…)`、合成 `Ingredient`（@Invoker dissolve）、建造 `new ItemStack(block)` / `TranslatableComponent(getDescriptionId())`、Lua 查注册表 `get(id)!=null`、`Hotbar` 换 `pickSlot`/自写 `swapOffhand`。**复核只剩 2 误报**（`class_310` 客户端类、`Registry.iterator()` 继承 Iterable）。⚠️ 教训：一次只修一个调用点不够——`isPowered()` 藏在 `blastSpanOf()` 是复核第二次扫出来的 | 本次提交、STATUS.md |
| 2026-10-13 | **全量文档更新 + 台账理顺**：核对所有说明文档，查实「刀 5 = 工具改 `@Fn` 驱动重接」（不是原计划的「清理」）；原「清理」改列**刀 6**；README / `RESTORE_PLAN_V3`（加作废横幅）/ 本文件 / `UPSTREAM_COMPARISON` 同步更正 | `0b07064` |
| 2026-10-13 | **11 处「合并 jar 有、运行服务端 jar 没有」逐条 javap 核真**：关键结论「真机 8/11 通过 ≠ 这些安全」——潜伏崩全在未测路径上；确认 **7 处真·潜伏崩溃** + 2 处待现场确认；`Registry.iterator()`（继承 `Iterable`）与整类 `class_310`（只被 `core/client/*` 引用）判为误报 / 非问题 | `a6f1832`、UPSTREAM §4.4 |
| 2026-10-13 | **R4 可复用脚本入 `tools/`**：21 个脚本入库（搬运/重映射/分析 9 + e2e 8 + NAS 部署 3）+ `nas_creds.py` 口令脱敏；`git-audit.py` 修 `__pycache__/*.pyc` 误报（否则一编译就拦提交） | `8ba51cc` |
| 2026-10-13 | **刀 5 修 3 处 `NoSuchMethodError`**（全是「合并 jar 有、服务端 jar 没有」）+ **`mine` 生存模式挖不动**（根因：`handleBlockBreakAction` 第 4 参误传 0，应为 `body.getServer().getMaxBuildHeight()`） | `76c2f24`、`5fb61d1`、PORTING §19 |
| 2026-10-13 | **保存方式整改**：`core/src` 379 文件被误删其中 21 个因 `**/build/` 误伤源码包从未入库；新增 `tools/`（git-audit / git-snapshot / install-hooks）+ `docs/GIT_SAFETY.md`，钩子自动拦「该入库却被忽略」 | `3545682`、`32c2280` |
| 2026-10-13 | **环境B 专项通过**：刀 4 jar 部署 NAS 容器（25586）+ 本机**纯原版**客户端跨机进服全绿。查清「自动进服时好时坏」真凶 = 1.16.5 `--server` 受 `serversAllowed()` 门控、假 token 下取决于能否连上 Mojang；测试脚本改用死代理（`--proxyHost 127.0.0.1 --proxyPort 1`）逼离线兜底 → 14s 确定性通过 | `PORTING.md` §18、`STATUS.md` |
| 2026-10-13 | **刀 4 完成**：13 任务族 + tools/scan/route/nav/area/blueprint/combat/act/init 照搬，`Acts.java` 退役；真机验收全过（当场修 3 缺陷：结构注册表静态化、搜索/寻路 tick 挂载遗漏、ServerLevelBlockChangeMixin 补齐）；jar 968 = core 733 + pathing 235 | 刀 4 提交、STATUS.md |
| 2026-10-12 | **D-09 状态台账制度**：新建 `docs/STATUS.md` 活台账（逐刀进度+真机验收台账）；拍板**之后的修改/更新都要在实际测试完成后及时记录**；当前暂停推进（刀 4 未开工） | `STATUS.md` |
| 2026-10-12 | 刀 3 完成：上游 `agent/tool`+`agent/skill`+`NoteBook`+`Constants` 照搬进 agent；core 新增 `ToolRunner`/`WorldTool` 统一派发；删 `CompanionScript.java`；真机 15 工具 / MCP 19 工具验通 | 刀 3 提交 |
| 2026-10-11 | **D-01 ~ D-04 长期方针**由用户明确（默认按上游搬；大脑在服务端；UI 可不同但功能齐；先自写跑通再回头对齐） | 本文件 |
| 2026-10-11 | 全量结构审计 → 结论「除大脑外还有 7 处偏离，**不符合仅移植**」；计划只覆盖约四成缺口 | `UPSTREAM_COMPARISON.md` §3 |
| 2026-10-11 | 选**方案乙**，重订完整执行计划 v3（`program` 服务端半边纳入，接缝 3→2） | `RESTORE_PLAN_V3.md` |
| 2026-10-11 | ⚠️ **方案乙被证伪** → 用户拍板改走**方案甲**（program 22 + network 33 全搬，接缝归零）；本批 137 文件 / 13082 行 | 本文件 §四、刀 1a `bc18bbf` |
| 2026-10-11 | v2 合规自检：3 处接缝全不合规 → 发现「`program/` 不搬」口径太粗 | `RESTORE_PLAN_V2.md` §0 |
| 2026-10-11 | 重订计划 v2（实测 sdk↔task **循环依赖**，阶段 1/2 合并；规模 69/7360） | `RESTORE_PLAN_V2.md` |
| 2026-10-11 | 用户拍板 **A 计划 + 外接大脑**；README 状态表重写；纯原版客户端实测连通 | `D-05`/`D-06` |
| 2026-10-11 | 归位阶段 2 收尾：`GateTerrain` 接进寻路，真机 A/B/C 全过 | `PORTING.md` §17 |
| 2026-10-11 | 归位阶段 2：Gate 接进 `mine`/`use_block`/`drop`，真机 5/5 | `PORTING.md` §16 |
| 2026-10-11 | 归位阶段 1：`permission/` 17 件套 + 55 单测全绿 | `PORTING.md` §14.2 |
| 2026-10-11 | 部署 NAS docker 测试服 `mc1165-test`（25586） | `MEMORY.md` |
| 2026-10-09~10 | 引擎四层（`lua`/`agent`/`ai`）逐字搬 + `pathing` 移植完成（55 条编译差异全修） | `PORTING.md` §10.1 |

---

## 七、作废与取代（保留备查，别删）

| 项 | 状态 | 被谁取代 |
|---|---|---|
| `RESTORE_PLAN.md`（v1） | ❌ 历史 | v3（顶部有横幅） |
| `RESTORE_PLAN_V2.md`（v2） | ❌ 过程稿 | v3 |
| 「`program/` 不搬」这句口径 | ❌ 太粗 | 方案乙（只不搬传输半边） |
| **方案乙**（`program/` 只搬服务端半边 + `network/` 不搬） | ❌ **实测编不过** | **方案甲**（program 22 + network 33 全搬，接缝归零） |
| 「先 sdk 后 task」的顺序 | ❌ 写反 | sdk↔task 循环依赖，必须同批搬 |
| 「接缝 3：tick 驱动」 | ❌ 我写错 | 照搬上游 `CompanionTickDispatcher` 即可，无需接缝 |
