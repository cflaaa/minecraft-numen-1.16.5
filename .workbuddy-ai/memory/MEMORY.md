# 我的世界 — 项目长期笔记

## 项目：Numen Minecraft 1.16.5 移植版

位置：`numen-1.16.5/`（`ref/numen-1.20.1/` 是上游 1.20.1 分支的浅克隆，供比对用）

### 架构约定（照上游）

三层，**按「依赖 Minecraft 的程度」分层，越往下越纯**：

| 层 | 目录 | 是否依赖 MC |
|---|---|---|
| LLM 连接 | `ai/` | 否（纯 JVM，5 套协议） |
| 大脑内核 | `agent/` | 否（ReAct 循环 + 事件队列） |
| Lua 沙箱 | `lua/` | 否（自研 Lua 5.2 虚拟机） |
| 模组本体 | `core/` | 是（1.16.5 适配层） |

**改代码时守住这条线**：任何与 MC 版本无关的逻辑都不要写进 `core/`，
这样 `ai`/`agent`/`lua` 才能继续逐字同步上游。

### 硬约束

1. **本机能构建了**（2026-10-09 实测打通）。要点：`maven.fabricmc.net` 是**抖动**不是被墙，
   把 `maven.aliyun.com/repository/public` 排到仓库列表**最前**（settings.gradle 的
   pluginManagement + build.gradle 的 subprojects 两处都要），第一次报 `Could not download` 就**重跑**。
   跑法：`JAVA_HOME=.toolchain/jdk-17.0.20.1+1` + `.toolchain/gradle-7.6.4/bin/gradle :core:build`。
   产出 `core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar`；引擎层 465 个单测全过。
2. **Java 17**（不是 1.16.5 生态默认的 8），因为引擎层用了 record。
3. **官方 Mojang 映射**，与上游一致，不要改成 Yarn。
4. 引擎层三个模块是**逐字照搬**的，改动前先确认不是为了迁就某个本地问题——
   一旦分叉，以后同步上游就变成手工活。
   （例外：`agent` 必须 `implementation project(':lua')`、`ai` 的 gson/slf4j 必须 `api` 而非
   `compileOnly` —— 这两处本仓曾与上游不一致，是构建失败的根因。
   另见下面第 6 条：gson 有 24 处**版本无关**的兼容改写，是允许的分叉。）
5. **`fabric.mod.json` 的 Fabric API 依赖写 `"fabric": "*"`，不是 `"fabric-api"`。**
   1.16 线 Fabric API 的 mod id 就叫 `fabric`（`provides` 为空），`fabric-api` 是 1.19 后才改的名；
   Fabric Loader 里也没有 `fabric-api` 别名。照抄上游的 `"fabric-api"` 必然失败。
   ⚠️ 同一条报错文本（`HARD_DEP_NO_CANDIDATE ... depends fabric-api`）还有另一个成因是
   「mod 自身被 java 谓词剔除」，**两条都要查**。
6. **宿主自带库的版本要单独核对，别只核对 MC 的 API**（详见 `docs/PORTING.md` 3.3/3.4）：
   - 1.16.5 **没有 slf4j**（1.17+ 才有）→ 必须随 jar 内嵌
     `slf4j-api:1.7.36` + `log4j-slf4j-impl:2.8.1`（版本要与游戏自带 log4j 对齐）；
     只发 api 会退化成 NOP logger，日志静默消失。
   - 1.16.5 的 gson 是 **2.8.0**（上游是 2.10.x）→ 24 处用了 2.8.6+/2.8.2+ 的 API，
     已改成两代通用的写法 + 新增 `ai/.../agent/json/JsonCompat.java`。
     **编译期把 gson 钉在 2.8.0**，误用新 API 会当场报错；单测用 `testImplementation` 抬到 2.10.1。
   - ⚠️ **不要用「内嵌新版 gson」解决**：Fabric 是模组类路径优先（能盖住），
     但会造成双类加载器，Fabric API 的 `LootJsonParser` 会 ClassCastException。
     能用两代通用写法解决的，就不要引入第二份库。

### 移植方法论

跨版本移植**不要凭印象写 API**。用 `docs/mm.py` 查官方映射（注意文件是
**左可读名、右混淆名**，反的 proguard 格式）。查证过的差异表在 `docs/PORTING.md` 第二节。

