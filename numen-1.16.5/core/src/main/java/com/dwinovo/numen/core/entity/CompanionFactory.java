package com.dwinovo.numen.core.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 造一具同伴身体。
 *
 * <p>移植自上游 {@code api/entity/CompanionFactory.java}。
 *
 * <h2>身份是稳定的</h2>
 * 每个同伴有一个由「名字」推出来的固定 UUID，名字不变则身体重新召唤后 UUID 也不变——
 * 于是她自己的 playerdata（背包、经验、位置）会落在同一个 {@code .dat} 上，休眠再回来还是同一个人。
 * 用的是原版离线模式的同一套算法（{@code OfflinePlayer:<name>}），所以和「离线服里的玩家」同构。
 *
 * <h2>怎么把她放进世界</h2>
 * 走 {@code PlayerList.placeNewPlayer(Connection, ServerPlayer)}——和真玩家登录同一个入口。
 * 这样区块加载、实体跟踪、playerdata 读写、玩家列表事件全都白拿。
 * 传进去的 {@link FakeConnection} 把出站包全丢掉，因为对面没有客户端。
 */
public final class CompanionFactory {

    private CompanionFactory() {}

    /** 名字 → 稳定的离线式 UUID。 */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 在 {@code level} 里召唤一只同伴。
     *
     * @param ownerUuid 主人的 UUID；此后归属判断只比这个
     * @param name      同伴的名字（3~16 位字母数字下划线，调用方负责校验）
     * @return 已经进入玩家列表的身体
     */
    public static NumenPlayer spawn(MinecraftServer server, ServerLevel level, UUID ownerUuid, String name) {
        UUID companionUuid = offlineUuid(name);
        GameProfile profile = new GameProfile(companionUuid, name);
        // 借来的正版皮肤(Mojang 签名的 textures,注册表持久化)注入档案——客户端只认
        // 签过名的皮肤数据;没有则回落原版默认皮肤(按 UUID 哈希抽取)。上游的注入点
        // 就在这里:所有出生路径共用一处,从注册表读。
        CompanionRegistry.Entry reg = CompanionRegistry.get(server).find(companionUuid);
        if (reg != null && !reg.skinValue().isEmpty()) {
            profile.getProperties().put("textures", new com.mojang.authlib.properties.Property(
                    "textures", reg.skinValue(), reg.skinSig().isEmpty() ? null : reg.skinSig()));
        }
        NumenPlayer body = new NumenPlayer(server, level, profile);
        body.setOwnerUuid(ownerUuid);

        // 和真玩家登录同一个入口。内部会建 ServerGamePacketListenerImpl 并把它挂在
        // 这具身体的 connection 上——所以 connection 不是我们自己设的。
        server.getPlayerList().placeNewPlayer(new FakeConnection(), body);

        // 皮肤覆盖层是同步实体数据、不随存档存取，每次进世界都要重设。
        body.showAllSkinLayers();
        return body;
    }

    /** 把同伴移出世界。走玩家列表，与真玩家登出同一条路径。 */
    public static void despawn(MinecraftServer server, ServerPlayer body) {
        server.getPlayerList().remove(body);
    }
}
