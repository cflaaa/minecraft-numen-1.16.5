package com.dwinovo.numen.core.task;

/**
 * 受理之前的准备：这件活此刻真能开始吗。任务交出它（{@link Task#prepare}），派发方在服务端线程上每刻问一次
 * {@link #poll}，直到给出结论——就绪才受理、才换进槽里、才回活的编号；不成就当场回错误结果，
 * 她手上原来那件活一点不受影响。
 *
 * <p>照上游 {@code api/common/.../task/Preparation.java} 逐字搬过来（那里没有 MC 依赖）。
 *
 * <p>判的顺序：参数的写法在处理函数里就判过了（写错当场提醒）；这里判世界事实（东西在不在、身上有没有、
 * 够不够得着），再做规划。准备不占身体、不碰世界：身体这时可能还在干上一件活。
 *
 * <p>等主人点头不在这里判：计划里有要问主人的格照样就绪，运行中再问。受理之后世界变了、主人拒绝、
 * 中途卡住，照旧走终态。
 */
public interface Preparation {

    /** 有结论就交出，还在判是 null。在服务端线程上每刻调一次，交出结论之后不再调。 */
    Readiness poll();

    /** 不要了（被新派的活顶替、主人按了停止、身体离开世界）：在飞的搜索作废。交出结论之后不会再调。 */
    default void cancel() {
    }

    /** 当场就能开始。 */
    Preparation READY = () -> Readiness.READY;

    /** 当场就知道开始不了：{@code why} 就是这次调用的错误结果。 */
    static Preparation refused(TaskResult why) {
        Readiness readiness = Readiness.refused(why);
        return () -> readiness;
    }

    /**
     * 准备的结论。
     *
     * @param refusal 开始不了时这次调用的错误结果；能开始时为 null
     */
    record Readiness(TaskResult refusal) {

        /** 能开始。 */
        public static final Readiness READY = new Readiness(null);

        public static Readiness refused(TaskResult why) {
            return new Readiness(why);
        }

        public boolean ready() {
            return refusal == null;
        }
    }
}
