package com.dwinovo.numen.core.entity;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.agent.CompanionBrain;
import com.dwinovo.numen.core.api.CompanionEvent;
import com.dwinovo.numen.core.api.NumenPlugins;
import com.dwinovo.numen.core.api.gear.GearSlot;
import com.dwinovo.numen.core.data.ModLanguageData;
import com.dwinovo.numen.core.network.NumenNetwork;
import com.dwinovo.numen.core.network.payload.CompanionListPayload;
import com.dwinovo.numen.core.network.payload.NumenDeathPayload;
import com.dwinovo.numen.core.network.payload.NumenEventPayload;
import com.dwinovo.numen.core.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.core.event.NumenEvents;
import com.dwinovo.numen.core.platform.ServerLifecycle;
import com.dwinovo.numen.agent.inbox.EventTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同伴的花名册与生命周期。
 *
 * <p>移植自上游 {@code api/entity/Companions.java} 的职责部分（上游那份还挂着征询、
 * 皮肤、跨维度跟随等，那些是后续阶段）。
 *
 * <p>花名册按 <b>身体 UUID</b> 索引，不按名字：名字只是给人看的，UUID 才和 playerdata、
 * 网络身份一一对应。身体没了（死亡遣散、服务器关）就顺手把大脑一起丢掉——
 * 这正是上游「状态跟着身体走、不进静态表」那条原则的最小实现。
 */
public final class Companions {

    private Companions() {}

    /** 身体 UUID → 大脑。一具身体一个大脑，身体走了大脑一起走。 */
    private static final Map<UUID, CompanionBrain> BRAINS = new LinkedHashMap<>();

    /**
     * 死掉、等着本 tick 之后收掉的尸体（身体 UUID）。
     *
     * <p>{@link #onDeath} 跑在实体 tick 里（{@code NumenPlayer.tick} → {@code ServerLevel.tickNonPassenger}），
     * 那一刻调 {@code PlayerList.remove} 会撞原版「Removing entity while ticking!」的保护。攒到这里，
     * 由 {@link #tick}（挂在 {@code END_SERVER_TICK}，本 tick 的实体刻已全部走完）统一收。
     *
     * <p><b>别再退回 {@code server.execute}</b>：同线程时它当场内联执行，等于没推迟。
     * 世界作废时随其它排队状态一起清掉。
     */
    private static final java.util.Set<UUID> PENDING_DESPAWN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 开服:下一个服务器刻把**服务器同伴**(无主)放回世界。此刻世界刚就绪,照 JOIN 的同款推迟一拍。 */
    private static volatile boolean serverRestorePending;

    public static void scheduleServerRestore() {
        serverRestorePending = true;
    }

    /**
     * 同伴 UUID → 主人 UUID。
     *
     * <p>给「大脑在服务端」这一侧按主人分东西用——模块目录 {@code lua/<主人>/} 就是她
     * （上游那侧是客户端， Modules.init 直接问登录账号；这一侧多几个主人，得记下谁是谁的）。
     * 每具身体出生时写（{@link NumenPlayer#setOwnerUuid} 是唯一赋值点，召唤与复活都经过它），
     * 永久除名时清。看到 {@code null} 按「还谈不上归属」处理即可。
     */
    private static final Map<UUID, UUID> OWNERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 这只同伴的主人；没记过是 null。 */
    public static UUID ownerOf(UUID companion) {
        return OWNERS.get(companion);
    }

    /** 记下归属（{@link NumenPlayer#setOwnerUuid} 调）。 */
    public static void rememberOwner(UUID companion, UUID owner) {
        if (companion != null && owner != null) {
            OWNERS.put(companion, owner);
        }
    }

    /** 除名时忘掉归属。 */
    public static void forgetOwner(UUID companion) {
        OWNERS.remove(companion);
    }

    /** 死亡之后隔多久把她放回主人身边（毫秒）。 */
    private static final long RESPAWN_DELAY_MS = 5_000L;

    // ---- 召唤 / 遣散 ----

