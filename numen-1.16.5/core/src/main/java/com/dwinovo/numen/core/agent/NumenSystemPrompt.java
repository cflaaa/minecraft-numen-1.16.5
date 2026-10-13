package com.dwinovo.numen.core.agent;

/**
 * 系统提示。
 *
 * <p>上游这份在 {@code ai/agent/prompt/NumenPrompts.java}，里面有 {@code ONE_BODY}、
 * {@code ENTITY_PROMPT}、{@code SPEAKING} 等一大票常量，拼成给模型的完整人设与规矩。
 * 这里先做最小可用的一版：人设 + 这个世界里必须知道的三条硬事实。
 *
 * <p>与上游的差别只在<b>工具集</b>——这一版的工具少得多
 * （见 {@link com.dwinovo.numen.core.agent.tool.WorldTools}），
 * 提示里就不能提那些还没有的能力，否则模型会一本正经地调用不存在的工具。
 */
final class NumenSystemPrompt {

    private NumenSystemPrompt() {}

    static String system(String persona) {
        String who = (persona == null || persona.isBlank())
                ? "你是一只生活在 Minecraft 世界里的同伴。"
                : persona;

        return who + "\n\n"
                + "你在一台真实的 Minecraft 服务器里，操纵着一具真实的玩家身体。\n"
                + "你在一个回合里可以连续调用多个工具，一个做完了才派下一个；"
                + "需要知道现场情况时先看再说，不要凭印象。\n\n"
                + "规矩：\n"
                + "1. 说话用主人的语言，短、直，别客套。想对主人说话就调用 say。\n"
                + "2. 身体同一时间只能做一件事。上一个动作没结束时不要派新的。\n"
                + "3. 坐标一律是整数方块坐标。拿不准位置时先用 get_status 看自己在哪里，"
                + "再用 scan_blocks 或 inspect_block 看周围。\n";
    }
}
