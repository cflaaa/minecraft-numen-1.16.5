package com.dwinovo.numen.core.task.locate;
import com.dwinovo.numen.core.task.IdSuggest;
import com.dwinovo.numen.core.task.CompassUtil;
import com.dwinovo.numen.core.scan.SearchBudget;
import com.dwinovo.numen.core.scan.RingSpiral;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.core.task.TaskState;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;

import java.util.LinkedHashSet;
import java.util.function.Predicate;

/**
 * Goal for {@code locate biome}: find the nearest instance of a biome (by id)
 * in the entity's CURRENT dimension — vanilla {@code /locate biome} semantics,
 * time-sliced across ticks.
 *
 * <h2>The Nature's Compass model</h2>
 * Biomes need no chunks at all: {@link BiomeSource#getNoiseBiome} answers from
 * climate noise alone, so unlike the structure locator there is no expensive
 * fallback — just bounded sampling. Mirrors Nature's Compass (MattCzyr):
 * <ul>
 *   <li>sample on a {@value #SAMPLE_STEP_BLOCKS}-block grid, walked as an
 *       expanding square ring spiral (their worker walks the same square,
 *       turn by turn; a ring is the same set of points);</li>
 *   <li>probe SEVERAL Y levels per column (stepping outward from the entity's
 *       own Y) — Nether and cave biomes are 3D, a single-Y scan misses warped
 *       forests under/above you;</li>
 *   <li>per-tick work caps via the GLOBAL {@link SearchBudget}
 *       shared with structure searches (NC uses a tick worker; same idea).</li>
 * </ul>
 * Coverage: {@value #SEARCH_RADIUS_RINGS} rings × {@value #SAMPLE_STEP_BLOCKS}
 * blocks = 6400 blocks, exactly vanilla /locate biome's radius; NC's default
 * reach is 10k with the same 64-block grid. Worst-case full miss ≈ 40k samples
 * ≈ 160 budgeted ticks ≈ 8s.
 *
 * <h2>1.16.5: quart coordinates, and no biome tags</h2>
 * {@code getNoiseBiome} 在这一版收的是 <b>quart 坐标</b>({@code block >> 2}),而且没有
 * {@code Sampler} 参数。另外 1.16.5 没有群系标签(与结构标签同源,都是 1.19 才随注册表标签出现),
 * 所以 {@code #minecraft:is_forest} 这类写法在这一版没有对应物,只能按 id 找。
 *
 * <p>收工看环数,不看时间:身体从头到尾站着等这次搜索({@link #awaitSearch}),任务期限不走,
 * 机器慢只是答案晚几刻,不会变成"搜到一半超时"。
 */
public final class LocateBiomeCompanionTask extends AbstractCompanionTask<LocateBiomeTaskRecord> {

    /** Sample grid pitch — NC's default (16 × biome size 4). Vanilla /locate uses 32. */
    private static final int SAMPLE_STEP_BLOCKS = 64;
    /** Rings of samples; 100 × 64 = 6400 blocks, vanilla /locate biome's radius. */
    private static final int SEARCH_RADIUS_RINGS = 100;
    /** Vertical probe pitch within a sample column (NC uses the same 64). */
    private static final int Y_STEP_BLOCKS = 64;
    /** 1.16.5 世界高度 0..255,探针留在里头。 */
    private static final int MIN_Y = 1;
    private static final int MAX_Y = 254;

    private Predicate<Biome> match;
    private BiomeSource biomeSource;
    private int[] yBlocks;          // probe heights, ordered outward from entity Y
    private int centerX, centerZ;   // block coords of the search origin
    private int ring, perimIdx;
    private boolean exhausted;
    private BlockPos best;
    private String failReason = "not on a server level";

