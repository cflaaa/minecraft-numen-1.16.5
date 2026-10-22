package com.dwinovo.numen.core.agent;

import com.dwinovo.numen.core.script.Modules;
import com.dwinovo.numen.core.task.reflex.PolicyReflex;
import com.dwinovo.numen.core.task.reflex.ReflexRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示组装（上游 SystemPromptComposer 的移植）：各层的顺序与取舍。
 * 无头可测——组装是纯 JVM 的（{@code <api>} 索引在没登记 API 组的测试环境里为空，不拼）。
 */
class NumenSystemPromptTest {

    @TempDir
    Path dir;

    private UUID companion() {
        Modules.init(uuid -> dir);
        return UUID.randomUUID();
    }

    @Test
    void blankPersonaFallsBackToTheBuiltInOne() {
        String prompt = NumenSystemPrompt.system("  ", companion());
        assertTrue(prompt.startsWith("<persona>"), "人设层裹在 <persona> 里、放在最前");
        assertTrue(prompt.contains("easygoing companion"), "空人设退到内置默认人设");
    }

    @Test
    void givenPersonaWinsOverDefault() {
        String prompt = NumenSystemPrompt.system("你叫小七，性子急。", companion());
        assertTrue(prompt.contains("你叫小七"));
        assertFalse(prompt.contains("easygoing companion"));
    }

    @Test
    void carriesOperatingCoreInstinctsRosterMemoryAndSpeaking() {
        ReflexRegistry.register(new PolicyReflex("prompt_test_dummy", "会自动躲雨"));
        String prompt = NumenSystemPrompt.system(null, companion());

        assertTrue(prompt.contains("<operating_principles>"), "操作纪律");
        assertTrue(prompt.contains(NumenPrompts.ONE_BODY), "一具身体一件后台活的规矩拼进操作核心");
        assertTrue(prompt.contains("numen.work.dig"), "操作纪律提到的函数名在册");
        assertTrue(prompt.contains("<choosing_actions>"), "合成/熔炼的路由提示");

        assertTrue(prompt.contains("<instincts>"), "本能名册要进提示——她得知道身体会自己做什么");
        assertTrue(prompt.contains("你的身体有这些本能"), "名册的前言");
        assertTrue(prompt.contains("会自动躲雨"), "登记过的本能一行不少");

        assertTrue(prompt.contains("<memory_rules>"), "札记规矩");
        assertTrue(prompt.contains("<speaking>"), "说话规矩");
        assertTrue(prompt.contains("<examples>"), "说话的目标语气示例");
    }

    @Test
    void doesNotPromiseWhatThisPortDoesNotHave() {
        // 本仓没有群聊（<audience>/旁听/Conversations），也没有 <current_task> 注入——
        // 提示里提了不存在的东西，模型就会一本正经地引用它（NumenSystemPrompt 的旧注释自己强调过这条）
        String prompt = NumenSystemPrompt.system(null, companion());
        assertFalse(prompt.contains("<audience>"));
        assertFalse(prompt.contains("conversation_rules"));
        assertFalse(prompt.contains("<current_task>"));
    }
}
