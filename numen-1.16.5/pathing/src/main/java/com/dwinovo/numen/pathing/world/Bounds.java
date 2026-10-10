package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;

/**
 * 世界的边:身体能待在哪、手能动哪一格。寻路里判世界边界只有这一处。
 *
 * <p>两件事口径不同,都照原版:身体被世界边界当墙挡着,碰撞盒整个在边界里才待得住,高度不设限;动手(挖、放、交互)照
 * {@code Level.mayInteract} 看这一格在不在世界边界里,另外这一格得在建筑高度之内——之外既没有方块可挖,也放不下。
 *
 * <p><b>1.16.5 的差别:</b>上游那个参数是原版的 {@code LevelHeightAccessor}(1.17+),
 * 这一代没有它,换成本模块自己的 {@link BuildHeight}——形状一样,只是不必反过来依赖第 1 层。
 */
public final class Bounds {

    /**
     * 建筑高度的下界。1.16.5 的建筑高度是**固定的 0..256**,所以恒为 0。
     *
     * <p>可变建筑高度、以及把"最低建筑高度/区段"抽象出来的 {@code LevelHeightAccessor},都是 1.17+ 才有的;
     * 这一代的 {@code Level} 上既没有 {@code getMinBuildHeight()} 也没有 {@code getMinSection()}。
     * 全模块只有这一处写这个 0,别处一律引它 —— 以后要支持别的版本时只改这里。
     */
    public static final int MIN_BUILD_HEIGHT = 0;

    private Bounds() {}

    /** 身体站在 {@code (x, z)} 这一列时,碰撞盒整个在世界边界里。 */
    public static boolean holdsBody(WorldBorder border, BodyStats body, int x, int z) {
        double half = body.width() / 2;
        return border.isWithinBounds(new AABB(x + 0.5 - half, 0, z + 0.5 - half, x + 0.5 + half, 0, z + 0.5 + half));
    }

    /** 手能不能动这一格:在世界边界里,且在建筑高度之内。 */
    public static boolean allowsEdit(WorldBorder border, BuildHeight heights, BlockPos pos) {
        return border.isWithinBounds(pos) && !heights.isOutsideBuildHeight(pos);
    }
}
