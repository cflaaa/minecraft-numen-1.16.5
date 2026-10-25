package com.dwinovo.numen.core.net;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.command.NumenCommand;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.inventory.CompanionInventoryMenu;
import com.dwinovo.numen.core.llm.ModelCatalog;
import com.dwinovo.numen.core.llm.ModelCatalogState;
import com.dwinovo.numen.core.mcp.McpMode;
import com.dwinovo.numen.core.mcp.McpSetup;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * G 面板的服务端一侧：收客户端的请求，在服务端主线程上执行，再把新快照回过去。
 *
 * <p><b>权限与它替代的命令完全一致</b>：{@code /numen} 那组命令要 2 级权限（单人里就是「开了作弊」），
 * 面板也一样。理由是这些操作动的是世界和全局配置（配置里有 API key），
 * 面板只是一个更好按的入口，不该比命令行更宽松。
 *
 * <p><b>线程</b>：Fabric 的 play 通道回调跑在 netty 线程上，而同伴花名册、配置单例
 * 都只许在服务端主线程上碰。所以收到包先 {@code server.execute(...)} 跳过去，再干活、再回包。
 */
public final class NumenPanelServer {

    private NumenPanelServer() {}

    /** 在模组初始化时调一次（客户端与专用服务端都会走到这里，这是对的）。 */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(NumenPanelNet.CHANNEL,
                (server, player, handler, buf, sender) -> {
                    // 在 netty 线程上把字符串读出来（buf 出了回调就失效了），再跳主线程。
                    String json;
                    try {
                        json = buf.readUtf(32767);
                    } catch (RuntimeException ex) {
                        Constants.warn("[numen-panel] 收包读不出来：{}", ex.toString());
                        return;
                    }
                    server.execute(() -> handle(server, player, json));
                });
        Constants.info("[numen-panel] G 面板通道已注册");
    }

    private static void handle(MinecraftServer server, ServerPlayer player, String json) {
        JsonObject req = NumenPanelNet.parse(json);
        String op = NumenPanelNet.str(req, "op", NumenPanelNet.OP_LIST);

        // hello / list 只是「刷新一下」，不改任何东西，任何玩家都能要。
        if (NumenPanelNet.OP_HELLO.equals(op) || NumenPanelNet.OP_LIST.equals(op)) {
            reply(player, NumenPanelNet.snapshot(server, null, true));
            return;
        }

        if (!player.hasPermissions(2)) {
            reply(player, NumenPanelNet.snapshot(server,
                    "没有权限：面板与 /numen 命令一样要 2 级权限（单人世界里就是「开启作弊」）", false));
            return;
        }

        String note;
        boolean ok = true;
        switch (op) {
            case NumenPanelNet.OP_SUMMON -> {
                String name = NumenPanelNet.str(req, "name", "").trim();
                note = summon(server, player, name);
                ok = note == null;
                if (ok) {
                    note = "召唤了 " + name;
                }
            }
            case NumenPanelNet.OP_DISMISS -> {
                String name = NumenPanelNet.str(req, "name", "").trim();
                ok = Companions.dismiss(server, name);
                note = ok ? "遣散了 " + name : "没有叫 " + name + " 的同伴";
            }
            case NumenPanelNet.OP_TELL -> {
                String name = NumenPanelNet.str(req, "name", "").trim();
                String text = NumenPanelNet.str(req, "text", "");
                note = tell(server, name, text);
                ok = note == null;
                if (ok) {
                    note = "已转达给 " + name;
                }
            }
            case NumenPanelNet.OP_OPEN_INV -> {
                // G 键「打开同伴的背包」（#0027）：准星点着谁就开谁的（客户端把名字带上）；
                // 没点名或点名不认识，就开 16 格内最近的一只。背包是原版容器那套，
                // 服务端这半把槽挂在她真格的 PlayerInventory 上——放进去的她真背着。
                String name = NumenPanelNet.str(req, "name", "").trim();
                note = openCompanionInventory(server, player, name);
                ok = note == null;
            }
            case NumenPanelNet.OP_CONFIG -> {
                String key = NumenPanelNet.str(req, "key", "");
                String value = NumenPanelNet.str(req, "value", "");
                note = config(key, value);
                ok = note == null;
                if (ok) {
                    note = "已设置 " + key + ("apiKey".equals(key) ? "（不回声）" : " = " + value);
                }
            }
            case NumenPanelNet.OP_MCP -> {
                boolean on = NumenPanelNet.integer(req, "value", 0) != 0;
                McpMode.instance().setEnabled(on);
                note = on ? "外接大脑已开启" : "外接大脑已关闭";
            }
            case NumenPanelNet.OP_MCP_SETUP -> {
                // 聊天栏 <mcp>：服务端自检（外接大脑开没开/端口听没听/该用哪个地址），
                // 把接入提示词与一行命令一并回给客户端——它只进剪贴板，不上屏广播。
                reply(player, NumenPanelNet.snapshot(server, "MCP 自检完成", true, McpSetup.build(server)));
                return;
            }
            case NumenPanelNet.OP_MODELS -> {
                // 异步：立刻回一份「正在拉」的快照（面板显示「获取中…」），
                // 拉完了再推一份带结果的。不能在这里阻塞主线程——那会把整个服务器卡住。
                startModelFetch(server, player, req);
                return;
            }
            default -> {
                note = "不认识的请求：" + op;
                ok = false;
            }
        }
        reply(player, NumenPanelNet.snapshot(server, note, ok));
    }

    private static void reply(ServerPlayer player, JsonObject snapshot) {
        // 对面可能是原版客户端（没装这个模组），那就别发——发了会在日志里刷一堆
        // "unknown custom payload" 之类的噪声。
        if (!ServerPlayNetworking.canSend(player, NumenPanelNet.CHANNEL)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(snapshot.toString());
        ServerPlayNetworking.send(player, NumenPanelNet.CHANNEL, buf);
    }

    /**
     * 拉取模型清单。**立刻返回**（回一份「正在拉」的快照），真正的网络请求在别的线程上跑，
     * 拿到结果后再推一份快照过去。
     *
     * <p>为什么不能在这里同步等：这是服务端主线程，一次 HTTP 往返几百毫秒到几十秒，
     * 等着的这段时间整个服务器（所有玩家、所有生物）都是冻住的。
     *
     * <p>为什么要立刻回一份：面板点了按钮之后要有反馈，否则玩家以为没点上、连点好几下。
     *
     * <p>请求用的是**面板里此刻填的值**（{@code req} 里带的），不是配置文件里的——
     * 玩家刚填完 key 还没点保存，这时候点「获取模型」也该能成功。
     */
    private static void startModelFetch(MinecraftServer server, ServerPlayer player, JsonObject req) {
        String baseUrl = NumenPanelNet.str(req, "baseUrl", "");
        String apiKey = NumenPanelNet.str(req, "apiKey", "");
        String provider = NumenPanelNet.str(req, "provider", "");
        if (!provider.isBlank()) {
            NumenConfig cfg = NumenConfig.get();
            cfg.provider = provider;
            // provider 与 baseUrl 一起落盘：玩家切站点时，端点几乎总得跟着换。
            if (!baseUrl.isBlank()) {
                cfg.baseUrl = baseUrl;
            }
            NumenConfig.save();
        }

        ModelCatalogState.begin();
        reply(player, NumenPanelNet.snapshot(server, "正在获取模型列表…", true));

        java.util.UUID playerId = player.getUUID();
        ModelCatalog.fetch(baseUrl, apiKey, server,
                (models, error) -> {
                    if (error != null) {
                        ModelCatalogState.failed(error);
                    } else {
                        ModelCatalogState.succeeded(models);
                    }
                    // 回调保证在服务端主线程上，可以安全地找玩家、组快照。
                    ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                    if (p != null) {
                        reply(p, NumenPanelNet.snapshot(server,
                                error != null ? "获取模型失败：" + error
                                        : "拿到 " + models.size() + " 个模型，点一个填进「模型」",
                                error == null));
                    }
                });
    }

    // ---- 各操作。返回 null = 成功；返回字符串 = 给玩家看的失败原因 ----

    private static String summon(MinecraftServer server, ServerPlayer player, String name) {
        if (!NumenCommand.validName(name)) {
            return "名字要 3~16 位，只能用字母、数字、下划线";
        }
        if (Companions.findByName(server, name) != null) {
            return "已经有一只叫 " + name + " 的同伴了";
        }
        // 面板这一侧知道是谁点的，就直接让发起者当主人——比 /numen 的
        // 「控制台/命令源」更准，也比 MCP 那条只能猜 firstOwner 的路更准。
        NumenPlayer body = Companions.spawn(server, player, name);
        if (body == null) {
            return "召唤失败";
        }
        String missing = NumenConfig.get().missingReason();
        if (missing != null) {
            Constants.warn("[numen-panel] 同伴 {} 已召唤，但还不能干活：{}", name, missing);
        }
        return null;
    }

    private static String tell(MinecraftServer server, String name, String text) {
        if (text == null || text.isBlank()) {
            return "要说点什么";
        }
        NumenPlayer body = Companions.findByName(server, name);
        if (body == null) {
            return "没有叫 " + name + " 的同伴";
        }
        CompanionBrain brain = Companions.brain(body);
        if (brain == null) {
            return "这只同伴没有大脑（可能正在重生）";
        }
        String missing = brain.unavailableReason();
        if (missing != null) {
            return missing;
        }
        brain.tell(text);
        return null;
    }

    /**
     * G 键「打开同伴的背包」（#0027）：返回 null = 已打开（开容器的包已经发过去了）；
     * 返回字符串 = 给玩家看的失败原因。点名找不到或没点名，就取 16 格内最近的一只。
     */
    private static String openCompanionInventory(MinecraftServer server, ServerPlayer player, String name) {
        NumenPlayer body = name.isBlank() ? null : Companions.findByName(server, name);
        if (body == null) {
            double best = 16 * 16;
            for (NumenPlayer c : Companions.all(server)) {
                double d = player.distanceToSqr(c);
                if (d < best) {
                    best = d;
                    body = c;
                }
            }
        }
        if (body == null) {
            return "附近没有同伴（16 格内）";
        }
        final NumenPlayer companion = body;   // 交给 λ 要 effectively final
        // 扩展开屏：除了菜单本体，还塞进她的实体 id——客户端拿它画角色模型。
        player.openMenu(new ExtendedScreenHandlerFactory() {
            @Override
            public void writeScreenOpeningData(ServerPlayer opener, net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeVarInt(companion.getId());
            }

            @Override
            public net.minecraft.network.chat.Component getDisplayName() {
                return new TextComponent(companion.getName().getString() + " 的背包");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int id, net.minecraft.world.entity.player.Inventory inv,
                    net.minecraft.world.entity.player.Player opener) {
                return new CompanionInventoryMenu(id, inv, companion.inventory, companion, companion.getId());
            }
        });
        return null;
    }

    private static String config(String key, String value) {
        NumenConfig cfg = NumenConfig.get();
        switch (key) {
            case "apiKey" -> cfg.apiKey = value;
            case "model" -> cfg.model = value;
            case "baseUrl" -> cfg.baseUrl = value;
            case "provider" -> cfg.provider = value;
            case "proxy" -> cfg.proxy = value;
            case "persona" -> cfg.persona = value;
            case "initiative" -> {
                try {
                    cfg.initiative = Integer.parseInt(value.trim());
                } catch (NumberFormatException ex) {
                    return "initiative 要 1~10 的整数";
                }
                if (cfg.initiative < 1) {
                    cfg.initiative = 1;
                }
                if (cfg.initiative > 10) {
                    cfg.initiative = 10;
                }
            }
            default -> {
                return "不认识的配置项：" + key;
            }
        }
        NumenConfig.save();
        Constants.info("[numen-panel] 配置 {} 已更新", key);
        return null;
    }
}
