package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.permission.Listing;
import com.dwinovo.numen.core.task.build.BuildCellRules;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/* ────────────────────────────────────────────────────────────────────────────
 * ⚠️ 本文件是 2026-10-13 **反编译恢复**的（提交 32c2280）。
 *
 * 原版源码曾被 .gitignore 的通配忽略规则连坐忽略、**从未进过版本库**；工作树一次误删后，
 * 从**已部署的 jar**（.testserver/mods/numen-0.1.4-1.16.5.jar，刀 4 构建）反编译救回：
 * 抽 class → tiny-remapper(intermediary→named) → CFR → 文本重映射残留名。
 * **逻辑与刀 4 完全一致，但原注释全部丢失**，且反编译产物带少量伪影（强转/泛型推断）。
 *
 * 下面这份类说明取自**上游 1.20.1 同名文件**的 javadoc，权作阅读路线图：
 * 1.16.5 适配过的细节可能与它有出入，以代码为准。
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * 收不了尾时还缺的格:走一遍够得着却放不下的格,每一格归到一个病因下,得到一份清单。留案的日志、交给模型的回执、失败的类型都从这一份出,
 * 不各数各的。
 *
 * <p>剩下的格放不下去只有三种病因,修法各不相同:让占着的人挪开、认下图纸里原版不允许的那几格、找出是什么一再把它弄没。
 * 所以按病因分开报,每一堆点名到方块和坐标——笼统一句 "could not be placed" 等于裁决做了却不交代理由。
 */

final class BuildOutstanding {
    private final List<Cell> cells;
    private final int damagedCells;
    private static final int LOGGED_EXAMPLES = 12;

    BuildOutstanding(List<Cell> cells, int damagedCells) {
        this.cells = List.copyOf(cells);
        this.damagedCells = damagedCells;
    }

    static BuildOutstanding survey(List<BuildTaskRecord.Target> targets, BuildCellRules rules, LongSet skipped, Level level, int damagedCells) {
        ArrayList<Cell> cells = new ArrayList<Cell>();
        for (BuildTaskRecord.Target target : targets) {
            BlockPos pos;
            BlockState actual;
            if (target.matches(actual = rules.peek(pos = target.pos())) || skipped.contains(pos.asLong())) continue;
            BlockState desired = target.desiredState();
            Cause cause = rules.blockedByEntity(pos, desired) ? Cause.OCCUPIED : (!desired.canSurvive(level, pos) ? Cause.UNSUPPORTED : Cause.NOT_KEPT);
            cells.add(new Cell(cause, target, actual));
        }
        return new BuildOutstanding(cells, damagedCells);
    }

    List<Cell> cells() {
        return this.cells;
    }

    FailureType failure() {
        Map<Cause, Integer> counts = this.counts();
        Cause dominant = null;
        for (Map.Entry<Cause, Integer> e : counts.entrySet()) {
            if (dominant != null && e.getValue() <= counts.get(dominant)) continue;
            dominant = e.getKey();
        }
        return dominant == null ? FailureType.NOT_KEPT : dominant.failure;
    }

    String describe(Placement frame) {
        Function<BlockPos, String> naming = frame == null ? Listing::coords : pos -> Listing.coords(pos) + " = design " + Listing.coords(frame.relative(pos));
        ArrayList parts = new ArrayList();
        this.grouped().forEach((cause, byBlock) -> {
            int n = byBlock.values().stream().mapToInt(List::size).sum();
            ArrayList heaps = new ArrayList();
            byBlock.forEach((block, positions) -> heaps.add(Listing.part((String)BuildOutstanding.name(block), (int)positions.size(), positions, (Function)naming)));
            String says = cause == Cause.NOT_KEPT && this.damagedCells > 0 ? cause.says + " \u2014 something kept breaking the finished work" : cause.says;
            parts.add(n + " " + says + ": " + String.join(", ", heaps));
        });
        return this.cells.size() + " cell(s) unbuilt \u2014 " + String.join("; ", parts);
    }

    void log(int completed, int total, BlockPos feet, Placement frame) {
        TreeMap<Integer, Integer> byLayer = new TreeMap<Integer, Integer>();
        for (Cell cell : this.cells) {
            byLayer.merge(cell.target().pos().getY(), 1, Integer::sum);
        }
        Constants.LOG.info("[numen-build] \u6536\u4e0d\u4e86\u5c3e {}/{} feet={} \u7f3a\u683c\u5206\u5c42={} {}", new Object[]{completed, total, feet.toShortString(), byLayer, this.describe(frame)});
        for (Cell cell : this.cells.subList(0, Math.min(12, this.cells.size()))) {
            Constants.LOG.info("[numen-build] \u7f3a {} {} \u671f\u671b={} \u5b9e\u9645={}", new Object[]{cell.cause(), cell.target().pos().toShortString(), cell.target().desiredState(), cell.actual()});
        }
    }

    private Map<Cause, Integer> counts() {
        EnumMap<Cause, Integer> counts = new EnumMap<Cause, Integer>(Cause.class);
        for (Cell cell : this.cells) {
            counts.merge(cell.cause(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<Cause, Map<Block, List<BlockPos>>> grouped() {
        EnumMap<Cause, Map<Block, List<BlockPos>>> out = new EnumMap<Cause, Map<Block, List<BlockPos>>>(Cause.class);
        for (Cell cell : this.cells) {
            out.computeIfAbsent(cell.cause(), k -> new LinkedHashMap<>()).computeIfAbsent(cell.target().desiredState().getBlock(), k -> new ArrayList<>()).add(cell.target().pos());
        }
        return out;
    }

    private static String name(Block block) {
        return Registry.BLOCK.getKey(block).getPath();
    }

    static enum Cause {
        OCCUPIED(FailureType.ENTITY_BLOCKED, "blocked by someone standing there \u2014 ask them to step aside"),
        UNSUPPORTED(FailureType.NO_SUPPORT, "that vanilla physics will not hold at that spot (the blueprint asks for something impossible there)"),
        NOT_KEPT(FailureType.NOT_KEPT, "that would not stay put");

        final FailureType failure;
        final String says;

        private Cause(FailureType failure, String says) {
            this.failure = failure;
            this.says = says;
        }
    }

    record Cell(Cause cause, BuildTaskRecord.Target target, BlockState actual) {
    }
}