⚠️ **`mm.py` 查不出访问修饰符**：成员行没有 public/protected。
所以「上游直接读某字段」的地方（`jumping` / `onGround` / `yRot`）**只有真编一次才算核过**。
跨版本时，对**字段访问**比**方法调用**危险得多——1.16.5 里 `Entity.yRot` 是 public 字段、
`LivingEntity.jumping` 和 `Entity.onGround` 是 protected、`AABB` 还没有 `distanceToSqr`。

⚠️ **`mm.py` 还会列出「真实 jar 里没有的成员」。** 它描述的是**未混淆构建**的源码级 API，
不等于 ProGuard 之后发布 jar 里的成员——没被用到的会被裁掉。
实例：`net.minecraft.client.User.getType()` 在映射里写着 `-> f`，但 1.16.5 真实 jar 里
`dkm`(User) 只有 `a()`~`e()`，`f()` 不存在，编译直接报「找不到符号」。
**规则：`mm.py` 只用来发现候选差异；任何真要调用的成员，都在真实 jar 上 `javap` 核一遍。**

### mixin（本仓 2026-10-09 才第一次真正用上）

`core/build.gradle` 里 Loom 0.12.5 已配好 `mixin { defaultRefmapName = "numen.refmap.json" }`，
`numen.mixins.json` 的 `refmap` 与之一致。三个必须记住的点：

1. **客户端 mixin 放在 `com.dwinovo.numen.core.client.mixin`，并写进 mixins.json 的 `client` 数组。**
   放 `mixins`（通用）会让专用服务端一启动就炸（`Minecraft` 不存在）。
   包名前缀是 `core.client.` 也让 `server-api-check.py` 的反向检查自动放行。
2. ⚠️ **`compatibilityLevel` 必须 ≥ mixin 类的字节码版本**，本仓是 `options.release = 17`
   → 必须写 `JAVA_17`。写成 `JAVA_8` 的后果是**静默失效**：不报错、不崩溃、
   日志里连 `Selecting config ...` 都没有，配置压根没被选中。
   `required: true` + `defaultRequire: 1` **救不了**这种情况（它们管的是"选中后注入失败"）。
3. **怎么确认它真的生效**：加 `-Dmixin.debug=true -Dmixin.debug.verbose=true` 启动，
   日志里必须出现 `Mixing <你的Mixin> from <配置名> into <目标类>`。
   **"没崩"不等于"生效"。** 那条 `Compatibility level JAVA_17 ... higher than max (JAVA_13)`
   的 WARN 是无害的，别为它降级。

### 日志：占位符是 slf4j 的 `{}`，不是 `%s`

`NumenCore.info/warn/error` 把 format 直接交给 slf4j 的 `Logger`，
全项目 28 处都用 `{}`。写成 `%s` 不会报错，只会把参数全丢掉、原样打印 `%s`。

### 离线账号的「多人游戏已被禁用」— 已查清并修掉（2026-10-09）

**不是"离线模式不支持多人游戏"，而是令牌校验失败时默认判否。**
链路：`Minecraft.allowsMultiplayer() = multiplayerEnabled && socialInteractionsService.serversAllowed()`；
服务对象由 authlib 的 `YggdrasilSocialInteractionsService` 构造，它请求
`https://api.minecraftservices.com/privileges`（Bearer 假令牌 → **401 + `{"path":"/privileges"}`**）；
authlib 对非 2xx **把错误流当正常响应返回、不抛异常**，而 `PrivilegesResponse`
的字段是预初始化的（`enabled` 默认 false）→ `serversAllowed() = false` → 按钮变灰。
唯一的兜底 `OfflineSocialInteractions`（全 true）只在**连不上认证服务器**时才用到——
这就是"断网启动就能联机"的真正原因。

**修法**：`core/client/NumenOfflineMultiplayer.java` + `core/client/mixin/NumenOfflineMultiplayerMixin.java`，
在 `allowsMultiplayer()` / `allowsChat()` 返回 false 时掰回 true（与社区修复 mod 同手法）。
开关：`-Dnumen.offlineMultiplayer=false`。详见 `docs/PORTING.md` 第八节。

⚠️ **验证客户端时别去动用户开着的游戏。** 用户的 PCL 客户端会占住
`versions/<VER>/logs/latest.log`（`WinError 32`）。用 `verify-offline-mp.py`：
换一个 `--gameDir`（`%TEMP%\numen-verify`，mods 里只拷 numen + fabric-api），
assets/libraries/versions 只读共享，两边互不干扰。

