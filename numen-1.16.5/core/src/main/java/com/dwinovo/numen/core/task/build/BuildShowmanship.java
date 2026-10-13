package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.task.build.BuildInventory;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Hotbar;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

final class BuildShowmanship {
    private static final int SWING_PERIOD_TICKS = 6;
    private static final int CONJURE_TRAIL_SAMPLES = 6;
    private static final int PARTICLE_BUDGET_PER_BATCH = 3;
    private final NumenPlayer player;
    private final BuildInventory inv;
    private int swingCooldown;
    private boolean crouching;

    BuildShowmanship(NumenPlayer player, BuildInventory inv) {
        this.player = player;
        this.inv = inv;
    }

    void stand() {
        this.player.controls().stop();
        this.player.controls().set(Controls.Key.SNEAK, this.crouching);
    }

    void performWork(List<BlockPos> touched, BlockState sample) {
        ServerLevel level;
        boolean swung;
        Vec3 centre;
        block9: {
            block8: {
                Level level2;
                int slot;
                centre = Vec3.ZERO;
                for (BlockPos pos : touched) {
                    centre = centre.add((double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5);
                }
                centre = centre.scale(1.0 / (double)touched.size());
                this.applySteppedAim(centre);
                this.crouching = centre.y < this.player.getY() + 0.6;
                boolean bl = swung = --this.swingCooldown <= 0;
                if (swung) {
                    this.player.swing(InteractionHand.MAIN_HAND);
                    this.swingCooldown = 6;
                }
                if (sample != null && (slot = this.inv.findSlot(sample.getBlock().asItem(), true)) >= 0) {
                    Hotbar.hold((ServerPlayer)this.player, (int)slot);
                }
                if (!((level2 = this.player.level()) instanceof ServerLevel)) break block8;
                level = (ServerLevel)level2;
                if (sample != null) break block9;
            }
            return;
        }
        if (swung) {
            this.emitConjureTrail(level, centre);
        }
        int spouts = Math.min(3, touched.size());
        int step = Math.max(1, touched.size() / spouts);
        for (int i = 0; i < touched.size(); i += step) {
            BlockPos pos = touched.get(i);
            level.sendParticles((ParticleOptions)new BlockParticleOption(ParticleTypes.BLOCK, sample), (double)pos.getX() + 0.5, (double)pos.getY() + 0.5, (double)pos.getZ() + 0.5, 4, 0.28, 0.28, 0.28, 0.0);
        }
        SoundEvent sound = sample.getSoundType().getPlaceSound();
        BlockPos at = touched.get(touched.size() / 2);
        level.playSound(null, at, sound, SoundSource.BLOCKS, 0.7f, 0.9f + this.player.getRandom().nextFloat() * 0.2f);
    }

    void celebrate(BlockPos siteMin, BlockPos siteMax) {
        Level level = this.player.level();
        if (level instanceof ServerLevel) {
            ServerLevel level2 = (ServerLevel)level;
            level2.sendParticles((ParticleOptions)ParticleTypes.HAPPY_VILLAGER, (double)(siteMin.getX() + siteMax.getX()) / 2.0 + 0.5, (double)siteMax.getY() + 1.0, (double)(siteMin.getZ() + siteMax.getZ()) / 2.0 + 0.5, 24, (double)(siteMax.getX() - siteMin.getX()) / 3.0 + 1.0, 1.0, (double)(siteMax.getZ() - siteMin.getZ()) / 3.0 + 1.0, 0.0);
        }
    }

    private void emitConjureTrail(ServerLevel level, Vec3 to) {
        Vec3 from = this.player.getEyePosition(1.0f).add(this.player.getLookAngle().scale(0.6)).add(0.0, -0.3, 0.0);
        Vec3 step = to.subtract(from).scale(0.14285714285714285);
        for (int i = 1; i <= 6; ++i) {
            Vec3 p = from.add(step.scale((double)i));
            level.sendParticles((ParticleOptions)ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private void applySteppedAim(Vec3 point) {
        Aim.look((ServerPlayer)this.player, (Vec3)point);
    }
}

