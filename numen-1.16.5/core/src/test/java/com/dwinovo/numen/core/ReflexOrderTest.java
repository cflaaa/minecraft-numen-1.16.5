package com.dwinovo.numen.core;

import com.dwinovo.numen.core.task.BrainChains;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本能链排序守卫。{@code NumenCore.registerReflexes()} 的注释引用了本测试（上游有、
 * 移植时一直缺席——现补上）。排序即宪法 §一的竞价序：
 * 摔落缓冲 > 换气 > 逃跑 > 自卫 > 自动进食 > 无食自救 > 脱困 > 睡觉（舒适级,最末）。
 */
class ReflexOrderTest {

    @BeforeAll
    static void registerOnce() {
        NumenCore.registerReflexes();
    }

    @Test
    void instinctsComeInConstitutionOrder() {
        assertEquals(List.of(10, 20, 25, 30, 35, 36, 50, 60), BrainChains.registeredOrders(),
                "本能链的注册号序就是身体竞价序,改动必须过这条");
    }

    @Test
    void sleepIsComfort_neverBeforeSurvivalChains() {
        List<Integer> orders = BrainChains.registeredOrders();
        int sleep = orders.indexOf(60);
        assertTrue(sleep > orders.indexOf(50), "睡觉必须排在脱困之后——卡住比缺觉要命");
        assertTrue(sleep > orders.indexOf(36), "睡觉必须排在觅食之后——饿比缺觉要命");
        assertTrue(sleep > orders.indexOf(30), "睡觉必须排在自卫之后——活下来才谈得上睡");
    }

    @Test
    void sleepIsRegistered_soNightWalkingIsReal() {
        // 2026-10-30 工单:旧版 SleepChain 只进了名册没进运行链,"晚上自动睡觉"从未运行过
        assertTrue(BrainChains.registeredOrders().contains(60),
                "SleepChain 必须在 BrainChains 里,否则夜间找床睡觉整条是死代码");
    }
}
