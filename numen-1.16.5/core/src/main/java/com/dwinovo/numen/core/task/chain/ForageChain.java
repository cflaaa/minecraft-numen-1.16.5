package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.event.NumenEvents;
import com.dwinovo.numen.core.permission.Action;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.core.task.Task;
import com.dwinovo.numen.core.task.TaskState;
import com.dwinovo.numen.core.task.combat.AttackCompanionTask;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.task.reflex.Reflex;
import com.dwinovo.numen.pathing.body.Controls;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;

/**
 * 饿了、背包又没吃的,就<b>自己去弄一口</b>:打一只附近的动物,走过去把它掉的东西捡起来,再交给
 * {@link EatChain} 去吃。
 *
 * <h2>它补的是"空背包"那个洞</h2>
 * {@code auto_eat} 只会吃背包里已有的;背包空的它无能为力。旧行为是这种时候发 {@code hungry}
 * 恒急件去求救——把在跑的程序打断(#0004/#0021)，而无人值守 + 无 op 的外接大脑形态下，外脑既
 * 不能 {@code /give} 也没法在被打断后接着跑，于是"从头开始(空背包)"的活第一步就死。本仓按
 * 用户定的第二方案(#0025)**加回一条低优先本能**:没吃的就自己去打猎，把"找吃的"这件事从
 * "打断外脑"改成"身体自己办"。
 *
 * <h2>三拍子（状态机见 {@link ForageDecisions}，2026-10-30 重做）</h2>
 * <pre>
 * IDLE: 空闲。初始与每轮收场都在这里;再出发必须过完整闸门(ForageDecisions.next),
 *       过了才真的开猎——饱食的同伴绝不主动狩猎,也不再有"选中了却空转"的 tick。
 * HUNT: 派一场 AttackCompanionTask 打最近的一只猎物(点名模式,与 numen.fight.attack 同一份代码)
 * LOOT: 用按键直接走到掉落物旁——原版玩家走近就捡(ItemEntityTouchMixin → NumenPlayer.touchedItem);
 *       捡完/捡不到回 IDLE,下一轮再饿时由闸门重新放行。
 * </pre>
 *
 * <h2>什么时候不抢身体</h2>
 * <ul>
 *   <li><b>打架不吃/不猎</b>：{@code mob_defense} 正在忙、或身边本来就有危险时让位——先活下来；</li>
 *   <li>背包里有吃的轮不到它({@link EatChain#pickFood} 非空)；</li>
 *   <li>模型派的 {@code numen.fight.attack}/{@code numen.inv.eat} 在跑不抢(按住同 ID 的本能)。</li>
 * </ul>
 */
public final class ForageChain implements Task, Reflex {

    /** 本能名册里的 id。 */
    public static final String ID = "forage";

    /** 看多远找猎物。 */
    private static final double SCAN_RADIUS = 32.0;
    /** 身边这么近有危险就先去打架/逃命,不猎。 */
    private static final double DANGER_RADIUS = 12.0;
    /** 背包里没吃的、饱食度掉到这条线(原版疾跑线 SPRINT_AT=7 之上一点)就出发打猎。 */
    private static final int FORAGE_AT = 8;
    /** 一场猎的期限(60s)。 */
    private static final long HUNT_DEADLINE_TICKS = 60L * 20L;
    /** 捡东西时看得见多远的掉落物。 */
    private static final double LOOT_RADIUS = 8.0;
    /** 走过去捡最多花这么久(5s),免得追一个够不到的东西卡死。 */
    private static final int LOOT_MAX_TICKS = 100;
    /** 离掉落物这么近就停(原版拾取要 bounding box 相交)。 */
    private static final double LOOT_REACH = 0.6;

    private ForageDecisions.Phase phase = ForageDecisions.Phase.IDLE;
    private AttackCompanionTask hunt;
    private int lootTicks;

    public ForageChain() {
    }

    // ---- 给 CompanionTickDispatcher 判"能不能自救"用 ----

    /**
     * 身体自己能不能弄到吃的:{@code EatChain} 管背包里已有的,这条管"背包空的但有猎可打"。
     * 两者都为假时,{@code hungry} 才是真的求救急件(#0025)。
     */
    public static boolean canForage(NumenPlayer companion) {
        if (!WorkProfile.of(companion).hasHunger()) {
            return false;   // 创造/无饥饿机制
        }
        if (companion.getFoodData().getFoodLevel() > FORAGE_AT) {
            return false;   // 还不到饿的线
        }
        if (EatChain.pickFood(companion) != null) {
            return false;   // 背包里有吃的:那是 EatChain 的事
        }
        return nearestPrey(companion) != null;
    }

    // ---- 调度 ----

