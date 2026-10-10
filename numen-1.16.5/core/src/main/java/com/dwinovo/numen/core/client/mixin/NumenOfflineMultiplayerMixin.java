package com.dwinovo.numen.core.client.mixin;

import com.dwinovo.numen.core.client.NumenOfflineMultiplayer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把 {@code Minecraft.allowsMultiplayer()} / {@code allowsChat()} 的 false 掰回 true。
 *
 * <p>只放在 mixins.json 的 {@code client} 数组里 —— {@code Minecraft} 是客户端类，
 * 声明成通用 mixin 会让专用服务端启动时就炸。
 * 原因见 {@link NumenOfflineMultiplayer}。
 */
@Mixin(Minecraft.class)
public abstract class NumenOfflineMultiplayerMixin {

    @Inject(method = "allowsMultiplayer", at = @At("RETURN"), cancellable = true)
    private void numen$allowsMultiplayer(CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) && NumenOfflineMultiplayer.rescue("多人游戏")) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "allowsChat", at = @At("RETURN"), cancellable = true)
    private void numen$allowsChat(CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) && NumenOfflineMultiplayer.rescue("在线聊天")) {
            cir.setReturnValue(true);
        }
    }
}