### G 面板（按 G 打开的图形界面）— 已落地（2026-10-10）

`core/client/`：`NumenPanelScreen`（整块自绘，两页签：同伴 / 设置）、`NumenField`（自写输入框）、
`NumenUi`（自绘工具）、`NumenPanelState` + `NumenPanelNet`（服务端 → 客户端的快照通道）。
**不用原版 Button/EditBox**——跨版本时渲染改动同步不上，自绘反而稳。
自绘的关键纪律：**绘制时把可点区域记进 `hits`，点击只查这张表**，
「画在哪」和「点到哪」必须同一段代码算，分开算两遍是这类界面最经典的错位来源。

⚠️ **布局溢出不只是难看，会抢热区**：面板高度被窗口高度卡住
（`面板高 = min(280, 窗口高-20)`，正文区再减 HEADER_H 24 + STATUS_H 22）。
溢出的按钮会在状态栏的主题开关上再压一个热区，点下去触发错的动作。
所以每个块画之前都要用 `fits(y, h, bottom, what)` 看守，放不下就**整块不画**。
实测设置页原要 221 像素、241 高的窗口只给 195 → 溢出；改两列 + 合并端点/令牌行后只要 153。

⚠️ **GUI 布局没法靠「启动一下」验证**：布局代码只在那一页真被画出来时才跑。
埋了 `-Dnumen.panel=settings|companions`（不设时行为不变）让客户端自动开页，
第一次绘制打尺寸日志、被 `fits()` 拦下的块打「放不下」日志；
`verify-panel.py` 喂参数 + 钉死窗口尺寸（`options.txt` 的 `overrideWidth/Height`）+ 读日志判定。
**踩过的坑**：标题界面上 `mc.screen` 永远不为 null（就是 `TitleScreen`），
判定条件写 `mc.screen == null` 会导致永远不触发（脚本跑满 240 秒面板一次没开）。

### 寻路模块（`pathing`）— 已接进游戏并**真机跑通**（2026-10-10）

`pathing/`（117 个文件）**只对着原版 Minecraft 编译**，classpath 上没有 `ai`/`agent`/`lua`/`core`，
边界由编译器守。它是照 1.20.1 写的，55 条编译错误已全部修完：
`:pathing:compileJava` / `:pathing:build` / `:core:build` 全绿。

**55 条全是同一类问题**（1.16.5 上没有的 API），没有一条是「做不了」的。最值得记的几条：
`serverLevel()`→`getLevel()`（8 处）、`BlockPos.containing`→`new BlockPos`（7 处）、
`ItemStack.is(item)`→`getItem()==item`（6 处）、`setYRot/setXRot/setMaxUpStep`→**public 字段直接赋值**。
完整对照表在 `docs/PORTING.md` §10.1。

**三处不能靠改名解决的**：`AABB.distanceToSqr`（没有 → 写在 `world/Reach`）、
`PalettedContainer.copy()`（没有 → `LevelChunkSection.write/read` 序列化往返）、
`BlockGetter.traverseBlocks`（签名不同且 `ClipContext` 传 null 会 NPE → 原样搬进 `Sight.march`）。

⚠️ **`Sight.march` 是手工搬的浮点代码**（逐格推进 DDA，连 `-1.0E-7` 容差都照抄）。
已用 20000 条随机射线与独立实现（事件法）比对过，0 条不一致。**以后改它必须重跑这个比对。**

⚠️ **`settings.gradle` 里 `include('pathing')` 已放开**：老注释说「Loom 会把同构建里所有 Loom
子项目当嵌套模组收进 jar」**实测不成立**——只有 `core/build.gradle` 里显式
`include project(':xxx')` 的才进 `META-INF/jars`，pathing 不在其中。

⏭ **下一步（未做）**：补权限层（上游 `GateTerrain`/`Gate`）与放开改地形；
上游 `nav/` 其余件（`Trip`/`Journey`/`NavText`/`Survey`/`BoatNav` 等）。
**默认规格 `dig=false place=false`，所以她只绕不改。**

#### ✅ 7 处已全部修完，并**真机跑通**（2026-10-10）

