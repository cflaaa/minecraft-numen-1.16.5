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

