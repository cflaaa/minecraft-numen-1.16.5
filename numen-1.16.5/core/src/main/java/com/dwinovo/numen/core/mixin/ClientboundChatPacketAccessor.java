package com.dwinovo.numen.core.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 直取 {@link ClientboundChatPacket} 的私有字段 {@code message}。
 *
 * <p>⚠️ 源码本来写的是 {@code said.getMessage()}，但**这个方法在专用服务端 jar 里被裁掉了**——
 * 这个包服务端只负责<b>写</b>、不负责读（读它的是客户端）。字段 {@code message} 还在。
 * 同伴没有客户端，{@code FakeClient.onOutbound} 要在服务端侧读到「服务端对同伴说的话」，
 * 只能直取字段。这是「合并 jar 有、专用服务端 jar 没有」那一类坑的又一例（见 `PORTING.md` §11.3）。
 *
 * <p>（{@code getType()} 服务端 jar 里**有**，不需要走 accessor。）
 */
@Mixin(ClientboundChatPacket.class)
public interface ClientboundChatPacketAccessor {

    @Accessor("message")
    Component numen$getMessage();
}
