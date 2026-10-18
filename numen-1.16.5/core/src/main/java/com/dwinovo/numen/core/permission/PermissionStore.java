package com.dwinovo.numen.core.permission;

import com.dwinovo.numen.core.Constants;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 每主人一份:他手下每只同伴的{@link Mode 模式},和他自己写的那一层规则(deny、ask、allow 三张表)。
 * 存在主世界的存档数据里,文件名带主人 UUID。命令、"允许并记住"与以后的面板都只经这里的公开方法改它。
 *
 * <p>规则层是不可变的 {@link RuleSet},每次改动换一份新的:{@link Permission#gateFor} 在主线程取走引用,
 * 搜索线程拿着读,不会读到改了一半的表。每一行都经 {@link Rule#parse} 解析,存档里写错的行读档时记一条
 * 错误日志、不进表。
 *
 * <p>照上游 {@code permission/PermissionStore.java} 搬(2026-10-11 归位阶段 1),1.16.5 适配:
 * <ul>
 *   <li>{@code UUIDUtil.STRING_CODEC} 这一代没有,存档里模式的键用
 *       {@code Codec.STRING.xmap(UUID::fromString, UUID::toString)};</li>
 *   <li>1.16.5 的 {@code SavedData} 用 {@code load(CompoundTag)} 读档、注册走 {@code computeIfAbsent(Supplier, String)}
 *       (见 {@link #of});</li>
 *   <li>DFU 4.0.26 的 {@code DataResult.error} 只收字符串。</li>
 * </ul>
 */
public final class PermissionStore extends SavedData {

    /** UUID 的字符串编解码(1.16.5 没有 UUIDUtil,见类注释)。 */
    private static final Codec<UUID> UUID_STRING = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    /**
     * 一张表,每一行经 {@link Rule#parse}。这一代的 DFU 在列表里有一行读不通时,连带整张表都丢,所以逐行读:写错的行记一条错误日志、
     * 不进表,其余照读。
     */
    private static final Codec<List<Rule>> TABLE = Codec.STRING.listOf()
            .xmap(PermissionStore::readRows, rows -> rows.stream().map(Rule::toString).toList());

    private static final Codec<PermissionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUID_STRING, Codec.STRING)
                    .fieldOf("modes").forGetter(PermissionStore::modeNames),
            TABLE.optionalFieldOf("deny", List.of()).forGetter(s -> s.rules.deny()),
            TABLE.optionalFieldOf("ask", List.of()).forGetter(s -> s.rules.ask()),
            TABLE.optionalFieldOf("allow", List.of()).forGetter(s -> s.rules.allow())
    ).apply(i, PermissionStore::new));

    private static String fileName(UUID owner) {
        return "numen_permissions_" + owner;
    }

    private final Map<UUID, Mode> modes = new HashMap<>();
    private RuleSet rules = RuleSet.EMPTY;

    PermissionStore(UUID owner) {
        super(fileName(owner));
    }

    /** 测试用无参构造(上游同名;SavedData 的 id 用占位,测试不起存档)。 */
    PermissionStore() {
        super("numen_permissions_test");
    }

    /** 指定存档文件名的构造(服务器档用)。 */
    private PermissionStore(String dataFileName) {
        super(dataFileName);
    }

    private PermissionStore(Map<UUID, String> modeNames, List<Rule> deny, List<Rule> ask, List<Rule> allow) {
        this(new UUID(0L, 0L));
        modeNames.forEach((uuid, name) -> modes.put(uuid, Mode.byName(name)));
        rules = new RuleSet(deny, ask, allow);
    }

    /** 存档读入(1.16.5 的 SavedData 形状,见类注释)。 */
    @Override
    public void load(CompoundTag tag) {
        PermissionStore decoded = decode(tag);
        if (decoded == null) {
            return;
        }
        modes.clear();
        modes.putAll(decoded.modes);
        rules = decoded.rules;
    }

    /**
     * 解一份存档成独立的规则层(上游的静态 {@code load};1.16.5 的 {@code SavedData.load(CompoundTag)}
     * 是实例方法、名字撞了,测试与读档都走这里)。
     */
    static PermissionStore decode(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag)
                .resultOrPartial(error -> Constants.LOG.error("[numen-permission] 权限存档有读不懂的内容: {}", error))
                .orElse(null);
    }

    private static List<Rule> readRows(List<String> rows) {
        List<Rule> table = new ArrayList<>();
        for (String row : rows) {
            Rule.CODEC.parse(NbtOps.INSTANCE, StringTag.valueOf(row))
                    .resultOrPartial(error -> Constants.LOG.error("[numen-permission] 权限存档有读不懂的内容: {}", error))
                    .ifPresent(table::add);
        }
        return table;
    }

    public static PermissionStore of(MinecraftServer server, UUID owner) {
        return server.overworld().getDataStorage().computeIfAbsent(() -> new PermissionStore(owner), fileName(owner));
    }

    /** 服务器同伴(无主)共用的权限档:固定一个文件,不按 owner 分。 */
    public static final String SERVER_FILE = "numen_permissions_server";

    public static PermissionStore ofServer(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(() -> new PermissionStore(SERVER_FILE), SERVER_FILE);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    /** 这只同伴的模式;没设过是 {@link Mode#ASK}。 */
    public Mode modeOf(UUID companion) {
        return modes.getOrDefault(companion, Mode.ASK);
    }

    /** 这只同伴的模式;没设过给 {@code fallback}(服务器档用 {@link Mode#AUTO})。 */
    public Mode modeOrDefault(UUID companion, Mode fallback) {
        return modes.getOrDefault(companion, fallback);
    }

    public void setMode(UUID companion, Mode mode) {
        modes.put(companion, mode);
        setDirty();
    }

    /** 主人这一层此刻的规则(不可变快照)。 */
    public RuleSet rules() {
        return rules;
    }

    /**
     * 在一张表末尾加一行。
     *
     * @return 加上了;这张表里已经有一模一样的一行时不重复加,返回 false
     */
    public boolean add(Verdict.Kind table, Rule rule) {
        List<Rule> rows = rules.table(table);
        if (rows.contains(rule)) {
            return false;
        }
        List<Rule> next = new ArrayList<>(rows);
        next.add(rule);
        rules = rules.withTable(table, next);
        setDirty();
        return true;
    }

    /**
     * 删掉一张表里的第 {@code index} 行(从 0 数)。
     *
     * @throws IndexOutOfBoundsException 这张表没有这一行
     */
    public Rule remove(Verdict.Kind table, int index) {
        List<Rule> next = new ArrayList<>(rules.table(table));
        Rule removed = next.remove(index);
        rules = rules.withTable(table, next);
        setDirty();
        return removed;
    }

    /** 清空主人这一层的三张表;出厂层与各同伴的模式不动。 */
    public void reset() {
        rules = RuleSet.EMPTY;
        setDirty();
    }

    /** "允许并记住":每一行进 allow 表,已经有的不重复。 */
    public void remember(List<Rule> allow) {
        for (Rule rule : allow) {
            add(Verdict.Kind.ALLOW, rule);
        }
    }

    private Map<UUID, String> modeNames() {
        Map<UUID, String> out = new HashMap<>();
        modes.forEach((uuid, mode) -> out.put(uuid, mode.name().toLowerCase(Locale.ROOT)));
        return out;
    }
}
