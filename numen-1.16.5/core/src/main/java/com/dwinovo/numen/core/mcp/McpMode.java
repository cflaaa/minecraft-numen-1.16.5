package com.dwinovo.numen.core.mcp;

import com.dwinovo.numen.core.Constants;

import java.nio.file.Path;

/**
 * 「外接大脑」模式的唯一状态源——内置大脑闸门与 HTTP 服务器两方共读的一处真相。
 *
 * <p>照搬上游 {@code api/common/src/client/.../mcp/server/McpMode.java}，改了两处：
 * 上游是客户端面板 + 客户端 tick 驱动，本仓没有面板、由<b>服务端 tick</b> 驱动，
 * 失联播报从「聊天栏弹一条」退化成「日志里写一行」。
 *
 * <h2>模式 = 服务器开关</h2>
 * 不另立「模式」概念：{@link McpConfig#enabled} 开着就是模式开着，服务器就在跑。
 * 这一版没有设置面板，开关直接改 {@code config/numen/mcp_server.json}，<b>重进世界生效</b>。
 *
 * <h2>谁在驱动这具身体：{@link #driving()}</h2>
 * 「两个大脑抢一具身体」的总闸口径：外脑驱动期间内置大脑一轮都不开
 * （内核每次推进都现问 {@code HostPort.externallyDriven()}，在它手里时停牌 {@code EXTERNAL}）。
 * 主人在游戏里照样说话——话进事件队列，由外脑经 {@code get_events} 取走并用 {@code say} 回话。
 * 身体层面的互斥另有「一具身体一件活」兜底，两者各管一层。
 *
 * <p>失联回退（{@link McpConfig#quietFallback}）：开着时外脑安静超过
 * {@value #QUIET_AFTER_MS} 毫秒就视作不在场，{@code driving()} 翻假、内置大脑接管；
 * 外脑一有动静立即交还。默认关——主人开这个模式是刻意的，她安静待命。
 *
 * <h2>线程</h2>
 * {@link #enabled()} / {@link #driving()} 被服务端主线程与 HTTP 线程两边读，故用 volatile 裸字段；
 * 握手信息由 HTTP 线程写入、服务端主线程读。
 */
public final class McpMode {

    private static final McpMode INSTANCE = new McpMode();

    /**
     * 外脑安静多久算「不在场」（失联播报与 quietFallback 的同一判据）。
     *
     * <p>{@code get_events} 长轮询最长 50 秒，正常在场的外脑请求间隔不会超过它——
     * 两倍再留余量，误报比漏报伤：错误的「失联」会让内脑抢答。
     */
    public static final long QUIET_AFTER_MS = 120_000L;

    private volatile boolean enabled;
    private volatile String clientName;      // initialize 握手报的对方名字，null = 还没人连过
    private volatile long lastActivityMs;
    private volatile String lastError;       // 起服失败的原因
    private boolean announcedQuiet;          // 失联已播报过（边沿检测，只在主线程碰）

    private Path configFile;
    private McpConfig config = McpConfig.disabledDefault();
    private McpServer server;

    private McpMode() {}

    public static McpMode instance() {
        return INSTANCE;
    }

    // ---- 生命周期 ----

    /**
     * 模组初始化时装载配置：记住配置文件位置，{@code enabled} 则立刻起服。
     *
     * <p>只调一次（{@code NumenCoreFabric.onInitialize}）。
     */
    public void bootstrap(Path file, McpConfig loaded) {
        this.configFile = file;
        this.config = loaded;
        if (loaded.enabled()) {
            startServer();
        } else {
            this.enabled = false;
            Constants.info("[numen-mcp] 外接大脑模式关闭（{} 里 enabled=false）", file);
        }
    }

    /**
     * 拨动开关：即时起停服务器并写回配置文件。
     *
     * @return 是否达成目标状态（起服失败时返回 false，原因见 {@link #lastError()}）
     */
    public boolean setEnabled(boolean on) {
        if (on == enabled) {
            return true;
        }
        if (on) {
            if (!startServer()) {
                return false;
            }
        } else {
            stopServer();
        }
        config = config.withEnabled(enabled);
        if (configFile != null) {
            config.save(configFile);
        }
        return true;
    }

    /** 当前配置的只读快照。 */
    public McpConfig config() {
        return config;
    }

    private boolean startServer() {
        lastError = null;
        McpServer s = new McpServer(config);
        try {
            s.start();
        } catch (Exception ex) {
            lastError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            Constants.error("[numen-mcp] 起服失败 {}:{} —— {}", config.host(), config.port(), ex.toString());
            enabled = false;
            return false;
        }
        server = s;
        enabled = true;
        Constants.info("[numen-mcp] 外接大脑模式已开启，端点 {}（令牌见配置文件；改动 host/port 要重进世界）", endpoint());
        return true;
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        enabled = false;
        clientName = null;
        Constants.info("[numen-mcp] 外接大脑模式已关闭，内置大脑恢复接管");
    }

