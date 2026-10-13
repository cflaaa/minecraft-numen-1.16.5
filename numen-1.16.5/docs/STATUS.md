# 状态台账（STATUS）

> **本文件是移植进度的唯一活台账**。每把刀在**真机验收通过后**立即更新；
> 之后的修改、更新、修 bug 同样要在实际测试完成后及时记录进来（2026-10-12 拍板）。
> 分工：决策看 `DECISIONS.md`，技术细节与踩坑看 `PORTING.md`（引用 §号），执行计划看 `RESTORE_PLAN_V3.md`。

**最后更新**：2026-10-13（刀 4 真机验收通过后）

---

## 一、快照

- **策略**：方案甲 —— 上游文件逐字照搬，只在 1.16.5 API 真不同处适配（A类照搬 / B类适配）。
- **已落地**：刀 1a / 1b / 2 / 3 / **4** 全部提交并真机验收；工作树干净。
- **构建**：`:core:build` 绿；发行 jar **968 类 = core 733 + pathing 235**；五件套嵌入
  （agent / ai / lua / slf4j-api 1.7.36 / log4j-slf4j-impl 2.8.1）。
- **下一步**：刀 5（清理收口：导航壳 / G 面板与 MCP 版本漂移 / server-api-check / 文档）。

## 二、刀进度总表

| 刀 | 内容 | 提交 | 编译/单测 | 真机验收 | 状态 |
|---|---|---|---|---|---|
| 1a | `program/` + `network/` 全量归位 core | `bc18bbf` | ✅ 全绿 | 框架层，随 1b 验收 | ✅ 完成 |
| 1b | 接线：排程器 / @Fn 登记 / 生命周期 / 平台服务 / Modules.init / `/numen program` | `9b54a9e` + `8bd440f` | ✅ 全绿 | `fn-wire-e2e.py` 全过 | ✅ 完成 |
| 2 | Wardrobe 穿脱（VanillaArmor / FailureType） | `a7dedeb` | ✅ 全绿 | `gear-e2e.py` 全过 | ✅ 完成 |
| 3 | 工具替换 / ToolRunner 统一派发 | `311b2e2` | ✅ 全绿 | `tool-reg-e2e.py` 全过 | ✅ 完成 |
| 4 | 13 任务族 + tools + scan/route/nav/area/blueprint/combat/act/init 替换 `Acts.java` | （本次提交） | ✅ 全绿 | `smoke/enum/accept4-刀4.py` 全过（真机抓出 3 缺陷当场修） | ✅ 完成 |
| 5 | 清理（导航壳 / G 面板与 MCP 版本漂移 / server-api-check / 文档） | — | — | — | ⬜ 未开始 |

## 三、已实现能力明细（按刀）

### 刀 1a —— 框架与模块归位
- 上游 `program/`(22) + `network/`(33) 整组照搬进 `core`（接缝归零）。
- 大脑服务端化：`NumenPlayer extends ServerPlayer`（假同伴玩家）；程序经 `LoopbackClient`
  进程内跑完整产品链路（fragment packing → codec → `ServerPrograms.run` → `ApiRegistry` → 主线程车道）。
- `Companions.summon` 两重载 + `findByOwnerName`（离线 UUID 用 `CompanionFactory.offlineUuid` 推导，保 .dat 同一性）。
- `JsonCompat` 作为 gson 2.8.0 兼容垫片（无 `parseString`/`keySet()`/`deepCopy`）。

### 刀 1b —— 接线
- `NumenCoreFabric.onInitialize`：`@Fn` 三组登记（api/task/module；mc 组 McApi 未搬）、
  `START_SERVER_TICK`→`CompanionTickDispatcher.tick()`（排程器心跳）、
  `DEATH/SPAWN/REMOVE` 三订阅、`ReflexRegistry.register(SpeakingLookChain)`、
  `JOIN→scheduleRestoreFor+syncRosterToOwner`、`DISCONNECT→ServerPrograms.ownerLeft`、`SERVER_STOPPED→fireStopped`。
- `ConsentDesk` 从实体 tick 移出改排程器驱动；`NumenPlayer` 补维度变更检测（1.16.5 无 `AFTER_PLAYER_CHANGE_WORLD`）；
  `Companions.onDeath` 换上游版（`tickRespawns`/`respawnDead` 闭环）。
