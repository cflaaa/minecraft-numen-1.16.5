package com.dwinovo.numen.core.net;

import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.mcp.McpMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * G 面板的线上格式。
 *
 * <p>客户端与服务端之间只开一条通道，两个方向都是「一个字符串，内容是 JSON」。
 * 这样做的理由：面板要传的东西是**形状会变**的东西（花名册、配置、MCP 状态），
 * 用 {@code FriendlyByteBuf} 逐字段读写的话，每加一个字段就要改两头、还要管版本兼容；
 * 塞一个 JSON 字符串进去，加字段是纯增量的事，老的一端读不懂也只是忽略。
 *
 * <p>为什么不用「客户端直接发 {@code /numen} 命令」代替：命令能改世界、能读配置，
 * 但**读不到花名册**（客户端看不到同伴的血量、在干嘛），面板就只能是一堆按钮而没有内容。
 * 而且命令的返回值是聊天栏文本，面板要的是结构化数据。
 *
 * <p>⚠️ <b>这个类在专用服务端上也会被加载</b>，所以里面**一个客户端类都不能碰**。
 * 客户端那一半在 {@code core/client} 下，由 fabric.mod.json 的 client 入口点负责只在客户端加载。
 *
 * <p>⚠️ gson 2.8.0 兼容（同 {@code NumenConfig}）：不用 {@code JsonParser.parseString}（2.8.6+）、
 * 不用 {@code JsonElement.deepCopy()} / {@code JsonArray.isEmpty()}（2.8.2+）。
 */
public final class NumenPanelNet {

    /** 唯一通道。命名空间沿用模组 id。 */
    public static final ResourceLocation CHANNEL = new ResourceLocation("numen", "panel");

    /** 协议号。将来线上格式真出现不兼容改动时，用它让老客户端自己退场，而不是解析出一堆 null。 */
    public static final int PROTOCOL = 1;

    // ---- 客户端 → 服务端 ----

    public static final String OP_HELLO = "hello";
    public static final String OP_LIST = "list";
    public static final String OP_SUMMON = "summon";
    public static final String OP_DISMISS = "dismiss";
    public static final String OP_TELL = "tell";
    /** G 键：打开同伴的背包（客户端版需求 #0027）。name 空串 = 让服务端挑最近的一只。 */
    public static final String OP_OPEN_INV = "open_inventory";
    public static final String OP_CONFIG = "config";
    public static final String OP_MCP = "mcp";
    /** 聊天栏发 <mcp>：请服务端做外接大脑自检，并把接入提示词回过来。 */
    public static final String OP_MCP_SETUP = "mcp_setup";
    public static final String OP_MODELS = "models";

    // ---- 服务端 → 客户端 ----

    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_OK = "ok";
    public static final String KEY_NOTE = "note";
    public static final String KEY_COMPANIONS = "companions";
    public static final String KEY_CONFIG = "config";
    public static final String KEY_MCP = "mcp";
    /** 「获取模型」的结果。形状：{@code {"pending":bool,"error":str,"list":[str,...]}}。 */
    public static final String KEY_MODELS = "models";
    /** 自检结果块（McpSetup.build 的形状）。 */
    public static final String KEY_MCP_SETUP = "mcp_setup";

    private static final JsonParser PARSER = new JsonParser();

    private NumenPanelNet() {}

