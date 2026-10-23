package com.dwinovo.numen.core.chat;

import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;

/**
 * 同伴旁白：把她「思考 / 在做 / 完成」讲进<b>原版聊天栏</b>，让坐在客户端前的玩家看得见
 * 外接大脑（MCP agent）驱动她的全过程——这正是"客户端直接对话"体验的另一半：话能说进去，
 * 她那边的动静也得看得见（#0027，用户定的客户端版需求）。
 *
 * <p>只动嘴不动手：这里不改变任何行为，纯粹是播报。三类各有各的出处，都在服务端主线程上：
 * <ul>
 *   <li>{@link #thinking}——外脑派了一次工具（{@code McpActuator.invoke}），摘要取 Lua 代码的第一行
 *       （外脑写程序时的注释就是它的意图）；</li>
 *   <li>{@link #doing}——一件占身体的活开跑（{@code TaskSlot#put}，受理那一刻）；</li>
 *   <li>{@link #done}——一件占身体的活收场（{@code TaskSlot#settle}），带用时。</li>
 * </ul>
 *
 * <p>格式刻意压成一行灰字（{@code §8[§7名字§8] §8(思考) …}）：和真人聊天（白色、{@code <名字>} 前缀）
 * 一眼就能区分——旁白是"戏台边的字幕"，不该抢主角的词。用 SYSTEM 频道而不是 CHAT：
 * 系统消息不进服务器的聊天日志统计，也不带玩家前缀。
 *
 * <p>无头单测里 {@code server} 是 null：全部静默跳过，不炸不刷。
 */
public final class CompanionNarrator {

    private CompanionNarrator() {}

    /** 外脑（MCP agent）派了一次工具——把它「想说的话」讲出来。 */
    public static void thinking(MinecraftServer server, String companion, String summary) {
        broadcast(server, companion, "思考", summary);
    }

    /** 一件占身体的活开跑：她此刻在干什么。 */
    public static void doing(MinecraftServer server, String companion, String what) {
        broadcast(server, companion, "在做", what);
    }

    /** 一件占身体的活收场，带用时（秒）。 */
    public static void done(MinecraftServer server, String companion, String what) {
        broadcast(server, companion, "完成", what);
    }

    private static void broadcast(MinecraftServer server, String companion, String tag, String text) {
        if (server == null || server.getPlayerList() == null) {
            return;   // 无头单测/未起世界：旁白没有听众，静默
        }
        if (text == null || text.isBlank()) {
            return;
        }
        Component line = new TextComponent(
                "§8[§7" + companion + "§8] §8(" + tag + ")§7 " + text);
        server.getPlayerList().broadcastMessage(line, ChatType.SYSTEM, Util.NIL_UUID);
    }
}
