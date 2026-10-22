package com.dwinovo.numen.core.agent;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.core.script.Modules;
import com.dwinovo.numen.core.sdk.ApiDocs;
import com.dwinovo.numen.core.task.reflex.ReflexRegistry;

import java.util.UUID;

/**
 * 系统提示的组装（上游 {@code SystemPromptComposer.compose} 的移植）：只放会话内稳定的那几层——
 * 人设、操作核心、技能表、API 索引(含模块)、本能名册、札记的规矩、说话规则——好让它成为
 * <b>字节级稳定的缓存前缀</b>。会变的东西不在这里：札记索引随注入的 user 消息进历史
 * （见 {@code MemoryPreamble}，经 {@code HostPort#injectionPreamble}）。
 *
 * <p>与上游的差别只在<b>没有群聊一节</b>：本仓没有 {@code <audience>}/旁听/Conversations，
 * CONVERSATION 那节不拼——提示里不提不存在的东西。各节取舍与适配见 {@link NumenPrompts} 的类注释。
 *
 * <p>纯 JVM：不碰 Minecraft，可无头单测。
 */
final class NumenSystemPrompt {

    private NumenSystemPrompt() {}

    /**
     * 组装一份完整的系统提示。
     *
     * @param persona  配置的人设正文；空着退到 {@link NumenPrompts#DEFAULT_PERSONA}
     *                 ——空着的人设槽会让她退回通用助手的腔调，所以最后一档是一个具体的性格
     * @param companion 这只同伴——她的模块目录({@code config/numen/lua/<她>/})决定 {@code <api>}
     *                  索引里模块那几行
     */
    static String system(String persona, UUID companion) {
        String base = (persona != null && !persona.isBlank())
                ? persona : NumenPrompts.DEFAULT_PERSONA;

        StringBuilder sb = new StringBuilder(12_000);
        // 人设层裹上 <persona>，与后面不可变的操作核心(ENTITY_PROMPT)分得清清楚楚
        sb.append("<persona>\n").append(base.strip()).append("\n</persona>");
        sb.append(NumenPrompts.ENTITY_PROMPT);
        String skillsXml = SkillRegistry.instance().formatXml();
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        // API 索引：装了哪些组，一组一句，再是模块(内置的在前、她的在后)——她不必先 help 就知道去哪找。
        // 只随组与模块的增减变、按名字排好，是稳定前缀的一部分(她存一个模块，前缀变一次)；
        // 各组的动作与语法只在帮助里。上游同样每轮重取(EntityAgentLoop.Model#turnRequest)。
        String commands = ApiDocs.index(Modules.of(companion));
        if (!commands.isEmpty()) {
            sb.append("\n\n").append(commands);
        }
        // 本能名册：她得知道身体会自己做哪些事，不然既可能重复去做，也可能对"我怎么突然挪了二十格"
        // 毫无头绪。名册是纯注册表内容，本地就算得出来。模型只从这里读到它——工具描述里不再另带一份。
        String reflexes = ReflexRegistry.overview();
        if (!reflexes.isEmpty()) {
            sb.append("\n\n<instincts>\n").append(reflexes).append("\n</instincts>");
        }
        // 札记的规矩：她有记忆这件事、什么值得记。规矩不变所以在前缀里，内容会变所以在注入块里。
        sb.append(NumenPrompts.MEMORY);
        // 怎么说话压在最末尾：长度与语气离生成位置越近，越不容易在长对话里被冲淡(见 NumenPrompts)
        sb.append(NumenPrompts.SPEAKING);
        return sb.toString();
    }
}
