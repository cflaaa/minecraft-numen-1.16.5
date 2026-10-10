package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.world.Bounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.FluidState;

/**
 * 搜索视图:派发搜索时在世界所在的线程上拷贝一块区域里已加载区块的方块段,工作线程只读这份拷贝,不碰活世界。
 * 拷贝的是每个非空区段的方块调色板(1.16.5 没有 {@code PalettedContainer.copy},见 {@link #copy}),全空的区段记为空气;世界边界与建筑高度一并抄下。
 * 区域是以一个区块为中心、边长 {@code 2·radius+1} 的正方形,没加载的区块留空,{@link #isLoaded} 答否。
 *
 * <p>拷贝之后只读,不再变;多个线程同时读是安全的。方块实体不拷,读出来都是 null——一格有没有方块实体是方块状态的事,
 * 里面装着什么只在世界所在的线程上读。
 */
public final class WorldSnapshot implements SearchView {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** 1.16.5 的建筑高度固定 {@code 0..256}，最低建筑高度与最低区段恒为 0，出处见 {@link Bounds#MIN_BUILD_HEIGHT}。 */
    private static final int MIN_BUILD_HEIGHT = Bounds.MIN_BUILD_HEIGHT;
    private static final int MIN_SECTION = 0;

    /** 一个区块的方块段从哪来:给出那个区块的全部区段,没加载为 null。只在世界所在的线程上调用。 */
    @FunctionalInterface
    public interface Sections {
        LevelChunkSection[] of(int chunkX, int chunkZ);
    }

    private final int minChunkX;
    private final int minChunkZ;
    private final int side;
    /** [区块][区段]:区块没加载为 null,区段全空为 null。 */
    private final PalettedContainer<BlockState>[][] chunks;
    private final int minBuildHeight;
    private final int height;
    private final int minSection;
    private final WorldBorder border;
    private final boolean ultraWarm;

