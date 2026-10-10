package com.dwinovo.numen.core.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.SerializationTags;
import net.minecraft.tags.SetTag;
import net.minecraft.tags.TagCollection;
import net.minecraft.tags.TagContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 规则文本的解析与匹配:全仓只在 {@link Rule} 里解析,写错了要说清哪儿错;项的四种形态
 * (信号、id、标签、某只实体)与取反、通配都要能对上动作。需要 MC 注册表(信号表初始化读物品)。
 *
 * <p>照上游逐字搬,1.16.5 适配:标签注入不走 {@code MappedRegistry.bindTags}(Holder 体系 1.18+ 才有),
 * 直接给 {@link SerializationTags} 绑一个新 {@link TagContainer}({@code TagCollection.of} 公开可造),
 * 测完绑回原来的。
 */
@Tag("mc")
class RuleTest {

    private static final PlacedBlocks.Placer STEVE =
            new PlacedBlocks.Placer(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa"), "Steve");

    private static boolean booted;
    private static final BlockPos POS = new BlockPos(1, 64, 1);

    @BeforeAll
    static void boot() {
        booted = FakeWorld.boot();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过规则钉桩");
    }

    private static Facts facts(FakeWorld world, PlacedBlocks placed) {
        return new Facts(world, placed, null, null);
    }

    // ==================== 解析 ====================

    @Test
    void parsesVerbAndTerms() {
        Rule r = Rule.parse("break(placed & !contents)");
        assertEquals(Action.Kind.BREAK, r.kind());
        assertEquals("break(placed & !contents)", r.toString());
        assertEquals("placed by a player", r.describe(), "取反的项不进自述");
        assertEquals(null, Rule.parse("*(placed)").kind(), "* 是任何动作");
        Rule own = Rule.parse("break(self_placed & !contents)");
        assertEquals("break(self_placed & !contents)", own.toString());
        assertEquals("placed by herself", own.describe());
        assertFalse(own.irreversible());
    }

    @Test
    void mistakesAreTaught() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break placed"))
                .getMessage().contains("verb(term & term)"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("chew(placed)"))
                .getMessage().contains("unknown verb 'chew'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break(haunted)"))
                .getMessage().contains("unknown signal 'haunted'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("break()"))
                .getMessage().contains("at least one term"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rule.parse("attack(entity:nope)"))
                .getMessage().contains("bad entity uuid"));
    }

    // ==================== 匹配 ====================

    @Test
    void verbMustMatchUnlessWildcard() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.DIRT.defaultBlockState());
        Action place = Action.place(POS, world.getBlockState(POS), Items.DIRT);
        assertFalse(Rule.parse("break(*)").matches(place, facts(world, new PlacedBlocks())));
        assertTrue(Rule.parse("place(*)").matches(place, facts(world, new PlacedBlocks())));
        assertTrue(Rule.parse("*(*)").matches(place, facts(world, new PlacedBlocks())));
    }

    @Test
    void signalTermsReadTheWorld() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.OAK_LOG.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));
        Rule rule = Rule.parse("break(placed)");
        assertFalse(rule.matches(dig, facts(world, placed)));
        placed.record(POS, STEVE);
        assertTrue(rule.matches(dig, facts(world, placed)));
        assertFalse(Rule.parse("break(!placed)").matches(dig, facts(world, placed)), "取反");
        assertTrue(Rule.parse("break(placed & !block_entity)").matches(dig, facts(world, placed)), "与");
    }

    @Test
    void selfPlacedIsTheActorsOwnMarkAndPlacedIsEveryoneElses() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.COBBLESTONE.defaultBlockState());
        PlacedBlocks placed = new PlacedBlocks();
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));
        java.util.UUID her = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        Facts asHer = new Facts(world, placed, null, her);
        Facts asSteve = new Facts(world, placed, null, STEVE.id());
        assertFalse(Rule.parse("break(self_placed)").matches(dig, asHer), "没有记号:谁都没放过");
        placed.record(POS, new PlacedBlocks.Placer(her, "Aria"));
        assertTrue(Rule.parse("break(self_placed)").matches(dig, asHer));
        assertFalse(Rule.parse("break(placed)").matches(dig, asHer));
        assertFalse(Rule.parse("break(self_placed)").matches(dig, asSteve), "别人来拆,那就是别人放的");
        assertTrue(Rule.parse("break(placed)").matches(dig, asSteve));
        assertFalse(Rule.parse("break(self_placed)").matches(dig, facts(world, placed)), "不知道是谁动手,不算她的");
    }

    @Test
    void idAndTagTermsNameKinds() {
        FakeWorld world = new FakeWorld();
        world.set(POS, Blocks.CHEST.defaultBlockState());
        Action dig = Action.breakBlock(POS, world.getBlockState(POS));
        Facts f = facts(world, new PlacedBlocks());
        assertTrue(Rule.parse("break(minecraft:chest)").matches(dig, f));
        assertFalse(Rule.parse("break(minecraft:barrel)").matches(dig, f));
        // 标签内容来自数据包,无头引导下手动绑:石头绑进门标签,钉的是"标签项生效"
        bindBlockTags(Map.of(BlockTags.DOORS.getName(), List.of(Blocks.STONE)));
        try {
            world.set(POS, Blocks.STONE.defaultBlockState());
            Action digStone = Action.breakBlock(POS, world.getBlockState(POS));
            assertTrue(Rule.parse("break(#minecraft:doors)").matches(digStone, f));
            assertFalse(Rule.parse("break(#minecraft:beds)").matches(digStone, f));
        } finally {
            bindBlockTags(Map.of());
        }
        // 放置看的是要放的方块,不是被盖掉的那格
        Action placeTnt = Action.place(POS, Blocks.AIR.defaultBlockState(), Items.TNT);
        assertTrue(Rule.parse("place(minecraft:tnt)").matches(placeTnt, f));
        assertTrue(Rule.parse("place(hazard_item)").matches(placeTnt, f));
        assertTrue(Rule.parse("drop(minecraft:diamond)").matches(Action.drop(Items.DIAMOND), f));
    }

    /** 1.16.5:标签集合经 SerializationTags.bind 注入;测完要恢复(引导时绑的是空容器)。 */
    private static void bindBlockTags(Map<ResourceLocation, List<Block>> tags) {
        Map<ResourceLocation, net.minecraft.tags.Tag<Block>> map = new java.util.HashMap<>();
        tags.forEach((id, blocks) -> map.put(id, SetTag.create(new java.util.HashSet<>(blocks))));
        TagContainer previous = SerializationTags.getInstance();
        TagCollection<Block> blockTags = TagCollection.of(map);
        SerializationTags.bind(new TagContainer() {
            @Override public TagCollection<Block> getBlocks() { return blockTags; }
            @Override public TagCollection<net.minecraft.world.item.Item> getItems() { return previous.getItems(); }
            @Override public TagCollection<net.minecraft.world.level.material.Fluid> getFluids() {
                return previous.getFluids();
            }
            @Override public TagCollection<net.minecraft.world.entity.EntityType<?>> getEntityTypes() {
                return previous.getEntityTypes();
            }
        });
    }
}
