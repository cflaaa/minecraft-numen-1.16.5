package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.entity.NumenPlayer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 同伴(假玩家)的跨维度传送改为「实体刻内登记、实体刻外执行」（#0008）。
 *
 * <h2>为什么必须拦</h2>
 * 原版传送链：{@code Entity.baseTick → handleNetherPortal → ServerPlayer.changeDimension(ServerLevel)}。
 * {@code changeDimension} 里 {@code ServerLevel.removePlayer} —— 真人的 {@code doTick} 由网络层驱动、
 * 在实体刻之外，这一步合法；同伴的 {@code doTick} 由 {@code Physics.step} 在<b>自己的实体刻之内</b>调
 * （见 {@code NumenPlayer.tick}），同一句就撞上原版「tick 中不得移除实体」→
 * {@code IllegalStateException: Removing entity while ticking!}——且异常被 {@code NumenPlayer.tick}
 * 的 try/catch 吞掉，传送被永久中止（同伴卡在门里，进不了下界/末地）。
 *
 * <h2>怎么拦</h2>
 * {@code changeDimension} 的 HEAD 上 cancellable：同伴 → 只登记目标维度、立即返回；
 * {@code Companions.tick}（挂在 END_SERVER_TICK，实体刻已结束）再把登记的传送真正执行。
 *
 * <h2>安全性</h2>
 * <ul>
 *   <li>调用方 {@code handleNetherPortal} 对返回值<b>只 pop 不用</b>（字节码 132 行），返回 {@code this} 安全；</li>
 *   <li>{@code isInsidePortal=false}（145-147 行）在 changeDimension 之后、仍在 handleNetherPortal 内，
 *       cancel 只切掉传送这一句 → 同一 tick 不会重复登记；</li>
 *   <li>{@code numen$deferredChangeRunning} 放行「延迟执行时的真实调用」，否则会无限递归；</li>
 *   <li>真人玩家（非 NumenPlayer）完全不受影响。</li>
 * </ul>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerChangeDimensionMixin {

    @Inject(method = "changeDimension", at = @At("HEAD"), cancellable = true)
    private void numen$deferChangeDimension(ServerLevel dest, CallbackInfoReturnable<Entity> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (!(self instanceof NumenPlayer companion)) {
            return;   // 只有同伴(假玩家)走延迟路径，真人玩家原样
        }
        if (companion.numen$deferredChangeRunning) {
            return;   // 这是 Companions 在实体刻之外补执行的那一次真实调用——放行
        }
        companion.numen$queueDimensionChange(dest);
        Constants.info("[numen-portal] {} mixin 拦下 changeDimension → {}（已排队，实体刻外补执行）",
                self.getName().getString(), dest.dimension().location());
        // handleNetherPortal 对返回值只 pop 不用；返回自己表示"此实体未离开"
        cir.setReturnValue(self);
    }
}
