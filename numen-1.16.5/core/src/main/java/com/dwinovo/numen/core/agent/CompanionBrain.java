package com.dwinovo.numen.core.agent;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.memory.Compactor;
import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.agent.request.MemoryPreamble;
import com.dwinovo.numen.core.agent.tool.ToolRunner;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.HostPort;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.mcp.McpMode;
import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.function.Consumer;

/**
 * 一只同伴的大脑：把 {@code agent} 模块的循环内核接到 Minecraft 这一侧。
 *
 * <p>上游这一层是 {@code api/agent/CompanionBrain.java} 那一族（在 1.20.1 上分了十几个文件）。
 * 这里把四个端口做成内部类，一个文件装下，读起来是完整的一条链：
 *
 * <pre>
 *   主人的话 ──push──▶ EventQueue ──▶ AgentLoop ──turnRequest──▶ ModelPort ──▶ NumenLlmClient
 *                                       │                                        │
 *                                       └──run──▶ ToolPort ──▶ WorldTools ──▶ 身体
 * </pre>
 *
 * <h2>线程</h2>
 * 引擎层是纯 JVM、单线程假设：{@code AgentLoop} 的所有状态只在一个线程上读写。
 * 在 Minecraft 里那个线程就是<b>服务端主线程</b>。所以 {@link ModelPortImpl#call} 里
 * HTTP 的回调必须用 {@code server.execute(...)} 切回来——引擎的端口契约要求「回调在内核的线程上执行」，
 * 漏了这一步会出现「偶发、且只在网络快的时候才复现」的诡异 bug。
 *
 * <h2>上下文整理</h2>
 * {@link Compactor}（随 {@code agent} 引擎逐字移植，此处接线）：自动压缩按上一次请求的
 * 实测体量（服务端不报用量就退本地估算）对照绑定模型的窗口闸门；近段消息逐字跨过压缩边界，
 * 上一份摘要作为累积账单独给更新。对话同时落一份 append-only 的 {@link ConvoLog}
 * （{@code config/numen/companions/<uuid>/chat.jsonl}）：压缩/清空只落边界，记录一个字不删。
 * 这份日志也是将来"跨重启恢复历史"的底子——恢复那一刀还没切（收件箱同样不落盘，见
 * {@link #MEMORY_JOURNAL}），先记着。
 */
public final class CompanionBrain {

    private final NumenPlayer body;
    private final MinecraftServer server;

    private final ConvoLog log;
    private final ConvoState transcript;
    private final EventQueue inbox = new EventQueue(MEMORY_JOURNAL);
    private final Compactor compactor;
    private final MemoryPreamble memory;
    private final AgentLoop loop;

    /** 此刻身体在干的活，给人看的一句话；没有具体动作时 null。 */
    private volatile String activity;

    /** 上一刻驾驶席在不在外接大脑手里——只用来找「翻转成外接」的那一下。 */
    private boolean wasDriving;

    public CompanionBrain(NumenPlayer body) {
        this.body = body;
        this.server = body.server;
        // 对话日志：append-only，压缩/清空只落边界（CompanionHome 那一族的目录约定，服务端落在 config 下）。
        this.log = ConvoLog.atFile(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("numen").resolve("companions")
                .resolve(body.getUUID().toString()).resolve("chat.jsonl"));
        this.transcript = new ConvoState(msg -> log.append(msg, null));
        this.compactor = new Compactor(name(), transcript, log, this::contextWindow);
        // 札记索引的贴不贴策略在这它那（只在历史里那份没了或过时了时重贴，见 MemoryPreamble）；
        // 同一本 NoteBook 与 memory 工具共享（NoteBook.of 按同伴缓存）
        this.memory = new MemoryPreamble(NoteBook.of(body.getUUID()));
        this.loop = new AgentLoop(
                name(),
                new ModelPortImpl(),
                new ToolPortImpl(),
                transcript,
                inbox,
                compactor,
                new HostPortImpl());
        this.loop.subscribe(this::onLoopEvent);
        // 实测体量（ModelUsed）与历史边界（整理/清空）是压缩闸门的判据，见 Compactor#on
        this.loop.subscribe(compactor::on);
        // 压缩/清空把历史里那份 <memory> 索引吃掉了——下一次注入得重贴一份完整的
        this.loop.subscribe(memory::on);
        this.wasDriving = McpMode.instance().driving();
    }

