package com.dwinovo.numen.core.task;

import com.dwinovo.numen.agent.script.ErrorKind;

/**
 * 一件身体活交回的结果：成没成、它做了什么的实际账、值，失败时还有种类与能照抄的下一步。
 *
 * <p>照上游 {@code api/common/.../task/TaskResult.java} 搬过来。**唯一删掉的是
 * {@code stoppedBy(TaskRecord.StopCause)}**——那一个方法属于「任务记录」那一层，
 * 而本仓还没有搬 {@code TaskRecord}（它绑着权限层与 SDK，见 docs/PORTING.md）。
 * 等搬记录层时把它补回来即可。
 *
 * @param success 做成了没有
 * @param message 它做了什么的实际账
 * @param value   成功时交给程序的值，失败时失败时知道的东西；没有是 null
 * @param kind    失败的种类；成功是 null
 * @param hint    失败时能照抄的下一步；没有是 null
 */
public record TaskResult(boolean success, String message, Object value, ErrorKind kind, String hint) {

    public TaskResult {
        if (success == (kind != null)) {
            throw new IllegalArgumentException("a failed result names its kind, a successful one has none");
        }
    }

    /** 做成了，交回 {@code value}。 */
    public static TaskResult ok(String message, Object value) {
        return new TaskResult(true, message, value, null, null);
    }

    /** 做成了，不交回值。 */
    public static TaskResult ok(String message) {
        return ok(message, null);
    }

    /** 没做成，种类是 {@link ErrorKind#FAILED}。 */
    public static TaskResult fail(String message) {
        return fail(ErrorKind.FAILED, message, null, null);
    }

    /** 没做成，说清种类；{@code hint} 是能照抄的下一步（没有为 null）。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint) {
        return fail(kind, message, hint, null);
    }

    /** 没做成，说清种类，带上失败时知道的东西。 */
    public static TaskResult fail(ErrorKind kind, String message, String hint, Object data) {
        return new TaskResult(false, message, data, kind, hint);
    }

    /** 到了期限还没做完，带上做到哪的值。 */
    public static TaskResult timeout(String message, Object data) {
        return new TaskResult(false, message, data, ErrorKind.TIMEOUT, null);
    }

    public static TaskResult timeout(String message) {
        return timeout(message, null);
    }

    /** 被叫停了，带上做到哪的值。 */
    public static TaskResult cancelled(String message, Object data) {
        return new TaskResult(false, message, data, ErrorKind.INTERRUPTED, null);
    }

    public static TaskResult cancelled(String message) {
        return cancelled(message, null);
    }

    /** 到了期限。 */
    public boolean timedOut() {
        return kind == ErrorKind.TIMEOUT;
    }

    /** 被叫停了。 */
    public boolean interrupted() {
        return kind == ErrorKind.INTERRUPTED;
    }

    /**
     * 这次结局被 {@code cause} 叫停的版本：同一份结果，消息前挂上叫停的原因。
     *
     * <p>第一刀搬本类时 {@code TaskRecord} 还没到位，这个方法被暂时去掉；现在 {@code TaskRecord}
     * 随刀 1a 到位了，按上游补回（`UPSTREAM_COMPARISON.md` §2 注里记的那一处）。
     */
    public TaskResult stoppedBy(TaskRecord.StopCause cause) {
        return new TaskResult(success, cause.words() + " — " + message, value, kind, hint);
    }
}
