package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

public record Layout(List<BuildTaskRecord.Target> targets, Vec3i size, Map<Long, CompoundTag> blockEntityData, List<BuildTaskRecord.EntitySpawn> entities, Map<Long, List<BuildTaskRecord.CellNeed>> cellNeeds, int dropped) {
    public Layout {
        targets = List.copyOf(targets);
        blockEntityData = Map.copyOf(blockEntityData);
        entities = List.copyOf(entities);
        cellNeeds = Map.copyOf(cellNeeds);
    }

    public static Layout of(List<BuildTaskRecord.Target> targets, int dropped) {
        return new Layout(targets, Layout.extent(targets), Map.of(), List.of(), Map.of(), dropped);
    }

    static Vec3i extent(List<BuildTaskRecord.Target> targets) {
        if (targets.isEmpty()) {
            return Vec3i.ZERO;
        }
        BlockPos first = targets.get(0).pos();
        int[] lo = new int[]{first.getX(), first.getY(), first.getZ()};
        int[] hi = (int[])lo.clone();
        for (BuildTaskRecord.Target t : targets) {
            int[] at = new int[]{t.pos().getX(), t.pos().getY(), t.pos().getZ()};
            for (int i = 0; i < 3; ++i) {
                lo[i] = Math.min(lo[i], at[i]);
                hi[i] = Math.max(hi[i], at[i]);
            }
        }
        return new Vec3i(hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1);
    }
}

