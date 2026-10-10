package com.dwinovo.numen.core.task;

/**
 * 一件身体活的生死状态。
 *
 * <p>照上游 {@code api/common/.../task/TaskState.java} 逐字搬过来（那里没有 MC 依赖）。
 *
 * <p>状态是刻驱动、单向的：记录从 {@link #PENDING} 起，被调度器挑中时翻成 {@link #RUNNING}，
 * 跑它的任务走到终态（或期限到了、被人叫停）之后落在四个终态之一。
 *
 * <p>引擎只会看见终态，所以调度器之外的调用方不必显式处理 {@code PENDING} / {@code RUNNING}。
 */
public enum TaskState {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMEOUT,
    CANCELLED;

    public boolean isTerminal() {
        return this != PENDING && this != RUNNING;
    }
}
