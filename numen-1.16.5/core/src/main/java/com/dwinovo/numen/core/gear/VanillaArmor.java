package com.dwinovo.numen.core.gear;

import com.dwinovo.numen.core.api.gear.GearSlot;
import com.dwinovo.numen.core.api.gear.GearSource;
import com.dwinovo.numen.core.entity.NumenPlayer;
import net.minecraft.core.Registry;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 原版的四件甲，作为第一个 {@link GearSource} 经 {@code NumenApi.registerGear} 登记——和模组的饰品栏同一扇门。
 *
 * <p>照上游 {@code core/gear/VanillaArmor.java} 搬，判据一条不差（1.16.5 适配见下）：
 * <ul>
 *   <li>放入问 {@code Mob.getEquipmentSlotForItem(stack) == 这个部位}——1.16.5 物品栏菜单里
 *       {@code ArmorSlot.mayPlace} 是同一条判据；</li>
 *   <li>取出问绑定诅咒（{@code EnchantmentHelper.hasBindingCurse}）——{@code ArmorSlot.mayPickup}
 *       只在这一种情况下不许摘，创造模式照旧能摘；</li>
 *   <li>写入走 {@code NumenPlayer#setItemSlot}——1.16.5 的 {@code Player.setItemSlot} 会把
 *       {@code inventory.armor} 同步好并标记脏；</li>
 *   <li>读穿戴走 {@code getItemBySlot}——玩家这一侧就是 {@code inventory.armor} 的那一格。</li>
 * </ul>
 *
 * <p><b>1.16.5 差异</b>：上游直接拿物品栏菜单里的 {@code ArmorSlot}（{@code InventoryMenu.ARMOR_SLOT_START}）
 * 用；1.16.5 没有那个常量，且判据本来就是上面三条——所以这里直接对身体装备写，不经过菜单。
 */
public final class VanillaArmor implements GearSource {

    /** 四件甲的顺序，即 {@code <worn>} 与自动选位的顺序。 */
    private static final EquipmentSlot[] PIECES = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** 四个槽名，按 {@link #PIECES} 的顺序。{@code slot=armor} 展开成的就是它。 */
    public static final List<String> NAMES = List.of(
            PIECES[0].getName(), PIECES[1].getName(), PIECES[2].getName(), PIECES[3].getName());

    @Override
    public List<GearSlot> slots(NumenPlayer body) {
        List<GearSlot> out = new ArrayList<>(PIECES.length);
        for (int i = 0; i < PIECES.length; i++) {
            out.add(new Piece(NAMES.get(i), PIECES[i], body));
        }
        return out;
    }

    /** 哪几个部位的真实格子肯收它——与 {@link Piece#refuseWear} 是同一个判据。 */
    @Override
    public Set<String> kindsOf(NumenPlayer body, ItemStack stack) {
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < PIECES.length; i++) {
            if (PIECES[i] == net.minecraft.world.entity.Mob.getEquipmentSlotForItem(stack)) {
                kinds.add(NAMES.get(i));
            }
        }
        return kinds;
    }

    private record Piece(String name, EquipmentSlot slot, NumenPlayer body) implements GearSlot {

        @Override
        public ItemStack worn() {
            return body.getItemBySlot(slot);
        }

        @Override
        public Optional<String> refuseWear(ItemStack one) {
            return slot == net.minecraft.world.entity.Mob.getEquipmentSlotForItem(one)
                    ? Optional.empty()
                    : Optional.of(id(one) + " can't be worn on " + name);
        }

        /** 原版盔甲槽只在一种情况下不许摘：带「阻止换甲」效果的附魔，也就是绑定诅咒。创造模式能摘。 */
        @Override
        public Optional<String> refuseRemove() {
            ItemStack worn = worn();
            return worn.isEmpty() || body.isCreative() || !EnchantmentHelper.hasBindingCurse(worn)
                    ? Optional.empty()
                    : Optional.of(id(worn) + " is stuck on " + name + " (curse of binding)");
        }

        @Override
        public ItemStack swap(ItemStack in) {
            ItemStack old = worn();
            body.setItemSlot(slot, in);
            return old;
        }

        private static String id(ItemStack stack) {
            return Registry.ITEM.getKey(stack.getItem()).getPath();
        }
    }
}
