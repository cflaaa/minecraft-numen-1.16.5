package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.permission.PlacedBlocks;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

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
 * 一串格要成的样子:每一格最终要成什么。后写覆盖先写——同一格写两次,留下后一笔。
 */

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