- 平台服务实现（真机抓出的必修）：`FabricPlatformHelper` / `FabricNetworkChannel`（1.16.5 `PlayChannelHandler` 五参）/
  `FabricBlockCapabilityReader`（桩）/ `FabricNumenConfig` + `META-INF/services`×4。
  教训：`ServiceLoader` 只在真加载器里跑，单测盖不住。
- `Modules.init`（服务端注入，`Companions.OWNERS` 同伴→主人）；`/numen program <名字> <Lua>` 开发入口。

### 刀 2 —— 同伴穿脱装备
- 上游 `Wardrobe` / `VanillaArmor` / `FailureType` 照搬；`registerGear` 挂进 `NumenPlugins.register(NUMEN)`；
  `Acts.equip` 换轨 `Wardrobe.wear`。
- 1.16.5 适配：无 `InventoryMenu.ARMOR_SLOT_START`（改对身体装备写，判据同 ArmorSlot）；
  `Mob.getEquipmentSlotForItem` 是静态；无 `copyWithCount`（`copy()+setCount`）；无 `stack.is(item)`（`getItem()==`）。

### 刀 3 —— 工具替换 / ToolRunner 统一
- 上游 `agent/tool` + `agent/skill` + `NoteBook` + `Constants` 整组照搬进 `agent` 模块（MC 无关）；
  `ToolArgs` / `CompanionToolPort` 不搬（依赖 `net.minecraft`，逻辑留 core）。
- core 新增 `ToolRunner`（`registerAll`：4 大脑工具 + 把 `NumenTools.specs()` 包成 `WorldTool`；
  `specs/has/run/runProgram/bind/unbind/current/runWorldTool`）+ `WorldTool`（旧 `IToolSpec`→`NumenTool`）。
- `NumenTools` 删 lua Spec、加 `asNumenTools()+runSingle()`；CompanionBrain / McpActuator / McpServer /
  NumenCommand 全改走 `ToolRunner` 统一入口；`arrived/cancel` 改打断运行中程序。
- **删 `CompanionScript.java`**（A类自写子集，被 ScriptTool/WorldTool 路径取代）。
- 意义：刀 4 的新 `@Fn` 函数落入即可，无需再动 MCP / 派发代码。

### 刀 4 —— 13 任务族 + 工具本体（`Acts.java` 退役）
- 上游 `core/common` 主体整组照搬：`task/`（base/chain/combat/dig/fish/interact/inventory/locate/
  move/reflex/survival/wait + CompassUtil/IdSuggest/MouseButton）、`tools/`（ScanOps/StatusApi/ScanApi/
  ToolParse 等工具组）、`scan/`（BlockSearch/BlockScan/TargetIndex/RingSpiral/SearchGeometry/SearchBudget…）、
  `route/` `nav/`（BoatNav/Journey/Trip/DigQuote/Feet/Survey…）、`area/` `blueprint/` `combat/` `act/` `init/`、
  `Constants` `PlayerInv` `WorkProfile`，及 mixin 4 件（CraftingMenu/FishingHook/ItemEntity/Slot accessor）。
- **Lua API 面：17 组**（api/build/creative/fight/gear/gui/inv/locate/module/move/route/scan/status/task/
  time/use/work）；MCP 外层工具仍 19 个（刀 3 的面），任务族经 `numen.*` Lua 调。
- 真机抓出的 3 缺陷（当场修，均先 javap/官方映射核实）：
  1. **`status.self` 崩 `Missing registry: worldgen/structure_feature`** —— 1.16.5 的 structure_feature 是
     **静态**注册表，不在 `registryAccess()` 动态集里；改取 `Registry.STRUCTURE_FEATURE` 本体。
  2. **`scan.blocks` 永不回执、同伴 busy 卡死** —— 刀 4 忘了把上游 core 入口的
     `END_SERVER_TICK`：`BlockSearch::tick`（搜索心跳）+ `RouteQueries::serverTick`（寻路回执）挂上；
     `Pending` 永不完成，后续所有调用全被「手上已经有活在跑」挡住。
  3. **共享索引无喂食** —— 补 `ServerLevelBlockChangeMixin`（混入 `ServerLevel.onBlockStateChange`，
     上游同款；1.16.5 该方法存在，已核）。`required:true` + `defaultRequire:1` 兜底：注入失败即炸服。
