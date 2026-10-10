# Numen → Minecraft 1.16.5 移植说明

上游：[Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)（LGPL-3.0-only）
本文记的是**移植过程中实际查证过的每一处差异**，不是推测。所有 API 结论都对着
Mojang 官方 1.16.5 映射文件核过（`client.txt`，见文末「怎么复核」）。

---

## 一、结论先说：能移植，而且比想象中省事

上游 1214 个 Java 文件，但**它们不是均匀地依赖 Minecraft**：

| 上游模块 | 文件数 | 引用 `net.minecraft` 的文件数 | 移植方式 |
|---|---|---|---|
| `lua/` | 52 | **0** | 逐字照搬 |
| `agent/` | 64 | **0** | 逐字照搬 |
| `ai/` | 50 | **0** | 逐字照搬 |
| `ui/` | 53 | 0 | 纯 JVM 的界面框架，可照搬 |
| `pathing/` | 165 | 134 | 要重写（依赖原版寻路类） |
| `api/common` | 488 | 218 | 要重写 |
| `core/common` | 214 | 153 | 要重写 |

**引擎层（`lua` + `agent` + `ai`，166 个文件）一行不用改**——它们是纯 JVM 的：
Lua 5.2 虚拟机、ReAct 循环内核、LLM 连接层，都不认识 Minecraft。
这已经是整个项目最难、最有价值的那部分。

本仓已验证：`lua/` + `agent/` + `ai/` 三个模块**编译通过**（123 个主源码文件 → 364 个 class，
零错误）。验证方式见文末。

真正的工作量在 `core`（Minecraft 适配层），也就是本仓 `core/` 正在做的事。

---

## 二、1.20.1 → 1.16.5：逐条核过的 API 差异

这是本次移植最有价值的产出。**这些结论都来自官方映射，不是印象**。
凡是「上游那样写、1.16.5 上编译不过」的，都在表里。

### 2.1 构造器与类形状

| 上游 1.20.1 | 1.16.5 | 说明 |
|---|---|---|
| `new ServerPlayer(server, level, profile)` | `new ServerPlayer(server, level, profile, new ServerPlayerGameMode(level))` | **四参**。1.16.x 独有，1.17 起又变回三参。最容易漏的一处 |
| `server.getPlayerList().placeNewPlayer(conn, player)` | 同名同形 | ✓ 直接可用 |
| `PlayerList.remove(ServerPlayer)` | 同名同形 | ✓ |
| `ServerPlayerGameMode.destroyBlock(BlockPos)` | 同名同形 | ✓ 挖方块走它，玩家语义完整 |

### 2.2 字段 vs 方法（这一代很多是公开字段，后来才收成方法）

| 上游 1.20.1 | 1.16.5 | 说明 |
|---|---|---|
| `player.getAbilities()` | `player.abilities`（字段） | `Abilities.instabuild` / `mayfly` 都是字段 |
| `player.getInventory()` | `player.inventory`（字段） | |
| `player.onGround()` | `player.onGround`（字段） | 同代也有 `isOnGround()` |
| `player.setMaxUpStep(0.6F)` | `player.maxUpStep = 0.6F`（字段） | 字段形式一直用到 1.20.5 |
| `player.serverLevel()` | `player.getLevel()` 或 `(ServerLevel) player.level` | `serverLevel()` 是 1.17+ 才有 |
| `player.getServer()` | `player.server`（字段） | |
| `level.getChunkSource().move(player)` | `((ServerLevel) player.level).getChunkSource().move(player)` | 参数是 `ServerPlayer` |

### 2.3 签名真的变了（照抄必挂）

| 上游 1.20.1 | 1.16.5 | 说明 |
|---|---|---|
| `player.doCheckFallDamage(x, y, z, onGround)` | `player.doCheckFallDamage(y, onGround)` | **参数个数不同**。1.16.5 的 `ServerPlayer` 覆写的是两参版 |
| `conn.send(packet, PacketSendListener)` | `conn.send(packet)` + `conn.send(packet, GenericFutureListener)` | **`PacketSendListener` 这个类型 1.16.5 上不存在**（1.17 才引入）。两个出站口都要堵 |
| `player.getEyePosition()` | `player.getEyePosition(1.0F)` | 无参重载是 1.17+ 才有 |
| `EnchantmentHelper.getSneakingSpeedBonus(player)` | 无对应物 | Swift Sneak 附魔 1.19 才加，1.16.5 蹲行速度是常数 0.3 |
| `profileCache.get(uuid)` → `Optional<GameProfile>` | `profileCache.get(uuid)` → 可空的 `GameProfile` | 上游那串 `Optional.flatMap(...)` 要改成判 null |

### 2.3.1 访问修饰符：几处「看起来能抄、其实抄不了」（**实测编译报错后才改的**）

这一组是第一次真正跑 `./gradlew build` 时才暴露的——只读映射文件**看不出**哪些成员是
`protected`。全部用 `javac` 报的错定了案，9 处改动见 `git log`。

| 上游写法 | 1.16.5 正确写法 | 为什么 |
|---|---|---|
| `entity.getYRot()` / `entity.setYRot(f)` | `entity.yRot = f`（**公开字段**） | `getYRot/setYRot` 是 **1.17 才有的**。1.16.5 上 `Entity.yRot` / `xRot` 就是 public field |
| `entity.getXRot()` | `entity.xRot`（公开字段） | 同上 |
| `living.jumping = b` | `living.setJumping(b)` | `LivingEntity.jumping` 在 1.16.5 是 **protected**；`setJumping` 才是 public |
| `entity.onGround` | `entity.isOnGround()` | `Entity.onGround` 在 1.16.5 是 **protected**；`isOnGround()` 才是 public |
| `source.hasPermissionLevel(2)` | `source.hasPermission(2)` | `hasPermissionLevel` 是 1.17+ 改的名；1.16.5 是 `hasPermission(int)` |
| `aabb.distanceToSqr(vec)` | `vec.distanceToSqr(aabb.getCenter())` | `AABB.distanceToSqr` 是 1.17+ 才有。**1.16.5 只有 `Vec3.distanceToSqr`**，把接收者换成 Vec3 |
| `Mth.square(6.0)`（作为 `float` 用） | `36.0D` 或 `(float)` 强转 | `Mth.square` 返回 `float`，与 `Vec3.distanceToSqr` 的 `double` 比较时会有精度损失告警（`-Werror` 下即成错误） |

> **教训**：`docs/mm.py` 能查出「有哪个方法」，但**查不出访问修饰符**。
> 成员行只有 `<行号>:<行号>:<返回类型> <名字>(<参数>)`，没有 `public/protected`。
> 所以任何「上游直接读某字段」的行，都要**真编一次**才算核过。

### 2.4 注册表与文本

| 上游 1.20.1 | 1.16.5 |
|---|---|
| `BuiltInRegistries.ENTITY_TYPE` | `Registry.ENTITY_TYPE`（`BuiltInRegistries` 是 1.19.3+ 才有） |
| `Registry.BLOCK.getKey(block)` | 同名同形 ✓ |
| `Component.literal("x")` | `new TextComponent("x")` |
| `Component.translatable("k")` | `new TranslatableComponent("k")` |
| `ItemStack` 数据组件 | `ItemStack.getTag()`（`CompoundTag`） |
| `playerList.broadcastMessage(component, false)` | `playerList.broadcastMessage(component, ChatType, UUID)` | 两参版 1.16.5 没有。用 `ChatType.CHAT` / `ChatType.SYSTEM` + `Util.NIL_UUID` |

### 2.5 Fabric API

| 上游 1.20.1 | 1.16.5 |
|---|---|
| `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback`，回调三参 `(dispatcher, registryAccess, environment)` | `net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback`，回调**两参** `(dispatcher, dedicated)` |
| `ServerTickEvents.END_SERVER_TICK` | 同名同形 ✓ |
| `ServerLifecycleEvents.SERVER_STARTED` | 同名同形 ✓ |
| `FabricLoader.getInstance().getConfigDir()` | 同名同形 ✓ |

### 2.6 确认「照抄就行」的部分

这些**没有变**，是移植能省下大量时间的原因：

- `Connection(PacketFlow)` 构造器、`PacketFlow.SERVERBOUND`
- `EmbeddedChannel` 做无网线连接（`FakeConnection` 的骨架）
- `ServerPlayer.doTick()` —— **存在**，这是「服务端假玩家自己走路」整套方案成立的关键
- `Player.checkMovementStatistics(double,double,double)`、`Player.getFoodData()`
- `LivingEntity.zza` / `xxa` / `jumping`（公开字段）、`setShiftKeyDown`、`setSprinting`
- `Entity.getEntityData()`、`addAdditionalSaveData` / `readAdditionalSaveData`
- `CompoundTag.putUUID / hasUUID / getUUID`
- `Vec3.atCenterOf(Vec3i)`、`AABB(BlockPos)`、`Mth.square`、`Mth.wrapDegrees`
- `Inventory.getFreeSlot()` / `getSlotWithRemainingSpace(ItemStack)` / `getSelected()`

---

## 三、为什么是 Java 17（以及它的代价）

引擎层用了 `record`、文本块、`List.of`、`instanceof` 模式匹配——上游按 Java 17 写的。
压回 Java 8 意味着改写 123 个文件里所有 `record`、`List.of/Map.of/Set.of`、`String.isBlank`、
`instanceof` 模式，工作量以天计，而且从此和上游永久分叉。

所以本移植沿用 **Java 17**。代价说清楚：

- Mojang 给 1.16.5 声明的运行时是 **Java 8**（官方 version json 里
  `javaVersion.majorVersion = 8`）。
- 1.16.5 + Fabric Loader + Java 17 **确实能跑**，但不是这一代生态的默认配置。
- `fabric.mod.json` 里因此声明了 `"java": ">=17"`。

**要回到 Java 8 的话**，路线是：把 `lua` / `agent` / `ai` 三个模块降级
（`record` → final 类 + 访问器、`List.of` → `Arrays.asList`、文本块 → 拼接），
`core` 本来就是新写的，跟着改即可。这是一次独立的、机械但量大的重构。

### 3.1 ⚠️ 实机第一个坑：「不兼容的模组 / 缺 fabric-api」其实是 JVM 选错了

**已实机复现并定位（2026-10-09）。** 症状：

```
Immediate reason: [HARD_DEP_NO_CANDIDATE numen 0.1.4-1.16.5 {depends fabric-api @ [*]}]
确定了一种可能的解决方法：安装 fabric-api，任意版本。
```

**这条报错指错了方向。** 因果链是：

1. 启动器看到 MC 1.16.5，**自动选 Java 8**（1.16.5 官方就是 Java 8，这是合理默认）；
2. `fabric.mod.json` 的 `"java": ">=17"` 在 Java 8 上判定失败；
3. → **numen 自己被 loader 剔除**，`fabric-api` 依赖随之无人满足；
4. → Fabric 原样报出「numen 需要 fabric-api」。

用 loader 自带的 `VersionPredicate` 实测（决定性证据）：

| 启动 JVM | `numen` 的 `java>=17` | `fabric-api` 的 `minecraft` |
|---|---|---|
| Java 8 (`1.8.0_51`) | **REJECT** | PASS |
| Java 17 (`17.0.15`) | PASS | PASS |

**修法**：把实例的 Java 显式指到 17。PCL 是版本设置 → 设置 → Java 选择；
或直接写 `versions/<实例>/PCL/Setup.ini` 的 `Java:` 键。
（PCL 的 Java 候选列表在 `%APPDATA%\PCL\config.json` 的 `JavaList`。）

**排查时排除掉的可能**（都不是原因，别再查一遍）：

- fabric-api jar 损坏 —— sha1 与官方发布**逐字节相同**；
- `fabric-api` 声明的 `minecraft: ~1.16.2-alpha.20.28.a` 与 1.16.5 不匹配 ——
  **实测 `~` 的语义是 `>=1.16.2-alpha.20.28.a <1.17.0`，1.16.5 命中**。
  这个老写法**看起来**只匹配 1.16.2，实际不是；
