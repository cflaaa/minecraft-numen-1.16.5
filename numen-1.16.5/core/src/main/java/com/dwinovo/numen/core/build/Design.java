package com.dwinovo.numen.core.build;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.core.sdk.BadValue;
import com.dwinovo.numen.core.sdk.Codec;
import com.dwinovo.numen.core.sdk.LuaCodecs;
import com.dwinovo.numen.core.sdk.Positions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

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
 * 要盖成的样子({@code numen.build.place}、{@code numen.build.diff} 收的那一个参数):一串格(Cells,每一格是那一格要放的方块,照
 * {@code /setblock} 的写法),或一份蓝图文件摆在哪儿({@code numen.build.blueprint} 交回的那张表:文件名、原点、转了多少度)。两样恰好
 * 一样。蓝图只记在哪、怎么摆,格子留在文件里,用到时才读。
 *
 * @param cells     一串格;是蓝图时为 null
 * @param blueprint 一份蓝图;是一串格时为 null
 */

public record Design(List<Cell> cells, Blueprint blueprint) {
    public static final Codec<Design> CODEC = new Codec<Design>(){

        public ScriptType type() {
            return ScriptType.union((ScriptType[])new ScriptType[]{LuaCodecs.CELLS.type(), new ScriptType.Named("Blueprint")});
        }

        public Design decode(Object value) {
            Map t;
            Object v;
            if (value instanceof Map && (v = (t = (Map)value).get("blueprint")) instanceof String) {
                BlockPos origin;
                String name = (String)v;
                Object turned = t.get("rotation");
                if (turned != null && !(turned instanceof Long)) {
                    throw new BadValue("expected a Blueprint's rotation to be 0, 90, 180 or 270; got " + BadValue.given(turned));
                }
                try {
                    origin = Positions.cell(t.get("origin"));
                }
                catch (BadValue bad) {
                    throw bad.in("the Blueprint's origin");
                }
                return new Design(null, new Blueprint(name, origin, turned == null ? 0 : ((Long)turned).intValue()));
            }
            ArrayList<Cell> cells = new ArrayList<Cell>();
            List items = Positions.items(value);
            for (int i = 0; i < items.size(); ++i) {
                Object item = items.get(i);
                try {
                    String block;
                    Map m;
                    Object v2;
                    cells.add(new Cell(Positions.cell(item), item instanceof Map && (v2 = (m = (Map)item).get("name")) instanceof String ? (block = (String)v2) : null));
                    continue;
                }
                catch (BadValue bad) {
                    throw items.size() == 1 ? bad : bad.in("item " + (i + 1));
                }
            }
            if (cells.isEmpty()) {
                throw new BadValue("expected Cells (Blocks: the block for each cell, written as /setblock takes it) or a Blueprint from numen.build.blueprint; got " + BadValue.given(value));
            }
            return new Design(cells, null);
        }

        public Object encode(Design value) {
            if (value.blueprint != null) {
                LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
                out.put("blueprint", value.blueprint.name());
                out.put("origin", Positions.value(value.blueprint.origin()));
                out.put("rotation", Long.valueOf(value.blueprint.rotation()));
                return out;
            }
            ArrayList<Map> out = new ArrayList<Map>();
            for (Cell cell : value.cells) {
                if (cell.block() == null) {
                    out.add(Positions.value(cell.pos()));
                    continue;
                }
                LinkedHashMap<String, Object> block = new LinkedHashMap<String, Object>();
                block.put("name", cell.block());
                block.put("pos", Positions.value(cell.pos()));
                out.add(block);
            }
            return out;
        }
    };

    public Design {
        if (cells == null == (blueprint == null)) {
            throw new IllegalArgumentException("cells or a blueprint, exactly one of them");
        }
        cells = cells == null ? null : List.copyOf(cells);
    }

    public record Blueprint(String name, BlockPos origin, int rotation) {
        public Blueprint {
            origin = origin.immutable();
        }
    }

    public record Cell(BlockPos pos, String block) {
        public Cell(BlockPos pos, String block) {
            this.pos = pos = pos.immutable();
            this.block = block;
        }
    }
}

