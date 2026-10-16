package com.dwinovo.numen.pathing.search;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

/**
 * 由 core 的 {@code PalettedContainerAccessor} mixin 实现的访问口：把 {@link net.minecraft.world.level.chunk.PalettedContainer}
 * 的位存储与调色板露给寻路快照。
 *
 * <p>为什么需要它：1.16.5 的 {@code PalettedContainer} **没有** {@code copy()}（1.20 才有），而 NBT 那条路
 * （{@code write(CompoundTag,…)} / {@code read(ListTag, long[])}）里的 {@code write} 会把 4096 格**逐格重打包**
 * 成一个新的 {@code BitStorage} ——专用服务端实测每段约 240µs，是主线程寻路 99% 的开销来源。
 * 源段本来就有现成的 {@code BitStorage}，直接拷它的 raw {@code long[]} 即可，无需重打包。
 *
 * <p>接口放在 {@code pathing}（core 依赖 pathing，反向不行），实现（mixin）在 core。
 */
public interface PalettedContainerAccess<T> {

    /** 当前调色板（索引 → 方块状态）。**只读共享**，见 {@link WorldSnapshot} 的安全性说明。 */
    Palette<T> numen$palette();

    /** 位存储（打包的方块索引）。 */
    BitStorage numen$storage();

    /** 位存储的位数（构造克隆用的 {@link BitStorage} 时要）。 */
    int numen$bits();
}
