package com.dwinovo.numen.core;

import com.dwinovo.numen.core.api.CompanionEvent;
import com.dwinovo.numen.core.api.NumenPlugins;
import com.dwinovo.numen.core.command.NumenCommand;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.CompanionEvents;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.mcp.McpActuator;
import com.dwinovo.numen.core.mcp.McpConfig;
import com.dwinovo.numen.core.mcp.McpMode;
import com.dwinovo.numen.core.net.NumenPanelServer;
import com.dwinovo.numen.core.network.NumenNetwork;
import com.dwinovo.numen.core.platform.ServerLifecycle;
import com.dwinovo.numen.core.program.ServerPrograms;
import com.dwinovo.numen.core.script.ModuleApi;
import com.dwinovo.numen.core.sdk.HelpApi;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.TaskApi;
import com.dwinovo.numen.core.agent.tool.ToolRunner;
import com.dwinovo.numen.core.task.chain.SpeakingLookChain;
import com.dwinovo.numen.core.task.reflex.ReflexRegistry;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 模组入口。
 *
 * <p>对应上游的 {@code core/fabric/.../NumenCoreFabric.java}（内容刀）加
 * {@code api/fabric/.../NumenMod.java}（引擎刀）两份接线之和。上游那一侧要挂平台抽象、
 * 网络通道、几百个事件；这一版只挂<b>跑起来必须有的</b>那几件：
 *
 * <ol>
 *   <li>读配置（模型端点 + 外接大脑）；</li>
 *   <li>网络通道登记（{@link NumenNetwork#register}）；</li>
 *   <li>引擎自己的 API 函数组经插件那扇门登记（{@code api} / {@code task} / {@code module}）；</li>
 *   <li>任务排程机器的 lifecycle 接线（同伴进场/离场/死亡 → 排程器）；</li>
 *   <li>每个服务端刻的 START 驱动 {@link CompanionTickDispatcher}；</li>
 *   <li>主人登录/断线/关服的三件事（恢复排队、程序收掉、世界状态作废）；</li>
 *   <li>注册 {@code /numen} 命令；</li>
 *   <li>外接大脑（MCP）服务端的起停与「此刻在不在世界里」的绑定。</li>
 * </ol>
 *
 * <h2>1.16.5 的两处 Fabric API 名字差</h2>
 * <ul>
 *   <li>命令回调在 1.16.5 上是 <b>{@code api.command.v1.CommandRegistrationCallback}</b>；
 *       {@code v2} 那个包是 1.19 才拆出来的。而且 v1 的回调签名是
 *       {@code (CommandDispatcher, boolean)} 两参（v2 是 {@code (dispatcher, registryAccess, environment)} 三参）。</li>
 *   <li>{@code ServerTickEvents.START_SERVER_TICK} 与 {@code ServerLifecycleEvents} 两代同名同形，
 *       可以直接照抄。</li>
 * </ul>
 *
 * <h2>排程器为什么挂 START 而不是 END（照搬上游）</h2>
 * 任务→导航→执行器落下的移动/按键输入由<b>本 tick 的实体物理立即消费</b>——「在位置 P 做的决策
 * 作用于从 P 出发的这一步」，不产生一 tick 的输入滞后（潜行、松跳这些边缘时机全靠它）。
 * 本仓旧的那套自驱动（大脑 + 工具）暂时仍挂在 END，与排程器新旧并存；删旧的是刀 3。
 *
 * <h2>外接大脑（MCP）的生命周期</h2>
 * HTTP 服务器在<b>模组初始化时</b>起（配置里 {@code enabled=true} 的话），之后一直活着——
 * 和上游一样，它绑的是「这个游戏进程」，不是「某一个世界」。
 * 世界进出的只是 {@link McpActuator#bind}/{@link McpActuator#unbind} 那条引用：
 * 不在世界里时调用会如实回「现在不在世界里」，而不是把服务器整个收掉。
 * 这样单机「退到标题界面再进另一个世界」之后，外脑不用重连。
 *
 * <h2>关服时必须收 HTTP 服务器（2026-10-11 实测补的）</h2>
 * {@code com.sun.net.httpserver.HttpServer} 的 <b>HTTP-Dispatcher 线程不是 daemon</b>，
 * 而 JVM 只在「所有非 daemon 线程都结束」后才退出。所以只要不在关服时把 HTTP 服务器
 * <b>真的收掉</b>，即使世界已经存完、netty 已经停干净，<b>专用服务端也会永远停在
 * {@code /stop} 后面不返回</b>——表现是日志停在 "All chunks are saved"，进程不退出，
 * 只能强杀。<b>这条只在专用服务端上暴露</b>（docker 里 SIGKILL 掉就有回档风险），
 * 单机侧因为客户端退出走的是 {@code System.exit}（会强制终止 JVM）所以看不出来。
 *
 * <p>收的时机放在 {@code SERVER_STOPPING}，但<b>只对专用服务端收</b>：集成服务端在
 * 「退到标题界面」时也会发这个事件，那一侧的 HTTP 服务器要留着（见上一段的设计）。
 */
public final class NumenCoreFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // core 内容注册:13 任务族的 numen.* API 组(status/scan/locate/route/move/work/build/fight/use/gui/inv/gear/
        // creative/time)+ 全部 TaskFactory 执行器 + 五条生存链。上游 NumenCoreFabric.onInitialize 的第一件事就是
        // NumenCore.init()(ref/numen-1.20.1/core/fabric/.../NumenCoreFabric.java:21);本仓早期只挂了引擎自己的
        // api/task/module 三组,这 13 族一直没进 runtime——真机 api.help("numen") 只列 3 组即为此,刀 4 验收第一坑。
        NumenCore.init();
        NumenConfig.load();

        // 同伴的背包（#0027，客户端版的 G 键入口）：两边各注册一次，
        // 服务端拿它开容器，客户端拿它弹界面，注册表 id 把两头咬合。
        com.dwinovo.numen.core.inventory.NumenMenus.register();

        // 外接大脑（MCP）：读配置，开着就立刻起服。
        // 本仓没有设置面板，改 host/port/开关要重进游戏（见 docs/PORTING.md）。
        Path mcpFile = FabricLoader.getInstance().getConfigDir()
                .resolve("numen").resolve("mcp_server.json");
        McpMode.instance().bootstrap(mcpFile, McpConfig.load(mcpFile));

        // 网络通道：把所有 payload 登记上去。上游两个 loader 入口的第一件事。
        NumenNetwork.register();

        // 她的 Lua 模块按主人分目录（lua/<主人>/，同一主人的同伴共用一个）。上游那一侧是
        // 客户端，CompanionHome 直接问登录账号；这一侧大脑在服务端，按 Companions 记下的
        // 「同伴→主人」映射解析（每具身体出生时写，见 NumenPlayer#setOwnerUuid）。
        Path numenRoot = FabricLoader.getInstance().getConfigDir().resolve("numen");
        com.dwinovo.numen.core.script.Modules.init(companion -> {
            UUID owner = Companions.ownerOf(companion);
            return numenRoot.resolve("lua").resolve(owner == null ? "unknown" : owner.toString());
        });

        // 出厂的那一套 Lua 库正文（modules/*.lua：numen/work.lua 是 numen.work、numen/move.lua 是 numen.move 等），
        // 原地读 jar 里的 modules/ 目录交给 BuiltinModules。上游在 loader 入口经 ModJar.find 取；Fabric 侧对应
        // ModContainer#findPath（开发运行是磁盘目录，成品是挂在 jar 上的路径）。
        Path bundledModules = FabricLoader.getInstance().getModContainer(Constants.MOD_ID)
                .flatMap(container -> container.findPath("modules"))
                .orElse(null);
        if (bundledModules == null) {
            Constants.warn("jar 里没有 modules/ 目录——出厂的 Lua 库（numen.work.collect 等）不会安装");
        }

        // 引擎自己的几组 API 函数，和 core 与插件走同一扇门（NumenPlugins.register）：
        // api（帮助）、task（手上的活与表）、module（Lua 模块）。它们属于引擎：谁登记了
        // 函数，谁都指望帮助与这几样在。mc（原版指令）那一组上游在 api/common/mc/McApi.java，
        // 尚未归位，登记它的时候一起补上。
        NumenPlugins.register(NumenPlugins.NUMEN, numen -> {
            if (bundledModules != null) {
                numen.bundleModules(bundledModules);
            }
            numen.api("api", "The API itself: the typed signatures of a group's or a module's functions, or one "
                    + "function in full.", HelpApi.class);
            numen.api("task", "The background task and your pending timers.", TaskApi.class);
            numen.api(ModuleApi.GROUP, "Modules — functions written in "
                    + com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.language() + " that programs use by name, kept "
                    + "as files on your owner's computer (numen/work.lua is numen.work): the built-in ones, your own, "
                    + "and how the programs that used them went.", ModuleApi.class);
        });

        // 工具派道的统一入口（上游 CommonClass.registerTools 的位置与口径）：四个大脑工具
        // （lua / skill / todo / memory）+ 本仓的世界工具进同一张 ToolRegistry，MCP 的
        // tools/list 与内置大脑的系统提示都从这张表来。
        ToolRunner.registerAll();

        // 札记落点与技能根（上游在客户端 CompanionHome.init 做的两件；这一侧大脑在服务端，
        // 按同一份 config/numen/ 布局放）。技能目录此刻是空的也没关系——扫一遍记着，rescan 再扫。
        Path numenRoot2 = numenRoot;
        com.dwinovo.numen.agent.memory.NoteBook.init(
                companion -> numenRoot2.resolve("companions").resolve(companion.toString()).resolve("memory"),
                () -> {
                    MinecraftServer s = ToolRunner.current();
                    return s == null ? 0 : (int) (s.overworld().getDayTime() / 24000L);
                });
        com.dwinovo.numen.agent.skill.SkillRegistry.instance().scan(
                numenRoot2.resolve("skills"));

        // 任务排程机器的 lifecycle 接线（照搬上游 CommonClass.wireTaskMachine）：
        // 引擎自己也走同一条总线，和插件用的是同一套事件——没有「内部另有一条捷径」。
        CompanionEvents.subscribe(
                CompanionEvent.DEATH,
                CompanionTickDispatcher::clearActiveTask);
        CompanionEvents.subscribe(
                CompanionEvent.SPAWN,
                CompanionTickDispatcher::onCompanionSpawned);
        CompanionEvents.subscribe(
                CompanionEvent.REMOVE,
                CompanionTickDispatcher::onCompanionRemoved);
        // 引擎自带姿态链的名册文书：提示词总览里的一行。
        ReflexRegistry.register(new SpeakingLookChain());

        // 排程机器的心跳：每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 START（实体更新之前）：任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——「在位置 P 做的决策作用于从 P 出发
        // 的这一步」，不产生一 tick 的输入滞后（潜行/松跳等边缘时机全靠它）。
        ServerTickEvents.START_SERVER_TICK.register(CompanionTickDispatcher::tick);

        // 服务端刻：旧的那套自驱动（大脑 + 长动作）与 MCP 边沿检测，暂时仍挂 END，
        // 与上面的排程器新旧并存（删旧的是刀 3）。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Companions.tick(server);
            McpMode.instance().tick();
            // 刀 4 的工具配套（上游 core/fabric 入口同款）：
            // 预算分片的方块搜索每刻推进（scan.blocks / goto FIND / mine 都靠它收尾）——
            // 不挂它，Pending 永不完成，同伴永远「手上已经有活在跑」。
            com.dwinovo.numen.core.scan.BlockSearch.tick(server);
            // 寻路查询：轮询走完的搜索并回执。
            com.dwinovo.numen.core.nav.RouteQueries.serverTick(server);
        });

        // 服务器起来的时候把配置里的缺口说清楚——比玩家发了消息才拿到一个 401 友好得多。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ToolRunner.bind(server);
            McpActuator.bind(server);
            // 开服入驻:把服务器同伴(无主)放回世界,不等任何玩家登录。
            Companions.scheduleServerRestore();
            String missing = NumenConfig.get().missingReason();
            if (missing == null) {
                Constants.info("端点已就绪：{} / {}", NumenConfig.get().provider, NumenConfig.get().model);
            } else {
                Constants.warn("内置大脑还不能干活：{}", missing);
            }
            if (McpMode.instance().enabled()) {
                Constants.info("外接大脑已开启，端点 {}，令牌 {}", McpMode.instance().endpoint(),
                        McpMode.instance().maskedToken());
                Constants.info("（外脑接管期间内置大脑不干活，上面那条「端点」缺口不影响外脑）");
            }
        });

        // 世界退了：把「在不在世界里」这条引用摘掉。
        // 专用服务端上，这次退出就是整个进程的终点——必须顺手把 MCP 的 HTTP 服务器收掉，
        // 否则它的 HTTP-Dispatcher 线程（非 daemon）会把 JVM 钉住，/stop 永远不返回
        // （日志停在 "All chunks are saved"，只能强杀）。集成服务端要留着给「换世界」用，
        // 所以只在专用服务端收。详见类注释「关服时必须收 HTTP 服务器」。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            McpActuator.unbind();
            if (server.isDedicatedServer()) {
                McpMode.instance().shutdown();
            }
        });

        // 服务器停了：属于那个世界的进程内状态一起作废（照搬上游 ServerLifecycleEvents.SERVER_STOPPED）。
        // 单人「退出存档」不结束进程，静态表会原封不动活到下一个存档——谁持有谁在自己那边
        // 报到，见 ServerLifecycle。
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ToolRunner.unbind();
            ServerLifecycle.fireStopped();
        });

        // 主人登录：只排队，不在这儿恢复——JOIN 跑在原版 placeNewPlayer 内部，在这里出的任何
        // 异常都会打断主人的入场，客户端只看到「无效的玩家数据」（照搬上游注释）。
        // 真正的恢复在下一次服务端刻的排程器里做（CompanionTickDispatcher.tick → restorePending）。
        // 1.16.5 差异：回调给的是 ServerGamePacketListenerImpl，玩家是公开字段 player（1.20.1 才有 getPlayer()）。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (handler.player instanceof com.dwinovo.numen.core.entity.NumenPlayer) return;  // 不是同伴自己
            Companions.scheduleRestoreFor(handler.player.getUUID());
            Companions.syncRosterToOwner(server, handler.player);
        });

        // 主人断线：他名下在服务端跑着的程序收掉，等他答复的反向请求失败，他的模块缓存清掉。
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!(handler.player instanceof com.dwinovo.numen.core.entity.NumenPlayer)) {
                ServerPrograms.ownerLeft(handler.player.getUUID());
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> NumenCommand.register(dispatcher));

        // G 面板的服务端一侧。客户端那一半在 core/client 下，由 fabric.mod.json 的
        // client 入口点负责；这里注册的只是「收请求、干活、回快照」。
        // 专用服务端上也会注册——装了模组的客户端连过来才有得聊。
        NumenPanelServer.register();

        Constants.info("Numen 1.16.5 移植版已加载");
    }
}
