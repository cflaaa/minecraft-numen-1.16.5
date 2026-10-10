package com.dwinovo.numen.core.command;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.config.NumenConfig;
import com.dwinovo.numen.core.entity.CompanionFactory;
import com.dwinovo.numen.core.entity.Companions;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
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
                                                StringArgumentType.getString(ctx, "value")))))));
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
