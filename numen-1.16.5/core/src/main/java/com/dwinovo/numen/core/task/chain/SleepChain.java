package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.event.NumenEvents;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.Task;
import com.dwinovo.numen.core.task.TaskState;
import com.dwinovo.numen.core.task.reflex.Reflex;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.core.tools.SleepOps;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 晚上自动找床睡觉的反射链（2026-10-30 重做——旧版从未接入运行链，接入了也不会走路）。
 *
 * <h2>旧版的三处死症</h2>
 * <ol>
 *   <li><b>没接入运行链</b>：只在 {@code CoreReflexes.register()} 进了名册（提示词里看得到），
 *       {@code NumenCore.registerReflexes()} 的 BrainChains 里没有它——链体从未运行；</li>
 *   <li><b>卡住超时形同虚设</b>：{@code canRun()} 每次都把 stuckTicks 清零，调度又是先 canRun
 *       后 tick，计数永远到不了上限；</li>
 *   <li><b>不走路</b>：锁定远处的床后只返回 RUNNING，注释说"等外部 move.to 接管"——
 *       没有任何代码会替它发起,于是整夜占着身体站着。</li>
 * </ol>
 *
 * <h2>现在的行为</h2>
 * 夜里（且本维度可以睡床——下界/末地床会炸,绝不碰）扫 16 格内的空床，用
 * {@link Trip} 寻路走过去（{@code Goals.within} 半径 2.5 格；本链持有自己的 Trip,
 * canRun 只对"别人的 Trip"让位），到床边调 {@link SleepOps#sleep} 上床,天亮自动醒。
 * 走不到（FAILED/超预算）或上床被拒的床记入本夜黑名单,重扫时跳过——不空转、不重复撞墙。
 * 清晨 {@code reset()} 顺带清黑名单,新的一夜重新扫描。
 */
public final class SleepChain implements Task, Reflex {

    private static final int SCAN_RADIUS = 16;
    private static final double ARRIVE_DIST = 2.0;     // 够得着床的距离
    private static final long WALK_BUDGET_TICKS = 600L; // 走床预算 30s:16 格半径足够,超了记黑名单

    private BlockPos targetBedHead;
    private Trip walk;
    private long walkBudget;
    private boolean sleepingConfirmed;
    /** 重扫冷却用游戏时间锚(而非计数):被抢断后 canRun 可能几刻不被咨询,计数会漏走。 */
    private long scanBlockedUntil;
    /** 本夜放弃过的床（床头坐标 asLong）:走不到/上不去的不再选,清晨清空。 */
    private final Set<Long> unusableBeds = new HashSet<>();

    public SleepChain() {}

    @Override
    public boolean canRun(NumenPlayer companion) {
        // 0. 维度闸门:下界/末地床会爆炸,bedWorks=false 的维度绝不自发上床
        if (!companion.level().dimensionType().bedWorks()) {
            return false;
        }

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

        // 3. 在做别的任务（走路、战斗、挖矿...）不抢——但自己发起的走路不算"别的任务"
        Trip current = Trip.current(companion);
        if (current != null && current != walk && current.status() == Trip.Status.RUNNING) {
            return false;
        }

        // 4. 水里、岩浆里、掉落中不睡
        if (companion.isInWater() || companion.isInLava() || !companion.onGround()) {
            return false;
        }

        // 5. 限频扫描附近有没有空闲床（黑名单里的跳过）
        if (targetBedHead == null) {
            if (companion.level().getGameTime() < scanBlockedUntil) {
                return false;   // 冷却中(黑名单刚记过/刚失败):用游戏时间锚,抢断间隙也不漏
            }
            Optional<BlockPos> bed = ScanOps.findNearestBed(companion, SCAN_RADIUS);
            if (bed.isEmpty() || unusableBeds.contains(bed.get().asLong())) {
                return false;
            }
            targetBedHead = bed.get();
        }

        sleepingConfirmed = false;
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
            reset(); // 天亮:醒来,清黑名单,交回认知层
            return TaskState.RUNNING;
        }

        // 自己发起的寻路走到床边
        if (walk != null) {
            if (--walkBudget <= 0) {
                giveUp(companion, "走床超预算(30s)");
                return TaskState.RUNNING;
            }
            Trip.Status status = walk.tick();
            if (status == Trip.Status.ARRIVED) {
                walk = null;
                trySleep(companion);
            } else if (status == Trip.Status.FAILED) {
                giveUp(companion, "寻路失败(不可达)");
            }
            return TaskState.RUNNING;
        }

        // 没在走路但锁定了床:刚好在床边就直接上,否则发起寻路
        if (targetBedHead != null) {
            double dist = companion.position().distanceTo(Vec3.atCenterOf(targetBedHead));
            if (dist <= ARRIVE_DIST) {
                trySleep(companion);
            } else {
                startWalk(companion);
            }
            return TaskState.RUNNING;
        }

        return TaskState.RUNNING; // 无目标:等 canRun 的限频扫描,不占着身体做任何事
    }

    /** 到了床边:让原版判"能不能睡"（怪物太近/已有人睡都会被原版拒绝）,拒了的床本夜不再选。 */
    private void trySleep(NumenPlayer companion) {
        try {
            SleepOps.Slept result = SleepOps.sleep(targetBedHead, companion);
            if (result.sleeping()) {
                sleepingConfirmed = true;
                if (walk != null) {
                    walk.stop();
                    walk = null;
                }
                NumenEvents.reflex(companion, this, "上床睡着了 (" + coord(targetBedHead) + ")");
                return;
            }
            blacklist(companion, "上床被拒");
        } catch (Exception e) {
            blacklist(companion, "上床异常: " + e.getMessage());
        }
    }

    /** 走不到/上不去:床记入本夜黑名单,清目标,交回身体——不空转。 */
    private void giveUp(NumenPlayer companion, String why) {
        blacklist(companion, why);
        if (walk != null) {
            walk.stop();
            walk = null;
        }
    }

    private void blacklist(NumenPlayer companion, String why) {
        if (targetBedHead != null) {
            unusableBeds.add(targetBedHead.asLong());
            Constants.LOG.info("[numen-sleep] 放弃床 {} —— {}", coord(targetBedHead), why);
        }
        targetBedHead = null;
        scanBlockedUntil = companion.level().getGameTime() + 40;   // 2s 冷却,且不怕被抢断间隙
    }

    private void startWalk(NumenPlayer companion) {
        // 只走不挖不放、不等授权(反射不发 Consent 请求):走不到就 FAILED,黑名单兜底
        RouteSpec spec = RouteSpec.defaults().edit().consent(false).build();
        Goal goal = Goals.within(Goals.at(targetBedHead), 0.0, ARRIVE_DIST);
        walk = Trip.to(companion, goal, spec, targetBedHead);
        walkBudget = WALK_BUDGET_TICKS;
        Constants.LOG.info("[numen-sleep] 夜深了,去床边睡觉 —— 目标 {} 约 {} 格外",
                coord(targetBedHead), Math.round(companion.position().distanceTo(Vec3.atCenterOf(targetBedHead))));
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // 被更高优先级(吃/打架/脱困)抢走:只收自己的寻路。黑名单/冷却/目标全部保留——
        // 抢断是暂时的(回来接着干),若在这里清状态,canRun 变 false 就被 stop 清一次,
        // 下一刻又赢又失败,就是无限热循环(实测踩过)。
        if (walk != null) {
            walk.stop();
            walk = null;
        }
    }

    /** 清晨醒来/被打断后的重置:黑名单一并清空,新的一夜重新扫描。 */
    private void reset() {
        sleepingConfirmed = false;
        targetBedHead = null;
        walk = null;
        walkBudget = 0;
        scanBlockedUntil = 0;
        unusableBeds.clear();
    }

    @Override
    public String name() { return "sleep"; }

    @Override public String id() { return name(); }

    @Override public String describe() { return "晚上自动找空床上床睡觉，天亮自动醒来"; }

    // --- helpers ---

    /** 1.16.5 的 BlockPos 没有 toShortString（1.17+ 才有）——坐标自己拼。 */
    private static String coord(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    private static boolean isNight(NumenPlayer companion) {
        return companion.level().isNight(); // 1.16.5 Level.isNight() = 时间在 13000-23000
    }
}
