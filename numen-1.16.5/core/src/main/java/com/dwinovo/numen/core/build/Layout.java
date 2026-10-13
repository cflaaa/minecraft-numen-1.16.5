package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的通配忽略规则连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * 摆到世界里的一份施工图:每一格要成什么(世界坐标),外加只有图纸文件才带的几样——方块实体数据、摆设实体、逐格料单——
 * 以及<b>摆的时候就掉掉的格数</b>。设计、蓝图文件、当场执行的一个原语,摆下去都是它,交给同一个执行器。
 *
 * <p>掉格要有账:一张一千格的图纸掉了两百格,不记的话任务会报"八百格全部达标",缺的那五分之一无人知晓。
 *
 * @param size 占地尺寸(x × y × z)
 */

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

