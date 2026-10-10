package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;

/**
 * 建筑高度的上下界:一格方块在不在可建造范围里。
 *
 * <p>上游直接用原版的 {@code LevelHeightAccessor}:1.17+ 把"最低建筑高度 / 总高度 / 区段下标"抽象成了接口,
 * 可变建筑高度(数据包改高度)也靠它。**1.16.5 没有这个接口** —— 建筑高度固定 {@code 0..256},
 * 那几样东西散在 {@code Level}({@code getMaxBuildHeight}、{@code isOutsideBuildHeight})与
 * {@code ChunkAccess}({@code getSections()})上。
 *
 * <p>这里补一个同形状的最小接口,理由是**层次**:{@link Bounds} 是第 0 层,
 * 而拿它当参数用的 {@code WorldView} 在第 1 层 —— 第 0 层不能反过来依赖第 1 层。
 * 上游能直接写 {@code LevelHeightAccessor},是因为那是原版第 0 层的类型。
 */
public interface BuildHeight {

    /** 最低建筑高度。1.16.5 恒为 {@link Bounds#MIN_BUILD_HEIGHT}。 */
    int getMinBuildHeight();

    /** 建筑总高度(不是最高可建的 y)。1.16.5 恒为 256。 */
    int getHeight();

    /** {@code y} 落在建筑高度之外。语义与上游 {@code LevelHeightAccessor.isOutsideBuildHeight} 一致。 */
    default boolean isOutsideBuildHeight(int y) {
        return y < getMinBuildHeight() || y >= getMinBuildHeight() + getHeight();
    }

    default boolean isOutsideBuildHeight(BlockPos pos) {
        return isOutsideBuildHeight(pos.getY());
    }
}
