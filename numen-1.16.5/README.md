# Numen — Minecraft 1.16.5 移植版

把 [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)（言出法随）移植到
**Minecraft 1.16.5 + Fabric**。

Numen 是一个**活在 Minecraft 里的 AI 同伴**：你召唤一个同伴（一具服务端的假玩家），
用自然语言跟它说话，它自己规划、自己走过去、自己动手。

**大脑跑在服务端**（与上游不同：上游的大脑在主人客户端，中间靠 `program/` 传输层连通）。
两种跑法，二选一：

- **内置大脑**：provider 与 API key 配在服务端 `config/numen.json`，服务端自己发起推理；
- **外接大脑**：MC 服务端只暴露一个内置的 MCP 服务端，真正的 LLM 循环跑在 **Minecraft 之外**
  （另一台机器/另一个进程），服务端不存 key、也不扛推理算力。

两种都**不需要玩家装客户端 mod**——同伴是服务端假玩家，征询走聊天按钮，
原版客户端连上就能用（少了自绘的 G 面板）。

> 本仓是**非官方移植**。上游源码许可 LGPL-3.0-only。

---

## 现在的状态

| 部分 | 状态 |
|---|---|
| 引擎层 `lua/` `agent/` `ai/`（166 个文件） | ✅ **逐字照搬上游**，已验证编译通过 |
| 引擎层编译验证 | ✅ 123 个主源码 → 364 个 class，零错误（Temurin JDK 17） |
| `core/` Minecraft 适配层 | 🟡 **已写出，未编译**——本机网络到不了 Fabric 仓，见下 |
| 寻路 `pathing/` | ❌ 未搬。现在 `goto` 是直线走 |
| 客户端界面 / 按键 | ❌ 未做。现在走 `/numen` 命令 |
| 完整任务集（建造/战斗/钓鱼/开箱/合成） | ❌ 未搬 |

**一句话**：引擎（最难的部分）已经站在 1.16.5 上了，Minecraft 适配层写完了主体，
但**还没有在能联网的机器上跑过一次编译**。第一次编译大概率要修几处残留的 API 名差——
`docs/PORTING.md` 第二节已经把查证过的差异全列出来了，照着改就行。

---

## 构建状态

**已经编出来了。** 2026-10-09 在本机跑通：

```
./gradlew build           ✅ BUILD SUCCESSFUL
  产出 core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar    (592 KB)
  └─ META-INF/jars/  agent + ai + lua 三个嵌套 jar 已就位

./gradlew :lua:test :agent:test :ai:test    ✅ 465 tests / 0 failures
```

`maven.fabricmc.net` 这个 Cloudflare 域名**时通时不通**（第一次全超时，第二次通）。
解法不是代理，是把**阿里云公共仓排到仓库列表最前面**——loom 那些量大的传递依赖
（asm / guava / jackson / netty）全由它整包接住，Cloudflare 只剩 fabric 独有的几个坐标。
配置已经写进 `settings.gradle` 与 `build.gradle`。

若第一次仍报 `Could not download`，**直接重跑**：Gradle 会保留已下到的部分，
通常第二三次就过（本次是第 3 次拉全 fabric-loom 的）。

> 还没在真实 1.16.5 客户端里跑过。`./gradlew runClient` 的冒烟测试是下一步。

---

## 构建

```bash
cd numen-1.16.5
./gradlew build        # → core/build/libs/Numen-fabric-1.16.5-0.1.4-1.16.5.jar
./gradlew runClient    # 起 1.16.5 + Fabric 开发客户端
./gradlew runServer
```

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
/numen config apiKey sk-xxxxxx          填你自己的 key
/numen config model gpt-4o-mini         或 claude-sonnet-4-... 等
/numen config provider openai           openai / anthropic / deepseek / moonshot
/numen config baseUrl https://api.openai.com/v1
/numen config persona 你是一只话少的猫

/numen summon 小满                       召唤
/numen tell 小满 去把前面那棵树砍了        跟她说话
/numen status 小满                       看她此刻在干什么
/numen list                              看看有谁
/numen dismiss 小满                      遣散
```

配置落在 `config/numen.json`。**key 只存在这个文件里**，只发给玩家自己选的服务商。

同伴现在会这六个动作：`say`、`get_status`、`inspect_block`、`scan_blocks`、`goto`、`mine`。
`mine` 走的是原版「玩家挖方块」那条路（`ServerPlayerGameMode.destroyBlock`），
破坏速度、掉落、统计都照玩家来，不是 `setblock`。

---

## 仓库结构

```
numen-1.16.5/
├── lua/        ← 逐字照搬：Lua 5.2 沙箱虚拟机（纯 JVM）
├── agent/      ← 逐字照搬：ReAct 循环内核 + 事件队列（纯 JVM）
├── ai/         ← 逐字照搬：LLM 连接层，5 套协议（纯 JVM）
├── core/       ← 新写：Minecraft 1.16.5 适配层（模组本体）
│   └── src/main/java/com/dwinovo/numen/core/
│       ├── NumenCoreFabric.java    入口
│       ├── body/                   键盘 / 物理步进 / 交互距离
│       ├── entity/                 假玩家身体、工厂、花名册
│       ├── agent/                  大脑与工具表
│       ├── command/                /numen
│       └── config/                 config/numen.json
└── docs/
    ├── PORTING.md      ← 逐条核过的 1.20.1 → 1.16.5 API 差异表 + 阶段计划
    └── mm.py           ← 查官方映射的小工具（本文所有 API 结论靠它核的）
```

---

## 想接着做

看 `docs/PORTING.md` 第五节。按建议顺序是：
**客户端体验 → 寻路 → 完整任务集 → Lua 程序工具 → 外接大脑/插件**。

`docs/PORTING.md` 第二节那张表是接着做的人最该先读的东西：
里面每一条都是「上游那样写、1.16.5 上编译不过」的坑，对着官方映射核过，不用再踩一遍。

---

## 许可

上游源码 LGPL-3.0-only；截图/示意图 CC BY 4.0；其余美术资源保留所有权利。
本移植沿用同样的许可。
