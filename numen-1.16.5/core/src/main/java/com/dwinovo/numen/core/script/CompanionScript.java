package com.dwinovo.numen.core.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.permission.Action;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.core.permission.Verdict;
import com.dwinovo.numen.core.task.act.Acts;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * 让模型**写一段 Lua 一次做完几十步**——上游的核心卖点。
 *
 * <p>引擎那一侧（Lua 5.2 沙箱、{@code ScriptCall}/{@code ScriptRun}/{@code ScriptCatalog}）
 * 本仓早就逐字搬过来了，缺的一直是**宿主这一侧**：登记有哪些函数、把 Lua 的调用读成动作、
 * 执行它、把结果交回去。这个类就是那一侧，一个文件装下三件事：
 *
 * <ol>
 *   <li><b>登记处</b>——{@link #register} 把「函数名 + 参数名 + 怎么执行」记进来；</li>
 *   <li><b>宿主</b>——实现 {@link ScriptCall.Host}（脚本要的目录、把调用读成 {@link Invocation}）；</li>
 *   <li><b>驱动循环</b>——{@code begin → Dispatch → result → … → Done}，照 {@link ScriptCall} 的约定走。</li>
 * </ol>
 *
 * <p>⚠️ <b>与上游的差别（有意）</b>：
 * <ul>
 *   <li>参数只走**选项表**（{@code numen.use.block{x=1,y=2,z=3}}），不支持按位置传对象。
 *       上游的 {@code ApiFunction}/{@code Binder} 那套位置绑定（364+199 行）还没搬。</li>
 *   <li>还没有 **JOB**（占身体的活）——所以脚本里不能调「走过去」这类跨刻动作，
 *       调了会当场失败并说明。等任务层接上再开。</li>
 *   <li>上游每个函数要写 {@code @Doc}/{@code @Example}/{@code @Note} 供 {@code help()} 查；
 *       这一版把说明直接写进 {@link #register} 的短描述里。</li>
 * </ul>
 *
 * <p>只在世界所在的线程上调用。
 */
public final class CompanionScript implements ScriptCall.Host {

    /** 引擎自己的命名空间：脚本里写 {@code numen.use.block}。 */
    public static final String NAMESPACE = "numen";

    /** 一个函数怎么执行：拿参数、交回一个值（字符串就是给脚本的消息）。 */
    @FunctionalInterface
    public interface Call {
        Object run(NumenPlayer body, JsonObject args) throws ApiError;
    }

    /** 登记的一行。 */
    public record Entry(Set<String> options, Call call) {}

    private static final Map<String, Map<String, Entry>> GROUPS = new TreeMap<>();
    private static ScriptCatalog catalog;


    // ==================== 登记处 ====================

    /**
     * 记一个函数。
     *
     * @param group   组名（{@code use}/{@code inv}/{@code work}…），脚本里写作 {@code numen.<group>.<name>}
     * @param options 参数名；脚本里写成最后那张表，键全是这些名字
     */
    public static void register(String group, String name, Set<String> options, Call call) {
        GROUPS.computeIfAbsent(group, g -> new TreeMap<>()).put(name, new Entry(options, call));
        catalog = null;   // 现算一次就够，登记之后作废
    }

    static Entry find(String group, String name) {
        Map<String, Entry> functions = GROUPS.get(group);
        return functions == null ? null : functions.get(name);
    }

    /** 脚本那一侧的目录：每个函数一个 {@code numen.<组>.<函数>}。 */
    private static synchronized ScriptCatalog buildCatalog() {
        if (catalog != null) {
            return catalog;
        }
        Map<String, Map<String, ScriptCatalog.Function>> groups = new TreeMap<>();
        GROUPS.forEach((group, functions) -> {
            Map<String, ScriptCatalog.Function> copy = new TreeMap<>();
            functions.forEach((name, entry) -> copy.put(name, new ScriptCatalog.Function(
                    // positions = 0：参数一律走选项表，所以那张表永远不会被当成「一个对象」
                    0, entry.options(), ScriptCatalog.Kind.VALUE, ScriptType.STRING, "")));
            groups.put(NAMESPACE + "." + group, copy);
        });
        catalog = new ScriptCatalog(groups, ScriptCatalog.ModuleSource.NONE, Map.of());
        return catalog;
    }

    /** 登记过的函数数（给日志和自检看）。 */
    public static int size() {
        return GROUPS.values().stream().mapToInt(Map::size).sum();
    }

    // ==================== 跑一段 ====================

    /**
     * 跑一段脚本，交回给模型看的回执（结局一行 + stderr / returned / stdout 三栏）。
     *
     * <p>驱动循环照 {@link ScriptCall} 的约定：{@code begin} 走到第一次调用，我们执行它、把结果
     * {@link ScriptCall#result} 交回去，直到 {@code Next.Done}。**整段同步跑完**——
     * 没有 JOB（跨刻的活），所以不会让出主线程。
     */
    public static String run(NumenPlayer body, String code) {
        CompanionScript host = new CompanionScript(body);
        ScriptCall script;
        try {
            script = ScriptCall.inline(code, host);
        } catch (RuntimeException ex) {
            return "脚本没跑起来：" + ex;
        }
        ScriptCall.Next next = script.begin();
        while (true) {
            if (next instanceof ScriptCall.Next.Done done) {
                return done.end().receipt();
            }
            if (next instanceof ScriptCall.Next.Dispatch dispatch) {
                next = script.result(execute(body, dispatch.invocation()));
                continue;
            }
            // Await：这个版本没有 JOB，所以不该出现；真出现了就照实收场，别悬着。
            ScriptCall.Next.Await await = (ScriptCall.Next.Await) next;
            next = script.finished(new ScriptCall.Finish(await.task(), "failed",
                    "这个版本还不支持占身体的活（走过去、挖到碎这类）", null));
        }
    }

    /** 执行一次调用，把结果写成 {@link ApiReply} 要的那份 JSON。 */
    private static String execute(NumenPlayer body, Invocation invocation) {
        Entry entry = find(invocation.group().substring(NAMESPACE.length() + 1), invocation.name());
        if (entry == null) {
            return ApiReply.error(ErrorKind.FAILED,
                    "没有这个函数：" + invocation.function(), "用 help 看看有哪些函数", null).toString();
        }
        try {
            Object value = entry.call().run(body, invocation.args());
            return ApiReply.value(value == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(value)))
                    .toString();
        } catch (ApiError wrong) {
            return ApiReply.error(wrong.kind(), wrong.getMessage(), wrong.hint(), null).toString();
        } catch (RuntimeException ex) {
            // 执行里冒出来的别的异常：别让它掀掉服务端主循环，如实报给脚本
            return ApiReply.error(ErrorKind.FAILED,
                    invocation.function() + " 出错了：" + ex, null, null).toString();
        }
    }

    // ==================== Host ====================

    private final NumenPlayer body;

    private CompanionScript(NumenPlayer body) {
        this.body = body;
    }

    @Override
    public ScriptCatalog catalog() {
        return buildCatalog();
    }

    /**
     * 把脚本的一次调用读成 {@link Invocation}：**参数一律走选项表**，
     * 所以直接把 {@link ScriptRun.Call#options()} 转成 JSON 就行。
     */
    @Override
    public Invocation invocation(ScriptRun.Call call) {
        JsonObject args = new JsonObject();
        call.options().forEach((k, v) -> args.add(k, json(v)));
        return new Invocation(call.group(), call.name(), call.function(), args);
    }

    @Override
    public void tally(String module, ScriptCall.Tally tally) {
        // 战绩统计（哪个模块被用了多少次）还没做——不报错即可。
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    /** Lua 值 → JSON（字符串、数、布尔、列表、表）。 */
    private static JsonElement json(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(item -> array.add(json(item)));
            return array;
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((key, item) -> object.add(String.valueOf(key), json(item)));
            return object;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    // ==================== 参数读取 ====================

    /** 读一个整数参数；缺了或读不成抛 {@link ApiError}。 */
    static int intArg(JsonObject args, String name) throws ApiError {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ApiError(ErrorKind.FAILED, "少了参数 " + name, "补上 " + name + " 再试");
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ex) {
            throw new ApiError(ErrorKind.FAILED, name + " 要是个整数，给的是 " + value,
                    "写成整数，比如 " + name + " = 64");
        }
    }

    /** 读一个字符串参数；缺了或读不成抛 {@link ApiError}。 */
    static String textArg(JsonObject args, String name) throws ApiError {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ApiError(ErrorKind.FAILED, "少了参数 " + name, "补上 " + name + " 再试");
        }
        try {
            return value.getAsString();
        } catch (RuntimeException ex) {
            throw new ApiError(ErrorKind.FAILED, name + " 要是个字符串，给的是 " + value,
                    "写成带引号的文字，比如 " + name + " = \"minecraft:bread\"");
        }
    }

    /** 读一个可省缺的整数参数；缺了给 {@code fallback}。 */
    static int intArgOr(JsonObject args, String name, int fallback) {
        JsonElement value = args.get(name);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    static BlockPos posArg(JsonObject args) throws ApiError {
        return new BlockPos(intArg(args, "x"), intArg(args, "y"), intArg(args, "z"));
    }

    /** 三个坐标参数的参数名集合（登记时用）。 */
    static Set<String> xyz() {
        return Set.of("x", "y", "z");
    }

    /** 空参数表。 */
    static Set<String> none() {
        return Set.of();
    }

    // ==================== 内置函数 ====================

    /**
     * 把本仓已经有的那几件能力挂到脚本上。
     *
     * <p>组名照上游：{@code inv}（背包）、{@code gear}（穿戴）、{@code use}（用一格）、
     * {@code work}（干活）。⚠️ 上游同名函数的**参数形状与返回类型**可能不同，
     * 这一版按本仓已有的 {@link Acts} 写，等对齐上游时再逐个核。
     */
    public static void installBuiltins() {
        if (size() > 0) {
            return;   // 只装一次
        }

        register("inv", "items", none(), (body, args) -> Acts.bag(body));
        register("inv", "eat", Set.of("item"), (body, args) -> Acts.eat(body, textArg(args, "item")));
        register("inv", "drop", Set.of("item", "count"), (body, args) -> {
            String query = textArg(args, "item");
            ItemStack stack = Acts.findItem(body, query);
            if (!stack.isEmpty()) {
                // 动手之前过权限层。东西不在就不必问主人——直接交给 Acts 去说「背包里没有…」。
                guard(body, Action.drop(stack.getItem()));
            }
            return Acts.drop(body, query, intArgOr(args, "count", 0));
        });
        register("gear", "hold", Set.of("item"), (body, args) -> Acts.equip(body, textArg(args, "item")));
        register("use", "block", xyz(), (body, args) -> {
            BlockPos at = posArg(args);
            guard(body, Action.useBlock(at, body.getLevel().getBlockState(at)));
            return Acts.useBlock(body, at);
        });
    }

    /**
     * 脚本里动手之前过权限层（归位阶段 2）。
     *
     * <p>⚠️ 与工具那条路的差别：一段 Lua 是**同步跑完**的，等不了主人答复，所以这里只做同步裁决——
     * 放行就过；不许抛 {@link ErrorKind#DENIED}；**要问**抛 {@link ErrorKind#NEEDS_CONSENT}
     * （脚本 {@code pcall} 接得住，改走会等的工具 {@code use_block} / {@code drop} 即可）。
     *
     * <p>上游这条路经 {@code Consents.await} 能真的把这次调用挂起等主人，等 SDK 归位后换回来。
     * 在那之前「要问」只能如实失败——**失败方向是关**，绝不偷偷放行。
     */
    private static void guard(NumenPlayer body, Action action) throws ApiError {
        Verdict verdict = Permission.judge(body, action);
        switch (verdict.kind()) {
            case ALLOW -> { }
            case DENY -> throw new ApiError(ErrorKind.DENIED,
                    "did not run " + action.describe() + ": " + verdict.reason(), null);
            case ASK -> throw new ApiError(ErrorKind.NEEDS_CONSENT,
                    "did not run " + action.describe()
                            + ": needs the owner's consent, and a script cannot ask — "
                            + "use the use_block / drop tool instead", null);
        }
    }

    /** 登记过的组名，按名字排（自检与日志用）。 */
    public static List<String> groups() {
        return List.copyOf(new LinkedHashMap<>(GROUPS).keySet());
    }
}
