package com.dwinovo.numen.core.inventory;

import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 同伴的背包——原版容器风格（G 键打开，#0027）。
 *
 * <p>上面 5×9 是<b>她的</b>：三行主背包 + 快捷栏一行 + 盔甲四件/副手一行（末尾 4 格是占位，
 * 画出来齐整、不可放不可取）；下面 36 格是玩家自己的。玩家可以往里放东西、也可以直接拿出来——
 * 服务端这半的槽挂在<b>她真格的 {@code PlayerInventory}</b> 上，所以放进去的东西她真的会背着、
 * 会用（auto_eat 挑食、equip 穿戴都从同一个背包读）；反过来她在外头捡的东西，开着界面也看得见
 * 进来——原版的槽同步每刻都在对账。
 *
 * <h2>两侧怎么共用一个类</h2>
 * {@code MenuType} 的工厂只有 {@code (windowId, playerInventory)}（1.16.5 没有带缓冲的工厂），
 * 所以客户端那半拿一个 {@link SimpleContainer} 哑格顶上：真正的格内容、点击、shift 搬运全由
 * 原版容器协议在服务端这半结算，客户端只是照同步画。{@link #body} 只有服务端实例才非空，
 * 也就是 {@link #stillValid} 只在服务端做"她还在吗、还够得着吗"的检查——客户端那半永远 true，
 * 关屏由服务端发关包决定（原版同一套）。
 *
 * <h2>坐标</h2> 照抄 {@code ChestMenu} 五行的排法（i = 72）：她的区 y=18 起，玩家区 y=175 起。
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {

    /** 她那一侧显示的格数：5 行 × 9。 */
    public static final int COMPANION_SLOTS = 45;

    /** 服务端：她的身体（客户端那半为 null）。 */
    private final NumenPlayer body;

    /** 客户端工厂（{@code MenuType} 用）：哑格顶上，内容由原版槽同步喂。 */
    public CompanionInventoryMenu(int windowId, Inventory playerInv) {
        this(windowId, playerInv, new SimpleContainer(41), null);
    }

    public CompanionInventoryMenu(int windowId, Inventory playerInv, Container companion, NumenPlayer body) {
        super(NumenMenus.COMPANION_INVENTORY, windowId);
        this.body = body;
        checkContainerSize(companion, 41);
        companion.startOpen(playerInv.player);
        // 她的 5×9。第 0–2 行是主背包（槽 9..35），第 3 行是快捷栏（槽 0..8）——
        // 与「玩家自己的背包」同款观感：主区在上、快捷栏垫底。
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row < 3 ? 9 + row * 9 + col : row * 9 + col;
                addSlot(new Slot(companion, index, 8 + col * 18, 18 + row * 18));
            }
        }
        // 第 5 行：盔甲 4 + 副手 1，余下 4 格占位（画着齐整，不可用）。
        for (int col = 0; col < 5; col++) {
            addSlot(new Slot(companion, 36 + col, 8 + col * 18, 18 + 4 * 18));
        }
        Container filler = new SimpleContainer(4);
        for (int col = 0; col < 4; col++) {
            addSlot(new BlockedSlot(filler, col, 8 + (5 + col) * 18, 18 + 4 * 18));
        }
        // 玩家自己的 3 行 + 快捷栏。
        //
        // ⚠️ 偏移量照原版 {@code ChestMenu} 的字节码：{@code i = (行数 - 4) * 18}（不是 (行数-1)！）。
        // 五行时 i=18，玩家三行在 121/139/157、快捷栏 179；写成 (5-1)*18=72 会整体下移 54 像素，
        // 玩家背包会跑到窗口背景外面去（2026-10-24 真机截图实测）。
        int i = (5 - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 103 + row * 18 + i));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 161 + i));
        }
    }

    /** shift 搬运：她的 ↔ 玩家的（照 ChestMenu 的写法，占位槽由 mayPlace=false 挡着）。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = slot.getItem();
        ItemStack out = taken.copy();
        if (index < COMPANION_SLOTS) {
            if (!moveItemStackTo(taken, COMPANION_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!moveItemStackTo(taken, 0, COMPANION_SLOTS, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (taken.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return out;
    }

    /**
     * 还开着吗：服务端看「她还活着，且玩家没走远（16 格）」；客户端那半没有她的引用，
     * 永远 true——关屏这件事由服务端的判定经原版关包送达。
     */
    @Override
    public boolean stillValid(Player player) {
        return body == null || (body.isAlive() && player.distanceToSqr(body) <= 16.0 * 16.0);
    }

    /** 占位槽：画出来齐整，但不可放、不可取、不可点。 */
    private static final class BlockedSlot extends Slot {
        BlockedSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