- `.fabric/remappedJars` 残留缓存、zip 压缩方式、版本 json 结构 —— 都干净且与可用实例一致。

**另外**：`fabric.mod.json` 里 `"java": ">=17"` 是**真话**——实测全部 class 都是
主版本 61（Java 17），共 24+138+37+189 个。所以这不是「放宽约束就能过」的问题，
必须换 JVM。

### 3.2 ⚠️ 更正 3.1 的归因：那条报错是**依赖 id 写错**，不是 Java 8

3.1 的结论要收回一半。同一个报错文本有**两个**可能的成因，当时只查了 Java 那条：

| 成因 | 报错是否长这样 | 是否本次的真因 |
|---|---|---|
| Java 8 → numen 自身被剔除 | 是 | 否（本次 JVM 一直是 17.0.15） |
| `fabric.mod.json` 写了 `fabric-api`，而 1.16.5 上没有这个 id | 是 | **是** |

实测证据（2026-10-09）：

1. **Fabric API 0.42.0+1.16 的 mod id 是 `fabric`**，`provides` 为空：

   ```
   $ python -c "读 fabric-api-0.42.0+1.16.jar 的 fabric.mod.json"
   id = fabric | name = Fabric API | version = 0.42.0+1.16
   provides = None
   ```

   上游 1.20.1 那边用的 `fabric-api` 是**新版 Fabric API 的 id**（1.19 之后才改的名），
   1.16 线上没有。**照抄上游的 `"fabric-api": "*"` 必然失败。**

2. **Fabric Loader 0.19.5 里没有 `fabric-api` 这个字面量** —— 即不存在 id 别名：

   ```
   $ 在 fabric-loader-0.19.5.jar 的所有 class 里搜 b'fabric-api' → 0 命中
   ```

   所以 `"fabric-api": "*"` 在 1.16.5 上**无解**，装多少个 Fabric API 都没用。

3. 实机启动成功的那个 jar（`V10-fixed.jar`）里，`depends` 写的正是 `"fabric": "*"`。

**结论**：1.16.5 这一侧 `fabric.mod.json` 必须写 `"fabric": "*"`。
（3.1 里「`java: >=17` 是真话、1.16.5 启动器默认选 Java 8」这部分**仍然成立**，
只是它解释不了这条具体报错。）

### 3.3 实机第二个坑：**1.16.5 根本没有 slf4j**

依赖解析通过、模组列表也列出来了，却在**初始化阶段**崩：

```
java.lang.NoClassDefFoundError: org/slf4j/LoggerFactory
	at com.dwinovo.numen.core.NumenCore.<clinit>(NumenCore.java:16)
	at com.dwinovo.numen.core.NumenCoreFabric.onInitialize(NumenCoreFabric.java:36)
```

- MC **1.17+** 才随 `log4j-slf4j-impl` 把 slf4j 带进游戏；**1.16.5 没有**。
  查 1.16.5 的 version json，65 个库里 `slf4j` 零命中（只有 gson 2.8.0 与 log4j 2.8.1）。
- 引擎层 `ai/AiLog` 与 `core/NumenCore` 都要用 slf4j，所以**必须自己发**。

**修法**：`core/build.gradle` 里把 slf4j 随 jar 内嵌（两个都要）：

```gradle
include 'org.slf4j:slf4j-api:1.7.36'
include 'org.apache.logging.log4j:log4j-slf4j-impl:2.8.1'
```

- 只发 `slf4j-api` 不够：没有绑定它会退化成 NOP logger，`[numen]` 的日志**静默消失**。
- `log4j-slf4j-impl` 把 slf4j 接到游戏自带的 log4j 上，日志于是与其它模组一样进 `latest.log`。
- 版本要对齐：`2.8.1` 与 1.16.5 自带的 log4j **完全一致**；它属于 **slf4j 1.7 系**绑定，
  所以 `slf4j-api` 用 `1.7.36`（不能用 2.x —— 2.x 改用 ServiceLoader 找 provider，认不出
  1.7 系绑定的 `StaticLoggerBinder`）。

### 3.4 实机第三个坑：**gson 2.8.0 的 API 缺口**（24 处）

同一批「引擎层逐字照搬」的代码，在 1.20.1（gson 2.10.x）能编，在 1.16.5（gson **2.8.0**）编不过。
实测 `javap` 过 2.8.0 的 gson：

| 用到的 API | 引入版本 | 处数 | 位置 |
|---|---|---|---|
| `JsonParser.parseString(String)`（静态） | 2.8.6 | 7 | `ai`: HttpLlmTransport×2 / ToolOutcome×2 / LlmToolCall / ConvoLog / AnthropicProvider |
| `JsonParser.parseReader(Reader)`（静态） | 2.8.6 | 1 | `ai`: ProviderRegistry |
| `JsonArray/JsonObject.isEmpty()` | 2.8.2 | 8 | `ai`: OpenAIProvider×4 / OpenAIResponsesProvider×2 / HttpLlmTransport / AnthropicProvider |
| `JsonElement.deepCopy()` | 2.8.2 起才 **public** | 5 | `ai`: OpenAIResponsesProvider×3 / ConvoLog / StreamAccumulator |
| 同上两族 | — | 3 | `agent`: ApiReply×2 / JsonlJournal |

注意 `deepCopy()` 在 2.8.0 里**是存在的，只是包级私有**，所以报错是
「deepCopy()在JsonObject中不是公共的」而不是「找不到符号」——别被措辞带偏。

**为什么不用「内嵌一份新版 gson」这种更省事的做法**（**已排除，别试**）：

- Fabric 的类加载顺序实测是**模组类路径优先**（`KnotClassDelegate.loadClass` 字节码里
  `tryLoadClass` 在父加载器之前），所以内嵌确实能盖住游戏的 gson；
- **但代价是灾难性的**：mod 侧拿到 2.10.1、游戏侧（平台类加载器）还是 2.8.0，
  同一个 `com.google.gson.JsonObject` 被两个类加载器各定义一遍。Fabric API 的
  `net.fabricmc.fabric.api.loot.v1.LootJsonParser` 恰好会把**游戏侧**的 gson 实例
  `checkcast` 成自己的类型，于是**每次加载战利品表就 ClassCastException**。
  重定位（shade）能躲开 Fabric API，但 mod 与 MC 之间仍会分叉。

**采用的做法**：改源码，但只用**在 2.8.0 与 2.10.x 上都能编译运行**的等价写法，
外加一个 5 行的兼容类。全 mod、Fabric API、游戏三方继续共用同一份 gson，运行期零风险。

| 原写法 | 改后 | 说明 |
|---|---|---|
| `JsonParser.parseString(x)` | `new JsonParser().parse(x)` | 实例方法在 2.8.0 就有 |
| `JsonParser.parseReader(r)` | `new JsonParser().parse(r)` | 同上 |
| `x.isEmpty()` | `x.size() == 0` | `size()` 两代都有 |
| `x.deepCopy()` | `JsonCompat.copy(x)` | 新增 `ai/.../agent/json/JsonCompat.java`，按 gson 自身语义重写（primitive 直接复用、array/object 逐层复制） |

编译期把 gson 钉在运行期的版本上（`ai/build.gradle`：`api 'com.google.code.gson:gson:2.8.0'`），
这样**以后谁再误用 2.8.6+ 的 API，编译器会当场报错**，而不是等到游戏里 `NoSuchMethodError`。
单测则相反，测试类路径上抬到 2.10.1（`ai` 与 `agent` 各写一句 `testImplementation`），
于是测试可以自由用新 API，而 main 源码仍被 2.8.0 卡住。

> 这 24 处是**版本无关**的写法，不是「迁就本地」——上游将来更新时，这些行不会烂掉。

### 3.5 ⚠️ 实机第四个坑：**「客户端独有的成员」编译期查不出来**（只在专用服务端炸）

这是本次移植里最阴的一个坑，**编译器全程沉默**，只有真跑到那一行才炸。

现象（2026-10-09 实测）：

```
java.lang.NoSuchMethodError: 'net.minecraft.class_2561 net.minecraft.class_1792.method_7848()'
    at com.dwinovo.numen.core.agent.NumenTools.status(NumenTools.java:238)
```

对应源码是 `item.getDescription()`（`Item.getDescription()`），编译**毫无问题**。

**根因**：Fabric Loom 给模组编译用的类路径是 **`minecraft-merged-intermediary.jar`，
即客户端 + 服务端的合并 jar**。1.16.5 的客户端 jar 比服务端 jar 多若干成员，
`Item.getDescription()` 与 `Item.appendHoverText(...)` 就是这一类。实测 `javap` 对比：

| 方法 | merged（编译用的） | server-intermediary（运行时用的） |
|---|---|---|
| `Item.method_7848()Lclass_2561;`（`getDescription`） | 有 | **没有** |
| `Item.method_7851(...)`（`appendHoverText`） | 有 | **没有** |

于是「编译期对得上」和「服务端跑得起来」是**两件事**。单机（集成服务端，在客户端进程里）
不会复现——只有**专用服务端**才炸。这也解释了为什么它躲过了之前所有测试。

**改法**：换成服务端也有的等价访问器。取物品显示名用
`ItemStack.getHoverName()`（`class_1799.method_7964()Lclass_2561;`，实测服务端 jar 里有）：

```java
// 错：Item.getDescription() —— 只在 merged jar 里
body.inventory.getSelected().getItem().getDescription().getString()
// 对：ItemStack.getHoverName()
body.inventory.getSelected().getHoverName().getString()
```

**怎么一次性找全（别等一个个炸）**：用本仓的 `server-api-check.py`。

```bash
python server-api-check.py .testserver/mods/numen-0.1.4-1.16.5.jar \
       .testserver/.fabric/remappedJars/minecraft-1.16.5-0.19.5/server-intermediary.jar
```

做法是：`javap -c` 抠出模组 jar 里每条 `// Method/Field net/minecraft/...` 引用，
再沿**父类/接口链**去**运行时那份 server-intermediary.jar** 里核对。
首次跑（修之前）结果：**97 条 MC 引用、9 个 MC 类，只有 1 条对不上**，就是上面这条。

> 这类坑的共同特征：**编译期能过 + 只在专用服务端炸**。
> 所以「`gradle build` 成功」永远不能替代「专用服务端上真跑一遍」。
> 迁移任何「原本跑在集成服务端 / 客户端」的代码时，都要按这条过一遍。

---

## 四、构建

### 4.1 网络：能连上，但要靠镜像（已实测打通）

**结论：本机可以构建。** 第一次尝试全部超时，第二次（2026-10-09）通了。关键发现：

| 目标 | 状态 | 备注 |
|---|---|---|
| `maven.fabricmc.net` | ⚠️ **可达但握手不稳** | 第一次全超时，第二次通。Cloudflare，抖动大 |
| `bmclapi2.bangbang93.com/maven` | ⚠️ **302 到 USTC，抖动** | 会报 `Remote host terminated the handshake` |
| `maven.aliyun.com/repository/public` | ✅ **稳定** | 公共制品全部走它 |
| `repo1.maven.org` | ✅ 通 | Gradle 的 `mavenCentral()` 就是它 |
| `libraries.minecraft.net` | ✅ 通 | 1.16.5 的 Mojang 依赖 |
| `piston-meta.mojang.com` | ✅ 通 | loom 拿 version json |
| `launcher.mojang.com` | ✅ 通 | `piston-data` 的备用域名，**映射文件从这里下** |
| `services.gradle.org` | ✅ 通 | Gradle 发行版 |

**所以真正要做的只有一件事：把阿里云排到仓库列表最前面。**
loom 的传递依赖（asm / guava / jackson / commons-* / netty……）量最大，让稳定的本地镜像
整包接住；Cloudflare 那边只剩下 fabric 独有的几个坐标，抖动面就小了。

