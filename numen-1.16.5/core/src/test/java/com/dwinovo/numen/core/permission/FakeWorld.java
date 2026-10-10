package com.dwinovo.numen.core.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.SerializationTags;
import net.minecraft.tags.TagCollection;
import net.minecraft.tags.TagContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;

import java.util.HashMap;
import java.util.Map;

/**
 * Map 后备的方块视图:权限层的测试只需要"这一格是什么"。
 *
 * <p>照上游逐字搬,1.16.5 适配两处:BlockGetter 没有 {@code getHeight}/{@code getMinBuildHeight}
 * (那是 1.17+ 的 LevelHeightAccessor);引导后给 {@link SerializationTags} 绑一个空标签容器——
 * 1.16.5 的标签按 SerializationTags 查,无头引导下没人往里装过容器,不绑的话第一条标签项就 NPE。
 */
final class FakeWorld implements BlockGetter {

    final Map<BlockPos, BlockState> blocks = new HashMap<>();

    void set(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    /** 无头引导;失败返回 false(测试跳过而不失败)。1.16.5:没有 tryDetectVersion(1.17+),直接 bootStrap。 */
    static boolean boot() {
        try {
            net.minecraft.server.Bootstrap.bootStrap();
            SerializationTags.bind(emptyTags());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 四类标签各给一个空集合:查不到的标签按"不命中"回答,与上游 Holder 语义一致。 */
    static TagContainer emptyTags() {
        return new TagContainer() {
            @Override public TagCollection<Block> getBlocks() { return TagCollection.empty(); }
            @Override public TagCollection<Item> getItems() { return TagCollection.empty(); }
            @Override public TagCollection<Fluid> getFluids() { return TagCollection.empty(); }
            @Override public TagCollection<EntityType<?>> getEntityTypes() { return TagCollection.empty(); }
        };
    }
}
