package com.dwinovo.numen.core.inventory;

import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

/**
 * 本模组的容器注册处。目前只有一件：{@code numen:companion_inventory}——同伴的背包（#0027）。
 *
 * <p><b>必须在 common init（{@code onInitialize}）里注册，不能懒注册</b>：开容器包过网带的是
 * 注册表的<b>数字 id</b>，两侧按同一顺序注册才对得上号；懒注册（首次使用才触）在服务端与客户端
 * 发生的时机不同，数字 id 就会错位——客户端会弹出完全不相干的界面。Fabric 的
 * {@link ScreenHandlerRegistry#registerSimple} 就是给这条约束用的入口
 * （原版 {@code MenuType} 的构造器是私有的）。
 *
 * <p>两侧各跑一次同一个 {@link #register()}（common init 两边都会跑），工厂只有客户端用：
 * 服务端那半在打开时由 {@code MenuProvider} 直接构造（挂上真格的 PlayerInventory），
 * 客户端那半由工厂拿哑格顶上、内容靠原版槽同步喂。
 */
public final class NumenMenus {

    /** 同伴的背包。common init（{@link #register}）之后才非 null。 */
    public static MenuType<CompanionInventoryMenu> COMPANION_INVENTORY;

    private NumenMenus() {}

    public static void register() {
        COMPANION_INVENTORY = ScreenHandlerRegistry.registerSimple(
                new ResourceLocation("numen", "companion_inventory"),
                CompanionInventoryMenu::new);
    }
}
