package com.dwinovo.numen.core.platform;

import com.dwinovo.numen.core.network.NumenPayload;
import com.dwinovo.numen.core.platform.services.INetworkChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fabric 这一侧的 {@link INetworkChannel}。照上游
 * {@code api/fabric/platform/FabricNetworkChannel.java} 搬，两处 1.16.5 适配：
 *
 * <ul>
 *   <li>{@code ServerPlayNetworking.PlayChannelHandler} 的签名是
 *       {@code (server, player, handler, buf, sender)} 五参（1.20.2+ 起 handler 参被去掉）；</li>
 *   <li>{@code PacketByteBufs} 两代同名同形，照抄。</li>
 * </ul>
 *
 * <h2>客户端类延迟加载</h2>
 * {@code ClientPlayNetworking} 是客户端专属的 Fabric 类。引用藏在环境判断后面、
 * 调用单独放一个静态方法，JVM 就只在客户端加载它——专用服务端永远走不到那句。
 *
 * <h2>线程</h2>
 * 缓冲区在网络线程上解码（netty 释放它之前），随后 handler 被改排到接收方的主线程。
 */
public final class FabricNetworkChannel implements INetworkChannel {

    @Override
    public <T extends NumenPayload> void registerClientToServer(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            BiConsumer<T, ServerPlayer> handler) {
        ServerPlayNetworking.registerGlobalReceiver(id,
                (server, player, listener, buf, responseSender) -> {
                    T payload = decoder.apply(buf);
                    server.execute(() -> handler.accept(payload, player));
                });
    }

    @Override
    public void sendToServer(NumenPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        // 延迟类加载：ClientPlayNetworking 是客户端专属，服务端 JVM 走不到这句。
        sendToServerImpl(payload.id(), buf);
    }

    /** 单独一个方法：客户端类只在这个方法被调用时才加载。 */
    private static void sendToServerImpl(ResourceLocation id, FriendlyByteBuf buf) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(id, buf);
    }

    @Override
    public <T extends NumenPayload> void registerServerToClient(
            ResourceLocation id,
            Function<FriendlyByteBuf, T> decoder,
            Consumer<T> handler) {
        // 只有接收方（客户端）需要挂钩；服务端只按通道 id 发。
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            registerClientReceiverImpl(id, decoder, handler);
        }
    }

    /** 为延迟类加载单独放的方法——只在客户端环境跑。 */
    private static <T extends NumenPayload> void registerClientReceiverImpl(
            ResourceLocation id, Function<FriendlyByteBuf, T> decoder, Consumer<T> handler) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                id, (client, listener, buf, responseSender) -> {
                    T payload = decoder.apply(buf);
                    client.execute(() -> handler.accept(payload));
                });
    }

    @Override
    public void sendToPlayer(ServerPlayer player, NumenPayload payload) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        payload.write(buf);
        ServerPlayNetworking.send(player, payload.id(), buf);
    }
}
