package com.dwinovo.numen.core.mc;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Small 1.16.5 equivalents for newer ItemStack convenience methods. */
public final class ItemStacks {
    private ItemStacks() {}

    public static boolean is(ItemStack stack, Item item) {
        return stack != null && !stack.isEmpty() && stack.getItem() == item;
    }

    /** Same item and tags, deliberately ignoring stack count. */
    public static boolean sameItemSameTags(ItemStack left, ItemStack right) {
        return ItemStack.isSame(left, right) && ItemStack.tagMatches(left, right);
    }
}
