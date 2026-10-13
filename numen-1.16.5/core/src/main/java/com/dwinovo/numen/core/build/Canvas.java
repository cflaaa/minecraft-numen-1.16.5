package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.permission.PlacedBlocks;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class Canvas {
    public static final int MAX_CELLS = 16384;
    private final Map<Long, BuildTaskRecord.Target> cells = new LinkedHashMap<Long, BuildTaskRecord.Target>();

    public void put(BuildTaskRecord.Target target) {
        this.cells.remove(target.pos().asLong());
        BlockPos other = PlacedBlocks.otherHalfOf(target.pos(), (BlockState)target.desiredState());
        if (other != null) {
            this.cells.remove(other.asLong());
        }
        this.cells.put(target.pos().asLong(), target);
        if (this.cells.size() > 16384) {
            throw new IllegalArgumentException("this comes to more than 16384 cells; build it as several pieces");
        }
    }

    public Layout layout() {
        return Layout.of(List.copyOf(this.cells.values()), 0);
    }
}

