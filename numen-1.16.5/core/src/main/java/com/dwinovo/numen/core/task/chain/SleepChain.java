package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.reflex.Reflex;
import com.dwinovo.numen.core.task.reflex.ReflexRegistry;
import com.dwinovo.numen.core.task.Task;
import com.dwinovo.numen.core.task.TaskState;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.event.NumenEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

/**
 * 晚上自动找床睡觉的反射链。
 * <p>
 * 触发条件：世界是夜晚、同伴没在睡、附近有空床。
 * 行为：扫床 -> 走到床边 -> 上床确认睡着 -> 天亮自动醒来。
 * 只要还在睡就持续 RUNNING；醒来（或被拒/没床）返回 RUNNING 但重置状态，
 * 下一轮 canRun 会返回 false，交回认知层。
 */
public final class SleepChain implements Task, Reflex {

    private static final int SCAN_RADIUS = 16;
    private static final double ARRIVE_DIST = 2.0; // 够得着床的距离
    private static final int MAX_STUCK_TICKS = 200; // 10秒卡住放弃

    private BlockPos targetBedHead;
    private boolean sleepingConfirmed;
    private int stuckTicks;
    private int scanCooldown;

    public SleepChain() {}

    @Override
    public boolean canRun(NumenPlayer companion) {
        scanCooldown = Math.max(0, scanCooldown - 1);

        // 1. 世界是晚上
        if (!isNight(companion)) {
            reset();
            return false;
        }

        // 2. 已经在睡了 -> 保持 RUNNING 直到天亮
        if (companion.isSleeping()) {
            sleepingConfirmed = true;
            targetBedHead = companion.getSleepingPos().orElse(null);
            return true;
        }

        // 3. 在做别的任务（走路、战斗、挖矿...）不抢
        Trip trip = Trip.current(companion);
        if (trip != null && trip.status() == Trip.Status.RUNNING) {
            return false;
        }

        // 4. 水里、岩浆里、掉落中不睡
        if (companion.isInWater() || companion.isInLava() || !companion.onGround()) {
            return false;
        }

        // 5. 扫描附近有没有空床（限频）
        if (scanCooldown == 0) {
            Optional<BlockPos> bed = ScanOps.findNearestBed(companion, SCAN_RADIUS);
            scanCooldown = 20; // 1秒扫一次
            if (bed.isEmpty()) {
                return false;
            }
            targetBedHead = bed.get();
        }

        if (targetBedHead == null) {
            return false;
        }

        sleepingConfirmed = false;
        stuckTicks = 0;
        return true;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        // 已经在睡 -> 等天亮
        if (sleepingConfirmed || companion.isSleeping()) {
            sleepingConfirmed = true;
            if (isNight(companion)) {
                return TaskState.RUNNING; // 继续睡
            }
            // 天亮了 -> 醒来，重置状态，下轮 canRun=false 交回认知层
            reset();
            return TaskState.RUNNING;
        }

        // 走向床
        if (targetBedHead != null) {
            double dist = companion.position().distanceTo(Vec3.atCenterOf(targetBedHead));
            if (dist <= ARRIVE_DIST) {
                // 够得着 -> 上床
                try {
                    SleepOps.Slept result = SleepOps.sleep(targetBedHead, companion);
                    if (result.sleeping()) {
                        sleepingConfirmed = true;
                        NumenEvents.reflex(companion, this, "上床睡着了 (" + targetBedHead + ")");
                        return TaskState.RUNNING;
                    }
                } catch (Exception e) {
                    // 床被占/白天/太远 -> 标记失败，下轮重扫
                    targetBedHead = null;
                    scanCooldown = 0;
                    stuckTicks = 0;
                    return TaskState.RUNNING;
                }
            } else {
                // 还没到 -> 让 move.to 接管（反射只负责"想睡觉并锁定床"）
                // 这里返回 RUNNING 但不做动作，等待外部 move.to 走过来
                // 如果卡住太久，放弃这张床
                if (stuckTicks++ > MAX_STUCK_TICKS) {
                    targetBedHead = null;
                    scanCooldown = 0;
                    stuckTicks = 0;
                }
            }
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        reset();
    }

    private void reset() {
        sleepingConfirmed = false;
        targetBedHead = null;
        stuckTicks = 0;
        scanCooldown = 0;
    }

    @Override
    public String name() { return "sleep"; }

    @Override public String id() { return name(); }

    @Override public String describe() { return "晚上自动找空床上床睡觉，天亮自动醒来"; }

    // --- helpers ---

    private static boolean isNight(NumenPlayer companion) {
        return companion.level().isNight(); // 1.16.5 Level.isNight() = 时间在 13000-23000
    }
}
