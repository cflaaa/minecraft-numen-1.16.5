package com.dwinovo.numen.core.inventory;

import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

/**
 * 本模组的容器注册处。目前只有一件：{@code numen:companion_inventory}——同伴的背包（#0027）。
 *
 * <p><b>必须在 common init（{@code onInitialize}）里注册，不能懒注册</b>：开容器包过网带的是
 * 注册表的<b>数字 id</b>，两侧按同一顺序注册才对得上号；懒注册（首次使用才触）在服务端与客户端
 * 发生的时机不同，数字 id 就会错位——客户端会弹出完全不相干的界面。
 *
 * <p>这一版用 Fabric 的<b>扩展类型</b>（{@link ScreenHandlerRegistry#registerExtended}）：
 * 开屏时服务端额外塞一段数据（她的实体 id），客户端工厂读它——屏幕据此画她的角色模型。
 * 原版 {@code MenuType} 的工厂只有 {@code (windowId, playerInventory)}，塞不下这段。
 */
public final class NumenMenus {

    /** 同伴的背包。common init（{@link #register}）之后才非 null。 */
    public static MenuType<CompanionInventoryMenu> COMPANION_INVENTORY;

    private NumenMenus() {}

    public static void register() {
        COMPANION_INVENTORY = ScreenHandlerRegistry.registerExtended(
                new ResourceLocation("numen", "companion_inventory"),
                (syncId, playerInv, buf) -> new CompanionInventoryMenu(syncId, playerInv, buf.readVarInt()));
    }
}