顺序（`settings.gradle` 的 `pluginManagement` 与根 `build.gradle` 的 `subprojects` 两处都要）：

```groovy
maven { name = 'AliyunPublic'; url = uri('https://maven.aliyun.com/repository/public') }
maven { name = 'BMCLAPI';     url = uri('https://bmclapi2.bangbang93.com/maven') }
maven { name = 'Fabric';      url = uri('https://maven.fabricmc.net') }
maven { name = 'Mojang';      url = uri('https://libraries.minecraft.net') }
mavenCentral()
```

**抖动是可重试的**：第一次 `build` 报 `Could not download` 时，直接重跑即可——
Gradle 会把已下到的部分留在缓存里，第二次通常就过了。（本次就是第 3 次才把
fabric-loom 0.12.5 拉全。）

### 4.2 构建步骤（**已实测通过**）

```bash
cd numen-1.16.5
./gradlew build            # ✅ 产出 core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar
./gradlew :lua:test :agent:test :ai:test   # ✅ 465 个测试全过
./gradlew runClient        # 起一个 1.16.5 + Fabric 的开发客户端
./gradlew runServer        # 起服务端
```

**本机的免安装跑法**（工具链都在 `.toolchain/`，不碰系统）：

```bash
export JAVA_HOME=".../.toolchain/jdk-17.0.20.1+1"
".../.toolchain/gradle-7.6.4/bin/gradle" :core:build
```

工具链要求：

| 项 | 值 | 理由 |
|---|---|---|
| Gradle | 7.6.4 | loom 0.12.x 要求的最后一条 Gradle 7 线 |
| fabric-loom | 0.12.5 | 支持 Java 17 运行时，且认得 1.16.5 |
| JDK | 17 | 见第三节 |
| Minecraft | 1.16.5 | |
| 映射 | `officialMojangMappings()` | **与上游同一套命名**，源码类名方法名一一对应 |
| Fabric Loader | 0.14.25 | |
| Fabric API | 0.42.0+1.16 | 1.16 线的最后一版 |

用官方 Mojang 映射而不是 Yarn，是这次移植一个关键的选择：上游也用 Mojang 映射，
于是**移植时不必在 Yarn 名与 Mojang 名之间做心算**，`ServerPlayer` 还是 `ServerPlayer`。
上表里那些差异就都是纯粹的版本差异，而不是映射方言差异。

### 4.3 构建期踩到的两个「不是 API 差异」的坑

这两处与 Minecraft 版本无关，但都让第一次构建失败，记下来：

1. **`agent` 少依赖 `lua`。** `agent/.../script/lua/LuaEngine.java` 用 `com.dwinovo.lua.LuaSandbox`，
   而 `agent/build.gradle` 里漏了 `implementation project(':lua')`（上游有）。
   症状：121 个「程序包 LuaSandbox 不存在」。
2. **`ai` 把 gson 声明成了 `compileOnly`。** Gson 类型（`JsonObject` / `JsonArray`）
   出现在 `agent` 的类型里，`compileOnly` 不传递，于是 `agent` 编译期看不见它们。
   **上游用的是 `api`**（见 `ref/numen-1.20.1/ai/build.gradle`），本仓已跟着改成 `api`。
   症状：71 个「找不到符号 JsonObject」。

另外 `numen.accesswidener` 里的类名必须写**斜杠形式**（`net/minecraft/...`），
写成点分（`net.minecraft....`）会报 `AccessWidenerFormatException`。

---

## 五、当前进度与后续阶段

### 已完成（本次）

**构建与验证状态：全部通过（2026-10-09）**

```
:ai:compileJava     ✅   :lua:compileJava    ✅   :agent:compileJava  ✅
:core:compileJava   ✅   :core:remapJar      ✅   :core:validateAccessWidener ✅
:ai:test :lua:test :agent:test   ✅  465 tests / 0 failures / 0 errors
产出：core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar
     └─ META-INF/jars/  agent + ai + lua + slf4j-api + log4j-slf4j-impl 五个嵌套 jar 已就位
```

**运行期已打通（2026-10-09）** —— 见 3.2 / 3.3 / 3.4 三节：

- `fabric.mod.json` 的依赖改回 `"fabric": "*"`（1.16.5 的 Fabric API id 就叫 `fabric`）；
- slf4j 随 jar 内嵌（`slf4j-api` + `log4j-slf4j-impl`）；
- 引擎层 24 处 gson 2.8.6+ API 换成 2.8.0 也支持的写法，新增 `agent/json/JsonCompat.java`；
- 实机已能进到主界面（模组列表里 numen 与 52 个 Fabric API 子模块正常加载）。
  待验证：召唤同伴后 LLM 往返、Lua 沙箱、工具调用是否正常。

**引擎层（逐字照搬；除 3.4 那 24 行 gson 兼容改写外未动）**
- `lua/` 52 个文件 — Lua 5.2 沙箱虚拟机
- `agent/` 64 个文件 — ReAct 循环内核、事件队列、会话历史、目标、记忆压缩
- `ai/` 50 个文件 — OpenAI / Anthropic / DeepSeek / Moonshot / Responses 五套协议 + HTTP 流式

**Minecraft 适配层（新写，`core/`，已编译通过）**
- `entity/FakeConnection` — 无网线的 Connection（堵住两个出站口）
- `entity/NumenPlayer` — 服务端假玩家身体：归属、死亡抄因、原地复活、存档、皮肤层
- `entity/CompanionFactory` — 稳定 UUID、走 `placeNewPlayer` 进玩家列表
- `entity/Companions` — 花名册与生命周期
- `body/Body` `Controls` `Physics` `BodyCompat` — 键盘 → 输入 → 物理步进、交互距离
- `agent/CompanionBrain` — 四个端口（Host / Model / Tool / Memory）接上引擎
- `agent/NumenTools` — 六个原子动作：`say` `get_status` `inspect_block` `scan_blocks` `goto` `mine`
- `command/NumenCommand` — `/numen summon|dismiss|list|tell|status|config`
- `config/NumenConfig` — `config/numen.json`，命令读写
- `mcp/McpConfig` `mcp/McpMode` `mcp/McpActuator` `mcp/McpServer` — 外接大脑（MCP），见第七节

### 未完成（按建议顺序）

| 阶段 | 内容 | 规模 |
|---|---|---|
| **2** | 客户端体验：G 面板**已落地**（见第九节）、Y 快说一句、R+滚轮选人、设置界面 | `ui/` 53 个文件基本可照搬 + 若干 Screen |
| **3** | **寻路**：搬 `pathing/`（165 文件），换掉现在的直线走路 | 大，但独立 |
| **4** | 完整任务集：建造、战斗、钓鱼、开箱、合成、装备、吃 | `core/task/` 一百多个文件 |
| **5** | Lua 程序工具：让模型一次写一段 Lua 组合多个原子动作（引擎已就位，缺服务端 API 绑定） | `api/script/` |
| **6** | 外接大脑（MCP server）**✅ 已落地**（见第七节）；插件系统、多同伴群聊、语音未做 | `api/mcp/`、`plugins/` |

阶段 3 之前，`goto` 是「面朝目标按住前进、撞墙就跳」的直线走法——
平地与缓坡够用，绕不开大障碍。这一点在代码注释里也写明了。

### 已经真跑过的（2026-10-09）

编译期与运行期是两回事，所以下面这些是**真的起来跑过**、不是「编过了」：

- **专用服务器加载**：`.testserver/` 起真实 1.16.5 服务端，模组正常加载、无崩溃，
  控制台可见 `numen-mcp` 监听行；`/numen summon|status|config` 命令注册成功。
- **模型 → 工具 → 回话整链**：本地假模型端点（`fake-llm.py`，OpenAI 协议）
  按脚本吐 `tool_calls`，同伴依次执行 6 个原子动作并回话，日志逐条对上。
- **MCP 通路**：裸 JSON-RPC 打 `/mcp`，`mcp-verify.py` **8/8 通过** ——
  无 token 401、`initialize` 返回 `numen-mcp`、`tools/list` 10 个工具、
  每个工具都带必填 `companion`、无同伴时优雅报错、服务不崩。
- **专用服务器 API 复核**：`server-api-check.py` 把 jar 里 96 处 MC 引用
  逐个对 `server-intermediary.jar` 解析，**全部命中**——没有误用客户端专属成员。

**还没做的验证**：**没有在真实客户端（带 GUI）里手点过**。
`./gradlew runClient` 或 PCL 起客户端进单人世界，summon 一只同伴让它走两步，
是下一步该做的冒烟测试。`FakeConnection` 的 `EmbeddedChannel` 连接、
`placeNewPlayer` 的时序、`doTick()` 在 1.16.5 上的行为，只有真进世界才看得出来。

---

## 六、怎么复核本文的每一条 API 结论

不需要 Minecraft，只需要官方映射文件（约 5.7 MB）：

```bash
# 1. 版本清单
curl -o manifest.json https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
# 2. 1.16.5 的 version json（从 manifest 里取 url）
curl -o 1.16.5.json https://piston-meta.mojang.com/v1/packages/fba9f7833e858a1257d810d21a3a9e3c967f9077/1.16.5.json
# 3. 官方客户端映射。注意 piston-data 可能不通，launcher.mojang.com 是同一份对象
curl -o client.txt https://launcher.mojang.com/v1/objects/374c6b789574afbdc901371207155661e0509e17/client.txt
```

映射文件是**反的** proguard 格式——**左边是可读名，右边是混淆名**：

```
net.minecraft.server.level.ServerPlayer -> aah:
    433:500:void doTick()
    922:927:void doCheckFallDamage(double,boolean)
```

`<行号>:<行号>:<返回类型> <名字>(<参数>) -> <混淆名>`。
一个能按类名查成员的脚本见 `docs/`（生成本仓时用过，思路就是把类头行与成员行归组）。

---

## 七、外接大脑（MCP server）

让**外部**一个会调工具的模型（WorkBuddy、Claude Desktop 等）来当同伴的大脑：
游戏里的内置模型整个让位，同伴变成一具「身体」，由外部按需驱动。

### 7.1 为什么放在 `core/` 而不是像上游那样放 `api/`

上游的 MCP 在 `api/mcp/`（4 个类：`McpServer` `McpConfig` `McpMode` `NumenMcp`）。
本仓**没有 `api` 模块**——它把上游 `api`+`core` 合并成了自己的 `core`，
所以这层直接落在 `core/mcp/`，四个类一一对应，只在必要处改写。

### 7.2 关键差异：客户端 → 服务端

上游同伴活在**主人客户端的集成服务器**里，所以桥接代码统一
`Minecraft.getInstance().execute(...)` 跳到客户端主线程。
本仓同伴活在**服务器**（集成或专用都可能），于是整套改成
`MinecraftServer.execute(...)`，并且：

- 服务器 tick 里 `McpActuator.bind/unbind` 跟着世界进出，HTTP 服务器**跟着游戏进程**
  而不是跟着世界——单人切换存档不会逼 MCP 重连；
- `McpActuator` 的每个公开方法都返回 `CompletableFuture`，内部统一走一个
  `hop(onFail, work)` 帮手：**绝不悬着**——不在世界里、或 `server.execute` 抛了，
  就当场用 `onFail` 结掉。HTTP 那边是阻塞等的，一个永不完成的 future 等于一次调用挂到超时。
- `create_companion` 在服务端没有「发起请求的玩家」这个概念，
  用 `Companions.firstOwner(server)`（第一个在线、且不是同伴的玩家）当主人。

### 7.3 驾驶闸（driving gate）——两道

外接大脑开启时，内置大脑**一步都不跑**：

1. **大脑层**：`HostPortImpl.externallyDriven()` 返回 `McpMode.instance().driving()`；
   内核每步都查，为真就取 hold `EXTERNAL`。主人聊天仍进事件队列，
   外部用 `get_events` 取走、用 `say` 回话。
