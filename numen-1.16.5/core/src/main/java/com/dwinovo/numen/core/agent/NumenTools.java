package com.dwinovo.numen.core.agent;

import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.nav.CompanionNav;
import com.dwinovo.numen.core.script.CompanionScript;
import com.dwinovo.numen.core.task.act.Acts;
import com.dwinovo.numen.pathing.body.BodyCompat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具表：模型能对这个世界做的每一件事。
 *
 * <p>上游这一层在 {@code core/task/} 下——一百多个文件，挖矿、建造、战斗、钓鱼、开箱、合成各有各的任务类，
 * 每个都是一台状态机。这一版只做<b>六个原子动作</b>，够把「召唤 → 说话 → 走过去 → 挖一格 → 报情况」
 * 这条链跑通；其余按 docs/PORTING.md 的阶段表往后排。
 *
 * <h2>一个身体同时只有一件长动作</h2>
 * {@code goto} 是要跨很多刻才做完的，所以这里有一个极简的任务槽：{@link #run} 派活，
 * {@link #tick} 每刻推进，做完才回报给引擎的 {@link ToolPort.Sink}。
 * 引擎侧本来就有「一个做完了才派下一个」的串行契约（{@code SerialCalls}），两边是一致的。
 *
 * <h2>动作是真的在做</h2>
 * {@code mine} 走的是 {@code ServerPlayerGameMode.destroyBlock}——就是原版服务端收到
 * 「玩家挖方块」包之后走的那一条路。不是 {@code setblock}，破坏速度、掉落、统计都照玩家来。
 */
public final class NumenTools {

    private NumenTools() {}

    /** 身体 UUID → 正在跑的那件活。 */
    private static final Map<UUID, Job> JOBS = new HashMap<>();

    // =====================================================================
    //  工具表（给模型看的）
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
                    obj("type", "object",
                            "properties", obj(
                                    "x", obj("type", "integer", "description", "方块 X 坐标"),
                                    "y", obj("type", "integer", "description", "方块 Y 坐标"),
                                    "z", obj("type", "integer", "description", "方块 Z 坐标")),
                            "required", List.of("x", "y", "z"))),

            new Spec("scan_blocks",
                    "一次看一片：以自己为中心扫一个立方体，报出每种方块有多少、以及最近的一个在哪。"
                            + "找路、看建筑轮廓、找矿首选它，比逐格 inspect_block 快得多。",
                    obj("type", "object",
                            "properties", obj(
                                    "radius", obj("type", "integer",
                                            "description", "扫描半径，3~16，默认 8")),
                            "required", List.of())),

            new Spec("goto",
                    "走到某个坐标附近。会自己找路：绕开障碍、上下台阶、必要时跳过缺口。"
                            + "走到距离目标 1.5 格以内算成功；走不到会报失败并说明原因（没路、卡住、太远等）"
                            + "以及当时停在哪。目标不可达时不要反复重试，换个坐标或先挖开挡路的东西。",
                    obj("type", "object",
                            "properties", obj(
                                    "x", obj("type", "integer", "description", "目标 X"),
                                    "y", obj("type", "integer", "description", "目标 Y"),
                                    "z", obj("type", "integer", "description", "目标 Z")),
                            "required", List.of("x", "y", "z"))),

            new Spec("mine",
                    "挖掉一格方块。走的是原版「玩家挖方块」那条路，所以掉落、统计都照玩家来。"
                            + "够不着的格子会报失败——先 goto 过去。",
                    obj("type", "object",
                            "properties", obj(
                                    "x", obj("type", "integer", "description", "方块 X"),
                                    "y", obj("type", "integer", "description", "方块 Y"),
                                    "z", obj("type", "integer", "description", "方块 Z")),
                            "required", List.of("x", "y", "z"))),

            new Spec("use_block",
                    "右键用一格方块：开箱子、开门、按按钮、拉杆、用工作台、翻活板门。"
                            + "走的是原版「玩家右键」那条路，服务端自己的规矩（够不够得着、冒险模式、"
                            + "别的模组取消了事件）都在里面生效。够不着或看不见那一面会报失败并说明原因。"
                            + "⚠️ 只能点**已经存在**的方块；要往世界里放东西得先手里拿着能放的。",
                    obj("type", "object",
                            "properties", obj(
                                    "x", obj("type", "integer", "description", "方块 X"),
                                    "y", obj("type", "integer", "description", "方块 Y"),
                                    "z", obj("type", "integer", "description", "方块 Z")),
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
                            "required", List.of("item"))),

            new Spec("lua",
                    "写一段 Lua 程序，一次做完好几步——比一步一步调工具省得多。"
                            + "能调的：numen.inv.items() / numen.inv.eat{item=} / numen.inv.drop{item=,count=} / "
                            + "numen.gear.hold{item=} / numen.use.block{x=,y=,z=}。"
                            + "用 print(...) 报告你看到的东西；用 return 交回一个值。"
                            + "程序是同步跑完的（不能等跨刻的动作），有 200 次调用与二十分钟的墙钟上限"
                            + "（上限由引擎的 ScriptLimits 强制）。",
                    obj("type", "object",
                            "properties", obj(
                                    "code", obj("type", "string", "description", "Lua 程序正文")),
                            "required", List.of("code"))));

    public static List<IToolSpec> specs() {
        return SPECS;
    }

    /**
     * 按名字找一个工具规格，大小写宽容（{@code Get_Status} 也认）；找不到返回 null。
     *
     * <p>给外接大脑（MCP）那条路用：它是<b>按名字</b>调工具的，手里不像模型那样
     * 有一个现成的 {@code LlmToolCall}。找不到就由调用方回一条失败——
     * 见 {@code McpActuator#invoke}。
     */
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

    // =====================================================================
    //  执行
    // =====================================================================

    /** 一件正在跑的活。 */
    private static final class Job {
        final List<LlmToolCall> calls;
        final ToolPort.Sink sink;
        final CompanionBrain brain;
        int index;
        /** 非 null = 当前这个调用是个跨刻的长动作。 */
        CompanionNav nav;
        /** 当前长动作对应的调用，收尾时回报用。 */
        LlmToolCall walking;
        /** 这批调用已经结算过了，保证 {@code settled} 只报一次。 */
        boolean settled;

        Job(List<LlmToolCall> calls, ToolPort.Sink sink, CompanionBrain brain) {
            this.calls = calls;
            this.sink = sink;
            this.brain = brain;
        }
    }

    /** 引擎派了一批调用。 */
    public static void run(NumenPlayer body, CompanionBrain brain, List<LlmToolCall> calls, ToolPort.Sink sink) {
        Job job = new Job(new ArrayList<>(calls), sink, brain);
        JOBS.put(body.getUUID(), job);
        advance(body, job);
    }

    /** 服务端每刻推进一次长动作。 */
    public static void tick(NumenPlayer body) {
        Job job = JOBS.get(body.getUUID());
        if (job == null || job.nav == null) {
            return;
        }
        String outcome = job.nav.step(body);
        if (outcome == null) {
            return;   // 还在走
        }
        job.sink.finished(job.walking, result(outcome, job.nav.success(), job.nav.data()));
        job.nav = null;
        job.walking = null;
        job.index++;
        advance(body, job);
    }

    /** 打断：松开所有键，把没结算的活丢掉。 */
    public static void cancel(NumenPlayer body) {
        Job job = JOBS.remove(body.getUUID());
        if (job != null) {
            if (job.nav != null) {
                job.nav.stop();
            }
            body.controls().releaseAll();
        }
    }

    /** 一路把能同步做完的做完，遇到长动作就停下等 tick。 */
    private static void advance(NumenPlayer body, Job job) {
        while (job.index < job.calls.size()) {
            LlmToolCall call = job.calls.get(job.index);
            job.sink.started(call);

            JsonObject args = args(call);
            switch (call.name()) {
                case "say" -> {
                    job.brain.speak(text(args, "text"));
                    job.sink.finished(call, result("说完了", true, null));
                    job.index++;
                }
                case "get_status" -> {
                    job.sink.finished(call, result(status(body), true, null));
                    job.index++;
                }
                case "inspect_block" -> {
                    job.sink.finished(call, result(inspect(body, args), true, null));
                    job.index++;
                }
                case "scan_blocks" -> {
                    job.sink.finished(call, result(scan(body, args), true, null));
                    job.index++;
                }
                case "mine" -> {
                    job.sink.finished(call, mine(body, args));
                    job.index++;
                }
                case "use_block" -> {
                    job.sink.finished(call, Acts.useBlock(body, pos(args)));
                    job.index++;
                }
                case "inventory" -> {
                    job.sink.finished(call, Acts.bag(body));
                    job.index++;
                }
                case "equip" -> {
                    job.sink.finished(call, Acts.equip(body, text(args, "item")));
                    job.index++;
                }
                case "eat" -> {
                    job.sink.finished(call, Acts.eat(body, text(args, "item")));
                    job.index++;
                }
                case "drop" -> {
                    job.sink.finished(call,
                            Acts.drop(body, text(args, "item"), intArg(args, "count")));
                    job.index++;
                }
                case "lua" -> {
                    // 登记内置函数（幂等），然后整段同步跑完。
                    CompanionScript.installBuiltins();
                    job.sink.finished(call, result(CompanionScript.run(body, text(args, "code")), true, null));
                    job.index++;
                }
                case "goto" -> {
                    CompanionNav nav = CompanionNav.to(body, intArg(args, "x"), intArg(args, "y"),
                            intArg(args, "z"));
                    job.nav = nav;
                    job.walking = call;
                    job.brain.setActivity("正在走到 " + nav.describe());
                    return;   // 交给 tick
                }
                default -> {
                    job.sink.finished(call, result("没有这个工具：" + call.name(), false, null));
                    job.index++;
                }
            }
        }
        if (!job.settled) {
            job.settled = true;
            job.sink.settled();
        }
        JOBS.remove(body.getUUID());
    }

    // ---- 各件活 ----

    private static String status(NumenPlayer body) {
        BlockPos pos = body.blockPosition();
        return "你在 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + "（维度 " + body.getLevel().dimension().location() + "）"
                + "，血量 " + Math.round(body.getHealth()) + "/" + Math.round(body.getMaxHealth())
                + "，饱食 " + body.getFoodData().getFoodLevel()
                // 用 ItemStack.getHoverName()，不要用 Item.getDescription()：
                // 后者只存在于「合并的客户端+服务端 jar」里，专用服务端的 jar 里没有它，
                // 编译期看不出来，一跑就 NoSuchMethodError（2026-10-09 实测踩到）。
                // 详见 docs/PORTING.md 3.5 与 server-api-check.py。
                + "，手里 " + body.inventory.getSelected().getHoverName().getString()
                + "，朝向 yaw=" + Math.round(body.yRot) + " pitch=" + Math.round(body.xRot)
                // 游戏模式与 abilities 要能一眼看到：`mine` 掉不掉东西全看它们。
                // 见 ServerPlayerGameMode.destroyBlock 的字节码——`if (isCreative()) return true;`
                // 之后才是掉落那一段；而 isCreative() 就是 gameModeForPlayer.isCreative()。
                + "，模式 " + body.gameMode.getGameModeForPlayer()
                + "（instabuild=" + body.abilities.instabuild + "）";
    }

    private static String inspect(NumenPlayer body, JsonObject args) {
        BlockPos pos = new BlockPos(intArg(args, "x"), intArg(args, "y"), intArg(args, "z"));
        BlockState state = body.getLevel().getBlockState(pos);
        ResourceLocation id = Registry.BLOCK.getKey(state.getBlock());
        return pos.getX() + "," + pos.getY() + "," + pos.getZ() + " 是 " + id
                + (state.isAir() ? "（空气）" : "");
    }

    private static String scan(NumenPlayer body, JsonObject args) {
        int radius = Math.max(3, Math.min(16, intArg(args, "radius") == 0 ? 8 : intArg(args, "radius")));
        BlockPos center = body.blockPosition();
        ServerLevel level = body.getLevel();

        Map<String, int[]> tally = new HashMap<>();   // 方块 → {数量, 最近距离平方, x, y, z}
        int scanned = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos at = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(at);
                    scanned++;
                    if (state.isAir()) {
                        continue;
                    }
                    String key = Registry.BLOCK.getKey(state.getBlock()).toString();
                    int dist = dx * dx + dy * dy + dz * dz;
                    int[] row = tally.get(key);
                    if (row == null) {
                        tally.put(key, new int[] {1, dist, at.getX(), at.getY(), at.getZ()});
                    } else {
                        row[0]++;
                        if (dist < row[1]) {
                            row[1] = dist;
                            row[2] = at.getX();
                            row[3] = at.getY();
                            row[4] = at.getZ();
                        }
                    }
                }
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("以 ").append(center.getX()).append(',').append(center.getY()).append(',')
                .append(center.getZ()).append(" 为中心，半径 ").append(radius)
                .append(" 扫了 ").append(scanned).append(" 格，非空气方块如下（按数量降序）：\n");
        tally.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed())
                .limit(25)
                .forEach(e -> {
                    int[] row = e.getValue();
                    out.append("  ").append(e.getKey()).append(" ×").append(row[0])
                            .append("，最近一个在 ").append(row[2]).append(',').append(row[3])
                            .append(',').append(row[4]).append('\n');
                });
        return out.toString();
    }

    private static String mine(NumenPlayer body, JsonObject args) {
        BlockPos pos = new BlockPos(intArg(args, "x"), intArg(args, "y"), intArg(args, "z"));
        if (body.getLevel().getBlockState(pos).isAir()) {
            return result("那一格本来就是空气", false, null);
        }
        if (!BodyCompat.canReachBlock(body, pos, 0.0)) {
            return result("够不着 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + "（手臂只有 " + BodyCompat.blockReach(body) + " 格），先 goto 过去", false, null);
        }
        // 原版「玩家挖方块」那条路：破坏速度、掉落、统计都照玩家来。
        boolean broke = body.gameMode.destroyBlock(pos);
        return result(broke
                ? "挖掉了 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                : "挖不动 " + pos.getX() + "," + pos.getY() + "," + pos.getZ(), broke, null);
    }

    // =====================================================================
    //  小工具
    // =====================================================================

    /**
     * 解析工具参数。
     *
     * <p>用 {@code new JsonParser().parse(...)} 而不是 {@code JsonParser.parseString(...)}：
     * 后者是 Gson 2.8.6 才加的，而 Minecraft 1.16.5 的类路径上带的是 Gson 2.8.0。
     * 前者在 2.8.0 与 2.10 上都在（2.9 起标了 deprecated，但仍是公开构造器）。
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

    /** 三个整数参数拼成一个方块坐标（x / y / z）。 */
    private static BlockPos pos(JsonObject args) {
        return new BlockPos(intArg(args, "x"), intArg(args, "y"), intArg(args, "z"));
    }

    private static String text(JsonObject args, String name) {
        try {
            return args.has(name) ? args.get(name).getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** 回执统一走一个 JSON 形状：{@code success} / {@code message} / {@code data}。 */
    private static String result(String message, boolean success, String data) {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("message", message);
        if (data != null) {
            json.addProperty("data", data);
        }
        return json.toString();
    }
}
