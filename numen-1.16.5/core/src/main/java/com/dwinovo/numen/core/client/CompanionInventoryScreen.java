package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.inventory.CompanionInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 同伴背包的界面（G 键打开，#0027）——大箱子的观感：{@code generic_54} 贴图五行版，
 * 标题是服务端送来的「某某 的背包」。不做任何自绘：这一屏的全部价值就是
 * 「和原版背包一个手感」，自绘反而会毁掉它。
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    /** 原版大箱子的贴图（最多六行，五行只用上部）。 */
    private static final ResourceLocation CONTAINER_BACKGROUND =
            new ResourceLocation("textures/gui/container/generic_54.png");

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 114 + 5 * 18;   // 与 ChestScreen 五行同款
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, delta);
        this.renderTooltip(pose, mouseX, mouseY);
    }

    /**
     * 贴图是<b>两段式</b>的（原版 {@code ChestScreen} 同款）：
     * <ul>
     *   <li>容器区：从贴图 (0,0) 取 {@code 行数*18+17} 高；</li>
     *   <li>玩家区：从贴图 (0,126) 取 96 高，接在容器区正下方。</li>
     * </ul>
     * 一次 blit 整个 {@code imageHeight} 会把两段之间那段 18 像素（generic_54 里六行箱与玩家区之间的空隙）
     * 一起画进来，而且玩家区整体错位——2026-10-24 真机实测踩到。
     */
    @Override
    protected void renderBg(PoseStack pose, float delta, int mouseX, int mouseY) {
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F);
        this.minecraft.getTextureManager().bind(CONTAINER_BACKGROUND);
        int rows = 5;
        blit(pose, this.leftPos, this.topPos, 0.0F, 0.0F, this.imageWidth, rows * 18 + 17, 256, 256);
        blit(pose, this.leftPos, this.topPos + rows * 18 + 17, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
    }
}