2. **身体层**：一具身体同时只接一个长动作；`AgentLoop#runAside` + `NumenTools` 的
   job 槽会拒绝第二个任务（返回 `false`）。

`McpMode.driving() = enabled && !(quietFallback && quietNow)`，
静默判定 `QUIET_AFTER_MS = 120_000`。`CompanionBrain.tick()` 里比较前后 `driving`，
**false→true 的边沿**才 `loop.halt(HaltReason.EXTERNAL)`（避免每 tick 重复 halt）。

### 7.4 暴露的工具面（比上游窄，是有意为之）

`tools/list` 一共 10 个：

- 5 个 MCP 专属：`list_companions` `create_companion` `delete_companion` `get_events` `say`
- 5 个原子动作：`get_status` `inspect_block` `scan_blocks` `goto` `mine`
  （由 `NumenTools.specs()` 自动枚举，**跳过**与专属 `say` 重名的那个，
  再减去 `hidden_tools` 里点名的，每个注入一个必填的 `companion` 参数）

**没有**建造、合成、战斗、钓鱼、开箱、背包这些工具，`goto` 也是直线走法不是寻路。
`INSTRUCTIONS` 里写明了这一点，免得外部模型凭空编工具名。

### 7.5 gson 2.8.0 约束（同 3.4）

`McpConfig` 解析、`McpServer` 拷参数都踩过：
不用 `JsonParser.parseString`（2.8.6+）、`JsonElement.deepCopy()`（2.8.2+）、
`JsonArray.isEmpty()`（2.8.2+）；改 `new JsonParser().parse(...)` + 手工逐键拷贝 + `size()==0`。

### 7.6 怎么接

配置文件 `config/numen/mcp_server.json`（首次生成时 `enabled=false`）：

```json
{ "enabled": true, "host": "127.0.0.1", "port": 8765,
  "token": "numen-……", "call_timeout_seconds": 300,
  "hidden_tools": [], "quiet_fallback": false }
```

端点 `http://127.0.0.1:8765/mcp`，`Authorization: Bearer <token>`（或 `?token=`）。
`host` 填 `0.0.0.0` 会局域网暴露，`token` 为空则不校验——两者都会在日志里告警。
WorkBuddy 侧把这段写进 `~/.workbuddy-ai/mcp.json` 的 `mcpServers.numen` 即可。

## 八、离线账号的「多人游戏已被禁用」（2026-10-09 查清并修掉）

### 8.1 症状

主菜单「多人游戏」按钮变灰，悬停提示：
`多人游戏已被禁用，请检查你的Microsoft账户设置。`（en_us：`multiplayer.disabled`）

这不是"离线模式不支持多人游戏"。离线账号在 1.16.5 上本来能联机，
2023-06-08 之后才普遍变成这样。原因是**令牌校验失败时默认判否**。

### 8.2 完整链路（从字节码读出来的，不是推断）

| # | 位置 | 事实 |
|---|---|---|
| 1 | `Minecraft.<init>` | `multiplayerEnabled = !demo`；随后 `socialInteractionsService = createSocialInteractionsService(authService, args)` |
| 2 | `Minecraft` 私有方法 | 内部调 `YggdrasilAuthenticationService.createSocialInteractionsService(accessToken)`，**只 catch `AuthenticationException`**，catch 到才回退 `OfflineSocialInteractions` |
| 3 | `YggdrasilSocialInteractionsService.<init>` | 调 `checkPrivileges()` → GET `{servicesHost}/privileges`，头 `Authorization: Bearer <accessToken>` |
| 4 | `HttpAuthenticationService.performGetRequest` | 非 2xx 时**把错误流当正常响应读出来返回，不抛异常**（只有连错误流都没有时才 rethrow） |
| 5 | `YggdrasilAuthenticationService.makeRequest` | `response.getError()` 为空 → 不抛 `AuthenticationException` → 这个响应被当成"正常结果" |
| 6 | `PrivilegesResponse` | 字段**预初始化**：`privileges = new Privileges()`，三个 `Privilege.enabled` 默认 `false`。JSON 里没有 `privileges` 键 → Gson 保持默认值 |
| 7 | `Minecraft.allowsMultiplayer()` | `return multiplayerEnabled && socialInteractionsService.serversAllowed();` → `true && false` = **false** |

关键在第 4/5 步：离线启动器给的假令牌换回来的是

```
HTTP/1.1 401        body: {"path" : "/privileges"}      ← 实测
```

这个体里**没有 `error` 字段**，于是 authlib 既不抛异常、也不报错，
只是把"三个权限全 false"的默认对象当成真实结果返回了。

而唯一那个兜底 `OfflineSocialInteractions`（三个权限全 `true`）只在
第 2 步 catch 到 `AuthenticationException` 时才生效——也就是**连不上认证服务器**那条路。

### 8.3 两条分支，都在本机实测过

用 `authlib-2.1.28` 直接调 `createSocialInteractionsService("000…0")`：

```
联网（假令牌）        serversAllowed=false  chatAllowed=false  realmsAllowed=false
连不上服务端          抛 AuthenticationUnavailableException（→ Minecraft 回退到全 true）
```

这就解释了社区里那条"断网启动就能联机"的偏方——不是玄学，
是 `performGetRequest` 的失败语义（连不上 = 抛异常 = fail-open；
连得上但 401 = 解析成默认值 = fail-closed）。

### 8.4 修法

`core` 里加了两个类（都在客户端专属包里）：

- `core/client/NumenOfflineMultiplayer.java` —— 判定 + 日志 + 开关
- `core/client/mixin/NumenOfflineMultiplayerMixin.java` ——
  `@Inject(at=@At("RETURN"), cancellable=true)` 到 `Minecraft.allowsMultiplayer()` /
  `allowsChat()`，返回 false 时掰回 true

几个必须注意的点：

1. **必须放进 `numen.mixins.json` 的 `client` 数组，不能放 `mixins`。**
   `Minecraft` 是客户端类，声明成通用 mixin 会让专用服务端启动时就炸。
2. **mixin 的 `package` 从 `com.dwinovo.numen.core.mixin` 改成了
   `com.dwinovo.numen.core.client.mixin`。** 这样 `server-api-check.py` 的
   CLIENT_PKG 例外自动覆盖它——客户端 mixin 必然引用 `net.minecraft.client.*`，
   放在共享包里会被那条反向检查（共享类不许碰 client）判为错误。
3. 这是本项目**第一个真正编译进去的 mixin**（此前 `mixins`/`client` 都是空数组），
   所以 refmap 生成此前从未被验证过。生成结果是对的：

   ```json
   "allowsChat":        "Lnet/minecraft/class_310;method_29044()Z",
   "allowsMultiplayer": "Lnet/minecraft/class_310;method_29043()Z"
   ```

   已拿 `.minecraft/libraries/net/fabricmc/intermediary/1.16.5/intermediary-1.16.5.jar`
   里的 `mappings/mappings.tiny` 对过：`METHOD djz ()Z s method_29043`、
   `METHOD djz ()Z u method_29044` ✅
4. 不想要这个行为：JVM 参数 `-Dnumen.offlineMultiplayer=false`。

5. ⚠️ **`compatibilityLevel` 必须 ≥ mixin 类的字节码版本，否则 mixin 会「静默不生效」。**
   一开始照原样留着 `"compatibilityLevel": "JAVA_8"`，而模块是 `options.release = 17` 编的
   （class 版本 61）。结果：**不报错、不崩溃、日志里连 `Selecting config numen.mixins.json`
   都没有** —— 配置根本没被选中，注入自然没发生。`required: true` + `defaultRequire: 1`
   在这里救不了你，因为它们管的是"选中之后注入失败"，而这是"压根没选中"。
   改成 `JAVA_17` 后立刻出现：

   ```
   [WARN] Compatibility level JAVA_17 specified by numen.mixins.json is higher than
          the maximum level supported by this version of mixin (JAVA_13).
   [INFO] Selecting config numen.mixins.json
   [INFO] Preparing numen.mixins.json (1)
   [INFO] Mixing NumenOfflineMultiplayerMixin from numen.mixins.json into net.minecraft.class_310
   ```

   那条 WARN 是无害的（mixin 0.8.7 自身最高只到 JAVA_13，但不影响注入），
   降到 JAVA_13 反而又会被跳过——因为类的字节码版本仍是 61。
   **判断方法**：加 `-Dmixin.debug=true -Dmixin.debug.verbose=true` 启动，
   日志里必须看到 `Mixing <你的Mixin> from <你的配置> into <目标类>`。
   看不到就是没生效，别看"没崩"就以为成功了。

6. ⚠️ **日志占位符是 slf4j 的 `{}`，不是 printf 的 `%s`。**
   `NumenCore.info` 直接把 format 交给 `Logger.info`，全项目 28 处都用 `{}`；
   这次新写的三处顺手写了 `%s`，日志就原样打出 `[诊断] %s() 原始返回 = %s`，
   参数全丢。为此白跑了一轮验证，误以为"注入没被调用"。

7. 实测确认（独立 gameDir 起的离线客户端，`--userName T --accessToken 0`）：

   ```
   [numen] 离线会话：多人游戏原本被禁用，已放行（账户 T（令牌长度 1））
   [numen] 要关掉这个行为，加 JVM 参数 -Dnumen.offlineMultiplayer=false
   ```

   即 `allowsMultiplayer()` 原始返回确实是 `false`，被掰成了 `true`。

### 8.5 顺带发现：映射文件里写着、真实 jar 里没有的成员

写这个 mixin 时顺手记了账户信息，用了 `User.getType()`（1.16.5 的
`User.Type` 枚举看着挺有用），结果**编译不过**：

```
NumenOfflineMultiplayer.java:94: 错误: 找不到符号
    return user.getName() + " / " + user.getType();
                                        ^  符号: 方法 getType()
```

而 `docs/mm.py "client.User"` 明明写着 `53:53:...getType() -> f`。查证下来：

- 真实 jar 里 `net.minecraft.client.User` = `dkm`，只有 `a()`~`e()` **五个**方法，
  `f()` 不存在；
- 整个 jar 里**只有 `dkm.class` 和 `dkm$a.class` 自己**引用了 `dkm$a`(User$Type)，
  也就是说 `User.Type` 这个字段全游戏没人读；
- jar 自带的 `version.json` 确认是 1.16.5（`world_version 2586`，2021-01-14）。

结论：**Mojang 的官方映射描述的是「未混淆构建」的源码级 API，
不等于 ProGuard 之后发布 jar 里真实存在的成员**——没被用到的成员会被裁掉。
所以 `mm.py` 只能用来**发现候选差异**，任何准备真正调用的成员，
都要按第六节的办法在真实 jar 上 `javap` 核一遍。

（这条比第 2.3.1 节更狠：那里是"修饰符查不出来"，这里是"成员根本不存在"。
共性是同一个——**mm.py 的输出不能当成编译保证**。）


---

## 九、G 面板（把 `/numen` 那组命令换成能点的界面）

### 9.1 是什么

按 **G** 打开一个整块自绘的面板，两页签：

- **同伴页**：同伴列表（名字 / 坐标 / 血条 / 状态）、召唤输入框、说话输入框、遣散按钮；
- **设置页**：服务商 / 模型 / 端点 / 密钥 / 主动性 / 人设 六个配置项，保存、重新载入，
  以及外接大脑（MCP）的开关与「复制端点 / 复制令牌」。

数据流复用已有的 **服务端 → 客户端** 快照通道（`NumenPanelNet.CHANNEL`）：
面板只读快照、只发意图，自己不算任何游戏逻辑。这一点是刻意的，
理由与「core/net 与 core/mcp 里一个客户端类型都不能出现」是同一条——
`server-api-check.py` 会盯着。

### 9.2 为什么不用原版的 Button / EditBox

