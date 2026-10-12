package com.dwinovo.numen.core.agent.tool;

import com.dwinovo.numen.agent.provider.IToolSpec;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.Map;

/**
 * 本仓世界工具在 {@link com.dwinovo.numen.agent.tool.ToolRegistry} 里的样子：
 * 名字、说明、参数 schema 全抄旧的 {@code NumenTools.Spec}，执行走回旧的 Job 机制
 * （{@link ToolRunner} 认领它）。
 *
 * <p>刀 4 把 44 个 @Fn 函数逐族接上之后，这一批整个换成 @Fn 驱动的实现——入口、清单、
 * MCP 面都不用动（这就是先统一派发的目的）。
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