    /**
     * 绑定模型的上下文窗口——自动压缩的闸门按它算。查无（自定义站点/未收录型号）吃
     * {@code ProviderRegistry.DEFAULT_CTX}，宁早压不炸窗。
     */
    private int contextWindow() {
        LlmEndpoint endpoint = NumenConfig.get().endpoint();
        return ProviderRegistry.contextWindow(endpoint.provider(), endpoint.model());
    }

    /** 不落盘的队列：这一版同伴的记忆不跨存档。 */
    private static final EventQueue.Journal MEMORY_JOURNAL = new EventQueue.Journal() {
        @Override
        public List<EventQueue.Entry> load() {
            return List.of();
        }

        @Override
        public void save(List<EventQueue.Entry> entries) {
            // 这一版不落盘。
        }
    };

    // ---- 对外 ----

    public String name() {
        return body.getGameProfile().getName();
    }

    public NumenPlayer body() {
        return body;
    }

    /** 主人在跟她说话。 */
    public void tell(String text) {
        inbox.push(EventTypes.QUERY, text, System.currentTimeMillis(), true);
        loop.pump();
    }

    /** 服务端每刻调一次。 */
    public void tick() {
        // 跨刻的长动作（走路、挖矿…）由 CompanionTickDispatcher 推进（见 NumenCoreFabric 的
        // START_SERVER_TICK）；这里只让引擎往前走一步。
        // 驾驶席翻转成外接的那一下作废在飞的回合：接管之后内脑的回复不该再派工具。
        // 交还不用做什么——停牌是现算的（见 HostPortImpl#externallyDriven），内核下一次推进自己看得见。
        boolean driving = McpMode.instance().driving();
        if (driving && !wasDriving) {
            loop.halt(HaltReason.EXTERNAL);
        }
        wasDriving = driving;
        loop.tick();
    }

    /** 停牌（遣散、死亡、主人叫停）。 */
    public void halt(String why) {
        loop.halt(HaltReason.OWNER_STOP, why);
    }

    /** 让同伴在聊天栏说一句话（工具 {@code say} 与善后播报都走这里）。 */
    public void say(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        body.server.getPlayerList().broadcastMessage(new TextComponent("<" + name() + "> " + text), ChatType.CHAT, Util.NIL_UUID);
    }

    // =====================================================================
    //  外接大脑（MCP）那一侧
    // =====================================================================

