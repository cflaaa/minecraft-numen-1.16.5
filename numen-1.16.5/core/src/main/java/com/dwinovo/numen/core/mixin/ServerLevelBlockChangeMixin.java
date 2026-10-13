package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.scan.BlockSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端每一次方块状态变更都喂给方块搜索的共享索引（{@link BlockSearch#onBlockChange}）。
 *
 * <p>{@code ServerLevel.onBlockStateChange} 正是原版自己的 POI 索引吃的那个钩子：凡是经
 * {@code Level.setBlock} 路过的变更（玩家、活塞、爆炸、命令、生长、流体）都点火。处理器对无关
 * 方块是 O(1)，没有搜索或任务登记目标时只是一次 volatile 读，几乎零成本。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockChangeMixin {

    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void numen$feedTargetIndex(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        BlockSearch.onBlockChange((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
