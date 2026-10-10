package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.net.NumenPanelNet;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 面板在客户端这一侧：一份服务端推来的快照，加上发请求的唯一出口。
 *
 * <p>快照是**整份替换**的，不做增量合并——服务端每次都回一份完整的，
 * 合并反而会留下已经消失的同伴的残影。
 */
public final class NumenPanelState {

    private static volatile JsonObject snapshot = NumenPanelNet.obj();
    private static volatile long updatedAt;

    private NumenPanelState() {}

    /** 收下服务端推来的一份快照。 */
    public static void accept(String json) {
        JsonObject parsed = NumenPanelNet.parse(json);
        if (NumenPanelNet.integer(parsed, NumenPanelNet.KEY_PROTOCOL, 0) != NumenPanelNet.PROTOCOL) {
            // 协议对不上：宁可什么都不显示，也别把半懂的数据画出来误导人。
            return;
        }
        snapshot = parsed;
        updatedAt = System.currentTimeMillis();
    }

    public static JsonObject snapshot() {
        return snapshot;
    }

    public static boolean hasData() {
        return updatedAt != 0L;
    }

    public static long ageMs() {
        return updatedAt == 0L ? Long.MAX_VALUE : System.currentTimeMillis() - updatedAt;
    }

    /** 最近一次操作的结果（服务端随快照捎回来的那句话）。 */
    public static String note() {
        return NumenPanelNet.str(snapshot, NumenPanelNet.KEY_NOTE, "");
    }

    public static boolean lastOk() {
        return !snapshot.has(NumenPanelNet.KEY_OK) || snapshot.get(NumenPanelNet.KEY_OK).getAsBoolean();
    }

    // ---- 发请求 ----

    /**
     * 唯一的出口。
     *
     * <p>{@code canSend} 与 {@code send} 在「还没连上服务器」时都会抛，
     * 这里一律吞掉——面板打不开或点了没反应是小事，把客户端主线程掀了是大事。
     */
    public static boolean send(JsonObject request) {
        try {
            if (!ClientPlayNetworking.canSend(NumenPanelNet.CHANNEL)) {
                return false;
            }
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeUtf(request.toString());
            ClientPlayNetworking.send(NumenPanelNet.CHANNEL, buf);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void send(String op, String key, String value) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", op);
        if (key != null) {
            o.addProperty(key, value);
        }
        send(o);
    }

    public static void requestRefresh() {
        send(NumenPanelNet.OP_LIST, null, null);
    }

    public static void summon(String name) {
        send(NumenPanelNet.OP_SUMMON, "name", name);
    }

    public static void dismiss(String name) {
        send(NumenPanelNet.OP_DISMISS, "name", name);
    }

    public static void tell(String name, String text) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", NumenPanelNet.OP_TELL);
        o.addProperty("name", name);
        o.addProperty("text", text);
        send(o);
    }

    public static void setConfig(String key, String value) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", NumenPanelNet.OP_CONFIG);
        o.addProperty("key", key);
        o.addProperty("value", value);
        send(o);
    }

    public static void setMcpEnabled(boolean on) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", NumenPanelNet.OP_MCP);
        o.addProperty("value", on ? 1 : 0);
        send(o);
    }

    /**
     * 让服务端拿这份「此刻填在面板里的」端点与密钥去拉一次模型清单。
     *
     * <p>特意带上 {@code provider}/{@code baseUrl}/{@code apiKey} 的**当前值**，而不是让服务端
     * 去读配置文件：玩家通常是「填完 key → 点获取模型 → 挑一个 → 再保存」，
     * 这时候配置里还是旧的（甚至是空的），用配置的值必然拿到 401。
     *
     * <p>{@code apiKey} 只在**玩家这次真的敲了**的时候才带上；没敲就传空串，
     * 让服务端回退到配置文件里已保存的那个——否则会拿空密钥去打一次注定 401 的请求。
     */
    public static void fetchModels(String provider, String baseUrl, String apiKeyOrEmpty) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", NumenPanelNet.OP_MODELS);
        o.addProperty("provider", provider == null ? "" : provider);
        o.addProperty("baseUrl", baseUrl == null ? "" : baseUrl);
        o.addProperty("apiKey", apiKeyOrEmpty == null ? "" : apiKeyOrEmpty);
        send(o);
    }

    /** 「获取模型」的结果区块；没拉到过也返回一个非 null 的对象（{@code pending=false}）。 */
    public static JsonObject models() {
        JsonObject m = NumenPanelNet.object(snapshot, NumenPanelNet.KEY_MODELS);
        return m == null ? NumenPanelNet.obj() : m;
    }
}
