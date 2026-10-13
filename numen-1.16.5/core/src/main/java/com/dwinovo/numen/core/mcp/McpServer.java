package com.dwinovo.numen.core.mcp;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.agent.NumenTools;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 一个极简的 MCP（Model Context Protocol）服务端：JSON-RPC 2.0 手写在 JDK 自带的
 * {@link HttpServer} 上——不引第三方 MCP SDK，不引 Reactor。它实现的正好是外部 agent
 * 驱动同伴所需的那一面：{@code initialize}、{@code tools/list}、{@code tools/call}、
 * {@code ping}，以及 {@code notifications/initialized} 握手。
 *
 * <p>移植自上游 {@code api/common/src/client/.../mcp/server/McpServer.java}。与上游的差异
 * （都是本仓还没有的东西，逐条写在 docs/PORTING.md 的「外接大脑」一节）：
 *
 * <ul>
 *   <li><b>工具面</b>：{@code tools/list} 全量给——{@code ToolRunner.specs()}（= {@code ToolRegistry}
 *       全表：跑程序的 {@code lua}、技能、计划、札记四个大脑工具 + 本仓的世界工具）加上五个
 *       管理/对话工具。刀 3 起枚举源是登记表，新增工具只登记一次，两边（MCP 与内置大脑）
 *       自动都看得见；世界工具的实现随刀 4 逐族换成上游 @Fn 函数时，这个面一行不用改。</li>
 *   <li><b>没有现场缓冲</b>（上游的 {@code McpTranscript}，喂设置面板里那条活动流）；
 *       本仓没有面板，工具调用改成写日志；</li>
 *   <li><b>没有接入提示词按钮</b>，那段话由 {@link McpMode#accessPrompt()} 提供；</li>
 *   <li><b>gson 2.8.0 兼容</b>：不用 {@code JsonParser.parseString}（2.8.6+）、
 *       不用 {@code JsonElement.deepCopy}（2.8.2+）、不用 {@code JsonArray.isEmpty}（2.8.2+）。</li>
 * </ul>
 *
 * <h2>工具面</h2>
 * {@code list_companions} / {@code create_companion} / {@code delete_companion} /
 * {@code get_events} / {@code say}，加上 {@link com.dwinovo.numen.core.agent.tool.ToolRunner#specs()}
 * 全表（减掉配置里 {@code hidden_tools} 那批，再减掉 {@code say}——它已经有专属的对话版了），
 * 每个都多注入一个必填的 {@code companion} 参数，调用经 {@link McpActuator#invoke} 落到
 * 同伴自己的工具口上。模式开着期间内置大脑一轮都不开，所以外部驱动者直接派活即可，
 * 没有「取得控制权」这一步。
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";
    /** 名册/召唤/遣散很快；只有工具动作才用配置里的超时。 */
    private static final int CONTROL_TIMEOUT_SECONDS = 10;
    /**
     * {@code get_events} 长轮询：默认只停 2 秒——停靠期间外脑困在这一次调用里，别的什么都做不了，
     * 它得频繁拿回方向盘才能主动行动，而不是只会应答。急件走叫醒挂点即刻送达，与这个值无关；
     * 上限 50 秒留给「就想挂着等主人」的用法。
     */
    private static final int DEFAULT_WAIT_SECONDS = 2;
    private static final int MAX_WAIT_SECONDS = 50;

    /**
     * 接上来的 agent 在 {@code initialize} 里拿到的说明（MCP 的 {@code instructions} 字段）——
     * Numen 是什么、怎么驱动它，任何客户端不装额外的技能也知道基本盘。
     *
     * <p>⚠️ 这段文字是<b>照着本仓实际有的工具写的</b>，与上游那份不同。改工具面时必须一起改，
     * 否则外脑会去调不存在的工具。
     */
    private static final String INSTRUCTIONS = """
            Numen companions are AI-controlled, player-like characters inside a live Minecraft game. \
            Through this server you take control of a companion's body and play the game as it — perceive, \
            move, mine. You ARE its brain while this server runs: the built-in AI stands aside entirely. \
            Drive the body directly — there is no 'take control' handshake.

            Loop: (1) list_companions to see who is live — create_companion by name to summon a new one, \
            delete_companion to dismiss one for good; (2) perceive with get_status (where am I, what's in \
            hand), inspect_block (one block, works at any distance), scan_blocks (a cube around the body: \
            what blocks are where); (3) act with goto (walk to a coordinate), mine (break one block you can \
            reach), say (speak as the companion). Every tool takes a 'companion' argument (name or id), so \
            each call targets one companion.

            What this build can and cannot do — read this before planning: this is the 1.16.5 port and its \
            tool set is still small. goto IS real pathfinding: it searches a route (A* over terrain, \
            avoiding falls, lava and nearby hostiles) and walks it, so it does route around walls and up \
            steps. It will not dig or place blocks on the way, so a target sealed behind solid rock still \
            fails — mine through first or pick another coordinate. use_block right-clicks one block \
            (chests, doors, buttons, crafting tables); inventory lists what she carries; equip / eat / \
            drop move and consume items. There is still NO building, crafting, fighting or fishing. \
            Do not invent tool names; tools/list is the whole catalogue.

            Rules: survival mode — the API does only what a real player can (mine to get stone; there is no \
            give or setblock). You are blind between calls, so perceive before and after acting. You can \
            drive several companions in parallel.

            You also carry the companion's conversation: call get_events(companion) about every 2 \
            seconds while you drive it. It waits 2 seconds by default and returns instantly the moment \
            something urgent lands, so you get the wheel back every couple of seconds and can act on \
            your own initiative instead of only reacting. The owner reaches the companion with the \
            in-game command `/numen tell <name> <text>` — that lands here as a <query> (this build has \
            no plain-chat hook, so ordinary chat does NOT reach you). Reply with say(companion, text): \
            the words appear in-game as the companion's chat line. Keep your own conversation history — \
            the game stores none for you; between get_events calls nothing is lost (events queue up). \
            Raise wait_seconds (up to 50) only when you deliberately want to park and wait for the \
            owner to speak.""";

    /** 引擎工具里不重复挂出来的：{@code say} 已经有专属的对话版（带 companion + text）。 */
    private static final String DEDICATED_SAY = "say";

    private final McpConfig config;
    private final Gson gson = new Gson();
    private HttpServer http;

    public McpServer(McpConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        http.createContext("/mcp", this::handle);
        // get_events 长轮询会让整条线程停靠最长 50 秒，动作类工具也会阻到任务结束——
        // 池得容下几个同伴各自的轮询加并行动作。
        http.setExecutor(Executors.newFixedThreadPool(16, r -> {
            Thread t = new Thread(r, "numen-mcp-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
        }
    }

    // ---- HTTP 层 ----

    @SuppressWarnings("deprecation")
    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                respond(ex, 405, "");
                return;
            }
            if (!authorized(ex)) {
                respond(ex, 401, "");
                return;
            }
            // 任何通过鉴权的请求都算「对方还在」——活跃时间靠它，ping 也算数。
            McpMode.instance().touch();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed;
            try {
                parsed = new JsonParser().parse(body);
            } catch (RuntimeException parseErr) {
                respondJson(ex, errorResponse(null, -32700, "parse error: " + parseErr.getMessage()));
                return;
            }

            if (parsed.isJsonArray()) {   // JSON-RPC 批
                JsonArray out = new JsonArray();
                for (JsonElement el : parsed.getAsJsonArray()) {
                    JsonObject r = dispatch(el.getAsJsonObject());
                    if (r != null) {
                        out.add(r);
                    }
                }
                if (out.size() == 0) {
                    respond(ex, 202, "");
                } else {
                    respondJson(ex, out);
                }
            } else {
                JsonObject r = dispatch(parsed.getAsJsonObject());
                if (r == null) {
                    respond(ex, 202, "");   // 通知：不回
                } else {
                    respondJson(ex, r);
                }
            }
        } catch (RuntimeException fatal) {
            Constants.warn("[numen-mcp] 请求失败：{}", fatal.toString());
            try {
                respond(ex, 500, "");
            } catch (IOException ignored) {
                // 连接已经断了
            }
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        if (config.token() == null || config.token().isBlank()) {
            return true;
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.equals("Bearer " + config.token())) {
            return true;
        }
        String query = ex.getRequestURI().getQuery();
        return query != null && query.contains("token=" + config.token());
    }

    private void respondJson(HttpExchange ex, JsonElement json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ---- JSON-RPC 分发 ----

    /** @return 响应对象；通知（没有 id）返回 null，表示不回。 */
    private JsonObject dispatch(JsonObject req) {
        JsonElement id = req.get("id");
        String method = req.has("method") ? req.get("method").getAsString() : "";

        // 通知没有 id，也不需要回。
        if (id == null || id.isJsonNull()) {
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> okResponse(id, initializeResult(req));
                case "ping" -> okResponse(id, new JsonObject());
                case "tools/list" -> okResponse(id, toolsListResult());
                case "tools/call" -> okResponse(id, toolsCallResult(req));
                default -> errorResponse(id, -32601, "method not found: " + method);
            };
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, "internal error: " + ex.getMessage());
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientProto = PROTOCOL_VERSION;
        if (req.has("params") && req.get("params").isJsonObject()) {
            JsonObject p = req.getAsJsonObject("params");
            if (p.has("protocolVersion")) {
                clientProto = p.get("protocolVersion").getAsString();
            }
            // 握手报的名字就是「谁接进来了」那一行。
            McpMode.instance().handshake(clientLabel(p));
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientProto);
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "numen-mcp");
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    /** {@code params.clientInfo} → "Claude Desktop 1.2.3"；没报名字就叫 unknown client。 */
    private static String clientLabel(JsonObject params) {
        if (!params.has("clientInfo") || !params.get("clientInfo").isJsonObject()) {
            return "unknown client";
        }
        JsonObject info = params.getAsJsonObject("clientInfo");
        String name = info.has("name") ? info.get("name").getAsString() : "unknown client";
        String version = info.has("version") ? info.get("version").getAsString() : "";
        return version.isBlank() ? name : name + " " + version;
    }

    // ---- tools/list ----

    private JsonObject toolsListResult() {
        JsonArray tools = new JsonArray();

        tools.add(toolDef("list_companions",
                "List the owner's live Minecraft companions (name + id). Call this first to see who you can drive.",
                objectSchema(null, false)));
        tools.add(toolDef("create_companion",
                "Summon a new companion into the world by name (3–16 letters, digits, or underscore). It arrives "
                        + "in a moment; call list_companions to confirm, then drive it directly — no 'take control' step. "
                        + "The companion belongs to the first player currently online.",
                requiredStringSchema("name",
                        "The new companion's name (3–16 letters, digits, or underscore).")));
        tools.add(toolDef("delete_companion",
                "Permanently dismiss a companion — it drops its inventory and is gone for good. "
                        + "Takes its name or id.",
                requiredStringSchema("companion",
                        "Which companion to dismiss — its name or id (see list_companions).")));
        tools.add(toolDef("get_events",
                "Long-poll the companion's inbox: returns the moment something urgent arrives (the owner "
                        + "speaking to it — via `/numen tell <name> <text>` — shows up as a <query>; world "
                        + "happenings as <event>s), or whatever "
                        + "accumulated once wait_seconds runs out. Events are consumed on read and nothing is "
                        + "lost between calls — call this about every 2 seconds while you drive the companion.",
                getEventsSchema()));
        tools.add(toolDef("say",
                "Speak as the companion: the text appears in-game as its chat line. Use it to answer the "
                        + "owner's <query> events and to narrate what you are doing.",
                saySchema()));

        // 全量给外脑，不做渐进披露：那是我们自己请求里的省法，外脑的上下文预算归它自己管。
        for (IToolSpec tool : com.dwinovo.numen.core.agent.tool.ToolRunner.specs()) {
            if (DEDICATED_SAY.equals(tool.name()) || config.isHidden(tool.name())) {
                continue;
            }
            tools.add(toolDef(tool.name(), tool.description(), withCompanion(tool.parameterSchema())));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolDef(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", inputSchema);
        return t;
    }

    /** 一个只带可选 {@code companion} 字符串属性的对象 schema。 */
    private JsonObject objectSchema(String companionKey, boolean required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        if (companionKey != null) {
            props.add(companionKey, companionProp());
        }
        schema.add("properties", props);
        if (companionKey != null && required) {
            JsonArray req = new JsonArray();
            req.add(companionKey);
            schema.add("required", req);
        }
        return schema;
    }

    /** 一个只带一个必填字符串属性的对象 schema。 */
    private JsonObject requiredStringSchema(String key, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        props.add(key, prop);
        schema.add("properties", props);
        JsonArray req = new JsonArray();
        req.add(key);
        schema.add("required", req);
        return schema;
    }

    /** 引擎工具自己的 schema，再注入一个必填的 {@code companion}。 */
    private JsonObject withCompanion(java.util.Map<String, Object> parameterSchema) {
        JsonObject schema = parameterSchema == null
                ? new JsonObject()
                : gson.toJsonTree(parameterSchema).getAsJsonObject();
        schema.addProperty("type", "object");
        JsonObject props = schema.has("properties") && schema.get("properties").isJsonObject()
                ? schema.getAsJsonObject("properties") : new JsonObject();
        props.add("companion", companionProp());
        schema.add("properties", props);
        JsonArray required = schema.has("required") && schema.get("required").isJsonArray()
                ? schema.getAsJsonArray("required") : new JsonArray();
        boolean has = false;
        for (JsonElement e : required) {
            if ("companion".equals(e.getAsString())) {
                has = true;
            }
        }
        if (!has) {
            required.add("companion");
        }
        schema.add("required", required);
        return schema;
    }

    private JsonObject companionProp() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "string");
        p.addProperty("description", "Which companion to act with — its name or id (see list_companions).");
        return p;
    }

    private JsonObject getEventsSchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject wait = new JsonObject();
        wait.addProperty("type", "integer");
        wait.addProperty("description", "How long to wait for something urgent before returning, in seconds "
                + "(0–" + MAX_WAIT_SECONDS + ", default " + DEFAULT_WAIT_SECONDS + "). 0 = return at once. "
                + "Leave it unset for the normal drive loop; raise it only to park and wait for the owner.");
        schema.getAsJsonObject("properties").add("wait_seconds", wait);
        return schema;
    }

    private JsonObject saySchema() {
        JsonObject schema = objectSchema("companion", true);
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        text.addProperty("description", "What the companion says out loud.");
        schema.getAsJsonObject("properties").add("text", text);
        schema.getAsJsonArray("required").add("text");
        return schema;
    }

    // ---- tools/call ----

    private JsonObject toolsCallResult(JsonObject req) {
        JsonObject params = req.has("params") && req.get("params").isJsonObject()
                ? req.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();

        JsonObject out = dispatchToolCall(name, args);
        recordActivity(name, args, out);
        return out;
    }

    /**
     * 动作行进日志。对话面的两个工具不记：{@code say} 自己就是一句话，
     * {@code get_events} 是轮询心跳，记了全是噪音。
     */
    private void recordActivity(String name, JsonObject args, JsonObject out) {
        if ("get_events".equals(name) || "say".equals(name)) {
            return;
        }
        String argsLine = argsSummary(args);
        Constants.info("[numen-mcp] 调用 {}{} → {}", name, argsLine.isBlank() ? "" : "  " + argsLine,
                truncate(textOf(out), 200));
    }

    private JsonObject dispatchToolCall(String name, JsonObject args) {
        try {
            return switch (name) {
                case "list_companions" -> content(listCompanions(), false);
                case "create_companion" -> handleCreate(args);
                case "delete_companion" -> handleDelete(args);
                case "get_events" -> handleGetEvents(args);
                case "say" -> handleSay(args);
                default -> handleToolInvoke(name, args);
            };
        } catch (TimeoutException te) {
            return content("timed out waiting for the action to finish", true);
        } catch (Exception ex) {
            return content("call failed: " + ex.getMessage(), true);
        }
    }

    /** 参数压成一行 {@code companion=Alice, count=5}；过长截断。 */
    private static String argsSummary(JsonObject args) {
        StringBuilder sb = new StringBuilder();
        for (var entry : args.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            JsonElement v = entry.getValue();
            String text = v.isJsonPrimitive() ? v.getAsString() : v.toString();
            sb.append(entry.getKey()).append('=').append(truncate(text, 30));
            if (sb.length() > 120) {
                break;
            }
        }
        return truncate(sb.toString(), 120);
    }

    private static String textOf(JsonObject result) {
        if (!result.has("content") || !result.get("content").isJsonArray()) {
            return "";
        }
        JsonArray arr = result.getAsJsonArray("content");
        if (arr.size() == 0 || !arr.get(0).isJsonObject()) {
            return "";
        }
        JsonObject first = arr.get(0).getAsJsonObject();
        return first.has("text") ? first.get("text").getAsString().replace('\n', ' ') : "";
    }

    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    private String listCompanions() throws Exception {
        List<McpActuator.Companion> list = McpActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (list.isEmpty()) {
            return "No companions are live in the world right now. Summon one with create_companion, "
                    + "or in-game with /numen summon <name>.";
        }
        StringBuilder sb = new StringBuilder("Live companions:\n");
        for (McpActuator.Companion c : list) {
            sb.append("- ").append(c.name()).append("  (id: ").append(c.uuid()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    private JsonObject handleCreate(JsonObject args) throws Exception {
        String name = args.has("name") && !args.get("name").isJsonNull()
                ? args.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            return content("create_companion needs a 'name' (3–16 letters, digits, or underscore)", true);
        }
        boolean ok = McpActuator.create(name).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!ok) {
            return content("could not summon — is the game in a world with a player online? "
                    + "(a companion needs an owner)", true);
        }
        return content("summoning '" + name + "' — it arrives in a moment; call list_companions to confirm, "
                + "then drive it directly (no acquire needed)", false);
    }

    private JsonObject handleDelete(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("no such companion — call list_companions to see valid names/ids", true);
        }
        boolean ok = McpActuator.delete(target).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "dismissed " + target + " — it drops its inventory and is gone for good"
                : "could not dismiss " + target, !ok);
    }

    /**
     * 长轮询取件：停靠在队列的急件叫醒挂点上，急件一到立即整批返回；到点清扫，
     * 有什么给什么，没有就如实说没有。叫醒只是提前，正确性由到点清扫兜底。
     */
    private JsonObject handleGetEvents(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("get_events needs a 'companion' argument (a name or id from list_companions)", true);
        }
        int wait = DEFAULT_WAIT_SECONDS;
        if (args.has("wait_seconds") && !args.get("wait_seconds").isJsonNull()) {
            wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, args.get("wait_seconds").getAsInt()));
        }
        if (wait > 0) {
            McpActuator.EventWait parked = McpActuator.awaitUrgent(target);
            try {
                String batch = parked.batch().get(wait, TimeUnit.SECONDS);
                if (batch != null) {
                    return content(batch, false);
                }
            } catch (TimeoutException deadline) {
                // 到点，转清扫——叫醒只是提前，正确性由清扫兜底
            } finally {
                parked.cancel().run();
            }
        }
        String rest = McpActuator.takeEvents(target)
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(rest != null ? rest
                : "(no new events — keep polling; the owner's words and world happenings land here)", false);
    }

    private JsonObject handleSay(JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("say needs a 'companion' argument (a name or id from list_companions)", true);
        }
        String text = args.has("text") && !args.get("text").isJsonNull()
                ? args.get("text").getAsString().trim() : "";
        if (text.isEmpty()) {
            return content("say needs a non-empty 'text'", true);
        }
        boolean ok = McpActuator.say(target, text).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return content(ok ? "said" : "could not say — is the companion live?", !ok);
    }

    private JsonObject handleToolInvoke(String toolName, JsonObject args) throws Exception {
        UUID target = resolveCompanion(args);
        if (target == null) {
            return content("this tool needs a 'companion' argument (a name or id from list_companions)", true);
        }
        // 手抄一份再删 companion：JsonElement.deepCopy() 是 gson 2.8.2 才公开的，
        // 而 1.16.5 的类路径上是 2.8.0。浅拷够用——我们只删一个键。
        JsonObject toolArgs = new JsonObject();
        for (var entry : args.entrySet()) {
            toolArgs.add(entry.getKey(), entry.getValue());
        }
        toolArgs.remove("companion");
        String result = McpActuator.invoke(target, toolName, toolArgs.toString())
                .get(config.callTimeoutSeconds(), TimeUnit.SECONDS);
        // 外接大脑读到的和内置大脑读到的是同一份：结果的 message，不是它的结构化数据
        return content(ToolOutcome.modelText(result), ToolOutcome.failed(result));
    }

    /** 把 {@code companion} 参数（名字或 UUID）解成在线同伴的 UUID；解不出返回 null。 */
    private UUID resolveCompanion(JsonObject args) throws Exception {
        if (!args.has("companion") || args.get("companion").isJsonNull()) {
            return null;
        }
        String raw = args.get("companion").getAsString().trim();
        if (raw.isEmpty()) {
            return null;
        }
        List<McpActuator.Companion> list = McpActuator.companions()
                .get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        for (McpActuator.Companion c : list) {           // 先认 id
            if (c.uuid().toString().equalsIgnoreCase(raw)) {
                return c.uuid();
            }
        }
        for (McpActuator.Companion c : list) {           // 再认名字（精确 → 忽略大小写）
            if (c.name().equals(raw)) {
                return c.uuid();
            }
        }
        for (McpActuator.Companion c : list) {
            if (c.name().equalsIgnoreCase(raw)) {
                return c.uuid();
            }
        }
        return null;
    }

    // ---- 结果信封 ----

    private JsonObject content(String text, boolean isError) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        JsonArray arr = new JsonArray();
        arr.add(item);
        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private JsonObject okResponse(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id == null ? JsonNull.INSTANCE : id);
        r.add("error", err);
        return r;
    }
}
