package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.inventory.CompanionInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static net.minecraft.client.gui.GuiComponent.fill;

/**
 * 同伴背包的界面（G 键打开，#0027）——排布与玩家自己的背包同款：
 * 左盔甲一列 + 副手，右主背包 3×9 + 快捷栏，下面接玩家自己的那一半。
 *
 * <h2>为什么自绘背景</h2>
 * 原版箱子用的 {@code generic_54} 贴图把凹槽烤死在固定坐标上，套到本排布上会对不齐；
 * 所以这里自己画面板 + 逐格凹槽（颜色照原版 GUI：面板 {@code #C6C6C6}、凹槽外框
 * {@code #373737}、内底 {@code #8B8B8B}），格子搬到哪儿凹槽就跟到哪儿。
 *
 * <p>高亮她<b>手里拿着的那一格</b>（{@link CompanionInventoryMenu#heldIndex()}，经数据槽同步）——
 * 这是"看不到手上拿的"的正解：手持是快捷栏里的选中格，不是另有一个格子。
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    private static final int PANEL = 0xFFC6C6C6;
    private static final int SLOT_OUTER = 0xFF373737;
    private static final int SLOT_INNER = 0xFF8B8B8B;
    private static final int HELD_HIGHLIGHT = 0x66FFFFFF;
    private static final int DIVIDER = 0xFF8B8B8B;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = CompanionInventoryMenu.WIDTH;
        this.imageHeight = CompanionInventoryMenu.HEIGHT;
        this.titleLabelY = 6;
        this.inventoryLabelY = 108;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, delta);
        this.renderTooltip(pose, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack pose, float delta, int mouseX, int mouseY) {
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        // 面板：外描边一圈黑，里面原版 GUI 灰
        fill(pose, leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF000000);
        fill(pose, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL);
        // 两半之间的分隔线
        fill(pose, leftPos + 6, topPos + 110, leftPos + imageWidth - 6, topPos + 111, DIVIDER);

        // 先高亮她手里的那一格（凹槽画在下面会盖住边缘，正好留出内圈）
        int held = menu.heldIndex();
        if (held >= 0 && held < 9) {
            Slot s = menu.slots.get(CompanionInventoryMenu.COMPANION_HOTBAR_FIRST + held);
            fill(pose, leftPos + s.x + 1, topPos + s.y + 1,
                    leftPos + s.x + 17, topPos + s.y + 17, HELD_HIGHLIGHT);
        }

        // 逐格凹槽
        for (Slot s : menu.slots) {
            slotBox(pose, leftPos + s.x, topPos + s.y);
        }
    }

    /** 原版同款凹槽：一圈深色内嵌 + 浅灰底。 */
    private void slotBox(PoseStack pose, int x, int y) {
        fill(pose, x, y, x + 18, y + 18, SLOT_OUTER);
        fill(pose, x + 1, y + 1, x + 17, y + 17, SLOT_INNER);
    }
}
