package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.build.BlueprintSafety;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.task.build.BuildInventory;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.TickPriority;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

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
 * 收工阶段的摆设与善后:生成图纸里的实体(展示框、盔甲架、画)、
 * 给工地外围的水重排流体 tick。付不起的摆设与身上带的东西按件记账
 * ({@link #skippedFixtures()}/{@link #skippedPayloads()}),不静默消失。
 */

final class BuildFixtures {
    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildInventory inv;
    private int skippedFixtures;
    private int skippedPayloads;

    BuildFixtures(NumenPlayer player, BuildTaskRecord r, BuildInventory inv) {
        this.player = player;
        this.r = r;
        this.inv = inv;
    }

    int skippedFixtures() {
        return this.skippedFixtures;
    }

    int skippedPayloads() {
        return this.skippedPayloads;
    }

    void spawnAll() {
        Object object;
        if (this.r.entities.isEmpty() || !((object = this.player.level()) instanceof ServerLevel)) {
            return;
        }
        ServerLevel level = (ServerLevel)object;
        for (BuildTaskRecord.EntitySpawn spawn : this.r.entities) {
            try {
                Optional created;
                Direction facing;
                boolean pays;
                if (this.alreadyThere(spawn)) continue;
                boolean bl = pays = this.r.consumeMaterials && spawn.item() != Items.AIR;
                if (pays && !this.inv.hasItems(spawn.item(), 1, true)) {
                    ++this.skippedFixtures;
                    continue;
                }
                List<ItemStack> carried = spawn.payload();
                boolean paidCarried = true;
                if (this.r.consumeMaterials) {
                    for (ItemStack want : carried) {
                        if (this.inv.strictCount(want) >= 1) continue;
                        paidCarried = false;
                        break;
                    }
                }
                CompoundTag nbt = spawn.nbt().copy();
                if (!paidCarried) {
                    BlueprintSafety.stripPayload(nbt);
                    this.skippedPayloads += carried.size();
                }
                boolean hangs = !"minecraft:armor_stand".equals(nbt.getString("id"));
                ListTag at = new ListTag();
                at.add(DoubleTag.valueOf((double)spawn.x()));
                at.add(DoubleTag.valueOf((double)spawn.y()));
                at.add(DoubleTag.valueOf((double)spawn.z()));
                nbt.put("Pos", (Tag)at);
                if (hangs) {
                    BlockPos anchor = new BlockPos(spawn.x(), spawn.y(), spawn.z());
                    nbt.putInt("TileX", anchor.getX());
                    nbt.putInt("TileY", anchor.getY());
                    nbt.putInt("TileZ", anchor.getZ());
                }
                if (nbt.contains("facing")) {
                    facing = Direction.from2DDataValue((int)nbt.getByte("facing"));
                    nbt.putByte("facing", (byte)spawn.rotation().rotate(facing).get2DDataValue());
                }
                if (nbt.contains("Facing")) {
                    facing = Direction.from3DDataValue((int)nbt.getByte("Facing"));
                    nbt.putByte("Facing", (byte)spawn.rotation().rotate(facing).get3DDataValue());
                }
                if ((created = EntityType.create((CompoundTag)nbt, (Level)level)).isEmpty()) continue;
                Entity entity = (Entity)created.get();
                float yaw = hangs ? entity.yRot : entity.rotate(spawn.rotation());
                entity.moveTo(spawn.x(), spawn.y(), spawn.z(), yaw, entity.xRot);
                entity.setUUID(UUID.randomUUID());
                level.addFreshEntity(entity);
                if (pays) {
                    this.inv.consumeOne(spawn.item());
                }
                if (!paidCarried || !this.r.consumeMaterials) continue;
                for (ItemStack want : carried) {
                    this.inv.consumeStrict(want);
                }
            }
            catch (RuntimeException runtimeException) {
            }
        }
    }

    boolean alreadyThere(BuildTaskRecord.EntitySpawn spawn) {
        Level level = this.player.level();
        if (!(level instanceof ServerLevel)) {
            return false;
        }
        ServerLevel level2 = (ServerLevel)level;
        Optional type = EntityType.by((CompoundTag)spawn.nbt());
        if (type.isEmpty()) {
            return false;
        }
        AABB box = new AABB(spawn.x() - 0.5, spawn.y() - 0.5, spawn.z() - 0.5, spawn.x() + 0.5, spawn.y() + 0.5, spawn.z() + 0.5);
        return !level2.getEntities((EntityType)type.get(), box, e -> true).isEmpty();
    }

    void nudgeSurroundingWater(BlockPos siteMin, BlockPos siteMax) {
        Level level;
        if (this.r.targets.stream().noneMatch(t -> t.mode() == ReplaceMode.REPLACE_EMPTY) || !((level = this.player.level()) instanceof ServerLevel)) {
            return;
        }
        ServerLevel level2 = (ServerLevel)level;
        BlockPos.betweenClosedStream(siteMin.offset(-1, -1, -1), siteMax.offset(1, 1, 1)).filter(pos -> pos.getX() < siteMin.getX() || pos.getX() > siteMax.getX() || pos.getY() < siteMin.getY() || pos.getY() > siteMax.getY() || pos.getZ() < siteMin.getZ() || pos.getZ() > siteMax.getZ()).filter(pos -> level2.isLoaded(pos) && level2.getFluidState(pos).getType().isSame(Fluids.WATER)).forEach(pos -> level2.getLiquidTicks().scheduleTick(pos.immutable(), Fluids.WATER, Fluids.WATER.getTickDelay(level2), TickPriority.NORMAL));
    }
}

