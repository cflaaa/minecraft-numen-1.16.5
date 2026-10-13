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

