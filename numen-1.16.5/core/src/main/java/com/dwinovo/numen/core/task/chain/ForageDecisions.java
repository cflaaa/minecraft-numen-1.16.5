package com.dwinovo.numen.core.task.chain;

/**
 * 觅食反射（{@link ForageChain}）的<b>纯状态机</b>——从链里拆出来所以能无头单测（不碰 Minecraft）。
 *
 * <h2>修的是什么（2026-10-30 工单）</h2>
 * 旧实现把 {@code DONE} 当终态：第一次觅食收场后 phase 永远停在 DONE，全类没有任何回到
 * HUNT 的路径；而 {@code canRun()} 在"饿 + 有猎物"时仍对 DONE 返回 true，调度器把身体交给
 * 一条什么都不做的链——空转占位，再饿也永远不会再猎。另外初始 {@code phase=HUNT} 让
 * canRun 对所有非 DONE 直接 true，饱食的同伴一上线就可能开猎。
 *
 * <p>现在的三状态只有 <b>IDLE</b> 是空闲态：初始与每轮收场都回到 IDLE，再出发必须过
 * {@link #next(Phase, Gates)} 里的完整闸门（饿到线、背包无食、近处有猎物、身边无危险、
 * 模型没占用）；一旦进入 HUNT/LOOT 就按既有优先级做完（抢断由更高优先级的链按序完成，
 * 不是本链自己的事）。被调度选中时动作是真实的——{@code START_HUNT} 就是构造并 start
 * 狩猎任务，不再有"选中了却空转"的状态。
 */
public final class ForageDecisions {

    private ForageDecisions() {}

    /** 链的生命阶段。IDLE 是唯一空闲态：初始与每轮收场（猎毕/捡完/猎物消失）都回到这里。 */
    public enum Phase { IDLE, HUNT, LOOT }

    /**
     * 一轮觅食的门槛快照。字段与 {@code ForageChain} 的既有闸门一一对应：
     * 饿到线（food ≤ FORAGE_AT）、背包无食（{@code EatChain#pickFood} 为空）、
     * 近处有可猎物（过权限层的）、身边有危险（DANGER_RADIUS 内）、模型占用的两个本能闸门。
     */
    public record Gates(boolean hungry, boolean bagEmpty, boolean preyNear,
                        boolean dangerNear, boolean foragePaused, boolean defensePaused) {}

    /** 本 tick 该做的事。{@code START_HUNT} 就是"发起攻击"：链会构造并 start 狩猎任务。 */
    public enum Action { START_HUNT, CONTINUE_HUNT, CONTINUE_LOOT, REST }

    /**
     * 状态机唯一入口。
     *
     * <ul>
     *   <li>HUNT/LOOT：做完再说——中途不打断自己（危险由更高优先级的 Flee/MobDefense 按
     *       链序抢断，不需要本链在 canRun 里自己让）；</li>
     *   <li>IDLE：过闸门——模型占用、身边有危险都先让路；然后"饿到线 + 背包无食 +
     *       有猎物"才 {@code START_HUNT}，否则 {@code REST}（不占调度器）。</li>
     * </ul>
     */
    public static Action next(Phase phase, Gates g) {
        return switch (phase) {
            case HUNT -> Action.CONTINUE_HUNT;
            case LOOT -> Action.CONTINUE_LOOT;
            case IDLE -> {
                if (g.foragePaused() || g.defensePaused()) {
                    yield Action.REST;   // 模型派的架在打/觅食活占着:不抢
                }
                if (g.dangerNear()) {
                    yield Action.REST;   // 身边有危险:让 MobDefense/Flee 先处理,别拉去追猪
                }
                yield (g.hungry() && g.bagEmpty() && g.preyNear())
                        ? Action.START_HUNT : Action.REST;
            }
        };
    }
}
