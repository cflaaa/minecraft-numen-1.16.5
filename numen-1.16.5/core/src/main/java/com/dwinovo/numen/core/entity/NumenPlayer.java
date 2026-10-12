package com.dwinovo.numen.core.entity;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.BodyCompat;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Physics;
import com.dwinovo.numen.pathing.body.PlayerHands;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 同伴的身体：一具服务端假 {@link ServerPlayer}。
 *
 * <p>移植自上游 {@code api/entity/NumenPlayer.java}。上游那份 502 行，里面除了「身体」本身，
 * 还挂着饥饿播报、背包满了播报、装备碎掉播报、主人挨打看护、本能暂停等一大堆<b>同伴级状态</b>——
 * 那些属于后续阶段（见 docs/PORTING.md 的阶段表），这一份先只保留「身体」该有的部分：
 * 身份与归属、死亡处理、每刻物理、存档、皮肤层。
 *
 * <h2>为什么是「玩家」而不是一只生物</h2>
 * 同伴做成玩家之后：原版的交互/战斗代码路径原生可用（对所有模组的兼容性都来自这里）、
 * 有自己的玩家背包、并且因为「在玩家列表里」而白拿区块加载与 playerdata 持久化。
 *
 * <h2>1.16.5 的构造器是四个参数</h2>
 * 上游 1.20.1 是 {@code super(server, level, profile)} 三参；1.16.5 的
 * {@code ServerPlayer} 构造器多一个 {@link ServerPlayerGameMode}：
 * <pre>
 *   ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ServerPlayerGameMode)
 * </pre>
 * 这是 1.16.x 独有的形状，1.17 起又变回三参。<b>这一行是整个移植里最容易被忽略的一处</b>，
 * 漏了它编译期会直接报「找不到符号」。
 */
public final class NumenPlayer extends ServerPlayer implements Body {

    private static final String NBT_KEY_OWNER = "NumenOwner";

    /** 主人的玩家 UUID。只在第一次赋值之前短暂为 null。 */
    private UUID ownerUuid;

    /** 这具身体的死亡已经处理过了，保证善后只跑一次。 */
    private boolean deathHandled;

    /**
     * 上一次 tick 见到的维度；变了就是穿过了传送门。出生时置 null，头一 tick 只记账不播报。
     * 上游挂 Fabric 的 {@code AFTER_PLAYER_CHANGE_WORLD}，1.16.5 没有那个事件，改为实体
     * tick 里比对（和既有的 poll 一套路子）。
     */
    private ResourceKey<Level> lastDimension;

    /** 她的键盘：谁要让身体走、跳、潜行都按它，{@link Physics#step} 每刻落一次。 */
    private final Controls controls = new Controls();

    /**
     * 她的两只手：原版挖掘/放置机制只有一份（{@link PlayerHands}），导航路上挖放与挖矿工具共用同一双。
     *
     * <p>必须**每具身体只有一双**：挖掘进度、右键冷却、正在挖的那一格都挂在它上面，
     * 每次导航现建一双的话，走路走到一半换个动作就会把进度清零。
     */
    private PlayerHands hands;

    /** 她这一侧的假客户端：原版交互回路（{@code ServerGamePacketListenerImpl} 那一套）要的那份「客户端」。 */
    private final FakeClient fakeClient = new FakeClient(this);

    /**
     * 挂在这具身体上的同伴级状态,按类型各一份(征询登记处、等主人答复的调用之类)。
     *
     * <p>与上游同一原则——<b>跟着身体走,不进静态表</b>:身体没了状态就没了,休眠回来是新身体、
     * 新状态,不用给每一种状态各配一套离场清理;引擎不认识调用方的类型,所以按类型取、
     * 首次取时由调用方建。（照上游 {@code entity/NumenPlayer#state} 逐字搬，2026-10-11 权限层归位。）
     */
    private final java.util.Map<Class<?>, Object> bodyState = new java.util.HashMap<>();

    /** 取(首次取时建)这具身体上的一份同伴级状态。 */
    public <T> T state(Class<T> type, java.util.function.Supplier<T> init) {
        return type.cast(bodyState.computeIfAbsent(type, k -> init.get()));
    }

