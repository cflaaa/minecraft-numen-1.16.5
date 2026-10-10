package com.dwinovo.numen.core.entity;

import com.dwinovo.numen.core.NumenCore;
import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.BodyCompat;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Physics;
import com.dwinovo.numen.pathing.body.PlayerHands;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

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

    /** 她的键盘：谁要让身体走、跳、潜行都按它，{@link Physics#step} 每刻落一次。 */
    private final Controls controls = new Controls();

    /**
     * 她的两只手：原版挖掘/放置机制只有一份（{@link PlayerHands}），导航路上挖放与挖矿工具共用同一双。
     *
     * <p>必须**每具身体只有一双**：挖掘进度、右键冷却、正在挖的那一格都挂在它上面，
     * 每次导航现建一双的话，走路走到一半换个动作就会把进度清零。
     */
    private PlayerHands hands;

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

    @Override
    public void tick() {
        // 假玩家死了不会被自动移出玩家列表（没有客户端来发重生包），会永远躺在 0 血。
        // 检测一次，交给善后流程，然后这一 tick 不再走正常的移动/AI。
        if (!deathHandled && (getHealth() <= 0.0F || isDeadOrDying())) {
            deathHandled = true;
            Companions.onDeath(this);
            return;
        }

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
