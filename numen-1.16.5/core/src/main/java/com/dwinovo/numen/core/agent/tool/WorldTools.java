package com.dwinovo.numen.core.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.event.NumenEvents;
import com.dwinovo.numen.core.sdk.Dispatcher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 世界工具表：模型 / 外接大脑（MCP）能对这个世界做的每一件事。
 *
 * <h2>这一层只剩两件事</h2>
 * <ol>
 *   <li><b>名字与参数</b>——给模型看的 {@code schema}。这是本仓的 <b>MCP 契约</b>（工具名与参数形状），
 *       不随实现改：外接大脑与内置大脑读的是同一张表（{@link ToolRunner#specs()}）。</li>
 *   <li><b>派发</b>——把一次调用翻成一次上游 {@code numen.*} 的 {@code @Fn} 调用，经
 *       {@link Dispatcher#serve} 读参数、按端执行、按种类交回（当场值 / 等一会儿 / 占身体的活）。</li>
 * </ol>
 *
 * <h2>动作本体全在上游（刀 5 收尾）</h2>
 * 本层<b>不自己动手</b>：挖矿是 {@code numen.work.dig}、右键是 {@code numen.use.block}、穿脱是
 * {@code numen.gear.wear}、走是 {@code numen.route.plan + numen.move.go}…… 自写的 {@code Acts} /
 * {@code CompanionNav} / 那套 Job 机制<b>已退役</b>——权限征询、跨刻长动作、任务审计都由上游的任务框架
 * （{@code TaskRecord} / {@code ServerCall.authorize} / {@code task_finished}）承担。
 *
 * <h2>占身体的活怎么回到这一次调用上</h2>
 * {@link Dispatcher#serve} 对返回 {@code Job} 的函数当场只回「受理了、编号 tN」，收尾走一条
 * {@code task_finished} 事件。本层挂一个 {@link NumenEvents#watch} 看着那条事件：编号对上了就把这次工具调用
 * 结掉——于是外接大脑看到的仍是「一件活做完才回执」，与旧机制的行为一致。
 */
public final class WorldTools {

    private WorldTools() {}

    // =====================================================================
    //  工具表（MCP 契约：名字 + 说明 + 参数 schema）
    // =====================================================================

    private record Spec(String name, String description, Map<String, Object> parameterSchema)
            implements IToolSpec {}

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> xyz(String dx, String dy, String dz) {
        return obj("x", obj("type", "integer", "description", dx),
                "y", obj("type", "integer", "description", dy),
                "z", obj("type", "integer", "description", dz));
    }

    private static final List<IToolSpec> SPECS = List.of(
            new Spec("say",
                    "对主人说一句话。只在需要让主人知道什么、或需要回应他时用；"
                            + "纯粹的中间步骤不要说话。",
                    obj("type", "object",
                            "properties", obj("text", obj("type", "string", "description", "要说的话")),
                            "required", List.of("text"))),

            new Spec("get_status",
                    "看自己此刻的状态：坐标、所在维度、血量、饱食度、手里拿着什么。"
                            + "要移动或动手之前，先看这个确认自己在哪。",
                    obj("type", "object", "properties", obj(), "required", List.of())),

            new Spec("inspect_block",
                    "看某一格方块是什么。可以远程调用（不受手臂长度限制），适合逐格探地形剖面。"
                            + "找一列的地面高度就用它从高往低扫，遇到第一个非空气就是地表。",
                    obj("type", "object", "properties", xyz("方块 X", "方块 Y", "方块 Z"),
                            "required", List.of("x", "y", "z"))),

            new Spec("scan_blocks",
                    "看脚下这一圈的地形：一张自上而下的地面图（每行一个字符串，北在上、东在右），"
                            + "@ 是自己，每格写着能不能走上去。找路、看建筑轮廓、判断哪儿能站首选它。"
                            + "要看远处某种方块（如铁矿）用 lua 调 numen.scan.blocks(\"iron_ore\")。",
                    obj("type", "object",
                            "properties", obj(
                                    "radius", obj("type", "integer",
                                            "description", "看多大半圈，3~16，默认 8")),
                            "required", List.of())),

            new Spec("goto",
                    "走到某个坐标附近。会自己找路：绕开障碍、上下台阶、必要时跳过缺口。"
                            + "走到距离目标 1.5 格以内算成功；走不到会报失败并说明原因（没路、卡住、太远等）"
                            + "以及当时停在哪。目标不可达时不要反复重试，换个坐标或先挖开挡路的东西。",
                    obj("type", "object", "properties", xyz("目标 X", "目标 Y", "目标 Z"),
                            "required", List.of("x", "y", "z"))),

            new Spec("mine",
                    "挖掉一格方块。走的是原版「玩家挖方块」那条路，所以掉落、统计都照玩家来。"
                            + "够不着的格子会报失败——先 goto 过去。",
                    obj("type", "object", "properties", xyz("方块 X", "方块 Y", "方块 Z"),
                            "required", List.of("x", "y", "z"))),

            new Spec("use_block",
                    "右键用一格方块：开箱子、开门、按按钮、拉杆、用工作台、翻活板门。"
                            + "走的是原版「玩家右键」那条路，服务端自己的规矩（够不够得着、冒险模式、"
                            + "别的模组取消了事件）都在里面生效。够不着或看不见那一面会报失败并说明原因。"
                            + "⚠️ 只能点**已经存在**的方块；要往世界里放东西得先手里拿着能放的。",
                    obj("type", "object", "properties", xyz("方块 X", "方块 Y", "方块 Z"),
                            "required", List.of("x", "y", "z"))),

            new Spec("inventory",
                    "看自己身上有什么：主手、副手、盔甲四件、以及主背包 36 格（空的格子不列）。"
                            + "找东西、确认工具/食物/方块在不在，先用它。",
                    obj("type", "object", "properties", obj(), "required", List.of())),

            new Spec("equip",
                    "把背包里的一件穿到身上：按它自己的槽放进盔甲栏或副手，原来穿着的那件换回背包。"
                            + "物品用 id（minecraft:iron_helmet 或 iron_helmet）或显示名里的一段来指定。",
                    obj("type", "object",
                            "properties", obj(
                                    "item", obj("type", "string", "description", "物品 id 或名字里的一段")),
                            "required", List.of("item"))),

            new Spec("eat",
                    "吃掉背包里的一样食物，回饱食度与血量。物品用 id 或显示名里的一段来指定。",
                    obj("type", "object",
                            "properties", obj(
                                    "item", obj("type", "string", "description", "物品 id 或名字里的一段")),
                            "required", List.of("item"))),

            new Spec("drop",
                    "丢掉背包里的一些东西（掉在脚下）。count 省缺或 ≤0 表示全丢。"
                            + "物品用 id 或显示名里的一段来指定。",
                    obj("type", "object",
                            "properties", obj(
                                    "item", obj("type", "string", "description", "物品 id 或名字里的一段"),
                                    "count", obj("type", "integer", "description", "丢几个；省缺=全丢")),
                            "required", List.of("item"))));

    public static List<IToolSpec> specs() {
        return SPECS;
    }

    /** 按名字找一个工具规格，大小写宽容（{@code Get_Status} 也认）；找不到返回 null。 */
    public static IToolSpec resolve(String name) {
        if (name == null) {
            return null;
        }
        for (IToolSpec spec : SPECS) {
            if (spec.name().equalsIgnoreCase(name)) {
                return spec;
            }
        }
        return null;
    }

    /** 把本表逐个包成引擎的工具（名字/说明/schema 照旧，执行由 {@link ToolRunner} 认领后走 {@link #run}）。 */
    public static List<NumenTool> asNumenTools() {
        List<NumenTool> out = new ArrayList<>(SPECS.size());
        for (IToolSpec spec : SPECS) {
            out.add(new WorldTool(spec));
        }
        return out;
    }

    // =====================================================================
    //  工具 → 上游 @Fn
    // =====================================================================

    /** 直接对应的：工具名 → {@code numen.*} 函数全名。参数形状差异在 {@link #toFnArgs} 里抹平。 */
    private static final Map<String, String> FN = Map.ofEntries(
            Map.entry("get_status", "numen.status.self"),
            Map.entry("inspect_block", "numen.scan.block"),
            Map.entry("scan_blocks", "numen.scan.map"),
            Map.entry("use_block", "numen.use.block"),
            Map.entry("inventory", "numen.inv.items"),
            Map.entry("equip", "numen.gear.wear"),
            Map.entry("eat", "numen.inv.eat"),
            Map.entry("drop", "numen.inv.drop"),
            Map.entry("mine", "numen.work.dig"));

    /**
     * 一次世界工具调用。{@code done} 恰好交回一次（服务端主线程），内容是给模型的
     * {@code {"success":…,"message":…}} 信封。
     *
     * <p>{@code say} 与 {@code goto} 没有一一对应的单个 @Fn，单独走（说话是大脑的出口；
     * 走一处是「先规划再走」两次调用）。
     */
    public static void run(NumenPlayer body, CompanionBrain brain, LlmToolCall call, Consumer<String> done) {
        JsonObject in = args(call);
        try {
            switch (call.name()) {
                case "say" -> {
                    brain.speak(text(in, "text"));
                    done.accept(ToolOutcome.success("说完了"));
                }
                case "goto" -> walk(body, call, in, done);
                default -> {
                    String fn = FN.get(call.name());
                    if (fn == null) {
                        done.accept(ToolOutcome.failure("没有这个工具：" + call.name()));
                        return;
                    }
                    Dispatcher.serve(fn, toFnArgs(call.name(), in), body, call.id(), r -> deliver(r, done));
                }
            }
        } catch (RuntimeException ex) {
            done.accept(ToolOutcome.failure("工具 " + call.name() + " 出错：" + ex));
        }
    }

    /**
     * 把工具的参数翻成 @Fn 参数 record 认的名字。
     *
     * <p>差异只有两种：三个数拼成的坐标要包成 {@code {cell = {x,y,z}}}（上游的参数名就叫 {@code cell}）；
     * 挖的那一族收的是一张 {@code {blocks = {…}}} 的表。别的同名同形，原样带过去。
     */
    private static JsonObject toFnArgs(String tool, JsonObject in) {
        JsonObject out = new JsonObject();
        switch (tool) {
            case "inspect_block", "use_block" -> out.add("cell", cell(in));
            case "mine" -> {
                JsonArray blocks = new JsonArray();
                blocks.add(cell(in));
                out.add("blocks", blocks);
            }
            case "scan_blocks" -> {
                if (in.has("radius")) {
                    out.add("radius", in.get("radius"));
                }
            }
            case "equip", "eat" -> {
                if (in.has("item")) {
                    out.add("item", in.get("item"));
                }
            }
            case "drop" -> {
                if (in.has("item")) {
                    out.add("item", in.get("item"));
                }
                if (in.has("count")) {
                    out.add("count", in.get("count"));
                }
            }
            default -> {
                // get_status / inventory：不收参数
            }
        }
        return out;
    }

    private static JsonObject cell(JsonObject in) {
        JsonObject p = new JsonObject();
        p.addProperty("x", intArg(in, "x"));
        p.addProperty("y", intArg(in, "y"));
        p.addProperty("z", intArg(in, "z"));
        return p;
    }

    /** 走到一处：先 {@code numen.route.plan} 规划，再 {@code numen.move.go} 照计划走（同一个调用编号，计划才认得出）。 */
    private static void walk(NumenPlayer body, LlmToolCall call, JsonObject in, Consumer<String> done) {
        JsonObject directions = new JsonObject();
        directions.add("to", cell(in));
        Dispatcher.serve("numen.route.plan", directions, body, call.id(), r1 -> {
            JsonObject planned = parse(r1);
            if (planned == null || !ok(planned)) {
                done.accept(envelope(planned));
                return;
            }
            JsonElement value = planned.get("value");
            if (value == null || !value.isJsonObject()) {
                done.accept(ToolOutcome.failure("规划没有给出计划"));
                return;
            }
            JsonObject plan = value.getAsJsonObject();
            if (plan.has("ok") && !plan.get("ok").getAsBoolean()) {
                String why = plan.has("why") ? plan.get("why").getAsString() : "走不过去";
                done.accept(ToolOutcome.failure("走不到 " + text(in, "x") + "," + text(in, "y") + ","
                        + text(in, "z") + "：" + why));
                return;
            }
            if (!plan.has("id")) {
                done.accept(ToolOutcome.failure("规划没有给出计划编号"));
                return;
            }
            JsonObject ref = new JsonObject();
            ref.addProperty("id", plan.get("id").getAsString());
            JsonObject go = new JsonObject();
            go.add("plan", ref);
            Dispatcher.serve("numen.move.go", go, body, call.id(), r2 -> deliver(r2, done));
        });
    }

    // =====================================================================
    //  交回：当场值 / 受理的活
    // =====================================================================

    /** 还等着收尾的活：任务编号 → 这次调用的回信口。 */
    private static final Map<String, Consumer<String>> WAITING = new ConcurrentHashMap<>();

    static {
        // 看着每一条服务端事件：是我们在等的那件活的收尾，就把它认下来（不再送给大脑），结掉这次调用。
        NumenEvents.watch((companion, entry) -> {
            ScriptCall.Finish finished = NumenEvents.finishOf(entry);
            if (finished == null) {
                return false;
            }
            Consumer<String> done = WAITING.remove(finished.task());
            if (done == null) {
                return false;
            }
            done.accept(envelope(finished.result()));
            return true;
        });
    }

    /** 一条 API 回信：受理了后台活就登记等收尾，否则当场结掉。 */
    private static void deliver(String replyJson, Consumer<String> done) {
        JsonObject reply = parse(replyJson);
        if (reply == null) {
            done.accept(ToolOutcome.failure("工具回了一条看不懂的结果"));
            return;
        }
        if (reply.has("job")) {
            WAITING.put(reply.get("job").getAsString(), done);
            return;
        }
        done.accept(envelope(reply));
    }

    /** API 回信 → 给模型的信封（{@code {success, message, data}}）。 */
    private static String envelope(JsonObject reply) {
        if (reply == null) {
            return ToolOutcome.failure("工具没有结果");
        }
        if (ok(reply)) {
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            JsonElement value = reply.get("value");
            out.addProperty("message", value == null ? "做完了" : value.toString());
            if (value != null) {
                out.add("data", value);
            }
            return out.toString();
        }
        JsonObject error = reply.has("error") ? reply.getAsJsonObject("error") : null;
        String message = error != null && error.has("message") ? error.get("message").getAsString() : "失败";
        String hint = error != null && error.has("hint") ? error.get("hint").getAsString() : null;
        return ToolOutcome.failure(hint == null ? message : message + "（" + hint + "）");
    }

    private static boolean ok(JsonObject reply) {
        return reply != null && reply.has("ok") && reply.get("ok").getAsBoolean();
    }

    private static JsonObject parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new JsonParser().parse(json).getAsJsonObject();
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    // =====================================================================
    //  小工具
    // =====================================================================

    /**
     * 解析工具参数。
     *
     * <p>用 {@code new JsonParser().parse(...)} 而不是 {@code JsonParser.parseString(...)}：
     * 后者是 Gson 2.8.6 才加的，而 Minecraft 1.16.5 的类路径上带的是 Gson 2.8.0。
     */
    @SuppressWarnings("deprecation")
    private static JsonObject args(LlmToolCall call) {
        try {
            String raw = call.arguments();
            if (raw == null || raw.isBlank()) {
                return new JsonObject();
            }
            return new JsonParser().parse(raw).getAsJsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    private static int intArg(JsonObject args, String name) {
        try {
            return args.has(name) ? args.get(name).getAsInt() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String text(JsonObject args, String name) {
        try {
            return args.has(name) ? args.get(name).getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
