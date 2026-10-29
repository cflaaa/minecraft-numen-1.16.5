package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.task.chain.ForageDecisions.Action;
import static com.dwinovo.numen.core.task.chain.ForageDecisions.Gates;
import static com.dwinovo.numen.core.task.chain.ForageDecisions.Phase;
import static com.dwinovo.numen.core.task.chain.ForageDecisions.next;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 觅食状态机的五条回归（2026-10-30 工单）。门槛全在 {@code Gates} 里收拢，
 * 这里直接断言"这一拍该做什么"——{@code START_HUNT} 就是链会真的构造并 start
 * 狩猎任务（发起攻击），{@code REST} 就是没有任何攻击动作。
 */
class ForageDecisionsTest {

    /** 饿、背包空、有猎物、无危险、无占用——标准出发条件。 */
    private static Gates ready() {
        return new Gates(true, true, true, false, false, false);
    }

    @Test
    void fedCompanionNearPigMakesNoAttackMove() {
        // 饱食(hungry=false):即便猪就在旁边也不发起攻击——这是旧实现的初始误猎
        Gates fed = new Gates(false, true, true, false, false, false);
        assertEquals(Action.REST, next(Phase.IDLE, fed));
        // 背包里有吃的轮不到它(EatChain 的活)
        Gates hasFood = new Gates(true, false, true, false, false, false);
        assertEquals(Action.REST, next(Phase.IDLE, hasFood));
    }

    @Test
    void hungryWithPreyActuallyStartsTheHunt() {
        assertEquals(Action.START_HUNT, next(Phase.IDLE, ready()));
    }

    @Test
    void fullCycleRearms_forAnotherHungryRound() {
        // 旧实现卡死的场景:第一轮走完(IDLE),再次饥饿+有猎物时必须能重新出发
        Phase p = Phase.IDLE;
        assertEquals(Action.START_HUNT, next(p, ready()));          // 第一轮出发
        p = Phase.HUNT;
        assertEquals(Action.CONTINUE_HUNT, next(p, ready()));       // 打猎中:继续
        p = Phase.LOOT;
        assertEquals(Action.CONTINUE_LOOT, next(p, ready()));       // 猎毕:捡掉落
        p = Phase.IDLE;                                             // 捡完收场
        Gates hungryAgain = ready();
        assertEquals(Action.START_HUNT, next(p, hungryAgain));      // 再饿:再出发,不再是终态
    }

    @Test
    void noPreyNeverOccupiesTheScheduler() {
        Gates noPrey = new Gates(true, true, false, false, false, false);
        assertEquals(Action.REST, next(Phase.IDLE, noPrey));
    }

    @Test
    void dangerAndModelPauseYieldFirst() {
        // 身边有危险:让 MobDefense/Flee 先处理
        assertEquals(Action.REST, next(Phase.IDLE,
                new Gates(true, true, true, true, false, false)));
        // 模型派的 numen.fight.attack 在跑(按住自卫闸门)
        assertEquals(Action.REST, next(Phase.IDLE,
                new Gates(true, true, true, false, false, true)));
        // 模型派的觅食活占着(按住本 ID 闸门)
        assertEquals(Action.REST, next(Phase.IDLE,
                new Gates(true, true, true, false, true, false)));
    }

    @Test
    void midHuntAndMidLootKeepGoing_evenIfConditionsShift() {
        // 打猎/捡东西中"做完再说"——条件变了也不自己掐断,抢断是更高链的事
        assertEquals(Action.CONTINUE_HUNT, next(Phase.HUNT,
                new Gates(false, true, false, true, true, true)));
        assertEquals(Action.CONTINUE_LOOT, next(Phase.LOOT,
                new Gates(false, false, false, true, true, true)));
        // 且永不 REST(否则就是空转占位)
        assertNotEquals(Action.REST, next(Phase.HUNT, ready()));
        assertNotEquals(Action.REST, next(Phase.LOOT, ready()));
    }
}
