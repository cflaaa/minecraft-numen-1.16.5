package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.task.build.BuildCellRules;
import com.dwinovo.numen.core.task.build.BuildFixtures;
import com.dwinovo.numen.core.task.build.BuildInventory;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 * 材料账本:此刻还需要什么、背包还差什么、缺口怎么向玩家交代。
 * 开工前置、中途报缺与收工回执共用同一个统计口径,所以玩家听到的永远
 * 是同一个问题的答案——"从现在起还要凑什么"。
 */

final class BuildLedger {
    private static final int MISSING_ITEMS_LISTED = 8;
    private final NumenPlayer player;
    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;

    BuildLedger(NumenPlayer player, BuildTaskRecord r, BuildCellRules rules, BuildInventory inv, BuildFixtures fixtures) {
        this.player = player;
        this.r = r;
        this.rules = rules;
        this.inv = inv;
        this.fixtures = fixtures;
    }

    Map<Item, Integer> remainingNeed() {
        LinkedHashMap<Item, Integer> need = new LinkedHashMap<Item, Integer>();
        for (BuildTaskRecord.Target target : this.r.targets) {
            if (!this.rules.costsMaterial(target) || !this.needsFor(target).isEmpty() || target.matches(this.rules.peek(target.pos())) || this.rules.blockedByMode(target) || this.rules.hopeless(target)) continue;
            need.merge(target.item(), target.materialCount(), Integer::sum);
        }
        for (BuildTaskRecord.EntitySpawn spawn : this.r.entities) {
            if (spawn.item() == Items.AIR || this.fixtures.alreadyThere(spawn)) continue;
            need.merge(spawn.item(), 1, Integer::sum);
        }
        return need;
    }

    List<BuildTaskRecord.CellNeed> needsFor(BuildTaskRecord.Target target) {
        if (this.r.cellNeeds.isEmpty()) {
            return List.of();
        }
        List<BuildTaskRecord.CellNeed> found = this.r.cellNeeds.get(target.pos().asLong());
        return found == null ? List.of() : found;
    }

    List<BuildTaskRecord.CellNeed> remainingCellNeeds() {
        ArrayList<BuildTaskRecord.CellNeed> out = new ArrayList<BuildTaskRecord.CellNeed>();
        for (BuildTaskRecord.Target target : this.r.targets) {
            List<BuildTaskRecord.CellNeed> needs = this.needsFor(target);
            if (needs.isEmpty() || !this.rules.costsMaterial(target) || target.matches(this.rules.peek(target.pos())) || this.rules.blockedByMode(target) || this.rules.hopeless(target)) continue;
            out.addAll(needs);
        }
        for (BuildTaskRecord.EntitySpawn spawn : this.r.entities) {
            if (this.fixtures.alreadyThere(spawn)) continue;
            for (ItemStack carried : spawn.payload()) {
                out.add(new BuildTaskRecord.CellNeed(carried, true));
            }
        }
        return out;
    }

    List<BuildTaskRecord.CellNeed> needShortfall(List<BuildTaskRecord.CellNeed> needs) {
        ArrayList<BuildTaskRecord.CellNeed> kinds = new ArrayList<BuildTaskRecord.CellNeed>();
        ArrayList<Integer> counts = new ArrayList<Integer>();
        for (BuildTaskRecord.CellNeed want : needs) {
            int at = -1;
            for (int i = 0; i < kinds.size(); ++i) {
                if (((BuildTaskRecord.CellNeed)kinds.get(i)).exact() != want.exact() || !((BuildTaskRecord.CellNeed)kinds.get(i)).matches(want.stack())) continue;
                at = i;
                break;
            }
            if (at < 0) {
                kinds.add(want);
                counts.add(1);
                continue;
            }
            counts.set(at, (Integer)counts.get(at) + 1);
        }
        ArrayList<BuildTaskRecord.CellNeed> missing = new ArrayList<BuildTaskRecord.CellNeed>();
        for (int i = 0; i < kinds.size(); ++i) {
            int have;
            for (int k = have = this.inv.countMatching((BuildTaskRecord.CellNeed)kinds.get(i)); k < (Integer)counts.get(i); ++k) {
                missing.add((BuildTaskRecord.CellNeed)kinds.get(i));
            }
        }
        return missing;
    }

    Map<Item, Integer> shortfallAgainstInventory(Map<Item, Integer> need) {
        LinkedHashMap<Item, Integer> shortfall = new LinkedHashMap<Item, Integer>();
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            int have = this.inv.mainInventoryCount(e.getKey());
            if (have >= e.getValue()) continue;
            shortfall.put(e.getKey(), e.getValue() - have);
        }
        return shortfall;
    }

    String missingReason(Map<Item, Integer> passMissing) {
        Map<Item, Integer> shortfall = this.shortfallAgainstInventory(this.remainingNeed());
        List<BuildTaskRecord.CellNeed> exact = this.needShortfall(this.remainingCellNeeds());
        if (shortfall.isEmpty() && exact.isEmpty()) {
            shortfall = passMissing;
        }
        return "still needs " + BuildLedger.summarizeShortfall(shortfall, exact) + ". Everything already standing stays; restock and send the SAME call again to carry on from exactly here \u2014 finished cells are skipped automatically.";
    }

    static String summarizeShortfall(Map<Item, Integer> shortfall, List<BuildTaskRecord.CellNeed> extra) {
        String bulk;
        String string = bulk = shortfall.isEmpty() ? "" : BuildLedger.summarizeShortfall(shortfall);
        if (extra.isEmpty()) {
            return bulk.isEmpty() ? "nothing" : bulk;
        }
        LinkedHashMap<String, Integer> plain = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, Integer> byName = new LinkedHashMap<String, Integer>();
        for (BuildTaskRecord.CellNeed need : extra) {
            (need.exact() ? byName : plain).merge(need.stack().getHoverName().getString(), 1, Integer::sum);
        }
        ArrayList<String> parts = new ArrayList<String>();
        plain.forEach((name, n) -> parts.add((String)((name + " x" + n))));
        if (!byName.isEmpty()) {
            ArrayList exactParts = new ArrayList();
            byName.forEach((name, n) -> exactParts.add(name + " x" + n));
            parts.add((String)(("and exactly these (same enchantments / patterns / contents, not just the same kind of item): " + String.join(", ", exactParts))));
        }
        String tail = String.join(", ", parts);
        return bulk.isEmpty() ? tail : bulk + "; " + tail;
    }

    static String summarizeShortfall(Map<Item, Integer> shortfall) {
        ArrayList<Map.Entry<Item, Integer>> sorted = new ArrayList<Map.Entry<Item, Integer>>(shortfall.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        int listed = Math.min(8, sorted.size());
        for (int i = 0; i < listed; ++i) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(BuildLedger.label((Item)((Map.Entry)sorted.get(i)).getKey())).append(" x").append(((Map.Entry)sorted.get(i)).getValue());
        }
        if (sorted.size() > listed) {
            int restKinds = sorted.size() - listed;
            int restCount = 0;
            for (int i = listed; i < sorted.size(); ++i) {
                restCount += ((Integer)((Map.Entry)sorted.get(i)).getValue()).intValue();
            }
            out.append(", and ").append(restKinds).append(" more kinds (").append(restCount).append(" items) \u2014 the short_of of `numen.build.blueprint` lists every one");
        }
        int total = 0;
        for (int v : shortfall.values()) {
            total += v;
        }
        out.append("; ").append(total).append(" items short in total");
        return out.toString();
    }

    private static String label(Item item) {
        return Registry.ITEM.getKey(item).getPath();
    }
}

