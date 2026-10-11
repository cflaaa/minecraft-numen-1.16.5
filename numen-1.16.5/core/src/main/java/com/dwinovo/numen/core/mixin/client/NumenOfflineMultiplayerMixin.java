package com.dwinovo.numen.core.mixin.client;

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
 *
 * <p>2026-10-11 搬家：原来在 {@code com.dwinovo.numen.core.client.mixin}，与通用 mixin
 * 一起挪到 {@code com.dwinovo.numen.core.mixin[.client]} 下。
 * <b>为什么必须搬</b>：mixin 配置里的 {@code package} 是**独占**的——那个包（含子包）下
 * 只许放 mixin 类；一旦把 {@code package} 开大到 {@code com.dwinovo.numen.core}，
 * 同包下任何普通类（比如 {@code NumenCoreFabric}）被加载时都会
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}，
 * 服务端起不来。详见 docs/PORTING.md §16.3。
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