    public NumenPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        // 1.16.5：四参。见类注释。
        super(server, level, profile, new ServerPlayerGameMode(level));
        // 这一代 ServerPlayer 构造时把迈步高度设成 1.0（迁就客户端的自动起跳），
        // 真玩家是 0.6。不修的话一格高的台阶会被直接迈上去，寻路算出来的路也跟着错。
        BodyCompat.walkLikeAPlayer(this);
    }

    /**
     * 点亮全部皮肤覆盖层（帽子/夹克/左右袖/左右裤腿）与披风。
     *
     * <p>假玩家没有客户端上报的模型定制，不设这个字节客户端只渲染单层基础皮肤。
     * 该字节是同步实体数据、不随 .dat 存取，所以每次进世界都要重设一次。
     *
     * <p>1.16.5 上 {@code DATA_PLAYER_MODE_CUSTOMISATION} 是 {@code Player} 的私有字段，
     * 靠 {@code numen.accesswidener} 打开；1.20.1 上它已经是 protected，上游不需要额外声明。
     */
    public void showAllSkinLayers() {
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
    }

    /** The loaded companion body with this UUID, or {@code null} if not spawned. */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        return server.getPlayerList().getPlayer(uuid) instanceof NumenPlayer companion ? companion : null;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    /** 跨维度安全的归属判断——比 UUID，不用原版那套依赖当前维度的 {@code isOwnedBy}。 */
    public boolean isOwnedByPlayer(UUID playerUuid) {
        return ownerUuid != null && ownerUuid.equals(playerUuid);
    }

    /** 主人此刻的在线玩家对象；离线时 null。 */
    public ServerPlayer resolveOwnerPlayer() {
        return ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
    }

    /** 主人的名字，给人读的；没有主人或名字查不到时是空串。 */
    public String ownerName() {
        return ownerUuid == null ? "" : playerName(server, ownerUuid);
    }

    /**
     * 一个玩家的名字，给人读的：在线就直接取，否则查服务器的档案缓存；查不到是空串。
     *
     * <p><b>1.16.5 的 {@code GameProfileCache.get(UUID)} 返回的是 {@code GameProfile}
     * 而不是 {@code Optional}。</b>上游 1.20.1 那一侧是 Optional，可以直接链式
     * {@code flatMap(...)}；这一代要判 null。这是很容易照抄错的一处。
     */
    public static String playerName(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server.getProfileCache() == null) {
            return "";
        }
        GameProfile profile = server.getProfileCache().get(player);
        return profile == null ? "" : profile.getName();
    }

    /**
     * 上船那一刻把身体朝向对齐船头。
     *
     * <p>真客户端在 {@code handleSetEntityPassengersPacket} 里做这件事，服务端身体没有那个包——
     * 不补的话她背对船头坐下，而船的转向又从她「现在朝哪」没有任何约束，画面立刻穿帮。
     * 与 Carpet 的假玩家同一处理。
     */
    @Override
    public boolean startRiding(net.minecraft.world.entity.Entity vehicle, boolean force) {
        if (!super.startRiding(vehicle, force)) {
            return false;
        }
        if (vehicle instanceof net.minecraft.world.entity.vehicle.Boat) {
            // 1.16.5 没有 getYRot()/setYRot()：Entity.yRot/xRot 是公开字段，直接读写。
            // （1.17 起才被 getRot()/setRot()/getYRot()/setYRot() 这一套取代。）
            yRotO = vehicle.yRot;
            yRot = vehicle.yRot;
            setYHeadRot(vehicle.yRot);
        }
        return true;
    }

    /** 死因，在 {@link #die} 里趁早抄下来。见方法注释。 */
    private String deathMessage;

    /**
     * 死因在这里抄下来——原版广播死亡消息也是在这一刻（清空战斗记录之前）。
     *
     * <p>不能等到 tick 里再问战斗记录：原版 {@code ServerPlayer.die()} 的最后一行是
     * {@code getCombatTracker().recheckStatus()}，玩家已死就把记录清空。我们的死亡检测是 tick 轮询，
     * 跑到的时候记录早没了，{@code getDeathMessage()} 只能返回兜底的
     * {@code death.attack.generic}——「她死了」，没有凶手。
     */
    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        this.deathMessage = getCombatTracker().getDeathMessage().getString();
        super.die(cause);
    }

    /** 上一次的死因（原版死亡消息原文）；还没死过则 null。 */
    public String deathMessage() {
        return deathMessage;
    }

    /**
     * 把她从死亡里拉回来（原地复活）。
     *
     * <p>假玩家没有客户端，不会自己发重生包，所以「死了就永远躺着」——必须由我们接手。
     *
     * <p>三件事都要做，缺一不可：
     * <ul>
     *   <li>{@code setHealth}：{@code isDeadOrDying()} 就是 {@code getHealth() <= 0}，血量不回就还是死的；</li>
     *   <li>{@code deathTime = 0}：原版用这个计数播放死亡动画与延迟移除，不清会继续涨；</li>
     *   <li>{@link #deathHandled} 复位：否则下一轮死亡不会再被 tick 里的检测捕获。</li>
     * </ul>
     *
     * <p>1.16.5 没有 {@code reviveCaps()}（那是 1.17 之后才有、且后来又被删掉的东西），
     * 这一代也不需要——能力值由 {@code onUpdateAbilities} 自己维护。
     */
    public void revive() {
        this.deathHandled = false;
        this.deathTime = 0;
        setHealth(getMaxHealth());
        clearFire();
    }

    // ---- 身体端口 ----

    @Override
    public ServerPlayer entity() {
        return this;
    }

    @Override
    public Controls controls() {
        return controls;
    }

    /** 她的两只手（首次取时建，之后一直是同一双）。见字段注释。 */
    public PlayerHands hands() {
        if (hands == null) {
            hands = new PlayerHands(this);
        }
        return hands;
    }

    // ─────────────────── 背包满了播报（上游 NumenPlayer 里同一段，逐字搬）───────────────────

    /** 这一轮「背包满了」已经说过了。背包满是持续状态，掉落物每刻都在碰她——不去抖就是每刻一条；背包里又有空格了才重新武装。 */
    private boolean fullReported;

    /** 因背包放不下而留在地上、还没说出去的那一件；没有是 null。 */
    private LeftBehind leftBehind;

    /** 一件因背包放不下而留在地上的东西：是什么、在哪一格。 */
    public record LeftBehind(ItemStack stack, BlockPos pos) {}

    /**
     * 一件掉落物碰到了她，原版正要往背包里放（由 {@code ItemEntityTouchMixin} 交来）。放不放得下照原版背包找格子的两步：
     * 有一格还能叠上它（{@code getSlotWithRemainingSpace}），或者有空格（{@code getFreeSlot}）；创造模式什么都收。
     * 放不下的记下来，由 {@link #pollInventoryFull} 交出去。「满了」只在这里判：挖、捡、合成这些活不各自判。
     */
    public void touchedItem(ItemEntity item) {
        if (fullReported || leftBehind != null || abilities.instabuild) {
            return;
        }
        Inventory inventory = this.inventory;
        ItemStack stack = item.getItem();
        if (inventory.getSlotWithRemainingSpace(stack) != -1 || inventory.getFreeSlot() != -1) {
            return;
        }
        leftBehind = new LeftBehind(stack.copy(), item.blockPosition());
    }

    /**
     * 这一刻该不该跟主人说「背包满了、东西留在了地上」。每服务端 tick 问一次（见 {@code CompanionTickDispatcher}）。
     * <b>一轮只说一次</b>，复位见 {@link #rearmInventoryFull}。
     */
    public LeftBehind pollInventoryFull() {
        if (leftBehind == null) {
            return null;
        }
        LeftBehind told = leftBehind;
        leftBehind = null;
        fullReported = true;
        return told;
    }

    /**
     * 背包里又有了空格就重新武装：有空格就什么都放得下，下一回再满、再有东西放不下时再说；还没说出去的那一件也作废。
     * 在自己的实体刻里、捡东西之前看——腾出的那一格要是这一刻就被捡起的东西占上，事后再看就看不见它空过。
     */
    private void rearmInventoryFull() {
        if (inventory.getFreeSlot() != -1) {
            fullReported = false;
            leftBehind = null;
        }
    }

    // ─────────── 饥饿 / 醒来 / 主人挨打 / 本能暂停（上游 NumenPlayer 的同一段，逐字搬）───────────

    /** 上一刻她在不在床上，{@link #pollWokeUp} 用它比出「刚醒」这一刻。 */
    private boolean sleepingLastTick;

    /** 这一轮「我饿了」已经说过了。回到 {@link #FED_LEVEL} 以上才重新武装。 */
    private boolean hungerReported;

    /** 饿到这个数以下才值得说。 */
    private static final int HUNGRY_LEVEL = 6;

    /** 吃回这个数以上才重新武装（迟滞，免得在阈值上一条接一条）。 */
    private static final int FED_LEVEL = 14;

    /** 被任务按住的本能。按住是临时的，{@link #resumeAllReflexes} 一次全解除。 */
    private java.util.Set<String> pausedReflexes = java.util.Set.of();

    /** 主人血量的看护（纯判定在 {@link OwnerHurtWatch}，便于无头单测）。 */
    private final OwnerHurtWatch ownerWatch = new OwnerHurtWatch();

    /** 一次「主人挨打」报告：攻击者、血量与档位（urgent = 跌进危险区）。 */
    public record OwnerHurt(String attacker, float hp, float maxHp, boolean urgent) {}

    /**
     * 她是不是<b>刚好在这一刻</b>从床上醒了。每服务端 tick 问一次（见 {@code CompanionTickDispatcher}）。
     * 死着的时候不算醒——原版 {@code LivingEntity.die} 会先把睡眠停掉，不挡的话她每次死在床上都会多出一条
     * 「你醒了」贴在死亡事件旁边。
     */
    public boolean pollWokeUp() {
        if (!isAlive()) {
            sleepingLastTick = false;
            return false;
        }
        boolean now = isSleeping();
        boolean woke = sleepingLastTick && !now;
        sleepingLastTick = now;
        return woke;
    }

    /**
     * 这一刻该不该跟主人说「我饿了」。<b>一轮饥饿只说一次</b>：说过就闭嘴，吃回 {@link #FED_LEVEL}
     * 以上才重新武装。她<b>不会自己吃</b>——饿了是主人该知道的事，交互本身就是目的。
     */
    public boolean pollGotHungry() {
        int food = getFoodData().getFoodLevel();
        if (food >= FED_LEVEL) {
            hungerReported = false;
            return false;
        }
        if (food > HUNGRY_LEVEL || hungerReported || !isAlive()) {
            return false;
        }
        hungerReported = true;
        return true;
    }

    /**
     * 主人这一刻有没有挨打值得说。每服务端 tick 问一次；主人不在线传 null，基线即重置。
     * 只报实体攻击，分档与去抖全在 {@link OwnerHurtWatch}。
     *
     * <p><b>1.16.5 差异</b>：上游用 {@code owner.getLastDamageSource().getEntity()}；这一代
     * {@code Entity} 没有 {@code getLastDamageSource()}，改用 {@code LivingEntity.getLastHurtByMob()}
     * （同一语义：最近一次伤害的实体攻击者）。
     */
    public OwnerHurt pollOwnerHurt(ServerPlayer owner, long now) {
        if (owner == null) {
            ownerWatch.reset();
            return null;
        }
        net.minecraft.world.entity.LivingEntity attacker = owner.getLastHurtByMob();
        OwnerHurtWatch.Verdict verdict = ownerWatch.poll(
                owner.getUUID(), owner.getHealth(), attacker != null, now);
        if (verdict == OwnerHurtWatch.Verdict.NONE) {
            return null;
        }
        String label = attacker instanceof net.minecraft.world.entity.player.Player p
                ? p.getGameProfile().getName()
                : Registry.ENTITY_TYPE.getKey(attacker.getType()).getPath();
        return new OwnerHurt(label, owner.getHealth(), owner.getMaxHealth(),
                verdict == OwnerHurtWatch.Verdict.DANGER);
    }

    /** 按住一条本能。见 {@link #pausedReflexes}。 */
    public void pauseReflex(String reflexId) {
        if (pausedReflexes.contains(reflexId)) {
            return;
        }
        java.util.Set<String> next = new java.util.HashSet<>(pausedReflexes);
        next.add(reflexId);
        pausedReflexes = java.util.Set.copyOf(next);
    }

    /** 这条本能这一刻被按住了吗。 */
    public boolean reflexPaused(String reflexId) {
        return pausedReflexes.contains(reflexId);
    }

    /** 她闲下来了，全部解除——按住是临时的，不必谁去显式还。 */
    public void resumeAllReflexes() {
        pausedReflexes = java.util.Set.of();
    }

    /** 她这一侧的假客户端（原版交互回路要的那份「客户端」）。 */
    public FakeClient fakeClient() {
        return fakeClient;
    }

    // ─────────── 用坏的装备（上游 NumenPlayer 的同一段，BodyDelta 要）───────────

    /** 一件在她身上用坏的装备：是什么、坏在哪个装备位。 */
    public record BrokenGear(net.minecraft.world.item.Item item,
                             net.minecraft.world.entity.EquipmentSlot slot) {}

    /** 最近用坏的装备，按先后；只留最近 {@link #BROKEN_KEPT} 件。 */
    private final java.util.ArrayList<BrokenGear> brokenGear = new java.util.ArrayList<>();
    /** 到现在一共用坏过几件：{@link #brokenGearMark} 读它，{@link #brokenGearSince} 拿它算新坏的是哪几件。 */
    private long brokenGearTotal;
    private static final int BROKEN_KEPT = 16;

    /**
     * 原版装备耐久耗尽、碎掉的那一刻（挖掘、打击、盾挡、鞘翅……都经 {@code ItemStack.hurtAndBreak} 到这里）。
     * 记下来，干活的人事后用 {@code BodyDelta} 说给她听；这里只记流水，不替谁下结论。
     */
    @Override
    public void broadcastBreakEvent(net.minecraft.world.entity.EquipmentSlot slot) {
        super.broadcastBreakEvent(slot);
        if (brokenGear.size() == BROKEN_KEPT) {
            brokenGear.remove(0);
        }
        brokenGear.add(new BrokenGear(getItemBySlot(slot).getItem(), slot));
        brokenGearTotal++;
    }

    /** 此刻用坏过几件的读数；之后问 {@link #brokenGearSince} 就是这个读数以来新坏的。 */
    public long brokenGearMark() {
        return brokenGearTotal;
    }

    /** 读数 {@code mark} 以来用坏的装备，按先后。 */
    public java.util.List<BrokenGear> brokenGearSince(long mark) {
        int fresh = (int) Math.min(brokenGearTotal - mark, brokenGear.size());
        return java.util.List.copyOf(brokenGear.subList(brokenGear.size() - fresh, brokenGear.size()));
    }

    @Override
    public void tick() {
        // 假玩家死了不会被自动移出玩家列表（没有客户端来发重生包），会永远躺在 0 血。
        // 检测一次，交给善后流程，然后这一 tick 不再走正常的移动/AI。
        if (!deathHandled && (getHealth() <= 0.0F || isDeadOrDying())) {
            deathHandled = true;
            Companions.onDeath(this);
            return;
        }
        rearmInventoryFull();

        try {
            super.tick();
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }

        // 她没有客户端：按着的键落成输入、玩家自己的一刻、摔伤结算、移动统计、区块跟随，
        // 原版由客户端与网络层替真玩家做的这一趟，由 {@link Physics#step} 在这里补上，每刻一次。
        try {
            Physics.step(this);
        } catch (RuntimeException ex) {
            reportTickFailure(ex);
        }

        // 穿过了传送门（ upstream 挂 ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD，
        // 1.16.5 的 Fabric API 没有这个事件）——和本仓既有的 poll 同一套路：眼见为实，
        // 每次 tick 比一次维度，变了就是过了门。
        ResourceKey<Level> dim = level.dimension();
        if (lastDimension == null) {
            lastDimension = dim;   // 首次进世界不算「进入」：出生不该播报
        } else if (!dim.equals(lastDimension)) {
            lastDimension = dim;
            try {
                Companions.onDimensionChanged(this);
            } catch (RuntimeException ex) {
                reportTickFailure(ex);
            }
        }
    }

    /** 同一具身体只吵一次：tick 每秒二十下，真炸起来就是每秒二十条，日志立刻没法看。 */
    private boolean tickFailureLogged;

    /**
     * 这一 tick 炸了：咽下去，记一次。
     *
     * <h2>为什么不能让它冒上去</h2>
     * 异常冒到 {@code ServerLevel} 的实体 tick 循环，原版会包成 {@code ReportedException} 掀掉
     * 整个服务端主循环——看门狗六十秒后判定崩溃强制关服，一屋子玩家一起掉线。
     *
     * <h2>最常见的抛出方不是我们</h2>
     * 同伴以「玩家」身份待在玩家列表里，别的模组收到它的生命周期事件时，没想过这个「玩家」没有
     * 真实连接。这一类我们永远堵不完，只能不让它掀桌子。
     *
     * <p>代价完全不对等：咽下去是这一 tick 白跑；不咽是整台服务器没了。
     */
    private void reportTickFailure(RuntimeException ex) {
        if (tickFailureLogged) {
            return;
        }
        tickFailureLogged = true;
        NumenCore.error("同伴 {} 的 tick 抛了异常，这一 tick 跳过（之后不再重复记这一条）", getUUID(), ex);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag output) {
        super.addAdditionalSaveData(output);
        if (ownerUuid != null) {
            output.putUUID(NBT_KEY_OWNER, ownerUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        if (input.hasUUID(NBT_KEY_OWNER)) {
            this.ownerUuid = input.getUUID(NBT_KEY_OWNER);
        }
    }
}
