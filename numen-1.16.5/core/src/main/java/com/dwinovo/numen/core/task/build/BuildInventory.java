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
 * 施工的背包口径,只此一处:存量只算 36 格主背包
 * ({@link PlayerInv#BUILDABLE_SLOTS}),预检、逐格闸门与实扣同源。
 * 此前预检用 41 格(含盔甲与副手)、闸门用 36 格:一整叠木板放在副手时,
 * 预检数得到、开工放行,而每一格都判缺料——口径分叉就是这类账目事故。
 *
 * <p>三档匹配并存且不可互换:按物品类型({@link #hasItems})、按料单口径
 * ({@link #countMatching}——料单自己说要不要组件全等)、按组件全等
 * ({@link #strictCount}——少比一个组件就等于拿白剑换走玩家那把锋利五)。
 */

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

