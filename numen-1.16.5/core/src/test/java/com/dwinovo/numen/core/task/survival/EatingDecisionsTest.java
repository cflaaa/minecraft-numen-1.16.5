package com.dwinovo.numen.core.task.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自动进食的三档判据（不碰 Minecraft，无头可测）。链把"有没有吃的/打不打架"排在前面，
 * 这里只管"急到哪一档、什么时候才动嘴"。
 */
class EatingDecisionsTest {

    @Test
    void nothingToEatOrFightingMeansNoBite() {
        assertEquals(EatingDecisions.Want.NONE, EatingDecisions.want(0, false, false));  // 没有吃的:求救归急件
        assertEquals(EatingDecisions.Want.NONE, EatingDecisions.want(0, true, true));    // 打架不吃
        assertEquals(EatingDecisions.Want.NONE, EatingDecisions.want(20, true, true));
    }

    @Test
    void starvationBitesNow() {
        assertEquals(EatingDecisions.Want.NOW, EatingDecisions.want(0, true, false));
    }

    @Test
    void sprintLostToleratesWalking() {
        // 原版 food > 6 才能疾跑:6 及以下"跑不动"档——空闲或纯走路时吃,不抢别的活
        assertEquals(EatingDecisions.Want.IDLE_OR_WALKING, EatingDecisions.want(6, true, false));
        assertEquals(EatingDecisions.Want.IDLE_OR_WALKING, EatingDecisions.want(1, true, false));
    }

    @Test
    void regenLostWaitsForFullIdle() {
        // 原版 food ≥ 18 才自然回血:17 及以下"不回血"档——只在她完全空闲时补一口
        assertEquals(EatingDecisions.Want.IDLE, EatingDecisions.want(17, true, false));
        assertEquals(EatingDecisions.Want.IDLE, EatingDecisions.want(7, true, false));
        // 18 恰好回到回血线:不吃
        assertEquals(EatingDecisions.Want.NONE, EatingDecisions.want(18, true, false));
        assertEquals(EatingDecisions.Want.NONE, EatingDecisions.want(20, true, false));
    }

    @Test
    void thresholdsAreVanillaOnes() {
        assertEquals(18, EatingDecisions.REGEN_AT);     // 原版自然回血线
        assertEquals(7, EatingDecisions.SPRINT_AT);     // food < 7 = 跑不动(原版 > 6 才能疾跑)
        assertEquals(0, EatingDecisions.STARVE_AT);     // 原版饥饿伤害线
    }
}
