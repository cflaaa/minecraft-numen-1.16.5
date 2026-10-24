package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.inventory.CompanionInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static net.minecraft.client.gui.GuiComponent.fill;

/**
 * 同伴背包的界面（G 键打开，#0027）——<b>两块完整「玩家背包」面板并排</b>：
 * 左边是她的、右边是你自己的，都带角色模型与 2×2 合成，格位与贴图逐像素照原版
 * {@code InventoryScreen}（176×166 的 {@code inventory.png}）。
 *
 * <p>背景直接 blit 原版贴图两次——凹槽、模型黑框、合成箭头全在贴图里，位置与格位天然对齐
 * （这也是当初改用原版贴图的原因：自绘只能画凹槽，画不出模型框与合成台那套家具）。
 *
 * <p>她<b>手里拿着的那一格</b>由 {@link CompanionInventoryMenu#heldIndex()} 高亮
 * （经数据槽同步）——手持不是单独一格，是快捷栏里的选中格。
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("textures/gui/container/inventory.png");
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 166;

    private static final int HELD_HIGHLIGHT = 0x55FFFFFF;
    private static final int HELD_EDGE = 0xCCFFFFFF;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = CompanionInventoryMenu.WIDTH;
        this.imageHeight = CompanionInventoryMenu.HEIGHT;
        // 两个标题：左边用菜单的 displayName（"Alice 的背包"），右边借"物品栏"那条标签的位置。
        this.titleLabelX = CompanionInventoryMenu.LEFT_PANEL_X + 8;
        this.titleLabelY = CompanionInventoryMenu.PAD + 6;
        this.inventoryLabelX = CompanionInventoryMenu.RIGHT_PANEL_X + 8;
        this.inventoryLabelY = CompanionInventoryMenu.PAD + 6;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, delta);
        renderModels(pose, mouseX, mouseY);
        this.renderTooltip(pose, mouseX, mouseY);
    }

    @Override
    protected void renderBg(PoseStack pose, float delta, int mouseX, int mouseY) {
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.minecraft.getTextureManager().bind(INVENTORY_TEXTURE);
        blit(pose, this.leftPos + CompanionInventoryMenu.LEFT_PANEL_X, this.topPos + CompanionInventoryMenu.PAD,
                0, 0, PANEL_W, PANEL_H);
        blit(pose, this.leftPos + CompanionInventoryMenu.RIGHT_PANEL_X, this.topPos + CompanionInventoryMenu.PAD,
                0, 0, PANEL_W, PANEL_H);

        // 高亮她手里那一格——画在贴图之上（贴图的凹槽是不透明的），物品图标由 super.render
        // 在这之后画，所以高亮天然衬在图标下面。
        int held = menu.heldIndex();
        if (held >= 0 && held < 9) {
            Slot s = menu.slots.get(CompanionInventoryMenu.COMPANION_HOTBAR_FIRST + held);
            int x = leftPos + s.x;
            int y = topPos + s.y;
            fill(pose, x + 1, y + 1, x + 17, y + 17, HELD_HIGHLIGHT);
            fill(pose, x + 1, y + 1, x + 17, y + 2, HELD_EDGE);
            fill(pose, x + 1, y + 16, x + 17, y + 17, HELD_EDGE);
            fill(pose, x + 1, y + 1, x + 2, y + 17, HELD_EDGE);
            fill(pose, x + 16, y + 1, x + 17, y + 17, HELD_EDGE);
        }
    }

    /** 两块面板各画一个角色模型：左边是她（实体 id 经扩展开屏数据过来），右边是你。 */
    private void renderModels(PoseStack pose, int mouseX, int mouseY) {
        int y = this.topPos + CompanionInventoryMenu.PAD + 75;
        if (this.minecraft.level != null) {
            var her = this.minecraft.level.getEntity(menu.companionEntityId());
            if (her instanceof LivingEntity living) {
                InventoryScreen.renderEntityInInventory(
                        this.leftPos + CompanionInventoryMenu.LEFT_PANEL_X + 51, y, 30, mouseX, mouseY, living);
            }
        }
        if (this.minecraft.player != null) {
            InventoryScreen.renderEntityInInventory(
                    this.leftPos + CompanionInventoryMenu.RIGHT_PANEL_X + 51, y, 30, mouseX, mouseY,
                    this.minecraft.player);
        }
    }
}