    public LocateBiomeCompanionTask(NumenPlayer player, LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        match = null;
        best = null;
        ring = 0;
        perimIdx = 0;
        exhausted = false;

        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return;
        }
        match = resolveBiomePredicate(sl, r.biome.trim());
        if (match == null) {
            fail(failReason, FailureType.UNKNOWN);   // failReason set by resolveBiomePredicate
            return;
        }
        biomeSource = sl.getChunkSource().getGenerator().getBiomeSource();
        yBlocks = probeHeights(player.getBlockY());
        centerX = player.getBlockX();
        centerZ = player.getBlockZ();
    }

    /**
     * 探针高度:从她自己的 Y 往外一层层 64 格,覆盖到世界上下界。
     *
     * <p>1.20.1 有 {@code Mth.outFromOrigin} 现成做这件事;1.16.5 没有,而且这一版世界高度
     * 是写死的 0..255(没有 {@code getMinBuildHeight}/{@code getMaxBuildHeight}),照它的意思
     * 手算一份:先她所在的这一层,然后上下交替往外推。
     */
    private static int[] probeHeights(int originY) {
        int here = Mth.clamp(originY, MIN_Y, MAX_Y);
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        out.add(here);
        for (int d = Y_STEP_BLOCKS; d <= MAX_Y - MIN_Y; d += Y_STEP_BLOCKS) {
            if (here + d <= MAX_Y) {
                out.add(here + d);
            }
            if (here - d >= MIN_Y) {
                out.add(here - d);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** @return a biome predicate, or null on bad input (failReason set). */
    private Predicate<Biome> resolveBiomePredicate(ServerLevel sl, String arg) {
        if (arg.startsWith("#")) {
            failReason = "'" + arg + "' is a biome tag — 1.16.5 has no biome (or structure) tags;"
                    + " that whole layer arrived with registry tags in 1.19. Use a biome id instead,"
                    + " e.g. minecraft:warped_forest / minecraft:desert";
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        if (id == null) {
            failReason = "unknown biome: " + arg
                    + " — use a biome id like minecraft:warped_forest / minecraft:desert";
            return null;
        }
        Registry<Biome> biomes = sl.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        if (biomes.getOptional(id).isEmpty()) {
            Registry<?> structures =
                    sl.registryAccess().registryOrThrow(Registry.STRUCTURE_FEATURE_REGISTRY);
            if (structures.getOptional(id).isPresent()) {
                failReason = arg + " is a STRUCTURE, not a biome — use locate structure " + arg + " instead";
                return null;
            }
            String suggestion = IdSuggest.closest(biomes.keySet().stream(), arg);
            failReason = "unknown biome: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a biome id like minecraft:warped_forest / "
                                    + "minecraft:desert; the world_atlas skill lists every id");
            return null;
        }
        // 按注册名比:群系源交回的实例就是注册表里那几个,但认 id 比认实例身份稳。
        return biome -> id.equals(biomes.getKey(biome));
    }

    @Override
    protected TaskState onTick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        try (SearchBudget.Slice slice = SearchBudget.slice(sl.getServer())) {
            while (true) {
                if (exhausted) {
                    return TaskState.SUCCESS;   // best == null → "not found"
                }
                if (!SearchBudget.tryBiomeSample()) {
                    awaitSearch();
                    return TaskState.RUNNING;    // pool drained — resume next tick
                }
                BlockPos hit = sampleNext();
                if (hit != null) {
                    best = hit;                  // ring order ⇒ first hit ≈ nearest
                    return TaskState.SUCCESS;
                }
            }
        }
    }

    /** Probe the next spiral column (all Y levels); non-null = matching pos. */
    private BlockPos sampleNext() {
        // Ring perimeter walk, same shape as the structure locator's spiral.
        while (perimIdx >= RingSpiral.perimeter(ring)) {
            ring++;
            perimIdx = 0;
            if (ring > SEARCH_RADIUS_RINGS) {
                exhausted = true;
                return null;
            }
        }
        int[] d = RingSpiral.offset(ring, perimIdx++);
        int x = centerX + d[0] * SAMPLE_STEP_BLOCKS;
        int z = centerZ + d[1] * SAMPLE_STEP_BLOCKS;
        for (int y : yBlocks) {
            // 1.16.5 的 getNoiseBiome 收 quart 坐标(block >> 2),且没有 Sampler 参数。
            Biome biome = biomeSource.getNoiseBiome(x >> 2, y >> 2, z >> 2);
            if (match.test(biome)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    /** Search tasks paint no path overlay — nothing to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Located value() {
        String dim = player.level().dimension().location().toString();
        return best != null ? Located.at(best, player.blockPosition(), dim)
                : Located.none(Math.min(ring, SEARCH_RADIUS_RINGS) * SAMPLE_STEP_BLOCKS, dim);
    }

    @Override
    protected String successMessage() {
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String dir = CompassUtil.compass(dx, dz);
            return "nearest " + r.biome + " around " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks; accurate to ~" + SAMPLE_STEP_BLOCKS + "). " + com.dwinovo.numen.core.nav.NavText.gotoCall(
                    new com.dwinovo.numen.core.sdk.Place(best.getX(), null, best.getZ()), "") + " goes there (it finds the height on its own), then confirm with "
                    + "`numen.scan.blocks` or `numen.scan.entities`.";
        }
        String dim = player.level().dimension().location().getPath();
        int searched = Math.min(ring, SEARCH_RADIUS_RINGS) * SAMPLE_STEP_BLOCKS;
        return "no " + r.biome + " within ~" + searched
                + " blocks IN THIS DIMENSION (" + dim + ") — check the biome's "
                + "home dimension (warped_forest/soul_sand_valley: nether; most "
                + "others: overworld) or travel a few thousand blocks and retry";
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted";
    }
}
