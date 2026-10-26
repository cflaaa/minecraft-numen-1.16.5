package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.sdk.BlockAt;
import com.dwinovo.numen.core.sdk.Doc;
import com.dwinovo.numen.core.sdk.Methods;
import com.dwinovo.numen.core.sdk.Pending;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import com.dwinovo.numen.core.scan.BlockSearch;

/**
 * {@code numen.scan.blocks} 的实现(登记在 {@link com.dwinovo.numen.core.tools.perception.ScanApi})。看是按刻分片的
 * ({@link BlockScan}),看完的那一刻才有值。
 *
 * <p>结果是一串团({@link Cluster}):相连的命中格(对角也算)成一团,由近及远;每团带着它的每一格(方块与位置,近的在前)、最近的那一格与格数。
 * 只是看,什么也不存:要再用,程序就拿着这份结果,或再看一次。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;

    private ScanOps() {}

    /** 一团:扫描找到的相连的方块。方法(filter、minus)写在模块 {@code numen.scan} 里。 */
    @Doc("Touching blocks a scan found (diagonals count): every block, nearest first.")
    @Methods("numen.scan")
    public record Cluster(@Doc("Every block of it, nearest first.") List<BlockAt> blocks,
                          @Doc("The block nearest to where you stood.") BlockAt nearest,
                          @Doc("How many blocks.") int count) {}

    /**
     * 看一次,看完那一刻有值:全部的团,由近及远。没看全时(没加载的区块、搜到上限)那句话随值交回,写进程序的回执:只交一串团,
     * 分不清"半径里没有铁"和"半径里大半没看",她每次都会读成前一种。
     */
    public static Pending<List<Cluster>> scan(NumenPlayer self, int radius, Set<Block> targets) {
        Pending<List<Cluster>> out = Pending.create();
        BlockScan.start(self, Mth.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS), targets, found -> {
            String note = coverageNote(found.coverage());
            if (note != null) {
                out.report("Only part of the radius was read: " + note + ".");
            }
            out.complete(found.groups().stream().map(ScanOps::cluster).toList());
        });
        return out;
    }

    /** 一团:每一格是一个 Block(近的在前),最近的那一格,格数。 */
    static Cluster cluster(BlockGroups.Group group) {
        List<BlockAt> blocks = new ArrayList<>();
        group.cells().forEach((pos, state) -> blocks.add(BlockAt.of(pos, state)));
        return new Cluster(blocks, BlockAt.of(group.nearest(), group.cells().get(group.nearest())),
                group.cells().size());
    }

    /**
     * 这次看实际看到了哪些,说给她的话——看全了是 null。
     */
    static String coverageNote(BlockSearch.ScanResult res) {
        List<String> notes = new ArrayList<>(3);
        String capped = res.sectionCapNote();
        if (capped != null) {
            notes.add(capped);
        }
        if (res.collectCapHit()) {
            notes.add("stopped at " + BlockSearch.MAX_COLLECT + " matching blocks — only the part nearest you "
                    + "was read and clusters at its edge may be cut off; scan a smaller radius");
        }
        if (res.columnsUnloaded() > 0) {
            notes.add(res.columnsUnloaded() + " of " + res.columnsTotal() + " chunk columns in this "
                    + "radius are not searched — blocks out there are "
                    + "UNKNOWN, not absent; walk that way and scan again to find out");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /**
     * 找最近的空闲床头。
     * <p>同步阻塞扫描 {@code #minecraft:beds}，返回最近的、床头未被占用的 {@code BlockPos}。
     * 用于 {@link com.dwinovo.numen.core.task.chain.SleepChain} 反射。
     *
     * @param self 同伴
     * @param radius 搜索半径（方块）
     * @return 最近的空闲床头位置，或 {@code Optional.empty()}
     */
    public static Optional<BlockPos> findNearestBed(NumenPlayer self, int radius) {
        return findNearestBedSync(self, radius);
    }

    /**
     * 同步找最近的空闲床头（反射专用，不走异步 Pending）。
     */
    private static Optional<BlockPos> findNearestBedSync(NumenPlayer self, int radius) {
        Level level = self.level();
        BlockPos center = self.blockPosition();
        int r = Mth.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS);
        
        // 从近到远遍历（按曼哈顿距离分层），找第一张空闲床
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    // 只检查当前层的边界（曼哈顿距离 = d 的格子）
                    if (Math.abs(dx) + Math.abs(dz) != d) continue;
                    
                    for (int dy = -2; dy <= 2; dy++) { // 床通常在同一高度或上下 1-2 格
                        BlockPos pos = center.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.getBlock() instanceof BedBlock) {
                            // 确认是床头
                            if (state.hasProperty(BedBlock.PART) 
                                    && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                                // 检查是否被占用：同一位置是否有实体在睡觉
                                if (!isBedOccupied(level, pos)) {
                                    return Optional.of(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 检查床头位置是否被实体占用（有玩家/同伴正在睡这张床）。
     */
    private static boolean isBedOccupied(Level level, BlockPos bedHead) {
        // 遍历附近实体，看有没有 isSleeping() 且 sleepingPos == bedHead
        var entities = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(bedHead).inflate(2.0),
                e -> e.isSleeping() && e.getSleepingPos().equals(bedHead));
        return !entities.isEmpty();
    }
}
