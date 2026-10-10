package com.dwinovo.numen.core.command;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.data.ModLanguageData;
import com.dwinovo.numen.core.entity.CompanionFactory;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.permission.ConsentAnswer;
import com.dwinovo.numen.core.permission.ConsentDesk;
import com.dwinovo.numen.core.permission.Mode;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.core.permission.PermissionStore;
import com.dwinovo.numen.core.permission.Rule;
import com.dwinovo.numen.core.permission.RuleSet;
import com.dwinovo.numen.core.permission.Verdict;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code /numen} 命令。
 *
 * <p>上游的玩家入口是按键（G 开面板、Y 快说一句）加图形界面；这一版先做命令行，
 * 因为它不需要客户端、单人多人一样能用，而且调试时看得见每一步。
 * 客户端面板与按键按 docs/PORTING.md 的阶段表在第二阶段做。
 *
 * <pre>
 *   /numen summon &lt;名字&gt;              召唤一只同伴
 *   /numen dismiss &lt;名字&gt;             遣散
 *   /numen list                        看看现在有谁
 *   /numen tell &lt;名字&gt; &lt;话&gt;           跟她说一句
 *   /numen status &lt;名字&gt;              看她此刻在干什么、端点通不通
 *   /numen config &lt;项&gt; &lt;值&gt;           改配置（apiKey / model / baseUrl / provider / persona / initiative）
 * </pre>
 */
public final class NumenCommand {

