package com.dwinovo.numen.core.entity;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.agent.CompanionBrain;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴的花名册与生命周期。
 *
 * <p>移植自上游 {@code api/entity/Companions.java} 的职责部分（上游那份还挂着征询、
 * 皮肤、跨维度跟随等，那些是后续阶段）。
 *
 * <p>花名册按 <b>身体 UUID</b> 索引，不按名字：名字只是给人看的，UUID 才和 playerdata、
 * 网络身份一一对应。身体没了（死亡遣散、服务器关）就顺手把大脑一起丢掉——
 * 这正是上游「状态跟着身体走、不进静态表」那条原则的最小实现。
 */
public final class Companions {

    private Companions() {}

    /** 身体 UUID → 大脑。一具身体一个大脑，身体走了大脑一起走。 */
    private static final Map<UUID, CompanionBrain> BRAINS = new LinkedHashMap<>();

    /** 死亡之后隔多久把她放回主人身边（毫秒）。 */
    private static final long RESPAWN_DELAY_MS = 5_000L;

    // ---- 召唤 / 遣散 ----

    /**
     * 在主人所在的位置召唤一只同伴。
     *
     * @return 新身体；重名时返回 {@code null}
     */
    public static NumenPlayer spawn(MinecraftServer server, ServerPlayer owner, String name) {
        if (findByName(server, name) != null) {
            return null;
        }
        ServerLevel level = owner.getLevel();
        NumenPlayer body = CompanionFactory.spawn(server, level, owner.getUUID(), name);

        // 落在主人身边（原版 placeNewPlayer 会按世界出生点安置，这里挪到主人脚边）。
        Vec3 at = owner.position();
        body.teleportTo(at.x, at.y, at.z);

        CompanionBrain brain = new CompanionBrain(body);
        BRAINS.put(body.getUUID(), brain);
        NumenCore.info("同伴 {} 已召唤，主人 {}", name, owner.getGameProfile().getName());
        return body;
    }

    /** 遣散一只同伴。 */
    public static boolean dismiss(MinecraftServer server, String name) {
        NumenPlayer body = findByName(server, name);
        if (body == null) {
            return false;
        }
        CompanionBrain brain = BRAINS.remove(body.getUUID());
        if (brain != null) {
            brain.halt("被主人遣散");
        }
        CompanionFactory.despawn(server, body);
        NumenCore.info("同伴 {} 已遣散", name);
        return true;
    }

    // ---- 查询 ----

    /** 按名字找已经加载的同伴身体；没有则 null。 */
    public static NumenPlayer findByName(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion
                    && companion.getGameProfile().getName().equalsIgnoreCase(name)) {
                return companion;
            }
        }
        return null;
    }

    /** 此刻世界里所有同伴。 */
    public static List<NumenPlayer> all(MinecraftServer server) {
        List<NumenPlayer> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion) {
                out.add(companion);
            }
        }
        return out;
    }

    /** 这具身体的大脑；没登记过则 null。 */
    public static CompanionBrain brain(NumenPlayer body) {
        return BRAINS.get(body.getUUID());
    }

    /** 按 UUID 找已经加载的同伴身体；没有则 null。给外接大脑（MCP）那条路用。 */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion && companion.getUUID().equals(uuid)) {
                return companion;
            }
        }
        return null;
    }

    /** 按 UUID 遣散一只同伴。 */
    public static boolean dismissByUuid(MinecraftServer server, UUID uuid) {
        NumenPlayer body = findByUuid(server, uuid);
        return body != null && dismiss(server, body.getGameProfile().getName());
    }

    /**
     * 第一个可以当主人的在线玩家（非同伴）；没有则 null。
     *
     * <p>给外接大脑的 {@code create_companion} 用：MCP 服务端开在<b>服务端</b>上，
     * 未必存在「发起召唤的那个玩家」，而同伴必须有归属。多个玩家在线时取玩家列表里的第一个——
     * 单机与小服够用；将来要做「选谁当主人」再改这一处。
     */
    public static ServerPlayer firstOwner(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer)) {
                return player;
            }
        }
        return null;
    }

    // ---- 每刻驱动 ----

    /** 服务端每刻调一次：让每只同伴的大脑往前走一步。 */
    public static void tick(MinecraftServer server) {
        if (BRAINS.isEmpty()) {
            return;
        }
        // 复制一份再遍历：大脑在这一步里可能把自己摘掉（死亡、被遣散）。
        for (CompanionBrain brain : new ArrayList<>(BRAINS.values())) {
            try {
                brain.tick();
            } catch (RuntimeException ex) {
                // 一只同伴的大脑炸了不能连累别人，更不能掀掉服务端主循环。
                NumenCore.error("同伴 {} 的大脑 tick 抛了异常，跳过这一步", brain.name(), ex);
            }
        }
    }

    // ---- 死亡善后 ----

    /**
     * 同伴死了。
     *
     * <p>假玩家不会被自动移出玩家列表（没有客户端来发重生包），会永远躺在 0 血，
     * 所以必须由我们接手：先停大脑，再<b>在原位重生</b>。
     *
     * <p>上游的做法是排一个延迟、把身体放回主人身边；这里先做最简单也最不惊悚的一种：
     * 原地复活并回满血。把「重生到主人身边 + 播报死因」留到后续阶段。
     */
    public static void onDeath(NumenPlayer body) {
        CompanionBrain brain = BRAINS.get(body.getUUID());
        if (brain != null) {
            brain.halt("身体死亡");
        }

        String why = body.deathMessage();
        NumenCore.info("同伴 {} 死亡：{}", body.getGameProfile().getName(),
                why == null ? "(未知)" : why);

        // 原地复活：这一代假玩家没有客户端，不会自己重生。
        // 细节与三个必须一起做的动作见 NumenPlayer#revive。
        body.revive();
    }

    /** 预留：死亡延迟重生的窗口长度，后续阶段接上「放回主人身边」时用。 */
    static long respawnDelayMs() {
        return RESPAWN_DELAY_MS;
    }
}
