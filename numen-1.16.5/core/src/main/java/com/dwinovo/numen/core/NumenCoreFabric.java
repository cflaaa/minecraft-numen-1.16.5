package com.dwinovo.numen.core;

import com.dwinovo.numen.core.command.NumenCommand;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.mcp.McpActuator;
import com.dwinovo.numen.core.mcp.McpConfig;
import com.dwinovo.numen.core.mcp.McpMode;
import com.dwinovo.numen.core.net.NumenPanelServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 模组入口。
 *
 * <p>对应上游的 {@code core/fabric/.../NumenCoreFabric.java}。上游那一份还要挂平台抽象、
 * 网络通道、数据生成、几百个事件；这一版只挂<b>跑起来必须有的</b>那几件：
 *
 * <ol>
 *   <li>读配置（模型端点 + 外接大脑）；</li>
 *   <li>每个服务端刻驱动一次所有同伴的大脑；</li>
 *   <li>注册 {@code /numen} 命令；</li>
 *   <li>外接大脑（MCP）服务端的起停与「此刻在不在世界里」的绑定。</li>
 * </ol>
 *
 * <h2>1.16.5 的两处 Fabric API 名字差</h2>
 * <ul>
 *   <li>命令回调在 1.16.5 上是 <b>{@code api.command.v1.CommandRegistrationCallback}</b>；
 *       {@code v2} 那个包是 1.19 才拆出来的。而且 v1 的回调签名是
 *       {@code (CommandDispatcher, boolean)} 两参（v2 是 {@code (dispatcher, registryAccess, environment)} 三参）。</li>
 *   <li>{@code ServerTickEvents.END_SERVER_TICK} 与 {@code ServerLifecycleEvents} 两代同名同形，
 *       可以直接照抄。</li>
 * </ul>
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
        NumenConfig.load();

        // 外接大脑（MCP）：读配置，开着就立刻起服。
        // 本仓没有设置面板，改 host/port/开关要重进游戏（见 docs/PORTING.md）。
        Path mcpFile = FabricLoader.getInstance().getConfigDir()
                .resolve("numen").resolve("mcp_server.json");
        McpMode.instance().bootstrap(mcpFile, McpConfig.load(mcpFile));

        // 服务端刻：驱动所有同伴的大脑与长动作，顺带做一次外脑失联的边沿检测。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Companions.tick(server);
            McpMode.instance().tick();
        });

        // 服务器起来的时候把配置里的缺口说清楚——比玩家发了消息才拿到一个 401 友好得多。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            McpActuator.bind(server);
            String missing = NumenConfig.get().missingReason();
            if (missing == null) {
                NumenCore.info("端点已就绪：{} / {}", NumenConfig.get().provider, NumenConfig.get().model);
            } else {
                NumenCore.warn("内置大脑还不能干活：{}", missing);
            }
            if (McpMode.instance().enabled()) {
                NumenCore.info("外接大脑已开启，端点 {}，令牌 {}", McpMode.instance().endpoint(),
                        McpMode.instance().maskedToken());
                NumenCore.info("（外脑接管期间内置大脑不干活，上面那条「端点」缺口不影响外脑）");
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

        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> NumenCommand.register(dispatcher));

        // G 面板的服务端一侧。客户端那一半在 core/client 下，由 fabric.mod.json 的
        // client 入口点负责；这里注册的只是「收请求、干活、回快照」。
        // 专用服务端上也会注册——装了模组的客户端连过来才有得聊。
        NumenPanelServer.register();

        NumenCore.info("Numen 1.16.5 移植版已加载");
    }
}
