package com.dwinovo.numen.core.inventory;

import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * 同伴背包的容器（G 键打开，#0027）——<b>两块「玩家背包」面板并排</b>：
 *
 * <pre>
 *  ┌─ 她 ────────────────────┐  ┌─ 你 ────────────────────┐
 *  │ [盔][角色模型][状态区]  │  │ [盔][角色模型][合成2×2] │
 *  │ [盔]                    │  │ [盔]         [合成结果] │
 *  │ [盔]  [副]              │  │ [盔]  [副]              │
 *  │ [盔]                    │  │ [盔]                    │
 *  │ [ 3×9 主背包 ]          │  │ [ 3×9 主背包 ]          │
 *  │ [ 1×9 快捷栏 ]          │  │ [ 1×9 快捷栏 ]          │
 *  └─────────────────────────┘  └─────────────────────────┘
 * </pre>
 *
 * 格位坐标逐像素照原版 {@code InventoryMenu}（176×166 的 {@code inventory.png}）。
 * <b>她那一侧的合成区换成了状态区</b>（血量/饥饿/效果）——她合成走自己的 API，
 * 摆一块用不到的合成台只是占地方（2026-10-24 用户定的）。她那一侧因此是 41 格、
 * 玩家那一侧 41+5（合成 2×2 + 结果）。
 *
 * <h2>槽位顺序（两侧必须一致）</h2>
 * <pre>
 *   0..40   她：盔甲36-39、副手40、主背包9-35、快捷栏0-8
 *   41..81  你：同上（对着你自己的 Inventory）
 *   82..86  你的合成：4 工作格 + 1 结果
 * </pre>
 *
 * <h2>同步给客户端的东西</h2>
 * 原版只把"玩家自己的"血量/饥饿发给他本人，别人的读不到；所以她的
 * {@code selected}/血量/饥饿经 {@link DataSlot} 推给客户端（状态区要画）。
 * 效果（buff）不用同步——实体自带的 {@code UpdateMobEffect} 对所有客户端都发，
 * 屏幕直接从她的实体上读。
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {

    // ---- 面板几何（照原版 InventoryScreen：176×166）----
    public static final int PANEL_W = 176;
    public static final int PANEL_H = 166;
    public static final int PAD = 4;
    public static final int GAP = 8;

    /** 面板左上角在窗口中的 x（屏幕画贴图/模型/状态用）。 */
    public static final int LEFT_PANEL_X = PAD;
    public static final int RIGHT_PANEL_X = PAD + PANEL_W + GAP;

    /** 整窗：宽 368 = 4+176+8+176+4；高 174 = 4+166+4。 */
    public static final int WIDTH = PAD + PANEL_W + GAP + PANEL_W + PAD;
    public static final int HEIGHT = PAD + PANEL_H + PAD;

    // ---- 原版坐标（相对面板左上角）----（public：自检单测直接断言这套算术）
    public static final int ARMOR_X = 8;
    public static final int ARMOR_Y = 8;
    public static final int OFFHAND_X = 77;
    public static final int OFFHAND_Y = 62;
    public static final int MAIN_X = 8;
    public static final int MAIN_Y = 84;
    /**
     * 快捷栏的 y。⚠️ 原版就是 <b>142</b>，不是 {@code MAIN_Y + 3*18 = 138}——主背包三行与快捷栏
     * 之间留了 4 像素。按 138 算会整行上移 4px、物品看着不在格心里（2026-10-24 用户截图指出）。
     */
    public static final int HOTBAR_Y = 142;
    public static final int CRAFT_X = 98;
    public static final int CRAFT_Y = 18;
    public static final int RESULT_X = 154;
    public static final int RESULT_Y = 28;

    /** 一侧面板里的格数（不含合成）：盔甲4+副手1+主背包27+快捷栏9。 */
    public static final int PANEL_SLOTS = 41;
    /** 玩家那一侧的合成格数：2×2 + 结果。 */
    public static final int CRAFT_SLOTS = 5;
    /** 左侧（她）里快捷栏的起始下标（4+1+27），屏幕高亮主手要用。 */
    public static final int COMPANION_HOTBAR_FIRST = 4 + 1 + 27;

    /** 服务端：她的身体；客户端：null。 */
    private final NumenPlayer body;
    /** 她的实体 id（客户端画模型/读 buff 用）。 */
    private final int companionEntityId;

    /** 玩家那一侧的合成（她那一侧没有合成，见类注释）。 */
    private final CraftingContainer playerCraft;
    private final ResultContainer playerResult = new ResultContainer();

    // 客户端侧的镜像（服务端推来的值存这儿）
    private final Mirror heldMirror = new Mirror();
    private final Mirror healthMirror = new Mirror();
    private final Mirror maxHealthMirror = new Mirror();
    private final Mirror foodMirror = new Mirror();

    /** 客户端工厂（{@code MenuType} 用）：扩展开屏数据里读她的实体 id。 */
    public CompanionInventoryMenu(int windowId, Inventory playerInv, int companionEntityId) {
        this(windowId, playerInv, new SimpleContainer(PANEL_SLOTS), null, companionEntityId);
    }

    public CompanionInventoryMenu(int windowId, Inventory playerInv, Container companion, NumenPlayer body,
                                  int companionEntityId) {
        super(NumenMenus.COMPANION_INVENTORY, windowId);
        this.body = body;
        this.companionEntityId = companionEntityId;
        checkContainerSize(companion, PANEL_SLOTS);
        companion.startOpen(playerInv.player);

        this.playerCraft = new CraftingContainer(this, 2, 2);

        addPanel(LEFT_PANEL_X, companion);        // 0..40  她
        addPanel(RIGHT_PANEL_X, playerInv);       // 41..81 你
        addCrafting(playerInv.player, RIGHT_PANEL_X, playerCraft, playerResult);   // 82..86

        // 她拿着哪一格 + 血量/饥饿（原版不会把别人的这些发给客户端）
        addDataSlot(body != null ? read(() -> body.inventory.selected) : heldMirror);
        addDataSlot(body != null ? read(() -> Math.round(body.getHealth() * 10.0F)) : healthMirror);
        addDataSlot(body != null ? read(() -> Math.round(body.getMaxHealth() * 10.0F)) : maxHealthMirror);
        addDataSlot(body != null ? read(() -> body.getFoodData().getFoodLevel()) : foodMirror);
    }

    /** 一侧的 41 格：盔甲一列、副手、主背包 3×9、快捷栏（坐标照原版）。 */
    private void addPanel(int panelX, Container inv) {
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(inv, 36 + i, panelX + ARMOR_X, PAD + ARMOR_Y + i * 18));
        }
        addSlot(new Slot(inv, 40, panelX + OFFHAND_X, PAD + OFFHAND_Y));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, 9 + row * 9 + col, panelX + MAIN_X + col * 18, PAD + MAIN_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, panelX + MAIN_X + col * 18, PAD + HOTBAR_Y));
        }
    }

    /** 一侧的 2×2 合成格 + 结果格（坐标照原版）。{@code owner} 是"谁在拿"，原版 {@code ResultSlot} 要它发统计。 */
    private void addCrafting(Player owner, int panelX, CraftingContainer craft, ResultContainer result) {
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                addSlot(new Slot(craft, col + row * 2, panelX + CRAFT_X + col * 18, PAD + CRAFT_Y + row * 18));
            }
        }
        addSlot(new ResultSlot(owner, craft, result, 0, panelX + RESULT_X, PAD + RESULT_Y));
    }

    // ---- 给屏幕读的 ----

    public int heldIndex() {
        return body != null ? body.inventory.selected : heldMirror.value();
    }

    public float health() {
        return (body != null ? Math.round(body.getHealth() * 10.0F) : healthMirror.value()) / 10.0F;
    }

    public float maxHealth() {
        return (body != null ? Math.round(body.getMaxHealth() * 10.0F) : maxHealthMirror.value()) / 10.0F;
    }

    public int food() {
        return body != null ? body.getFoodData().getFoodLevel() : foodMirror.value();
    }

    public int companionEntityId() {
        return companionEntityId;
    }

    /** 合成格变了就重算结果（照 {@code InventoryMenu} 的做法，配方现场匹配）。 */
    @Override
    public void slotsChanged(Container container) {
        if (container == playerCraft) {
            updateResult(playerCraft, playerResult);
        }
    }

    private void updateResult(CraftingContainer craft, ResultContainer result) {
        if (body == null) {
            return;   // 客户端：结果由服务端同步下来
        }
        Optional<CraftingRecipe> recipe = body.level.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, craft, body.level);
        result.setItem(0, recipe.map(r -> r.assemble(craft)).orElse(ItemStack.EMPTY));
        broadcastChanges();
    }

    /** 关屏：合成工作格里的东西还回背包（照原版）。 */
    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, player.level, playerCraft);
    }

    /** shift 搬运：两块面板之间来回；合成工作格里的送回玩家那一侧。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = slot.getItem();
        ItemStack out = taken.copy();
        int panel = PANEL_SLOTS;
        if (index < panel) {                              // 她 → 你
            if (!moveItemStackTo(taken, panel, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (index < 2 * panel) {                   // 你 → 她
            if (!moveItemStackTo(taken, 0, panel, false)) {
                return ItemStack.EMPTY;
            }
        } else {                                          // 你的合成格 → 你
            if (!moveItemStackTo(taken, panel, 2 * panel, true)) {
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

    @Override
    public boolean stillValid(Player player) {
        return body == null || (body.isAlive() && player.distanceToSqr(body) <= 16.0 * 16.0);
    }

    // ---- 小工具 ----

    /** 服务端侧的只读数据槽。 */
    private static DataSlot read(IntSupplier supplier) {
        return new DataSlot() {
            @Override
            public int get() {
                return supplier.getAsInt();
            }

            @Override
            public void set(int value) {
                // 只往下读，不往回写
            }
        };
    }

    /** 客户端侧的数据槽镜像：服务端推来的值存进自己字段。 */
    private static final class Mirror extends DataSlot {
        private int value;

        @Override
        public int get() {
            return value;
        }

        @Override
        public void set(int value) {
            this.value = value;
        }

        int value() {
            return value;
        }
    }
}
