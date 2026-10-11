package com.dwinovo.numen.core.nav;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.core.permission.ConsentItem;
import com.dwinovo.numen.core.permission.Gate;
import com.dwinovo.numen.core.permission.Mode;
import com.dwinovo.numen.core.permission.PlacedBlocks;
import com.dwinovo.numen.core.permission.RuleSet;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy.Change;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.SerializationTags;
import net.minecraft.tags.SetTag;
import net.minecraft.tags.TagCollection;
import net.minecraft.tags.TagContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 寻路问"这一格能不能动"时，同伴这一侧怎么答：权限层的裁决原样变成许可——放行；要问，凭据是写给主人的那一条征询；
 * 拒绝，理由是裁决本身。出厂规则下，自然方块放行，带方块实体的（箱子）、玩家放的、门标签里的要问。
 *
 * <p>照上游 {@code core/common/.../nav/GateTerrainTest.java} 搬（2026-10-11 归位阶段 2），仅改包名。
 * 1.16.5 适配两处：{@code BlockGetter} 只有三件（{@code getBlockEntity}/{@code getBlockState}/{@code getFluidState}，
 * 没有 {@code getHeight}/{@code getMinBuildHeight}——那是 1.17+ 的 LevelHeightAccessor）；标签注入不走
 * {@code MappedRegistry.bindTags}（Holder 体系 1.18+ 才有），直接给 {@link SerializationTags} 绑一个新
 * {@link TagContainer}，测完绑回空容器（无头引导下没人装过容器，不绑的话第一条标签项就 NPE）。
 */
@Tag("mc")
class GateTerrainTest {

    private static final BlockPos CELL = new BlockPos(3, 64, 3);

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = bootOnce();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用，跳过地形许可钉桩");
    }

    private static Gate gate(PlacedBlocks placed) {
        return new Gate(null, Mode.ASK, RuleSet.EMPTY, RuleSet.factory(),
                placed, List.of());
    }

    private static Permit dig(Gate gate, BlockState state) {
        View view = new View();
        view.set(CELL, state);
        return new GateTerrain(gate).judge(Change.DIG, CELL, state, view);
    }

    @Test
    void naturalBlocksMayBeDug() {
        assertInstanceOf(Permit.Allow.class, dig(gate(new PlacedBlocks()), Blocks.DIRT.defaultBlockState()));
    }

    @Test
    void aChestNeedsConsentAndTheCredentialSaysWhy() {
        Permit permit = dig(gate(new PlacedBlocks()), Blocks.CHEST.defaultBlockState());
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, permit);
        ConsentItem item = assertInstanceOf(ConsentItem.class, ask.credential());
        assertTrue(item.cause().contains("has a block entity"), item.cause());
    }

    @Test
    void theSameDirtNeedsConsentOnceAPlayerPlacedIt() {
        PlacedBlocks placed = new PlacedBlocks();
        placed.record(CELL, new PlacedBlocks.Placer(UUID.randomUUID(), "Steve"));
        Permit.Ask ask = assertInstanceOf(Permit.Ask.class, dig(gate(placed), Blocks.DIRT.defaultBlockState()));
        assertTrue(((ConsentItem) ask.credential()).cause().contains("placed by a player"));
    }

    /** 出厂表点名的是门标签；标签内容运行时来自数据包，这里把石头绑进去，钉的是"标签那一行生效"。 */
    @Test
    void theFactoryDoorRowCoversWhateverTheDoorTagHolds() {
        bindBlockTags(Map.of(BlockTags.DOORS.getName(), List.of(Blocks.STONE)));
        try {
            assertInstanceOf(Permit.Ask.class, dig(gate(new PlacedBlocks()), Blocks.STONE.defaultBlockState()));
        } finally {
            bindBlockTags(Map.of());
        }
    }

    @Test
    void placingIntoOpenAirIsAllowed() {
        View view = new View();
        Permit permit = new GateTerrain(gate(new PlacedBlocks()))
                .judge(new Change.Place(Blocks.DIRT), CELL, Blocks.AIR.defaultBlockState(), view);
        assertEquals(Permit.ALLOW, permit);
    }

    // ==================== 1.16.5 无头引导与标签注入 ====================

    /** 无头引导；失败返回 false（测试跳过而不失败）。1.16.5：没有 tryDetectVersion（1.17+），直接 bootStrap。 */
    private static boolean bootOnce() {
        try {
            net.minecraft.server.Bootstrap.bootStrap();
            SerializationTags.bind(emptyTags());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 四类标签各给一个空集合：查不到的标签按"不命中"回答，与上游 Holder 语义一致。 */
    private static TagContainer emptyTags() {
        return new TagContainer() {
            @Override public TagCollection<Block> getBlocks() { return TagCollection.empty(); }
            @Override public TagCollection<Item> getItems() { return TagCollection.empty(); }
            @Override public TagCollection<Fluid> getFluids() { return TagCollection.empty(); }
            @Override public TagCollection<EntityType<?>> getEntityTypes() { return TagCollection.empty(); }
        };
    }

    /** 1.16.5：标签集合经 SerializationTags.bind 注入；测完要恢复（引导时绑的是空容器）。 */
    private static void bindBlockTags(Map<ResourceLocation, List<Block>> tags) {
        Map<ResourceLocation, net.minecraft.tags.Tag<Block>> map = new HashMap<>();
        tags.forEach((id, blocks) -> map.put(id, SetTag.create(new java.util.HashSet<>(blocks))));
        TagContainer previous = SerializationTags.getInstance();
        TagCollection<Block> blockTags = TagCollection.of(map);
        SerializationTags.bind(new TagContainer() {
            @Override public TagCollection<Block> getBlocks() { return blockTags; }
            @Override public TagCollection<Item> getItems() { return previous.getItems(); }
            @Override public TagCollection<Fluid> getFluids() { return previous.getFluids(); }
            @Override public TagCollection<EntityType<?>> getEntityTypes() { return previous.getEntityTypes(); }
        });
    }

    /** Map 后备的方块视图：地形许可的测试只需要"这一格是什么"。 */
    private static final class View implements BlockGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
    }
}