- 编译修复全记录见当日日志（79→41→0，`isRemoved/level()/getBlockX` 等一批日志过期实为已修）。

### 早期已落地大件（本台账建档前，详见 PORTING.md）
- `permission/` 17 件套（Gate/Rule/PlacedBlocks/PermissionStore/ConsentDesk）+ `/numen permission|consent`，
  55 单测全绿；Gate 接进 `mine`/`use_block`/`drop` + Lua（ASK 抛 `NEEDS_CONSENT`），真机 5/5（§14.2、§16）。
- `core/nav/GateTerrain` 接进寻路（`CompanionPorts.TERRAIN`），真机 A/B/C 全过（§17）。
- pathing 55 条编译差异全修（§10.1）；`Sight.march` 手搬 DDA（改动须重跑 20000 射线比对）。
- MCP 外接大脑 `core/mcp/` 10 工具，`/numen tell` 唯一说话通道；离线多人修复；G 面板自绘；`ModelCatalog`。

## 四、真机验收台账（测过什么）

**环境**：本机 `.testserver/` Fabric 1.16.5 端口 **25565** + 本机客户端 + MCP 端口 8766（服务端控制台 stdin 可用）；
驱动 `_perm_live_common.py`；用例脚本在 `.workbuddy-ai/tmp/`。

| 日期 | 脚本 | 对应刀 | 验证通过的能力 |
|---|---|---|---|
| （早期） | `perm-e2e.py` 等 | permission | 权限三层模式 / 征询往返（放行·要问·记住·拒绝）/ 放置账本（§14.2、§16） |
| （早期） | `nav-gate-e2e.py` | nav | GateTerrain：密闭场地挖 / 垫 / 绕障（§17 A/B/C） |
| （早期） | `vanilla-client-e2e.py` | 兼容 | 原版客户端连带 Numen 的服务端不需客户端 mod |
| 2026-10-12 | `fn-wire-e2e.py` | 1b | ① 心跳上线 ② `@Fn` help + `task.status()` 回执 ok ③ 权限闸回归：`drop`→`ask(#1)`→`allow`→金块掉落实主 |
| 2026-10-12 | `gear-e2e.py` | 2 | ① equip 钻石头盔→`"equipped diamond_helmet in head"`，盔甲行头=Diamond Helmet ② 圆石被拒 `"stone is not something you wear"`（上游文案） |
| 2026-10-12 | `tool-reg-e2e.py` | 3 | ① 登记 **15 工具**（4 大脑 + 11 世界）② MCP `tools/list` **19 个**含 lua/skill/todo/memory/mine/drop/equip ③ lua 新路 `numen.task.status()`→ok（`{timers={}}`）④ `module.save` 落盘 ⑤ `api.help` 正常 ⑥ 权限闸回归 |
| 2026-10-12 | `module-probe.py` | 3 | `module.save/show/list` 持久化到 `config/numen/lua/<主人UUID>/my/probe.lua`；**跨程序** `numen.my.probe.ping()` 回 "pong"；`T` 的 UUID 与存储路径核实一致 |
| 2026-10-13 | `smoke-刀4.py` | 4 | 模组加载行 ✅ / 模组相关报错无 / 控制台 stdin 可写（MCP 端口属外脑进程，服务端不宿主，冒烟不判） |
| 2026-10-13 | `enum-刀4.py` | 4 | MCP `tools/list` 19 个 + Alice 召唤成功 + 工具清单存 `knife4-tools-enum.txt` |
| 2026-10-13 | `accept4.py` | 4 | ① API 索引 **17 组**全登记 ② task status/timer/stop ③ **13 任务族冒烟 10/10**（status.self/scan.blocks 修复后返值正常；move.follow·work.dig·use.block·gear.wear 按预期报参数/距离/缺料错=派发链通）④ 权限闸 drop→ask#1→allow→「丢掉了 Block of Gold ×1」⑤ 完整链一段 Lua 连跑多族；`numen-scan` 心跳日志：`stone r=16 → 1 hit(s) … 1 tick(s)` |

## 五、构建与产物基线

```bash
# 在 numen-1.16.5/ 下（.toolchain 在仓库根，用 ../ 前缀）
JAVA_HOME="../.toolchain/jdk-17.0.20.1+1" ../.toolchain/gradle-7.6.4/bin/gradle :core:build
```

