package com.dwinovo.numen.core.mcp;

import com.dwinovo.numen.core.NumenCore;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 外接大脑（MCP 服务端）的配置，落在 {@code config/numen/mcp_server.json}。
 *
 * <p>照搬上游 {@code api/common/src/client/.../mcp/server/McpConfig.java}，只改了两处：
 *
 * <ol>
 *   <li><b>日志</b>：上游用 {@code Constants.LOG}，这里用 {@link NumenCore}（本仓没有 {@code Constants}）；</li>
 *   <li><b>gson 写法</b>：上游用 {@code JsonParser.parseString(...)}（2.8.6 才有），
 *       这里用 {@code new JsonParser().parse(...)}——1.16.5 的类路径上带的是 gson 2.8.0。
 *       详见 docs/PORTING.md 3.4。</li>
 * </ol>
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@code enabled} —— 总开关。默认 <b>false</b>，主人自己拨开；</li>
 *   <li>{@code host}/{@code port} —— 绑哪里。默认只绑回环；</li>
 *   <li>{@code token} —— 访问凭据。默认<b>首次生成一份</b>：空令牌只在回环上无害，
 *       而配置文件是可以手改的，一旦 host 改成 {@code 0.0.0.0} 空令牌就是无鉴权全开。
 *       与其指望他改地址时记得再设一个，不如一开始就有；</li>
 *   <li>{@code call_timeout_seconds} —— 一次 {@code tools/call} 等身体动作做完的上限；</li>
 *   <li>{@code hidden_tools} —— 不暴露给外部的工具名（默认全给：外接大脑和内置大脑用的是同一套）；</li>
 *   <li>{@code quiet_fallback} —— 外脑安静超时后内置大脑是否临时接管。默认 false：主人开这个模式是刻意的。</li>
 * </ul>
 */
public record McpConfig(
        boolean enabled,
        String host,
        int port,
        String token,
        int callTimeoutSeconds,
        List<String> hiddenTools,
        boolean quietFallback) {

    /** 只绑回环。默认值，也是「没开局域网」时的地址。 */
    public static final String LOOPBACK = "127.0.0.1";
    /** 绑所有网卡——局域网里别的机器就能连上来了。 */
    public static final String ANY_HOST = "0.0.0.0";

    private static final List<String> DEFAULT_HIDDEN = List.of();

    /** 读配置；没有就播一份默认的（带一枚现生成的令牌）。 */
    @SuppressWarnings("deprecation")
    public static McpConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            McpConfig def = new McpConfig(false, LOOPBACK, 8765, mintToken(), 300, DEFAULT_HIDDEN, false);
            write(file, def, "写了默认配置");
            return def;
        }
        try {
            JsonObject o = new JsonParser().parse(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return new McpConfig(
                    o.has("enabled") && o.get("enabled").getAsBoolean(),
                    strOr(o, "host", LOOPBACK),
                    o.has("port") ? o.get("port").getAsInt() : 8765,
                    strOr(o, "token", ""),
                    o.has("call_timeout_seconds") ? o.get("call_timeout_seconds").getAsInt() : 300,
                    o.has("hidden_tools") ? strings(o, "hidden_tools") : DEFAULT_HIDDEN,
                    o.has("quiet_fallback") && o.get("quiet_fallback").getAsBoolean());
        } catch (IOException | RuntimeException ex) {
            NumenCore.warn("[numen-mcp] 配置读不出来 {} —— 服务端按关闭处理：{}", file, ex.toString());
            return new McpConfig(false, LOOPBACK, 8765, "", 300, DEFAULT_HIDDEN, false);
        }
    }

    public boolean isHidden(String toolName) {
        return hiddenTools.contains(toolName);
    }

    /** 配置文件还没读到时的占位（模式关闭），避免 {@link McpMode} 持 null 配置。 */
    static McpConfig disabledDefault() {
        return new McpConfig(false, LOOPBACK, 8765, "", 300, DEFAULT_HIDDEN, false);
    }

    McpConfig withEnabled(boolean on) {
        return new McpConfig(on, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    McpConfig withEndpoint(String host, int port, int callTimeoutSeconds) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    McpConfig withHiddenTools(List<String> hiddenTools) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, List.copyOf(hiddenTools), quietFallback);
    }

    McpConfig withToken(String token) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, quietFallback);
    }

    McpConfig withQuietFallback(boolean on) {
        return new McpConfig(enabled, host, port, token, callTimeoutSeconds, hiddenTools, on);
    }

    /** 绑的是所有网卡吗——局域网里别人够得着。 */
    public boolean lanExposed() {
        return !LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host);
    }

    /**
     * 令牌为空且对局域网开放 = 无鉴权裸奔。
     *
     * <p>「空令牌只在回环上无害」的前提是<b>回环</b>；地址一放开，那个前提就不成立了。
     */
    public boolean unguarded() {
        return lanExposed() && (token == null || token.isBlank());
    }

    /**
     * 生成一枚新令牌。
     *
     * <p>用 {@link java.security.SecureRandom}——这串东西是外部 AI 操控同伴的唯一凭据，
     * 拿可预测的随机数生成等于没有。
     */
    public static String mintToken() {
        byte[] raw = new byte[18];
        new java.security.SecureRandom().nextBytes(raw);
        return "numen-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    void save(Path file) {
        write(file, this, "保存了配置");
    }

    private static void write(Path file, McpConfig cfg, String what) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfg.enabled());
        o.addProperty("host", cfg.host());
        o.addProperty("port", cfg.port());
        o.addProperty("token", cfg.token());
        o.addProperty("call_timeout_seconds", cfg.callTimeoutSeconds());
        JsonArray hidden = new JsonArray();
        cfg.hiddenTools().forEach(hidden::add);
        o.add("hidden_tools", hidden);
        o.addProperty("quiet_fallback", cfg.quietFallback());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
            NumenCore.info("[numen-mcp] {} {}", what, file);
        } catch (IOException ex) {
            NumenCore.warn("[numen-mcp] 配置写不出去 {}：{}", file, ex.toString());
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray(key)) {
                out.add(el.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static String strOr(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }
}
