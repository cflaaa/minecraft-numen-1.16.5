package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.pathing.search.PalettedContainerAccess;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 把 {@link PalettedContainer} 的位存储与调色板露出来，供寻路快照做**廉价拷贝**。
 *
 * <p>背景：1.16.5 没有 {@code PalettedContainer.copy()}；退而用的 NBT 往返里 {@code write} 会把
 * 4096 格**逐格重打包**成新 {@code BitStorage}——真机实测每段约 240µs，是主线程寻路 99% 的开销。
 * 这里直取 {@code storage}/{@code palette}/{@code bits}，快照拷一份 raw {@code long[]} 即可。
 *
 * <p>字段都在专用服务端保留（本方块的存储是服务端核心数据，不是客户端便利方法）。注入失败即炸服
 * （{@code numen.mixins.json} 的 {@code required:true} + {@code defaultRequire:1}），早发现。
 */
@Mixin(PalettedContainer.class)
public interface PalettedContainerAccessor extends PalettedContainerAccess<BlockState> {

    @Override
    @Accessor("palette")
    Palette<BlockState> numen$palette();

    @Override
    @Accessor("storage")
    BitStorage numen$storage();

    @Override
    @Accessor("bits")
    int numen$bits();
}
