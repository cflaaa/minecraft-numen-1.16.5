package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 原版聊天 → 同伴（#0027，用户定的客户端版需求：「对话功能加入到原版聊天处，发送的消息
 * 直接发送到 mcp 服务器连接的 agent」）。
 *
 * <p>真人玩家在聊天栏打的字，照原样进公共聊天（这个 mixin 不动它），同时<b>转发给她听得见
 * 的同伴</b>——走 {@link CompanionBrain#tell}，也就是和 G 面板的「说话」同一条路：
 * 事件进她的收件箱（恒急件），外接大脑经 {@code get_events} 取走、用 {@code say} 回话，
 * 回话又广播回原版聊天栏。于是「打开聊天 → 打字 → 回车」就是完整的对话体验，
 * 不需要任何额外界面。
 *
 * <p>谁听得见：<b>世界里所有活着的同伴</b>。原版语义是"她自己的同伴"（owner 匹配），
 * 但本仓的部署形态是共享的服务器同伴 + 外接大脑，且公开聊天本来就会被她们的假客户端
 * 收成 {@code server_message}（AMBIENT）旁听——owner 门槛挡不住任何东西，只会让
 * "主人在聊天栏叫她她却不理"（2026-10-25 实测：她的 owner 因 playerdata 恢复成 TestBOT，
 * 于是真人说话她收不到急件，只收到迟到的旁听）。所以这里不做归属过滤。
 *
 * <p>收窄：命令（{@code /…}）不进；同伴自己的话不回流（FakeConnection 不发包，双保险）。
 *
 * <p><b>为什么挂 {@code handleChat(ServerboundChatPacket)}（公开的包处理方法）而不是私有的
 * {@code handleChat(String)}</b>：2026-10-25 实测后者没被调到——普通聊天在原版里走的是
 * 过滤/异步分支，不一定经那个私有重载。挂包处理方法则一定会经过，且能直接读 {@code getMessage()}。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerChatMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat(Lnet/minecraft/network/protocol/game/ServerboundChatPacket;)V", at = @At("HEAD"))
    private void numen$companionsHear(ServerboundChatPacket packet, CallbackInfo ci) {
        String message = packet == null ? null : packet.getMessage();
        if (message == null || message.isBlank() || message.startsWith("/")) {
            return;
        }
        if (this.player == null || this.player instanceof NumenPlayer) {
            return;   // 同伴自己（防御：她的假连接不发包，但别让任何回流进来）
        }
        MinecraftServer server = this.player.getServer();
        if (server == null) {
            return;
        }
        for (NumenPlayer companion : Companions.all(server)) {
            CompanionBrain brain = Companions.brain(companion);
            if (brain != null) {
                brain.tell(message);
            }
        }
    }
}
