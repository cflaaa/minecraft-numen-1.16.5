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

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的 `**/build/` 连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * 图纸的<b>安全白名单</b>:方块实体数据哪些可以照搬、实体哪些算建筑的
 * 一部分、摆设身上的物品按什么口径计价与净化。图纸是文件,可以任意编辑
 * 、可以从网上下载——这一层挡的是"一张塞满钻石的图纸建出来就是白送"
 * "一块写着点我领奖的牌子是个可执行口子"这类事。全部用白名单不用黑
 * 名单:黑名单是开放集合,每来一个新方块/新组件就得被咬一次;白名单
 * 一次定完,此后新东西自动落在安全的一侧。
 */

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