原版控件在 1.16.5 上换一套皮肤要贴着 1.17 才有的渲染改动，跨版本会同步不上。
面板全部自绘（直角矩形 + 分隔线 + 自己写的 `NumenField` 输入框），
好处是「画在哪」和「点到哪」由同一段代码决定：绘制时把可点区域记进 `hits`，
`mouseClicked` 只查这张表。分开算两遍是这类界面最经典的错位来源。

### 9.3 ⚠️ 布局溢出：不只是难看，还会「点到不该点的地方」

**症状**（用户截图）：设置页的 MCP 那一块画到了底部状态栏上，最后一个按钮被面板下边缘裁掉。

**成因**：面板高度被窗口高度卡住。

```
面板高 = min(280, 窗口高 - 20)
正文区 = 面板高 - HEADER_H(24) - STATUS_H(22)
```

设置页原设计要 **221 像素**（6 个设置行 + 分隔线 + 保存/重新载入 + MCP 两块），
而 252 高的窗口只给正文 **204**、241 高只给 **195** → 溢出 17~26 像素。

**真正危险的地方**：`hits` 是跟着绘制坐标记的。溢出的按钮会在状态栏的
主题开关（`btn:theme`）上再压一个热区，点下去触发的是错的动作。
所以这不能只当成「视觉问题」拖着。

**修法**（三条一起做，只做一条都不够）：

1. 设置页改**两列**：服务商/模型 一行、端点 整行、密钥/主动性 一行、人设 整行 ——
   六行压成四行（省 36 像素）。端点与人设的值是 URL 和一整句话，必须占整行。
2. **端点与令牌合成一行**（左边小字 + 右边两个复制按钮并排），再省 11 像素。
3. 加 `fits(y, h, bottom, what)` 逐块看守：画之前先看还放不放得下，
   放不下就**整块不画**——宁缺一块，绝不叠到状态栏上。
4. 面板高度上限 250 → 280，上下留白 40 → 20。

改完：设置页 **153**、同伴页 **190**。220 高的窗口就能装下设置页。

### 9.4 怎么验证 GUI（可复用）

GUI 的布局代码**只在那一页真的被画出来时才跑**。光启动客户端、停在标题界面，
`fits()` 一次都不会执行——「按键重叠」这类问题根本验证不到。
而真机截图要有人先按 G 再点页签，进不了流水线。

本仓的做法：

```bash
# 面板自动开到设置页；窗口钉死 427x240（"按键重叠"只在矮窗口出现）
python verify-panel.py settings 200 427x240
```

- 客户端埋了 `-Dnumen.panel=settings|companions`（**不设时行为完全不变**）：
  tick 里发现 `mc.screen instanceof TitleScreen` 就自动 `setScreen(...)`。
- `NumenPanelScreen` 第一次绘制时打一行尺寸日志；
  被 `fits()` 拦下的块打 `[numen-panel] 放不下，未绘制：<块名>（需要到 X，正文区只到 Y）`。
- `verify-panel.py` 在**独立 gameDir** 里启动（不动用户开着的客户端），
  写 `options.txt` 的 `overrideWidth/Height` 钉死窗口，然后判定上面两行日志。

**踩过的坑**：标题界面上 `mc.screen` **永远不为 null**（就是 `TitleScreen`），
所以判定条件不能写 `mc.screen == null`——第一版就是这样，脚本跑满 240 秒面板一次没开。
正确写法是 `mc.screen instanceof TitleScreen`。

### 9.5 当前状态

`427x240` 下两个页签都 **OK，无「放不下」告警**（设置页正文区 y=34→208）。
已部署到 PCL 的 mods 目录。

### 9.6 默认预设改成 NVIDIA，以及「获取模型」

**为什么默认是 NVIDIA**：它是 OpenAI 兼容站，`GET /v1/models` 直接列出模型，
且**不填密钥也能列出**（实测返回 80 个），所以玩家可以「先获取列表、再挑一个、最后填 key」，
不会一上来就被「密钥不对」卡住。默认三项一起改：

```java
provider = "nvidia";                                  // NumenConfig
model    = "deepseek-ai/deepseek-v4.1-flash";
baseUrl  = "https://integrate.api.nvidia.com/v1";
```

配套：`numen_providers.json` 新增 `nvidia` 条（第 16 个服务商），
`ProviderRegistry` 认 `nim` / `nvidia-nim` / `build.nvidia` 三个别名。

**「获取模型」是干什么的**：模型清单**一直在变**（下线旧型号、上新型号），
内置的 `numen_providers.json` 写死了就会过期。让玩家填完 key 点一下按钮，
**直接问服务商要一份真实清单**，比维护写死的列表准，也省得去文档抄模型名。

**实现（四个新文件 + 三处改动）**：

| 位置 | 做什么 |
|---|---|
| `core/llm/ModelCatalog.java` | 组装 URL + 发请求 + 解析 `data[].id` + 把异常翻成中文 |
| `core/llm/ModelCatalogState.java` | 结果**存在服务端**，等面板下一次拉快照时带走 |
| `agent/http/HttpLlmTransport.get()` | 传输层补一个 GET（与 POST 同一套代理/报头口径）|
| `core/net/NumenPanelNet` | 新 `OP_MODELS` + 新快照字段 `models` |
| `core/net/NumenPanelServer` | 异步处理：先回「正在获取…」，拿到结果再回一条 |
| `core/client/NumenPanelScreen` | 「获取模型」按钮 + 下拉选择浮层 |

**两个关键设计**：

1. **结果必须挂在快照上，不能只回一条消息**。面板每 20 tick 主动要一次快照，
   如果结果只放在「发起请求那条消息的回复」里，下一次快照轮询就把它盖掉了
   ——界面会闪一下「拿到 80 个模型」然后变回空白。所以让它进 `ModelCatalogState`，
   **每一次** `snapshot()` 都带上。

2. **模型列表是浮层，不是正文里的列表**。正文区高度被窗口卡死（240 高的窗口只有 174 像素），
   塞 80 个模型进去立刻回到 9.3 那个溢出问题。所以做成**最后画的下拉浮层**，
   并且把它覆盖范围内的 `hits` 热区**删掉**（`hits.removeIf(...)`），
   保证「看到的」和「点到的」一致。滚动条给浮层，Esc 先关浮层再关面板。

**URL 组装的三个坑**（`ModelCatalog.modelsUrl`）：玩家可能填
`https://integrate.api.nvidia.com`（不带 `/v1`）、带 `/v1` 的完整地址、
甚至把聊天路径 `/chat/completions` 一起粘进来。三种都得认：
剥聊天路径 → 补 `/v1` → 接 `/models`。已用 9 组 URL 样例测过（含尾斜杠、`/beta`、`/v4`、前后空格）。

**踩到的坑**：

- `JsonArray.isEmpty()` / `JsonElement.deepCopy()` 都是 **gson 2.8.2+**，
  而 1.16.5 自带 **2.8.0** → 用 `.size() == 0`，深拷贝**手抄一遍**。（同 3.4）
- `hits.removeIf(h -> h.y1() < ly2 ...)` 编译不过：`ly1`/`ly2` 被重新赋值，
  不是「实际上最终变量」→ 先拷成 `final int clearY1/clearY2`。
- `ConnectException.getMessage()` 是 **null**（实测），直接拼会显示「连不上：null」，
  像是我们自己的 bug → 加 `fallbackIfBlank()` 兜底。
- 服务商返回裸 404（`404 page not found`，不是 JSON）时不能只显示状态码，
  要说明「该站点没有 /models 这个接口」并提示手工填模型名。

**离线验证过的**（三个独立小测试，未进游戏）：
`modelsUrl` 9/9 通过；`parseIds` 对真实的 80 个模型解析正确、且 `{}` / 缺 `data` /
`data` 不是数组 / 条目字段缺失都能安全返回；真网络端到端拿到 **80 个模型**；
错误路径（错域名 → `ConnectException`、错路径 → `HTTP 404`）文案正确。
游戏内的「点按钮 → 服务端 → 回填」整条链路尚未在运行中的客户端里走一遍。

## 十、寻路模块（`pathing`）：55 条编译错误全部修完（2026-10-10）

上游的寻路是独立模块（`pathing/`，117 个文件），**只对着原版 Minecraft 编译**，
classpath 上没有 `ai`/`agent`/`lua`/`core`——这条边界由编译器守，不是靠自觉。
它的源码是照 **1.20.1** 写的，所以第一轮编译 `javac` 报了满 100 条（javac 的上限），
降下来之后还剩 **55** 条。**这 55 条已全部修完，`:pathing:build` 绿。**

结论先写在这儿：**55 条全是同一类问题——上游用了一批 1.16.5 上没有（或叫法/签名不同）的 API，
没有一条是「做不了」的。** 而且 35 条只集中在 8 个符号上，改起来是机械的批量替换。

### 10.1 按根因分组（55 条）

| 1.20.1 的写法 | 1.16.5 的对应写法 | 条数 | 依据 |
|---|---|---|---|
| `player.serverLevel()` | `player.getLevel()`（`ServerPlayer` 覆写，返回 `ServerLevel`） | 8 | javap |
| `BlockPos.containing(x,y,z)` | `new BlockPos(x,y,z)`（有 `(double,double,double)` 构造器，同样向下取整） | 7 | javap |
| `stack.is(item)` | `stack.getItem() == item` | 6 | javap |
| `box.distanceToSqr(vec)` | **没有**，自己写（见 10.3） | 4 | javap |
| `Blocks.POWDER_SNOW` | **没有**（细雪是 1.17 才加的）→ 相关分支删掉 | 4 | javap |
| `section.hasOnlyAir()` | `section.isEmpty()` | 2 | javap |
| `body.setYRot(f)` / `setXRot(f)` | `body.yRot = f` / `body.xRot = f`（**public 字段**） | 2 | javap |
| `Mth.square(double)` | 只收 `float` → 直接写 `x * x` | 2 | javap |
| `body.setMaxUpStep(f)` | `body.maxUpStep = f`（public 字段） | 1 | javap |
| `ItemStack.isSameItemSameTags(a,b)` | `ItemStack.matches(a,b)`（静态） | 1 | javap |
| `Inventory.SLOT_OFFHAND` | 没有这个常量 → 裸下标 **40**（主背包 0..35、盔甲 36..39、副手 40） | 1 | javap |
| `state.blocksMotion()` / `state.liquid()` | `state.getMaterial().blocksMotion()` / `.isLiquid()` | 1 | javap |
| `effect.isInfiniteDuration()` | `effect.isNoCounter()`（**语义略有差别**，见 10.3） | 1 | javap |
| `door.type().canOpenByHand()` | `DoorBlock.isWoodenDoor(state)`（1.16.5 没有方块组 BlockSetType） | 1 | javap |
| `WalkNodeEvaluator.isBurningBlock(s)` | 在 1.16.5 是 **private** → 照字节码抄一份（见 10.3） | 1 | javap -c |
| `cell.setY(y-1)` 当表达式用 | 1.16.5 的 `MutableBlockPos.setX/setY/setZ` 返回 **void** → 拆成两句 | 1 | javap |
| `Level.collidesWithSuffocatingBlock(e, box)` | **没有** → 自己按 `isSuffocating` 逐格问（见 10.3） | 1 | javap |
| `new EntityCollisionContext(b,d,ItemStack,Pred)` | 第 3 参是 **`Item` 不是 `ItemStack`**（空手传 `Items.AIR`），且**只有 4 个参数** | 1 | javap |
| `new ServerboundPlayerActionPacket(a,pos,face,seq)` | 只有 **3 参**版，去掉序列号 | 1 | javap |
| `doCheckFallDamage(x,y,z,onGround)` | 只有 **2 参**版 `doCheckFallDamage(double yDiff, boolean onGround)` | 1 | javap |
| `LongSet.of(v)` | fastutil **8.2.1** 没有 → `LongSets.singleton(v)` | 1 | javap |
| `container.copy()` | **没有**（`copy`/`clone` 都没有）→ 序列化往返（见 10.3） | 1 | javap |
| `ServerPlayer.walksOnPowderSnow()` | **没有**（细雪 1.17）→ 删 | 1 | javap |
| `ServerLevel.enabledFeatures()` | **没有**（特性开关 1.19.3 才有）→ 删 | 1 | javap |
| `EnchantmentHelper.getSneakingSpeedBonus` | **没有**（迅捷潜行附魔 1.19 才有）→ 删，蹲行速度就是常数 0.3 | 1 | javap |
| `body.minorHorizontalCollision` | **没有**（1.20.5 才加）→ 只用 `horizontalCollision` | 1 | javap |
| `BodySnapshot.inventory` | record 的字段是 private → `body.inventory()` | 1 | 编译错 |

