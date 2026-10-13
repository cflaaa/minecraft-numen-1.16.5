package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.entity.InputDriver;
import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.permission.Action;
import com.dwinovo.numen.core.permission.ConsentAnswer;
import com.dwinovo.numen.core.permission.ConsentItem;
import com.dwinovo.numen.core.permission.Gate;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.core.permission.PlacedBlocks;
import com.dwinovo.numen.core.permission.Verdict;
import com.dwinovo.numen.core.sdk.Call;
import com.dwinovo.numen.core.sdk.Doc;
import com.dwinovo.numen.core.sdk.LuaCodecs;
import com.dwinovo.numen.core.task.Task;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.TaskState;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.core.task.build.BuildCellRules;
import com.dwinovo.numen.core.task.build.BuildFixtures;
import com.dwinovo.numen.core.task.build.BuildInventory;
import com.dwinovo.numen.core.task.build.BuildLedger;
import com.dwinovo.numen.core.task.build.BuildOrder;
import com.dwinovo.numen.core.task.build.BuildOutstanding;
import com.dwinovo.numen.core.task.build.BuildShowmanship;
import com.dwinovo.numen.core.task.build.BuildSurvey;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.pathing.body.Hotbar;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BuildCompanionTask
extends AbstractCompanionTask<BuildTaskRecord> {
    private static final int PLACE_FLAGS = 11;
    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildShowmanship show;
    private final Map<Long, BuildTaskRecord.Target> targetByPos = new LinkedHashMap<Long, BuildTaskRecord.Target>();
    private final Map<Item, Integer> passMissing = new LinkedHashMap<Item, Integer>();
    private LongOpenHashSet observedCompleted;
    private Phase phase = Phase.WORK;
    private final BlockPos siteMin;
    private final BlockPos siteMax;
    private List<BuildTaskRecord.Target> order = List.of();
    private int layerStart;
    private int layerEnd;
    private final LongOpenHashSet placedThisLayer = new LongOpenHashSet();
    private double cellsPerTick;
    private double placeCredit;
    private int damagedCells;
    private LongOpenHashSet skippedPos = new LongOpenHashSet();
    private int skippedCells;
    private int passStartCompleted;
    private boolean passStarved;
    private String note = "done";
    private List<ConsentItem> consentItems = List.of();
    private static final int RESCAN_PER_TICK = 512;
    private int rescanCursor;

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.rules = new BuildCellRules(player, record);
        this.inv = new BuildInventory(player);
        this.fixtures = new BuildFixtures(player, record, this.inv);
        this.ledger = new BuildLedger(player, record, this.rules, this.inv, this.fixtures);
        for (BuildTaskRecord.Target target : record.targets) {
            this.targetByPos.put(target.pos().asLong(), target);
        }
        BlockPos[] box = BuildCompanionTask.siteBox(player, record.targets);
        this.siteMin = box[0];
        this.siteMax = box[1];
        this.show = new BuildShowmanship(player, this.inv);
    }

    private BuildSurvey survey() {
        return new BuildSurvey(this.player, (BuildTaskRecord)this.r, this.rules, this.inv, this.ledger);
    }

    protected List<Precondition> preconditions() {
        return List.of(this::checkMaterials, this::checkReach);
    }

    private Precondition.Failure checkReach() {
        BuildSurvey.Tally tally = this.survey().tally();
        if (tally.count(BuildSurvey.State.REACH) > 0 || tally.left() == 0) {
            return null;
        }
        int unheld = tally.count(BuildSurvey.State.UNHELD);
        if (unheld == tally.left()) {
            return new Precondition.Failure(unheld + " cell(s) would not stay where the design puts them: nothing holds them there.", FailureType.NO_SUPPORT);
        }
        String hint = !tally.far().isEmpty() ? Call.of((String)"numen.move.to", (Object[])new Object[]{tally.far().get(0), Map.of("arrive", "place")}) : (!tally.dig().isEmpty() ? Call.of((String)"numen.work.dig", (Object[])new Object[]{tally.dig().get(0)}) : null);
        return new Precondition.Failure("nothing of it to place within reach of where you stand. " + BuildCompanionTask.remaining(tally), FailureType.OUT_OF_REACH, hint);
    }

    private static String remaining(BuildSurvey.Tally tally) {
        int unheld;
        int shortCells;
        BlockPos next;
        ArrayList<String> parts = new ArrayList<String>();
        int reach = tally.count(BuildSurvey.State.REACH);
        if (reach > 0) {
            parts.add((String)((reach + " cell(s) within reach go in on the next numen.build.place")));
        }
        if (!tally.far().isEmpty()) {
            next = tally.far().get(0);
            parts.add((String)((tally.far().size() + " cell(s) out of reach \u2014 the lowest nearest is " + BuildCompanionTask.xyz(next) + ": numen.move.to(" + BuildCompanionTask.lua(next) + ", {arrive = \"place\"}) gets you within reach of it")));
        }
        if (!tally.dig().isEmpty()) {
            next = tally.dig().get(0);
            parts.add((String)((tally.dig().size() + " cell(s) hold another block that must be dug out first \u2014 the nearest is " + BuildCompanionTask.xyz(next) + ": numen.move.to(" + BuildCompanionTask.lua(next) + ", {arrive = \"dig\"}) then numen.work.dig(" + BuildCompanionTask.lua(next) + ")")));
        }
        if ((shortCells = tally.count(BuildSurvey.State.SHORT)) > 0) {
            parts.add((String)((shortCells + " cell(s) hold another block and you carry nothing to put there")));
        }
        if ((unheld = tally.count(BuildSurvey.State.UNHELD)) > 0) {
            parts.add((String)((unheld + " cell(s) would not stay put yet: what holds them is not built")));
        }
        return parts.isEmpty() ? "Nothing left to do here." : "Still to do: " + String.join("; ", parts) + ".";
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String lua(BlockPos pos) {
        return LuaCodecs.literal(pos);
    }

    private Precondition.Failure checkMaterials() {
        if (!((BuildTaskRecord)this.r).consumeMaterials) {
            return null;
        }
        Map<Item, Integer> need = this.ledger.remainingNeed();
        Map<Item, Integer> shortfall = this.ledger.shortfallAgainstInventory(need);
        List<BuildTaskRecord.CellNeed> exactShort = this.ledger.needShortfall(this.ledger.remainingCellNeeds());
        if (shortfall.isEmpty() && exactShort.isEmpty()) {
            return null;
        }
        if (((BuildTaskRecord)this.r).allowPartial) {
            for (Item item : need.keySet()) {
                if (!this.inv.hasItem(item, true)) continue;
                return null;
            }
        }
        return new Precondition.Failure("not enough materials yet \u2014 " + BuildLedger.summarizeShortfall(shortfall, exactShort) + ". Nothing was placed. Survival mode consumes 1 item per cell; gather these, then send the SAME call again \u2014 anything already standing is skipped, so a restocked repeat picks up exactly where this left off.", FailureType.NO_MATERIAL);
    }

    protected void onStart() {
        this.observedCompleted = new LongOpenHashSet();
        this.skippedPos = new LongOpenHashSet();
        this.rescanAll();
        this.rebuildOrder();
        this.computePace();
        this.passStartCompleted = ((BuildTaskRecord)this.r).completed();
        this.collectConsent();
        this.phase = this.consentItems.isEmpty() ? Phase.WORK : Phase.CONSENT;
        this.placeCredit = 0.0;
    }

    private void collectConsent() {
        Gate gate = Permission.gateFor((NumenPlayer)this.player);
        BuildSurvey survey = this.survey();
        ArrayList<ConsentItem> items = new ArrayList<ConsentItem>();
        for (BuildTaskRecord.Target target : ((BuildTaskRecord)this.r).targets) {
            if (survey.of(target) != BuildSurvey.State.REACH) continue;
            for (Action action : this.rules.actionsFor(target)) {
                Verdict verdict = gate.judgeLive(action, this.player.serverLevel());
                if (!verdict.asks()) continue;
                items.add(gate.consentItemLive(action, verdict, this.player.serverLevel()));
            }
        }
        this.consentItems = List.copyOf(items);
    }

    private TaskState tickConsent() {
        this.player.controls().stop();
        ConsentAnswer answer = this.consult(this.consentItems);
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            return this.conclude(new Ending(answer.refusal(this.consentItems), FailureType.REFUSED));
        }
        this.consentItems = List.of();
        this.rescanAll();
        this.rebuildOrder();
        this.phase = Phase.WORK;
        return TaskState.RUNNING;
    }

    protected TaskState onTick() {
        if (this.phase == Phase.CONSENT) {
            return this.tickConsent();
        }
        this.updateCompleted();
        if (((BuildTaskRecord)this.r).completed() + this.skippedCells >= ((BuildTaskRecord)this.r).targets.size()) {
            this.rescanAll();
            if (((BuildTaskRecord)this.r).completed() + this.skippedCells >= ((BuildTaskRecord)this.r).targets.size()) {
                return this.conclude(null);
            }
        }
        return this.tickWork();
    }

    private TaskState tickWork() {
        TaskState state = TaskState.RUNNING;
        this.placeCredit += this.cellsPerTick;
        int budget = (int)Math.min(this.placeCredit, 8.0);
        if (budget > 0) {
            state = this.runBatch(budget);
        }
        this.show.stand();
        return state;
    }

    private TaskState runBatch(int budget) {
        ArrayList<BlockPos> touched = new ArrayList<BlockPos>();
        BlockState sample = null;
        boolean passOver = false;
        while (budget > 0 && !this.passStarved) {
            BuildTaskRecord.Target target = this.nearestPendingInLayer();
            if (target == null) {
                if (this.advanceLayer()) continue;
                passOver = true;
                break;
            }
            BlockState placed = this.processCell(target);
            if (placed == null) continue;
            --budget;
            this.placeCredit -= 1.0;
            touched.add(target.pos());
            sample = placed;
        }
        if (!touched.isEmpty()) {
            this.show.performWork(touched, sample);
        }
        if (this.passStarved || passOver) {
            return this.endPass();
        }
        return TaskState.RUNNING;
    }

    private BuildTaskRecord.Target nearestPendingInLayer() {
        BuildTaskRecord.Target best = null;
        int bestStage = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        Vec3 me = this.player.position();
        for (int i = this.layerStart; i < this.layerEnd; ++i) {
            int stage;
            BuildTaskRecord.Target t = this.order.get(i);
            if (this.placedThisLayer.contains(t.pos().asLong()) || (stage = BuildOrder.stage(t)) > bestStage) continue;
            double dx = (double)t.pos().getX() + 0.5 - me.x;
            double dz = (double)t.pos().getZ() + 0.5 - me.z;
            double d = dx * dx + dz * dz;
            if (stage >= bestStage && !(d < bestDist)) continue;
            bestStage = stage;
            bestDist = d;
            best = t;
        }
        if (best != null) {
            this.placedThisLayer.add(best.pos().asLong());
        }
        return best;
    }

    private boolean advanceLayer() {
        int end;
        this.placedThisLayer.clear();
        this.layerStart = this.layerEnd;
        if (this.layerStart >= this.order.size()) {
            return false;
        }
        int y = this.order.get(this.layerStart).pos().getY();
        for (end = this.layerStart; end < this.order.size() && this.order.get(end).pos().getY() == y; ++end) {
        }
        this.layerEnd = end;
        return true;
    }

    private BlockState processCell(BuildTaskRecord.Target target) {
        boolean occupied;
        BlockPos pos = target.pos();
        BlockState desired = target.desiredState();
        if (desired != null && desired.getBlock() instanceof LiquidBlock) {
            return null;
        }
        if (!this.player.level().isLoaded(pos)) {
            return null;
        }
        BlockState current = this.player.level().getBlockState(pos);
        if (target.matches(current)) {
            this.markObserved(target, true);
            return null;
        }
        boolean bl = occupied = !current.isAir() && !(current.getBlock() instanceof LiquidBlock);
        if (this.rules.blockedByEntity(pos, desired)) {
            return null;
        }
        if (this.rules.blockedByMode(target) || this.rules.hopeless(target)) {
            return null;
        }
        int cost = ((BuildTaskRecord)this.r).consumeMaterials && this.rules.costsMaterial(target) ? target.materialCount() : 0;
        List<BuildTaskRecord.CellNeed> needs = this.ledger.needsFor(target);
        if (cost > 0 && !needs.isEmpty()) {
            for (BuildTaskRecord.CellNeed need : needs) {
                if (this.inv.countMatching(need) >= 1) continue;
                this.noteShortage(need.stack().getItem(), 1);
                return null;
            }
        } else if (cost > 0 && !this.inv.hasItems(target.item(), cost, true)) {
            this.noteShortage(target.item(), cost);
            return null;
        }
        if (occupied) {
            if (!WorkProfile.of((ServerPlayer)this.player).instaBreak()) {
                return null;
            }
            if (!this.player.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
                return null;
            }
            ((BuildTaskRecord)this.r).brokeOne(target.removes() != null);
            this.recordCleared(pos);
            if (BuildCellRules.isAirTarget(target)) {
                this.markObserved(target, true);
                return desired;
            }
        }
        if (BuildCellRules.isAirTarget(target)) {
            this.markObserved(target, current.isAir() || current.getBlock() instanceof LiquidBlock);
            return null;
        }
        if (target.itemPlace()) {
            this.placeWithItem(target, pos);
            BlockState now = this.player.level().getBlockState(pos);
            if (!target.matches(now)) {
                return null;
            }
            ((BuildTaskRecord)this.r).placedOne(occupied);
            this.recordPlaced(pos, now);
            this.markObserved(target, true);
            return now;
        }
        if (!this.player.level().setBlock(pos, desired, 11)) {
            return null;
        }
        this.applyBlockEntityData(pos, desired);
        try {
            desired.getBlock().setPlacedBy(this.player.level(), pos, desired, (LivingEntity)this.player, needs.isEmpty() ? new ItemStack((ItemLike)target.item()) : needs.get(0).stack().copy());
        }
        catch (RuntimeException now) {
            // empty catch block
        }
        if (!needs.isEmpty()) {
            if (cost > 0) {
                for (BuildTaskRecord.CellNeed need : needs) {
                    this.inv.consumeMatching(need);
                }
            }
        } else {
            for (int k = 0; k < cost; ++k) {
                this.inv.consumeOne(target.item());
            }
        }
        PlacedBlocks.placedBy((ServerLevel)this.player.serverLevel(), pos, (ServerPlayer)this.player);
        ((BuildTaskRecord)this.r).placedOne(occupied);
        this.recordPlaced(pos, desired);
        this.markObserved(target, true);
        return desired;
    }

    private void noteShortage(Item item, int count) {
        boolean firstShortageThisPass = this.passMissing.isEmpty();
        this.passMissing.merge(item, count, Integer::sum);
        if (firstShortageThisPass && !this.passStarved && !this.canStillAffordAnyPendingCell()) {
            this.passStarved = true;
        }
    }

    private boolean canStillAffordAnyPendingCell() {
        BuildSurvey survey = this.survey();
        for (BuildTaskRecord.Target target : ((BuildTaskRecord)this.r).targets) {
            if (target.matches(this.rules.peek(target.pos())) || this.rules.blockedByMode(target) || this.rules.hopeless(target) || !survey.affordable(target)) continue;
            return true;
        }
        return false;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void placeWithItem(BuildTaskRecord.Target target, BlockPos pos) {
        ItemStack restore = null;
        if (((BuildTaskRecord)this.r).consumeMaterials) {
            int slot = this.inv.findSlot(target.item(), true);
            if (slot < 0) {
                return;
            }
            Hotbar.hold((ServerPlayer)this.player, (int)slot);
        } else {
            restore = this.player.getMainHandItem();
            this.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack((ItemLike)target.item()));
        }
        InputDriver.lookAt((ServerPlayer)this.player, (Vec3)Vec3.atCenterOf((Vec3i)pos));
        try {
            InteractionResult result = this.player.gameMode.useItemOn((ServerPlayer)this.player, this.player.level(), this.player.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf((Vec3i)pos), Direction.UP, pos, false));
            if (result.consumesAction()) {
                this.player.swing(InteractionHand.MAIN_HAND);
            }
        }
        finally {
            if (restore != null) {
                this.player.setItemInHand(InteractionHand.MAIN_HAND, restore);
            }
        }
    }

    private void recordPlaced(BlockPos pos, BlockState state) {
        if (((BuildTaskRecord)this.r).site != null) {
            Built.of(this.player.getServer()).placed(((BuildTaskRecord)this.r).site, this.player.getGameProfile().getName(), this.player.getServer().overworld().getGameTime(), pos, state.getBlock());
        }
    }

    private void recordCleared(BlockPos pos) {
        if (((BuildTaskRecord)this.r).site != null) {
            Built.of(this.player.getServer()).cleared(((BuildTaskRecord)this.r).site, this.player.getServer().overworld().getGameTime(), pos);
        }
    }

    private TaskState endPass() {
        this.rescanAll();
        if (((BuildTaskRecord)this.r).completed() + this.skippedCells >= ((BuildTaskRecord)this.r).targets.size()) {
            return this.conclude(null);
        }
        boolean progressed = ((BuildTaskRecord)this.r).completed() > this.passStartCompleted;
        Constants.LOG.debug("[numen-build] \u6536\u904d {}/{} \u672c\u904d+{} \u7f3a\u6599{} \u65ad\u6599{}", new Object[]{((BuildTaskRecord)this.r).completed(), ((BuildTaskRecord)this.r).targets.size(), ((BuildTaskRecord)this.r).completed() - this.passStartCompleted, this.passMissing.size(), this.passStarved});
        if (this.passStarved || !progressed && !this.passMissing.isEmpty()) {
            return this.conclude(new Ending("built " + ((BuildTaskRecord)this.r).completed() + "/" + ((BuildTaskRecord)this.r).targets.size() + " and ran out \u2014 " + this.ledger.missingReason(this.passMissing), FailureType.NO_MATERIAL));
        }
        if (!progressed) {
            BuildOutstanding outstanding = BuildOutstanding.survey(this.order, this.rules, (LongSet)this.skippedPos, this.player.level(), this.damagedCells);
            outstanding.log(((BuildTaskRecord)this.r).completed(), ((BuildTaskRecord)this.r).targets.size(), this.player.blockPosition(), this.designFrame());
            return this.conclude(new Ending(outstanding.describe(this.designFrame()) + "; built " + ((BuildTaskRecord)this.r).completed() + "/" + ((BuildTaskRecord)this.r).targets.size(), outstanding.failure()));
        }
        return this.conclude(null);
    }

    private Placement designFrame() {
        return ((BuildTaskRecord)this.r).site == null ? null : new Placement(((BuildTaskRecord)this.r).site.anchor(), ((BuildTaskRecord)this.r).site.quarters());
    }

    private TaskState conclude(Ending why) {
        this.player.controls().stop();
        if (why != null) {
            this.fail(why.why(), why.type());
            return TaskState.FAILED;
        }
        return this.finish();
    }

    private int settleWithWorld() {
        Level level = this.player.level();
        ArrayList<BlockPos> built = new ArrayList<BlockPos>();
        for (BuildTaskRecord.Target t : ((BuildTaskRecord)this.r).targets) {
            if (BuildCellRules.isAirTarget(t) || !level.getBlockState(t.pos()).is(t.desiredState().getBlock())) continue;
            built.add(t.pos());
        }
        for (BlockPos pos : built) {
            BlockState current = level.getBlockState(pos);
            if (current.isAir()) continue;
            current.updateNeighbourShapes((LevelAccessor)level, pos, 2);
            level.updateNeighborsAt(pos, current.getBlock());
        }
        int settled = 0;
        for (BuildTaskRecord.Target t : ((BuildTaskRecord)this.r).targets) {
            if (BuildCellRules.isAirTarget(t) || !built.contains(t.pos()) || t.matches(level.getBlockState(t.pos()))) continue;
            ++settled;
        }
        ((BuildTaskRecord)this.r).settledAway(settled);
        if (settled > 0) {
            Constants.LOG.info("[numen-task] build \u843d\u5b9a\u540e {}/{} \u683c\u4e0e\u56fe\u7eb8\u4e0d\u540c(\u7ad9\u4e0d\u4f4f\u7684\u6389\u4e86\u3001\u5f62\u72b6\u6309\u90bb\u5c45\u91cd\u7b97\u4e86)", settled, built.size());
        }
        return settled;
    }

    private TaskState finish() {
        this.player.controls().stop();
        if (((BuildTaskRecord)this.r).completed() + this.skippedCells < ((BuildTaskRecord)this.r).targets.size()) {
            this.note = BuildCompanionTask.remaining(this.survey().tally());
            return TaskState.SUCCESS;
        }
        int popped = this.settleWithWorld();
        this.fixtures.spawnAll();
        this.fixtures.nudgeSurroundingWater(this.siteMin, this.siteMax);
        this.show.celebrate(this.siteMin, this.siteMax);
        ArrayList<String> notes = new ArrayList<String>();
        if (this.skippedCells > 0) {
            notes.add((String)(("left " + this.skippedCells + " cell(s) alone: what is there may not be moved, or the spot cannot be built on")));
        }
        if (popped > 0) {
            notes.add((String)((popped + " cell(s) ended up different once the world settled \u2014 vanilla would not hold them there, or their shape is decided by their neighbours")));
        }
        if (((BuildTaskRecord)this.r).droppedAtLoad > 0) {
            notes.add((String)((((BuildTaskRecord)this.r).droppedAtLoad + " cell(s) of the plan could not be taken along (liquids, or blocks with no item to pay with)")));
        }
        if (this.fixtures.skippedFixtures() > 0) {
            notes.add((String)(("short " + this.fixtures.skippedFixtures() + " fixture(s) (item frames / armour stands / paintings)")));
        }
        if (this.fixtures.skippedPayloads() > 0) {
            notes.add((String)((this.fixtures.skippedPayloads() + " item(s) the blueprint had in its frames / on its armour stands were left out \u2014 those need the exact same item (enchantments and all), so they went up empty")));
        }
        this.note = notes.isEmpty() ? "all requested cells match" : String.join("; ", notes);
        return TaskState.SUCCESS;
    }

    private static BlockPos[] siteBox(NumenPlayer player, List<BuildTaskRecord.Target> targets) {
        if (targets.isEmpty()) {
            BlockPos feet = Feet.cell((ServerPlayer)player);
            return new BlockPos[]{feet, feet};
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BuildTaskRecord.Target target : targets) {
            BlockPos pos = target.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
    }

    private void rebuildOrder() {
        BuildSurvey survey = this.survey();
        ArrayList<BuildTaskRecord.Target> pending = new ArrayList<BuildTaskRecord.Target>();
        for (BuildTaskRecord.Target target : ((BuildTaskRecord)this.r).targets) {
            if (survey.of(target) != BuildSurvey.State.REACH || this.rules.blockedByMode(target)) continue;
            pending.add(target);
        }
        pending.sort(BuildOrder.BUILD_ORDER);
        this.order = pending;
        this.resetLayerWindow();
    }

    private void resetLayerWindow() {
        this.placedThisLayer.clear();
        this.layerStart = 0;
        this.layerEnd = 0;
        if (this.order.isEmpty()) {
            return;
        }
        int y = this.order.get(0).pos().getY();
        while (this.layerEnd < this.order.size() && this.order.get(this.layerEnd).pos().getY() == y) {
            ++this.layerEnd;
        }
    }

    private void computePace() {
        int cells = Math.max(1, ((BuildTaskRecord)this.r).targets.size());
        this.cellsPerTick = BuildOrder.paceFor(cells, ((BuildTaskRecord)this.r).consumeMaterials);
        Constants.LOG.debug("[numen-build] \u8282\u594f {} \u683c,{} \u683c/\u79d2,\u9884\u8ba1 {} \u79d2", new Object[]{cells, String.format("%.1f", this.cellsPerTick * 20.0), (int)((double)cells / this.cellsPerTick / 20.0)});
    }

    private void markObserved(BuildTaskRecord.Target target, boolean completed) {
        if (this.observedCompleted == null) {
            this.observedCompleted = new LongOpenHashSet();
        }
        long key = target.pos().asLong();
        if (completed) {
            this.observedCompleted.add(key);
            this.skippedPos.remove(key);
        } else {
            this.observedCompleted.remove(key);
        }
        this.skippedCells = this.skippedPos.size();
        ((BuildTaskRecord)this.r).completed(this.observedCompleted.size());
    }

    private void updateCompleted() {
        this.rescan(512);
    }

    private void rescanAll() {
        this.rescanCursor = 0;
        this.rescan(((BuildTaskRecord)this.r).targets.size());
    }

    private void rescan(int budget) {
        if (this.observedCompleted == null) {
            this.observedCompleted = new LongOpenHashSet();
        }
        int total = ((BuildTaskRecord)this.r).targets.size();
        int n = Math.min(budget, total);
        Terrain view = Terrain.of((ServerPlayer)this.player);
        for (int k = 0; k < n; ++k) {
            if (this.rescanCursor >= total) {
                this.rescanCursor = 0;
            }
            BuildTaskRecord.Target target = ((BuildTaskRecord)this.r).targets.get(this.rescanCursor++);
            BlockPos pos = target.pos();
            long key = pos.asLong();
            if (!view.loaded(pos.getX(), pos.getZ())) continue;
            BlockState observed = view.state(pos);
            if (target.matches(observed) || target.desiredState().getBlock() instanceof LiquidBlock || BuildCellRules.isAirTarget(target) && observed.getBlock() instanceof LiquidBlock) {
                this.observedCompleted.add(key);
                this.skippedPos.remove(key);
                continue;
            }
            if (this.rules.refused(target) || this.rules.hopeless(target)) {
                this.skippedPos.add(key);
                this.observedCompleted.remove(key);
                continue;
            }
            this.skippedPos.remove(key);
            if (!this.observedCompleted.remove(key)) continue;
            ++this.damagedCells;
        }
        this.skippedCells = this.skippedPos.size();
        ((BuildTaskRecord)this.r).completed(this.observedCompleted.size());
    }

    private void applyBlockEntityData(BlockPos pos, BlockState placed) {
        if (((BuildTaskRecord)this.r).blockEntityData.isEmpty() || !(placed.getBlock() instanceof EntityBlock)) {
            return;
        }
        CompoundTag data = ((BuildTaskRecord)this.r).blockEntityData.get(pos.asLong());
        if (data == null) {
            return;
        }
        BlockEntity be = this.player.level().getBlockEntity(pos);
        if (be == null) {
            return;
        }
        try {
            CompoundTag copy = data.copy();
            copy.putInt("x", pos.getX());
            copy.putInt("y", pos.getY());
            copy.putInt("z", pos.getZ());
            be.load(placed, copy);
            be.setChanged();
            this.player.level().sendBlockUpdated(pos, placed, placed, 2);
        }
        catch (RuntimeException runtimeException) {
            // empty catch block
        }
    }

    public void stop(NumenPlayer companion, Task.StopReason why) {
        super.stop(companion, why);
        this.player.controls().stop();
    }

    protected void cleanup() {
        super.cleanup();
        this.player.controls().releaseAll();
    }

    protected Placed value() {
        Map<Item, Integer> shortfall = ((BuildTaskRecord)this.r).consumeMaterials ? this.ledger.shortfallAgainstInventory(this.ledger.remainingNeed()) : Map.of();
        return new Placed(((BuildTaskRecord)this.r).targets.size(), ((BuildTaskRecord)this.r).targets.size() - ((BuildTaskRecord)this.r).completed() - this.skippedCells, ((BuildTaskRecord)this.r).completed(), ((BuildTaskRecord)this.r).placed(), ((BuildTaskRecord)this.r).replaced(), ((BuildTaskRecord)this.r).broken(), ((BuildTaskRecord)this.r).removed(), Optional.ofNullable(this.building()), this.siteMin == null ? Optional.empty() : Optional.of(List.of(this.siteMin, this.siteMax)), this.damagedCells > 0 ? Optional.of(this.damagedCells) : Optional.empty(), ((BuildTaskRecord)this.r).settledAway() > 0 ? Optional.of(((BuildTaskRecord)this.r).settledAway()) : Optional.empty(), shortfall.isEmpty() ? Optional.empty() : Optional.of(BuildLedger.summarizeShortfall(shortfall)));
    }

    private String building() {
        if (((BuildTaskRecord)this.r).site == null) {
            return null;
        }
        Built.Building b = Built.of(this.player.getServer()).at(((BuildTaskRecord)this.r).site);
        return b == null ? null : b.name();
    }

    private String tally() {
        StringBuilder sb = new StringBuilder("placed ").append(((BuildTaskRecord)this.r).placed());
        if (((BuildTaskRecord)this.r).replaced() > 0) {
            sb.append(" (").append(((BuildTaskRecord)this.r).replaced()).append(" replacing what stood there)");
        }
        sb.append(", cleared ").append(((BuildTaskRecord)this.r).broken());
        if (((BuildTaskRecord)this.r).removed() > 0) {
            sb.append(" (").append(((BuildTaskRecord)this.r).removed()).append(" of them blocks you had put there before)");
        }
        return sb.toString();
    }

    protected String successMessage() {
        String building = this.building();
        return (String)(building == null ? "" : building + ": ") + "built " + ((BuildTaskRecord)this.r).completed() + "/" + ((BuildTaskRecord)this.r).targets.size() + " block(s); " + this.tally() + (String)(this.damagedCells > 0 ? "; " + this.damagedCells + " finished cell(s) were destroyed mid-build by something outside the job and had to be redone" : "") + " (" + this.note + ")";
    }

    protected String timeoutMessage() {
        return "timed out while building; completed " + ((BuildTaskRecord)this.r).completed() + "/" + ((BuildTaskRecord)this.r).targets.size() + " (" + this.note + ")";
    }

    protected String cancelledMessage() {
        return "build interrupted after " + ((BuildTaskRecord)this.r).completed() + "/" + ((BuildTaskRecord)this.r).targets.size() + " block(s)";
    }

    private static enum Phase {
        CONSENT,
        WORK;

    }

    private record Ending(String why, FailureType type) {
    }

    @Doc(value="What one numen.build.place did.")
    public record Placed(int requested, int left, int completed, int placed, int replaced, int cleared, int removed, Optional<String> building, Optional<List<BlockPos>> site, Optional<Integer> destroyedWhileBuilding, Optional<Integer> settledAway, Optional<String> stillShort) {
        public static final Placed NOTHING = new Placed(0, 0, 0, 0, 0, 0, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}

