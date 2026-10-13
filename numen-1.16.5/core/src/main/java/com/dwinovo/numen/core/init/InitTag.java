package com.dwinovo.numen.core.init;

import com.dwinovo.numen.core.Constants;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.SerializationTags;
import net.minecraft.tags.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Catalogue of the datapack tags the numen-core tool pack declares. The
 * pathfinder and the loader-side data generators both reference the constants
 * here so the key's identifier exists in one place only — rename or repath in
 * this file and every consumer follows. These are core's tags (namespace
 * {@code numen}), not the engine's: pathfinding scaffolding and blueprint safety
 * are tool-pack concerns. Which blocks the body may break is not a tag — that is
 * the permission layer's rule table ({@code com.dwinovo.numen.core.permission}).
 *
 * <h2>1.16.5: tags are resolved, not keyed</h2>
 * 1.20.1 hands out a {@code TagKey<T>} — a lazy <em>name</em> that a
 * {@code Holder} resolves later. 1.16.5 has no {@code TagKey} and no
 * {@code Holder}: a tag is a live {@link Tag} pulled out of a
 * {@link SerializationTags} collection. So the constants here are plain
 * {@link ResourceLocation}s (the tag's <em>id</em>) and the containers are
 * looked up <b>per call</b> through {@link #items}/{@link #blocks} —
 * which is also what this pack wants anyway: tags come from the datapack,
 * {@code /reload} may swap them, and a {@code static final} snapshot taken at
 * class-load time would freeze whatever was bound before the server started.
 *
 * <p>An absent tag comes back as an empty {@code Tag}, whose {@code contains}
 * is always false — the same "does not match" that upstream got from a
 * {@code Holder} on a missing tag.
 */
public final class InitTag {

    /**
     * Foods that may be used to feed/heal a companion. Datapack-driven so server
     * admins can extend the list without code changes — see
     * {@code data/numen/tags/item/tame_foods.json}.
     */
    public static final ResourceLocation TAME_FOODS = id("tame_foods");

    /**
     * The factory throwaway list: blocks a walk is willing to spend — bridging gaps,
     * stepping up, and pillaring — when its description names no {@code materials}
     * (reflexes always use it).
     * Datapack-driven so packs can add their own cheap blocks — see
     * {@code data/numen/tags/item/throwaway.json}.
     */
    public static final ResourceLocation THROWAWAY = id("throwaway");

    /**
     * Blocks whose block-entity data a blueprint may carry into the world — sign
     * text, banner patterns, and whatever a pack chooses to add.
     *
     * <p>The tag <b>is</b> the authorisation. A blueprint is a file: editable,
     * downloadable. Copying a chest's contents out of one would print items from
     * nothing, so nothing is copied unless it is named here. Being a datapack tag
     * rather than a list in code means a pack that adds decorative block entities
     * can declare them safe without touching the mod — but it also means adding a
     * container here lets blueprints print its contents. That is the pack author's
     * call to make, deliberately, and it should be made knowing that.
     *
     * <p>Named vanilla tags are preferred over listing members: {@code
     * #minecraft:banners} keeps meaning "banners" across versions. See
     * {@code data/numen/tags/block/safe_block_entity_data.json}.
     */
    public static final ResourceLocation SAFE_BLOCK_ENTITY_DATA = id("safe_block_entity_data");

    /** 模型写标签用的前缀:{@code #minecraft:beds} 指"床这一类",而不是某一种颜色的床。 */
    public static final String TAG_PREFIX = "#";

    /**
     * 把 {@code #ns:path} 解成标签 id;不是这个形式、或者 id 不合法,返回 {@code null}
     * (调用方接着按具体 id 试)。
     *
     * <p>让工具参数收标签,是因为"一类方块"这件事我们枚举不完:床有 16 色、石头有一族、
     * 模组还会加。标签是 Minecraft 自己表达"一类"的方式,而且整合包能扩。
     */
    public static ResourceLocation parseRef(String raw) {
        if (raw == null || !raw.startsWith(TAG_PREFIX)) {
            return null;
        }
        return ResourceLocation.tryParse(raw.substring(TAG_PREFIX.length()).trim());
    }

    /**
     * 物品标签此刻的成员。每次现取:{@code /reload} 换掉的标签,下一次调用就生效。
     * 标签不存在时拿到的是一张空表——成员判据一律为假,与上游"没有这个标签就谁都不命中"一致。
     */
    public static Tag<Item> items(ResourceLocation tag) {
        return SerializationTags.getInstance().getItems().getTagOrEmpty(tag);
    }

    /** 方块标签此刻的成员。取法与 {@link #items} 同。 */
    public static Tag<Block> blocks(ResourceLocation tag) {
        return SerializationTags.getInstance().getBlocks().getTagOrEmpty(tag);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation(Constants.MOD_ID, name);
    }

    private InitTag() {}
}
