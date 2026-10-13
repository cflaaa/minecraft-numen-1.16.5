package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.core.permission.Permission;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

/**
 * 同伴接入寻路的端口，只在这里组：她的身体、她的两只手、此刻的地形许可、这一趟愿意垫的料、附近要避开的生物。
 *
 * <p>与上游 {@code core/common/.../nav/CompanionPorts.java} 的分工相同，权限层也已就位：地形许可是
 * {@link GateTerrain}（挖/放之前先问主人许不许，见 {@link Permission}）。归位阶段 2 补上（2026-10-11）：
 * 此前这里全放行，寻路照走、动手前不请示。
 *
 * <p>只在世界所在的线程上调用：权限快照、威胁与垫路料都在这一刻现取。
 */
public final class CompanionPorts {

    /** 她附近多远的敌对生物算进要避开的。 */
    private static final double DANGER_SCAN = 16.0;

    /** 一只敌对生物的危险半径：贴到这么近就算挨打的威胁。 */
    private static final double DANGER_RADIUS = 3.0;

    private CompanionPorts() {}

    /** 她的寻路门面：手是她的两只手，此刻的权限快照，垫路料从她背包里挑，威胁由调用方给。 */
    public static Navigator navigator(NumenPlayer player, Threats threats) {
        return Navigator.of(player, new Ports(player.hands(), terrain(player), materials(player), threats));
    }

    /** 按 {@code spec} 与她此刻的身体、端口组一份成本模型：估一格挖多久、许不许挖，与寻路用的是同一份定价。 */
    static CostModel model(NumenPlayer player, RouteSpec spec) {
        return CostModel.of(spec, Snapshots.of(player), terrain(player), materials(player), dangers(player));
    }

    /**
     * 地形许可（宿主对「挖这一格 / 放这一格」的额外否决权）：权限层的一份裁决快照。
     *
     * <p>上游这里是 {@code new GateTerrain(Permission.gateFor(player))}，本仓同样。快照在主线程取
     * （{@link #navigator} 只在世界所在的线程上调），之后搜索线程只读它逐格问。
     *
     * <p>⚠️ <b>它不等于「随便挖」</b>：真正决定这一趟挖不挖、放不放的是<b>路线规格</b>
     * （{@code RouteSpec} 的 {@code dig} / {@code place}），这里只是宿主在此之上再加一道否决。
     * 规格说不动地形，寻路就一格都不会动。
     */
    private static TerrainPolicy terrain(NumenPlayer player) {
        return new GateTerrain(Permission.gateFor(player));
    }

    /**
     * 她附近此刻要避开的敌对生物。寻路把它们变成按位置的代价，绕开走。
     *
     * <p>用 {@link Monster} 这一类而不是「所有 {@code Enemy}」：1.16.5 的
     * {@code getEntitiesOfClass} 只收「类」，接口过不了 {@code <T extends Entity>} 那个界。
     * 僵尸、骷髅、苦力怕、蜘蛛这些都在 {@code Monster} 底下，够用。
     */
    public static Threats dangers(NumenPlayer player) {
        return () -> {
            AABB around = player.getBoundingBox().inflate(DANGER_SCAN);
            List<Threat> out = new ArrayList<>();
            for (Monster mob : player.getLevel().getEntitiesOfClass(Monster.class, around, Entity::isAlive)) {
                out.add(new Threat(mob.getX(), mob.getY(), mob.getZ(), DANGER_RADIUS));
            }
            return List.copyOf(out);
        };
    }

    /** 端口 {@link Materials}：下一次垫路会放下哪种方块。 */
    private static Materials materials(NumenPlayer player) {
        return () -> nextThrowaway(player);
    }

    /**
     * 出厂的那一份垫路料，按先后就是挑的先后。
     *
     * <p>判据照上游（标签 {@code numen:throwaway}）：遍地都是、没有功能、<b>放下去不会掉</b>。
     * ⚠️ 重力方块（沙、砂砾、混凝土粉末）一个都不要——垫在半空会直接落下去，柱子垫不起来。
     *
     * <p>上游这份名单来自数据包标签，整合包可以改；本仓还没有数据包基础设施，先写死在这里。
     */
    private static final List<Item> THROWAWAY = List.of(
            Items.COBBLESTONE, Items.DIRT, Items.STONE, Items.NETHERRACK,
            Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.SANDSTONE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.NETHER_BRICKS, Items.END_STONE, Items.BLACKSTONE);

    /**
     * 下一次垫路用哪种：按 {@link #THROWAWAY} 的先后，背包或副手里有哪一种就是哪一种。
     *
     * <p>创造模式（{@code abilities.instabuild}）下不算持有，直接给清单第一种——
     * 寻路把它拿到手上时会凭空取一叠。
     */
    private static Optional<Block> nextThrowaway(NumenPlayer player) {
        Inventory inventory = player.inventory;
        for (Item item : THROWAWAY) {
            if (!(item instanceof BlockItem block)) {
                continue;
            }
            if (player.abilities.instabuild || carries(inventory, player, item)) {
                return Optional.of(block.getBlock());
            }
        }
        return Optional.empty();
    }

    /** 背包或副手里有没有这一件。 */
    private static boolean carries(Inventory inventory, NumenPlayer player, Item item) {
        if (player.getOffhandItem().getItem() == item) {
            return true;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).getItem() == item) {
                return true;
            }
        }
        return false;
    }
}
