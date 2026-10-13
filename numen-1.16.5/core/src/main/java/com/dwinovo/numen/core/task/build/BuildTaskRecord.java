package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.build.BlueprintSafety;
import com.dwinovo.numen.core.build.BuildStates;
import com.dwinovo.numen.core.build.BuildValidity;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Layout;
import com.dwinovo.numen.core.mc.ItemStacks;
import com.dwinovo.numen.core.sdk.ServerCall;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class BuildTaskRecord
extends TaskRecord {
    public final List<Target> targets;
    public final boolean consumeMaterials;
    public final boolean allowPartial;
    public final Map<Long, CompoundTag> blockEntityData;
    public final List<EntitySpawn> entities;
    public final Map<Long, List<CellNeed>> cellNeeds;
    public final int droppedAtLoad;
    public final Built.Site site;
    private Map<Long, Target> byCell;
    private int placed;
    private int replaced;
    private int broken;
    private int removed;
    private int completed;
    private int settledAway;

    public BuildTaskRecord(ServerCall call, long deadlineGameTime, Layout layout, boolean consumeMaterials, boolean allowPartial, Built.Site site) {
        this(call.fn(), call.callId(), deadlineGameTime, layout, consumeMaterials, allowPartial, site);
    }

    public BuildTaskRecord(String name, String toolCallId, long deadlineGameTime, Layout layout, boolean consumeMaterials, boolean allowPartial, Built.Site site) {
        super(name, toolCallId, deadlineGameTime);
        this.entities = layout.entities();
        this.targets = BuildTaskRecord.promotePlainCells(layout.targets(), layout.blockEntityData());
        this.consumeMaterials = consumeMaterials;
        this.allowPartial = allowPartial;
        this.blockEntityData = layout.blockEntityData();
        this.cellNeeds = layout.cellNeeds();
        this.droppedAtLoad = layout.dropped();
        this.site = site;
    }

    public Target targetAt(BlockPos pos) {
        if (this.byCell == null) {
            HashMap<Long, Target> index = new HashMap<Long, Target>(this.targets.size() * 2);
            this.targets.forEach(t -> index.put(t.pos().asLong(), (Target)t));
            this.byCell = index;
        }
        return this.byCell.get(pos.asLong());
    }

    private static List<Target> promotePlainCells(List<Target> targets, Map<Long, CompoundTag> blockEntityData) {
        ArrayList<Target> out = new ArrayList<Target>(targets.size());
        for (Target t : targets) {
            BlockItem bi;
            Item item;
            boolean plain = !t.itemPlace() && !t.desiredState().isAir() && t.desiredState().getProperties().isEmpty() && !blockEntityData.containsKey(t.pos().asLong()) && (item = t.item()) instanceof BlockItem && (bi = (BlockItem)item).getBlock() == t.desiredState().getBlock();
            out.add(plain ? t.asItemPlace() : t);
        }
        return List.copyOf(out);
    }

    public int settledAway() {
        return this.settledAway;
    }

    public void settledAway(int count) {
        this.settledAway = count;
    }

    public boolean mayReplace() {
        return this.targets.stream().anyMatch(t -> t.mode().mayReplace());
    }

    public int placed() {
        return this.placed;
    }

    public void placedOne(boolean replacing) {
        ++this.placed;
        if (replacing) {
            ++this.replaced;
        }
    }

    public int replaced() {
        return this.replaced;
    }

    public int broken() {
        return this.broken;
    }

    public void brokeOne(boolean removal) {
        ++this.broken;
        if (removal) {
            ++this.removed;
        }
    }

    public int removed() {
        return this.removed;
    }

    public int completed() {
        return this.completed;
    }

    public void completed(int completed) {
        this.completed = completed;
    }

    public String describe() {
        return "\u642d\u5efa " + this.completed + "/" + this.targets.size();
    }

    public record Target(BlockState desiredState, Item item, BlockPos pos, String label, boolean itemPlace, ReplaceMode mask, Block removes) {
        public Target(BlockState desiredState, Item item, BlockPos pos, String label) {
            this(desiredState, item, pos, label, false, null, null);
        }

        public Target(Block block, Item item, BlockPos pos, String label) {
            this(block.defaultBlockState(), item, pos, label, false, null, null);
        }

        public Target {
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            desiredState = BuildStates.normalize(desiredState);
            item = Objects.requireNonNull(item, "item");
            pos = Objects.requireNonNull(pos, "pos").immutable();
            label = label == null || label.isBlank() ? desiredState.getBlock().getName().getString() : label;
        }

        public static Target removal(BlockPos pos, Block block) {
            return new Target(Blocks.AIR.defaultBlockState(), Items.AIR, pos, "air", false, ReplaceMode.REPLACE_EMPTY, block);
        }

        public Target withMask(ReplaceMode mask) {
            return new Target(this.desiredState, this.item, this.pos, this.label, this.itemPlace, mask, this.removes);
        }

        public ReplaceMode mode() {
            return this.mask != null ? this.mask : ReplaceMode.REPLACE_EMPTY;
        }

        public Target placed(BlockPos at, BlockState state) {
            return new Target(state, this.item, at, this.label, this.itemPlace, this.mask, this.removes);
        }

        public Target asItemPlace() {
            if (this.desiredState.isAir() || this.desiredState.getBlock() instanceof LiquidBlock || !(this.item instanceof BlockItem)) {
                return this;
            }
            return new Target(this.desiredState, this.item, this.pos, this.label, true, this.mask, this.removes);
        }

        public Block block() {
            return this.desiredState.getBlock();
        }

        public int materialCount() {
            if (this.desiredState == null || this.desiredState.isAir()) {
                return 0;
            }
            if (this.desiredState.getBlock() instanceof LiquidBlock) {
                return 0;
            }
            if (this.desiredState.hasProperty((Property)BlockStateProperties.DOUBLE_BLOCK_HALF) && this.desiredState.getValue((Property)BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                return 0;
            }
            if (this.desiredState.hasProperty((Property)BlockStateProperties.BED_PART) && this.desiredState.getValue((Property)BlockStateProperties.BED_PART) == BedPart.HEAD) {
                return 0;
            }
            if (this.desiredState.hasProperty((Property)BlockStateProperties.SLAB_TYPE) && this.desiredState.getValue((Property)BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
                return 2;
            }
            if (this.desiredState.getBlock() instanceof SnowLayerBlock) {
                return (Integer)this.desiredState.getValue((Property)BlockStateProperties.LAYERS);
            }
            if (this.desiredState.getBlock() instanceof TurtleEggBlock) {
                return (Integer)this.desiredState.getValue((Property)BlockStateProperties.EGGS);
            }
            if (this.desiredState.getBlock() instanceof SeaPickleBlock) {
                return (Integer)this.desiredState.getValue((Property)BlockStateProperties.PICKLES);
            }
            if (this.desiredState.is(Blocks.VINE)) {
                int faces = 0;
                for (BooleanProperty side : new BooleanProperty[]{BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN}) {
                    if (!this.desiredState.hasProperty((Property)side) || !((Boolean)this.desiredState.getValue((Property)side)).booleanValue()) continue;
                    ++faces;
                }
                return Math.max(1, faces);
            }
            return 1;
        }

        public boolean costsMaterial() {
            return this.materialCount() > 0;
        }

        public boolean matches(BlockState state) {
            if (this.itemPlace) {
                return !state.isAir() && (state.getBlock() == this.desiredState.getBlock() || state.getBlock().getDescriptionId().equals(this.desiredState.getBlock().getDescriptionId()));
            }
            return BuildValidity.valid(state, this.desiredState, false);
        }

        public boolean acceptsPlacedState(BlockState state) {
            return BuildValidity.valid(state, this.desiredState, true);
        }

        public String shortPos() {
            return this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ();
        }
    }

    public record EntitySpawn(double x, double y, double z, Rotation rotation, CompoundTag nbt) {
        public Item item() {
            return switch (this.nbt.getString("id")) {
                case "minecraft:item_frame" -> Items.ITEM_FRAME;
                case "minecraft:glow_item_frame" -> Items.ITEM_FRAME;
                case "minecraft:armor_stand" -> Items.ARMOR_STAND;
                case "minecraft:painting" -> Items.PAINTING;
                default -> Items.AIR;
            };
        }

        public List<ItemStack> payload() {
            return BlueprintSafety.payloadStacks(this.nbt);
        }
    }

    public record CellNeed(ItemStack stack, boolean exact) {
        public boolean matches(ItemStack other) {
            return this.exact ? ItemStacks.sameItemSameTags((ItemStack)this.stack, (ItemStack)other) : ItemStacks.is((ItemStack)other, (Item)this.stack.getItem());
        }
    }
}

