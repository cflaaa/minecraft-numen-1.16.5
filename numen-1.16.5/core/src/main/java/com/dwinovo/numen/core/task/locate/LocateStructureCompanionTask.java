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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.StructureFeatureConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Goal for {@code locate structure}: find the nearest instance of a structure
 * (by id) in the entity's CURRENT dimension — vanilla {@code /locate structure}
 * semantics, but <b>time-sliced across ticks instead of one synchronous call</b>.
 *
 * <h2>Why not {@code findNearestMapFeature}</h2>
 * 1.16.5 的原版 {@code /locate structure} 走
 * {@link ChunkGenerator#findNearestMapFeature}. 它自己也就是一个环状螺旋,但每到一个候选区块就
 * {@code level.getChunk(pos, STRUCTURE_STARTS)} —— 当场把区块生成出来,再读存下的
 * {@code StructureStart}。未加载的区块会因此跑一整趟世界生成,主线程干等;单 tick 超六十秒就被
 * 看门狗判定崩溃。这个任务沿用上游的解法:自己走螺旋、自己判存在,<b>一块区块都不加载</b>。
 *
 * <h2>存在性判据 = 真实生成用的那一趟</h2>
 * 生成时判定"这一格长不长这个结构"的地方是
 * {@link StructureFeature#generate}:先 {@code getPotentialFeatureChunk} 播种随机数、定位到这一格,
 * 再对它在那格上的群系里登记着的每个 {@link ConfiguredStructureFeature} 问
 * {@code isFeatureChunk}。这里照抄这两步(同一个 {@code WorldgenRandom}、同一个群系取法),
 * 所以答案与真实生成一致 —— 只是不做原版那一步把区块读出来。
 * {@code isFeatureChunk} 是 protected,由 access widener 开出来。
 *
 * <h2>据点走另一条路</h2>
 * stronghold 在 1.16.5 不在这张随机撒点表里(见 {@link net.minecraft.world.level.levelgen.StructureSettings})。
 * 它按同心环布点,环上的区块坐标由 {@code ChunkGenerator} 自己算好缓存着 —— 上游 1.20.1 那边是
 * {@code ConcentricRingsStructurePlacement};1.16.5 这边就是 {@code ChunkGenerator} 的
 * {@code strongholdPositions}(原版 {@code /locate stronghold} 走的正是这一支)。直接取环上最近的
 * 一个,既不加载区块,也和原版同源。
 *
 * <h2>收工看环数,不看时间</h2>
 * 搜多远由 {@link #SEARCH_RADIUS_RINGS} 定,每刻搜多少由 {@link SearchBudget} 定(机器慢、存档读得慢就少搜几个)。
 * 身体从头到尾站着等这次搜索({@link #awaitSearch}),任务期限不走——所以结论只取决于世界和问法:
 * 找到的就是环内最近的,找不到就是环内没有,不会因为机器慢而变成"搜到一半超时"。
 *
 * <h2>1.16.5 的一个收窄:{@code #tag} 不存在</h2>
 * 结构标签(以及群系标签)是随注册表标签在 1.19 才有的。1.16.5 的标签只有方块/物品/流体/实体四类,
 * 所以 {@code #minecraft:village} 这类写法在这一版没有对应物,只能按 id 找。
 */
public final class LocateStructureCompanionTask extends AbstractCompanionTask<LocateStructureTaskRecord> {

    /**
     * Search radius in placement-region RINGS, exactly vanilla /locate's
     * radius unit (one ring = one region = {@code spacing} chunks, so the
     * covered distance scales with the structure's rarity: fortress ≈ 43k
     * blocks, village ≈ 54k). This bounds the work; the global budget only paces it.
     */
    private static final int SEARCH_RADIUS_RINGS = 100;

    /** One structure's candidate stream (a random-spread placement's ring walk). */
    private static final class Job {
        final StructureFeature<?> feature;
        final StructureFeatureConfiguration config;
        /** 与真实生成同一个用法:候选定位把它播种,随后原样交给 isFeatureChunk。 */
        final WorldgenRandom rand = new WorldgenRandom();
        long seed;
        int spacing;
        int centerRegX, centerRegZ, maxRing, ring, perimIdx;

        Job(StructureFeature<?> feature, StructureFeatureConfiguration config) {
            this.feature = feature;
            this.config = config;
        }

        /** Next candidate chunk, or null when this job is exhausted. */
        ChunkPos next() {
            while (ring <= maxRing) {
                if (perimIdx >= RingSpiral.perimeter(ring)) {
                    ring++;
                    perimIdx = 0;
                    continue;
                }
                int[] d = RingSpiral.offset(ring, perimIdx++);
                // getPotentialFeatureChunk 收的是 CHUNK 坐标、自己按 spacing 取整,
                // 所以把区域序号乘回 spacing 再交进去 —— 与上游翻数一模一样。
                return feature.getPotentialFeatureChunk(config, seed, rand,
                        (centerRegX + d[0]) * spacing,
                        (centerRegZ + d[1]) * spacing);
            }
            return null;
        }
    }

    private final List<Job> jobs = new ArrayList<>();
    private boolean ringOnly;              // stronghold 那一支:环上取点,没有螺旋
    private int jobIndex;
    private ChunkPos pendingCandidate;     // budget ran out mid-candidate; resume here
    private BlockPos best;
    private double bestDistSqr = Double.MAX_VALUE;
    private String failReason = "not on a server level";

    public LocateStructureCompanionTask(NumenPlayer player, LocateStructureTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        jobs.clear();
        ringOnly = false;
        jobIndex = 0;
        pendingCandidate = null;
        best = null;
        bestDistSqr = Double.MAX_VALUE;

        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return;
        }
        StructureFeature<?> feature = resolveFeature(sl, r.structure.trim());
        if (feature == null) {
            fail(failReason, FailureType.UNKNOWN);   // failReason set by resolveFeature
            return;
        }
        ChunkGenerator gen = sl.getChunkSource().getGenerator();
        BiomeSource biomeSource = gen.getBiomeSource();
        // 与 findNearestMapFeature 同一个前置:这个维度的群系源根本生不出它,就没什么可找的。
        if (!biomeSource.canGenerateStructure(feature)) {
            fail(notInDimension(), FailureType.UNKNOWN);
            return;
        }
        String dim = player.level().dimension().location().getPath();

        // 据点:同心环,直接取环上最近的一个(原版 /locate stronghold 同一支)。
        if (feature == StructureFeature.STRONGHOLD) {
            ringOnly = true;
            gen.generateStrongholds();
            for (ChunkPos cp : gen.strongholdPositions) {
                consider(cp.getWorldPosition());
            }
            if (best == null) {
                failReason = "no stronghold in this world (" + dim + ")";
            }
            return;
        }

        StructureFeatureConfiguration config = gen.getSettings().getConfig(feature);
        if (config == null) {
            fail(notInDimension(), FailureType.UNKNOWN);
            return;
        }
        Job job = new Job(feature, config);
        job.seed = sl.getSeed();
        job.spacing = config.spacing();
        ChunkPos here = player.chunkPosition();
        job.centerRegX = Math.floorDiv(here.x, job.spacing);
        job.centerRegZ = Math.floorDiv(here.z, job.spacing);
        job.maxRing = SEARCH_RADIUS_RINGS;
        jobs.add(job);
    }

    private String notInDimension() {
        String dim = player.level().dimension().location().getPath();
        return r.structure + " does not generate IN THIS DIMENSION (" + dim + ")"
                + " — fortress/bastion: nether; end_city: the end;"
                + " stronghold/village/mansion/monument: overworld";
    }

    /**
     * 把参数解成结构本体。
     *
     * <p>1.16.5 没有结构标签,{@code #} 开头的写法只能明确地拒绝(而不是静默当成 id)。
     *
     * @return 解析出的结构;输入不认识时返回 null 并写好 {@link #failReason}
     */
    private StructureFeature<?> resolveFeature(ServerLevel sl, String arg) {
        if (arg.startsWith("#")) {
            failReason = "'" + arg + "' is a structure tag — 1.16.5 has no structure (or biome) tags;"
                    + " that whole layer arrived with registry tags in 1.19. Use a structure id instead,"
                    + " e.g. minecraft:village / minecraft:fortress / minecraft:stronghold";
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        if (id == null) {
            failReason = "unknown structure: " + arg
                    + " — use a structure id like minecraft:fortress / minecraft:village";
            return null;
        }
        // 1.16.5: structure_feature 是**静态**注册表(Registry.STRUCTURE_FEATURE 本体),
        // 不在 registryAccess 的动态注册表里——走 registryOrThrow 会炸 Missing registry
        // (与 StatusApi.self 同一结论,见那边注释)。
        Registry<StructureFeature<?>> structures = Registry.STRUCTURE_FEATURE;
        StructureFeature<?> feature = structures.getOptional(id).orElse(null);
        if (feature != null) {
            return feature;
        }
        Registry<Biome> biomes = sl.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        if (biomes.getOptional(id).isPresent()) {
            failReason = arg + " is a BIOME, not a structure — use locate biome " + arg + " instead";
            return null;
        }
        String suggestion = IdSuggest.closest(structures.keySet().stream(), arg);
        failReason = "unknown structure: " + arg
                + (suggestion != null
                        ? " — did you mean " + suggestion + "?"
                        : " — use a structure id like minecraft:fortress / "
                                + "minecraft:stronghold; the world_atlas skill lists every id");
        return null;
    }

    @Override
    protected TaskState onTick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        if (ringOnly) {
            return TaskState.SUCCESS;   // 环上取点在 onStart 一趟做完,这里只负责收工
        }
        ChunkGenerator gen = sl.getChunkSource().getGenerator();
        BiomeSource biomeSource = gen.getBiomeSource();
        // GLOBAL budget: shared by every searching companion on the server, so
        // total per-tick search cost is a constant regardless of pet count.
        try (SearchBudget.Slice slice = SearchBudget.slice(sl.getServer())) {
            while (true) {
                if (jobIndex >= jobs.size()) {
                    return TaskState.SUCCESS;
                }
                Job job = jobs.get(jobIndex);
                ChunkPos candidate = pendingCandidate != null ? pendingCandidate : job.next();
                pendingCandidate = null;
                if (candidate == null) {
                    jobIndex++;
                    continue;
                }
                if (!SearchBudget.tryCheck()) {
                    pendingCandidate = candidate;   // pool drained — resume next tick
                    awaitSearch();
                    return TaskState.RUNNING;
                }
                if (checkCandidate(gen, biomeSource, job, candidate)) {
                    consider(candidate.getWorldPosition());
                    jobIndex++;   // ring order ⇒ first hit is this job's nearest
                }
            }
        }
    }

    /**
     * 这一格上会不会长出这个结构。
     *
     * <h2>一块区块都不加载</h2>
     * 把 {@link StructureFeature#generate} 里那两步照抄过来:
     * <ol>
     *   <li>群系取法与 {@code ChunkGenerator.createStructures} 完全一致
     *       (候选区块中心,quart 坐标:({@code chunkX << 2) + 2})。原版那段也是这个式子
     *       —— 不是区块原点,是中心;</li>
     *   <li>在那格群系登记着的 {@link ConfiguredStructureFeature} 里挑出这个结构的每一款
     *       (一个结构可以有多个按群系不同的配置,如村庄),对每一款问
     *       {@code isFeatureChunk}(随机数状态由上面那次 {@code getPotentialFeatureChunk} 播种,
     *       与生成时同一趟)。</li>
     * </ol>
     * 原版到这一步之后还会 {@code getChunk(pos, STRUCTURE_STARTS)} 把区块读出来、只为拿
     * {@code StructureStart#getLocatePos}/引用登记 —— 那一步才是同步生成区块的所在,我们不进。
     * 坐标就取候选区块原点:与上游 1.20.1 一样是个按 ring 为精度的近似,结果消息里已经说了
     * "goes there (it finds the height on its own)"。
     */
    private boolean checkCandidate(ChunkGenerator gen, BiomeSource biomeSource, Job job, ChunkPos candidate) {
        Biome biome = biomeSource.getNoiseBiome((candidate.x << 2) + 2, 0, (candidate.z << 2) + 2);
        for (Supplier<ConfiguredStructureFeature<?, ?>> configured
                : biome.getGenerationSettings().structures()) {
            ConfiguredStructureFeature<?, ?> cf = configured.get();
            if (cf.feature != job.feature) {
                continue;
            }
            if (featureChunkAt(job.feature, gen, biomeSource, job.seed, job.rand,
                    candidate, biome, cf.config)) {
                return true;
            }
        }
        return false;
    }

    /** 泛型擦除后的直呼:{@code isFeatureChunk} 的最后一参在这一版是结构自己的配置。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean featureChunkAt(StructureFeature feature, ChunkGenerator gen, BiomeSource src,
                                          long seed, WorldgenRandom rand, ChunkPos candidate, Biome biome,
                                          FeatureConfiguration config) {
        return feature.isFeatureChunk(gen, src, seed, rand, candidate.x, candidate.z, biome, candidate, config);
    }

    private void consider(BlockPos pos) {
        double d = pos.distSqr(player.blockPosition());
        if (d < bestDistSqr) {
            bestDistSqr = d;
            best = pos;
        }
    }

    /** Search tasks paint no path overlay — nothing to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Located value() {
        String dim = player.level().dimension().location().toString();
        return best != null ? Located.at(best, player.blockPosition(), dim) : Located.none(searchedRadiusBlocks(), dim);
    }

    @Override
    protected String successMessage() {
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            String dir = CompassUtil.compass(dx, dz);
            return "nearest " + r.structure + " at " + best.getX() + ","
                    + best.getY() + "," + best.getZ() + " (" + dir + ", ~" + dist
                    + " blocks). " + com.dwinovo.numen.core.nav.NavText.gotoCall(new com.dwinovo.numen.core.sdk.Place(best.getX(), null,
                    best.getZ()), "") + " goes there (it finds the "
                    + "height on its own), then `numen.scan.blocks` finds its actual blocks.";
        }
        if (ringOnly) {
            String dim = player.level().dimension().location().getPath();
            return failReason != null ? failReason
                    : "no stronghold in this world (" + dim + ")";
        }
        String dim = player.level().dimension().location().getPath();
        int searched = searchedRadiusBlocks();
        return searched == 0
                ? notInDimension()
                : "no " + r.structure + " within ~" + searched
                        + " blocks of here (" + dim + ") — extremely unlucky seed; "
                        + "travel a few thousand blocks and retry";
    }

    @Override
    protected String cancelledMessage() {
        return r.getToolName() + " interrupted";
    }

    /** How far outward (blocks) the ring walk has covered so far. */
    private int searchedRadiusBlocks() {
        int max = 0;
        for (Job job : jobs) {
            int rings = Math.min(job.ring, job.maxRing);
            max = Math.max(max, rings * job.spacing * 16);
        }
        return max;
    }
}