    /**
     * 外接大脑取件（{@code get_events} 的取货口）：{@code urgentOnly} 时只在队里有给它的急件才取，
     * 长轮询靠它省着等；到点了不管急不急有什么给什么。
     *
     * <p>渲染走引擎同一份 {@link EventQueue#render}——外脑看到的事件文本和内脑一字不差。
     * 控制条目（整理/清空）是对内脑说的，引擎自己会跳过它们留在队里等交还。
     *
     * <p><b>只在服务端主线程上调</b>（{@link com.dwinovo.numen.core.mcp.McpActuator} 负责切回来）。
     *
     * @return 取走的事件拼段；这次没取到返回 null（继续等，或如实说没有）
     */
    public String takeEventsForExternal(boolean urgentOnly) {
        // 急件只可能是文本：控制命令在队列的急件规则里就不急。
        if (urgentOnly && !inbox.hasUrgent()) {
            return null;
        }
        long now = System.currentTimeMillis();
        List<EventQueue.Entry> taken = inbox.takeText(now);
        if (taken.isEmpty()) {
            return null;
        }
        List<String> parts = EventQueue.render(taken, now);
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /** 急件叫醒挂点直通（{@code get_events} 长轮询停靠用）。服务端主线程调用。 */
    public void addUrgentListener(Runnable listener) {
        inbox.addUrgentListener(listener);
    }

    public void removeUrgentListener(Runnable listener) {
        inbox.removeUrgentListener(listener);
    }

    /**
     * 外接大脑派一次工具调用：交给她自己的工具口，与内脑同一个派发器
     * （{@code AgentLoop#runAside}），不进会话历史。
     *
     * @return 收下了；内脑正在干活、或已有一段外接调用在跑时为 false
     */
    public boolean runExternal(LlmToolCall call, Consumer<String> done) {
        return loop.runAside(call, done);
    }

    /**
     * 外接大脑替她说话（{@code say} 工具）。
     *
     * <p>与内脑开口同一个出口——上游在这里还会挂气泡与语音，本仓暂时只有聊天栏。
     */
    public void externalSay(String text) {
        say(text);
    }

    /** 此刻在干什么，给命令与界面读。 */
    public String activity() {
        return activity;
    }

    /** 端点齐不齐，给命令读（外接大脑开着时不看内置端点，见下）。 */
    public String unavailableReason() {
        // 外接大脑（MCP）开着时内置端点不是必需的：主人的 /numen tell 话进事件队列，
        // 由外脑经 get_events 取走、用 say 回话——这是外脑模式主人→同伴的唯一说话通道。
        // 用 enabled 不用 driving：只要开着就允许话进队列，外脑短暂安静也不该把话丢掉。
        if (McpMode.instance().enabled()) {
            return null;
        }
        return NumenConfig.get().missingReason();
    }

    private void onLoopEvent(LoopEvent event) {
        Constants.info("同伴 {} 事件：{}", name(), event);
    }

    // =====================================================================
    //  端口实现
    // =====================================================================

    /** 同伴那一侧的事实：时钟、主动性档位、驾驶席、身体在做什么。 */
    private final class HostPortImpl implements HostPort {

        @Override
        public long now() {
            // 墙上时钟。游戏刻在单机退出时是冻结的，拿它算「躺了多久」会以为什么都没老。
            return System.currentTimeMillis();
        }

        @Override
        public int initiativeLevel() {
            return NumenConfig.get().initiative;
        }

        @Override
        public boolean externallyDriven() {
            // 外接大脑（MCP）拨开时，驾驶席就不在内脑手里——内核据此现算停牌 EXTERNAL，
            // 于是一轮都不开；主人的话照样进收件箱，等外脑来取。
            return McpMode.instance().driving();
        }

        @Override
        public String injectionPreamble() {
            // <memory> 索引垫在每次注入的 user 消息最前：内容会变（她一记就变），放系统提示会打碎缓存前缀。
            // 没变就不重贴——历史里已躺着一份，再贴是白花的 token（策略见 MemoryPreamble）
            return memory.next();
        }

        @Override
        public boolean bodyTaskRunning() {
            return activity != null;
        }

        @Override
        public String activity() {
            return activity;
        }
    }

    /**
     * 模型那一侧。
     *
     * <p>上游的 {@code ModelPort} 承担了「组装请求 / 端点检查 / 发请求」三件事，
     * 重试、压缩、目标评估都从 {@code call} 一处发出。这里保留同样的形状；压缩那一支
     * 已接（{@link Compactor}，摘要请求也从这里发出）。
     */
    private final class ModelPortImpl implements ModelPort {

        @Override
        public String unavailable() {
            return NumenConfig.get().missingReason();
        }

        @Override
        public ModelRequest turnRequest() {
            // 系统提示只在会话内稳定的那几层（人设/操作核心/技能表/<api>索引/本能名册/札记规矩/说话规矩），
            // 组装见 NumenSystemPrompt——是字节级稳定的缓存前缀
            return new ModelRequest(
                    transcript.snapshot(),
                    ToolRunner.specs(),
                    NumenSystemPrompt.system(NumenConfig.get().persona, body.getUUID()));
        }

        @Override
        public void call(ModelRequest request, CancelToken cancel,
                         Consumer<Delta> onDelta, Consumer<ModelOutcome> onDone) {
            NumenLlmClient client = NumenLlmClient.forEndpoint(NumenConfig.get().endpoint());
            client.chatStreaming(request.messages(), request.tools(), request.systemPrompt(), cancel, null)
                    .whenComplete((result, error) -> {
                        // 回调必须切回服务端主线程：引擎的单线程约定见类注释。
                        server.execute(() -> {
                            if (cancel.isCancelled()) {
                                return;
                            }
                            if (error != null) {
                                onDone.accept(new ModelOutcome.Failed(describe(error)));
                                return;
                            }
                            AssistantTurn turn = result.turn();
                            if (turn.hasReasoning()) {
                                onDelta.accept(new Delta("", turn.reasoning()));
                            }
                            onDone.accept(new ModelOutcome.Answered(turn, result.usage()));
                        });
                    });
        }

        private String describe(Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            String message = cause.getMessage();
            return cause.getClass().getSimpleName() + (message == null ? "" : "：" + message);
        }
    }

    /**
     * 工具那一侧：按顺序执行一批调用，一个做完了才派下一个。
     *
     * <p>身体只有一个动作槽，所以不并行——这是引擎的 {@code SerialCalls} 契约。
     *
     * <p>刀 3 起走 {@link ToolRunner} 的统一派发：程序（{@code lua}）送进服务端程序链路，
     * 大脑工具（技能/计划/札记）在 {@code ToolRegistry} 里，世界工具仍走旧 Job 机制。
     */
    private final class ToolPortImpl implements ToolPort {

        @Override
        public void run(List<LlmToolCall> calls, Sink sink) {
            ToolRunner.run(body, CompanionBrain.this, calls, sink);
        }

        @Override
        public void arrived(EventQueue.Entry entry, boolean urgent) {
            // 她正跑着一段程序而这是急件：让程序停在调用之间（上游 CompanionToolPort 同一口径）。
            // 不着急的照样排队——程序里的调用不等它。
            if (urgent) {
                String programId = ToolRunner.runningProgram(body.getUUID());
                if (programId != null) {
                    com.dwinovo.numen.core.program.ServerPrograms.interrupt(
                            body.getUUID(), programId, entry.type());
                }
            }
        }

        @Override
        public List<String> cancel(boolean stopBody) {
            if (stopBody) {
                body.controls().releaseAll();
                activity = null;
            }
            // 在跑的程序同一下叫停（它的回执还会到，晚到的按 AfterCut 处理——本仓先只叫停）。
            String programId = ToolRunner.runningProgram(body.getUUID());
            if (programId != null) {
                com.dwinovo.numen.core.program.ServerPrograms.cutOff(
                        body.getUUID(), programId, stopBody);
            }
            return List.of();
        }
    }

    /** 给工具用的：报告/清除「此刻在干什么」。 */
    void setActivity(String text) {
        this.activity = text;
    }

    /** 给工具用的：同伴说一句话（与 {@link #say} 同一出口，便于将来改成气泡/语音）。 */
    public void speak(String text) {
        say(text);
    }

    /** 给工具用的：这条消息也要进聊天栏（主人能看到工具结果）。 */
    void notifyOwner(String text) {
        body.server.getPlayerList().broadcastMessage(new TextComponent("[numen] " + text), ChatType.SYSTEM, Util.NIL_UUID);
    }

    /** 给工具用的：一句话描述身体现在在哪。 */
    String where() {
        var pos = body.blockPosition();
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