    // ---- JSON 小工具（2.8.0 安全） ----

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonObject parse(String json) {
        if (json == null || json.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = PARSER.parse(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            // 对面发来的东西读不懂，就当空请求处理——面板自己会看到一条「没反应」。
            return new JsonObject();
        }
    }

    /** 取字符串，缺了或类型不对就给默认值。 */
    public static String str(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static int integer(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static JsonObject object(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
            return null;
        }
        return o.getAsJsonObject(key);
    }

    // ---- 快照：把服务端此刻的样子做成一份给面板看的数据 ----

    /**
     * 组一份完整快照。**必须在服务端主线程上调用**（要读玩家列表和配置）。
     *
     * @param note 这一趟顺带要回报给面板的一句话（操作结果或报错），可为 null
     * @param ok   上面那句话是成功还是失败
     */
    public static JsonObject snapshot(MinecraftServer server, String note, boolean ok) {
        return snapshot(server, note, ok, null);
    }

    /** 同上，再往快照里附加一块（比如 <mcp> 自检的结果）；{@code extra} 可为 null。 */
    public static JsonObject snapshot(MinecraftServer server, String note, boolean ok, JsonObject extra) {
        JsonObject root = obj();
        root.addProperty(KEY_PROTOCOL, PROTOCOL);
        root.addProperty(KEY_OK, ok);
        if (note != null) {
            root.addProperty(KEY_NOTE, note);
        }
        root.add(KEY_COMPANIONS, companions(server));
        root.add(KEY_CONFIG, config());
        root.add(KEY_MCP, mcp());
        root.add(KEY_MODELS, com.dwinovo.numen.core.llm.ModelCatalogState.snapshot());
        if (extra != null) {
            root.add(KEY_MCP_SETUP, extra);
        }
        return root;
    }

    private static JsonArray companions(MinecraftServer server) {
        JsonArray out = new JsonArray();
        for (NumenPlayer body : Companions.all(server)) {
            JsonObject c = obj();
            c.addProperty("uuid", body.getUUID().toString());
            c.addProperty("name", body.getGameProfile().getName());
            BlockPos pos = body.blockPosition();
            c.addProperty("x", pos.getX());
            c.addProperty("y", pos.getY());
            c.addProperty("z", pos.getZ());
            c.addProperty("dim", String.valueOf(body.getLevel().dimension().location()));
            c.addProperty("hp", Math.round(body.getHealth()));
            c.addProperty("maxHp", Math.round(body.getMaxHealth()));
            c.addProperty("food", body.getFoodData().getFoodLevel());
            c.addProperty("alive", body.isAlive());
            c.addProperty("owner", body.ownerName());

            CompanionBrain brain = Companions.brain(body);
            c.addProperty("activity", brain == null || brain.activity() == null
                    ? "闲着" : brain.activity());
            c.addProperty("blocked", brain != null && brain.unavailableReason() != null);
            out.add(c);
        }
        return out;
    }

    /** 配置快照。**apiKey 只报「设没设、多长」，明文永远不过网。** */
    private static JsonObject config() {
        NumenConfig cfg = NumenConfig.get();
        JsonObject out = obj();
        out.addProperty("provider", cfg.provider);
        out.addProperty("model", cfg.model);
        out.addProperty("baseUrl", cfg.baseUrl);
        out.addProperty("proxy", cfg.proxy == null ? "" : cfg.proxy);
        out.addProperty("persona", cfg.persona == null ? "" : cfg.persona);
        out.addProperty("initiative", cfg.initiative);
        out.addProperty("apiKeySet", cfg.apiKey != null && !cfg.apiKey.isBlank());
        out.addProperty("apiKeyLen", cfg.apiKey == null ? 0 : cfg.apiKey.length());
        String missing = cfg.missingReason();
        out.addProperty("ready", missing == null);
        if (missing != null) {
            out.addProperty("missing", missing);
        }
        return out;
    }

    private static JsonObject mcp() {
        McpMode mode = McpMode.instance();
        JsonObject out = obj();
        out.addProperty("enabled", mode.enabled());
        out.addProperty("driving", mode.driving());
        out.addProperty("endpoint", mode.endpoint());
        out.addProperty("token", mode.token() == null ? "" : mode.token());
        String err = mode.lastError();
        if (err != null) {
            out.addProperty("error", err);
        }
        return out;
    }

    /** 主人是谁——面板要用它判断「现在能不能召唤」。 */
    public static ServerPlayer firstOwner(MinecraftServer server) {
        return Companions.firstOwner(server);
    }
}
