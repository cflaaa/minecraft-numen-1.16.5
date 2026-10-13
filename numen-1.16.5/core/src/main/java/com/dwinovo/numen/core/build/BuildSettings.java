package com.dwinovo.numen.core.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的通配忽略规则连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * 建造对账的服主参数:一格现有的方块什么时候算已经建对了({@link BuildValidity} 读它们)。字段 public 可变,运行期改写即生效;
 * 全局单例 {@link #get()}。方块与属性清单经由懒加载 getter 暴露,首次访问才触碰注册表。
 */

public final class BuildSettings {
    private static final BuildSettings INSTANCE = new BuildSettings();
    public boolean buildIgnoreExisting = false;
    public boolean buildIgnoreDirection = false;
    public boolean okIfWater = false;
    private List<Block> buildIgnoreBlocks;
    private List<Block> okIfAir;
    private List<String> buildIgnoreProperties;
    private Map<Block, List<Block>> buildValidSubstitutes;

    public static BuildSettings get() {
        return INSTANCE;
    }

    private BuildSettings() {
    }

    public List<Block> buildIgnoreBlocks() {
        if (this.buildIgnoreBlocks == null) {
            this.buildIgnoreBlocks = new ArrayList<Block>();
        }
        return this.buildIgnoreBlocks;
    }

    public List<Block> okIfAir() {
        if (this.okIfAir == null) {
            this.okIfAir = new ArrayList<Block>();
        }
        return this.okIfAir;
    }

    public List<String> buildIgnoreProperties() {
        if (this.buildIgnoreProperties == null) {
            this.buildIgnoreProperties = new ArrayList<String>();
        }
        return this.buildIgnoreProperties;
    }

    public Map<Block, List<Block>> buildValidSubstitutes() {
        if (this.buildValidSubstitutes == null) {
            this.buildValidSubstitutes = new HashMap<Block, List<Block>>();
        }
        return this.buildValidSubstitutes;
    }
}