### 10.2 手法：先映射找候选，再在真实 jar 上核

这 55 条**没有一条是猜的**。流程固定：

1. 用 `docs/mm.py` 在官方映射里找候选（注意映射是**左可读名、右混淆名**，反的 proguard 格式）；
2. **任何真要调用的成员，都在真实 jar 上 `javap` 核一遍**——包括访问修饰符、参数类型、
   以及「映射里写着、真实 jar 里其实没有」的成员（见 §8.5）。

这次还有一个比 `javap` 更好用的东西：Loom 缓存里有**去混淆的合并 jar**，直接对着它 `javap` 最省事——

```
C:/Users/cfl/.gradle/caches/fabric-loom/1.16.5/loom.mappings.1_16_5.layered+hash.2198-v2/minecraft-merged-named.jar
```

`javap -p -c` 还能读**方法体**，这次有两处直接靠它拿到了答案（见 10.3）。

### 10.3 三处不是「改个名」就能解决的

**(1) `AABB.distanceToSqr(Vec3)` —— 1.16.5 的 `AABB` 上根本没有这个方法**
（`distanceToSqr` 和 `distanceTo` 都没有）。四处调用点，统一在 `world/Reach` 里补了一个：

```java
public static double distanceToSqr(AABB box, Vec3 point) {
    double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
    double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
    double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
    return dx * dx + dy * dy + dz * dz;
}
```
（`AABB.minX..maxZ` 在 1.16.5 是 public final 字段，可以直接读。）

**(2) `PalettedContainer.copy()` —— 没有，`clone` 也没有。**
1.17 才加的 `copy()`。1.16.5 的标准做法是**序列化再读回来**，也正是区块走网络时那段代码：

```java
FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
section.write(buffer);                     // LevelChunkSection 自带 write/read
LevelChunkSection scratch = new LevelChunkSection(0);
scratch.read(buffer);
return scratch.getStates();
```
核过 `PalettedContainer.read` 内部 `acquire()`/`release()` 是配平的，不会漏引用。

**(3) `BlockGetter.traverseBlocks` —— 签名和 1.20 完全不同，而且不能靠传 null 绕过。**

1.20 是 `traverseBlocks(Vec3 from, Vec3 to, BlockGetter, BiFunction, Function)`，自己拼 `ClipContext`；
1.16.5 只有 `traverseBlocks(ClipContext, BiFunction, Function)` 三参版，**`ClipContext` 得自己造**。
而 1.16.5 的 `ClipContext` 构造器第 5 个参数是 **`Entity`**（不是 1.20 那样的 `CollisionContext`），
并且它**无条件**调 `CollisionContext.of(entity)`；`CollisionContext.of(null)` → `new EntityCollisionContext(null)`
→ `entity.isDescending()` → **当场 NPE**（以上全部在真实 jar 的字节码上核过）。

本模块的搜索阶段只有世界快照、**根本没有实体**，所以最后把原版那段逐格推进**原样搬进了 `Sight.march`**：
`-1.0E-7` 的容差、`Mth.sign`/`Mth.frac` 的用法、三个轴的比较顺序，一处不改。
（原版那个方法本身只读 `ClipContext` 的起终点、从不读它的碰撞上下文——这也从字节码确认了，
所以搬进来与调它是等价的。）

⚠️ **怎么确认搬对了**：写了 20000 条随机射线，把 `Sight.march` 走出来的格子序列与
**另一套独立实现**（事件法：算射线与所有整数平面的交点、按 t 排序、逐个跨格）逐条比对——
**0 条不一致**。这种「手工搬浮点代码」的地方，**光看是看不出来的，必须拿一个独立实现来对**。

### 10.4 `isBurningBlock`：private 也能抄，因为字节码是公开的

`WalkNodeEvaluator.isBurningBlock` 在 1.16.5 是 **private**，调不到。但 `javap -c` 能看到它的实现，
逐条指令读出来就是四个条件：

```java
state.is(BlockTags.FIRE) || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)
        || CampfireBlock.isLitCampfire(state)
```
照抄即可。⚠️ 注意**别照抄上游 1.20 的那一版**——它多一个岩浆炼药锅，那是后来加的。

### 10.5 `isInfiniteDuration` → `isNoCounter`：这里有个语义差

`isInfiniteDuration()`（1.20）判的是 `duration == -1`；`isNoCounter()`（1.16.5）判的是
「这一条效果不倒计时」（信标、潮涌给的就是这种）。**两者不完全等价**，
1.16.5 这一代「无限」就是用 `noCounter` 表示的，所以取它；但这属于**语义降级**，
以后真机验证「憋气」相关行为时要留个心眼。

### 10.6 `settings.gradle`：那条「不能 include pathing」的注释已经不成立

老注释说「Loom 会把同构建里所有 Loom 子项目当嵌套模组收进 jar，core 的 jar 就出不来」。
**2026-10-10 实测不成立**：只有 `core/build.gradle` 里显式 `include project(':xxx')` 的那几个
（`agent`/`ai`/`lua` + 两个库）才进 `META-INF/jars`，`pathing` 不在其中
（它没有 `fabric.mod.json`，Loom 只打一行提醒就放过了）。所以 `include('pathing')` **已放开**，
`:core:build` 会连带编译它——pathing 一旦被改坏当场就能发现，是净收益。

### 10.7 当前状态与下一步

- ✅ `:pathing:compileJava` / `:pathing:build` / `:core:build` 全绿（55 条清零）。
- ⏭ **还没接进游戏**：`pathing` 目前是「编译得过但没人调」的死代码。
  下一步要在 `core/` 里写适配层（`Body`/`TerrainPolicy`/`Materials`/`Threats`/`Effector` 的 Numen 侧实现），
  把 `NumenTools.goto` 从现在的「直线走法」换成 `Navigator.drive`，
  并删掉 `core/body/` 下那 4 个临时文件。**之后才算真的能跑，也才需要真机验证绕障碍。**

## 十一、寻路接进游戏：第一次真机测试，以及「ProGuard 裁掉的成员」这一整类坑（2026-10-10）

### 11.1 接线做了什么

按上游 `core/common/.../nav/` 的做法把寻路接到同伴身上：

| 改动 | 位置 |
|---|---|
| `implementation project(':pathing')` + **把 class 平铺进发行 jar** | `core/build.gradle` |
| 删掉 4 个临时副本（`core/body/{Body,Controls,Physics,BodyCompat}.java`） | 改用 `pathing/body/` 那一份 |
| 给 `NumenPlayer` 加一双**每具身体唯一**的手（`PlayerHands`） | `core/entity/NumenPlayer.java` |
| 端口工厂：手 / 地形许可 / 垫路料 / 威胁 | `core/nav/CompanionPorts.java`（新） |
| 一趟「走到某处」的驱动：包目标、按刻推进、把结局翻成中文 | `core/nav/CompanionNav.java`（新） |
| `goto` 从直线走法换成真寻路；删掉 `Walker` | `core/agent/NumenTools.java` |

⚠️ **`pathing` 不能用 `include` 当嵌套 jar**（它是 Loom 子项目，产出的是 remapJar 产物、没有
`fabric.mod.json`）。上游 `api-loader.gradle` 的做法是 `from({ project(':pathing').sourceSets.main.output })`
**平铺**进 jar——本仓照做。平铺后 `remapJar` 会连它们一起从 named 换成 intermediary，
实测 jar 里 242 个 pathing 类都已是 intermediary 名，运行期透明。

### 11.2 ⚠️ 第一次真机测试：召唤同伴当场把服务端打崩

```
java.lang.NoSuchMethodError: 'boolean net.minecraft.class_3222.method_20448()'
	at com.dwinovo.numen.pathing.body.Controls.apply(Controls.java:86)
	at com.dwinovo.numen.pathing.body.Physics.step(Physics.java:28)
	at com.dwinovo.numen.core.entity.NumenPlayer.tick(NumenPlayer.java:233)
```

`method_20448` = **`Entity.isVisuallyCrawling()`**。

**这不是「1.16.5 没有这个方法」**——它在 Loom 给模组编译用的**合并 jar**里有，
所以**编译期完全看不出来**。它在**专用服务端的 jar 里没有**：
发布 jar 经过 ProGuard，**没人引用的成员会被裁掉**（§8.5 记过这条，这次是它的第二个实例）。

### 11.3 一次性把这一类全找出来：把 `server-api-check.py` 的网补上

那次崩溃本来该被 `server-api-check.py` 拦住，但**没拦住**，两个原因：

1. **它只扫 `com/dwinovo/numen/core/`。** `pathing` 不在 core 的源码树里，
   却是**平铺进发行 jar** 的——运行时就是 core 的一部分，一样会炸。
   修法：加一个 `PACKAGES = ("com/dwinovo/numen/core/", "com/dwinovo/numen/pathing/")`。
2. **它把「继承来的成员」误报成「缺失」**，因为 **javap 会把过长的类头折行**：
   ```
   public interface net.minecraft.class_1936 extends net.minecraft.class_1922,
       net.minecraft.class_2818 {
   ```
   老代码要求「类头在一行里以 `{` 结尾」，折行时整条父链就丢了。
   实测冤枉过 `Level.getMaxBuildHeight`、`Entity.getEyeHeight`。
   修法：**先把折行的类头拼回一行**，并且 `extends A, B` 的**逗号后续项**也要收下。

补完之后一次抓出 **10 条**，逐条在服务端 jar 上核实，**7 条是真的缺**（另 3 条是上面那个折行 bug 的误报）。

### 11.4 真缺的 7 个成员，以及它们为什么缺

| 被裁掉的成员 | 谁在用 | 为什么服务端没有 |
|---|---|---|
| `Entity.isVisuallyCrawling()` | `body/Controls` | 只有客户端渲染用得到 |
| `Entity.getEyeHeight(Pose)` | `body/Snapshots` | 同上（`getEyeHeight(Pose, EntityDimensions)` 与 `getStandingEyeHeight` **也是** `protected`/被裁） |
| `MobEffectInstance.isNoCounter()` | `body/Snapshots` | `setNoCounter` 同样没有——这一对 API 在服务端整个被裁 |
| `Inventory.setPickedItem(ItemStack)` | `body/Hotbar` | 创造模式中键取物的客户端路径 |
| `ProjectileUtil.getEntityHitResult(...)` | `body/Crosshair` | 实体拾取（准星打实体）是客户端的事 |
| `Vec3.directionFromRotation(float,float)` | `body/Crosshair` | 纯客户端用的方向助手 |
| `LevelChunkSection.read(FriendlyByteBuf)` | `search/WorldSnapshot` | 服务端只**写**区段、不**读**区段（那是收包的一方做的） |

⚠️ **规律**：被裁的多是「**客户端才走的那条路**」——渲染、准星、收包。
寻路模块原本对着**合并 jar** 编译，看不见这条界线。

### 11.5 怎么查「服务端到底有没有这个成员」——两个可靠办法

**(1) 按类 javap**（快，但只回答「这个类自己有没有」）：
```bash
javap -p -classpath <server-intermediary.jar> net.minecraft.class_3222 | grep method_20448
```