- 发行 jar：`core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar`
- **类数基线：968 = core 733 + pathing 235**（改完 pathing 必须 clean 重建并逐个比对，防 loom 0.12 跨项目共享 TinyRemapper 丢类）
- 嵌入五件套：agent / ai / lua / slf4j-api 1.7.36 / log4j-slf4j-impl 2.8.1
- maven.fabricmc.net 抖动报 `Could not download` → 直接重跑即可。

## 六、已知语义与坑（速查）

- **同程序模块快照**：程序内刚 `module.save` 的模块在同程序里立即调用报 `no_function`（模块目录程序启动时快照，
  上游同款行为，非缺陷）；跨程序调用正常。
- **权限闸三前提**（真机测挖/垫）：场地密闭 + `/fill` 在世界高度内(0..255) + 目标埋脚下（PORTING §17）；
  原版不许把方块放进玩家自己身体里（§16.3）。
- **mixin 配置 `package` 独占**：该包下只许 mixin 类；`compatibilityLevel` 必须 JAVA_17；
  「没崩」≠「生效」，要看到 `Mixing ... into ...`。
- **gson 2.8.0**：无 `parseString`/`keySet()`/`deepCopy` → 走 `JsonCompat`；勿内嵌新版 gson。
- **发行 jar 丢 pathing 类**：core+pathing 均已 `remapperIsolation.set(true)`。
- 本机 `http_proxy` 会代理 localhost → Python 用 `ProxyHandler({})`，curl 用 `--noproxy '*'`。

## 七、维护约定

1. 每把刀：编译 + 单测 + jar 类数比对 + 真机验收 → **更新本文件**（含验收台账一行）→ git 提交（一次一刀）。
2. **用户拍板（2026-10-12）**：之后的修改、更新、修 bug 等，都要在**实际测试完成后及时**记录到本文件，不等攒批。
3. 计划与方向变更先过 `DECISIONS.md`，本文件只记事实与状态。

---

## 八、与原版的体量对照（2026-10-12 实测）

> 口径：只算 `src/main/java`（Fabric 可用代码）；`core/forge`(33 文件) 与 `bench`(11) 不计（本仓只做 Fabric）。
> 数字为 `find | wc` 实测。

**上游合计（Fabric 相关）**：729 文件 / 103,706 行；**本仓**：478 文件 / 72,572 行。

| 层 | 上游 | 本仓 | 覆盖 |
|---|---|---|---|
| 引擎 `lua`/`agent`/`ai` | 123 / 29,983 | 142 / 32,146 | ✅ **100%**（逐字照搬；本仓 `agent` 多出的 18 件是上游 `api/common` 的工具/技能层归位） |
| 寻路 `pathing` | 116 / 12,952 | 117 / 13,316 | ✅ **100%** |
| `api/common`（sdk·task框架·permission·program·network·script·platform·api·entity·mc…） | 226 / 24,953 | ≈177 件已搬 | 🟡 **≈78%**（缺 `agent` 胶水 26、`mixin` 10、`area` 2、`data` 部分） |
| `core/common`（**13 任务族 + tools + nav/route/scan/build/combat…**） | 178 / 26,021 | ≈6 件（gear 2 + nav 壳 3 + Acts 1） | ❌ **≈3%** ← **主体缺口在刀 4** |
| `ui` | 32 / 3,891 | 自绘 G 面板 7 / 1,680 | 🟡 版本原因顶替（C 类） |
| `plugins` | 48 / 5,689 | 0（仅用其出口 `NumenPlugins.register`） | ❌ 未搬 |
| `core/forge` / `bench` | 44 / 16,748 | — | ⬜ 不适用 / 未做 |

**功能面**：上游核心 `@Fn` API 函数 **54 个**（不含 `plugins`/`forge`/测试，实测 `grep`）；
本仓当前暴露 **15 个工具**（4 大脑 + 11 世界：say/get_status/inspect_block/scan_blocks/goto/mine/
use_block/inventory/equip/eat/drop），MCP `tools/list` **19 个**。→ 功能面约 **28%**，缺口同样是 13 任务族。

**一句话**：按 Fabric 可用代码量算，**已完成原版约 9 成**；引擎与寻路 100%、适配层与任务族（刀 4）已落地，
剩下的主要是 `ui/`、`plugins/` 两个整缺口（Q-02 / Q-01）与刀 5 清理收尾。
