package com.dwinovo.numen.core.inventory;

import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 同伴的背包——<b>玩家自己背包的同款排布</b>（G 键打开，#0027）。
 *
 * <pre>
 *   Alice 的背包            ← 标题
 *   [盔]  [ 主背包 3×9 ]    ← 盔甲一列在左，与主背包同高；副手在她这一区左下
 *   [盔]
 *   [盔]  [ 快捷栏 1×9 ]
 *   [盔]
 *   [副]
 *   ─────────────────────   ← 分隔
 *   物品栏                  ← 玩家自己的
 *   [ 3×9 ]
 *   [ 快捷栏 ]
 * </pre>
 *
 * 她的 41 格 = 盔甲 4 + 副手 1 + 主背包 27 + 快捷栏 9——**与一名玩家的背包格数完全一致**
 * （早先版本把盔甲/副手塞进最后一行、还补了 4 个占位槽，于是"比玩家多 4 格、又看不出哪格是装备"，
 * 2026-10-24 用户截图指出，见 issues/0027）。
 *
 * <h2>为什么自绘背景</h2>
 * 原版箱子的 {@code generic_54} 贴图把凹槽<em>烤死</em>在固定坐标上，套到这套排布上会对不齐；
 * 所以背景由 {@link CompanionInventoryScreen} 自绘（面板 + 逐格凹槽），格子放哪儿凹槽就画哪儿。
 *
 * <h2>两侧怎么共用一个类</h2>
 * 客户端工厂只有 {@code (windowId, playerInventory)}，所以客户端那半拿一个 {@link SimpleContainer}
 * 哑格顶上：格内容、点击、shift 搬运全由原版容器协议在服务端结算，客户端只照同步画。
 * {@link #body} 只有服务端实例非空——{@link #stillValid} 因此只在服务端做"她还在吗"的检查。
 *
 * <h2>她手里拿着哪一格</h2>
 * 她的 {@code inventory.selected} 经 {@link DataSlot} 同步到客户端（{@link #heldIndex()}），
 * 屏幕据此高亮快捷栏里那一格——"看不到手上拿的"这件事就靠它解决。
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {

    // ---- 坐标（相对面板左上角）----
    private static final int TOP = 18;            // 她那一区第一行的 y
    private static final int ARMOR_X = 8;         // 盔甲列 / 副手
    private static final int MAIN_X = 26;         // 她的主背包 / 快捷栏（给左边那列盔甲让出位置）
    private static final int PLAYER_X = 8;        // 玩家自己的
    private static final int PLAYER_TOP = 118;

    /** 她那一侧的格数：盔甲4 + 副手1 + 主背包27 + 快捷栏9 = 41（与玩家背包一致）。 */
    public static final int COMPANION_SLOTS = 41;

    /** 她快捷栏在 {@link #slots} 里的起始下标（盔甲4 + 副手1 + 主背包27）——屏幕高亮主手要用。 */
    public static final int COMPANION_HOTBAR_FIRST = 4 + 1 + 27;

    /** 面板尺寸（屏幕据此画背景）。 */
    public static final int WIDTH = 176;
    public static final int HEIGHT = PLAYER_TOP + 4 * 18 + 8;

    /** 服务端：她的身体；客户端：null。 */
    private final NumenPlayer body;

    /** 客户端侧的"她选中了哪一格"（服务端经数据槽推来）。 */
    private int heldIndex;

    /** 客户端工厂（{@code MenuType} 用）。 */
    public CompanionInventoryMenu(int windowId, Inventory playerInv) {
        this(windowId, playerInv, new SimpleContainer(41), null);
    }

    public CompanionInventoryMenu(int windowId, Inventory playerInv, Container companion, NumenPlayer body) {
        super(NumenMenus.COMPANION_INVENTORY, windowId);
        this.body = body;
        checkContainerSize(companion, 41);
        companion.startOpen(playerInv.player);

        // ---- 她的：盔甲一列在左，副手在其下，主背包 3×9 与快捷栏在右 ----
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(companion, 36 + i, ARMOR_X, TOP + i * 18));          // 盔甲 36-39
        }
        addSlot(new Slot(companion, 40, ARMOR_X, TOP + 4 * 18));                  // 副手 40
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(companion, 9 + row * 9 + col, MAIN_X + col * 18, TOP + row * 18));  // 主背包 9-35
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(companion, col, MAIN_X + col * 18, TOP + 3 * 18));   // 快捷栏 0-8
        }

        // ---- 玩家自己的：3×9 + 快捷栏 ----
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, 9 + row * 9 + col, PLAYER_X + col * 18, PLAYER_TOP + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, PLAYER_X + col * 18, PLAYER_TOP + 3 * 18));
        }

        // ---- 同步「她拿着哪一格」：服务端读她的 selected，客户端存进 heldIndex ----
        if (body != null) {
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return body.inventory.selected;
                }

                @Override
                public void set(int value) {
                    // 只往下读，不往回写
                }
            });
        } else {
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return heldIndex;
                }

                @Override
                public void set(int value) {
                    heldIndex = value;
                }
            });
        }
    }

    /** 她此刻选中的快捷栏格（0-8）。两侧都读得到。 */
    public int heldIndex() {
        return body != null ? body.inventory.selected : heldIndex;
    }

    /** shift 搬运：她的 ↔ 玩家的。 */
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
}
