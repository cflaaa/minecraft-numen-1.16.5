package com.dwinovo.numen.core.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.tool.ScriptTool;
import com.dwinovo.numen.agent.tool.MemoryTool;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.SkillTool;
import com.dwinovo.numen.agent.tool.TodoTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.agent.NumenTools;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.program.CallObserver;
import com.dwinovo.numen.core.program.LoopbackClient;
import com.dwinovo.numen.core.program.RunResult;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具派的统一入口（刀 3）。上游这份接线在 {@code CommonClass.registerTools} +
 * {@code CompanionToolPort}；本仓大脑在服务端，两者合成的形状就是这里：
 *
 * <ul>
 *   <li><b>登记</b>：{@link #registerAll()} 在模组 init 时调一次——四个大脑工具
 *       （跑程序的 {@code lua}、技能、计划、札记）加本仓的世界工具（旧 {@link NumenTools}
 *       的 Spec 逐个包成 {@link WorldTool} 进同一张表，实现不动）；</li>
 *   <li><b>派发</b>：{@link #run} 是 {@code ToolPort} 的唯一实现路径（内置大脑的循环与
 *       外接大脑的 {@code runAside} 都经它）。一次调用三选一：跑程序（ScriptTool 的口径，
 *       走本仓服务端程序链路）、大脑工具（{@link ToolRegistry#invoke}）、世界工具
 *       （旧 Job 机制，逐刻推进与征询停车照旧）；</li>
 *   <li><b>清单</b>：{@link #specs()} 就是 {@link ToolRegistry} 的全表——MCP 的
 *       {@code tools/list} 与内置大脑的系统提示都读它，新增工具只登记一次。</li>
 * </ul>
 *
 * <p><b>世界工具为什么还走旧机制</b>：它们背后是本仓自写的 {@code Acts}（刀 4 逐族换成上游
 * 44 个 @Fn 函数），但<b>派发已经统一</b>——刀 4 到位后把这批 {@link WorldTool} 换成 @Fn 驱动的
 * 实现即可，入口、清单、MCP 面一行都不用改。
 */
public final class ToolRunner {

    private ToolRunner() {}

    // ---- 服务器引用（给按 UUID 找身体用；SERVER_STARTED/STOPPED 时绑/解） ----

    private static volatile MinecraftServer server;

    public static void bind(MinecraftServer s) {
        server = s;
    }

    public static void unbind() {
        server = null;
        PROGRAMS.clear();
        loopServer = null;
        loopClient = null;
    }

    /** 此刻绑着的服务器（没进世界是 null）。按 UUID 找身体、问游戏天数用它。 */
    public static MinecraftServer current() {
        return server;
    }

    // ---- 登记 ----

    private static boolean registered = false;

    /** 模组 init 时调一次（上游 {@code CommonClass.registerTools} 的位置与口径；重入直接返回）。 */
    public static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;
        ToolRegistry.register(new ScriptTool());
        ToolRegistry.register(new SkillTool());
        ToolRegistry.register(new TodoTool());
        ToolRegistry.register(new MemoryTool());
        int brains = ToolRegistry.size();
        // 本仓的世界工具：旧 Spec 逐个包成 NumenTool（名字/说明/参数schema 照旧，执行走旧 Job）
        for (IToolSpec spec : NumenTools.specs()) {
            ToolRegistry.register(new WorldTool(spec));
        }
        Constants.info("登记 {} 个工具：{} 个大脑工具 + {} 个世界工具",
                ToolRegistry.size(), brains, ToolRegistry.size() - brains);
    }

    /** 工具清单（MCP tools/list 与内置大脑的系统提示都读它）。 */
    public static List<IToolSpec> specs() {
        List<IToolSpec> out = new ArrayList<>();
        for (NumenTool tool : ToolRegistry.all()) {
            out.add(tool);
        }
        return out;
    }

    /** 有没有这个工具（大小写宽容见 {@link ToolRegistry#resolve}）。 */
    public static boolean has(String name) {
        return ToolRegistry.resolve(name) != null;
    }

    /** 跑程序那个工具的名字（随脚本语言，眼下是 {@code lua}）。 */
    public static String scriptToolName() {
        return ScriptEngine.IN_USE.toolName();
    }

    // ---- 派发 ----

    /**
     * 一批调用按顺序执行（引擎一次只给一个；这里仍按表走完，保持 {@link ToolPort#run} 的契约）。
     *
     * <p>派发分流照上游 {@code CompanionToolPort}：程序经服务端程序链路，别的工具经
     * {@link ToolRegistry}；本仓多一类——世界工具仍在旧表里，由 {@link WorldTool} 认领。
     */
    public static void run(NumenPlayer body, CompanionBrain brain, List<LlmToolCall> calls, ToolPort.Sink sink) {
        for (LlmToolCall call : calls) {
            sink.started(call);
            String name = call.name();
            try {
                if (scriptToolName().equals(name)) {
                    // 程序只由派发器跑（ScriptTool.invoke 会抛），代码从参数里取
                    String code = ScriptTool.code(call.arguments());
                    runProgram(body, "tool-" + call.id(), code,
                            result -> sink.finished(call, receiptOf(result)));
                    continue;
                }
                NumenTool tool = ToolRegistry.resolve(name);
                if (tool == null) {
                    sink.finished(call, ToolOutcome.failure("unknown tool: " + name));
                    continue;
                }
                // 带规范名（tool.name()）而不是 LLM 写的那个：大小写宽容只在 resolve 这一步
                tool.invoke(new ToolCall(call.id(), tool.name(), call.arguments(),
                        body::getUUID, result -> sink.finished(call, result)));
            } catch (IllegalArgumentException invalid) {
                sink.finished(call, ToolOutcome.failure("invalid arguments: " + invalid.getMessage()));
            } catch (RuntimeException ex) {
                Constants.error("工具 {} 抛了异常（id={}）", name, call.id(), ex);
                sink.finished(call, ToolOutcome.failure(ex.getMessage()));
            }
        }
    }

    /**
     * 世界工具的一次调用 → 旧 Job 机制（{@link WorldTool#invoke} 调这里）。
     *
     * <p>逐刻推进、权限征询在调用之间停车、跨刻长动作——全都照旧；换了的只是「谁调它」。
     */
    static void runWorldTool(ToolCall call) {
        MinecraftServer s = server;
        NumenPlayer body = s == null ? null : Companions.findByUuid(s, call.ctx().entityUuid());
        if (body == null) {
            call.complete(ToolOutcome.failure("现在不在世界里，或者没有这只同伴"));
            return;
        }
        CompanionBrain brain = Companions.brain(body);
        if (brain == null) {
            call.complete(ToolOutcome.failure("这只同伴没有登记的大脑"));
            return;
        }
        NumenTools.runSingle(body, brain,
                new LlmToolCall(call.id(), call.toolName(), call.rawArgs()), call::complete);
    }

    // ---- 程序 ----

    /** 每个服务器一个扮主人客户端的 {@link LoopbackClient}（世界作废时清掉，见 {@link #unbind}）。 */
    private static volatile MinecraftServer loopServer;
    private static volatile LoopbackClient loopClient;

    /** 这只同伴此刻在跑的程序编号（急件叫停用）；没有在跑的是 null。 */
    private static final Map<UUID, String> PROGRAMS = new ConcurrentHashMap<>();

    /**
     * 送一段程序去跑。回执经 {@code done} 恰好交回一次（服务端主线程）。
     *
     * <p>ScriptTool 与 {@code /numen program} 开发入口共用这一条路：{@link LoopbackClient} 用的
     * 是产品里的同一批部件（分片、编解码、{@code ServerPrograms}、主线程车道），只是不出进程。
     */
    public static void runProgram(NumenPlayer body, String programId, String code,
                                  java.util.function.Consumer<RunResult> done) {
        MinecraftServer s = server;
        if (s == null || body == null) {
            done.accept(RunResult.refused("现在不在世界里，或者没有这只同伴"));
            return;
        }
        PROGRAMS.put(body.getUUID(), programId);
        loopback(s).run(body.getUUID(), programId, code, CallObserver.NONE, result -> {
            PROGRAMS.remove(body.getUUID(), programId);
            done.accept(result);
        });
    }

    /** 这只同伴此刻在跑的程序编号；没有是 null（{@code arrived} 叫停用）。 */
    public static String runningProgram(UUID companion) {
        return PROGRAMS.get(companion);
    }

    private static LoopbackClient loopback(MinecraftServer s) {
        LoopbackClient client = loopClient;
        if (client == null || loopServer != s) {
            synchronized (ToolRunner.class) {
                client = loopClient;
                if (client == null || loopServer != s) {
                    loopServer = s;
                    loopClient = client = new LoopbackClient(
                            uuid -> Companions.findByUuid(s, uuid),
                            com.dwinovo.numen.core.script.Modules::of);
                }
            }
        }
        return client;
    }

    /** 程序结局 → 交给模型的那句话（回执正文；不是 {@code Ended} 的（缺模块之类）走 JSON）。 */
    private static String receiptOf(RunResult result) {
        return result instanceof RunResult.Ended ended ? ended.outcome().receipt() : result.toJson();
    }
}
