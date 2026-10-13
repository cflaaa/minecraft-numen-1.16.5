package com.dwinovo.numen.pathing.body;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 把东西拿到手上,照原版玩家的几个按键:数字键切换快捷栏选中的格({@code handleSetCarriedItem}),中键把背包深处的
 * 东西换进快捷栏({@code handlePickItem},换进的是原版挑的那一格——先空格,再不是附魔过的,原来那件换回背包,不丢),
 * F 键交换主副手,创造模式中键凭空取一叠。每一次真的换了都交回一个 {@link BodyAction},由调用方记进结局。
 */
public final class Hotbar {

    /**
     * 副手在 {@link BodyAction.Held#from} 里的编号(原版物品栏里副手那一格)。
     *
     * <p>1.20 有 {@code Inventory.SLOT_OFFHAND} 这个常量,**1.16.5 没有**,只有裸的下标:
     * 主背包 0..35、盔甲 36..39、副手 40。
     */
    public static final int OFFHAND = 40;

    private Hotbar() {}

    /**
     * 把主背包第 {@code slot} 格的东西拿到主手;{@code slot} 是 {@link #OFFHAND} 就交换主副手;{@code slot} 为负是空手——切到
     * 快捷栏里一个空格({@link #emptyHand}),没有空格就拿着手上的东西。已经在主手上就什么也不做。手上本来就空着、只是换到
     * 另一个空格时,手上拿的没变,不算一个身体动作。
     */
    public static Optional<BodyAction> hold(ServerPlayer body, int slot) {
        Inventory inventory = body.inventory;
        if (slot == OFFHAND) {
            Item item = body.getOffhandItem().getItem();
            body.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            return Optional.of(new BodyAction.Held(item, OFFHAND, inventory.selected));
        }
        if (slot < 0) {
            int empty = emptyHand(inventory);
            if (empty < 0 || empty == inventory.selected) {
                return Optional.empty();
            }
            boolean wasEmpty = inventory.getSelected().isEmpty();
            BodyAction action = select(body, empty, Items.AIR);
            return wasEmpty ? Optional.empty() : Optional.of(action);
        }
        if (slot == inventory.selected) {
            return Optional.empty();
        }
        Item item = inventory.getItem(slot).getItem();
        if (Inventory.isHotbarSlot(slot)) {
            return Optional.of(select(body, slot, item));
        }
        body.connection.handlePickItem(new ServerboundPickItemPacket(slot));
        return Optional.of(new BodyAction.Held(item, slot, inventory.selected));
    }

    /**
     * 让一只手拿着 {@code item},右键就能用上它:主手已经是它,或主手空着而副手是它(原版右键主手用不了就试副手),
     * 什么也不做;背包里有就拿到主手(快捷栏先);只在副手、主手又占着,就交换主副手;创造模式背包里没有就凭空取一叠。
     *
     * @return 拿到了没有、拿在哪只手上,连同做了什么(没做为 null)
     */
    public static Grip grip(ServerPlayer body, Item item) {
        Inventory inventory = body.inventory;
        ItemStack main = inventory.getSelected();
        if (main.getItem() == item) {
            return new Grip(InteractionHand.MAIN_HAND, null);
        }
        if (main.isEmpty() && body.getOffhandItem().getItem() == item) {
            return new Grip(InteractionHand.OFF_HAND, null);
        }
        int slot = slotOf(inventory, item);
        if (slot >= 0) {
            return new Grip(InteractionHand.MAIN_HAND, hold(body, slot).orElse(null));
        }
        if (body.getOffhandItem().getItem() == item) {
            return new Grip(InteractionHand.MAIN_HAND, hold(body, OFFHAND).orElse(null));
        }
        if (body.gameMode.isCreative()) {
            conjure(inventory, item);
            return new Grip(InteractionHand.MAIN_HAND, new BodyAction.Conjured(item, inventory.selected));
        }
        return new Grip(null, null);
    }

    /**
     * {@link #grip} 的结果。
     *
     * @param hand   拿着它的那只手;没拿到为 null
     * @param action 为此做了什么;什么也没做为 null
     */
    public record Grip(InteractionHand hand, BodyAction action) {

        /** 拿到了。 */
        public boolean ready() {
            return hand != null;
        }
    }

    /**
     * 空手用快捷栏的哪一格。捡起的东西先进背包的第一个空格;空手挖着的时候那一格被塞进东西,手上就换了一样东西,原版会从头挖。
     * 所以挑一个不是第一个空格的空格:手上正空着的那一格是就不动,不是就从后往前找;只有第一个空格可用时用它。没有空格为 -1。
     */
    private static int emptyHand(Inventory inventory) {
        int first = inventory.getFreeSlot();
        if (inventory.getSelected().isEmpty() && inventory.selected != first) {
            return inventory.selected;
        }
        for (int i = Inventory.getSelectionSize() - 1; i >= 0; i--) {
            if (inventory.getItem(i).isEmpty() && i != first) {
                return i;
            }
        }
        return first >= 0 && Inventory.isHotbarSlot(first) ? first : -1;
    }

    /** 主背包里第一格装着 {@code item} 的,快捷栏在前;没有为 -1。 */
    private static int slotOf(Inventory inventory, Item item) {
        for (int i = 0; i < inventory.items.size(); i++) {
            if (inventory.items.get(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    private static BodyAction select(ServerPlayer body, int slot, Item item) {
        // ⚠️ 不能用 new ServerboundSetCarriedItemPacket(slot)：那个构造器只在「合并的客户端+服务端 jar」里，
        // 专用服务端 jar 经 ProGuard 裁剪后没有它（该包只由客户端构造发送），调了就是 NoSuchMethodError。
        // 本仓的口径（见 conjure 与 Wardrobe）是直接置位——服务端逻辑读的就是这一格。
        body.inventory.selected = slot;
        return new BodyAction.Held(item, slot, slot);
    }

    /**
     * 创造模式凭空取一叠：把 {@code item} 放进一个合适的快捷栏格并选中它。
     *
     * <p>⚠️ <b>不能调 {@code Inventory.setPickedItem(ItemStack)}</b>：它在 Loom 编译用的合并 jar 里有，
     * 却在**专用服务端的 jar 里被 ProGuard 裁掉了**（那是客户端「中键取物」走的路），
     * 调了就是 NoSuchMethodError。这里照它的实现重写一遍，顺序与原版一致：
     * 挑一个合适的快捷栏格 → 那一格不是空的就先挪到第一个空格 → 再把新的放进去。
     * **「先挪走」这一步不能省**——省了就等于把玩家原本放在那一格的东西凭空吃掉。
     */
    private static void conjure(Inventory inventory, Item item) {
        int slot = inventory.getSuitableHotbarSlot();
        ItemStack old = inventory.getItem(slot);
        if (!old.isEmpty()) {
            int free = inventory.getFreeSlot();
            if (free != -1) {
                inventory.setItem(free, old);
            }
        }
        inventory.setItem(slot, new ItemStack(item));
        inventory.selected = slot;
    }
}