    /**
     * 在主人所在的位置召唤一只同伴。
     *
     * <p>⚠️ 必须走 {@link #summon(MinecraftServer, UUID, String, ServerLevel, Vec3)}：只有它会把同伴
     * <b>入册</b>（{@link CompanionRegistry}）。上游没有这个 {@code spawn}，只有 {@code summon}；
     * 本仓早年自写的 {@code spawn} 绕过了注册表，于是从这里（MCP create、{@code /numen summon}、面板服务）出来的同伴
     * <b>不落盘、不复活、不上花名册</b>——2026-10-16 真机发现：{@code /kill} 后不进待复活。
     *
     * @return 新身体；重名时返回 {@code null}
     */
    public static NumenPlayer spawn(MinecraftServer server, ServerPlayer owner, String name) {
        if (findByName(server, name) != null) {
            return null;
        }
        // 委托给入册的那条路（安全落脚点/入册/皮肤注入都在里面）。
        return summon(server, owner.getUUID(), name, owner.getLevel(), owner.position());
    }

    /**
     * 召唤主人 {@code ownerUuid} 名下叫 {@code name} 的同伴。<b>按 (主人, 名字) 幂等</b>：
     * 已经有一只就叫它——活着 → 原样返回；休眠 → 放回世界。只有名字下没有同伴时才新造一具。
     * （早年「每次召唤都发新 UUID」会造出同名的一群，它们登录时还一起复活——那就是重复同伴 bug。）
     *
     * <p>1.16.5 适配：同伴 UUID 由名字推导（{@link CompanionFactory#offlineUuid}），不是
     * {@code UUID.randomUUID()}——playerdata 按 UUID 落 {@code .dat}，名字不变则永远是同一个人。
     * 另外出生不带坐标（原版 {@code placeNewPlayer} 按世界出生点安置），造完之后
     * {@code teleportTo} 到落脚点，和 {@link #spawn} 同一条路。
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                     ServerLevel level, Vec3 pos) {
        return summon(server, ownerUuid, name, level, pos, null);
    }

    /**
     * {@link #summon(MinecraftServer, UUID, String, ServerLevel, Vec3)} 再加一身借来的皮肤
     * （Mojang 签名的 textures，见 {@link MojangSkins}）。重复召唤携带皮肤 = <b>换肤</b>：
     * 注册表更新后，休眠体这次重建就生效，活体等下次重建。
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                     ServerLevel level, Vec3 pos, MojangSkins.Skin skin) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            if (skin != null) {
                CompanionRegistry.Entry e = reg.find(existing);
                if (e != null) {
                    reg.put(existing, e.withSkin(skin.value(), skin.signature()));
                }
                // 换肤必须重建身体才看得见（GameProfile 只在构造时注入）：活体先落盘休眠，
                // 紧接着的 respawn 立即以带新皮肤的档案重建，位置物品全保留。
                NumenPlayer live = NumenPlayer.findByUuid(server, existing);
                if (live != null) {
                    dormant(server, live);
                }
            }
            NumenPlayer body = respawn(server, existing);
            if (body != null) {
                return body;
            }
            reg.remove(existing);   // stale entry（没有 .dat）——换掉它
        }
        UUID companionUuid = CompanionFactory.offlineUuid(name);
        Vec3 safe = SafeSpawn.findNear(level, pos);
        if (safe != null) {
            pos = safe;   // 周围找不到站得下的地方 → 保留召唤者自己的坐标
        }
        // 先入册再造体：CompanionFactory.spawn 从注册表读皮肤，所有出生路径共用一个注入点。
        CompanionRegistry.Entry fresh = new CompanionRegistry.Entry(
                name, ownerUuid, level.dimension(), new BlockPos(pos.x, pos.y, pos.z));
        if (skin != null) {
            fresh = fresh.withSkin(skin.value(), skin.signature());
        }
        reg.put(companionUuid, fresh);
        NumenPlayer body = CompanionFactory.spawn(server, level, ownerUuid, name);
        body.teleportTo(pos.x, pos.y, pos.z);
        BRAINS.put(body.getUUID(), new CompanionBrain(body));
        reg.put(companionUuid, fresh.movedTo(level.dimension(), body.blockPosition()));
        Constants.info("同伴 {} 已召唤，主人 {}", name, ownerUuid);
        return body;
    }

    /** 主人名下叫这个名字的同伴，没有则 null。判断规则在 {@link CompanionRoster#findByName}
     *  (纯 JVM,可单测)；这里只负责把注册表摊平成它认得的形状。 */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        return CompanionRoster.findByName(rowsOf(server, ownerUuid), name);
    }

    /** 遣散一只同伴。 */
    public static boolean dismiss(MinecraftServer server, String name) {
        NumenPlayer body = findByName(server, name);
        if (body == null) {
            return false;
        }
        CompanionBrain brain = BRAINS.remove(body.getUUID());
        if (brain != null) {
            brain.halt("被主人遣散");
        }
        CompanionFactory.despawn(server, body);
        Constants.info("同伴 {} 已遣散", name);
        return true;
    }

    // ---- 查询 ----

    /** 按名字找已经加载的同伴身体；没有则 null。 */
    public static NumenPlayer findByName(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion
                    && companion.getGameProfile().getName().equalsIgnoreCase(name)) {
                return companion;
            }
        }
        return null;
    }

    /** 此刻世界里所有同伴。 */
    public static List<NumenPlayer> all(MinecraftServer server) {
        List<NumenPlayer> out = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion) {
                out.add(companion);
            }
        }
        return out;
    }

    /** 这具身体的大脑；没登记过则 null。 */
    public static CompanionBrain brain(NumenPlayer body) {
        return BRAINS.get(body.getUUID());
    }

    /** 按 UUID 找已经加载的同伴身体；没有则 null。给外接大脑（MCP）那条路用。 */
    public static NumenPlayer findByUuid(MinecraftServer server, UUID uuid) {
        if (uuid == null) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof NumenPlayer companion && companion.getUUID().equals(uuid)) {
                return companion;
            }
        }
        return null;
    }

    /** 按 UUID 遣散一只同伴。 */
    public static boolean dismissByUuid(MinecraftServer server, UUID uuid) {
        NumenPlayer body = findByUuid(server, uuid);
        return body != null && dismiss(server, body.getGameProfile().getName());
    }

    /**
     * 第一个可以当主人的在线玩家（非同伴）；没有则 null。
     *
     * <p>给外接大脑的 {@code create_companion} 用：MCP 服务端开在<b>服务端</b>上，
     * 未必存在「发起召唤的那个玩家」，而同伴必须有归属。多个玩家在线时取玩家列表里的第一个——
     * 单机与小服够用；将来要做「选谁当主人」再改这一处。
     */
    public static ServerPlayer firstOwner(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer)) {
                return player;
            }
        }
        return null;
    }

    /**
     * 召唤一只**服务器同伴**（无主）：落在主世界出生点。控制台 / MCP 用，不要求任何玩家在场。
     * 无主同伴的权限模式默认 {@code AUTO}（自动同意、deny 仍生效）。
     */
    public static NumenPlayer summonServer(MinecraftServer server, String name) {
        ServerLevel overworld = server.overworld();
        BlockPos sp = overworld.getSharedSpawnPos();
        return summon(server, /*owner=*/null, name, overworld,
                new Vec3(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5));
    }

    // ---- 每刻驱动 ----

    /** 服务端每刻调一次：让每只同伴的大脑往前走一步。 */
    public static void tick(MinecraftServer server) {
        if (serverRestorePending) {
            serverRestorePending = false;
            respawnAllServerOwned(server);
        }
        // 被拦下的跨维度传送：在实体刻之外补执行（#0008，与 drainPendingDespawns 同一理由）。
        drainPendingDimensionChanges(server);
        // 先收尸：这一刻之前死掉的，趁实体刻已结束移出世界（见 PENDING_DESPAWN）。
        drainPendingDespawns(server);
        if (BRAINS.isEmpty()) {
            return;
        }
        // 复制一份再遍历：大脑在这一步里可能把自己摘掉（死亡、被遣散）。
        for (CompanionBrain brain : new ArrayList<>(BRAINS.values())) {
            try {
                brain.tick();
            } catch (RuntimeException ex) {
                // 一只同伴的大脑炸了不能连累别人，更不能掀掉服务端主循环。
                Constants.error("同伴 {} 的大脑 tick 抛了异常，跳过这一步", brain.name(), ex);
            }
        }
    }

    /**
     * 把同伴在实体刻内被拦下的跨维度传送补执行（#0008）。
     *
     * <p>挂在 {@link #tick}（END_SERVER_TICK）里，此刻实体刻已全部结束——原版
     * {@code changeDimension → ServerLevel.removePlayer} 在这里合法（真人玩家的同一条路径
     * 本来就跑在实体刻之外）。执行后补一发 {@code setPortalCooldown()}：原版这条路径不显式设冷却，
     * 不补的话她在下界落点的新门里可能立刻被弹回。
     */
    private static void drainPendingDimensionChanges(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof NumenPlayer companion)) continue;
            ServerLevel dest = companion.numen$pendingDimension;
            if (dest == null) continue;
            companion.numen$pendingDimension = null;
            companion.numen$deferredChangeRunning = true;
            try {
                companion.changeDimension(dest);
                companion.setPortalCooldown();
                Constants.info("[numen-portal] {} 已延迟传送 → {}", companion.getName().getString(),
                        dest.dimension().location());
            } catch (RuntimeException ex) {
                Constants.error("[numen-portal] {} 延迟传送失败（去 {}）", companion.getName().getString(),
                        dest.dimension().location(), ex);
            } finally {
                companion.numen$deferredChangeRunning = false;
            }
        }
    }

    /**
     * 把 {@link #PENDING_DESPAWN} 里的尸体移出世界。挂在 {@link #tick} 的最前，此刻实体刻已全部结束，
     * {@code PlayerList.remove} 不会撞「Removing entity while ticking!」。
     */
    private static void drainPendingDespawns(MinecraftServer server) {
        if (PENDING_DESPAWN.isEmpty()) {
            return;
        }
        List<UUID> dead = new ArrayList<>(PENDING_DESPAWN);
        PENDING_DESPAWN.removeAll(dead);
        for (UUID uuid : dead) {
            NumenPlayer body = NumenPlayer.findByUuid(server, uuid);
            if (body != null) {
                CompanionFactory.despawn(server, body);
            }
        }
    }

    // ---- 死亡善后 ----

    /**
     * 她刚死了（在 {@link NumenPlayer#tick} 里检测到）。死亡本身完全交给原版——掉落、
     * 墓碑模组、keepInventory 都照常生效，因为这是一次真的 ServerPlayer 死亡。
     * 我们只做四件事：把死讯发给主人（他的循环据此停牌 DEAD）、触发生命周期钩子
     * （排程器丢活）、把死亡记进世界存档的注册表（5 秒后回主人身边）、把尸体在这 tick 之后收掉。
     *
     * <p>顺序要紧：先发死讯再触发点——死讯一到，客户端的循环就停牌 DEAD；此后钩子里产生的
     * 收尾事件（异步任务的 task_finished status="interrupted"）照样进队列，但不开 run，
     * 安静躺到复活。反过来的话，那条 urgent 收尾事件会在停牌之前到达、当场开一轮，而紧接着
     * 的死讯又把那一轮整个作废——白烧一次请求，还多一条没人看的对话。
     *
     * <p>1.16.5 适配：{@code body.level()} → {@code body.level} 字段。
     */
    public static void onDeath(NumenPlayer body) {
        MinecraftServer server = body.level.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = body.getUUID();
        // 死因取 die() 里抄下的那一句：此刻再问战斗记录已经被 vanilla 清空了，
        // 只会拿到「她死了」这种没有凶手的兜底文案（见 NumenPlayer#deathMessage）。
        String cause = body.deathMessage();
        if (cause == null || cause.isBlank()) {
            cause = body.getCombatTracker().getDeathMessage().getString();
        }
        if (cause == null || cause.isBlank()) {
            cause = "未知原因";
        }
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {   // immediate, same-session
            NumenNetwork.sendToPlayer(owner, new NumenDeathPayload(uuid, cause));
        }
        CompanionEvents.fire(CompanionEvent.DEATH, body);   // 不发工具结果：那条 tool_call 已由死因结算
        // 共存期：旧大脑（本仓自写子集）也停掉——它由 Companions.tick 驱着，不停会继续
        // 拿一具即将离场的身体跑工具。（刀 5 已删 Acts/NumenTools；这条留着待旧大脑一并收掉。）
        CompanionBrain oldBrain = BRAINS.get(uuid);
        if (oldBrain != null) {
            oldBrain.halt("身体死亡");
        }
        // 把死亡（死因 + 游戏时刻）落进世界存档的注册表：撑得过复活窗口里的一次登出——
        // 没有它，重登就丢了待复活状态，身体会「活着」回来，背包空空，也不知道自己死过。
        CompanionRegistry.get(server).markDead(uuid, cause, server.overworld().getGameTime());
        // 死亡状态进了注册表，名册才说得出「她还在，只是躺着」——面板的倒计时读这个。
        // 少了这一推，主人在死亡窗口里重登就会看见她凭空消失。
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
        body.setHealth(body.getMaxHealth());             // 存下的 .dat 是一具健康的身体，留给复活
        // 尸体排到本 tick 末尾再收：onDeath 正跑在实体 tick 里，当场 despawn 会撞原版移除保护。
        // 见 PENDING_DESPAWN —— 别再退回 server.execute（同线程不推迟）。
        PENDING_DESPAWN.add(uuid);
    }

    /**
     * 她一个人穿过了传送门——告诉她一声（环境事件，急不得）。
     *
     * <p>上游挂 Fabric 的 {@code AFTER_PLAYER_CHANGE_WORLD}；1.16.5 的 Fabric API 没有这个事件，
     * 改由 {@link NumenPlayer#tick} 里比对维度发现（见那边的 lastDimension）。
     */
    public static void onDimensionChanged(NumenPlayer body) {
        String dim = body.level.dimension().location().toString();
        com.dwinovo.numen.core.event.NumenEvents.emit(body,
                EventTypes.DIMENSION_CHANGE,
                java.util.Map.of("to", dim),
                "你进入了 " + dim + "。留意这个维度的环境和危险。", false);
    }

    /** 预留：死亡延迟重生的窗口长度，后续阶段接上「放回主人身边」时用。 */
    static long respawnDelayMs() {
        return RESPAWN_DELAY_MS;
    }

    // ─────────── 复活 / 主人登录恢复 / 名册同步 / 遣散（上游 Companions 的同一批，逐字搬）───────────

    /** 死亡之后多久才放回主人身边（游戏刻）。 */
    private static final long RESPAWN_DELAY_TICKS = 5 * 20;

    /**
     * 把一只休眠的同伴从注册表条目放回世界（位置与背包从 {@code .dat} 读回）。
     * 已经在世界里就直接返回它；注册表不认识这个 UUID 则返回 {@code null}。
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) {
            return live;
        }
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) {
            return null;
        }
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) {
            level = server.overworld();
        }
        NumenPlayer body = CompanionFactory.spawn(server, level, entry.owner().orElse(null), entry.name());
        // 注册表里记着的那一格：身体刚从 .dat 读回位置，这里把「名册说她在哪」对齐一次。
        BlockPos at = entry.pos();
        if (at != null) {
            body.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        BRAINS.put(body.getUUID(), new CompanionBrain(body));
        return body;
    }

    /**
     * 主人登录了，记一笔：他的同伴该回世界了。<b>真正的恢复不在这里做。</b>
     *
     * <p>登录事件是在原版 {@code PlayerList.placeNewPlayer()} <b>内部</b>触发的——那句还没返回。
     * 在这里恢复同伴，等于把恢复期间的任何异常接到主人的入场流程上：一只同伴的 {@code .dat} 有毛病、
     * 或者别的模组在同伴的入场事件里抛了，冒泡上去打断的是<b>主人的登录</b>，客户端看到的是
     * 「无效的玩家数据」——他会以为自己的存档毁了。推迟到下一个服务端 tick 就彻底解耦了。
     */
    public static void scheduleRestoreFor(UUID ownerUuid) {
        PENDING_RESTORE.add(ownerUuid);
    }

    /** 排队等恢复的主人。世界作废时一并清掉——排的是上一个存档的账。 */
    private static final java.util.Set<UUID> PENDING_RESTORE =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        ServerLifecycle.onStopped(PENDING_RESTORE::clear);
        ServerLifecycle.onStopped(PENDING_DESPAWN::clear);
    }

    /** 每个服务端 tick 调一次：把记下的主人挨个恢复。一个主人恢复失败不牵连下一个。 */
    public static void restorePending(MinecraftServer server) {
        drainPending(ownerUuid -> respawnAllOwnedBy(server, ownerUuid));
    }

    /**
     * 排空队列，逐个交给 {@code restore}。两条不变式：<b>每个主人只恢复一次</b>（先摘牌再执行，
     * 否则失败的那个会每 tick 重试到天荒地老），<b>一个失败不牵连下一个</b>（主人之间毫无关系）。
     * 纯逻辑、不碰 Minecraft——留这个缝是为了这两条能被单测钉住。
     */
    static void drainPending(java.util.function.Consumer<UUID> restore) {
        if (PENDING_RESTORE.isEmpty()) {
            return;
        }
        List<UUID> due = new ArrayList<>(PENDING_RESTORE);
        PENDING_RESTORE.removeAll(due);
        for (UUID ownerUuid : due) {
            try {
                restore.accept(ownerUuid);
            } catch (RuntimeException ex) {
                Constants.error("恢复 {} 的同伴时出错", ownerUuid, ex);
            }
        }
    }

    /** 主人登录：把他名下每一只同伴放回世界，并把他离线期间攒下的事件补发给他。逐只隔离。 */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            try {
                if (e.getValue().diedAt() > 0L) {
                    // 死在主人不在的时候：注册表里记着死亡，回来按「原地复活」那条路走。
                    if (NumenPlayer.findByUuid(server, e.getKey()) == null) {
                        respawn(server, e.getKey());
                    }
                } else {
                    respawn(server, e.getKey());
                }
            } catch (RuntimeException ex) {
                Constants.error("同伴 {} 没能回到世界里，跳过", e.getKey(), ex);
            }
        }
        if (owner != null) {
            replayOutbox(server, ownerUuid, owner);
        }
    }

    /** 开服：把注册表里所有**服务器同伴**（无主）放回世界。不依赖任何玩家登录。逐只隔离。 */
    public static void respawnAllServerOwned(MinecraftServer server) {
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).serverOwned()) {
            try {
                if (NumenPlayer.findByUuid(server, e.getKey()) == null) {
                    respawn(server, e.getKey());
                }
            } catch (RuntimeException ex) {
                Constants.error("服务器同伴 {} 没能回到世界里，跳过", e.getKey(), ex);
            }
        }
    }

    /**
     * 主人登录：把他离线期间攒下的世界事件补发给客户端。
     *
     * <p>他不在的时候她照样在干活——任务跑完了、被怪打了、跨了维度。这些都留着等他回来：
     * 「我帮你把矿挖完了」是最值得说的一件事，不能因为他当时不在就没了。
     */
    private static void replayOutbox(MinecraftServer server, UUID ownerUuid, ServerPlayer owner) {
        List<UUID> companions = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            companions.add(e.getKey());
        }
        for (NumenEventPayload p : outboxPayloads(EventOutbox.get(server), companions, System.currentTimeMillis())) {
            NumenNetwork.sendToPlayer(owner, p);
            Constants.info("{} 补发 {} 条离线输入", p.entityUuid(), p.entries().size());
        }
    }

    /**
     * 把出箱里攒的东西取出来，<b>一只同伴一个包</b>，装着她攒下的全部条目；什么都没攒的同伴不发。
     * 整批一个包是为了一次送达：客户端收到一个包就一次入队、再问一次熟没熟。
     * 纯逻辑、不碰网络——留这个缝是为了「一只一个包、一条不少」能被单测钉住。
     */
    static List<NumenEventPayload> outboxPayloads(EventOutbox outbox, Collection<UUID> companions, long now) {
        List<NumenEventPayload> out = new ArrayList<>();
        for (UUID companion : companions) {
            List<EventQueue.Entry> pending = outbox.take(companion, now);
            if (!pending.isEmpty()) {
                out.add(new NumenEventPayload(companion, pending));
            }
        }
        return out;
    }

    /** 注册表 → 决策层认得的纯数据行。 */
    private static List<CompanionRoster.Row> rowsOf(MinecraftServer server, UUID ownerUuid) {
        List<CompanionRoster.Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            rows.add(new CompanionRoster.Row(e.getKey(), e.getValue().name(), e.getValue().diedAt()));
        }
        return rows;
    }

    /**
     * 把这只主人的名册推给他。<b>状态同步不是事件通知</b>：漏一次也补得回来，所以「任何存在或存活状态的
     * 变化之后都要调」——登录、召唤、遣散、死亡、复活。
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (CompanionRoster.Line l : CompanionRoster.build(
                rowsOf(server, owner.getUUID()), server.overworld().getGameTime(), RESPAWN_DELAY_TICKS)) {
            NumenPlayer body = NumenPlayer.findByUuid(server, l.uuid());
            list.add(new CompanionListPayload.Entry(l.uuid(), l.name(), l.respawnInMs(),
                    body != null && body.isCreative()));
        }
        NumenNetwork.sendToPlayer(owner, new CompanionListPayload(reg.worldId(), list));
    }

    /**
     * 游戏模式落地，召唤表单和编辑卡同一道门。创造档过权限门：主人有 {@code /gamemode} 权限（等级 2）
     * <b>或本人就在创造</b>（无权限时客户端继承主人档）都放行；都不满足（伪造/竞态）按生存并说明
     * ——同伴的模式上限 = 主人的上限。
     */
    public static void applyGameMode(ServerPlayer owner, NumenPlayer body, boolean creative) {
        if (body == null) {
            return;
        }
        if (creative && !owner.hasPermissions(2) && !owner.isCreative()) {
            // 1.16.5 没有 sendSystemMessage:两参 sendMessage 走的就是 SYSTEM 通道。
            owner.sendMessage(new TranslatableComponent(ModLanguageData.Keys.NOTICE_CREATIVE_NEEDS_OP),
                    net.minecraft.Util.NIL_UUID);
            creative = false;
        }
        body.setGameMode(creative ? GameType.CREATIVE : GameType.SURVIVAL);
    }

    /** 遣散一只同伴（按身体）。 */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        dismiss(server, body.getOwnerUuid(), List.of(body.getUUID()));
    }

    /**
     * <b>遣散的唯一入口</b>：面板 ✕ 与 {@code /numen player despawn} 都走这里，调用方只管验归属、
     * 挑出是哪几只。活着的身体像死亡一样把身上的一切掉在脚下再离场；休眠的没有身体可掉，
     * {@code .dat} 留着东西但再没人重建它。最后一次除名、一次推送。
     */
    public static void dismiss(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        for (UUID uuid : uuids) {
            NumenPlayer live = NumenPlayer.findByUuid(server, uuid);
            if (live == null) {
                continue;
            }
            dropEverything(live);
            BRAINS.remove(uuid);
            CompanionFactory.despawn(server, live);
        }
        forget(server, ownerUuid, uuids);
    }

    /** 穿戴的经各穿戴来源摘下再掉：模组的饰品栏不在原版物品栏里，只 dropAll 的话它们会跟着身体一起消失。 */
    private static void dropEverything(NumenPlayer body) {
        for (GearSlot slot : NumenPlugins.gearSlots(body)) {
            ItemStack worn = slot.swap(ItemStack.EMPTY);
            if (!worn.isEmpty()) {
                body.drop(worn, true, false);
            }
        }
        body.inventory.dropAll();
    }

    /**
     * 永久除名：删注册表条目 = 这只同伴不再存在，再把新名册推给主人——客户端据此把她的家目录一并删掉。
     * 除名和通知是同一件事的两半，放在一处才不会出现「删了条目却忘了通知」或者「通知了却没删干净」的半截状态。
     * 主人不在线就只删条目——他下次登录收到的名册照样是对的，对账是<b>状态同步</b>不是事件通知，漏不掉。
     */
    private static void forget(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        EventOutbox outbox = EventOutbox.get(server);
        for (UUID uuid : uuids) {
            reg.remove(uuid);
            outbox.forget(uuid);              // 她攒的事件跟着走：没人会再收
            CompanionStateWatch.forget(uuid); // 背包镜像同理：这个 UUID 不会再回来了
            forgetOwner(uuid);                // 归属映射同理
        }
        ServerPlayer owner = ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
    }

    // ─────────── 休眠 / 死亡重生 / 醒来播报（上游 Companions 的同一批）───────────

    /** 把同伴存进 {@code .dat} 并移出世界（休眠）。 */
    public static void dormant(MinecraftServer server, NumenPlayer body) {
        // 离场前把「她此刻在哪」刷进注册表：回来时按这一格重建。
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(), prev.movedTo(((ServerLevel) body.level).dimension(), body.blockPosition()));
        }
        CompanionFactory.despawn(server, body);
    }

    /**
     * 复活窗口到了的同伴放回主人身边（跨维度跟随也归它——主人换了维度，她就在主人现在的维度回来）。
     * 主人不在线就继续等。每个服务端 tick 调一次。
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) {
                continue;
            }
            // 服务器同伴(无主):不等人,直接在床/家复活。
            if (entry.owner().isEmpty()) {
                respawnServerDead(server, e.getKey(), entry);
                continue;
            }
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner().orElse(null));
            if (owner == null) {
                continue;   // 主人不在线——等他登录
            }
            // 眼下没有安全的落脚点就下一刻再试，不吵（与宠物同一条惯例）。
            respawnDead(server, e.getKey(), entry, owner);
        }
    }

    /**
     * 把一只死掉的同伴在主人身边放回来，清掉死亡状态，并把她「死过、死因是什么」告诉她。
     * 主人身边眼下没有安全落脚点（窄隧道、爬行、深水）时返回 false——死亡状态留着，ticker 继续试。
     * 硬放会把身体塞进方块：窒息 → 死 → 又放回同一格，一个死循环。
     */
    private static boolean respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                       ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level;
        Vec3 pos = SafeSpawn.findNear(level, owner.position());
        if (pos == null && owner.isOnGround() && SafeSpawn.hasStandingRoom(level, owner.position())) {
            pos = owner.position();   // 半砖/地毯那种不是整方块的地面：主人自己那格就站得下
        }
        if (pos == null) {
            return false;
        }
        NumenPlayer body = CompanionFactory.spawn(server, level, entry.owner().orElse(null), entry.name());
        body.teleportTo(pos.x, pos.y, pos.z);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        BRAINS.put(body.getUUID(), new CompanionBrain(body));
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        NumenNetwork.sendToPlayer(owner, new NumenRespawnPayload(uuid, entry.deathCause()));
        return true;
    }

    /**
     * 把一只死掉的**服务器同伴**(无主)放回世界:优先回她用床设下的原版复活点(同维度时),
     * 没有就用名册里的落点(家/最后位置)。全程不依赖任何玩家在场。
     */
    private static void respawnServerDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry) {
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) {
            level = server.overworld();
        }
        NumenPlayer body = CompanionFactory.spawn(server, level, entry.owner().orElse(null), entry.name());
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        // 睡过床 → 身体的 .dat 里带着原版复活点;维度一致就用它,否则用名册落点。
        BlockPos at = entry.pos();
        BlockPos bed = body.getRespawnPosition();
        if (bed != null && body.getRespawnDimension() != null
                && body.getRespawnDimension().equals(level.dimension())) {
            at = bed;
        }
        if (at != null) {
            body.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        BRAINS.put(body.getUUID(), new CompanionBrain(body));
        CompanionRegistry.get(server).markAlive(uuid);
    }

    /**
     * 她从床上醒了。<b>急件</b>——{@code sleep} 到躺下那一刻就返回了，不发这条的话她会一直以为自己还睡着。
     *
     * <p><b>1.16.5 差异</b>：上游用 {@code getLastDamageSource()}；这一代没有，改用
     * {@code LivingEntity.getLastHurtByMob()} 取打醒她的那个东西的名字。
     */
    public static void onWoke(NumenPlayer body) {
        String attacker = null;
        if (body.hurtTime > 0) {
            net.minecraft.world.entity.LivingEntity hurtBy = body.getLastHurtByMob();
            attacker = hurtBy == null ? "什么东西" : hurtBy.getName().getString();
        }
        boolean day = body.level.isDay();
        NumenEvents.emit(body, EventTypes.WOKE,
                Map.of("cause", attacker != null ? "hurt" : day ? "daybreak" : "other"),
                wokeText(day, attacker), true);
    }

    /**
     * 醒来那句话。挨打排在天亮前面：两件都真时，挨她的那一下才是她该先处理的。
     *
     * @param attacker 打醒她的那个东西的名字；没挨打则 null
     */
    static String wokeText(boolean day, String attacker) {
        if (attacker != null) {
            return "你被" + attacker + "打醒了，已经不在床上。先看清楚周围再决定是打是躲"
                    + (day ? "，天已经亮了。" : "，天还没亮。");
        }
        return day
                ? "天亮了，你自己从床上醒来，夜过去了。接着做你原本要做的事。"
                : "你从床上醒来了，可是天还没亮——是外力把你弄醒的（床没了、被传送都算）。先看看周围。";
    }
}
