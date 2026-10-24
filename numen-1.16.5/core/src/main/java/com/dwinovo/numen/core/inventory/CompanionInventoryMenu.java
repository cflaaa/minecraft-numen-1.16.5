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

/**
 * 同伴背包的容器（G 键打开，#0027）——<b>两块完整「玩家背包」面板并排</b>：
 *
 * <pre>
 *  ┌─ Alice 的背包 ──────────┐  ┌─ 物品栏 ────────────────┐
 *  │ [盔][角色模型][合成2×2] │  │ [盔][角色模型][合成2×2] │
 *  │ [盔]         [合成结果] │  │ [盔]         [合成结果] │
 *  │ [盔]  [副]              │  │ [盔]  [副]              │
 *  │ [盔]                    │  │ [盔]                    │
 *  │ [ 3×9 主背包 ]          │  │ [ 3×9 主背包 ]          │
 *  │ [ 1×9 快捷栏 ]          │  │ [ 1×9 快捷栏 ]          │
 *  └─────────────────────────┘  └─────────────────────────┘
 * </pre>
 *
 * 每一侧的格位与坐标**逐像素照抄原版 {@code InventoryMenu}**（176×166 的
 * {@code textures/gui/container/inventory.png}），所以左侧就是"她的背包"、右侧就是"你的背包"。
 * 每侧 46 格 = 盔甲4 + 副手1 + 主背包27 + 快捷栏9 + 合成2×2 + 结果1；两侧的 2×2 都是**真能合成**
 * 的（配方用 {@code getRecipeFor} 现场匹配）。
 *
 * <h2>槽位顺序（两侧必须一致）</h2>
 * <pre>
 *   0..40   她：盔甲36-39、副手40、主背包9-35、快捷栏0-8
 *   41..81  你：同上（对着你自己的 Inventory）
 *   82..86  她的合成：4 工作格 + 1 结果
 *   87..91  你的合成：4 工作格 + 1 结果
 * </pre>
 *
 * <h2>两侧共用一个类</h2>
 * 客户端工厂只有 {@code (windowId, playerInventory)}，所以客户端那半用一个 {@link SimpleContainer}
 * 哑格顶上；她的<b>实体 id</b> 经 Fabric 的扩展开屏数据送到客户端（屏幕据此画她的模型）。
 * {@link #body} 只有服务端实例非空。
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {

    // ---- 面板几何（照原版 InventoryScreen：176×166）----
    public static final int PANEL_W = 176;
    public static final int PANEL_H = 166;
    public static final int PAD = 4;
    public static final int GAP = 8;

    /** 面板左上角在窗口中的 x（屏幕画贴图/模型用）。 */
    public static final int LEFT_PANEL_X = PAD;
    public static final int RIGHT_PANEL_X = PAD + PANEL_W + GAP;

    /** 整窗尺寸：宽 368 = 4+176+8+176+4；高 174 = 4+166+4。 */
    public static final int WIDTH = PAD + PANEL_W + GAP + PANEL_W + PAD;
    public static final int HEIGHT = PAD + PANEL_H + PAD;

    /** 一侧面板里的格数（不含合成）：盔甲4+副手1+主背包27+快捷栏9。 */
    public static final int PANEL_SLOTS = 41;
    /** 一侧的合成格数：2×2 + 结果。 */
    public static final int CRAFT_SLOTS = 5;
    /** 左侧（她）里快捷栏的起始下标（4+1+27），屏幕高亮主手要用。 */
    public static final int COMPANION_HOTBAR_FIRST = 4 + 1 + 27;

    // ---- 原版坐标（相对面板左上角）----（public：自检单测直接断言这套算术）
    public static final int ARMOR_X = 8;
    public static final int ARMOR_Y = 8;
    public static final int OFFHAND_X = 77;
    public static final int OFFHAND_Y = 62;
    public static final int MAIN_X = 8;
    public static final int MAIN_Y = 84;
    public static final int CRAFT_X = 98;
    public static final int CRAFT_Y = 18;
    public static final int RESULT_X = 154;
    public static final int RESULT_Y = 28;

    /** 服务端：她的身体；客户端：null。 */
    private final NumenPlayer body;
    /** 她的实体 id（客户端画模型用）。 */
    private final int companionEntityId;

    private final CraftingContainer companionCraft;
    private final ResultContainer companionResult = new ResultContainer();
    private final CraftingContainer playerCraft;
    private final ResultContainer playerResult = new ResultContainer();

    /** 客户端侧的"她选中了哪一格"。 */
    private int heldIndex;

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

        this.companionCraft = new CraftingContainer(this, 2, 2);
        this.playerCraft = new CraftingContainer(this, 2, 2);

        addPanel(LEFT_PANEL_X, companion);              // 0..40  她
        addPanel(RIGHT_PANEL_X, playerInv);             // 41..81 你
        addCrafting(playerInv.player, LEFT_PANEL_X, companionCraft, companionResult);    // 82..86
        addCrafting(playerInv.player, RIGHT_PANEL_X, playerCraft, playerResult);         // 87..91

        // 同步「她拿着哪一格」
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
            addSlot(new Slot(inv, col, panelX + MAIN_X + col * 18, PAD + MAIN_Y + 3 * 18));
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

    public int heldIndex() {
        return body != null ? body.inventory.selected : heldIndex;
    }

    public int companionEntityId() {
        return companionEntityId;
    }

    /** 合成格变了就重算结果（照 {@code InventoryMenu} 的做法，配方现场匹配）。 */
    @Override
    public void slotsChanged(Container container) {
        if (container == companionCraft) {
            updateResult(companionCraft, companionResult);
        } else if (container == playerCraft) {
            updateResult(playerCraft, playerResult);
        }
    }

    private void updateResult(CraftingContainer craft, ResultContainer result) {
        if (body == null) {
            return;   // 客户端：结果由服务端同步下来（与玩家自己那块同一个道理）
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
        clearContainer(player, player.level, companionCraft);
        clearContainer(player, player.level, playerCraft);
    }

    /** shift 搬运：两块面板之间来回；合成工作格里的也送回自己那一侧。 */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = slot.getItem();
        ItemStack out = taken.copy();
        int panel = PANEL_SLOTS;
        int craftStart = 2 * panel;
        if (index < panel) {                                  // 她 → 你
            if (!moveItemStackTo(taken, panel, 2 * panel, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index < 2 * panel) {                       // 你 → 她
            if (!moveItemStackTo(taken, 0, panel, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index < craftStart + CRAFT_SLOTS) {        // 她的合成格 → 你
            if (!moveItemStackTo(taken, panel, 2 * panel, true)) {
                return ItemStack.EMPTY;
            }
        } else {                                              // 你的合成格 → 她
            if (!moveItemStackTo(taken, 0, panel, false)) {
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
}
