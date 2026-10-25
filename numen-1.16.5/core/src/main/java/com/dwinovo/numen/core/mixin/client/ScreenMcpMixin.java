package com.dwinovo.numen.core.mixin.client;

import com.dwinovo.numen.core.client.NumenPanelState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 聊天栏发 {@code <mcp>}：客户端截住它（不再当普通聊天发到服务器），
 * 转而向服务端要一份「外接大脑自检 + 接入提示词」——结果进剪贴板，屏幕只留结论
 * （见 {@link NumenPanelState#requestMcpSetup}）。这是给新手的第一步：
 * 装好两端 → 进服 → 按 T 发 {@code <mcp>} → 把剪贴板粘给 AI，完事。
 *
 * <p>挂 {@code Screen.sendMessage(String)}：1.16.5 里聊天与命令都从这一个漏斗出去
 * （ChatScreen 回车 → trim → 这里），所以拦在这最稳；只认<strong>正好</strong>等于
 * {@code <mcp>} 的文本（大小写不计），不会误伤普通聊天与命令。
 */
@Mixin(Screen.class)
public abstract class ScreenMcpMixin {

    @Inject(method = "sendMessage(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void numen$mcpSetup(String content, CallbackInfo ci) {
        if (content == null || !"<mcp>".equalsIgnoreCase(content.trim())) {
            return;
        }
        ci.cancel();
        NumenPanelState.requestMcpSetup();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(
                    new TextComponent("§7[numen] 正在做 MCP 自检，稍等…"), false);
        }
    }
}
