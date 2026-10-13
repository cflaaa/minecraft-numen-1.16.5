package com.dwinovo.numen.core.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

public record Placement(BlockPos anchor, int quarters) {
    public static final Placement IN_PLACE = new Placement(BlockPos.ZERO, 0);

    public Placement {
        anchor = anchor.immutable();
        quarters = Math.floorMod(quarters, 4);
    }

    public static int quarters(Integer degrees) {
        if (degrees == null) {
            return 0;
        }
        return switch (degrees) {
            case 0 -> 0;
            case 90 -> 1;
            case 180 -> 2;
            case 270 -> 3;
            default -> throw new IllegalArgumentException("rotation must be 0, 90, 180 or 270, got " + degrees);
        };
    }

    public Rotation rotation() {
        return switch (this.quarters) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public int degrees() {
        return this.quarters * 90;
    }

    public BlockPos cell(BlockPos relative) {
        return StructureTemplate.transform(relative, (Mirror)Mirror.NONE, (Rotation)this.rotation(), BlockPos.ZERO).offset((Vec3i)this.anchor);
    }

    public BlockPos relative(BlockPos world) {
        return new Placement(BlockPos.ZERO, 4 - this.quarters).cell(world.subtract((Vec3i)this.anchor));
    }

    public Vec3 point(Vec3 relative) {
        return StructureTemplate.transform((Vec3)relative, (Mirror)Mirror.NONE, (Rotation)this.rotation(), BlockPos.ZERO).add((double)this.anchor.getX(), (double)this.anchor.getY(), (double)this.anchor.getZ());
    }

    public BlockState turn(BlockState state) {
        return state.rotate(this.rotation());
    }
}

