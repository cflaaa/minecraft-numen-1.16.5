package com.dwinovo.numen.core.client;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.net.NumenPanelNet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextComponent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 G 打开的面板——把 {@code /numen} 那组命令换成能点的界面。
 *
 * <p>面板是**整块自绘**的：底色、圆角矩形（用直角，1.16.5 画圆角要么贴图要么拆四段弧，
 * 不值当）、分隔线、按钮、输入框，全都是自己画。不用原版的 Button/EditBox，
 * 理由见 {@link NumenField} 的类注释。
 *
 * <p>布局在 {@link #render} 里算、顺手把可点区域记进 {@link #hits}；
 * {@code mouseClicked} 只查这张表。这样「画在哪」和「点到哪」永远一致——
 * 分开算两遍是这类界面最经典的错位来源。
 *
 * <p>这个界面**不暂停游戏**（{@code isPauseScreen} 为 false）：开着面板看同伴干活才是常态。
 */
public final class NumenPanelScreen extends Screen {

    // ---- 尺寸 ----
    private static final int HEADER_H = 24;
    private static final int STATUS_H = 22;
    private static final int ROW_H = 18;
    private static final int FIELD_H = 16;
    private static final int BTN_H = 16;
    private static final int PAD = 10;

    private enum Tab { COMPANIONS, SETTINGS }

    /** 一块可点区域。{@code action} 的取值见 {@link #onAction}。 */
    private record Hit(int x1, int y1, int x2, int y2, String action) {}

    private final List<Hit> hits = new ArrayList<>();

    private Tab tab = Tab.COMPANIONS;
    private NumenUi.Colors colors = NumenUi.DARK;

    // ---- 输入框 ----
    private final NumenField summonName =
            new NumenField("", "同伴名字：3~16 位字母 / 数字 / 下划线", 16);
    private final NumenField tellText =
            new NumenField("", "对她说一句话，回车发送", 200);
    private final NumenField cfgProvider = new NumenField("", "nvidia / openai / deepseek / moonshot", 32);
    private final NumenField cfgModel = new NumenField("", "点右边「获取模型」挑一个", 96);
    private final NumenField cfgBaseUrl = new NumenField("", "https://integrate.api.nvidia.com/v1", 200);
    private final NumenField cfgApiKey = new NumenField("", "留空 = 不改动", 200).masked();
    private final NumenField cfgPersona = new NumenField("", "人设，进系统提示", 300);
    private final NumenField cfgInitiative = new NumenField("", "1~10", 2);

    private NumenField focused;

    private String selectedUuid;
    private int scroll;

    private int px, py, pw, ph;
    private int refreshCooldown;
    /** 配置字段有没有被服务端的值填过（只填一次，之后不覆盖用户正在敲的内容）。 */
    private boolean configPrimed;
    private String hoverAction;
    /** 这一页画过一次没有（排查日志只报一次，别刷屏）。 */
    private boolean reportedLayout;

    // ---- 「获取模型」的下拉 ----
    /**
     * 模型下拉开着吗。
     *
     * <p>做成**覆盖在设置页之上的浮层**而不是「在页面里插一块列表」，是因为正文区高度是被窗口
     * 卡死的（240 高的窗口只有 174 像素），再塞十几行模型进去必然压到状态栏上——
     * 那正是刚修掉的「按键重叠」。浮层不占正文区的预算，多长的列表都放得下。
     */
    private boolean modelsOpen;
    /** 下拉里滚到第几项。模型动辄 80 个（NVIDIA 实测），不放滚会溢出屏幕。 */
    private int modelsScroll;

    public NumenPanelScreen() {
        super(new TextComponent("Numen"));
    }

    /**
     * 排查用：把面板预先切到某一页（{@code "settings"} / {@code "companions"}），返回自身便于连写。
     *
     * <p>由 {@code -Dnumen.panel=...} 触发，见 {@link NumenClientFabric#DEBUG_PANEL}。
     * 包内可见即可 —— 除了那个开关没有别的调用方。
     */
    NumenPanelScreen debugTab(String name) {
        if ("settings".equalsIgnoreCase(name)) {
            tab = Tab.SETTINGS;
        } else if ("companions".equalsIgnoreCase(name)) {
            tab = Tab.COMPANIONS;
        }
        return this;
    }

    @Override
    protected void init() {
        pw = Math.min(400, this.width - 40);
        // 高度上限给到 280、上下只留 10 像素：设置页要 157 像素正文，
        // 原来是 min(250, height-40)，在 252 高的窗口上只剩 166，MCP 那一块就被挤到状态栏上了。
        ph = Math.min(280, this.height - 20);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
        colors = NumenClientPrefs.dark() ? NumenUi.DARK : NumenUi.LIGHT;
        NumenPanelState.requestRefresh();
    }

    @Override
    public void tick() {
        // 一秒要一次新快照。同伴在动、血量在变，面板不刷新就是一张过期的照片。
        if (refreshCooldown-- <= 0) {
            refreshCooldown = 20;
            NumenPanelState.requestRefresh();
        }
        pullConfigIfNeeded();
        // 说话框的占位跟着「选中了谁」走
        String target = selectedName();
        tellText.placeholder = target == null
                ? "先在列表里选一只同伴"
                : "对 " + target + " 说…（回车发送）";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(PoseStack pose) {
        // 不要原版那张土色底图：面板是半透明的，底下应该透出世界。
        NumenUi.rect(pose, 0, 0, this.width, this.height, 0x90000000);
    }

    // ================= 画 =================

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        renderBackground(pose);
        hits.clear();
        hoverAction = null;

        if (!reportedLayout) {
            reportedLayout = true;
            // 报一次就够了。验证脚本靠这行确认「这一页真的被画过」；
            // 被 fits() 拦下来的块另有「放不下」的日志。
            NumenCore.info("[numen-panel] {} 页已绘制：窗口 {}x{}，面板 {}x{}，正文区 y={}→{}",
                    tab.name().toLowerCase(), this.width, this.height, pw, ph,
                    py + HEADER_H, py + ph - STATUS_H);
        }

        NumenUi.rect(pose, px, py, px + pw, py + ph, colors.panelBg());
        NumenUi.frame(pose, px, py, px + pw, py + ph, colors.divider());

        drawHeader(pose, mouseX, mouseY);
        int bodyTop = py + HEADER_H;
        int bodyBottom = py + ph - STATUS_H;
        if (tab == Tab.COMPANIONS) {
            drawCompanions(pose, bodyTop, bodyBottom, mouseX, mouseY);
        } else {
            drawSettings(pose, bodyTop, bodyBottom, mouseX, mouseY);
        }
        drawStatus(pose);
        // 模型下拉画在最后 = 盖住一切。它自己负责把身下的可点区域从 hits 里剔掉，
        // 否则「看得见的是下拉、点到的却是底下的按钮」。
        if (tab == Tab.SETTINGS) {
            drawModelPicker(pose, bodyTop, bodyBottom, mouseX, mouseY);
        }
    }

    private void drawHeader(PoseStack pose, int mouseX, int mouseY) {
        NumenUi.rect(pose, px + 1, py + 1, px + pw - 1, py + HEADER_H, colors.sectionBg());
        NumenUi.textShadow(pose, this.font, "Numen", px + PAD, py + 8, colors.accent());
        NumenUi.rect(pose, px + 1, py + HEADER_H, px + pw - 1, py + HEADER_H + 1, colors.divider());

        int closeX = px + pw - PAD - 14;
        boolean closeHover = NumenUi.inside(mouseX, mouseY, closeX, py + 5, closeX + 14, py + 19);
        if (closeHover) {
            NumenUi.rect(pose, closeX, py + 5, closeX + 14, py + 19, colors.danger());
        }
        NumenUi.textCentered(pose, this.font, "x", closeX + 7, py + 8,
                closeHover ? 0xFFFFFFFF : colors.textSecondary());
        hits.add(new Hit(closeX, py + 5, closeX + 14, py + 19, "close"));

        // 两个页签，从右往左摆
        int tabW = 46;
        int tabY = py + 4;
        int settingsX = closeX - 6 - tabW;
        int companionsX = settingsX - 4 - tabW;
        drawTab(pose, mouseX, mouseY, companionsX, tabY, tabW, "同伴", Tab.COMPANIONS);
        drawTab(pose, mouseX, mouseY, settingsX, tabY, tabW, "设置", Tab.SETTINGS);
    }

    private void drawTab(PoseStack pose, int mouseX, int mouseY,
                         int x, int y, int w, String label, Tab which) {
        boolean active = tab == which;
        boolean hover = NumenUi.inside(mouseX, mouseY, x, y, x + w, y + FIELD_H);
        if (active) {
            NumenUi.rect(pose, x, y, x + w, y + FIELD_H, colors.selected());
        } else if (hover) {
            NumenUi.rect(pose, x, y, x + w, y + FIELD_H, colors.hover());
        }
        NumenUi.textCentered(pose, this.font, label, x + w / 2, y + 4,
                active ? colors.textPrimary() : colors.textSecondary());
        hits.add(new Hit(x, y, x + w, y + FIELD_H, "tab:" + which.name().toLowerCase()));
    }

    // ---- 同伴页 ----

    private void drawCompanions(PoseStack pose, int top, int bottom, int mouseX, int mouseY) {
        JsonArray list = companions();

        int y = top + 4;
        NumenUi.text(pose, this.font, "同伴 " + list.size() + " 只", px + PAD, y, colors.textSecondary());
        NumenUi.textRight(pose, this.font, "选中一行再操作", px + pw - PAD, y, colors.textMuted());
        y += 12;

        // 列表
        int listX1 = px + PAD;
        int listX2 = px + pw - PAD;
        int listY1 = y;
        int visibleRows = 4;
        int listY2 = listY1 + visibleRows * ROW_H;
        NumenUi.rect(pose, listX1, listY1, listX2, listY2, colors.sectionBg());
        NumenUi.frame(pose, listX1, listY1, listX2, listY2, colors.divider());

        if (list.size() == 0) {
            NumenUi.textCentered(pose, this.font,
                    "还没有同伴。在下面填个名字，点「召唤」。",
                    (listX1 + listX2) / 2, (listY1 + listY2) / 2 - 4, colors.textMuted());
        } else {
            int maxScroll = Math.max(0, list.size() - visibleRows);
            if (scroll > maxScroll) {
                scroll = maxScroll;
            }
            if (scroll < 0) {
                scroll = 0;
            }
            for (int i = 0; i < visibleRows && i + scroll < list.size(); i++) {
                JsonObject c = list.get(i + scroll).getAsJsonObject();
                int rowY = listY1 + i * ROW_H;
                drawCompanionRow(pose, c, listX1 + 1, rowY, listX2 - 1, rowY + ROW_H, mouseX, mouseY);
            }
        }
        if (selectedUuid == null && list.size() > 0) {
            selectedUuid = NumenPanelNet.str(list.get(0).getAsJsonObject(), "uuid", null);
        }
        y = listY2;

        // 下面这几块同样先看放不放得下：窗口很矮时宁可少显示，也不要压在状态栏上。
        // 列表本身（上面那 4 行）是这个页面的主体，它一定放得下，不用判。
        y += 8;
        if (fits(y, FIELD_H, bottom, "召唤行")) {
            NumenUi.text(pose, this.font, "召唤", px + PAD, y + 4, colors.textSecondary());
            int f1x = px + PAD + 30;
            int btnW = 46;
            int f1w = pw - (f1x - px) - PAD - btnW - 4;
            NumenField f1 = summonName;
            f1.draw(pose, this.font, colors, f1x, y, f1w, FIELD_H, focused == f1, blink());
            hits.add(new Hit(f1x, y, f1x + f1w, y + FIELD_H, "field:summon"));
            drawButton(pose, mouseX, mouseY, px + pw - PAD - btnW, y, btnW, "召唤", "btn:summon", false);
        }
        y += FIELD_H + 6;

        // 说话
        if (fits(y, FIELD_H, bottom, "说话行")) {
            NumenUi.text(pose, this.font, "说话", px + PAD, y + 4, colors.textSecondary());
            int f2x = px + PAD + 30;
            int btnW = 46;
            int f2w = pw - (f2x - px) - PAD - btnW - 4;
            NumenField f2 = tellText;
            f2.draw(pose, this.font, colors, f2x, y, f2w, FIELD_H, focused == f2, blink());
            hits.add(new Hit(f2x, y, f2x + f2w, y + FIELD_H, "field:tell"));
            drawButton(pose, mouseX, mouseY, px + pw - PAD - btnW, y, btnW, "发送", "btn:tell", false);
        }
        y += FIELD_H + 8;

        // 遣散
        boolean hasSel = selectedName() != null;
        if (fits(y, BTN_H, bottom, "遣散按钮")) {
            drawButton(pose, mouseX, mouseY, px + PAD, y, 60, "遣散", "btn:dismiss", !hasSel);
        }
    }

    private void drawCompanionRow(PoseStack pose, JsonObject c,
                                  int x1, int y1, int x2, int y2, int mouseX, int mouseY) {
        String uuid = NumenPanelNet.str(c, "uuid", "");
        boolean selected = uuid.equals(selectedUuid);
        boolean hover = NumenUi.inside(mouseX, mouseY, x1, y1, x2, y2);

        if (selected) {
            NumenUi.rect(pose, x1, y1, x2, y2, colors.selected());
        } else if (hover) {
            NumenUi.rect(pose, x1, y1, x2, y2, colors.hover());
        }

        String name = NumenPanelNet.str(c, "name", "?");
        int hp = NumenPanelNet.integer(c, "hp", 0);
        int maxHp = NumenPanelNet.integer(c, "maxHp", 20);
        int food = NumenPanelNet.integer(c, "food", 0);
        boolean alive = !c.has("alive") || c.get("alive").getAsBoolean();
        String activity = NumenPanelNet.str(c, "activity", "闲着");
        String owner = NumenPanelNet.str(c, "owner", "?");
        int cx = NumenPanelNet.integer(c, "x", 0);
        int cy = NumenPanelNet.integer(c, "y", 0);
        int cz = NumenPanelNet.integer(c, "z", 0);

        int ty = y1 + (ROW_H - 8) / 2;
        int nameW = 54;
        NumenUi.text(pose, this.font, NumenUi.clip(this.font, name, nameW), x1 + 5, ty,
                alive ? colors.textPrimary() : colors.textMuted());

        String coord = cx + "," + cy + "," + cz;
        NumenUi.text(pose, this.font, coord, x1 + 5 + nameW + 6, ty, colors.textMuted());

        // 血量条：一眼看出掉没掉血，比读数字快
        int barX = x2 - 96;
        int barW = 40;
        int barY = ty + 3;
        NumenUi.rect(pose, barX, barY, barX + barW, barY + 3, colors.inputBg());
        if (maxHp > 0) {
            int filled = Math.max(0, Math.min(barW, barW * hp / maxHp));
            int barColor = hp * 2 < maxHp ? colors.danger()
                    : (hp * 4 < maxHp * 3 ? colors.warning() : colors.success());
            NumenUi.rect(pose, barX, barY, barX + filled, barY + 3, barColor);
        }
        NumenUi.text(pose, this.font, hp + "/" + maxHp, barX - 30, ty, colors.textSecondary());

        // 状态：活着就报在干嘛，死了就报死
        String status = alive ? activity : "已死";
        int statusX = barX + barW + 6;
        NumenUi.text(pose, this.font,
                NumenUi.clip(this.font, status, x2 - statusX - 4), statusX, ty,
                alive ? colors.textSecondary() : colors.danger());

        // 悬停时把主人和饱食度放状态栏（列表一行塞不下）
        if (hover) {
            hoverAction = "row:" + uuid + "|" + name + "|主人 " + owner + " · 饱食 " + food;
        }
        hits.add(new Hit(x1, y1, x2, y2, "row:" + uuid));
    }

    // ---- 设置页 ----

    /** 设置页一行的步进：输入框 + 行距。 */
    private static final int SET_ROW_H = FIELD_H + 2;

    private void drawSettings(PoseStack pose, int top, int bottom, int mouseX, int mouseY) {
        JsonObject cfg = NumenPanelNet.object(NumenPanelState.snapshot(), NumenPanelNet.KEY_CONFIG);
        JsonObject mcp = NumenPanelNet.object(NumenPanelState.snapshot(), NumenPanelNet.KEY_MCP);

        // 两列排：值短的四个（服务商 / 模型 / 密钥 / 主动性）并排，端点与人设各占整行——
        // 它们的值是 URL 和一整句话，挤在半行里读不了。
        // 这么排把六行压成四行（省 36 像素），是这一页能塞进正文区的关键。
        int full = pw - 2 * PAD;
        int colW = (full - 8) / 2;
        int col2 = px + PAD + colW + 8;

        int y = top + 4;
        settingRow(pose, y, "provider", cfgProvider, "服务商", px + PAD, colW);
        // 模型这一格右边带一个「获取模型」按钮：它和模型是一件事（拉回来就是为了选模型），
        // 所以并排放，而不是单独占一行——单独占一行就要多花 18 像素正文区的高度。
        int fetchW = 58;
        int modelW = Math.max(60, colW - fetchW - 4);
        settingRow(pose, y, "model", cfgModel, "模型", col2, modelW);
        drawModelFetchButton(pose, mouseX, mouseY, col2 + modelW + 4, y, fetchW);
        y += SET_ROW_H;
        settingRow(pose, y, "baseUrl", cfgBaseUrl, "端点", px + PAD, full);
        y += SET_ROW_H;
        settingRow(pose, y, "apiKey", cfgApiKey, "密钥", px + PAD, colW);
        settingRow(pose, y, "initiative", cfgInitiative, "主动性", col2, colW);
        y += SET_ROW_H;
        settingRow(pose, y, "persona", cfgPersona, "人设", px + PAD, full);
        y += SET_ROW_H;

        y += 2;
        NumenUi.rect(pose, px + PAD, y, px + pw - PAD, y + 1, colors.divider());
        y += 5;

        drawButton(pose, mouseX, mouseY, px + PAD, y, 56, "保存", "btn:save", false);
        drawButton(pose, mouseX, mouseY, px + PAD + 60, y, 68, "重新载入", "btn:reload", false);

        String missing = cfg == null ? null : NumenPanelNet.str(cfg, "missing", null);
        if (missing != null) {
            NumenUi.text(pose, this.font,
                    NumenUi.clip(this.font, missing, full - 136),
                    px + PAD + 134, y + 4, colors.warning());
        }
        y += BTN_H + 6;

        y += 5;
        NumenUi.rect(pose, px + PAD, y, px + pw - PAD, y + 1, colors.divider());
        y += 5;

        // 外接大脑那一块。**每一块画之前都先看还够不够**：面板高度是被窗口高度卡住的，
        // 内容一旦超过正文区，按钮就会压到下面的状态栏上——看着重叠，而且点得到不该点的东西
        // （主题开关、状态栏）。放不下就整块不画，宁可不显示也不叠。
        boolean mcpOn = mcp != null && mcp.has("enabled") && mcp.get("enabled").getAsBoolean();
        if (fits(y, BTN_H, bottom, "MCP 标题行")) {
            NumenUi.text(pose, this.font, "外接大脑（MCP）", px + PAD, y + 4, colors.textSecondary());
            drawButton(pose, mouseX, mouseY, px + pw - PAD - 56, y, 56,
                    mcpOn ? "已开启" : "已关闭", "btn:mcp", false, mcpOn ? colors.success() : null);
        }
        y += BTN_H + 4;

        if (mcp != null && fits(y, BTN_H, bottom, "MCP 端点/令牌行")) {
            // 端点与令牌合成一行：左边一行小字，右边两个复制按钮并排。
            // 分成两行会多占 11 像素，而这一页正好缺这 11 像素。
            String endpoint = NumenPanelNet.str(mcp, "endpoint", "");
            String token = NumenPanelNet.str(mcp, "token", "");
            String masked = token.length() > 10 ? token.substring(0, 10) + "…" : token;
            String info = token.isEmpty() ? endpoint : endpoint + "    令牌 " + masked;

            int copyW = 52;
            int copyTokenX = px + pw - PAD - copyW;
            int copyEndX = copyTokenX - 4 - copyW;
            NumenUi.text(pose, this.font,
                    NumenUi.clip(this.font, info, copyEndX - 6 - (px + PAD)),
                    px + PAD, y + 4, colors.textMuted());
            drawButton(pose, mouseX, mouseY, copyEndX, y, copyW, "复制端点", "btn:copyEndpoint", false);
            drawButton(pose, mouseX, mouseY, copyTokenX, y, copyW, "复制令牌", "btn:copyToken", false);
        }
    }

    /** 「获取模型」按钮。三种状态各自有词：正在拉 / 拿到了几个 / 上次失败。 */
    private void drawModelFetchButton(PoseStack pose, int mouseX, int mouseY, int x, int y, int w) {
        JsonObject m = NumenPanelState.models();
        boolean pending = NumenPanelNet.integer(m, "pending", 0) != 0;
        String error = NumenPanelNet.str(m, "error", "");
        JsonArray list = modelList();

        String label;
        Integer accent = null;
        if (pending) {
            label = "获取中…";
        } else if (!error.isEmpty()) {
            label = "重试 " + list.size() + "个";   // 失败也算重试入口
            accent = colors.danger();
        } else if (list.size() > 0) {
            label = "重选 " + list.size() + "个";
            accent = colors.success();
        } else {
            label = "获取模型";
        }
        // 正在拉的时候禁用，避免连点堆出一串请求。
        drawButton(pose, mouseX, mouseY, x, y, w, label, "btn:fetchModels", pending, accent);
    }

    /** 服务端带回来的模型名清单。没有就空数组（永远不返回 null）。 */
    private JsonArray modelList() {
        JsonObject m = NumenPanelState.models();
        if (m.has("list") && m.get("list").isJsonArray()) {
            return m.getAsJsonArray("list");
        }
        return new JsonArray();
    }

    /**
     * 模型下拉：一个盖在设置页之上的浮层。
     *
     * <p>为什么是浮层：正文区高度被窗口卡死（240 高的窗口只有 174 像素），
     * 而 NVIDIA 实测能拉回 80 个模型。塞进正文区必然压到状态栏——
     * 那正是刚修掉的「按键重叠」。浮层不占正文区预算。
     *
     * <p>画在**最后**（状态栏之后），这样它盖住一切；点击也走 {@code hits} 表，
     * 但因为浮层的 hit 是最后加进去的，{@link #mouseClicked} 从前往后遍历会先命中下层。
     * 所以这里反着来：浮层自己先把点击吃掉——见 {@link #mouseClicked}。
     */
    private void drawModelPicker(PoseStack pose, int top, int bottom, int mouseX, int mouseY) {
        JsonArray list = modelList();
        if (!modelsOpen || list.size() == 0) {
            return;
        }
        String error = NumenPanelNet.str(NumenPanelState.models(), "error", "");

        int rowH = 13;
        int listX1 = px + PAD;
        int listX2 = px + pw - PAD;
        // 浮层最多占正文区剩下的高度，但至少给 4 行；再少就不如不开。
        int maxH = Math.max(4 * rowH, bottom - top - 8);
        int visible = Math.min(list.size(), Math.max(4, maxH / rowH));
        int listH = visible * rowH + 22;   // 22 = 标题行 + 底部提示行

        // 从正文区顶部开始，但如果下面放不下就往上收。
        int ly1 = top;
        if (ly1 + listH > bottom + 20) {
            ly1 = Math.max(top, bottom + 20 - listH);
        }
        int ly2 = ly1 + listH;

        // 先把下拉范围内的点击清掉：否则底下那些按钮的 hit 会先被 mouseClicked 命中。
        // 这是浮层唯一容易出错的地方——「看得见」和「点得到」必须一致。
        final int clearY1 = ly1;
        final int clearY2 = ly2;
        hits.removeIf(h -> h.y1() < clearY2 && h.y2() > clearY1);

        NumenUi.rect(pose, listX1, ly1, listX2, ly2, colors.panelBg());
        NumenUi.frame(pose, listX1, ly1, listX2, ly2, colors.accent());
        NumenUi.rect(pose, listX1 + 1, ly1 + 1, listX2 - 1, ly1 + 15, colors.sectionBg());

        String title = error.isEmpty()
                ? "模型 " + list.size() + " 个（滚轮翻页，点一下选用）"
                : "上次失败：" + error;
        NumenUi.text(pose, this.font,
                NumenUi.clip(this.font, title, listX2 - listX1 - 26),
                listX1 + 5, ly1 + 4, error.isEmpty() ? colors.textSecondary() : colors.danger());
        // 右上角一个关闭按钮——点空白处也能关，但有个明确的 X 更清楚。
        int closeX = listX2 - 16;
        boolean closeHover = NumenUi.inside(mouseX, mouseY, closeX, ly1 + 3, closeX + 12, ly1 + 15);
        if (closeHover) {
            NumenUi.rect(pose, closeX, ly1 + 3, closeX + 12, ly1 + 15, colors.danger());
        }
        NumenUi.textCentered(pose, this.font, "x", closeX + 6, ly1 + 5,
                closeHover ? 0xFFFFFFFF : colors.textSecondary());
        hits.add(new Hit(closeX, ly1 + 3, closeX + 12, ly1 + 15, "picker:close"));

        int maxScroll = Math.max(0, list.size() - visible);
        if (modelsScroll > maxScroll) {
            modelsScroll = maxScroll;
        }
        if (modelsScroll < 0) {
            modelsScroll = 0;
        }

        String current = cfgModel.value == null ? "" : cfgModel.value.trim();
        for (int i = 0; i < visible && i + modelsScroll < list.size(); i++) {
            String id = list.get(i + modelsScroll).getAsString();
            int ry = ly1 + 16 + i * rowH;
            boolean hover = NumenUi.inside(mouseX, mouseY, listX1 + 1, ry, listX2 - 1, ry + rowH);
            boolean isCurrent = id.equals(current);
            if (isCurrent) {
                NumenUi.rect(pose, listX1 + 1, ry, listX2 - 1, ry + rowH, colors.selected());
            } else if (hover) {
                NumenUi.rect(pose, listX1 + 1, ry, listX2 - 1, ry + rowH, colors.hover());
            }
            NumenUi.text(pose, this.font,
                    NumenUi.clip(this.font, id, listX2 - listX1 - 14),
                    listX1 + 5, ry + 2, isCurrent ? colors.textPrimary() : colors.textSecondary());
            hits.add(new Hit(listX1 + 1, ry, listX2 - 1, ry + rowH, "pick:" + id));
        }

        // 滚动提示放底部：有得翻才显示，没得翻就别提。
        if (maxScroll > 0) {
            NumenUi.textRight(pose, this.font,
                    (modelsScroll + 1) + "~" + Math.min(list.size(), modelsScroll + visible)
                            + " / " + list.size(),
                    listX2 - 5, ly2 - 11, colors.textMuted());
        }
    }

    /**
     * 从 {@code y} 起还要画 {@code h} 像素，正文区放得下吗。
     *
     * <p>放不下时还会记一笔日志——不是给玩家看的，是给验证脚本看的：
     * 面板的布局只在「那一页真的被画出来」时才跑，而这又要求有人先按下 G。
     * {@code -Dnumen.panel=<页签>} 能让客户端自动开到那一页，脚本再去日志里找这条。
     * 没有这条日志，"布局修好了"就只能靠肉眼看截图——而肉眼看不出「这块其实没画」。
     */
    private static boolean fits(int y, int h, int bottom, String what) {
        if (y + h <= bottom) {
            return true;
        }
        NumenCore.info("[numen-panel] 放不下，未绘制：{}（需要到 {}，正文区只到 {}）", what, y + h, bottom);
        return false;
    }

    /** 一行设置：左边标签、右边输入框，画在给定的 x 与宽度上。返回下一行的 y。 */
    private int settingRow(PoseStack pose, int y, String key, NumenField field, String label, int x, int w) {
        NumenUi.text(pose, this.font, label, x, y + 4, colors.textSecondary());
        int fx = x + 44;
        int fw = w - 44;
        field.draw(pose, this.font, colors, fx, y, fw, FIELD_H, focused == field, blink());
        hits.add(new Hit(fx, y, fx + fw, y + FIELD_H, "field:" + key));
        return y + SET_ROW_H;
    }

    // ---- 底部状态栏 ----

    private void drawStatus(PoseStack pose) {
        int y = py + ph - STATUS_H;
        NumenUi.rect(pose, px + 1, y, px + pw - 1, y + 1, colors.divider());
        int ty = y + 7;

        // 右下角一个小开关：深色 / 浅色。这是显示偏好，所以放最边上，不占正文。
        int btnW = 34;
        int btnX = px + pw - PAD - btnW;
        NumenUi.rect(pose, btnX, y + 4, btnX + btnW, y + 4 + BTN_H, colors.sectionBg());
        NumenUi.frame(pose, btnX, y + 4, btnX + btnW, y + 4 + BTN_H, colors.inputBorder());
        NumenUi.textCentered(pose, this.font, NumenClientPrefs.dark() ? "深色" : "浅色",
                btnX + btnW / 2, y + 8, colors.textSecondary());
        hits.add(new Hit(btnX, y + 4, btnX + btnW, y + 4 + BTN_H, "btn:theme"));

        int textRight = btnX - 6;

        if (hoverAction != null) {
            String[] parts = hoverAction.split("\\|");
            if (parts.length >= 3) {
                NumenUi.text(pose, this.font,
                        NumenUi.clip(this.font, parts[2], textRight - px - PAD), px + PAD, ty,
                        colors.textMuted());
                return;
            }
        }

        if (!NumenPanelState.hasData()) {
            NumenUi.text(pose, this.font, "正在连接服务端…", px + PAD, ty, colors.textMuted());
            return;
        }
        String note = NumenPanelState.note();
        if (note.isEmpty()) {
            long age = NumenPanelState.ageMs();
            note = age < 3000 ? "已连接" : "数据是 " + (age / 1000) + " 秒前的";
            NumenUi.text(pose, this.font, note, px + PAD, ty, colors.textMuted());
            return;
        }
        NumenUi.text(pose, this.font,
                NumenUi.clip(this.font, note, textRight - px - PAD), px + PAD, ty,
                NumenPanelState.lastOk() ? colors.success() : colors.danger());
    }

    // ---- 按钮 ----

    private void drawButton(PoseStack pose, int mouseX, int mouseY,
                            int x, int y, int w, String label, String action, boolean disabled) {
        drawButton(pose, mouseX, mouseY, x, y, w, label, action, disabled, null);
    }

    private void drawButton(PoseStack pose, int mouseX, int mouseY, int x, int y, int w,
                            String label, String action, boolean disabled, Integer accentOverride) {
        boolean hover = !disabled && NumenUi.inside(mouseX, mouseY, x, y, x + w, y + BTN_H);
        int bg = disabled ? colors.inputBg()
                : (hover ? colors.selected() : colors.sectionBg());
        NumenUi.rect(pose, x, y, x + w, y + BTN_H, bg);
        NumenUi.frame(pose, x, y, x + w, y + BTN_H,
                disabled ? colors.inputBorder() : colors.inputBorder());
        int fg = disabled ? colors.textMuted()
                : (accentOverride != null ? accentOverride : colors.textPrimary());
        NumenUi.textCentered(pose, this.font, label, x + w / 2, y + 4, fg);
        if (!disabled) {
            hits.add(new Hit(x, y, x + w, y + BTN_H, action));
        }
    }

    private static boolean blink() {
        return (System.currentTimeMillis() / 500L) % 2L == 0L;
    }

    // ================= 输入 =================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (Hit hit : hits) {
            if (NumenUi.inside(mouseX, mouseY, hit.x1(), hit.y1(), hit.x2(), hit.y2())) {
                // 点输入框：把光标放到点到的位置，而不是当作「按钮」处理
                if (hit.action().startsWith("field:")) {
                    NumenField f = fieldFor(hit.action().substring(6));
                    if (f != null) {
                        focused = f;
                        f.click((int) mouseX - hit.x1(), this.font);
                    }
                    return true;
                }
                onAction(hit.action());
                return true;
            }
        }
        focused = null;
        // 点到空白处：下拉开着就收掉（点外面关闭是浮层的常识）。
        // 注意这里**不是**「点空白关面板」——那样太容易误触。
        if (modelsOpen) {
            modelsOpen = false;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 下拉开着的时候，滚轮归下拉——它盖在最上面，滚的是它。
        if (modelsOpen && tab == Tab.SETTINGS && modelList().size() > 0) {
            modelsScroll -= (int) Math.signum(delta);
            return true;
        }
        if (tab == Tab.COMPANIONS) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // 下拉开着时，Esc 先收下拉而不是关面板——这是「Esc 退一层」的常识，
            // 不然想取消选模型却把整个面板关了。
            if (modelsOpen) {
                modelsOpen = false;
                return true;
            }
            this.onClose();
            return true;
        }
        if (focused != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                submitFocused();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                cycleFocus();
                return true;
            }
            if (focused.keyPressed(keyCode)) {
                return true;
            }
        }
        // 其余按键一律吃掉：面板开着的时候，按键不该漏到世界里去（走路、丢东西、开背包）。
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (focused != null) {
            return focused.charTyped(codePoint);
        }
        return false;
    }

    private void cycleFocus() {
        List<NumenField> order = tab == Tab.COMPANIONS
                ? List.of(summonName, tellText)
                : List.of(cfgProvider, cfgModel, cfgBaseUrl, cfgApiKey, cfgPersona, cfgInitiative);
        int i = order.indexOf(focused);
        focused = order.get((i + 1) % order.size());
        focused.focus();
    }

    private NumenField fieldFor(String key) {
        return switch (key) {
            case "summon" -> summonName;
            case "tell" -> tellText;
            case "provider" -> cfgProvider;
            case "model" -> cfgModel;
            case "baseUrl" -> cfgBaseUrl;
            case "apiKey" -> cfgApiKey;
            case "persona" -> cfgPersona;
            case "initiative" -> cfgInitiative;
            default -> null;
        };
    }

    private void submitFocused() {
        if (focused == summonName) {
            doSummon();
        } else if (focused == tellText) {
            doTell();
        } else {
            doSave();
        }
    }

    private void onAction(String action) {
        if (action.startsWith("row:")) {
            selectedUuid = action.substring(4);
            return;
        }
        if (action.startsWith("tab:")) {
            tab = action.endsWith("settings") ? Tab.SETTINGS : Tab.COMPANIONS;
            focused = null;
            // 换页时把下拉收掉：它属于设置页，跟着页签走才不会让人以为「列表没了」。
            modelsOpen = false;
            pullConfigIfNeeded();
            return;
        }
        // 下拉里点了一个模型：填进「模型」框，然后收起来。**不自动保存**——
        // 玩家可能想连着把服务商也改了再一起保存，自动保存会让中间状态落到磁盘上。
        if (action.startsWith("pick:")) {
            String picked = action.substring(5);
            String before = cfgModel.lastSeenValue();
            cfgModel.setValue(picked);
            // setValue 会顺手把「已见值」也更新掉，于是这一格看起来「没改过」，
            // 保存时会被跳过。这里把它按回改之前的值，让 dirty() 如实为真。
            cfgModel.restoreLastSeen(before);
            modelsOpen = false;
            return;
        }
        if ("picker:close".equals(action)) {
            modelsOpen = false;
            return;
        }
        switch (action) {
            case "close" -> this.onClose();
            case "btn:summon" -> doSummon();
            case "btn:tell" -> doTell();
            case "btn:dismiss" -> {
                String name = selectedName();
                if (name != null) {
                    NumenPanelState.dismiss(name);
                }
            }
            case "btn:save" -> doSave();
            case "btn:reload" -> {
                configPrimed = false;
                NumenPanelState.requestRefresh();
            }
            case "btn:fetchModels" -> toggleModelPicker();
            case "btn:mcp" -> {
                JsonObject mcp = NumenPanelNet.object(NumenPanelState.snapshot(), NumenPanelNet.KEY_MCP);
                boolean on = mcp != null && mcp.has("enabled") && mcp.get("enabled").getAsBoolean();
                NumenPanelState.setMcpEnabled(!on);
            }
            case "btn:copyEndpoint" -> copy(mcpString("endpoint"));
            case "btn:copyToken" -> copy(mcpString("token"));
            case "btn:theme" -> {
                NumenClientPrefs.toggle();
                colors = NumenClientPrefs.dark() ? NumenUi.DARK : NumenUi.LIGHT;
            }
            default -> { }
        }
    }

    /**
     * 「获取模型」按钮的行为：没拉过（或列表是空的）就去拉；已经有列表了就开/收下拉。
     *
     * <p>为什么不每次都重新拉：拉一次要几百毫秒到几秒，而玩家点这个按钮**多数时候**是想选一个
     * 之前拉到的模型。只有列表还空着（第一次，或上次失败）才值得再打一次网络。
     * 想强制刷新也有路：下拉关掉之后再点一次按钮（列表非空时它是「重选 N 个」，
     * 点开下拉、再在失败提示下点一次「重试」）。
     */
    private void toggleModelPicker() {
        JsonArray list = modelList();
        if (list.size() == 0) {
            // 当前填在面板里的值一起带上：玩家常常是「刚粘完 key → 立刻点获取模型」，
            // 这时候配置里还是旧的。服务端会优先用这里带的值。
            NumenPanelState.fetchModels(cfgProvider.value.trim(), cfgBaseUrl.value.trim(),
                    cfgApiKey.value);
            modelsOpen = true;
            modelsScroll = 0;
            return;
        }
        modelsOpen = !modelsOpen;
        if (modelsOpen) {
            modelsScroll = indexOfCurrentModel(list);
        }
    }

    /** 下拉打开时，滚到当前模型那一行——不然 80 个模型里找不到自己选的是哪个。 */
    private int indexOfCurrentModel(JsonArray list) {
        String current = cfgModel.value == null ? "" : cfgModel.value.trim();
        for (int i = 0; i < list.size(); i++) {
            if (current.equals(list.get(i).getAsString())) {
                return i;
            }
        }
        return 0;
    }

    private void doSummon() {
        String name = summonName.value.trim();
        if (name.isEmpty()) {
            return;
        }
        NumenPanelState.summon(name);
        summonName.setValue("");
    }

    private void doTell() {
        String name = selectedName();
        String text = tellText.value.trim();
        if (name == null || text.isEmpty()) {
            return;
        }
        NumenPanelState.tell(name, text);
        tellText.setValue("");
    }

    private void doSave() {
        if (cfgProvider.dirty()) {
            NumenPanelState.setConfig("provider", cfgProvider.value.trim());
            cfgProvider.markClean();
        }
        if (cfgModel.dirty()) {
            NumenPanelState.setConfig("model", cfgModel.value.trim());
            cfgModel.markClean();
        }
        if (cfgBaseUrl.dirty()) {
            NumenPanelState.setConfig("baseUrl", cfgBaseUrl.value.trim());
            cfgBaseUrl.markClean();
        }
        if (!cfgApiKey.isEmpty()) {
            NumenPanelState.setConfig("apiKey", cfgApiKey.value.trim());
            cfgApiKey.setValue("");
        }
        if (cfgPersona.dirty()) {
            NumenPanelState.setConfig("persona", cfgPersona.value);
            cfgPersona.markClean();
        }
        if (cfgInitiative.dirty()) {
            NumenPanelState.setConfig("initiative", cfgInitiative.value.trim());
            cfgInitiative.markClean();
        }
    }

    private void copy(String text) {
        if (text == null || text.isEmpty() || this.minecraft == null) {
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(text);
    }

    private String mcpString(String key) {
        JsonObject mcp = NumenPanelNet.object(NumenPanelState.snapshot(), NumenPanelNet.KEY_MCP);
        return mcp == null ? "" : NumenPanelNet.str(mcp, key, "");
    }

    // ================= 数据 =================

    private JsonArray companions() {
        JsonObject snap = NumenPanelState.snapshot();
        if (snap.has(NumenPanelNet.KEY_COMPANIONS) && snap.get(NumenPanelNet.KEY_COMPANIONS).isJsonArray()) {
            return snap.getAsJsonArray(NumenPanelNet.KEY_COMPANIONS);
        }
        return new JsonArray();
    }

    private String selectedName() {
        for (JsonElement e : companions()) {
            JsonObject c = e.getAsJsonObject();
            if (NumenPanelNet.str(c, "uuid", "").equals(selectedUuid)) {
                return NumenPanelNet.str(c, "name", null);
            }
        }
        return null;
    }

    /** 第一次拿到服务端的配置时，把值灌进输入框。之后不再覆盖——用户可能正在改。 */
    private void pullConfigIfNeeded() {
        if (configPrimed) {
            return;
        }
        JsonObject cfg = NumenPanelNet.object(NumenPanelState.snapshot(), NumenPanelNet.KEY_CONFIG);
        if (cfg == null) {
            return;
        }
        cfgProvider.setValue(NumenPanelNet.str(cfg, "provider", ""));
        cfgModel.setValue(NumenPanelNet.str(cfg, "model", ""));
        cfgBaseUrl.setValue(NumenPanelNet.str(cfg, "baseUrl", ""));
        cfgPersona.setValue(NumenPanelNet.str(cfg, "persona", ""));
        cfgInitiative.setValue(String.valueOf(NumenPanelNet.integer(cfg, "initiative", 6)));
        cfgApiKey.setValue("");
        configPrimed = true;
    }
}
