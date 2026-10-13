package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.task.build.BuildCellRules;
import com.dwinovo.numen.core.task.build.BuildFixtures;
import com.dwinovo.numen.core.task.build.BuildInventory;
import com.dwinovo.numen.core.task.build.BuildLedger;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.move.Destination;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的 `**/build/` 连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * 一件施工的每一格,对她此刻站的地方而言是什么情形:已经对上、不去动、得先挖开、还立不住、够得着就放、够不着。{@code numen.build.place} 只放
 * "够得着就放"的那些、受理前问还有没有,{@code numen.build.diff} 把整份照这同一个判据数给脚本——施工与查询读的是同一份。
 *
 * <p>"够得着"就是 {@code numen.move.to(…, {arrive = "place"})} 走到的地方({@link Destination#reach}):走到了,这一格就在这里算够得着。
 * 生存模式下图纸要的格里立着别的东西,得先由 {@code numen.work.dig} 挖开,{@code numen.build.place} 不挖;创造模式照原版一下就碎,放的时候
 * 直接顶掉。立着东西、身上却没有要放的料的格不叫她挖:没料挖了只是在主人的地上挖个坑。
 */

public final class BuildSurvey {
    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildLedger ledger;
    private final boolean instaBreak;
    private final Feet feet;

    BuildSurvey(NumenPlayer player, BuildTaskRecord r, BuildCellRules rules, BuildInventory inv, BuildLedger ledger) {
        this.player = player;
        this.r = r;
        this.rules = rules;
        this.inv = inv;
        this.ledger = ledger;
        this.instaBreak = WorkProfile.of((ServerPlayer)player).instaBreak();
        this.feet = Feet.of((ServerPlayer)player);
    }

    public static BuildSurvey of(NumenPlayer player, BuildTaskRecord r) {
        BuildCellRules rules = new BuildCellRules(player, r);
        BuildInventory inv = new BuildInventory(player);
        BuildLedger ledger = new BuildLedger(player, r, rules, inv, new BuildFixtures(player, r, inv));
        return new BuildSurvey(player, r, rules, inv, ledger);
    }

    public State of(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState now = this.rules.peek(pos);
        if (target.matches(now) || target.desiredState().getBlock() instanceof LiquidBlock || BuildCellRules.isAirTarget(target) && now.getBlock() instanceof LiquidBlock) {
            return State.DONE;
        }
        if (this.rules.refused(target) || this.rules.hopeless(target)) {
            return State.SKIPPED;
        }
        if (!this.instaBreak && DigTaskRecord.wants(null, (BlockState)now)) {
            return BuildCellRules.isAirTarget(target) || this.affordable(target) ? State.DIG : State.SHORT;
        }
        if (target.itemPlace() && !target.desiredState().canSurvive(this.player.level(), pos) && this.waitsOnNeighbour(pos)) {
            return State.UNHELD;
        }
        return this.reaches(pos) ? State.REACH : State.FAR;
    }

    private boolean waitsOnNeighbour(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BuildTaskRecord.Target next = this.r.targetAt(pos.relative(side));
            if (next == null || next.matches(this.rules.peek(next.pos())) || this.rules.refused(next) || this.rules.hopeless(next)) continue;
            return true;
        }
        return false;
    }

    boolean reaches(BlockPos pos) {
        return this.feet != null && this.feet.in(Destination.reach((NumenPlayer)this.player, pos));
    }

    boolean affordable(BuildTaskRecord.Target target) {
        int cost;
        int n = cost = this.r.consumeMaterials && this.rules.costsMaterial(target) ? target.materialCount() : 0;
        if (cost <= 0) {
            return true;
        }
        List<BuildTaskRecord.CellNeed> needs = this.ledger.needsFor(target);
        if (needs.isEmpty()) {
            return this.inv.hasItems(target.item(), cost, true);
        }
        for (BuildTaskRecord.CellNeed need : needs) {
            if (this.inv.countMatching(need) >= 1) continue;
            return false;
        }
        return true;
    }

    public Tally tally() {
        EnumMap<State, Integer> counts = new EnumMap<State, Integer>(State.class);
        ArrayList<BlockPos> dig = new ArrayList<BlockPos>();
        ArrayList<BlockPos> far = new ArrayList<BlockPos>();
        for (BuildTaskRecord.Target target : this.r.targets) {
            State state = this.of(target);
            counts.merge(state, 1, Integer::sum);
            if (state == State.DIG) {
                dig.add(target.pos());
                continue;
            }
            if (state != State.FAR) continue;
            far.add(target.pos());
        }
        BlockPos here = this.player.blockPosition();
        dig.sort(Comparator.comparingDouble(p -> p.distSqr((Vec3i)here)));
        far.sort(Comparator.comparingInt(Vec3i::getY).thenComparingDouble(p -> p.distSqr((Vec3i)here)));
        return new Tally(counts, dig, far);
    }

    public static enum State {
        DONE,
        SKIPPED,
        DIG,
        SHORT,
        UNHELD,
        REACH,
        FAR;

    }

    public record Tally(Map<State, Integer> counts, List<BlockPos> dig, List<BlockPos> far) {
        public int count(State state) {
            return this.counts.getOrDefault(state, 0);
        }

        public int left() {
            return this.count(State.DIG) + this.count(State.SHORT) + this.count(State.REACH) + this.count(State.UNHELD) + this.count(State.FAR);
        }
    }
}

