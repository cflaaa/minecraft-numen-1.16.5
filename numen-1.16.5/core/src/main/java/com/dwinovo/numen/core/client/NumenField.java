package com.dwinovo.numen.core.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

/**
 * 一个手写的单行输入框。
 *
 * <p>为什么不用原版的 {@code EditBox}：面板是整块自绘的，原版输入框自带一层底色和边框，
 * 盖在深色面板上像贴了张便签；而 {@code setBordered(false)} 之后它又只剩光标，
 * 还得自己补背景。既然背景、边框、光标都要自己画，不如连编辑逻辑一起收进来，
 * 省掉「子控件由 Screen 统一渲染、必然被面板盖住」这个顺序问题。
 *
 * <p>支持：输入、退格、Delete、左右、Home/End、点击定位光标，以及
 * <b>Ctrl+V 粘贴 / Ctrl+C 复制</b>（API key 这类长文本没法手打，2026-10-14 补）。
 * 没有多行和选区——所以没有 Ctrl+A 全选 / Ctrl+X 剪切。
 */
public final class NumenField {

    public String value;
    /** 占位文案。可变：说话框的占位要跟着「选中了谁」变。 */
    public String placeholder;
    public final int maxLength;

    private int cursor;
    private boolean masked;
    /** 上一次输入框内容，用来判断「有没有被改过」。 */
    private String lastSeen;

    public NumenField(String value, String placeholder, int maxLength) {
        this.value = value == null ? "" : value;
        this.placeholder = placeholder;
        this.maxLength = maxLength;
        this.cursor = this.value.length();
        this.lastSeen = this.value;
    }

    public NumenField masked() {
        this.masked = true;
        return this;
    }

    public boolean isMasked() {
        return masked;
    }

    public void setValue(String v) {
        this.value = v == null ? "" : v;
        this.cursor = this.value.length();
        this.lastSeen = this.value;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    /** 内容被用户改过（相对上一次读到的值）。面板用它决定要不要写回配置。 */
    public boolean dirty() {
        return !value.equals(lastSeen);
    }

    public void markClean() {
        lastSeen = value;
    }

    /** 「上一次读到的值」。给「程序改内容但仍算作待保存」的场合用，见 {@link #restoreLastSeen}。 */
    public String lastSeenValue() {
        return lastSeen;
    }

    /**
     * 把「上一次读到的值」按回去。
     *
     * <p>给下拉选模型用：{@link #setValue} 会把 {@code lastSeen} 一起更新，
     * 于是程序填进去的新模型名看起来像「本来就是这个值」，{@link #dirty()} 为假、
     * 保存时被跳过。先把旧值记下来、填完再按回去，这一格就如实是「改过了、待保存」。
     */
    public void restoreLastSeen(String previous) {
        this.lastSeen = previous == null ? "" : previous;
    }

    public void focus() {
        cursor = value.length();
    }

    public void click(int localX, Font font) {
        cursor = value.length();
        int best = 0;
        int bestDist = Math.abs(localX);
        for (int i = 1; i <= value.length(); i++) {
            int d = Math.abs(localX - font.width(value.substring(0, i)));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        cursor = best;
    }

    /** 被吃掉的按键返回 true。旧签名保留（视为无修饰键）。 */
    public boolean keyPressed(int keyCode) {
        return keyPressed(keyCode, 0);
    }

    /**
     * 被吃掉的按键返回 true。{@code mods} 是 GLFW 修饰键位掩码
     * （{@code Screen.keyPressed} 的第三参原样传进来）。
     */
    public boolean keyPressed(int keyCode, int mods) {
        if ((mods & GLFW.GLFW_MOD_CONTROL) != 0) {
            var keyboard = Minecraft.getInstance().keyboardHandler;
            if (keyCode == GLFW.GLFW_KEY_V) {
                paste(keyboard.getClipboard());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_C) {
                keyboard.setClipboard(value);
                return true;
            }
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursor > 0) {
                    value = value.substring(0, cursor - 1) + value.substring(cursor);
                    cursor--;
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < value.length()) {
                    value = value.substring(0, cursor) + value.substring(cursor + 1);
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (cursor > 0) {
                    cursor--;
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (cursor < value.length()) {
                    cursor++;
                }
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                cursor = 0;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                cursor = value.length();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean charTyped(char c) {
        if (c < ' ' || c == 127) {
            return false;
        }
        if (value.length() >= maxLength) {
            return true;
        }
        value = value.substring(0, cursor) + c + value.substring(cursor);
        cursor++;
        return true;
    }

    /**
     * 在光标处粘入剪贴板文本。丢掉控制字符和换行（密钥/URL/模型名都不该有），
     * 超出 {@link #maxLength} 的部分直接截掉。
     */
    private void paste(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && value.length() + sb.length() < maxLength; i++) {
            char c = text.charAt(i);
            if (c >= ' ' && c != 127) {
                sb.append(c);
            }
        }
        if (sb.length() == 0) {
            return;
        }
        value = value.substring(0, cursor) + sb + value.substring(cursor);
        cursor += sb.length();
    }

    /**
     * 画出来。返回画完之后的下一个可用 x（都是右边界 + 4），方便横排摆控件。
     *
     * @param blinkOn 光标这一帧亮不亮（外面按时间算好传进来，保证整屏一起闪）
     */
    public int draw(PoseStack pose, Font font, NumenUi.Colors c,
                    int x, int y, int w, int h, boolean focused, boolean blinkOn) {
        NumenUi.rect(pose, x, y, x + w, y + h, c.inputBg());
        NumenUi.frame(pose, x, y, x + w, y + h, focused ? c.accent() : c.inputBorder());

        String shown = masked ? "\u2022".repeat(value.length()) : value;
        int pad = 4;
        int inner = w - pad * 2;

        // 光标滚出可视区时把文字整体左推，保证光标永远看得见。
        int cursorPx = font.width(shown.substring(0, Math.min(cursor, shown.length())));
        int startPx = Math.max(0, cursorPx - inner);
        String visible = shown;
        int drawnCursor = cursorPx;
        if (startPx > 0) {
            int cut = 0;
            while (cut < shown.length() && font.width(shown.substring(0, cut + 1)) <= startPx) {
                cut++;
            }
            visible = shown.substring(cut);
            drawnCursor = cursorPx - font.width(shown.substring(0, cut));
        }

        int textY = y + (h - 8) / 2;
        if (value.isEmpty() && !focused) {
            NumenUi.text(pose, font, NumenUi.clip(font, placeholder, inner), x + pad, textY, c.textMuted());
        } else {
            NumenUi.text(pose, font, NumenUi.clip(font, visible, inner + 8), x + pad, textY, c.textPrimary());
        }

        if (focused && blinkOn) {
            int cx = x + pad + Math.max(0, Math.min(drawnCursor, inner));
            NumenUi.rect(pose, cx, y + 3, cx + 1, y + h - 3, c.accent());
        }
        return x + w;
    }
}
