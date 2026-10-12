package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.core.task.TaskState;

/**
 * 结构化「为什么失败」的归类，从寻路/放置基座（{@code PlayerNav}、{@code Interaction}、
 * {@code BlockDigger}）里穿出来，让反应式任务层能<b>按原因分支</b>，而不是去人话理由里做字符串匹配。
 *
 * <p>与 {@link TaskState} 不是一回事：{@code TaskState} 是任务的生命周期（跑着 / 终局）；
 * {@code FailureType} 是挂在一次 {@code FAILED} 上的诊断。给人看的 {@code failReason} 字符串照旧
 * 随行——枚举给代码用，字符串给模型看。
 *
 * <h2>恢复边界（分类为什么是这张形状）</h2>
 * 反应式层的第一戒律：恢复阶梯恢复的是<b>一个有界目标的执行</b>，绝不扩大目标的范围，也不自动去补
 * 前置条件。所以分类分成两种：
 * <ul>
 *   <li><b>梯内</b>（换个法子到达同一个有界目标是值得试的）：{@link #OCCLUDED}、{@link #BOXED_IN}、
 *       {@link #NO_PATH}、{@link #OUT_OF_REACH}、{@link #HAZARD}；</li>
 *   <li><b>踢回 LLM</b>（不做一个战略性决定就满足不了这个目标，而决定不许确定性层做）：
 *       {@link #NO_MATERIAL}、{@link #WRONG_TOOL}、{@link #TARGET_LOST}、{@link #MINED_OUT}
 *       ——缺的东西是去弄、是放宽搜索、还是收手，模型说了算。</li>
 * </ul>
 * 一级阶梯声明它 {@code handles} 哪些 {@code FailureType}；不认的原样漏到最后，带着这个原因终局放弃。
 */
public enum FailureType {
    /** 背包里没有可放/可用的方块或物品。前置条件——踢回 LLM。 */
    NO_MATERIAL,
    /** 没有空格可收（脱下来的穿戴、捡起来的战利品）。踢回 LLM：先丢还是先存是战略判断。 */
    NO_SPACE,
    /** 目标处/周围没有可贴靠的实心面，或原版根本不许那块方块站在那里（石头上放花、空中放火把）：
     * 那一步放住了也待不住。别处还有支撑面时算梯内；那一步本身承不住就踢回 LLM——改计划或改地面才行。 */
    NO_SUPPORT,
    /** 目标格里站着一个活的/挡建筑的实体——它不动，原版每次右键都拒绝。踢回 LLM：等、引开、换一格
     *  是战略判断；换姿势或挖都不管用。 */
    ENTITY_BLOCKED,
    /** 看不到那个面/那块——视线被墙或别的遮挡物围住了。梯内。 */
    OCCLUDED,
    /** 身体自己挪不开 / 重规划预算里没活下一条路。梯内。 */
    BOXED_IN,
    /** A* 到目标什么都没返回。梯内：放宽一档目标（附近/相邻）再试。 */
    NO_PATH,
    /** 走路能改的范围内没有路：不改地形就没有，而有挖掘/搭桥/垒柱的一条是存在的，或者路上的格子超出
     *  她同意过的计划。原因里会说清要动几块、她的走路描述里哪一条允许（{@code costs = {dig = true,
     *  place = true}}，然后再来一次 {@code numen.route.plan}）。接近类任务当它梯内（放宽姿势也许仍能绕开）；
     *  路线步行不在它上面放宽任何东西——要不要多给是 LLM 的一句话。 */
    TERRAIN_BLOCKED,
    /** 始终没进到目标的交互距离内。梯内：换个站位。 */
    OUT_OF_REACH,
    /** 手上的家伙没法有效地采/打。前置条件——踢回 LLM。 */
    WRONG_TOOL,
    /** 实体/方块目标没了、死了，或移出了有界搜索。踢回 LLM。 */
    TARGET_LOST,
    /** 有界扫描半径内没有更多匹配的目标。踢回 LLM（放宽？收手？）。 */
    MINED_OUT,
    /** 权限层拦下了这次动作（主人的规则、observe 模式，或没给到的征询）。踢回 LLM：模型不许绕着走——主人说了算。 */
    REFUSED,
    /** 方块放进去了但没待住，或者世界没收下这次写入：别的模组取消了放置、游戏拒绝了写入、或者活干完之后
     *  有东西在把它不断拆掉。不是权限层（{@link #REFUSED}），不是那一步承不住（{@link #NO_SUPPORT}），
     *  不是身体挡路（{@link #ENTITY_BLOCKED}）——那几条各有各的补救。踢回 LLM：原样再试一次什么都不会变，
     *  得先找到是谁在拆。 */
    NOT_KEPT,
    /** 流体/岩浆/虚空危险挡住了安全执行。梯内：绕开，绕不开就放弃。 */
    HAZARD,
    /** 被抢先或取消（主人按停、死亡）。不是真失败——终局收尾。 */
    INTERRUPTED,
    /** 期限预算用完了。 */
    TIMED_OUT,
    /** 记录类型没有登记过的执行器。 */
    UNSUPPORTED,
    /**
     * 任务自己抛了异常——<b>我们的 bug，不是世界的问题</b>。
     *
     * <p>单列一档而不是并进 {@link #UNKNOWN}：那一档的意思是「原因没归类」，而这一档的意思是
     * 「这里本不该发生」。两者对模型的含义完全不同——前者可以换个法子再试，后者再试多少遍都一样，
     * 而且该被人看见。
     */
    INTERNAL,
    /** 原因未归类。 */
    UNKNOWN;

    /**
     * 这一类失败交给脚本时是哪一种错误值（{@link ErrorKind}）：脚本按它分支，所以只分到她下一步做法不同的那几种——路不通、
     * 够不着、被拒、东西没了、缺料、被叫停、超时；其余是 {@link ErrorKind#FAILED}。
     */
    public ErrorKind kind() {
        return switch (this) {
            case NO_PATH, BOXED_IN, TERRAIN_BLOCKED, HAZARD -> ErrorKind.NO_PATH;
            case OUT_OF_REACH, OCCLUDED -> ErrorKind.OUT_OF_REACH;
            case REFUSED -> ErrorKind.DENIED;
            case TARGET_LOST, MINED_OUT -> ErrorKind.NOT_FOUND;
            case NO_MATERIAL -> ErrorKind.NO_MATERIAL;
            case INTERRUPTED -> ErrorKind.INTERRUPTED;
            case TIMED_OUT -> ErrorKind.TIMEOUT;
            case NO_SPACE, NO_SUPPORT, ENTITY_BLOCKED, NOT_KEPT, WRONG_TOOL, UNSUPPORTED, INTERNAL, UNKNOWN ->
                    ErrorKind.FAILED;
        };
    }
}
