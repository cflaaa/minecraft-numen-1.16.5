package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.event.NumenEvents;
import com.dwinovo.numen.core.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.Task;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.TaskState;
import com.dwinovo.numen.core.task.inventory.EatCompanionTask;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.task.move.FollowTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.task.reflex.Reflex;
import com.dwinovo.numen.core.task.survival.EatingDecisions;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 饿了自己吃。上游把这条常驻链<b>删了</b>（"饿了是主人该知道的事，交互本身就是目的"，
 * 见 {@code CompanionTickDispatcher} 的旧注释）——那是"主人在场"的设计；本仓的部署形态
 * （D-05：服务器同伴 + 外接大脑，无人值守）按新规矩把它<b>加回来</b>，三档（{@link EatingDecisions}）：
 *
 * <pre>
 * 不回血（food ≤ 17）：身体完全空闲时补一口        —— 不打断任何活
 * 跑不动 （food ≤ 6） ：空闲、或只是在走路时补一口  —— 吃不妨碍走：让位一小口（约 1.6s），走自动续上
 * 挨饿  （food = 0）  ：立刻吃                    —— 打断一切非战斗的活；战斗中不吃
 * </pre>
 *
 * <h2>它补的是"饥饿急件"的洞</h2>
 * 没有这条链时，饥饿是恒急件：打断在跑的程序、干等主人/外脑来喂（2026-10-17 实测一轮饿死
 * 27 次；RUN 2 反复打断 Lua 程序——issues/0021）。有了它：背包有吃的 → 身体自己处理，
 * <b>不发急件、不打断程序</b>，吃完报一条 reflex 事件（AMBIENT，搭下一轮的车）；没有吃的 →
 * 照旧发 hungry 急件求救（判据 {@link #canFeedItself}，调用处 CompanionTickDispatcher）。
 *
 * <h2>什么时候不抢身体</h2>
 * <ul>
 *   <li><b>打架不吃</b>：反射自己开的仗（mob_defense 注册号 30 在本条 35 之前，TaskSelector
 *       先问先赢）和模型派的仗（{@code AttackCompanionTask} 会按住 mob_defense，这里照着看）
 *       都算——先活下来，再吃饭；</li>
 *   <li>模型派的 {@code numen.inv.eat} 在跑不抢（{@code EatCompanionTask} 反过来按住本条，
 *       同 MobDefense 对模型 attack 的口径）；</li>
 *   <li>创造/无饥饿机制不吃。</li>
 * </ul>
 *
 * <h2>让位与被打断都不亏</h2>
 * 被它顶掉的走路/挖矿只是 PREEMPTED（{@code TaskSlot#loseBody}），吃完自动续上、期限后推；
 * 反过来它自己被更高的本能（摔落缓冲、换气、逃跑）顶掉时，嘴里那一口直接作废重来——
 * 原版在咀嚼<b>完成那一刻</b>才扣食物，半途作废一口不亏东西。
 */
public final class EatChain implements Task, Reflex {

    /** 本能名册里的 id。别处按住这条本能时用它（{@code NumenPlayer.pauseReflex}）。 */
    public static final String ID = "auto_eat";

    /** 这一口的期限：照 {@code EatItemTaskRecord} 给 API 那一口的量（15s，盖住任何食物的咀嚼再加余量）。 */
    private static final long BITE_DEADLINE_TICKS = 15L * 20L;

    /** 正在吃的这一口。null = 这一刻没在吃。 */
    private EatCompanionTask bite;

    public EatChain() {
    }

    // ---- 名册登记（进 <instincts>，模型从这里知道自己有这条本能） ----

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String describe() {
        return "饿了自己吃背包里的食物:空闲时补一口,饿到扣血立刻吃,打架时不吃";
    }

    /**
     * 身体自己能解决这一顿吗：有饥饿机制、且背包里有可吃的。
     * CompanionTickDispatcher 用它决定"饿了"还要不要发急件。
     */
    public static boolean canFeedItself(NumenPlayer companion) {
        boolean hunger = WorkProfile.of(companion).hasHunger();
        Item picked = pickFood(companion);
        if (hunger && picked == null) {
            // [临时诊断 #0021] 有饥饿机制却挑不出食物——把背包摊开来看(只在饿急件这一刻打,不刷屏)
            var inv = companion.getInventory();
            StringBuilder sb = new StringBuilder("slots=").append(inv.getContainerSize()).append(" 内容:");
            for (int i = 0; i < inv.getContainerSize() && sb.length() < 600; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) {
                    sb.append(i).append('=').append(Registry.ITEM.getKey(s.getItem()))
                            .append(s.getItem().getFoodProperties() == null ? " " : "(食) ");
                }
            }
            Constants.LOG.warn("[numen-eat-debug] 挑不出食物(有饥饿): {}", sb);
        }
        return hunger && picked != null;
    }

    // ---- 调度 ----

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (bite != null) {
            return true;   // 正吃着:把这一口吃完,收场后下一刻再判要不要下一口
        }
        if (companion.reflexPaused(ID)) {
            return false;   // 模型派的 numen.inv.eat 正在跑:别抢
        }
        if (companion.reflexPaused(MobDefenseChain.ID)) {
            return false;   // 模型派的架正在打:打架不吃
        }
        if (!WorkProfile.of(companion).hasHunger()) {
            return false;   // 创造/无饥饿机制:吃不吃都没意义
        }
        if (pickFood(companion) == null) {
            return false;   // 没有吃的:轮不到这条链,hungry 急件自会去求救
        }
        // 打架与有没有吃的上面都排掉了:这里只判"急到哪一档、这个身体状态吃不吃"
        EatingDecisions.Want want = EatingDecisions.want(
                companion.getFoodData().getFoodLevel(), true, false);
        switch (want) {
            case NOW:
                return true;                                       // 挨饿扣血:立刻吃
            case IDLE_OR_WALKING:
                TaskRecord busy = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
                return busy == null || isWalking(busy);            // 只在走路:让位一小口,吃完续上
            case IDLE:
                return !CompanionTickDispatcher.bodyBusy(companion.getUUID());  // 完全空闲才补
            default:
                return false;
        }
    }

    /** 手上这件活是不是"只是在走路"：move.go / move.follow 那两种（原版边走边吃本来就行）。 */
    private static boolean isWalking(TaskRecord busy) {
        return busy instanceof MoveToTaskRecord || busy instanceof FollowTaskRecord;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (bite == null) {
            begin(companion);
            return TaskState.RUNNING;
        }
        TaskState state = bite.tick(companion);
        if (state != TaskState.RUNNING) {
            end(companion, state);
        }
        return TaskState.RUNNING;   // 这条链没有终点:吃没吃饱,下一刻 canRun 说了算
    }

    /** 开一口:挑背包里最好的那一样可食(营养最高,有害的排除),一口一口吃。 */
    private void begin(NumenPlayer companion) {
        Item food = pickFood(companion);
        if (food == null) {
            return;   // 刚好吃完了:这一刻没得吃,下一刻 canRun 再判
        }
        long now = companion.level.getGameTime();
        EatItemTaskRecord record = new EatItemTaskRecord(
                ID, "reflex-" + now, now + BITE_DEADLINE_TICKS, food, label(food), true);
        bite = new EatCompanionTask(companion, record);
        bite.start(companion);   // 前提不成立(比如刚好吃空)会当刻置 FAILED,下一 tick 在 end 收场
    }

    private void end(NumenPlayer companion, TaskState state) {
        String line = bite.result(state).message();
        bite = null;
        companion.controls().releaseAll();
        if (state == TaskState.SUCCESS) {
            Constants.LOG.info("[numen-eat] 自动进食 —— {}", line);
            // 不急:身体已经自己应对过了,这条是让她和翻聊天流的主人看得懂刚才发生了什么
            NumenEvents.reflex(companion, this, "handled hunger on instinct — " + line);
        } else {
            // 没吃成(最常见:判过之后刚好吃空了/身体说已经饱了):没什么可报的,下一刻 canRun 再判
            Constants.LOG.info("[numen-eat] 自动进食没成 —— {}", line);
        }
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        if (bite != null) {
            // 半途作废这一口:原版在咀嚼完成那一刻才扣食物,不亏东西;回来重开一口
            bite.stop(companion, why);
            bite = null;
        }
        companion.controls().releaseAll();
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- 挑食 ----

    /** 有害的:吃了挨负面效果/被传送——饿死也不该自动吃它们(主人想喂是主人的事)。 */
    private static final java.util.Set<String> INEDIBLE = java.util.Set.of(
            "minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato",
            "minecraft:pufferfish", "minecraft:chorus_fruit", "minecraft:suspicious_stew");

    /**
     * 背包里最好的那一样可食:营养最高,平手看饱和修饰。没有可食返回 null。
     * 没有"食物策略"可循(上游的 FoodPolicy 只在 PolicyReflex 的注释里当过例子,谁也没写过),
     * 这里取最简单的安全口径:营养优先、排除有害的。
     */
    static Item pickFood(NumenPlayer companion) {
        var inv = companion.getInventory();
        Item best = null;
        int bestNutrition = -1;
        float bestSaturation = Float.NEGATIVE_INFINITY;
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            var food = item.getFoodProperties();
            if (food == null || INEDIBLE.contains(Registry.ITEM.getKey(item).toString())) {
                continue;
            }
            if (food.getNutrition() > bestNutrition
                    || (food.getNutrition() == bestNutrition && food.getSaturationModifier() > bestSaturation)) {
                best = item;
                bestNutrition = food.getNutrition();
                bestSaturation = food.getSaturationModifier();
            }
        }
        return best;
    }

    private static String label(Item item) {
        return Registry.ITEM.getKey(item).getPath();
    }
}
