package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.permission.Action;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.core.permission.PlacedBlocks;
import com.dwinovo.numen.core.permission.Verdict;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BuildCellRules {
    private final NumenPlayer player;
    private final BuildTaskRecord r;

    BuildCellRules(NumenPlayer player, BuildTaskRecord r) {
        this.player = player;
        this.r = r;
    }

    BlockState peek(BlockPos pos) {
        return Terrain.of((ServerPlayer)this.player).state(pos);
    }

    static boolean isAirTarget(BuildTaskRecord.Target target) {
        return target.block() == Blocks.AIR;
    }

    boolean costsMaterial(BuildTaskRecord.Target target) {
        return !BuildCellRules.isAirTarget(target) && target.costsMaterial();
    }

    boolean blockedByMode(BuildTaskRecord.Target target) {
        return this.blocked(target, false);
    }

    boolean refused(BuildTaskRecord.Target target) {
        return this.blocked(target, true);
    }

    private boolean blocked(BuildTaskRecord.Target target, boolean askIsOpen) {
        BlockState current = this.peek(target.pos());
        if (!target.mode().allows(current, target.desiredState())) {
            return true;
        }
        if (target.removes() != null && !current.isAir() && !current.is(target.removes())) {
            return true;
        }
        if (target.matches(current)) {
            return false;
        }
        for (Action action : this.actionsFor(target)) {
            Verdict verdict = Permission.judge((NumenPlayer)this.player, (Action)action);
            if (verdict.allowed() || askIsOpen && verdict.asks()) continue;
            return true;
        }
        return false;
    }

    List<Action> actionsFor(BuildTaskRecord.Target target) {
        BlockState otherState;
        BlockPos other;
        BlockPos pos = target.pos();
        BlockState current = this.peek(pos);
        ArrayList<Action> actions = new ArrayList<Action>(3);
        if (!current.isAir()) {
            actions.add(Action.breakBlock(pos, (BlockState)current));
        }
        if (!BuildCellRules.isAirTarget(target)) {
            actions.add(Action.place(pos, (BlockState)current, (Item)target.item()));
        }
        if ((other = PlacedBlocks.otherHalfOf(pos, (BlockState)target.desiredState())) != null && !(otherState = this.peek(other)).isAir()) {
            actions.add(Action.breakBlock(other, (BlockState)otherState));
        }
        return actions;
    }

    boolean hopeless(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        if (!this.player.level().getWorldBorder().isWithinBounds(pos)) {
            return true;
        }
        this.player.level();
        if (Level.isOutsideBuildHeight(pos)) {
            return true;
        }
        return this.unbreakableAt(pos, target.desiredState());
    }

    private boolean unbreakableAt(BlockPos pos, BlockState desired) {
        Level level = this.player.level();
        if (this.peek(pos).getDestroySpeed((BlockGetter)level, pos) == -1.0f) {
            return true;
        }
        BlockPos other = PlacedBlocks.otherHalfOf(pos, (BlockState)desired);
        return other != null && this.peek(other).getDestroySpeed((BlockGetter)level, other) == -1.0f;
    }

    boolean blockedByEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape((BlockGetter)this.player.level(), pos, CollisionContext.of((Entity)this.player));
        if (shape.isEmpty()) {
            return false;
        }
        VoxelShape placed = shape.move((double)pos.getX(), (double)pos.getY(), (double)pos.getZ());
        AABB body = this.player.getBoundingBox();
        for (AABB piece : placed.toAabbs()) {
            if (!piece.intersects(body)) continue;
            return true;
        }
        return !this.player.level().isUnobstructed((Entity)this.player, placed);
    }
}