替代写法（每处都先在服务端 jar 上核过）：`isVisuallyCrawling`→内联
`isVisuallySwimming() && !isInWater()`；`getEyeHeight(Pose)`→内联
`Player.getStandingEyeHeight` 的常数表；`isNoCounter`→退回 `getDuration()`（**语义降级**）；
`Inventory.setPickedItem`→照字节码重写（**「先把旧物挪到空格」不能省**，省了就是吃掉玩家东西）；
`ProjectileUtil.getEntityHitResult`→自己用 `Level.getEntities` + `AABB.clip`；
`Vec3.directionFromRotation`→内联公式放 `BodyCompat`（**两个调用点**，差点漏 `Aim`）；
`LevelChunkSection.read`→改走 **NBT** 路（`PalettedContainer.write(CompoundTag,..)` + `read(ListTag,long[])`）。
`server-api-check.py` 从 10 条 → **0 条**（276 类全扫）。

**真机验证**（专用服务端 + 自动进服客户端 + MCP）：召唤不崩；
27 格 A* 展开 92 节点、21 步里 17 步斜走、改 0 格；
**挖一个 4 格深的坑再让她走到坑对面 → 路线终点 `z=-85` 而不是 `-86`，绕开了坑**（决定性证据）；
60 格一次走完；到不了的目标报 `OutOfBudget` + 中文原因。

### ⚠️ `from({ ... })` 闭包会**丢掉任务依赖**（Gradle，2026-10-10）

把 pathing 的 class 平铺进 core 的 jar 时：

```groovy
// ❌ 闭包不携带任务依赖 → :core:jar 可能抢在 :pathing:compileJava 前跑，拿到空目录
tasks.named('jar', Jar) { from({ project(':pathing').sourceSets.main.output }) }
```

**第一次没暴露**，只因 pathing 的类恰好已编译过、目录里有东西。三种写法都试错了一遍：

| 写法 | 结果 |
|---|---|
| `dependsOn project(':pathing').tasks.named('classes')` | 配置期解析任务对象 → `Task with name 'classes' not found` |
| `from project(':pathing').sourceSets.main.output`（非闭包） | 配置期取 `sourceSets` → `Could not get unknown property 'sourceSets'`（core 先于 pathing 配置） |
| ✅ `dependsOn ':pathing:classes'`（字符串=惰性）+ `from({ ... })`（闭包=执行期求值） | 正确 |

⚠️ **这种「任务顺序」的坑，只跑 `:core:build` 验不出来**（增量构建下类早就在了）。
**必须 `gradle clean :core:build`** 从零构建，再数 jar 里有没有那些类（实测 243 个）。

### ⚠️ ProGuard 会把「客户端才用到的成员」从专用服务端裁掉（2026-10-10 实测）

**症状**：`NoSuchMethodError`，**编译期完全看不出来**（Loom 编译用的是合并 jar），
**只在专用服务端复现**（单机没事）。

**接线后第一次真机测试，召唤同伴当场把服务端打崩**：
`pathing/body/Controls.apply` 调了 `Entity.isVisuallyCrawling()` → 服务端 jar 里没有。

**一次抓出 7 个**（`server-api-check.py` 补网后）：
`isVisuallyCrawling` / `getEyeHeight(Pose)` / `MobEffectInstance.isNoCounter` /
`Inventory.setPickedItem` / `ProjectileUtil.getEntityHitResult` /
`Vec3.directionFromRotation` / `LevelChunkSection.read`。
**规律：被裁的多是「客户端才走的那条路」——渲染、准星、收包。**

**查证手法**：
- **三列映射**翻译 intermediary → 可读名：
  `~/.gradle/caches/fabric-loom/<版本>/loom.mappings.*/mappings.tiny`
  ⚠️ 描述符列是**混淆类型**，只能按成员名匹配。
- **扫整个 jar** 比按类 javap 可靠（能排除「挂在父类上」），
  ⚠️ **必须加词边界**，否则 `method_1030` 会匹配 `method_10305`。
- 替代 API **必须重新核可见性**：`getEyeHeight(Pose,Dims)` 是 protected、
  `getStandingEyeHeight` 也被裁了。

**`server-api-check.py` 已补两处网**（这次崩溃本该被它拦住）：
1. 扫描范围加上 `com/dwinovo/numen/pathing/`（它平铺进发行 jar，运行时就是 core 的一部分）；
2. **javap 会把过长的类头折行**，老代码要求「一行内以 `{` 结尾」→ 折行时父链丢失 →
   把继承来的成员误报成缺失（冤枉过 `Level.getMaxBuildHeight`）。已改成先拼回一行。

