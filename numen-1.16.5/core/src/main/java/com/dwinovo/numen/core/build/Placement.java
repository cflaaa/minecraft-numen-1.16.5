package com.dwinovo.numen.core.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

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
 * 一份施工图摆到哪儿、朝哪儿:原点 {@code (0,0,0)} 落在 {@code anchor},整份绕原点顺时针(俯视)转 {@code quarters} 个
 * 90°。设计与蓝图文件摆下去都经它——同一条变换,方块朝向跟着转。
 *
 * <p>为什么绕原点而不是"转完再把最小角对齐到锚点":设计改了会变大变小,最小角跟着挪;同一栋房子按同一个落点再摆一次,
 * 原来的墙必须落回原来的格子,差异才算得对。绕原点转,原点永远在锚点上。
 *
 * <p>位置与方向都由原版算({@link StructureTemplate#transform}、{@link BlockState#rotate}):楼梯朝向、门的合页转过去
 * 该是什么样,是原版自己的规则。
 */

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