**(2) 扫整个 jar**（最definitive，且能排除子串误报）：
```python
# ⚠️ 必须加词边界，否则 method_1030 会匹配到 method_10305
pat = re.compile(rb"(?<![0-9A-Za-z_])method_20448(?![0-9A-Za-z_])")
```

**中间名 → 可读名**：用 Loom 的三列映射，比反查混淆名省事得多。
```
~/.gradle/caches/fabric-loom/<MC版本>/loom.mappings.<...>/mappings.tiny
# tiny v2，三列：official / intermediary / named
```
⚠️ **成员行的描述符列是「混淆类型」，不是 intermediary**——
想按 `(Lnet/minecraft/class_1799;)V` 匹配会全部落空，只能按成员名匹配。

### 11.6 当前状态

- ✅ 接线完成；`:core:build` 绿；jar 里 pathing 类已 remap；`server-api-check.py` 补网后能抓这类问题。
- ❌ **一召唤同伴就崩**（`isVisuallyCrawling`），**游戏里还跑不起来**。
- ⏭ **下一步**：把 §11.4 那 7 处逐个换成服务端安全的写法，每换一个都要
  （a）在服务端 jar 上核实替代 API 存在、（b）重跑 `server-api-check.py`、
  （c）真机重测。**替代写法不能想当然**——实测这条链很深：
  想用 `getEyeHeight(Pose, EntityDimensions)` 替代 `getEyeHeight(Pose)`，
  发现前者是 `protected`；想用 `getStandingEyeHeight`，发现它也被裁了。

### 11.7 七处全部修完，并且真机跑通了（2026-10-10）

**替代写法**（每一处都先在专用服务端 jar 上核过存在性与可见性）：

| 原调用 | 替代 | 备注 |
|---|---|---|
| `Entity.isVisuallyCrawling()` | `isVisuallySwimming() && !isInWater()` | 照字节码内联 |
| `Entity.getEyeHeight(Pose)` | 内联 `Player.getStandingEyeHeight` 的常数表 | 链很深：2 参版是 `protected`、`getStandingEyeHeight` 也被裁 |
| `MobEffectInstance.isNoCounter()` | 退回 `getDuration()` | **语义降级**（`setNoCounter` 也被裁，没有替代 API） |
| `Inventory.setPickedItem(stack)` | 照字节码重写：挑格 → **先把旧物挪到空格** → 放入 | 「先挪走」不能省，省了就是凭空吃掉玩家的东西 |
| `ProjectileUtil.getEntityHitResult(...)` | 自己用 `Level.getEntities` + `AABB.clip` 挑最近的一只 | |
| `Vec3.directionFromRotation(pitch,yaw)` | 内联公式，放在 `BodyCompat`，**两个调用点共用** | ⚠️ 差点漏掉 `Aim` 那一处——改完检查脚本又抓出来了 |
| `LevelChunkSection.read(FriendlyByteBuf)` | 改走 **NBT** 那条路：`PalettedContainer.write(CompoundTag,String,String)` + `read(ListTag,long[])` | 二进制 `read` 被裁；NBT 那条服务端**都在**，而且同样是数组拷贝（不是逐格复制） |

`server-api-check.py`：**10 条 → 0 条**（276 个类全扫）。

### 11.8 ⚠️ 第九个坑：`from({ ... })` 闭包会**丢掉任务依赖**

改完七处后重新构建，发现**发行 jar 里一个寻路的类都没有**（0 个）：

```groovy
tasks.named('jar', Jar) {
    from({ project(':pathing').sourceSets.main.output })   // ← 闭包不携带任务依赖
}
```

闭包不携带任务依赖 → `:core:jar` 可能抢在 `:pathing:compileJava` 前面跑，拿到一个空目录。
**第一次没暴露**，只是因为 pathing 的类恰好已经编译过、目录里有东西。

这个组合**三处都试错了一遍**：

| 写法 | 结果 |
|---|---|
| `dependsOn project(':pathing').tasks.named('classes')` | 配置期就解析任务对象 → `Task with name 'classes' not found` |
| `from project(':pathing').sourceSets.main.output`（非闭包） | 配置期取 pathing 的 `sourceSets` → `Could not get unknown property 'sourceSets'`（core 先于 pathing 配置） |
| ✅ `dependsOn ':pathing:classes'`（字符串=惰性）+ `from({ ... })`（闭包=执行期求值） | 正确 |

**⚠️ 验证方式**：这种「任务顺序」的坑，**只跑 `:core:build` 验不出来**（增量构建下 pathing 的类早就在了）。
必须 `gradle clean :core:build` 从零构建，再看 jar 里有没有那些类（实测 243 个）。

### 11.9 真机验证：寻路跑通了

测试环境：本机专用服务端（超平坦世界）+ 自动进服的客户端 + 经 MCP 驱动。

**1. 召唤同伴不再崩**（原崩溃点已修）。

**2. 真寻路**（27 格）：
```
[numen-path] 搜索 从脚下 -127,4,-103 去 within(at(-105,4,-85) 0.0..1.5) dig=false place=false
  展开 92 用时 63.5ms 停因 ARRIVED 到目标 21 步 [WALK×4 DIAGONAL×17] 代价 99.9 改 0 格
  结局 Arrived[] 走了 21 步 挖 0 放 0 用了 107 刻 墙钟 5.3s
```
21 步里 17 步是斜着走的 —— 搜出来的路线，不是直线；**一格没挖**（符合默认规格）。

**3. 绕开障碍**（决定性证据）：先在她旁边挖一个 4 格深的坑（`maxFall=3`，掉进去会摔伤），
再让她走到坑对面的 (-160,4,-86)：
```
搜索 从脚下 -165,4,-86 去 within(at(-160,4,-86) 0.0..1.5)
  展开 9 用时 1.1ms 停因 ARRIVED 到目标 4 步 [WALK×3 DIAGONAL×1] 代价 15.7 改 0 格
  -165,4,-86 → -161,4,-85
```
**终点是 `z=-85` 而不是 `-86`** —— 她先斜着挪到 z=-85，再沿 z=-85 往东走，
**完全避开了 (-164,-86) 那个坑**。直线本该正好从坑上过。

**4. 远距离**：60 格一次走完（约 11 秒）。

**5. 到不了的目标**：报 `OutOfBudget`（搜到 40000 节点预算用完，不能证明没路），
面板上显示「太远或太绕，搜索没搜完就放弃了——换个近一点的地方试试」。

### 11.10 现在的状态

**寻路已经可用。** 同伴会自己找路、绕开深坑、不走直线；默认不挖不放（要改地形得改路线规格）。

还没做的（按重要性）：
- **权限层**（上游 `GateTerrain` + `Gate`）：挖/放之前问主人许不许。本仓现在是 `TerrainPolicy.ALLOW_ALL`，
  所以**目前根本不会挖**（默认规格 `dig=false`）——加权限层是等放开挖掘之后的事。
- 上游 `core/common/.../nav/` 里其余的件（`Trip`/`Journey`/`NavText`/`Survey`/`BoatNav`/`DigQuote`/
  `ThrowawayBlocks`/`RouteQueries`/`Feet`）——这一版只搬了跑通 `goto` 必需的那部分。

## 十二、任务框架与工具族（interact / inventory）— 第一刀（2026-10-10）

### 12.1 先量清楚依赖闭包：这一步比想象的大

上游 `api/common/task/` 分成两层：

| 层 | 文件 | 能不能直接搬 |
|---|---|---|
| **自包含的** | `Task`(91) / `TaskState`(28) / `TaskResult`(88) / `Preparation`(51) / `TaskSelector`(68) | ✅ 只依赖 `NumenPlayer` 与 `ErrorKind`（**后者本仓 `agent` 里已经有了**） |
| **绑在外面的** | `TaskRecord`（绑 `permission.ConsentDesk` + `sdk`）、`TaskSlot`（绑记录与事件）、`TaskFactory`、`TaskApi`（绑 4026 行的 `sdk`） | ❌ |

⚠️ **任何一个真实工具任务的依赖闭包都超过 1.2 万行**：

```
core/common/task/base/AbstractCompanionTask   637
core/common/nav                               2027
api/common/permission                         2216
core/common/act                               1526
core/common/gear                               468
api/common/sdk                                4026
+ entity/BodyDelta, entity/InputDriver, core/PlayerInv, core/FailureType
```

所以「搬框架 + 一两个工具族」**没法一次做完**——先搬能搬的框架，把**能力**按本仓已有的做法
（`core/` 一律是自写的缩减版）做出来，等框架与权限层补齐再回头对齐上游写法。

### 12.2 这一轮搬了什么

**框架**（`core/task/`，逐字照搬）：

| 文件 | 说明 |
|---|---|
| `TaskState` | 六个状态 + `isTerminal()` |
| `TaskResult` | 结果信封（`ok`/`fail`/`timeout`/`cancelled`）。**只删了 `stoppedBy`**——那一个属于记录层 |
| `Preparation` | 受理前的准备（`poll` 每刻问一次，就绪才拿到身体） |
| `Task` | 三层共用的那一个接口（`canRun`/`prepare`/`start`/`tick`/`stop`/`result`） |
| `TaskSelector` | 谁拿身体的纯选择逻辑（反射 → 同步 → 当前任务 → 空闲姿态） |

**工具**（`core/task/act/Acts.java`）——对应上游 `interact` + `inventory` 两族的**能力**：

| 工具 | 做什么 | 走哪条原版路 |
|---|---|---|
| `use_block` | 右键用一格：开箱、开门、按按钮、用工作台 | `Aim.use`（找用得上的面）→ `Aim.look` → `Crosshair.on` → `body.hands().use` |
| `inventory` | 列背包：主手 / 副手 / 盔甲四件 / 主背包 36 格 | 读 `body.inventory` |
| `equip` | 穿装备（原来穿的**换回背包**，不是丢掉） | `Mob.getEquipmentSlotForItem` 找槽 + 换位 |
| `eat` | 吃东西，回饱食度与血量 | `ItemStack.finishUsingItem` |
| `drop` | 丢东西（掉在脚下） | `Player.drop` |

⚠️ **`eat` 是简化版**：上游是真的把东西举起来、等够 `useDuration` 再咽下去；
这里直接调 `finishUsingItem`——营养、效果、创造模式不扣物品都由原版那一个方法做，
但**省掉了举起与咀嚼的那几刻**。要看得见这个过程，得等任务框架接上之后改成多刻的 `Task`。

⚠️ **右手的机制不在这份代码里**：挖掘/放置/右键只有一份，就是寻路模块的 `PlayerHands`
（`body.hands()`）。这里只负责「瞄哪儿、什么时候按、把结果说成人话」——
这正是上游 `CompanionHands` 与 `PlayerHands` 的分工。

### 12.3 验证

- `server-api-check.py`：285 个类全扫，**0 问题**。
- MCP 侧**自动暴露**新工具（它遍历 `NumenTools.specs()`），实测 15 个都在。
- 实机：`inventory` 正确报空背包；**`use_block` 的右键通路真的跑通**（瞄准 → 准星 → 按键 → 服务端处理）；
  `equip`/`eat`/`drop` 找不到东西时给正确的中文提示；**服务端日志无 error / exception**。

### 12.4 还没做的

- **`TaskRecord` + `TaskSlot` + `TaskFactory`**：模型可见的「任务记录」那一层，绑着权限层与 SDK。
- **权限层** `permission`（2216 行）：挖/放前问主人。
- **`act`**（1526 行）：上游把「右键会发生什么」抽成 `Interaction` 单独一层；本仓暂时直接用 `PlayerHands`。
- **`gear/Wardrobe`**：装备的自动选槽与拒绝理由，上游比本仓这一版讲究。
- 上游 `interact`/`inventory` 两族里其余的件：`InteractEntityCompanionTask`（对实体用）、
  `GuiItemsCompanionTask`（读容器界面里的东西）、`TransferCompanionTask`（搬东西）、
  `UnequipCompanionTask`（脱下来）。

