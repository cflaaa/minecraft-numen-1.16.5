package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.Constants;
import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.kinds.Applicative;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

public final class Built
extends SavedData {
    private static final String KEY = "numen_built";
    private static final String BUILDINGS = "buildings";
    private static final Codec<List<Stored>> CODEC = Stored.CODEC.listOf();
    private final List<Building> buildings = new ArrayList<Building>();

    public Built() {
        super(KEY);
    }

    public static Built of(MinecraftServer server) {
        return (Built)server.overworld().getDataStorage().computeIfAbsent(Built::new, KEY);
    }

    public void load(CompoundTag tag) {
        if (tag.contains(BUILDINGS)) {
            CODEC.parse(NbtOps.INSTANCE, tag.get(BUILDINGS)).resultOrPartial(e -> Constants.LOG.error("[numen-build] \u5efa\u6210\u7684\u623f\u5b50\u8bfb\u4e0d\u56de\u6765: {}", e)).ifPresent(list -> list.forEach(s -> this.buildings.add(s.building())));
        }
    }

    public CompoundTag save(CompoundTag tag) {
        CODEC.encodeStart(NbtOps.INSTANCE, this.buildings.stream().map(Building::stored).toList()).result().ifPresent(list -> tag.put(BUILDINGS, list));
        return tag;
    }

    public List<Building> all() {
        return List.copyOf(this.buildings);
    }

    public LongSet standingIn(ServerLevel level) {
        ResourceLocation dimension = level.dimension().location();
        LongOpenHashSet out = new LongOpenHashSet();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Building b : this.buildings) {
            if (!b.dimension.equals(dimension)) continue;
            for (Map.Entry<Long, Block> cell : b.cells.entrySet()) {
                pos.set(cell.getKey().longValue());
                if (!level.isLoaded(pos) || !level.getBlockState(pos).is(cell.getValue())) continue;
                out.add(cell.getKey().longValue());
            }
        }
        return LongSets.unmodifiable((LongSet)out);
    }

    public Building at(Site site) {
        for (Building b : this.buildings) {
            if (!site.is(b)) continue;
            return b;
        }
        return null;
    }

    public void placed(Site site, String builder, long now, BlockPos pos, Block block) {
        Building building = this.at(site);
        if (building == null) {
            int number = 1 + this.buildings.stream().filter(b -> b.source.equals(site.source())).mapToInt(b -> b.number).max().orElse(0);
            building = new Building(site.source(), number, site.dimension(), site.anchor(), site.quarters(), builder, now, now, Map.of());
            this.buildings.add(building);
        }
        building.quarters = site.quarters();
        building.changedAt = now;
        building.cells.put(pos.asLong(), block);
        this.setDirty();
    }

    public void cleared(Site site, long now, BlockPos pos) {
        Building building = this.at(site);
        if (building != null && building.cells.remove(pos.asLong()) != null) {
            building.changedAt = now;
            this.setDirty();
        }
    }

    public static final class Building {
        private final String source;
        private final int number;
        private final ResourceLocation dimension;
        private final BlockPos anchor;
        private int quarters;
        private final String builder;
        private final long builtAt;
        private long changedAt;
        private final Map<Long, Block> cells;

        private Building(String source, int number, ResourceLocation dimension, BlockPos anchor, int quarters, String builder, long builtAt, long changedAt, Map<Long, Block> cells) {
            this.source = source;
            this.number = number;
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.quarters = quarters;
            this.builder = builder;
            this.builtAt = builtAt;
            this.changedAt = changedAt;
            this.cells = new LinkedHashMap<Long, Block>(cells);
        }

        public String name() {
            return this.source + "#" + this.number;
        }

        public String source() {
            return this.source;
        }

        public ResourceLocation dimension() {
            return this.dimension;
        }

        public BlockPos anchor() {
            return this.anchor;
        }

        public int quarters() {
            return this.quarters;
        }

        public String builder() {
            return this.builder;
        }

        public long builtAt() {
            return this.builtAt;
        }

        public long changedAt() {
            return this.changedAt;
        }

        public Map<Long, Block> cells() {
            return Collections.unmodifiableMap(this.cells);
        }

        private Stored stored() {
            ArrayList<String> palette = new ArrayList<String>();
            ArrayList<Long> at = new ArrayList<Long>();
            ArrayList<Integer> kind = new ArrayList<Integer>();
            for (Map.Entry<Long, Block> e : this.cells.entrySet()) {
                int i = palette.indexOf(Registry.BLOCK.getKey(e.getValue()).toString());
                if (i < 0) {
                    i = palette.size();
                    palette.add(Registry.BLOCK.getKey(e.getValue()).toString());
                }
                at.add(e.getKey());
                kind.add(i);
            }
            return new Stored(this.source, this.number, this.dimension, this.anchor.asLong(), this.quarters, this.builder, this.builtAt, this.changedAt, palette, at, kind);
        }
    }

    public record Site(String source, ResourceLocation dimension, BlockPos anchor, int quarters) {
        public Site {
            anchor = anchor.immutable();
        }

        boolean is(Building building) {
            return building.source.equals(this.source) && building.dimension.equals(this.dimension) && building.anchor.equals(this.anchor);
        }
    }

    private record Stored(String source, int number, ResourceLocation dimension, long anchor, int quarters, String builder, long builtAt, long changedAt, List<String> palette, List<Long> cells, List<Integer> kinds) {
        static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(Codec.STRING.fieldOf("source").forGetter(Stored::source), Codec.INT.fieldOf("number").forGetter(Stored::number), ResourceLocation.CODEC.fieldOf("dimension").forGetter(Stored::dimension), Codec.LONG.fieldOf("anchor").forGetter(Stored::anchor), Codec.INT.fieldOf("quarters").forGetter(Stored::quarters), Codec.STRING.fieldOf("builder").forGetter(Stored::builder), Codec.LONG.fieldOf("built_at").forGetter(Stored::builtAt), Codec.LONG.fieldOf("changed_at").forGetter(Stored::changedAt), Codec.STRING.listOf().fieldOf("palette").forGetter(Stored::palette), Codec.LONG.listOf().fieldOf("cells").forGetter(Stored::cells), Codec.INT.listOf().fieldOf("kinds").forGetter(Stored::kinds)).apply(i, Stored::new));

        Building building() {
            LinkedHashMap<Long, Block> map = new LinkedHashMap<Long, Block>();
            for (int i = 0; i < this.cells.size(); ++i) {
                Block block = (Block)Registry.BLOCK.get(new ResourceLocation(this.palette.get(this.kinds.get(i))));
                map.put(this.cells.get(i), block);
            }
            return new Building(this.source, this.number, this.dimension, BlockPos.of((long)this.anchor), this.quarters, this.builder, this.builtAt, this.changedAt, map);
        }
    }
}

