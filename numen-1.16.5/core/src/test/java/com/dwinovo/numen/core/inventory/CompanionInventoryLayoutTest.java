package com.dwinovo.numen.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴背包的布局不变式。
 *
 * <p>存在的理由很具体：2026-10-24 连着踩了两次"真机上才看得见"的坐标错——
 * 玩家区整体下移 54px 跑出背景、主背包 9 列加左盔甲列超出面板宽 12px。这类错本来就是
 * 纯算术：{@link CompanionInventoryMenu} 的常量摆在这里，算一遍就能拦住。
 */
class CompanionInventoryLayoutTest {

    private static final int SLOT = 18;
    private static final int COLS = 9;

    /** 一块原版面板里，每样家具都得装得进 176×166。 */
    @Test
    void everyVanillaElementFitsInOnePanel() {
        int w = CompanionInventoryMenu.PANEL_W;
        int h = CompanionInventoryMenu.PANEL_H;
        assertTrue(CompanionInventoryMenu.MAIN_X + COLS * SLOT <= w, "主背包 9 列越界");
        assertTrue(CompanionInventoryMenu.MAIN_Y + 4 * SLOT <= h, "主背包 3 行+快捷栏越界");
        assertTrue(CompanionInventoryMenu.ARMOR_X + SLOT <= w, "盔甲列越界");
        assertTrue(CompanionInventoryMenu.ARMOR_Y + 4 * SLOT <= h, "盔甲列越界（纵向）");
        assertTrue(CompanionInventoryMenu.OFFHAND_X + SLOT <= w, "副手越界");
        assertTrue(CompanionInventoryMenu.OFFHAND_Y + SLOT <= h, "副手越界（纵向）");
        assertTrue(CompanionInventoryMenu.CRAFT_X + 2 * SLOT <= w, "合成 2×2 越界");
        assertTrue(CompanionInventoryMenu.CRAFT_Y + 2 * SLOT <= h, "合成 2×2 越界（纵向）");
        assertTrue(CompanionInventoryMenu.RESULT_X + SLOT <= w, "合成结果越界");
        assertTrue(CompanionInventoryMenu.RESULT_Y + SLOT <= h, "合成结果越界（纵向）");
    }

    /** 两块面板并排都装得进整窗。 */
    @Test
    void theTwoPanelsFitSideBySide() {
        assertEquals(CompanionInventoryMenu.LEFT_PANEL_X + CompanionInventoryMenu.PANEL_W
                        + CompanionInventoryMenu.GAP + CompanionInventoryMenu.PANEL_W
                        + CompanionInventoryMenu.PAD,
                CompanionInventoryMenu.WIDTH, "整窗宽度的算式");
        assertTrue(CompanionInventoryMenu.RIGHT_PANEL_X + CompanionInventoryMenu.PANEL_W
                        <= CompanionInventoryMenu.WIDTH,
                "右侧面板越出窗口");
        assertTrue(CompanionInventoryMenu.PAD + CompanionInventoryMenu.PANEL_H
                        <= CompanionInventoryMenu.HEIGHT,
                "面板纵向越出窗口");
    }

    /** 槽位顺序：她 41、玩家 41+5（合成），快捷栏起点 32。 */
    @Test
    void slotOrderMatchesTheClientToo() {
        assertEquals(41, CompanionInventoryMenu.PANEL_SLOTS);
        assertEquals(5, CompanionInventoryMenu.CRAFT_SLOTS);
        assertEquals(4 + 1 + 27, CompanionInventoryMenu.COMPANION_HOTBAR_FIRST);
        assertTrue(CompanionInventoryMenu.COMPANION_HOTBAR_FIRST + 9 <= CompanionInventoryMenu.PANEL_SLOTS,
                "她的快捷栏 9 格不能越过一侧面板");
    }

    /**
     * 装备栏的容器下标是 {@code 39 - row}（界面头/胸/腿/脚 对着 armor[3..0]），不是 {@code 36 + row}。
     * 写反了的后果是"把头盔放进最上格，实际戴在脚上"——真机上看着就是"没穿上"（2026-10-24 用户指出）。
     */
    @Test
    void armorRowsMapHeadToTheHighestContainerIndex() {
        assertEquals(39, CompanionInventoryMenu.armorSlotIndex(0), "最上格（头盔）→ 容器 39");
        assertEquals(38, CompanionInventoryMenu.armorSlotIndex(1), "胸甲 → 38");
        assertEquals(37, CompanionInventoryMenu.armorSlotIndex(2), "护腿 → 37");
        assertEquals(36, CompanionInventoryMenu.armorSlotIndex(3), "最下格（靴子）→ 容器 36");
    }

    /**
     * 快捷栏的 y 是原版那个 <b>142</b>，不是 {@code MAIN_Y + 3*18 = 138}——主背包三行与快捷栏
     * 之间原版留了 4 像素。按 138 算会整行上移 4px，格子和贴图凹槽错开、物品看着不在格心里
     * （2026-10-24 用户截图指出）。这条断言就是为它写的。
     */
    @Test
    void theHotbarSitsFourPixelsBelowTheMainRows() {
        assertEquals(142, CompanionInventoryMenu.HOTBAR_Y, "原版快捷栏 y 是 142");
        assertTrue(CompanionInventoryMenu.HOTBAR_Y > CompanionInventoryMenu.MAIN_Y + 3 * SLOT,
                "快捷栏要在主背包三行之下，且留出原版那 4 像素的间距");
        assertTrue(CompanionInventoryMenu.PAD + CompanionInventoryMenu.HOTBAR_Y + SLOT
                        <= CompanionInventoryMenu.PAD + CompanionInventoryMenu.PANEL_H,
                "快捷栏必须落在面板内");
    }
}
