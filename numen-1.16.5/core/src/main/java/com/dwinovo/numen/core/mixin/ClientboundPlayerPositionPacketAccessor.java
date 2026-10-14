package com.dwinovo.numen.core.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 直取 {@link ClientboundPlayerPositionPacket} 的传送编号 {@code id}。
 *
 * <p>⚠️ 服务端 jar 里这个类**连一个 {@code ()I} 方法都没有**（{@code getId()} 被整条裁掉）——
 * 传送编号只在客户端被读回。字段 {@code id} 还在。
 * {@code FakeClient.onOutbound} 靠它取编号、回 {@code ServerboundAcceptTeleportationPacket}，
 * 否则同伴永远卡在「正在换维度」（传送门冷却不减、免疫伤害）。见 `PORTING.md` §11.3。
 */
@Mixin(ClientboundPlayerPositionPacket.class)
public interface ClientboundPlayerPositionPacketAccessor {

    @Accessor("id")
    int numen$getId();
}
