package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record Changes(List<BuildTaskRecord.Target> work, int fill, int replace, int remove, int unseen) {
    public boolean none() {
        return this.fill + this.replace + this.remove + this.unseen == 0;
    }

    public static Changes between(List<BuildTaskRecord.Target> targets, Built.Building was, Function<BlockPos, BlockState> world) {
        int fill = 0;
        int replace = 0;
        int remove = 0;
        int unseen = 0;
        HashSet<Long> drawn = new HashSet<Long>();
        ArrayList<BuildTaskRecord.Target> work = new ArrayList<BuildTaskRecord.Target>(targets);
        for (BuildTaskRecord.Target target : targets) {
            boolean empty;
            drawn.add(target.pos().asLong());
            BlockState now = world.apply(target.pos());
            if (now == null) {
                ++unseen;
                continue;
            }
            if (target.matches(now) || !target.mode().allows(now, target.desiredState())) continue;
            boolean bl = empty = now.isAir() || now.getMaterial().isReplaceable();
            if (empty && !target.desiredState().isAir()) {
                ++fill;
                continue;
            }
            ++replace;
        }
        if (was != null) {
            for (Map.Entry entry : was.cells().entrySet()) {
                if (drawn.contains(entry.getKey())) continue;
                BlockPos pos = BlockPos.of((long)((Long)entry.getKey()));
                BlockState now = world.apply(pos);
                if (now == null) {
                    ++unseen;
                    work.add(BuildTaskRecord.Target.removal(pos, (Block)entry.getValue()));
                    continue;
                }
                if (!now.is((Block)entry.getValue())) continue;
                ++remove;
                work.add(BuildTaskRecord.Target.removal(pos, (Block)entry.getValue()));
            }
        }
        return new Changes(List.copyOf(work), fill, replace, remove, unseen);
    }
}

