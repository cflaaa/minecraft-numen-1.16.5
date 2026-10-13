package com.dwinovo.numen.core.task.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
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
 * 目标格上已经有东西时,让不让路。
 *
 * <p>四档,从最保守到最霸道。此前只有一个布尔量"替不替换",而那个布尔量把两件不同
 * 的事捆在了一起:<b>要不要顶掉挡路的</b>,和<b>要不要把该空的地方清空</b>。往一栋
 * 老房子上加东西时,人要的往往是"只往空地上补,已有的一律别碰"——那是这里的
 * {@link #DONT_REPLACE},用布尔量表达不出来。
 *
 * <p>"软"的判据用 {@link BlockState#canBeReplaced()}:草、花、水草、雪层、火——
 * 原版自己判断"能不能直接盖上去"用的就是它,所以不必另立一套近似判据。
 */

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

