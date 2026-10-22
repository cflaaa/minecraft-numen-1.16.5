package com.dwinovo.numen.core.task.survival;

/**
 * 自动进食反射（{@code auto_eat}）的<b>纯判据</b>——"现在想吃吗、急到什么程度"，从 {@code EatChain}
 * 里拆出来所以能无头单测（不碰 Minecraft）。三条线全部取自原版的生存规则：
 *
 * <ul>
 *   <li>{@link #REGEN_AT} = 18：原版 food ≥ 18 才自然回血——掉破这条线是<b>不回血</b>；</li>
 *   <li>{@link #SPRINT_AT} = 7：原版 food > 6（即 ≥ 7）才能疾跑——掉破这条线是<b>跑不动</b>；</li>
 *   <li>{@link #STARVE_AT} = 0：原版 food = 0 开始挨饿伤害（easy 难度扣到 10 血为底）——这条线以下是<b>正在挨饿</b>。</li>
 * </ul>
 *
 * <h2>三档策略（2026-10-21 定，见 issues/0021）</h2>
 * <pre>
 * 不回血（food ≤ 17）：身体<b>完全空闲</b>时补一口        —— 不打断任何活
 * 跑不动 （food ≤ 6） ：空闲、<b>或只是在走路</b>时补一口 —— 原版边走边吃本来就行：
 *                       让位一小口（约 1.6s），吃完走路自动续上（PREEMPTED 不丢状态）
 * 挨饿  （food = 0）  ：<b>立刻吃</b>                    —— 打断一切非战斗的活（战斗中不吃）
 * </pre>
 * 让位也不丢活：被它顶掉的任务只是 PREEMPTED（{@code TaskSlot#loseBody}），吃完自己接着干，
 * 期限还会被 freeze 往后推。
 *
 * <p>与饥饿<b>急件</b>（{@code EventTypes#HUNGRY}，food < 6 发）的分工：急件是"她没有吃的"时的求救线；
 * 有吃的这一档由本反射自己处理，不再打断在跑的程序（见 {@code CompanionTickDispatcher} 的判据）。
 */
public final class EatingDecisions {

    private EatingDecisions() {}

    /** 原版 food ≥ 18 才自然回血——掉破这条线就"不回血"。 */
    public static final int REGEN_AT = 18;

    /** 原版 food > 6 才能疾跑——掉破这条线（≤ 6）就"跑不动"。 */
    public static final int SPRINT_AT = 7;

    /** 原版 food = 0 开始饥饿伤害——这条线以下是"正在挨饿"。 */
    public static final int STARVE_AT = 0;

    /** 想不想吃、急到什么程度。 */
    public enum Want {
        /** 不吃。 */
        NONE,
        /** 吃，但不抢身体：等她把手上这件活干完（两个槽都空了）再吃。 */
        IDLE,
        /** 吃，不抢身体——但"只是在走路"不算占用：让位一小口、吃完走路自动续上。 */
        IDLE_OR_WALKING,
        /** 立刻吃：打断手上任何非战斗的活（战斗由反射序与 mob_defense 的占用挡着）。 */
        NOW
    }

    /**
     * 现在想吃吗、急到哪一档。
     *
     * @param food     饱食度 0..20
     * @param hasFood  背包里有可吃的吗（没吃的轮不到她：那是 hungry 急件去找主人/大脑的事）
     * @param fighting 正在打架吗（打架不吃：先活下来，再吃饭）
     */
    public static Want want(int food, boolean hasFood, boolean fighting) {
        if (!hasFood || fighting) {
            return Want.NONE;
        }
        if (food <= STARVE_AT) {
            return Want.NOW;
        }
        if (food < SPRINT_AT) {
            return Want.IDLE_OR_WALKING;
        }
        if (food < REGEN_AT) {
            return Want.IDLE;
        }
        return Want.NONE;
    }
}
