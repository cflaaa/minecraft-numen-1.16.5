package com.dwinovo.numen.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同伴背包的布局不变式。
 *
 * <p>存在的理由很具体：2026-10-24 两次把坐标算错（玩家区整体下移 54px 跑出背景、
 * 主背包 9 列 + 左盔甲列超出面板宽 12px）。这类错在真机上才看得见，而它本来就是纯算术
 * ——{@link CompanionInventoryMenu} 的常量摆在这里，算一遍就能拦住。
 */
class CompanionInventoryLayoutTest {

    private static final int SLOT = 18;
    private static final int COLS = 9;

    @Test
    void herMainGridFitsInsideThePanel() {
        assertTrue(CompanionInventoryMenu.MAIN_X + COLS * SLOT <= CompanionInventoryMenu.WIDTH,
                "她的主背包 9 列必须装得进面板（MAIN_X + 9*18 <= WIDTH）");
        assertTrue(CompanionInventoryMenu.ARMOR_X + SLOT <= CompanionInventoryMenu.WIDTH,
                "盔甲列必须装得进面板");
        assertTrue(CompanionInventoryMenu.PLAYER_X + COLS * SLOT <= CompanionInventoryMenu.WIDTH,
                "玩家的 9 列必须装得进面板");
    }

    @Test
    void theTwoSectionsDoNotOverlap() {
        int herBottom = CompanionInventoryMenu.TOP + 5 * SLOT;   // 盔甲4 + 副手1
        assertTrue(herBottom <= CompanionInventoryMenu.PLAYER_TOP,
                "她那一区（含副手）不能压到玩家的区：" + herBottom + " <= " + CompanionInventoryMenu.PLAYER_TOP);
    }

    @Test
    void thePlayerRowsAndHotbarFitInsideThePanel() {
        int hotbarBottom = CompanionInventoryMenu.PLAYER_TOP + 4 * SLOT;
        assertTrue(hotbarBottom <= CompanionInventoryMenu.HEIGHT,
                "玩家三行+快捷栏必须在面板内：" + hotbarBottom + " <= " + CompanionInventoryMenu.HEIGHT);
    }

    /** 她的 41 格 = 盔甲4 + 副手1 + 主背包27 + 快捷栏9；外加玩家 36 = 77。 */
    @Test
    void slotCountsMatchAPlayersInventory() {
        assertEquals(41, CompanionInventoryMenu.COMPANION_SLOTS);
        assertEquals(4 + 1 + 27, CompanionInventoryMenu.COMPANION_HOTBAR_FIRST);
        assertTrue(CompanionInventoryMenu.COMPANION_HOTBAR_FIRST + 9 <= CompanionInventoryMenu.COMPANION_SLOTS);
    }
}