### 12.5 ⚠️ 待查：`mine` 挖掉了方块但没有掉落物

实测（新世界 + `gamemode=survival`）：`mine` 报「挖掉了」、`inspect_block` 确认那一格已变成空气
——**挖掘本身是好的**；但背包里没有东西，附近也扫不到掉落。

已排除：`FakeConnection.send` 是**丢弃**出站包、不抛异常，所以不是它打断了 `blockBreaking`。

可疑点：`NumenPlayer` 的 `gameModeForPlayer` 是 **`GameType.NOT_SET`**
（`ServerPlayerGameMode(ServerLevel)` 构造器写死 `NOT_SET`，已核字节码），
而 `placeNewPlayer` 之后**没有调过 `setGameModeForPlayer`**，所以 `abilities` 停在默认值——
`Player.canHarvestBlock` = `abilities.instabuild || state.canHarvestBlock(...)`，
这一条可能为假，于是 `blockBreaking` 里的 `playerDestroy` 没跑。

**下一步**：把游戏模式与 `abilities.instabuild` 打进 `get_status`，先看清她此刻是什么模式。

### 12.6 ✅ 12.5 那条「没有掉落」**不是 bug**——是距离

查清了：`ServerPlayerGameMode.destroyBlock` 的字节码里，
`if (isCreative()) return true;` **之后**才是掉落那一段；她的游戏模式实测是
`SURVIVAL`（`instabuild=false`），所以掉落本来就会发生。

之前「没掉落」的真正原因是**距离**：

| 挖的格子 | 结果 |
|---|---|
| 她脚边（309,3,-138） | ✅ 掉落物**被自动捡起**，背包里出现 `Dirt ×1` |
| 离她 2 格（312,3,-138） | ❌ 掉落物**躺在原地**，她不会走过去捡 |

**「走过去捡掉落」是上游 `DigTask` 的功能，本仓还没搬**——这不是 bug，是一个还没做的行为。

顺带把游戏模式与 `abilities.instabuild` 加进了 `get_status`（排查这类问题时的第一手信息）。

### 12.7 ✅ 完整链路实测通过

| 步骤 | 结果 |
|---|---|
| `mine` 挖脚边草方块 | 回「挖掉了」，`inspect_block` 确认变空气 |
| 自动捡起 | `inventory` 显示 `[0] Dirt ×1` |
| `use_block` 右键旁边的草方块 | **`311,4,-138：minecraft:air → minecraft:dirt`** —— 世界真的变了；独立 `inspect_block` 复核确认 |
| 泥土被消耗 | `inventory` 回到空 |
| 再挖一格 → `drop dirt` | 「丢掉了 Dirt ×1」，背包清空 |

**`interact` + `inventory` 两族的能力已经在游戏里跑通了。**

## 十三、Lua 绑定：第一刀（2026-10-10）

### 13.1 分层：引擎侧早就有，缺的是宿主侧

| 层 | 内容 | 本仓 |
|---|---|---|
| 引擎 | Lua 5.2 沙箱、`ScriptCall`/`ScriptRun`/`ScriptCatalog`/`ScriptEngine`/`Program` | ✅ **早就逐字搬好了**（`agent/script/`，16 个文件） |
| **绑定** | `sdk`（39 文件 4026 行）：`ApiFunction`/`ApiRegistry`/`Binder`/`Dispatcher`/`ServerCall`… | ❌ 这一刀做了个缩减版 |
| 传输 | `program`（22 文件 1730 行）：程序跑在客户端、调用走网络到服务端 | ❌ **本仓不需要**——同伴的大脑本来就在服务端 |
| 函数 | `tools`（36 文件 3757 行）：44 个 API 函数的实现 | ⚠️ 挂了 5 个 |

⚠️ 量出来的两个结论：
- `sdk` 的**注册与派发核心**（`ApiFunction`/`ApiRegistry`/`Binder`/`Dispatcher`）**只依赖 `agent/script`**，是可搬的；
  卡住的只有 `ServerCall`——它绑着权限层（`Gate`/`Verdict`）与任务层（`TaskDispatch`/`TaskRecord`）。
- **`program` 那一层本仓可以不要**：上游是「大脑在客户端、身体在服务端」才需要网络传输；
  本仓的同伴大脑本来就在服务端，调用是同一个线程上的直接调用。

### 13.2 这一刀做了什么

**`core/script/CompanionScript.java`**（一个文件装下三件事）：
1. **登记处**——`register(group, name, options, call)`；
2. **宿主**——实现 `ScriptCall.Host`（`catalog()` / `invocation()` / `tally()` / `now()`）；
3. **驱动循环**——`begin → Dispatch → result → … → Done`，照 `ScriptCall` 的约定走。

**挂上去的 5 个函数**（组名照上游 `numen.<组>.<函数>`）：
`numen.inv.items` · `numen.inv.eat` · `numen.inv.drop` · `numen.gear.hold` · `numen.use.block`

**新的 `lua` 工具**：模型写一段程序，一次做完好几步。

### 13.3 ⚠️ 与上游的三处差别（有意，都还没补）

1. **参数只走选项表**（`numen.use.block{x=1,y=2,z=3}`）——上游那套位置绑定
   （`ApiFunction` 364 行 + `Binder` 199 行）没搬。
2. **没有 JOB**（占身体的活）：所以脚本里**不能调跨刻的动作**（走过去、挖到碎），
   调了会当场失败并说明。要开得等任务层接上。
3. **没有 `help()`**：上游每个函数带 `@Doc`/`@Example`/`@Note` 供模型自查，这一版把说明
   写进了 `register` 的短描述里。

### 13.4 状态

`:core:build` 绿，jar 已部署。**游戏内还没测**——下一次要跑的是：
模型写一段 `numen.inv.items()` + `numen.use.block{...}` 的程序，看回执对不对。

## 十四、归位阶段 1：权限层（permission/ 17 件套）（2026-10-11）

### 14.1 背景与决策

上游有一整套「同伴行为许可」层：同伴每次动手（挖/放/攻击/用方块/命令）都要过
`Gate`（owner 三层：deny→allow→ask）+ 工厂默认表 + 玩家放过的方块账本
（`PlacedBlocks`）+ 规则解析器（`Rule`，如 `break(placed & !contents)`），
不可逆动作还要主人**点头**（`ConsentDesk`）。这是「言出法随」不拆家的安全底座，
归位计划（docs/RESTORE_PLAN.md）把它列为第一刀。

用户拍板 **A 计划**：大脑留在服务端（现状），`program/` 传输层不搬，
内置插件 API（sdk/ 等）逐阶段补。本阶段 = permission/ 全量 + 测试。

### 14.2 1.16.5 适配对照表（全部 javap 过 loom 缓存的 merged-named jar）

| 上游 (1.20.1) | 1.16.5 等价物 |
|---|---|
| `BuiltInRegistries.BLOCK/ITEM` | `Registry.BLOCK` / `Registry.ITEM`（DefaultedRegistry） |
| `TagKey` / `Holder` / `state.is(TagKey)` | `SerializationTags.getInstance().getBlocks().getTagOrEmpty(id)` + `block.is(Tag<Block>)` / `item.is(Tag<Item>)` / `entityType.is(Tag<EntityType<?>>)` |
| `TagCollection.of(...)`/`empty()`/`bind(...)` | 同名 public；`SetTag.create(Set)`；`Tag.Named.getName()`（不是 getLocation） |
| `state.hasBlockEntity()` | `state.getBlock() instanceof EntityBlock` |
| `net.minecraft.core.UUIDUtil` | `Codec.STRING.xmap(UUID::fromString, UUID::toString)` |
| `ChunkPos.asLong(BlockPos)` | `ChunkPos.asLong(x >> 4, z >> 4)` |
| `OwnableEntity.getOwnerUUID()` | `TamableAnimal.getOwnerUUID()` |
| `DimensionDataStorage.computeIfAbsent(supplier,id)` | 同签名存在；`SavedData` 抽象 `load(CompoundTag)+save(CompoundTag)`，构造器 `SavedData(String)` |
| DFU 4.0.26 `DataResult.error(Supplier)` | 只有 `error(String)`；`resultOrPartial(Consumer<String>)` 在 |
| `Item.getDescription()`（服务端被 ProGuard 裁） | `new ItemStack(item).getHoverName()` |
| `Component.translatable/literal` | `new TranslatableComponent(...)` / `new TextComponent(...)` |
| `Entity.level()` | `getLevel()` |
| `SharedConstants.tryDetectVersion()` | 1.17+ 才有；测试引导只 `Bootstrap.bootStrap()` |

### 14.3 有意的过渡差异（A 计划产物，network/ 落地后换回）

上游把征询请求**推到主人客户端**弹 GUI（ConsentRequestPayload）。本版没有
network/，改走聊天消息 + 可点击按钮（`ClickEvent.Action.RUN_COMMAND` →
`/numen consent allow|remember|deny <id>`）。`ConsentDesk.Line` 接缝保持原样，
换回时只动 `BodyLine`。

`ConsentDesk` 的 tick 上游由 CompanionTickDispatcher 驱动（任务框架，阶段 2），
现挂在 `NumenPlayer.tick()` 里 try/catch 兜底。

### 14.4 测试（55 条全绿）

上游 api/common 的测试思路搬过来：`FakeWorld`（只实现 BlockGetter 三个抽象方法）
+ `Bootstrap.bootStrap()` 引导注册表；自定义标签注入用
`TagCollection.of(map)` + `SerializationTags.bind`（**不用** 起 tag loader）。
JUnit 5：BOM 5.11.4 + `useJUnitPlatform()`。分布：Rule 6 / Gate 16 / ConsentDesk 12
/ CommandRule 11（Brigadier 迷你树，含 tp/msg 别名）/ PermissionStore 4 / PlacedBlocks 6。
PermissionStore/PlacedBlocks 测试走静态 `decode(CompoundTag)`（实例 load 与
SavedData.load 撞名）。

### 14.5 🔴 大坑：remapJar 间歇性丢光 pathing 类（真凶找到了，与测试依赖无关）

现象：`:core:build` 绿，发行 jar 里 pathing 的类 0 个（dev jar 243 个好好的），
时好时坏，`git stash` 单变量实验还一度把罪名扣到 junit 依赖头上——**冤案**。

真凶：**loom 0.12 默认跨项目共享 TinyRemapperService**。服务 id 只由
mappings+任务名构成（`remapJarService:named:intermediary:remapJar`），不含项目路径，
于是 `:core:remapJar` 和 `:pathing:remapJar` 共用同一个 tiny-remapper 实例：

1. core 的发行 jar 平铺了 pathing 的类（jar 任务的 `from` 闭包）；
2. pathing 的 dev jar 又在 core 的 compileClasspath 上；
3. 两个 `prepareRemapJar` 并发执行，同一个类被两边各读成一次 **input**，
   tiny-remapper 打 `duplicate input class` 警告并按 InputTag 去重合并；
4. 去重合并的 tag 归属在并发下不稳定，core 的 `apply(tag)` 一筛就把 pathing
   的类整个筛掉——输出 0 个。

修法（两个项目都要）：

```groovy
tasks.withType(net.fabricmc.loom.task.AbstractRemapJarTask).configureEach {
    it.remapperIsolation.set(true)   // 各项目独享 remapper
}
```

验证：clean 构建稳定 243 个 pathing 类 + 339 总类；junit 依赖恢复后 55 条测试照跑全绿。
**以后丢类先查这个，别再冤枉依赖。**

### 14.6 验证

- `:core:build` 绿（含 55 测试）；`server-api-check.py`：447 条 MC 引用全对上，客户端专属引用 0。
- smoke-test.py 扩了权限命令树（mode / mode owner / rules list·add·reset），见下一节实测结果。
