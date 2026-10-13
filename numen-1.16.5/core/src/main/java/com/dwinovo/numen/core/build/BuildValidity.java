package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.build.BuildSettings;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的 `**/build/` 连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/** Shared block-state validity rules for construction scans and placement; the knobs are {@link BuildSettings}. */

public final class BuildValidity {
    private static final Set<Property<?>> ORIENTATION_PROPERTIES = Set.copyOf(List.of(RotatedPillarBlock.AXIS, HorizontalDirectionalBlock.FACING, StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE, PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP, TrapDoorBlock.OPEN, TrapDoorBlock.HALF));
    private static final Set<Property<?>> AUTHORED_PROPERTIES = Set.copyOf(List.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF, BlockStateProperties.DOUBLE_BLOCK_HALF, BlockStateProperties.SLAB_TYPE, BlockStateProperties.DOOR_HINGE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.BELL_ATTACHMENT, BlockStateProperties.ROTATION_16, BlockStateProperties.ORIENTATION, BlockStateProperties.BED_PART));

    private BuildValidity() {
    }

    public static boolean valid(BlockState current, BlockState desired, boolean itemVerify) {
        if (desired == null) {
            return true;
        }
        BuildSettings settings = BuildSettings.get();
        if (current.getBlock() instanceof LiquidBlock && settings.okIfWater) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && desired.getBlock() instanceof AirBlock) {
            return true;
        }
        if (current.getBlock() instanceof AirBlock && settings.okIfAir().contains(desired.getBlock())) {
            return true;
        }
        if (desired.getBlock() instanceof AirBlock && settings.buildIgnoreBlocks().contains(current.getBlock())) {
            return true;
        }
        if (!(current.getBlock() instanceof AirBlock) && settings.buildIgnoreExisting && !itemVerify) {
            return true;
        }
        if (!itemVerify && settings.buildValidSubstitutes().getOrDefault(desired.getBlock(), List.of()).contains(current.getBlock())) {
            return true;
        }
        if (current.equals(desired)) {
            return true;
        }
        return BuildValidity.sameBlockState(current, desired);
    }

    public static boolean sameBlockState(BlockState first, BlockState second) {
        if (first.getBlock() != second.getBlock()) {
            return false;
        }
        BuildSettings settings = BuildSettings.get();
        List<String> ignoredProps = settings.buildIgnoreProperties();
        boolean gate = first.getBlock() instanceof FenceGateBlock;
        for (Property<?> property : AUTHORED_PROPERTIES) {
            if (!first.hasProperty(property) || !second.hasProperty(property) || gate && property == BlockStateProperties.HORIZONTAL_FACING || settings.buildIgnoreDirection && ORIENTATION_PROPERTIES.contains(property) || ignoredProps.contains(property.getName()) || first.getValue(property) == second.getValue(property)) continue;
            return false;
        }
        return true;
    }
}

