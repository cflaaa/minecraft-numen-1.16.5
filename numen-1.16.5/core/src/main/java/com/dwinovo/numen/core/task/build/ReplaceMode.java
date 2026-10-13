package com.dwinovo.numen.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

public enum ReplaceMode {
    DONT_REPLACE,
    REPLACE_SOLID,
    REPLACE_ANY,
    REPLACE_EMPTY;


    public boolean mayReplace() {
        return this == REPLACE_ANY || this == REPLACE_EMPTY;
    }

    public boolean allows(BlockState current, BlockState desired) {
        boolean soft;
        boolean clearing;
        boolean bl = clearing = desired == null || desired.isAir();
        if (clearing) {
            return this == REPLACE_EMPTY;
        }
        if (this == REPLACE_ANY || this == REPLACE_EMPTY) {
            return true;
        }
        boolean bl2 = soft = current.isAir() || current.getMaterial().isReplaceable();
        if (soft) {
            return true;
        }
        return this == REPLACE_SOLID && desired.isCollisionShapeFullBlock((BlockGetter)EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}

