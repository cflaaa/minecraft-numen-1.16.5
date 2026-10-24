package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.inventory.CompanionInventoryMenu;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import static net.minecraft.client.gui.GuiComponent.fill;

/**
 * 同伴背包的界面（G 键打开，#0027）——两块「玩家背包」面板并排：左她、右你。
 *
 * <p>背景直接 blit 原版 {@code inventory.png} 两次，格位逐像素对齐原版。
 * 她那一侧的<b>合成区换成了状态区</b>（血量/饥饿/效果）：她把合成那格盖成面板灰再自绘状态，
 * 因为贴图里烤着合成台的凹槽与箭头，不盖掉就会露出"用不到的家具"。
 *
 * <p>标题也去掉了：原版把标题画在左上角，那位置在本布局里正压着<b>盔甲列</b>——
 * 挡视线又没有信息量（左边靠状态区的名字区分，右边看一眼模型就知道是自己的）。
 *
 * <p>她<b>手里拿着的那一格</b>高亮（{@link CompanionInventoryMenu#heldIndex()}，经数据槽同步）。
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    private static final ResourceLocation INVENTORY_TEXTURE =
            new ResourceLocation("textures/gui/container/inventory.png");
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 166;

    private static final int PANEL_GREY = 0xFFC6C6C6;
    private static final int TEXT = 0xFF404040;
    private static final int TEXT_DIM = 0xFF707070;
    /** 血量/饥饿画成 20 个小格（一格 = 一点），与原版 HUD 同色，不写数字。 */
    private static final int CELL_W = 3;
    private static final int CELL_H = 8;
    private static final int CELL_GAP = 1;
    private static final int CELLS = 20;
    /** 空格子：拿槽位那种中灰，落在面板灰上正好读成"没点亮"。 */
    private static final int CELL_EMPTY = 0xFF8B8B8B;
    private static final int HEALTH_FILL = 0xFFD23A3A;
    private static final int FOOD_FILL = 0xFFC98A2B;

    private static final int HELD_HIGHLIGHT = 0x55FFFFFF;
    private static final int HELD_EDGE = 0xCCFFFFFF;

    /** 她那一侧被状态区占掉的区域（面板相对坐标）：原合成区那一块。 */
    /** 她那一侧被状态区占掉的区域（面板相对坐标）。⚠️ 必须避开副手槽（x 77..95），
     *  取 86 会盖掉副手右半边（2026-10-24 截图）。 */
    private static final int STATUS_X = 97;
    private static final int STATUS_W = PANEL_W - STATUS_X;
    private static final int STATUS_H = 80;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = CompanionInventoryMenu.WIDTH;
        this.imageHeight = CompanionInventoryMenu.HEIGHT;
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

        // 把她那侧的合成家具（凹槽/箭头/结果格）盖成面板灰——那一块改画状态
        fill(pose, this.leftPos + CompanionInventoryMenu.LEFT_PANEL_X + STATUS_X, this.topPos + CompanionInventoryMenu.PAD,
                this.leftPos + CompanionInventoryMenu.LEFT_PANEL_X + PANEL_W,
                this.topPos + CompanionInventoryMenu.PAD + STATUS_H, PANEL_GREY);

        // 高亮她手里那一格（画在贴图之上；物品图标由 super.render 之后画，衬在高亮上）
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
        LivingEntity her = herEntity();
        if (her != null) {
            InventoryScreen.renderEntityInInventory(
                    this.leftPos + CompanionInventoryMenu.LEFT_PANEL_X + 51, y, 30, mouseX, mouseY, her);
        }
        if (this.minecraft.player != null) {
            InventoryScreen.renderEntityInInventory(
                    this.leftPos + CompanionInventoryMenu.RIGHT_PANEL_X + 51, y, 30, mouseX, mouseY,
                    this.minecraft.player);
        }
    }

    /**
     * 只画她那一侧的状态区，**不画**原版那两条标题（它们会压在盔甲列上）。
     * 内容：名字、血量、饥饿、效果（buff）。血/饥饿经数据槽同步；效果直接从她的实体读
     * （{@code UpdateMobEffect} 对所有客户端都发，不用自己同步）。
     */
    @Override
    protected void renderLabels(PoseStack pose, int mouseX, int mouseY) {
        // ⚠️ renderLabels 的 PoseStack 已经被 translate(leftPos, topPos) 过了
        //（原版 render 里 translatef 在 renderBg 之后、renderLabels 之前），
        // 这里只能用**面板相对**坐标；再加一次 leftPos 会让整块文字跑到右下面板上去。
        int x = CompanionInventoryMenu.LEFT_PANEL_X + STATUS_X;
        int y = CompanionInventoryMenu.PAD + 2;

        LivingEntity her = herEntity();
        this.font.draw(pose, her != null ? her.getName().getString() : "同伴", x, y, TEXT);
        y += 10;

        // 生命：20 格（每格一点），与原版 HUD 同色的红
        this.font.draw(pose, "生命", x, y, TEXT_DIM);
        y += 10;
        drawCells(pose, x, y, Math.round(menu.health()), HEALTH_FILL);
        y += CELL_H + 3;

        // 饥饿：同样 20 格，南瓜色
        this.font.draw(pose, "饥饿", x, y, TEXT_DIM);
        y += 10;
        drawCells(pose, x, y, menu.food(), FOOD_FILL);
        y += CELL_H + 3;

        // 效果（buff）：最多两条 + 省略号（这块地方就这么高）
        this.font.draw(pose, "效果", x, y, TEXT_DIM);
        y += 10;
        if (her == null || her.getActiveEffects().isEmpty()) {
            this.font.draw(pose, "无", x, y, TEXT_DIM);
            return;
        }
        java.util.List<MobEffectInstance> effects = new java.util.ArrayList<>(her.getActiveEffects());
        int shown = 0;
        for (MobEffectInstance e : effects) {
            if (shown >= 2) {
                this.font.draw(pose, "…", x, y, TEXT_DIM);
                break;
            }
            this.font.draw(pose, effectLine(e), x, y, TEXT);
            y += 9;
            shown++;
        }
    }

    /** 把一点画成一格：点亮的着色，没点亮的用槽位中灰。一格 = 一点，共 20 格。 */
    private void drawCells(PoseStack pose, int x, int y, int filled, int color) {
        for (int i = 0; i < CELLS; i++) {
            int cx = x + i * (CELL_W + CELL_GAP);
            fill(pose, cx, y, cx + CELL_W, y + CELL_H, i < filled ? color : CELL_EMPTY);
        }
    }

    /** 一条效果：名字 + 等级 + 剩余时间。 */
    private String effectLine(MobEffectInstance e) {
        String name = e.getEffect().getDisplayName().getString();
        int amp = e.getAmplifier();
        String level = switch (amp) {
            case 0 -> "";
            case 1 -> " II";
            case 2 -> " III";
            case 3 -> " IV";
            default -> " " + (amp + 1);
        };
        String time;
        if (e.isNoCounter()) {
            time = "∞";
        } else {
            int secs = Math.max(0, e.getDuration() / 20);
            time = (secs / 60) + ":" + String.format("%02d", secs % 60);
        }
        return name + level + "  " + time;
    }

    /** 她此刻的实体（客户端没加载到就 null，模型与 buff 都跳过）。 */
    private LivingEntity herEntity() {
        if (this.minecraft.level == null) {
            return null;
        }
        var e = this.minecraft.level.getEntity(menu.companionEntityId());
        return e instanceof LivingEntity living ? living : null;
    }
}
