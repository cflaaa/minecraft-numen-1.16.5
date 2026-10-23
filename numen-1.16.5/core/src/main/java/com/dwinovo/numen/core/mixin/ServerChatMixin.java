package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
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
 * <p>谁听得见：她自己的同伴，和无主的「服务器同伴」（owner == null——正是本仓无人值守的
 * 部署形态）。别人家的同伴不偷听。
 *
 * <p>收窄：命令（{@code /…}）不进——原版在包处理那层就把命令分流走了，到不了这里，
 * 这里再挡一道是防御；同伴自己的话不回流（FakeConnection 不发包，双保险），免得自说自话。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerChatMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat(Ljava/lang/String;)V", at = @At("HEAD"))
    private void numen$companionsHear(String message, CallbackInfo ci) {
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
        UUID speaker = this.player.getUUID();
        for (NumenPlayer companion : Companions.all(server)) {
            UUID owner = companion.getOwnerUuid();
            if (owner != null && !owner.equals(speaker)) {
                continue;   // 别人的同伴不偷听
            }
            CompanionBrain brain = Companions.brain(companion);
            if (brain != null) {
                brain.tell(message);
            }
        }
    }
}
