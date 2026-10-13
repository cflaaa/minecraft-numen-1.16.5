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

/**
 * 把一处变成施工图的样子要动哪些格:施工图在这里求出的<b>每一格应该是什么</b>,和世界里的<b>实际</b>方块比——已经对的不动,
 * 缺的补,不一样的换;再加上这一栋从前由她放下、施工图里已经没有的格,只拆"世界里现在仍是她放下的那个方块"的。
 * 第一次盖时这一栋还没有记录,差异就是整栋:盖与改是同一条路、同一个计算。
 *
 * <p>交给执行器的活是施工图的全部格加上要拆的格——执行器本来就逐格对着世界跳过已经对的格,施工中被外力弄坏的也照样补回来;
 * 这里的几个数只回答"有没有要动的、各是多少",没有就不派活。
 *
 * @param work    交给执行器的目标格:施工图的每一格,接着要拆的格({@link BuildTaskRecord.Target#removal})
 * @param fill    空着(或只有草这类软东西)、要补上的格
 * @param replace 立着别的东西、要换掉的格(施工图要空气的格上立着东西,也是换)
 * @param remove  这一栋从前由她放下、施工图里已经没有、世界里仍是那个方块的格
 * @param unseen  所在区块没加载、此刻看不见的格:到了那儿执行器自己对照,算作要去看一眼
 */

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

