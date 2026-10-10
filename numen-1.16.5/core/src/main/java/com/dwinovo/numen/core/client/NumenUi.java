package com.dwinovo.numen.core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;

/**
 * 面板的配色与画图小工具。
 *
 * <p>配色抄的是上游 {@code ui/NumenTheme}：**只认语义槽位**（面板底、分隔线、强调色…），
 * 不在这里出现「某个地方该用哪个色号」的判断。换主题就是换一整张表。
 *
 * <p>继承 {@link GuiComponent} 只是为了拿到它那几个 {@code fill}/{@code drawString}
 * ——它们在 1.16.5 里是 {@code protected static}，不继承就用不了。
 * 这个类不会被实例化。
 */
public final class NumenUi extends GuiComponent {

    /** 语义色表。上游那张表的子集——面板上用得着的槽位。 */
    public record Colors(
            int panelBg, int sectionBg, int divider,
            int textPrimary, int textSecondary, int textMuted,
            int accent, int danger, int success, int warning,
            int inputBg, int inputBorder,
            int hover, int selected) {}

    public static final Colors DARK = new Colors(
            0xF0191A20, 0xFF23242B, 0xFF383A44,
            0xFFF0F1F4, 0xFFA9ADBB, 0xFF707585,
            0xFF5B9CFF, 0xFFE5534B, 0xFF4CBB5A, 0xFFD9A23C,
            0xFF272932, 0xFF414453,
            0xFF2E313B, 0xFF35507F);

    public static final Colors LIGHT = new Colors(
            0xF0F4F4F6, 0xFFFFFFFF, 0xFFDDDDE2,
            0xFF1E1E24, 0xFF55555E, 0xFF9090A0,
            0xFF3B78E7, 0xFFC7392F, 0xFF3E8E41, 0xFFB98617,
            0xFFECECEF, 0xFFCFCFD8,
            0xFFE6E6EB, 0xFFBDD3F7);

    private NumenUi() {}

    // ---- 画 ----

    public static void rect(PoseStack pose, int x1, int y1, int x2, int y2, int argb) {
        fill(pose, x1, y1, x2, y2, argb);
    }

    /** 空心矩形，线宽 1。 */
    public static void frame(PoseStack pose, int x1, int y1, int x2, int y2, int argb) {
        fill(pose, x1, y1, x2, y1 + 1, argb);
        fill(pose, x1, y2 - 1, x2, y2, argb);
        fill(pose, x1, y1 + 1, x1 + 1, y2 - 1, argb);
        fill(pose, x2 - 1, y1 + 1, x2, y2 - 1, argb);
    }

    public static void text(PoseStack pose, Font font, String s, int x, int y, int argb) {
        font.draw(pose, s, (float) x, (float) y, argb);
    }

    public static void textShadow(PoseStack pose, Font font, String s, int x, int y, int argb) {
        font.drawShadow(pose, s, (float) x, (float) y, argb);
    }

    public static void textCentered(PoseStack pose, Font font, String s, int cx, int y, int argb) {
        font.draw(pose, s, (float) (cx - font.width(s) / 2), (float) y, argb);
    }

    public static void textRight(PoseStack pose, Font font, String s, int right, int y, int argb) {
        font.draw(pose, s, (float) (right - font.width(s)), (float) y, argb);
    }

    /**
     * 超宽就截断加省略号。
     *
     * <p>没有用 {@code Font.plainSubstrByWidth}——那个在 1.16.5 叫别的名字，
     * 而这里要的语义就是「量出来再砍」，自己量最省事也最不依赖版本。
     */
    public static String clip(Font font, String s, int maxWidth) {
        if (s == null) {
            return "";
        }
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int ellipsis = font.width("...");
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); i++) {
            int w = font.width(String.valueOf(s.charAt(i)));
            if (used + w + ellipsis > maxWidth) {
                break;
            }
            out.append(s.charAt(i));
            used += w;
        }
        return out + "...";
    }

    // ---- 命中判定 ----

    public static boolean inside(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }
}
