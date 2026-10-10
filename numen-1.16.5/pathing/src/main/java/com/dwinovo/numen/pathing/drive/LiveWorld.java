package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.world.Bounds;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

/**
 * 活世界:执行复核读的视图,前提函数在它上面判与在搜索快照上判是同一份。只读已经加载的区块,不触发加载或生成;没加载的
 * 列由 {@link #isLoaded} 答否,执行碰到它们就停在边上,不往里走。
 *
 * <p>只在世界所在的线程上用。
 */
public final class LiveWorld implements WorldView {

    private static final BlockState VOID = Blocks.VOID_AIR.defaultBlockState();

    private final ServerLevel level;

    public LiveWorld(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return level;
    }

    /** {@code (x, z)} 这一列所在的区块已经加载。 */
    public boolean isLoaded(int x, int z) {
        return chunk(x, z) != null;
    }

    private LevelChunk chunk(int x, int z) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        LevelChunk chunk = chunk(pos.getX(), pos.getZ());
        return chunk == null ? VOID : chunk.getBlockState(pos);
    }

    /** 没加载的区块读出来是虚空空气,建筑高度之外是空气;其余看区段自己数的非空气方块数。 */
    @Override
    public boolean airSection(int x, int y, int z) {
        LevelChunk chunk = chunk(x, z);
        if (chunk == null) {
            return true;
        }
        // 1.16.5 的 ChunkAccess 没有 getSectionIndex / getSectionsCount（那是 1.17+ 的 LevelHeightAccessor）：
        // 建筑高度固定 0..256，最低区段恒为 0，所以区段下标就是 y 的区段坐标，长度取 getSections().length。
        LevelChunkSection[] sections = chunk.getSections();
        int index = SectionPos.blockToSectionCoord(y);
        return index < 0 || index >= sections.length || sections[index].isEmpty();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        LevelChunk chunk = chunk(pos.getX(), pos.getZ());
        return chunk == null ? null : chunk.getBlockEntity(pos);
    }

    @Override
    public int getHeight() {
        // 1.16.5 的 Level 上没有 getHeight()（那是 1.17+ 的 LevelHeightAccessor），只有 getMaxBuildHeight()。
        // 建筑高度固定从 0 起，所以高度就是它。
        return level.getMaxBuildHeight();
    }

    @Override
    public int getMinBuildHeight() {
        // 1.16.5 建筑高度固定 0..256，没有 getMinBuildHeight()（1.17+）。
        return Bounds.MIN_BUILD_HEIGHT;
    }

    @Override
    public WorldBorder border() {
        return level.getWorldBorder();
    }

    @Override
    public boolean ultraWarm() {
        return level.dimensionType().ultraWarm();
    }
}
