package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.mc.ItemStacks;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class BuildInventory {
    private final NumenPlayer player;

    BuildInventory(NumenPlayer player) {
        this.player = player;
    }

    private int buildableLimit(Inventory inventory) {
        return Math.min(36, inventory.items.size());
    }

    int countMatching(BuildTaskRecord.CellNeed need) {
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        int n = 0;
        for (int i = 0; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !need.matches(stack)) continue;
            n += stack.getCount();
        }
        return n;
    }

    void consumeMatching(BuildTaskRecord.CellNeed need) {
        if (WorkProfile.of((ServerPlayer)this.player).freeMaterials()) {
            return;
        }
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        for (int i = 0; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !need.matches(stack)) continue;
            stack.shrink(1);
            return;
        }
    }

    void consumeOne(Item item) {
        if (WorkProfile.of((ServerPlayer)this.player).freeMaterials()) {
            return;
        }
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        for (int i = 0; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.is((ItemStack)stack, (Item)item)) continue;
            stack.shrink(1);
            return;
        }
    }

    int strictCount(ItemStack want) {
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        int n = 0;
        for (int i = 0; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.sameItemSameTags((ItemStack)stack, (ItemStack)want)) continue;
            n += stack.getCount();
        }
        return n;
    }

    boolean consumeStrict(ItemStack want) {
        if (WorkProfile.of((ServerPlayer)this.player).freeMaterials()) {
            return true;
        }
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        for (int i = 0; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.sameItemSameTags((ItemStack)stack, (ItemStack)want)) continue;
            stack.shrink(1);
            return true;
        }
        return false;
    }

    boolean hasItem(Item item, boolean wholeInventory) {
        return this.hasItems(item, 1, wholeInventory);
    }

    boolean hasItems(Item item, int count, boolean wholeInventory) {
        if (wholeInventory) {
            return this.mainInventoryCount(item) >= count;
        }
        return this.hotbarCount(item) >= count;
    }

    private int hotbarCount(Item item) {
        Inventory inventory = this.player.getInventory();
        int n = 0;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.is((ItemStack)stack, (Item)item)) continue;
            n += stack.getCount();
        }
        return n;
    }

    int findSlot(Item item, boolean wholeInventory) {
        Inventory inventory = this.player.getInventory();
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.is((ItemStack)stack, (Item)item)) continue;
            return i;
        }
        if (wholeInventory) {
            return this.findMainInventorySlot(item);
        }
        return -1;
    }

    int mainInventoryCount(Item item) {
        return PlayerInv.buildableCount((Inventory)this.player.getInventory(), (Item)item);
    }

    private int findMainInventorySlot(Item item) {
        Inventory inventory = this.player.getInventory();
        int limit = this.buildableLimit(inventory);
        for (int i = 9; i < limit; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !ItemStacks.is((ItemStack)stack, (Item)item)) continue;
            return i;
        }
        return -1;
    }
}

