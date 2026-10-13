package com.dwinovo.numen.core.agent.tool;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.Map;

/**
 * 本仓世界工具在 {@link com.dwinovo.numen.agent.tool.ToolRegistry} 里的样子：
 * 名字、说明、参数 schema 来自 {@link WorldTools} 的表（MCP 契约），执行交给 {@link ToolRunner}
 * （认领后经 {@link WorldTools#run} 翻成上游 {@code numen.*} 的 @Fn 调用）。
 *
 * <p>刀 5 收尾后本类不再碰自写的动作层——动作全在上游任务族里。
 */
public final class WorldTool implements NumenTool {

    private final IToolSpec spec;

    public WorldTool(IToolSpec spec) {
        this.spec = spec;
    }

    @Override
    public String name() {
        return spec.name();
    }

    @Override
    public String description() {
        return spec.description();
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return spec.parameterSchema();
    }

    @Override
    public void invoke(ToolCall call) {
        ToolRunner.runWorldTool(call);
    }
}
