package com.dwinovo.numen.core.mixin;

import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 直写 {@link ServerboundAcceptTeleportationPacket} 的传送编号 {@code id}。
 *
 * <p>⚠️ 源码本来写的是 {@code new ServerboundAcceptTeleportationPacket(id)}，但那个
 * <b>{@code (int)} 构造器在专用服务端 jar 里没有</b>——服务端 jar 只留了<b>无参</b>构造器
 * （这个包是客户端<b>发</b>给服务端的，构造它的是客户端）。{@code FakeClient.answer()} 是服务端侧
 * 假装客户端回执，必须自己造这个包，只能无参构造 + 直写字段。
 *
 * <p>字段 {@code id} 服务端 jar 里有（{@code getId()} 也在，但没有 setter）。见 `PORTING.md` §11.3。
 */
@Mixin(ServerboundAcceptTeleportationPacket.class)
public interface ServerboundAcceptTeleportationPacketAccessor {

    @Accessor("id")
    void numen$setId(int id);
}
