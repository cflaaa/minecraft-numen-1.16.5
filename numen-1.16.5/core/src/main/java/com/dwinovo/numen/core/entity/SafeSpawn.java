package com.dwinovo.numen.core.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * 给同伴身体找一个安全的落脚点（在主人附近）。
 *
 * <p><b>为什么不能直接照抄 {@code owner.position()}</b>：主人贴着天花板、在隧道里爬行、
 * 站在熔岩边上时，直接照抄坐标会把整只同伴塞进方块里——玩家碰撞箱与方块重叠，每刻受伤、
 * 死、复活又落回同一处，直到主人碰巧走开。上游为此专门有这个类。
 *
 * <p>找法：先在锚点周围 7×3×7 的盒子里随机试最多 10 次（跳过贴着锚点的那几格，免得在窄处
 * 被实体推挤顶进墙里），再按「最近的圈优先、并逐层向上爬」做一次确定性扫描（主人站在坑底时
 * 需要往上找）。每一个候选都要满足：脚下是**实心整方块**、不是岩浆块/仙人掌，脚与头不在火里、
 * 头顶不是液体（要能呼吸），并且放得下站立的玩家碰撞箱。
 *
 * <p>照上游 {@code api/entity/SafeSpawn.java} 搬（2026-10-11 归位阶段 2，真机试跑发现本仓
 * 的召唤少了这一层）。1.16.5 适配：{@code RandomSource} 这一代还没有，{@link ServerLevel#getRandom()}
 * 返回 {@code java.util.Random}；{@code BlockPos.containing(Vec3)} 也还没有，用三参构造器。
 */
final class SafeSpawn {

    private static final int RANDOM_ATTEMPTS = 10;
    private static final int HORIZONTAL_RANGE = 3;
    private static final int UPWARD_SWEEP = 8;
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    private SafeSpawn() {}

    /**
     * {@code center} 附近一个安全的站立点（方块底面中心），整个邻域此刻都不可用时返回 {@code null}
     * ——要不要退回原位、还是等一等再试，由调用方定。
     */
    static Vec3 findNear(ServerLevel level, Vec3 center) {
        BlockPos origin = new BlockPos(center.x, center.y, center.z);
        Random random = level.getRandom();
        for (int i = 0; i < RANDOM_ATTEMPTS; i++) {
            int dx = random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE;
            int dy = random.nextInt(3) - 1;
            int dz = random.nextInt(HORIZONTAL_RANGE * 2 + 1) - HORIZONTAL_RANGE;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                continue;   // 不踩在锚点头上
            }
            BlockPos pos = origin.offset(dx, dy, dz);
            if (isSafe(level, pos)) {
                return Vec3.atBottomCenterOf(pos);
            }
        }
        // 随机那一段没找到：确定性扫描，逐层向上爬（主人可能在坑底）。
        for (int dy = -1; dy <= UPWARD_SWEEP; dy++) {
            for (int r = 0; r <= HORIZONTAL_RANGE; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (isSafe(level, pos)) {
                            return Vec3.atBottomCenterOf(pos);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 这个（非格点）坐标上放不放得下站立的玩家碰撞箱。
     *
     * <p>锚点在非整方块地面上（半砖、地毯）时的兜底：主人站得下的地方，身体也站得下。
     */
    static boolean hasStandingRoom(ServerLevel level, Vec3 pos) {
        return level.noCollision(standingBox(pos.x, pos.y, pos.z));
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockPos belowPos = pos.below();
        BlockState footing = level.getBlockState(belowPos);
        if (!footing.isFaceSturdy(level, belowPos, Direction.UP)) {
            return false;
        }
        if (footing.is(Blocks.MAGMA_BLOCK) || footing.is(Blocks.CACTUS)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (feet.is(BlockTags.FIRE) || head.is(BlockTags.FIRE)) {
            return false;
        }
        if (feet.getFluidState().is(FluidTags.LAVA)) {
            return false;
        }
        if (!head.getFluidState().isEmpty()) {
            return false;   // 头顶得能呼吸
        }
        return level.noCollision(standingBox(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
    }

    private static AABB standingBox(double x, double y, double z) {
        return new AABB(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
    }
}
