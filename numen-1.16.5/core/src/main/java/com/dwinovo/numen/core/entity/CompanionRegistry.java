package com.dwinovo.numen.core.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent index of every companion that exists, keyed by companion UUID.
 * The companion BODY (inventory, position, owner) persists for free as a vanilla
 * player {@code .dat}, but vanilla never enumerates the {@code playerdata/}
 * folder for players that aren't logging in — so without this index we couldn't
 * know which companions to recreate, or who owns them, while they sit dormant.
 *
 * <p>World-saved on the overworld data storage (one file, all owners' companions).
 * The {@code dimension}/{@code pos} are a respawn hint (which level to construct
 * the body in); the {@code .dat} carries the authoritative restored state.
 */
@com.dwinovo.numen.core.api.Internal
public final class CompanionRegistry extends SavedData {

    /** 1.16.5 没有 UUIDUtil(1.18 才有),存档里的 UUID 用字符串编解码——
     *  与 permission 层(PermissionStore / PlacedBlocks)同一写法。 */
    private static final Codec<UUID> UUID_STRING = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /** 1.16.5 没有 ResourceKey.codec，维度 key 自己用 ResourceLocation 编解码。 */
    private static final Codec<ResourceKey<Level>> DIMENSION = ResourceLocation.CODEC.xmap(
            id -> ResourceKey.create(Registry.DIMENSION_REGISTRY, id), ResourceKey::location);

    /** One companion's catalog entry. {@code diedAt > 0} = dead, awaiting a respawn-at-owner (the death
     *  state is persisted here so it SURVIVES a logout during the respawn window — see Companions).
     *  {@code skinValue}/{@code skinSig} = 借来的正版皮肤(Mojang 签名的 textures 属性),
     *  空串 = 无皮肤,客户端回落原版默认皮肤(按 UUID 哈希抽取)。 */
    public record Entry(String name, Optional<UUID> owner, ResourceKey<Level> dimension, BlockPos pos,
                        String deathCause, long diedAt, String skinValue, String skinSig,
                        String taskName, String taskLua, String taskOld) {
        /** A live companion (not dead), no borrowed skin, idle. {@code owner} 为 null = 服务器同伴(无主)。 */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, Optional.ofNullable(owner), dimension, pos, "", 0L, "", "", "", "", "");
        }

        /**
         * 她现在在做什么:这件活给模型看的名字(派它的函数的全名)与重启后再跑的那一行 Lua({@code taskLua});空串 = 闲着,只有名字 =
         * 在做、但接不回来。见 {@code TaskPersistence}。{@code taskOld} 只是读进来的:旧版本按一行命令记下的活,这一版不再跑它。
         */
        public Entry doing(String task, String lua) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig,
                    task == null ? "" : task, lua == null ? "" : lua, "");
        }

        /** 刷新落点(休眠/移动时的 respawn 提示),皮肤与死亡状态原样保留。 */
        public Entry movedTo(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, skinValue, skinSig,
                    taskName, taskLua, taskOld);
        }

        /** 换上 Mojang 签名的皮肤数据(value+signature)。 */
        public Entry withSkin(String value, String sig) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt,
                    value == null ? "" : value, sig == null ? "" : sig, taskName, taskLua, taskOld);
        }

        Entry dead(String cause, long at) {
            return new Entry(name, owner, dimension, pos, cause, at, skinValue, skinSig,
                    taskName, taskLua, taskOld);
        }

        Entry alive() {
            return new Entry(name, owner, dimension, pos, "", 0L, skinValue, skinSig,
                    taskName, taskLua, taskOld);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUID_STRING.optionalFieldOf("owner").forGetter(Entry::owner),
                DIMENSION.fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("deathCause", "").forGetter(Entry::deathCause),
                Codec.LONG.optionalFieldOf("diedAt", 0L).forGetter(Entry::diedAt),
                Codec.STRING.optionalFieldOf("skinValue", "").forGetter(Entry::skinValue),
                Codec.STRING.optionalFieldOf("skinSig", "").forGetter(Entry::skinSig),
                Codec.STRING.optionalFieldOf("taskName", "").forGetter(Entry::taskName),
                Codec.STRING.optionalFieldOf("taskLua", "").forGetter(Entry::taskLua),
                // 旧版本记的那一行命令:读进来只为如实说它接不回来,写回去它已经清掉了
                Codec.STRING.optionalFieldOf("taskArgs", "").forGetter(Entry::taskOld)
        ).apply(i, Entry::new));
    }

    private static final Codec<CompanionRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUID_STRING, Entry.CODEC)
                    .fieldOf("companions").forGetter(d -> d.entries),
            Codec.STRING.optionalFieldOf("worldId", "").forGetter(d -> d.worldId)
    ).apply(i, CompanionRegistry::new));

    // 1.16.5 的 SavedData 是「先 new 再 load(tag)」的形状（1.20.1 起才改成 SavedData.Factory +
    // 静态 load）；注册走 computeIfAbsent(Supplier, name)。CODEC 的 (de)序列化仍是我们自己的。

    /** 存档文件名：1.16.5 的 SavedData 构造器要它。 */
    private static final String DATA_NAME = "numen_companions";

    @Override
    public CompoundTag save(CompoundTag tag) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    /** 存档读入：把解出来的那份抄进自己（1.16.5 的 {@code SavedData.load} 是实例方法、要就地改 this）。 */
    @Override
    public void load(CompoundTag tag) {
        CompanionRegistry decoded = decode(tag);
        entries.clear();
        entries.putAll(decoded.entries);
        worldId = decoded.worldId;
    }

    // 包内可见:持久化是这个类最要命的部分(解析失败 = 全世界同伴静默消失),
    // 得让单测够得着。（这就是上游那个静态 load；名字与 1.16.5 的 SavedData.load 撞了,改叫 decode。）
    static CompanionRegistry decode(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(CompanionRegistry::new);
    }

    private final Map<UUID, Entry> entries;
    private String worldId;

    CompanionRegistry() {
        super(DATA_NAME);
        this.entries = new HashMap<>();
        this.worldId = "";
    }

    private CompanionRegistry(Map<UUID, Entry> entries, String worldId) {
        super(DATA_NAME);
        this.entries = new HashMap<>(entries);
        this.worldId = worldId == null ? "" : worldId;
    }

    public static CompanionRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CompanionRegistry::new, DATA_NAME);
    }

    /**
     * 这个世界的身份证——首次访问时随机生成并持久化。
     *
     * <p>客户端的同伴数据({@code config/numen/companions/}) 是一个跨存档共用的目录,
     * 所以"这只同伴不在名册上"必须先问清楚"名册是哪个世界的"。没有这个 id,换一个
     * 存档进去就会把上一个存档的同伴全判成已遣散——那是会毁数据的。
     *
     * <p>随机 UUID 而不是存档名:存档名会重名、会改名,服务器地址会变。
     */
    public String worldId() {
        if (worldId == null || worldId.isBlank()) {
            worldId = UUID.randomUUID().toString();
            setDirty();
        }
        return worldId;
    }

    /** Add or update a companion's catalog entry. */
    public void put(UUID companionUuid, Entry entry) {
        entries.put(companionUuid, entry);
        setDirty();
    }

    public Entry find(UUID companionUuid) {
        return entries.get(companionUuid);
    }

    public void remove(UUID companionUuid) {
        if (entries.remove(companionUuid) != null) setDirty();
    }

    /** Every companion owned by {@code ownerUuid} (UUID + entry). {@code ownerUuid} 为 null 时返回无主者。 */
    public List<Map.Entry<UUID, Entry>> ownedBy(UUID ownerUuid) {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            UUID o = e.getValue().owner().orElse(null);
            if (o == null ? ownerUuid == null : o.equals(ownerUuid)) out.add(e);
        }
        return out;
    }

    /** Every companion with no owner——服务器同伴。开服入驻用。 */
    public List<Map.Entry<UUID, Entry>> serverOwned() {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().owner().isEmpty()) out.add(e);
        }
        return out;
    }

    /** Every companion currently dead and awaiting respawn (persisted, survives a logout). */
    public List<Map.Entry<UUID, Entry>> pendingDead() {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().diedAt() > 0L) out.add(e);
        }
        return out;
    }

    /** Mark a companion dead (records the cause + game-time, persisted for the respawn timer). */
    public void markDead(UUID uuid, String cause, long diedAt) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.dead(cause, diedAt));
        setDirty();
    }

    /**
     * Mark a companion dead <b>and remember where she fell</b>——无主同伴复活时就回这一格（「原地复活」）。
     *
     * <p>这是 #0015 的「死亡时回写」修法：名册落点平时只在召唤/休眠时刷新，于是死一次就回召唤点；
     * 这里把死亡处写进去，复活（{@code respawnServerDead}）便按它回来。
     */
    public void markDeadAt(UUID uuid, String cause, long diedAt, ResourceKey<Level> dimension, BlockPos pos) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.movedTo(dimension, pos).dead(cause, diedAt));
        setDirty();
    }

    /** Clear the death state (called when the body is respawned). */
    public void markAlive(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || e.diedAt() == 0L) return;
        entries.put(uuid, e.alive());
        setDirty();
    }
}