    /** 服务器要关了：把 HTTP 服务器一起收掉，别留着线程。 */
    public void shutdown() {
        if (enabled) {
            stopServer();
        }
    }

    // ---- 状态查询（闸门） ----

    /** 模式（=服务器）是否开启；两脑仲裁读 {@link #driving()}。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 外脑此刻是否驱动着身体——内置大脑的开轮闸门读这一处口径。
     * 模式开着即驱动；仅当失联回退开着且外脑安静超时，才交还内脑。
     */
    public boolean driving() {
        return enabled && !(config.quietFallback() && quietNow());
    }

    /** 外脑安静超时（或从未有人连过）。 */
    private boolean quietNow() {
        return clientName == null || System.currentTimeMillis() - lastActivityMs > QUIET_AFTER_MS;
    }

    /**
     * 服务端每刻一次：失联/回归的边沿检测。
     *
     * <p>上游在这里往聊天栏弹一句（主人有知情权）。本仓没有面板，退化成日志一行——
     * 该说的事还是要说，只是说给日志听。
     */
    public void tick() {
        if (!enabled || clientName == null) {
            announcedQuiet = false;
            return;
        }
        boolean quiet = System.currentTimeMillis() - lastActivityMs > QUIET_AFTER_MS;
        if (quiet && !announcedQuiet) {
            announcedQuiet = true;
            Constants.warn("[numen-mcp] 外接大脑安静超过 {} 秒{}", QUIET_AFTER_MS / 1000,
                    config.quietFallback() ? "，内置大脑临时接管" : "，她安静待命（未开失联接管）");
        } else if (!quiet && announcedQuiet) {
            announcedQuiet = false;
            Constants.info("[numen-mcp] 外接大脑回来了，驾驶席交还");
        }
    }

    /** 拨「失联后内脑接管」开关：只写配置，即时生效（{@link #driving()} 现算）。 */
    public void setQuietFallback(boolean on) {
        config = config.withQuietFallback(on);
        if (configFile != null) {
            config.save(configFile);
        }
    }

    public String endpoint() {
        return "http://" + config.host() + ":" + config.port() + "/mcp";
    }

    public String token() {
        return config.token();
    }

    /**
     * 打码后的令牌，给日志/截图看——明文 token 不上屏。
     * 要用整份的地方直接读配置文件。
     */
    public String maskedToken() {
        String t = config.token();
        if (t == null || t.isBlank()) {
            return "";
        }
        return t.length() <= 8 ? "•".repeat(t.length()) : t.substring(0, 8) + "•".repeat(6);
    }

    /** 最近一次 initialize 握手报上来的客户端名字，或 null（还没人接入）。 */
    public String clientName() {
        return clientName;
    }

    /** 最近一次收到请求的时刻（毫秒），0 = 从未。 */
    public long lastActivityMs() {
        return lastActivityMs;
    }

    /** 最近一次起服失败的原因，或 null。 */
    public String lastError() {
        return lastError;
    }

    /**
     * 「接入提示词」：内嵌当前端点与令牌，复制给 AI 就能让它自己配好本机 MCP。
     *
     * <p>上游由设置面板的复制按钮调它。本仓没有面板，留着这个出口给将来，
     * 也方便从日志/调试里直接拿到那段话。含明文令牌，别往外贴。
     */
    public String accessPrompt() {
        String ep = endpoint();
        String tk = token();
        return "本机有一个 Numen 的 MCP 服务端在跑（Minecraft 里的 AI 同伴）。请把下面这段配置写进你的 MCP 配置"
                + "（WorkBuddy 是 ~/.workbuddy-ai/mcp.json）：\n"
                + "{\"mcpServers\":{\"numen\":{\"url\":\"" + ep + "\",\"headers\":{\"Authorization\":\"Bearer " + tk + "\"}}}}\n"
                + "写好后让用户在连接器管理页对 numen 点「信任」。";
    }

    // ---- HTTP 线程回调 ----

    /** 收到任何请求都刷新活跃时刻（ping 也算——它正是客户端用来证明自己还在的）。 */
    void touch() {
        lastActivityMs = System.currentTimeMillis();
    }

    /** initialize 握手：记下对方是谁（"Claude Desktop 1.2.3"）。 */
    void handshake(String name) {
        clientName = name;
        touch();
    }
}
