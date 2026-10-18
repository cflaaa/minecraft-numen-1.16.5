package com.dwinovo.numen.core.mcp;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.agent.tool.WorldTools;
import com.dwinovo.numen.core.command.NumenCommand;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 从「外面的大脑」驱动同伴身体的那扇门——上游 {@code api/NumenActuator.java} 的移植版。
 *
 * <p>上游那扇门开在<b>客户端</b>（同伴活在主人的集成服务端里，所以一切都经
 * {@code Minecraft.getInstance().execute(...)} 切到客户端主线程）。本仓的同伴活在
 * <b>服务端</b>（单人的集成服务端 / 专用服务端都一样），所以这里把同样的形状开在服务端：
 * 每个方法都经 {@link MinecraftServer#execute} 切到<b>服务端主线程</b>，
 * 结果用 {@link CompletableFuture} 带回去。这条切换是硬要求，不是优化——
 * 引擎层的 {@code AgentLoop} 是纯 JVM、单线程假设，它的状态只允许在服务端主线程上读写。
 *
 * <h2>两道闸，不需要「取得控制权」这一步</h2>
 * <ul>
 *   <li><b>大脑层</b>——{@link McpMode#driving()} 为真时内置大脑一轮都不开
 *       （内核每步现问 {@code HostPort.externallyDriven()}，在别人手里时停牌 {@code EXTERNAL}）。
 *       主人在游戏里说的话照样进收件箱，由外脑经 {@link #takeEvents} 取走、用 {@link #say} 回话；</li>
 *   <li><b>身体层</b>——一具身体同时只有一件长动作：{@link WorldTools} 背后的上游任务槽
 *       与内核的 {@code runAside} 都会拒绝第二件活。</li>
 * </ul>
 *
 * <h2>无会话副作用</h2>
 * 一次无头的 {@link #invoke} <b>不碰</b>同伴的会话历史。结果只回给调用方，上下文归外脑自己管。
 */
public final class McpActuator {

    private McpActuator() {}

    /** 无头调用用的合成工具调用 id，与模型自己发的 id 错开。 */
    private static final AtomicLong SEQ = new AtomicLong();
    private static final String TOOL_PREFIX = "mcp-";

    /** 当前服务端；不在世界里时为 null。 */
    private static volatile MinecraftServer server;

    /** 服务器起来了。 */
    public static void bind(MinecraftServer s) {
        server = s;
    }

    /** 服务器要关了。 */
    public static void unbind() {
        server = null;
    }

    /** 此刻在不在世界里——create/invoke 都要有世界才有意义。 */
    public static boolean inWorld() {
        return server != null;
    }

    /** 主人的一只在线同伴：稳定的 id + 显示名。 */
    public record Companion(UUID uuid, String name) {}

    // =====================================================================
    //  名册
    // =====================================================================

    public static CompletableFuture<List<Companion>> companions() {
        return hop(List.of(), s -> {
            List<Companion> out = new ArrayList<>();
            for (NumenPlayer body : Companions.all(s)) {
                out.add(new Companion(body.getUUID(), body.getGameProfile().getName()));
            }
            return out;
        });
    }

    /**
     * 按名字召唤一只同伴。
     *
     * <p>上游走网络包（客户端面板的「+」），主人就是那个玩家。本仓的 MCP 服务端开在服务端上、
     * 未必有「发起召唤的玩家」，所以这里取<b>第一个在线的主人玩家</b>当归属者
     * （见 {@link Companions#firstOwner}）。没有玩家在线就召唤不了——同伴得有个主人。
     */
    public static CompletableFuture<Boolean> create(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || !NumenCommand.validName(n)) {
            return CompletableFuture.completedFuture(false);
        }
        return hop(false, s -> {
            ServerPlayer owner = Companions.firstOwner(s);
            // 没有玩家在场 → 召唤成"服务器同伴"（无主），不依赖玩家。
            if (owner == null) {
                return Companions.summonServer(s, n) != null;
            }
            return Companions.spawn(s, owner, n) != null;
        });
    }

    /** 永久遣散一只同伴（掉落背包，一去不回）。 */
    public static CompletableFuture<Boolean> delete(UUID companion) {
        if (companion == null) {
            return CompletableFuture.completedFuture(false);
        }
        return hop(false, s -> Companions.dismissByUuid(s, companion));
    }

    // =====================================================================
    //  事件线（一条线：事件出、说话回）
    // =====================================================================

    /**
     * 取走此刻攒着的事件（主人说的话是 {@code <query>}，世界里的事是 {@code <event>}）。
     *
     * <p>这是长轮询的<b>到点清扫</b>；停靠等待用 {@link #awaitUrgent}。
     * 取走即消费，两次调用之间什么都不丢（都在队列里攒着）。
     *
     * @return 渲染好的事件文本；这次没有就 null
     */
    public static CompletableFuture<String> takeEvents(UUID companion) {
        if (companion == null) {
            return CompletableFuture.completedFuture(null);
        }
        return hop(null, s -> {
            CompanionBrain brain = brainOf(companion);
            return brain == null ? null : brain.takeEventsForExternal(false);
        });
    }

    /**
     * 停靠式长轮询：{@code batch} 在<b>急件落地的那一刻</b>带着整批事件完成；
     * {@code cancel} 撤回等待者（幂等，完成之后调也安全）。
     *
     * <p>正确性不建立在「叫醒」上：漏掉的一次叫醒本该送的东西，由调用方的到点清扫
     * （{@link #takeEvents}）兜底。
     */
    public record EventWait(CompletableFuture<String> batch, Runnable cancel) {}

    public static EventWait awaitUrgent(UUID companion) {
        CompletableFuture<String> f = new CompletableFuture<>();
        MinecraftServer s = server;
        if (s == null || companion == null) {
            f.complete(null);
            return new EventWait(f, () -> { });
        }
        // 「查—登记」必须在同一次服务端主线程跳里做完，否则急件可能落在
        // 「队列是空的」和「等待者挂上了」之间，那一声就永远不响。
        java.util.concurrent.atomic.AtomicReference<Runnable> hook =
                new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cancel = () -> {
            try {
                s.execute(() -> {
                    Runnable h = hook.getAndSet(null);
                    CompanionBrain brain = brainOf(companion);
                    if (h != null && brain != null) {
                        brain.removeUrgentListener(h);
                    }
                });
            } catch (RuntimeException ignored) {
                // 服务器已经在关了，等待者随它去
            }
        };
        try {
            s.execute(() -> {
                CompanionBrain brain = brainOf(companion);
                if (brain == null) {
                    f.complete(null);
                    return;
                }
                String now = brain.takeEventsForExternal(true);
                if (now != null) {
                    f.complete(now);
                    return;
                }
                Runnable h = () -> {
                    String batch = brain.takeEventsForExternal(true);
                    if (batch != null) {   // 别的取件人抢先了就继续挂着，等下一声
                        Runnable self = hook.getAndSet(null);
                        if (self != null) {
                            brain.removeUrgentListener(self);
                        }
                        f.complete(batch);
                    }
                };
                hook.set(h);
                brain.addUrgentListener(h);
            });
        } catch (RuntimeException ex) {
            f.complete(null);
        }
        return new EventWait(f, cancel);
    }

    /**
     * 替同伴说话——和内置大脑开口同一个出口（聊天栏；气泡与语音是后续阶段）。
     *
     * <p>对话历史归调用方自己记：游戏侧完全无状态，说完即完。
     */
    public static CompletableFuture<Boolean> say(UUID companion, String text) {
        if (companion == null || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        return hop(false, s -> {
            CompanionBrain brain = brainOf(companion);
            if (brain == null) {
                return false;
            }
            brain.externalSay(text);
            return true;
        });
    }

    // =====================================================================
    //  工具
    // =====================================================================

    /**
     * 替 {@code companion} 跑一个工具——<b>和内置大脑用的是同一套工具</b>
     * （目录见 {@link WorldTools#specs()}），只是不过模型。
     *
     * <p>走的是同伴自己的工具口（{@code AgentLoop#runAside}）：一件长动作做完才回执，
     * 被急件停在调用之间，跑完把工具结果带回来。身体忙的时候收不下去，回一条带话术的失败。
     *
     * <p>不需要先取得控制权：内置大脑要么被「外接大脑」模式整体挂起，
     * 要么和这次调用一起受「一具身体一件活」约束。
     *
     * @param toolName 已注册的工具名（大小写宽容，见 {@link WorldTools#resolve}）
     * @param argsJson 工具参数，JSON 对象文本；null/空 = {@code {}}
     * @return 工具结果的 {@code ToolOutcome} JSON 文本；失败也是这个形状，不会是异常的 future
     */
    public static CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson) {
        if (companion == null || toolName == null || toolName.isBlank()) {
            return CompletableFuture.completedFuture(ToolOutcome.failure("companion 与 toolName 都是必填"));
        }
        if (!com.dwinovo.numen.core.agent.tool.ToolRunner.has(toolName)) {
            return CompletableFuture.completedFuture(ToolOutcome.failure("没有这个工具：" + toolName));
        }
        String args = (argsJson == null || argsJson.isBlank()) ? LlmToolCall.NO_ARGS : argsJson;
        CompletableFuture<String> f = new CompletableFuture<>();
        MinecraftServer s = server;
        if (s == null) {
            f.complete(ToolOutcome.failure("现在不在世界里——先让游戏进一个世界"));
            return f;
        }
        try {
            s.execute(() -> {
                try {
                    CompanionBrain brain = brainOf(companion);
                    if (brain == null) {
                        f.complete(ToolOutcome.failure("没有这只同伴（可能已被遣散）——先 list_companions 看看"));
                        return;
                    }
                    LlmToolCall call = new LlmToolCall(TOOL_PREFIX + SEQ.incrementAndGet(), toolName, args);
                    if (!brain.runExternal(call, f::complete)) {
                        f.complete(ToolOutcome.failure(
                                "这只同伴手上已经有活在跑（内置大脑正在动作，或另一次外接调用还没完）——等它收尾再派"));
                    }
                } catch (RuntimeException ex) {
                    f.complete(ToolOutcome.failure(ex.toString()));
                }
            });
        } catch (RuntimeException ex) {
            f.complete(ToolOutcome.failure(ex.toString()));
        }
        return f;
    }

    // =====================================================================
    //  内部
    // =====================================================================

    /**
     * 把一件活切到服务端主线程上做，结果带回来。
     *
     * <p><b>绝不悬着</b>：不在世界里、或者 {@code server.execute} 抛了，就当场用 {@code onFail}
     * 结掉 future。HTTP 那边是阻塞等的，一个永远不完成的 future 等于一次调用挂到超时。
     */
    private static <T> CompletableFuture<T> hop(T onFail, Function<MinecraftServer, T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        MinecraftServer s = server;
        if (s == null) {
            f.complete(onFail);
            return f;
        }
        try {
            s.execute(() -> {
                T value = onFail;
                try {
                    value = work.apply(s);
                } catch (RuntimeException ex) {
                    Constants.warn("[numen-mcp] 调用失败：{}", ex.toString());
                }
                f.complete(value);
            });
        } catch (RuntimeException ex) {
            f.complete(onFail);
        }
        return f;
    }

    /** 只准在服务端主线程上调。 */
    private static CompanionBrain brainOf(UUID companion) {
        MinecraftServer s = server;
        if (s == null || companion == null) {
            return null;
        }
        NumenPlayer body = Companions.findByUuid(s, companion);
        return body == null ? null : Companions.brain(body);
    }
}