    /** 名字规则与上游一致：3~16 位字母数字下划线。 */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private NumenCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("numen")
                // 召唤/遣散能改世界，按 2 级权限（单人世界里就是「开了作弊」）。
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("summon")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> dismiss(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list")
                        .executes(NumenCommand::list))
                .then(Commands.literal("tell")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> tell(ctx,
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "text"))))))
                .then(Commands.literal("status")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("config")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(ctx -> config(ctx,
                                                StringArgumentType.getString(ctx, "key"),
                                                StringArgumentType.getString(ctx, "value"))))))
                // 权限层与征询答复（归位阶段 1 搬入）。命令本体照上游 entity/NumenCommands 的
                // permission/consent 段落搬；上游挂在 /numen 根下并按 FOR_PLAYERS 过滤，
                // 本仓的 /numen 根自带 requires(2)，沿用本仓现状（单人即开作弊）。
                .then(permissionCommand())
                .then(consentCommand()));
    }

    // ---- 权限（照上游 NumenCommands 搬，2026-10-11） ----

    /** {@code /numen permission mode <名字> [模式] | rules list|add|remove|reset}。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> permissionCommand() {
        var modeName = Commands.argument("name", StringArgumentType.word()).executes(ctx -> mode(ctx, null));
        for (Mode mode : Mode.values()) {
            modeName.then(Commands.literal(mode.name().toLowerCase(Locale.ROOT)).executes(ctx -> mode(ctx, mode)));
        }
        return Commands.literal("permission")
                .then(Commands.literal("mode").then(modeName))
                .then(Commands.literal("rules")
                        .then(Commands.literal("list").executes(NumenCommand::listRules))
                        .then(tableCommand("add", (literal, table) -> literal.then(
                                Commands.argument("rule", StringArgumentType.greedyString())
                                        .executes(ctx -> addRule(ctx, table)))))
                        .then(tableCommand("remove", (literal, table) -> literal.then(
                                Commands.argument("row", IntegerArgumentType.integer(1))
                                        .executes(ctx -> removeRule(ctx, table)))))
                        .then(Commands.literal("reset").executes(NumenCommand::resetRules)));
    }

    /** {@code <verb> <deny|ask|allow> …}:三张表各一个字面量分支,后面接什么由 {@code branch} 定。 */
    private interface TableBranch {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> attach(
                com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal, Verdict.Kind table);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> tableCommand(
            String verb, TableBranch branch) {
        var root = Commands.literal(verb);
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ASK, Verdict.Kind.ALLOW)) {
            root.then(branch.attach(Commands.literal(tableName(table)), table));
        }
        return root;
    }

    private static String tableName(Verdict.Kind table) {
        return table.name().toLowerCase(Locale.ROOT);
    }

    /** 看或设调用者名下一只在场同伴的模式。 */
    private static int mode(CommandContext<CommandSourceStack> ctx, Mode mode) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        NumenPlayer companion = null;
        for (ServerPlayer player : owner.getServer().getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer body && body.isOwnedByPlayer(owner.getUUID())
                    && body.getName().getString().equals(name)) {
                companion = body;
            }
        }
        if (companion == null) {
            fail(ctx, new TranslatableComponent(ModLanguageData.Keys.COMMAND_YOURS_NOT_HERE, name));
            return 0;
        }
        if (mode != null) {
            Permission.setMode(companion, mode);
        }
        String now = Permission.modeOf(companion).name().toLowerCase(Locale.ROOT);
        ctx.getSource().sendSuccess(new TranslatableComponent(
                mode == null ? ModLanguageData.Keys.COMMAND_MODE : ModLanguageData.Keys.COMMAND_MODE_SET,
                name, now), false);
        return 1;
    }

    private static int listRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        RuleSet mine = PermissionStore.of(owner.getServer(), owner.getUUID()).rules();
        MutableComponent text = new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULES_YOURS);
        appendLayer(text, mine, true);
        text.append("\n").append(new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULES_FACTORY));
        appendLayer(text, RuleSet.factory(), false);
        text.append("\n").append(new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULES_UNMATCHED));
        ctx.getSource().sendSuccess(text, false);
        return 1;
    }

    /** 一层规则的三张表:表名与规则行照命令里的写法,只有"没有"一词随主人的语言。 */
    private static void appendLayer(MutableComponent text, RuleSet layer, boolean numbered) {
        for (Verdict.Kind table : List.of(Verdict.Kind.DENY, Verdict.Kind.ALLOW, Verdict.Kind.ASK)) {
            List<Rule> rows = layer.table(table);
            text.append("\n  " + tableName(table) + ":");
            if (rows.isEmpty()) {
                text.append(" ").append(new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULES_NONE));
            }
            for (int i = 0; i < rows.size(); i++) {
                text.append("\n    " + (numbered ? (i + 1) + ". " : "- ") + rows.get(i));
            }
        }
    }

    /** 主人加一行规则。 */
    private static int addRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        Rule rule;
        try {
            rule = Rule.parse(StringArgumentType.getString(ctx, "rule"));
        } catch (IllegalArgumentException mistake) {
            fail(ctx, new TextComponent(mistake.getMessage()));
            return 0;
        }
        boolean added = PermissionStore.of(owner.getServer(), owner.getUUID()).add(table, rule);
        ctx.getSource().sendSuccess(new TranslatableComponent(
                added ? ModLanguageData.Keys.COMMAND_RULE_ADDED : ModLanguageData.Keys.COMMAND_RULE_EXISTS,
                tableName(table), rule.toString()), false);
        return added ? 1 : 0;
    }

    private static int removeRule(CommandContext<CommandSourceStack> ctx, Verdict.Kind table)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        int row = IntegerArgumentType.getInteger(ctx, "row");
        int size = store.rules().table(table).size();
        if (row > size) {
            fail(ctx, new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULE_NO_ROW, tableName(table),
                    row, size));
            return 0;
        }
        Rule removed = store.remove(table, row - 1);
        ctx.getSource().sendSuccess(new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULE_REMOVED,
                tableName(table), removed.toString()), false);
        return 1;
    }

    private static int resetRules(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        PermissionStore.of(owner.getServer(), owner.getUUID()).reset();
        ctx.getSource().sendSuccess(
                new TranslatableComponent(ModLanguageData.Keys.COMMAND_RULES_RESET), false);
        return 1;
    }

    // ---- 征询答复（照上游 NumenCommands 搬，2026-10-11） ----

    /** {@code /numen consent allow <id> | remember <id> | deny <id> [附言]}。附言只随拒绝。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> consentCommand() {
        return Commands.literal("consent")
                .then(Commands.literal("allow").then(consentId(ConsentAnswer.Decision.ALLOW_ONCE)))
                .then(Commands.literal("remember").then(consentId(ConsentAnswer.Decision.ALLOW_REMEMBER)))
                .then(Commands.literal("deny").then(consentId(ConsentAnswer.Decision.DENY)
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> consent(ctx, ConsentAnswer.Decision.DENY,
                                        StringArgumentType.getString(ctx, "note"))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Long> consentId(
            ConsentAnswer.Decision decision) {
        return Commands.argument("id", LongArgumentType.longArg(1)).executes(ctx -> consent(ctx, decision, ""));
    }

    private static int consent(CommandContext<CommandSourceStack> ctx, ConsentAnswer.Decision decision, String note)
            throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        long id = LongArgumentType.getLong(ctx, "id");
        NumenPlayer companion = ConsentDesk.pendingAt(owner.getServer(), id);
        ConsentDesk.Reply reply = companion == null ? ConsentDesk.Reply.NOT_PENDING
                : ConsentDesk.reply(owner, companion, id, decision, note);
        switch (reply) {
            case ANSWERED -> ctx.getSource().sendSuccess(new TranslatableComponent(
                    ModLanguageData.Keys.COMMAND_CONSENT_ANSWERED,
                    id, companion.getName(), decision.name().toLowerCase(Locale.ROOT)), false);
            case NOT_OWNER -> fail(ctx, new TranslatableComponent(
                    ModLanguageData.Keys.COMMAND_CONSENT_NOT_OWNER, id));
            case NOT_PENDING -> fail(ctx, new TranslatableComponent(
                    ModLanguageData.Keys.COMMAND_CONSENT_NOT_PENDING, id));
        }
        return reply == ConsentDesk.Reply.ANSWERED ? 1 : 0;
    }

    // ---- 各子命令 ----

    private static int summon(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        if (!NAME.matcher(name).matches()) {
            fail(ctx, "名字要 3~16 位，只能用字母、数字、下划线");
            return 0;
        }
        CommandSourceStack source = ctx.getSource();
        ServerPlayer owner = source.getPlayerOrException();

        if (Companions.findByName(source.getServer(), name) != null) {
            fail(ctx, "已经有一只叫 " + name + " 的同伴了");
            return 0;
        }
        NumenPlayer body = Companions.spawn(source.getServer(), owner, name);
        if (body == null) {
            fail(ctx, "召唤失败");
            return 0;
        }

        String missing = NumenConfig.get().missingReason();
        if (missing != null) {
            source.sendSuccess(new TextComponent(
                    "召唤了 " + name + "，但还不能干活：" + missing).withStyle(ChatFormatting.YELLOW), false);
        } else {
            source.sendSuccess(new TextComponent(
                    "召唤了 " + name + "。用 /numen tell " + name + " <话> 跟她说话。"), false);
        }
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx, String name) {
        if (Companions.dismiss(ctx.getSource().getServer(), name)) {
            ctx.getSource().sendSuccess(new TextComponent("遣散了 " + name), false);
            return 1;
        }
        fail(ctx, "没有叫 " + name + " 的同伴");
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<NumenPlayer> all = Companions.all(ctx.getSource().getServer());
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(new TextComponent("现在一只同伴都没有"), false);
            return 0;
        }
        StringBuilder out = new StringBuilder("现在有 " + all.size() + " 只同伴：");
        for (NumenPlayer body : all) {
            var pos = body.blockPosition();
            out.append("\n  ").append(body.getGameProfile().getName())
                    .append("  在 ").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ())
                    .append("  主人 ").append(body.ownerName())
                    .append(body.isAlive() ? "" : "  [已死]");
        }
        ctx.getSource().sendSuccess(new TextComponent(out.toString()), false);
        return 1;
    }

    private static int tell(CommandContext<CommandSourceStack> ctx, String name, String text) {
        NumenPlayer body = Companions.findByName(ctx.getSource().getServer(), name);
        if (body == null) {
            fail(ctx, "没有叫 " + name + " 的同伴");
            return 0;
        }
        CompanionBrain brain = Companions.brain(body);
        if (brain == null) {
            fail(ctx, "这只同伴没有大脑（可能正在重生）");
            return 0;
        }
        String missing = brain.unavailableReason();
        if (missing != null) {
            fail(ctx, missing);
            return 0;
        }
        brain.tell(text);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx, String name) {
        NumenPlayer body = Companions.findByName(ctx.getSource().getServer(), name);
        if (body == null) {
            fail(ctx, "没有叫 " + name + " 的同伴");
            return 0;
        }
        CompanionBrain brain = Companions.brain(body);
        var pos = body.blockPosition();
        StringBuilder out = new StringBuilder();
        out.append(name).append(" 在 ").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        out.append("\n  血量 ").append(Math.round(body.getHealth())).append('/').append(Math.round(body.getMaxHealth()));
        out.append("\n  饱食 ").append(body.getFoodData().getFoodLevel());
        out.append("\n  此刻 ").append(brain == null || brain.activity() == null ? "闲着" : brain.activity());
        out.append("\n  端点 ").append(NumenConfig.get().provider).append(" / ").append(NumenConfig.get().model);
        String missing = NumenConfig.get().missingReason();
        if (missing != null) {
            out.append("\n  ⚠ ").append(missing);
        }
        ctx.getSource().sendSuccess(new TextComponent(out.toString()), false);
        return 1;
    }

    /** 改一项配置。密钥这种敏感值只回报「已设置」，不回声。 */
    private static int config(CommandContext<CommandSourceStack> ctx, String key, String value) {
        NumenConfig cfg = NumenConfig.get();
        switch (key) {
            case "apiKey" -> cfg.apiKey = value;
            case "model" -> cfg.model = value;
            case "baseUrl" -> cfg.baseUrl = value;
            case "provider" -> cfg.provider = value;
            case "proxy" -> cfg.proxy = value;
            case "persona" -> cfg.persona = value;
            case "initiative" -> {
                try {
                    cfg.initiative = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    fail(ctx, "initiative 要 1~10 的整数");
                    return 0;
                }
            }
            default -> {
                fail(ctx, "不认识的配置项。可用的有：apiKey / model / baseUrl / provider / proxy / persona / initiative");
                return 0;
            }
        }
        NumenConfig.save();

        boolean secret = key.equals("apiKey");
        String shown = secret ? "（已设置，" + value.length() + " 个字符）" : value;
        ctx.getSource().sendSuccess(new TextComponent("已设置 " + key + " = " + shown), false);
        NumenCore.info("配置 {} 已更新", key);
        return 1;
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(new TextComponent(message).withStyle(ChatFormatting.RED));
    }

    /** 同 {@link #fail(CommandContext, String)}，收一条现成的组件（征询/权限命令用上游的语言键）。 */
    private static void fail(CommandContext<CommandSourceStack> ctx, net.minecraft.network.chat.Component message) {
        ctx.getSource().sendFailure(message);
    }

    /** 给别处引用的：名字规则，召唤前先自己校验一遍省得来回。 */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** 未使用：保留给将来的「按 UUID 找同伴」子命令。 */
    @SuppressWarnings("unused")
    private static NumenPlayer byUuid(CommandSourceStack source, java.util.UUID uuid) {
        return NumenPlayer.findByUuid(source.getServer(), uuid);
    }

    /** 未使用：保留给将来的 UUID 参数补全。 */
    @SuppressWarnings("unused")
    private static java.util.UUID offlineUuid(String name) {
        return CompanionFactory.offlineUuid(name);
    }
}
