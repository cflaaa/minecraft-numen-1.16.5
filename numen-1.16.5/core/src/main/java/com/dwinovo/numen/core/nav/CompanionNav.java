package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 一趟「走到某处」：真的寻路（A* 加地形分析），不是「面朝目标按住前进」。
 *
 * <p>用法与原来那个直线走法一样：{@link #step} 每服务端刻调一次，返回 null 表示还在走，
 * 返回文字表示走完了（到没到看 {@link #success()}）。
 *
 * <p>走法是寻路模块的事（{@link Navigator#drive} 交出的 {@link Navigation}）；
 * 这里只负责三件宿主该管的事：把坐标包成目标、按刻推进、把结局翻成给模型看的中文。
 * 到了的时候模块自己会松开所有键，不用这里再收尾。
 *
 * <p>只在世界所在的线程上调用。
 */
public final class CompanionNav {

    /** 水平离目标这么近就算到（与原版「走到那一格」的手感一致）。 */
    private static final double ARRIVE = 1.5;

    /**
     * 最多走这么多刻（20 刻 = 1 秒）就判「走不到」。
     *
     * <p>这是兜底，不是主判据：正常收场由寻路自己给（没路、卡住、憋不住气……）。
     * 三分钟足够走完任何正常的一趟；真卡住时靠它把身体和工具槽解放出来。
     */
    private static final int TICK_LIMIT = 20 * 60 * 3;

    private final Navigation navigation;
    private final String target;
    private int ticks;
    private boolean success;
    private String data = "";

    private CompanionNav(Navigation navigation, String target) {
        this.navigation = navigation;
        this.target = target;
    }

    /** 走到 {@code (x, y, z)} 附近 {@link #ARRIVE} 格以内。 */
    public static CompanionNav to(NumenPlayer player, int x, int y, int z) {
        BlockPos target = new BlockPos(x, y, z);
        Navigator navigator = CompanionPorts.navigator(player, CompanionPorts.dangers(player));
        Goal goal = Goals.within(Goals.at(target), 0.0, ARRIVE);
        Navigation navigation = navigator.drive(NavRequest.to(goal, RouteSpec.defaults()));
        return new CompanionNav(navigation, x + "," + y + "," + z);
    }

    public boolean success() {
        return success;
    }

    /** 给结局附的事实（到达时是位置，没到是原因）。 */
    public String data() {
        return data;
    }

    public String describe() {
        return target;
    }

    /** @return null = 还没走完；否则是给模型看的结果文字。 */
    public String step(NumenPlayer body) {
        if (++ticks > TICK_LIMIT) {
            navigation.stop();
            success = false;
            data = "走了 " + ticks + " 刻还没到，停在 " + where(body);
            return "走不到 " + target + "（超时），停在 " + where(body);
        }
        NavStatus status = navigation.tick();
        if (status.running()) {
            return null;
        }
        success = status.state() == NavStatus.State.ARRIVED;
        data = render(status, body);
        return success ? "走到了 " + target + "，现在在 " + where(body) : "走不到 " + target + "：" + data;
    }

    /** 半路不要了：在飞的搜索作废、松开所有键。 */
    public void stop() {
        navigation.stop();
    }

    private static String where(NumenPlayer body) {
        BlockPos at = body.blockPosition();
        return at.getX() + "," + at.getY() + "," + at.getZ();
    }

    /**
     * 把结局翻成给模型看的中文。
     *
     * <p>寻路的结局是**事实**（{@link Outcome}），怎么对人说是宿主的事——所以翻译只在这一处。
     * 每一种都要说清「为什么没到」，并且尽量给出下一步该怎么办，模型才知道要不要放宽规格或换个目标。
     */
    private static String render(NavStatus status, NumenPlayer body) {
        if (status.state() == NavStatus.State.STOPPED) {
            return "被叫停了";
        }
        Outcome outcome = status.outcome();
        if (outcome == null) {
            return "没有结局";
        }
        if (outcome instanceof Outcome.Arrived) {
            return "到了";
        }
        if (outcome instanceof Outcome.NoRoute) {
            return "按现在的方式走不过去（不挖不垫的话绕不过去）";
        }
        if (outcome instanceof Outcome.OutOfBudget) {
            return "太远或太绕，搜索没搜完就放弃了——换个近一点的地方试试";
        }
        if (outcome instanceof Outcome.Unloaded) {
            return "路伸进了还没加载的区块，那边是什么不知道";
        }
        if (outcome instanceof Outcome.Stranded stranded) {
            return "出发不了：身体卡在 " + pos(stranded.cell()) + " 里，或悬在半空";
        }
        if (outcome instanceof Outcome.NeedsChanges changes) {
            return "要改 " + changes.alterations() + " 格地形（挖开或垫上）才过得去，而这一趟不许改地形";
        }
        if (outcome instanceof Outcome.NoMaterials) {
            return "要垫方块才过得去，但身上没有能垫的料";
        }
        if (outcome instanceof Outcome.OverAlterBudget over) {
            return "最少要改 " + over.needed() + " 格地形，超出这一趟的预算";
        }
        if (outcome instanceof Outcome.Breathless) {
            return "路上有一段水下太长了，憋不住气";
        }
        if (outcome instanceof Outcome.Denied denied) {
            return "有一格被拒绝了：" + pos(denied.cell());
        }
        if (outcome instanceof Outcome.Blocked) {
            return "走到一半被挡住了，过不去";
        }
        if (outcome instanceof Outcome.NoLineOfSight sight) {
            return "到了附近，但看不见 " + pos(sight.target()) + " 那一格";
        }
        return String.valueOf(outcome);
    }

    private static String pos(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