    @SuppressWarnings("unchecked")
    private WorldSnapshot(Level heights, WorldBorder border, boolean ultraWarm, Sections source,
                          int centerX, int centerZ, int radius) {
        this.minChunkX = centerX - radius;
        this.minChunkZ = centerZ - radius;
        this.side = 2 * radius + 1;
        this.chunks = new PalettedContainer[side * side][];
        this.minBuildHeight = MIN_BUILD_HEIGHT;
        this.height = heights.getMaxBuildHeight();
        this.minSection = MIN_SECTION;
        this.border = copy(border);
        this.ultraWarm = ultraWarm;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                LevelChunkSection[] sections = source.of(minChunkX + i, minChunkZ + j);
                if (sections == null) {
                    continue;
                }
                PalettedContainer<BlockState>[] states = new PalettedContainer[sections.length];
                for (int s = 0; s < sections.length; s++) {
                    if (sections[s] != null && !sections[s].isEmpty()) {
                        states[s] = copy(sections[s]);
                    }
                }
                chunks[i * side + j] = states;
            }
        }
    }

    /** 调色板 NBT 的两个键名：{@code PalettedContainer} 的 NBT 读写要成对给同一组名字。 */
    private static final String KEY_PALETTE = "palette";
    private static final String KEY_DATA = "data";
    /** NBT 里 ListTag 的**元素**类型号：10 = CompoundTag（调色板每一项都是一个 CompoundTag）。 */
    private static final int TAG_COMPOUND = 10;

    /**
     * 拷一份区段的方块调色板。
     *
     * <p>1.20 的 {@code PalettedContainer} 自带 {@code copy()},**1.16.5 的没有**（既没有 {@code copy}
     * 也没有 {@code clone}）。
     *
     * <p>⚠️ 也**不能用二进制那条路**：{@code LevelChunkSection.read(FriendlyByteBuf)} 在合并 jar 里有，
     * 在**专用服务端的 jar 里被 ProGuard 裁掉了**（服务端只**写**区段、不**读**——读是收包那一方做的），
     * 调了就是 NoSuchMethodError。所以走 **NBT 那条路**：{@code write(CompoundTag, String, String)} 与
     * {@code read(ListTag, long[])} 在服务端**都在**，而且同样是一次数组拷贝（不是逐格复制）。
     *
     * <p>读写必须用**同一组键名**，调色板那一项是 `ListTag`、元素是 `CompoundTag`，所以
     * {@code getList} 的类型号传 {@link #TAG_COMPOUND}。
     */
    private static PalettedContainer<BlockState> copy(LevelChunkSection section) {
        PalettedContainer<BlockState> source = section.getStates();
        CompoundTag tag = new CompoundTag();
        source.write(tag, KEY_PALETTE, KEY_DATA);
        PalettedContainer<BlockState> target = new LevelChunkSection(0).getStates();
        target.read(tag.getList(KEY_PALETTE, TAG_COMPOUND), tag.getLongArray(KEY_DATA));
        return target;
    }

    /** 派发一次搜索时拷贝的半径(区块数):以起点所在区块为中心,边长 13 个区块,约两百格见方。 */
    public static final int SEARCH_RADIUS = 6;

    /** 为从 {@code start} 出发的一次搜索拷贝快照:以它所在的区块为中心、{@link #SEARCH_RADIUS} 为半径。必须在世界所在的线程上调用。 */
    public static WorldSnapshot around(Level level, BlockPos start) {
        return capture(level, SectionPos.blockToSectionCoord(start.getX()), SectionPos.blockToSectionCoord(start.getZ()),
                SEARCH_RADIUS);
    }

    /**
     * 拷贝 {@code level} 里以区块 {@code (centerX, centerZ)} 为中心、半径 {@code radius} 个区块的已加载区块。必须在
     * {@code level} 所在的线程上调用;不加载任何区块。
     */
    public static WorldSnapshot capture(Level level, int centerX, int centerZ, int radius) {
        return capture(level, level.getWorldBorder(), level.dimensionType().ultraWarm(), (cx, cz) -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            return chunk == null ? null : chunk.getSections();
        }, centerX, centerZ, radius);
    }

    /** 同上,方块段、建筑高度、世界边界与维度倒不倒得出水由调用方给出。 */
    public static WorldSnapshot capture(Level heights, WorldBorder border, boolean ultraWarm,
                                        Sections source, int centerX, int centerZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("半径不能为负:" + radius);
        }
        return new WorldSnapshot(heights, border, ultraWarm, source, centerX, centerZ, radius);
    }

    /** 世界边界抄一份静止的:正在缩放的边界按此刻的大小。 */
    private static WorldBorder copy(WorldBorder live) {
        WorldBorder copy = new WorldBorder();
        copy.setCenter(live.getCenterX(), live.getCenterZ());
        copy.setSize(live.getSize());
        return copy;
    }

    private PalettedContainer<BlockState>[] chunk(int x, int z) {
        int i = SectionPos.blockToSectionCoord(x) - minChunkX;
        int j = SectionPos.blockToSectionCoord(z) - minChunkZ;
        if (i < 0 || j < 0 || i >= side || j >= side) {
            return null;
        }
        return chunks[i * side + j];
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return chunk(x, z) != null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        PalettedContainer<BlockState>[] states = chunk(pos.getX(), pos.getZ());
        if (states == null) {
            return AIR;
        }
        int index = SectionPos.blockToSectionCoord(pos.getY()) - minSection;
        if (index < 0 || index >= states.length || states[index] == null) {
            return AIR;
        }
        return states[index].get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
    }

    /** 没加载的区块、全空的区段(拷贝时记为空)、建筑高度之外,读出来都是空气。 */
    @Override
    public boolean airSection(int x, int y, int z) {
        PalettedContainer<BlockState>[] states = chunk(x, z);
        if (states == null) {
            return true;
        }
        int index = SectionPos.blockToSectionCoord(y) - minSection;
        return index < 0 || index >= states.length || states[index] == null;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minBuildHeight;
    }

    @Override
    public WorldBorder border() {
        return border;
    }

    @Override
    public boolean ultraWarm() {
        return ultraWarm;
    }
}
