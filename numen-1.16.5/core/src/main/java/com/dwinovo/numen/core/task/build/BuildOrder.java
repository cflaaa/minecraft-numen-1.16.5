package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.task.build.BuildCellRules;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

public final class BuildOrder {
    static final double SURVIVAL_MIN_RATE = 0.1;
    static final double SURVIVAL_TARGET_TICKS = 14400.0;
    static final double FREE_MAX_RATE = 5.0;
    static final double FREE_TARGET_TICKS = 500.0;
    public static final int MAX_CELLS_PER_TICK = 8;
    public static final Comparator<BuildTaskRecord.Target> BUILD_ORDER = Comparator.comparingInt((BuildTaskRecord.Target t) -> BuildOrder.needsSupport(t.desiredState()) ? 1 : 0).thenComparingInt(t -> t.pos().getY()).thenComparingInt(BuildOrder::stage).thenComparingInt(t -> t.pos().getZ()).thenComparingInt(t -> (t.pos().getZ() & 1) == 0 ? t.pos().getX() : -t.pos().getX());
    private static final long MIN_DEADLINE_TICKS = 1200L;
    private static final long TRAVEL_ALLOWANCE_TICKS = 800L;
    private static final double DEADLINE_SLACK = 1.6;

    private BuildOrder() {
    }

    public static boolean instant(int cellCount) {
        return cellCount <= 8;
    }

    public static boolean needsSupport(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty((Property)BlockStateProperties.HANGING)) {
            return true;
        }
        Block b = state.getBlock();
        return b instanceof LadderBlock || b instanceof TorchBlock || b instanceof SignBlock || b instanceof BasePressurePlateBlock || b instanceof BaseRailBlock || b instanceof DiodeBlock || b instanceof RedStoneWireBlock || b instanceof WoolCarpetBlock || b instanceof BushBlock || b instanceof FlowerPotBlock || b instanceof SnowLayerBlock || b instanceof FaceAttachedHorizontalDirectionalBlock && !(b instanceof GrindstoneBlock);
    }

    public static int stage(BuildTaskRecord.Target target) {
        if (BuildCellRules.isAirTarget(target)) {
            return 0;
        }
        BlockState state = target.desiredState();
        return state != null && state.isCollisionShapeFullBlock((BlockGetter)EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 1 : 2;
    }

    public static double paceFor(int cellCount, boolean consumeMaterials) {
        int cells = Math.max(1, cellCount);
        if (BuildOrder.instant(cells)) {
            return cells;
        }
        return consumeMaterials ? Math.max(0.1, (double)cells / 14400.0) : Math.min(5.0, (double)cells / 500.0);
    }

    public static long estimatedTicks(int cellCount, boolean consumeMaterials) {
        return (long)Math.ceil((double)Math.max(1, cellCount) / BuildOrder.paceFor(cellCount, consumeMaterials));
    }

    public static long deadlineTicks(int cellCount, boolean consumeMaterials) {
        return Math.max(1200L, 800L + (long)((double)BuildOrder.estimatedTicks(cellCount, consumeMaterials) * 1.6));
    }
}

