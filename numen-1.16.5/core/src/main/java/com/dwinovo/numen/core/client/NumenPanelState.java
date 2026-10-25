package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.net.NumenPanelNet;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;

/**
 * 面板在客户端这一侧：一份服务端推来的快照，加上发请求的唯一出口。
 *
 * <p>快照是**整份替换**的，不做增量合并——服务端每次都回一份完整的，
 * 合并反而会留下已经消失的同伴的残影。
 */
public final class NumenPanelState {

    private static volatile JsonObject snapshot = NumenPanelNet.obj();
    private static volatile long updatedAt;
    /** 玩家刚发了 <mcp>：下一份快照里的自检块要接住（进了剪贴板再清掉）。 */
    private static volatile boolean awaitingMcpSetup;

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
        if (awaitingMcpSetup) {
            awaitingMcpSetup = false;
            showMcpSetup(parsed);
        }
    }

    /** 聊天栏 <mcp>：请服务端自检。结果回显在 showMcpSetup。 */
    public static void requestMcpSetup() {
        awaitingMcpSetup = true;
        send(NumenPanelNet.OP_MCP_SETUP, null, null);
    }

    /**
     * 自检结果进两条路：接入提示词（含令牌）**只进剪贴板**（上游同款决定——不上屏、不进聊天日志），
     * 屏幕上只留自检结论与一行命令。发不出去（服务端没装 numen/没权限）就直说。
     */
    private static void showMcpSetup(JsonObject snap) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        JsonObject setup = NumenPanelNet.object(snap, NumenPanelNet.KEY_MCP_SETUP);
        if (setup == null) {
            String note = NumenPanelNet.str(snap, NumenPanelNet.KEY_NOTE, "服务端没有回应自检数据");
            mc.player.displayClientMessage(
                    new TextComponent("§c[numen] MCP 自检失败：" + note), false);
            return;
        }
        boolean enabled = NumenPanelNet.integer(setup, "enabled", 0) != 0;
        boolean bound = NumenPanelNet.integer(setup, "bound", 0) != 0;
        String endpoint = NumenPanelNet.str(setup, "endpoint", "?");
        String lan = NumenPanelNet.str(setup, "lan", "");
        int port = NumenPanelNet.integer(setup, "port", 0);
        int companions = NumenPanelNet.integer(setup, "companions", 0);
        if (enabled && bound) {
            mc.player.displayClientMessage(
                    new TextComponent("§a[numen] MCP 自检通过：端点 " + endpoint), false);
        } else if (!enabled) {
            mc.player.displayClientMessage(new TextComponent(
                    "§c[numen] 外接大脑没开：把 config/numen/mcp_server.json 的 enabled 改 true 后重启"), false);
        } else {
            mc.player.displayClientMessage(new TextComponent(
                    "§c[numen] 端口 " + port + " 没在监听：看 latest.log 里 [numen-mcp] 的报错"), false);
        }
        mc.player.displayClientMessage(
                new TextComponent("§7同伴 " + companions + " 只" + (lan.isEmpty() ? "" : "，本机地址 " + lan)),
                false);
        String prompt = NumenPanelNet.str(setup, "prompt", "");
        if (!prompt.isEmpty()) {
            mc.keyboardHandler.setClipboard(prompt);
            mc.player.displayClientMessage(new TextComponent(
                    "§a[numen] 接入提示词已复制到剪贴板——Ctrl+V 直接发给你的 AI（含令牌，只发你信任的）"),
                    false);
        }
        String oneliner = NumenPanelNet.str(setup, "oneliner", "");
        if (!oneliner.isEmpty()) {
            // 含令牌，所以只在本地聊天里显示（不上服务器广播），用户自己注意别外传
            mc.player.displayClientMessage(new TextComponent("§f" + oneliner), false);
            mc.player.displayClientMessage(
                    new TextComponent("§7（上面这行含令牌，pi 一行接入用；粘贴前别截图外发）"), false);
        }
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

    private static boolean send(String op, String key, String value) {
        JsonObject o = NumenPanelNet.obj();
        o.addProperty("op", op);
        if (key != null) {
            o.addProperty(key, value);
        }
        return send(o);
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

    /**
     * G 键「打开同伴的背包」（#0027）。{@code name} 空串 = 让服务端挑最近的一只。
     *
     * @return 发出去没有；发不出去通常是服务端没装 numen（或频道不匹配）。
     */
    public static boolean openInventory(String name) {
        return send(NumenPanelNet.OP_OPEN_INV, "name", name == null ? "" : name);
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
