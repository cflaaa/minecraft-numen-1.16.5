package com.dwinovo.numen.core.task.reflex;

import com.dwinovo.numen.core.task.reflex.Reflex;
import com.dwinovo.numen.core.task.reflex.ReflexRegistry;

import com.dwinovo.numen.core.task.chain.MLGChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.core.task.chain.UnstuckChain;

/**
 * numen-core's reflex roster: the survival chains, which implement
 * {@link Reflex} themselves, registered once at {@code Constants.init}. The chain instances enlisted here
 * are roster representatives only (id/describe are constants); the live,
 * per-companion chain instances stay inside each {@code CompanionBrain}.
 *
 * <p>上游五条(MLG/换气/逃跑/自卫/脱困)之外,本仓加回:
 * {@code EatChain}(自动进食,#0021)、{@code SleepChain}(晚上自动睡觉)。
 * 上游删了 EatChain("饿是主人该知道的事")，但无人值守的部署形态没有主人盯着喂——
 * 饿到/困到自己处理,免得急件一遍遍打断在跑的程序。
 */
public final class CoreReflexes {

    private CoreReflexes() {}

    public static void registerAll() {
        ReflexRegistry.register(new MLGChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.BreathChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.FleeChain());
        ReflexRegistry.register(new MobDefenseChain());
        ReflexRegistry.register(new UnstuckChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.EatChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.ForageChain());
        ReflexRegistry.register(new com.dwinovo.numen.core.task.chain.SleepChain());
    }
}
