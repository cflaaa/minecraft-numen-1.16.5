package com.dwinovo.numen.core.agent;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.HostPort;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.MemoryPort;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.NumenCore;
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
 *                                       └──run──▶ ToolPort ──▶ NumenTools ──▶ 身体
 * </pre>
 *
 * <h2>线程</h2>
 * 引擎层是纯 JVM、单线程假设：{@code AgentLoop} 的所有状态只在一个线程上读写。
 * 在 Minecraft 里那个线程就是<b>服务端主线程</b>。所以 {@link ModelPortImpl#call} 里
 * HTTP 的回调必须用 {@code server.execute(...)} 切回来——引擎的端口契约要求「回调在内核的线程上执行」，
 * 漏了这一步会出现「偶发、且只在网络快的时候才复现」的诡异 bug。
 *
 * <h2>这一版少什么</h2>
 * 上游的 {@code MemoryPort} 会做自动压缩（上下文逼近窗口时把历史摘要掉），
 * 这里先返回「不用压缩」——短对话完全够用。见 docs/PORTING.md 的阶段表。
 */
public final class CompanionBrain {

    private final NumenPlayer body;
    private final MinecraftServer server;

    private final ConvoState transcript = new ConvoState();
    private final EventQueue inbox = new EventQueue(MEMORY_JOURNAL);
    private final AgentLoop loop;

    /** 此刻身体在干的活，给人看的一句话；没有具体动作时 null。 */
    private volatile String activity;

    /** 上一刻驾驶席在不在外接大脑手里——只用来找「翻转成外接」的那一下。 */
    private boolean wasDriving;

    public CompanionBrain(NumenPlayer body) {
        this.body = body;
        this.server = body.server;
        this.loop = new AgentLoop(
                name(),
                new ModelPortImpl(),
                new ToolPortImpl(),
                transcript,
                inbox,
                new MemoryPortImpl(),
                new HostPortImpl());
        this.loop.subscribe(this::onLoopEvent);
        this.wasDriving = McpMode.instance().driving();
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
        // 先推进跨刻的长动作（走路），再让引擎往前走一步——
        // 顺序反了的话，「动作刚做完」这件事要等下一刻才被引擎看见。
        NumenTools.tick(body);
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

    /** 端点齐不齐，给命令读。 */
    public String unavailableReason() {
        return NumenConfig.get().missingReason();
    }

    private void onLoopEvent(LoopEvent event) {
        NumenCore.info("同伴 {} 事件：{}", name(), event);
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
            return "";
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
     * 重试、压缩、目标评估都从 {@code call} 一处发出。这里保留同样的形状，
     * 只是压缩那一支还没接（{@link MemoryPortImpl} 恒报「不用压」）。
     */
    private final class ModelPortImpl implements ModelPort {

        @Override
        public String unavailable() {
            return NumenConfig.get().missingReason();
        }

        @Override
        public ModelRequest turnRequest() {
            return new ModelRequest(
                    transcript.snapshot(),
                    NumenTools.specs(),
                    NumenSystemPrompt.system(NumenConfig.get().persona));
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
     */
    private final class ToolPortImpl implements ToolPort {

        @Override
        public void run(List<LlmToolCall> calls, Sink sink) {
            NumenTools.run(body, CompanionBrain.this, calls, sink);
        }

        @Override
        public void arrived(EventQueue.Entry entry, boolean urgent) {
            // 这一版没有「在服务端跑着一段程序」的情形，插话直接排队即可。
        }

        @Override
        public List<String> cancel(boolean stopBody) {
            if (stopBody) {
                body.controls().releaseAll();
                activity = null;
            }
            return List.of();
        }
    }

    /**
     * 上下文整理：这一版只支持「清空」，不做自动压缩。
     *
     * <p>{@code compactionDue()} 恒 false，于是引擎永远不会调 {@code compaction()}——
     * 返回 null 是安全的（上游同样允许不压缩的实现）。
     */
    private final class MemoryPortImpl implements MemoryPort {

        @Override
        public boolean compactionDue() {
            return false;
        }

        @Override
        public Compaction compaction(boolean auto) {
            return null;
        }

        @Override
        public void clear() {
            transcript.addHalt("主人清空了上下文");
            transcript.resetTurnCount();
        }
    }

    /** 给工具用的：报告/清除「此刻在干什么」。 */
    void setActivity(String text) {
        this.activity = text;
    }

    /** 给工具用的：同伴说一句话（与 {@link #say} 同一出口，便于将来改成气泡/语音）。 */
    void speak(String text) {
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

    /** 未使用，保留给将来的 Compaction 实现做编译期占位。 */
    @SuppressWarnings("unused")
    private static Component text(String s) {
        return new TextComponent(s);
    }
}
