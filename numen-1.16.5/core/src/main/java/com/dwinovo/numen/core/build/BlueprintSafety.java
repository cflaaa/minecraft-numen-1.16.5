package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.init.InitTag;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class BlueprintSafety {
    private static final List<String> PAYLOAD_KEYS = List.of("Item", "ArmorItems", "HandItems", "equipment");

    private BlueprintSafety() {
    }

    public static CompoundTag safeBlockEntityData(BlockState state, CompoundTag data) {
        if (state == null || data == null || data.isEmpty()) {
            return null;
        }
        if (!state.is(InitTag.blocks((ResourceLocation)InitTag.SAFE_BLOCK_ENTITY_DATA))) {
            return null;
        }
        CompoundTag out = data.copy();
        for (String positional : new String[]{"x", "y", "z"}) {
            out.remove(positional);
        }
        if (state.is((net.minecraft.tags.Tag)BlockTags.SIGNS)) {
            if (out.contains("front_item") || out.contains("back_item")) {
                return null;
            }
            for (String side : new String[]{"front_text", "back_text"}) {
                CompoundTag text = out.getCompound(side);
                if (!text.contains("messages", 9)) continue;
                for (Tag line : text.getList("messages", 8)) {
                    if (!BlueprintSafety.hasClickEvent(line.getAsString())) continue;
                    return null;
                }
            }
            return out.isEmpty() || out.size() == 1 && out.contains("id") ? null : out;
        }
        if (BlueprintSafety.opOnlyNbt(state)) {
            return null;
        }
        return out.isEmpty() || out.size() == 1 && out.contains("id") ? null : out;
    }

    private static boolean opOnlyNbt(BlockState state) {
        Block block = state.getBlock();
        if (!(block instanceof EntityBlock)) {
            return false;
        }
        EntityBlock holder = (EntityBlock)block;
        try {
            BlockEntity be = holder.newBlockEntity((BlockGetter)null);
            return be != null && be.onlyOpCanSetNbt();
        }
        catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean hasClickEvent(String json) {
        try {
            MutableComponent component = Component.Serializer.fromJson((String)(json.isEmpty() ? "\"\"" : json));
            return component != null && BlueprintSafety.hasClickEvent((Component)component);
        }
        catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean hasClickEvent(Component component) {
        if (component.getStyle() != null && component.getStyle().getClickEvent() != null) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (!BlueprintSafety.hasClickEvent(sibling)) continue;
            return true;
        }
        return false;
    }

    public static CompoundTag safeEntityData(CompoundTag data) {
        if (data == null || !data.contains("id")) {
            return null;
        }
        String id = data.getString("id");
        if (!(id.equals("minecraft:item_frame") || id.equals("minecraft:glow_item_frame") || id.equals("minecraft:armor_stand") || id.equals("minecraft:painting"))) {
            return null;
        }
        CompoundTag out = data.copy();
        BlueprintSafety.sanitizePayload(out);
        for (String anchor : new String[]{"Pos", "TileX", "TileY", "TileZ"}) {
            out.remove(anchor);
        }
        return out;
    }

    public static List<ItemStack> payloadStacks(CompoundTag data) {
        if (data == null) {
            return List.of();
        }
        ArrayList<ItemStack> out = new ArrayList<ItemStack>();
        for (String key : PAYLOAD_KEYS) {
            Tag tag = data.get(key);
            if (tag instanceof CompoundTag) {
                CompoundTag one = (CompoundTag)tag;
                BlueprintSafety.addStack(out, one);
                continue;
            }
            if (!(tag instanceof ListTag)) continue;
            ListTag many = (ListTag)tag;
            for (Tag slot : many) {
                if (!(slot instanceof CompoundTag)) continue;
                CompoundTag c = (CompoundTag)slot;
                BlueprintSafety.addStack(out, c);
            }
        }
        return out;
    }

    public static boolean unsafeItemTagKey(String key) {
        return !key.equals("Enchantments") && !key.equals("Potion") && !key.equals("CustomPotionEffects") && !key.equals("CustomPotionColor") && !key.equals("Damage") && !key.equals("display");
    }

    public static ItemStack withUnsafeComponentsDiscarded(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return stack;
        }
        ItemStack copy = stack.copy();
        CompoundTag pruned = copy.getTag();
        for (String key : List.copyOf(pruned.getAllKeys())) {
            if (!BlueprintSafety.unsafeItemTagKey(key)) continue;
            pruned.remove(key);
        }
        if (pruned.contains("display", 10)) {
            CompoundTag display = pruned.getCompound("display");
            for (String key : List.copyOf(display.getAllKeys())) {
                if (key.equals("Name")) continue;
                display.remove(key);
            }
            if (display.isEmpty()) {
                pruned.remove("display");
            }
        }
        if (pruned.isEmpty()) {
            copy.setTag(null);
        }
        return copy;
    }

    private static void addStack(List<ItemStack> out, CompoundTag tag) {
        if (tag.isEmpty()) {
            return;
        }
        ItemStack stack = BlueprintSafety.withUnsafeComponentsDiscarded(ItemStack.of((CompoundTag)tag));
        if (!stack.isEmpty()) {
            out.add(stack);
        }
    }

    private static void sanitizePayload(CompoundTag data) {
        for (String key : PAYLOAD_KEYS) {
            Tag tag = data.get(key);
            if (tag instanceof CompoundTag) {
                CompoundTag one = (CompoundTag)tag;
                Tag fixed = BlueprintSafety.sanitizeItemTag(one);
                if (fixed == null) {
                    data.remove(key);
                    continue;
                }
                data.put(key, fixed);
                continue;
            }
            if (!(tag instanceof ListTag)) continue;
            ListTag many = (ListTag)tag;
            ListTag rebuilt = new ListTag();
            for (Tag slot : many) {
                Tag tag2;
                if (slot instanceof CompoundTag) {
                    CompoundTag c = (CompoundTag)slot;
                    tag2 = BlueprintSafety.sanitizeItemTag(c);
                } else {
                    tag2 = null;
                }
                Tag fixed = tag2;
                rebuilt.add(fixed == null ? new CompoundTag() : fixed);
            }
            data.put(key, (Tag)rebuilt);
        }
    }

    private static Tag sanitizeItemTag(CompoundTag tag) {
        if (tag.isEmpty()) {
            return null;
        }
        ItemStack cleaned = BlueprintSafety.withUnsafeComponentsDiscarded(ItemStack.of((CompoundTag)tag));
        return cleaned.isEmpty() ? null : cleaned.save(new CompoundTag());
    }

    public static void stripPayload(CompoundTag data) {
        if (data == null) {
            return;
        }
        for (String key : PAYLOAD_KEYS) {
            data.remove(key);
        }
    }
}