    /** 把 Minecraft 事实收拢成状态机的门槛快照。 */
    private ForageDecisions.Gates gates(NumenPlayer companion, boolean preyNear) {
        return new ForageDecisions.Gates(
                WorkProfile.of(companion).hasHunger()
                        && companion.getFoodData().getFoodLevel() <= FORAGE_AT,
                EatChain.pickFood(companion) == null,
                preyNear,
                !Menace.hostilesAround(companion, DANGER_RADIUS).isEmpty(),
                companion.reflexPaused(ID),
                companion.reflexPaused(MobDefenseChain.ID));
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        // HUNT/LOOT:做完再说(抢断由更高优先级链按序完成);IDLE:过完整闸门,过不了不占调度器。
        boolean preyNear = nearestPrey(companion) != null;
        return ForageDecisions.next(phase, gates(companion, preyNear))
                != ForageDecisions.Action.REST;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        boolean preyNear = nearestPrey(companion) != null;
        switch (ForageDecisions.next(phase, gates(companion, preyNear))) {
            case START_HUNT -> startHunt(companion, preyNear);
            case CONTINUE_HUNT -> tickHunt(companion);
            case CONTINUE_LOOT -> tickLoot(companion);
            case REST -> { /* 闸门没过:不该被调度;万一被调度,交回身体不做任何事 */ }
        }
        return TaskState.RUNNING;   // 这条链没有终点:收场回 IDLE,下一刻闸门说了算
    }

    /** IDLE 且闸门全过:真的发起攻击——构造狩猎任务并 start。 */
    private void startHunt(NumenPlayer companion, boolean preyNear) {
        Mob prey = nearestPrey(companion);
        if (prey == null) {
            return;   // canRun 与此刻之间猎物消失:留在 IDLE,下轮闸门重新判
        }
        long now = companion.level().getGameTime();
        AttackTaskRecord record = new AttackTaskRecord(
                ID, "forage-" + now, now + HUNT_DEADLINE_TICKS, List.of(prey.getId()), false);
        hunt = new AttackCompanionTask(companion, record);
        hunt.start(companion);
        phase = ForageDecisions.Phase.HUNT;
        Constants.LOG.info("[numen-forage] 自动打猎 —— 目标 {} 约 {} 格外",
                prey.getType().getDescriptionId(), Math.round(companion.distanceTo(prey)));
    }

    private void tickHunt(NumenPlayer companion) {
        if (hunt == null) {
            phase = ForageDecisions.Phase.IDLE;   // 状态错位(被抢占清了任务):回 IDLE 重走闸门
            return;
        }
        TaskState state = hunt.tick(companion);
        if (state != TaskState.RUNNING) {
            String line = hunt.result(state).message();
            hunt = null;
            companion.controls().releaseAll();
            Constants.LOG.info("[numen-forage] 猎毕 {} —— {}", state, line);
            phase = ForageDecisions.Phase.LOOT;   // 去把它掉的捡起来
            lootTicks = LOOT_MAX_TICKS;
        }
    }

    /** 用按键直接走到掉落物旁:走近了就由原版拾取 + {@code NumenPlayer.touchedItem} 收进背包。 */
    private void tickLoot(NumenPlayer companion) {
        ItemEntity drop = nearestDrop(companion);
        if (drop == null) {
            finish(companion, "地上没有可捡的掉落物");
            return;
        }
        double dx = drop.getX() - companion.getX();
        double dz = drop.getZ() - companion.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= LOOT_REACH || --lootTicks <= 0) {
            finish(companion, "走到掉落物旁");
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));   // 朝它
        companion.setYRot(yaw);
        companion.setYHeadRot(yaw);
        Controls keys = companion.controls();
        keys.press(Controls.Key.FORWARD);
        keys.press(Controls.Key.JUMP);   // 每一步都能翻过一格台阶
    }

    private void finish(NumenPlayer companion, String why) {
        companion.controls().releaseAll();
        phase = ForageDecisions.Phase.IDLE;
        lootTicks = 0;
        Constants.LOG.info("[numen-forage] 收场 —— {}", why);
        NumenEvents.reflex(companion, this, "went and got food on instinct — " + why);
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // 被更高的本能(吃/打架/摔落/换气)抢走:松开身体,猎的状态一个不动,回来接着打/捡。
        if (hunt != null) {
            hunt.stop(companion, why);
        }
        companion.controls().releaseAll();
    }

    @Override
    public String name() {
        return ID;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String describe() {
        return "饿着又没吃的时,自己去打一只动物、把掉落捡起来充饥";
    }

    // ---- 找猎物 / 找掉落物 ----

    /**
     * 最近的一只可猎的动物:牛/猪/羊/鸡/兔一类。排除可驯服的(狼/猫/马——打了会还手,还会成帮)与北极熊
     * (会主动攻击),并过权限层(别人点了名的宠物、有名字的不动)。
     */
    private static Mob nearestPrey(NumenPlayer companion) {
        List<Mob> prey = NearbyEntities.within(companion, SCAN_RADIUS, Mob.class,
                m -> m.isAlive() && m instanceof Animal
                        && !(m instanceof TamableAnimal) && !(m instanceof PolarBear));
        Mob best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Mob m : prey) {
            if (!Permission.judge(companion, Action.attack(m)).allowed()) {
                continue;
            }
            double d = companion.distanceToSqr(m);
            if (d < bestSqr) {
                bestSqr = d;
                best = m;
            }
        }
        return best;
    }

    private static ItemEntity nearestDrop(NumenPlayer companion) {
        List<ItemEntity> items = NearbyEntities.within(companion, LOOT_RADIUS, ItemEntity.class, e -> e.isAlive());
        ItemEntity best = null;
        double bestSqr = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = companion.distanceToSqr(e);
            if (d < bestSqr) {
                bestSqr = d;
                best = e;
            }
        }
        return best;
    }
}
