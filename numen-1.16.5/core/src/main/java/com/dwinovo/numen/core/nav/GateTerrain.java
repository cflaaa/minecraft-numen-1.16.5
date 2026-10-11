package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.core.permission.Action;
import com.dwinovo.numen.core.permission.ConsentItem;
import com.dwinovo.numen.core.permission.Gate;
import com.dwinovo.numen.core.permission.Verdict;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 端口 {@link TerrainPolicy} 的实现：权限层的一份裁决快照（{@link Gate}，开一趟路时在主线程上取），
 * 之后搜索线程逐格问它，只读快照与传进来的视图。放行；要问——凭据是写给主人的那一条征询
 * （{@link ConsentItem}）；拒绝——理由是裁决本身（{@link Verdict}）。
 *
 * <p>挖是拆这一格；放是往这一格里放那块方块的物品，接住坠落倒的水是放一桶水。
 *
 * <p>照上游 {@code core/common/.../nav/GateTerrain.java} 逐字搬（2026-10-11 归位阶段 2），仅改包名。
 * 1.16.5 无需适配：{@code Blocks.WATER}/{@code Items.WATER_BUCKET}/{@code Block.asItem()} 这一代都在
 * （javap 核过）。
 */
record GateTerrain(Gate gate) implements TerrainPolicy {

    @Override
    public Permit judge(Change change, BlockPos pos, BlockState state, BlockGetter view) {
        Action action = action(change, pos, state);
        Verdict verdict = gate.judge(action, view);
        return switch (verdict.kind()) {
            case ALLOW -> Permit.ALLOW;
            case ASK -> Permit.ask(gate.consentItem(action, verdict, view));
            case DENY -> Permit.deny(verdict);
        };
    }

    /** 对 {@code pos}（此刻是 {@code state}）做 {@code change} 是权限层的哪个动作：挖是拆这一格，放是往里放那块方块的物品。 */
    static Action action(Change change, BlockPos pos, BlockState state) {
        if (change instanceof Change.Dig) {
            return Action.breakBlock(pos, state);
        } else {
            Change.Place place = (Change.Place) change;
            return Action.place(pos, state, itemOf(place));
        }
    }

    /** 放的是什么物品：水方块没有物品形态，倒水用的是水桶。 */
    private static Item itemOf(Change.Place place) {
        return place.block() == Blocks.WATER ? Items.WATER_BUCKET : place.block().asItem();
    }
}
