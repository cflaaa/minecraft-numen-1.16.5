package com.dwinovo.numen.core.mcp;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.entity.Companions;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 「<mcp> 自检」——玩家在聊天栏发 {@code <mcp>}，服务端自己体检一遍外接大脑的接入条件，
 * 然后把"直接可粘"的接入材料交给客户端：一段给 agent 的提示词（照上游 {@code McpAccessPrompt}
 * 的思路内嵌端点与令牌，用户零填空）、外加一条 pi 的一行接入命令。
 *
 * <h2>自检查什么</h2>
 * <ul>
 *   <li>外接大脑开没开（{@link McpMode#enabled}）；</li>
 *   <li>端口真在听吗（本机 socket 连一发，HTTP 起没起一测便知）；</li>
 *   <li>从外面该用哪个地址（枚举本机局域网 IPv4；端点一定用真实 IP，
 *       {@code 0.0.0.0} 连不上）；</li>
 *   <li>现在有几只同伴（agent 接上后第一件事就是看/召唤）。</li>
 * </ul>
 *
 * <p>令牌是明文进出这里与剪贴板的：所以这份数据<b>只发给有 2 级权限的人</b>
 * （面板通道的既有闸门），且客户端只把它进剪贴板（上游同款决定：「只走剪贴板、不上屏」）。
 */
public final class McpSetup {

    private McpSetup() {}

    /**
     * 做一遍自检并打包。**必须在服务端主线程上调用**（要读玩家列表）。
     *
     * @return 形如：
     * <pre>
     * { "enabled": bool, "bound": bool, "host": str, "port": int, "token": str,
     *   "lan": "192.168.1.5, …", "endpoints": [url…], "endpoint": url,
     *   "companions": int, "oneliner": str, "prompt": str }
     * </pre>
     */
    public static JsonObject build(MinecraftServer server) {
        McpMode mode = McpMode.instance();
        McpConfig config = mode.config();
        boolean enabled = mode.enabled();
        int port = config.port();
        String host = config.host();
        boolean bound = enabled && listening(McpConfig.ANY_HOST.equals(host) || host.isBlank()
                ? McpConfig.LOOPBACK : host, port);

        // 局域网 IPv4：agent 大概率在另一台机器上，得给它能连上的地址
        List<String> lan = lanIps();
        List<String> endpoints = new ArrayList<>();
        for (String ip : lan) {
            endpoints.add("http://" + ip + ":" + port + "/mcp");
        }
        String loopback = "http://" + McpConfig.LOOPBACK + ":" + port + "/mcp";
        String endpoint = endpoints.isEmpty() ? loopback : endpoints.get(0);

        String token = config.token() == null ? "" : config.token();
        int companions = server == null ? 0 : Companions.all(server).size();

        JsonObject out = new JsonObject();
        out.addProperty("enabled", enabled);
        out.addProperty("bound", bound);
        out.addProperty("host", host);
        out.addProperty("port", port);
        out.addProperty("token", token);
        out.addProperty("lan", String.join(", ", lan));
        JsonArray eps = new JsonArray();
        for (String e : endpoints) {
            eps.add(e);
        }
        if (endpoints.isEmpty()) {
            eps.add(loopback);
        }
        out.add("endpoints", eps);
        out.addProperty("endpoint", endpoint);
        out.addProperty("companions", companions);
        out.addProperty("oneliner", oneliner(endpoint, token));
        out.addProperty("prompt", prompt(endpoint, token));

        Constants.info("[numen-mcp] <mcp> 自检：enabled={} bound={} endpoint={} companions={}",
                enabled, bound, endpoint, companions);
        return out;
    }

    /** 端口真在听吗：本机连一枪，400ms 超时。 */
    private static boolean listening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 本机所有"非回环"IPv4 地址，能连的都给。 */
    private static List<String> lanIps() {
        List<String> out = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                    nis.hasMoreElements(); ) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                for (Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                        addrs.hasMoreElements(); ) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到网卡就只给回环地址，提示词里会照实说
        }
        return out;
    }

    /** pi 的一行接入命令：粘到装了 pi 的那台机器的 shell 里就能用。 */
    private static String oneliner(String endpoint, String token) {
        return "export NUMEN_MCP_TOKEN='" + token + "'; "
                + "pi mcp add numen --url " + endpoint
                + " --bearer-token-env-var NUMEN_MCP_TOKEN && pi mcp list";
    }

    /**
     * 给 agent 的整段话（照上游 {@code McpAccessPrompt} 的骨架，按本仓"服务端 mod + 外脑"改写）：
     * 说是"我的服务器"，教它认自己是什么客户端、怎么配、怎么验收、怎么开一只同伴、怎么驱动她。
     * 用英文写（指令遵循度最稳），结尾叮嘱它用用户的语言回话。端点与令牌内嵌，零填空。
     */
    private static String prompt(String endpoint, String token) {
        String auth = "every request must carry the header `Authorization: Bearer " + token + "`";
        return """
                I'm playing Minecraft with a mod called Numen, and I want you to connect to it.

                ## What Numen is

                Numen puts AI-controlled, player-like companions into my live Minecraft server. It exposes \
                an MCP (Model Context Protocol) server, so an external AI — you — can take over a \
                companion's body and play the game as it: perceive the world, move, mine, build, craft, \
                fight, use containers.

                ## Your task

                Set up the MCP connection, then confirm it works. Here are the details:

                - MCP endpoint: %s
                - Auth token: `%s` — %s
                - The endpoint lives on my Minecraft server machine (it may be another box on my LAN); \
                use the endpoint exactly as given.

                ### Step 1 — figure out which client you're running in

                You might be the pi coding agent, Claude Code, Cursor, Cline, Windsurf, or something else. \
                Work out which one you are and where its MCP config lives.

                - If you are **pi**: have a shell on your machine, then run exactly:
                  `export NUMEN_MCP_TOKEN='%s'`
                  `pi mcp add numen --url %s --bearer-token-env-var NUMEN_MCP_TOKEN`
                  `pi mcp list`   ← it must show numen connected, 19 tools
                - If you dial streamable-HTTP MCP servers directly, add this to your `mcp.json` \
                (merge, don't overwrite existing entries):
                  { "mcpServers": { "numen": { "url": "%s", "headers": { "Authorization": "Bearer %s" } } } }
                - If you only support stdio servers (Claude Desktop today), bridge with mcp-remote: \
                command `npx`, args `["-y","mcp-remote","%s","--header","Authorization: Bearer %s"]`.

                ### Step 2 — verify

                Call `list_companions`. If no companion is live, call `create_companion` with a name \
                (3–16 letters, digits, or underscore), then `list_companions` again to confirm it arrived, \
                and `get_status` to see where it is.

                ## How to drive a companion once you're in

                - Every tool takes a `companion` argument (name or id), so each call targets one body. \
                There is no take-control handshake — just call tools.
                - Poll `get_events(companion, wait_seconds=2)` while you drive it: it returns at once when \
                something urgent lands. The player talking to the companion in-game arrives as a query; \
                world happenings arrive as events. Events are consumed on read.
                - Reply and narrate with `say(companion, text)` — the words appear in-game as the \
                companion's chat line. Keep your own conversation history; the game stores none for you.
                - The `lua` tool runs a program whose functions are the companion's API: \
                `numen.status.self()`, `numen.scan.blocks("iron_ore")`, `numen.move.to({x=120,y=12,z=-35})`, \
                `numen.work.mine(cluster)`, `numen.fight.attack(entity)`, `numen.inv.craft(...)` … \
                `numen.api.help("numen")` lists the groups, `numen.api.help("numen.work")` one group's \
                functions. One call is a one-line program; chain steps in one program when the next step \
                depends on what the last returned.
                - You're blind between calls: perceive with `numen.status.self()` / \
                `numen.scan.blocks` / `numen.scan.entities` before and after acting.
                - It's survival mode — the API does only what a real player can. No give, no setblock.

                One more thing: talk to me in the language I'm writing to you in, even though these \
                instructions are in English.""".formatted(
                endpoint,
                token,
                auth,
                token,
                endpoint,
                endpoint,
                token,
                endpoint,
                token);
    }
}
