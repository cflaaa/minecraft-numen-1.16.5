package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collections;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrassPathBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

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
 * 落位前把方块状态<b>归一</b>,以及判定一格能不能建——建造与图纸两条入口的唯一收口。
 *
 * <p>此前这两件事散在四处各判各的:图纸加载器筛掉一批方块、调色板筛掉另一批、
 * {@code Target} 的构造器里偷偷把树叶改成不会腐烂、而落位时又把图纸里那些运行态
 * 原样写进世界。同一个判断分四个地方做,改一处就漏三处。
 *
 * <p>归一的判据只有一条:<b>不是作者定的,就不该照抄</b>。图纸是某个世界某一刻的
 * 快照,里面混着大量世界自己算出来的东西——作物长到第几节、方块含不含水、活塞伸没
 * 伸出去、堆肥桶攒了多少、锅里装的什么。照字面摆下去,就会凭空长出一片熟麦子、凭空
 * 造出水来、摆出一口装着岩浆的锅。{@link BuildValidity} 那张"作者属性"白名单是同
 * 一条判据的另一面:它管比对时忽略什么,这里管落位前清掉什么。
 */

public final class BuildStates {
    private BuildStates() {
    }

    public static Resolved resolve(String written) {
        BlockState state;
        String trimmed = written.trim();
        try {
            state = BlockStateArgument.block().parse(new StringReader(trimmed)).getState();
        }
        catch (CommandSyntaxException bad) {
            throw new IllegalArgumentException(trimmed + " \u2014 " + bad.getMessage());
        }
        String no = BuildStates.unbuildableReason(state);
        if (no != null) {
            throw new IllegalArgumentException(trimmed + " \u2014 " + no);
        }
        Item item = BuildStates.materialItem(state.getBlock());
        if (item == Items.AIR && !state.isAir()) {
            throw new IllegalArgumentException(trimmed + " is not a placeable block");
        }
        String name = trimmed.contains("[") ? trimmed.substring(0, trimmed.indexOf(91)).trim() : trimmed;
        return new Resolved(state, item, name.contains(":") ? name.split(":", 2)[1] : name);
    }

    public static BlockState normalize(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.is(Blocks.COMPOSTER)) {
            return Blocks.COMPOSTER.defaultBlockState();
        }
        if (state.getBlock() instanceof CauldronBlock) {
            return Blocks.CAULDRON.defaultBlockState();
        }
        for (Property p : state.getProperties()) {
            IntegerProperty age;
            if (!(p instanceof IntegerProperty) || !"age".equals((age = (IntegerProperty)p).getName())) continue;
            state = (BlockState)state.setValue((Property)age, (Comparable)((Integer)Collections.min(age.getPossibleValues())));
            break;
        }
        if (state.hasProperty((Property)BlockStateProperties.LEVEL_HONEY)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.LEVEL_HONEY, (Comparable)Integer.valueOf(0));
        }
        if (state.hasProperty((Property)BlockStateProperties.HATCH)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.HATCH, (Comparable)Integer.valueOf(0));
        }
        if (state.hasProperty((Property)BlockStateProperties.STAGE)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.STAGE, (Comparable)Integer.valueOf(0));
        }
        if (state.hasProperty((Property)BlockStateProperties.WATERLOGGED)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.WATERLOGGED, (Comparable)Boolean.valueOf(false));
        }
        if (state.hasProperty((Property)BlockStateProperties.EXTENDED)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.EXTENDED, (Comparable)Boolean.valueOf(false));
        }
        if (state.hasProperty((Property)BlockStateProperties.PERSISTENT)) {
            state = (BlockState)state.setValue((Property)BlockStateProperties.PERSISTENT, (Comparable)Boolean.valueOf(true));
        }
        return state;
    }

    public static boolean isSecondaryHalf(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty((Property)BlockStateProperties.BED_PART) && state.getValue((Property)BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return true;
        }
        return state.hasProperty((Property)BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue((Property)BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    public static Item materialItem(Block block) {
        Item override = BuildStates.overrideItem(block);
        return override != null ? override : block.asItem();
    }

    public static Item materialItem(BlockState state, LevelReader level, BlockPos pos) {
        if (state == null) {
            return Items.AIR;
        }
        Item override = BuildStates.overrideItem(state.getBlock());
        if (override != null) {
            return override;
        }
        if (level != null && !(state.getBlock() instanceof EntityBlock)) {
            try {
                ItemStack picked = state.getBlock().getCloneItemStack((BlockGetter)level, pos, state);
                if (!picked.isEmpty()) {
                    return picked.getItem();
                }
            }
            catch (RuntimeException runtimeException) {
                // empty catch block
            }
        }
        return state.getBlock().asItem();
    }

    private static Item overrideItem(Block block) {
        if (block instanceof FarmBlock || block instanceof GrassPathBlock) {
            return Items.DIRT;
        }
        return null;
    }

    public static List<BuildTaskRecord.CellNeed> cellNeeds(BlockState state, CompoundTag safeData, LevelReader level, BlockPos probe) {
        FlowerPotBlock pot;
        if (state == null) {
            return List.of();
        }
        Block block = state.getBlock();
        if (block instanceof FlowerPotBlock && (pot = (FlowerPotBlock)block).getContent() != Blocks.AIR) {
            Item plant = BuildStates.materialItem(state, level, probe);
            if (plant == Items.AIR) {
                plant = pot.getContent().asItem();
            }
            if (plant == Items.AIR || plant == Items.FLOWER_POT) {
                return List.of();
            }
            return List.of(new BuildTaskRecord.CellNeed(new ItemStack((ItemLike)Items.FLOWER_POT), false), new BuildTaskRecord.CellNeed(new ItemStack((ItemLike)plant), false));
        }
        ItemStack exact = BuildStates.strictItem(state, safeData);
        return exact == null ? List.of() : List.of(new BuildTaskRecord.CellNeed(exact, true));
    }

    public static ItemStack strictItem(BlockState state, CompoundTag safeData) {
        if (state == null || safeData == null || !state.is((Tag)BlockTags.BANNERS) || !safeData.contains("Patterns")) {
            return null;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return null;
        }
        ItemStack stack = new ItemStack((ItemLike)item);
        CompoundTag beTag = new CompoundTag();
        beTag.put("Patterns", safeData.get("Patterns").copy());
        stack.addTagElement("BlockEntityTag", (net.minecraft.nbt.Tag)beTag);
        return stack;
    }

    public static String unbuildableReason(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.JIGSAW) || state.is(Blocks.STRUCTURE_BLOCK)) {
            return "structure tooling blocks are not part of a building";
        }
        if (state.getBlock() instanceof PistonHeadBlock || state.getBlock() instanceof MovingPistonBlock) {
            return "a piston head belongs to an extended piston, not to a build";
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return "she does not place or drain liquids; leave water and lava out of it and dig the basin instead, or let the player pour it";
        }
        return null;
    }

    public record Resolved(BlockState state, Item item, String label) {
    }
}