**`pathing` 不能用 `include` 当嵌套 jar**（Loom 子项目、产物无 `fabric.mod.json`）——
照上游 `api-loader.gradle` 用
`tasks.named('jar') { from({ project(':pathing').sourceSets.main.output }) }` **平铺**进 jar。
平铺后 `remapJar` 会连它们一起换成 intermediary，实测 242 个类都对。

### 默认 API 预设 = NVIDIA NIM；面板有「获取模型」（2026-10-10）

**默认改成 NVIDIA**（用户要求）：`NumenConfig` 的
`provider="nvidia"` / `model="deepseek-ai/deepseek-v4.1-flash"` /
`baseUrl="https://integrate.api.nvidia.com/v1"`；
`numen_providers.json` 里 `nvidia` 是第 16 个服务商，
`ProviderRegistry` 认 `nim` / `nvidia-nim` / `build.nvidia` 别名。
选它当默认是因为 OpenAI 兼容 + `GET /v1/models` **不填密钥也能列**（实测 80 个），
玩家可以「先看列表 → 挑一个 → 再填 key」。

**「获取模型」**：`core/llm/ModelCatalog`（组装 URL / 解析 `data[].id` / 中文错误）
+ `core/llm/ModelCatalogState`（结果存放处）+ `HttpLlmTransport.get()`。
URL 组装要认三种输入：不带 `/v1`、带 `/v1`、误把 `/chat/completions` 粘进来。

⚠️ **面板是轮询快照的 → 异步结果必须进快照字段，不能只回一条消息**。
面板每 20 tick 主动拉一次快照；只放在「发起那条请求的回复」里的话，
下一次轮询就把它盖掉，界面会「闪一下结果又变空」。
所以结果进 `ModelCatalogState`，每次 `snapshot()` 都带上；`snapshot()` 里要**深拷贝**
（否则下一次 `begin()` 会把已发出去的那份也改成「获取中」），
而 gson 2.8.0 没有 `deepCopy()` → 手抄。

### 外接大脑（MCP）— 已落地并真跑通（2026-10-09）

上游放在 `api/mcp/`，本仓没有 `api` 模块 → 落在 **`core/mcp/`**：
`McpConfig` / `McpMode` / `McpActuator` / `McpServer`，端点 `http://127.0.0.1:<port>/mcp`。

**客户端 → 服务端的翻转**：上游同伴在主人客户端的集成服务器里，桥接走
`Minecraft.getInstance().execute`；本仓同伴在**服务端**，走 `MinecraftServer.execute`。
`McpActuator` 每个方法返回 `CompletableFuture`，统一 `hop(onFail, work)` —— **绝不悬着**。

**工具面只有 10 个**（有意收窄，`INSTRUCTIONS` 里写明「别编工具名」）：
`list_companions` `create_companion` `delete_companion` `get_events` `say`
+ `get_status` `inspect_block` `scan_blocks` `goto` `mine`。
**没有建造 / 合成 / 战斗 / 钓鱼 / 开箱 / 背包**；`goto` 是**直线走法不是寻路**；
动作工具**同步阻塞**，没有 `task_id`。除花名册外每个工具都要必填 `companion`。

**驾驶闸两道**：大脑层 `HostPort.externallyDriven()` → `McpMode.driving()`（真就取 hold
`EXTERNAL`，内置大脑一步不跑）；身体层 `AgentLoop#runAside` + `NumenTools` job 槽拒绝第二个任务。

⚠️ **主人跟同伴说话只有 `/numen tell` 一条路**——本仓**没有普通聊天钩子**，
直接打字聊天到不了事件队列。（MCP 的 `INSTRUCTIONS` 一度写错成「普通聊天也行」，已改正。）

⚠️ **本机 `http_proxy=127.0.0.1:60608` 会代理 localhost 请求 → 502。**
Python 用 `ProxyHandler({})` 的 opener，curl 用 `--noproxy '*'`；别改全局环境变量。
⚠️ **`DETACHED_PROCESS` 起的后台服务端会被回收**，要用前台 + `run_in_background` 保活。
⚠️ `--server` / `--port` **是能用的**（22:53 实测自动进服成功），别被早前的失败记录误导。

驱动脚本：`mcp-drive.py`（serve-fg/stop/status/tools/call/log/waitplayer）、
`launch-client-join.py`（起客户端并自动进本机专用服）。详见 `docs/PORTING.md` 第七节。
