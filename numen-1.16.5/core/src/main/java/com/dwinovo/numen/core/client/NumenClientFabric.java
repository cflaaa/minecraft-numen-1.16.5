package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.net.NumenPanelNet;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端的入口点。
 *
 * <p>由 {@code fabric.mod.json} 的 {@code client} 入口点加载——**专用服务端上这个类不会被加载**，
 * 所以这里可以随便碰 {@code net.minecraft.client.*}。反过来，{@code core/net} 与 {@code core/mcp}
 * 那些共享类里一个客户端类型都不能出现，那条线由 {@code server-api-check.py} 看着。
 *
 * <p>三件事：绑 G 键、收服务端推来的快照、每客户端 tick 处理一次按键。
 */
public final class NumenClientFabric implements ClientModInitializer {

    /**
     * 键位分类。原版 Controls 里会多出一个「Numen」区，玩家可以自己改键。
     *
     * <p>写的是翻译键（{@code key.categories.numen}），对应 {@code assets/numen/lang/*.json}。
     * Fabric 的 {@code MixinGameOptions} 会把模组分类挂进原版控制界面，不用自己动手。
     */
    private static final String CATEGORY = "key.categories.numen";

    private static KeyMapping openPanel;

    /**
     * 排查界面用的开关：加 JVM 参数 {@code -Dnumen.panel=settings}（或 {@code companions}）启动，
     * 客户端一进世界就自动把面板开到那一页。
     *
     * <p>存在的理由很具体：面板的布局代码只在「那一页真的被画出来」时才跑，
     * 光启动客户端验证不到它；要截图核对布局就得有人先按一下 G 再点页签。
     * 这个开关让截图能自动化（见 {@code verify-panel.py}）。不设这个属性时行为与原来完全一样。
     */
    private static final String DEBUG_PANEL = System.getProperty("numen.panel");
    private static boolean debugOpened;

    @Override
    public void onInitializeClient() {
        openPanel = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.numen.open_panel",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(NumenPanelNet.CHANNEL,
                (client, handler, buf, responseSender) -> {
                    // netty 线程：先把字符串读出来（buf 出了回调就失效），再跳客户端主线程。
                    String json;
                    try {
                        json = buf.readUtf(32767);
                    } catch (RuntimeException ex) {
                        NumenCore.warn("[numen-panel] 收包读不出来：{}", ex.toString());
                        return;
                    }
                    client.execute(() -> NumenPanelState.accept(json));
                });

        ClientTickEvents.END_CLIENT_TICK.register(NumenClientFabric::tick);
        NumenCore.info("[numen-panel] 客户端已就绪：按 G 打开面板（可在「选项 → 控制」里改键）");
    }

    /**
     * 每客户端 tick 查一次按键。
     *
     * <p>用 {@code consumeClick()} 而不是 {@code isDown()}：前者把这一 tick 攒下的点击都取走，
     * 不会因为一帧里按了两下就丢掉一次。
     *
     * <p>面板开着的时候按 G = 关掉。这是「同一个键开、同一个键关」的常识，
     * 不这么写的话玩家会去按 Esc——那个也行，但顺手再按一次 G 更自然。
     */
    private static void tick(Minecraft mc) {
        while (openPanel.consumeClick()) {
            if (mc.screen instanceof NumenPanelScreen) {
                mc.setScreen(null);
            } else if (mc.screen == null && mc.player != null) {
                mc.setScreen(new NumenPanelScreen());
            }
        }
        // 排查用：自动开一次面板（只在设了 -Dnumen.panel 时）。
        //
        // 三个条件都是刻意放宽的，理由都是「验证脚本只能停在标题界面」：
        //   * 不要求 mc.player != null —— 面板纯画界面 + 读快照，不碰世界，标题界面开着它没问题；
        //     布局是否溢出（fits 的判定）与在不在世界里毫无关系。
        //   * 不要求 mc.screen == null —— 标题界面上 mc.screen 就是 TitleScreen，永远不为 null。
        //   * 延迟到 titleScreen 出现之后再动手：太早设 screen 会被原版的初始化覆盖掉。
        // 之前硬要求「进了世界」，结果验证脚本跑完 240 秒，面板一次都没打开。
        if (DEBUG_PANEL != null && !debugOpened
                && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
            debugOpened = true;
            mc.setScreen(new NumenPanelScreen().debugTab(DEBUG_PANEL));
            NumenCore.info("[numen-panel] 排查开关生效：自动打开面板的 {} 页", DEBUG_PANEL);
        }
    }
}
