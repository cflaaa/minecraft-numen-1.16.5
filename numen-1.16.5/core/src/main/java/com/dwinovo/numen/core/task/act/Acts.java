package com.dwinovo.numen.core.task.act;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Aim;
import com.dwinovo.numen.pathing.body.BodyCompat;
import com.dwinovo.numen.pathing.body.Crosshair;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.world.Sight;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 同伴身上那几件「动手」的事：用一格方块、看背包、穿装备、吃东西、丢东西。
 *
 * <p>这一份对应上游 {@code core/common/task/interact/} 与 {@code core/common/task/inventory/}
 * 两族任务**当前能落地的那部分**。上游那些任务长在 {@code AbstractCompanionTask}（637 行）+
 * {@code Trip}/{@code Journey} + 权限层 + SDK 上，依赖闭包一万两千行以上；
 * 本仓先按已有的做法（{@code core/} 一律是自写的缩减版）把**能力**做出来，
 * 等框架与权限层补齐再回头对齐上游的写法。见 docs/PORTING.md。
 *
 * <p>右手的机制**不在这里**：挖掘/放置/右键只有一份，就是寻路模块的
 * {@link com.dwinovo.numen.pathing.body.PlayerHands}（{@code body.hands()}），
 * 这里只负责「瞄哪儿、什么时候按、把结果说成人话」。
 *
 * <p>只在世界所在的线程上调用。
 */
public final class Acts {

    private Acts() {}

    // ==================== 用一格方块（interact）====================

    /**
     * 右键点 {@code pos} 这一格：开箱子、开门、按按钮、用工作台、翻活板门。
     *
     * <p>顺序照原版客户端：先找一个**用得上的面**（{@link Aim#use}：面朝着眼睛、在交互距离内、
     * 视线上没有硬遮挡），转过去看它，再看准星落在谁身上（{@link Crosshair#on}），最后经
     * {@link com.dwinovo.numen.pathing.body.PlayerHands#use} 按下——那一条就是原版服务端收到
     * 「玩家右键」包之后走的路，所以服务端自己的规矩（够不够得着、冒险模式、别的模组取消了事件）都在里面生效。
     *
     * @return 给模型看的一句话
     */
    public static String useBlock(NumenPlayer body, BlockPos pos) {
        BlockState state = body.getLevel().getBlockState(pos);
        if (state.isAir()) {
            return "那一格是空气，没什么可用的";
        }
        if (!BodyCompat.canReachBlock(body, pos, 0.0)) {
            return "够不着 " + at(pos) + "（手臂只有 " + BodyCompat.blockReach(body) + " 格），先 goto 过去";
        }
        Sight.Trace trace = Aim.use(body, pos);
        if (trace == null) {
            return "看不见 " + at(pos) + "（" + id(state) + "）的任何一面：先绕过去，或者挖开挡着视线的方块";
        }
        Aim.look(body, trace.point());
        BlockHitResult hit = Crosshair.on(body, pos);
        if (hit == null) {
            return "瞄不准 " + at(pos) + "：转过去之后准星落在了别的东西上";
        }
        Effector.Use use = body.hands().use(hit);
        return describeUse(pos, state, use);
    }

    private static String describeUse(BlockPos pos, BlockState before, Effector.Use use) {
        if (use instanceof Effector.Use.Waiting) {
            return "手还没缓过来（原版两次右键至少隔 4 刻），过一下再试";
        }
        if (use instanceof Effector.Use.Refused refused) {
            return "服务端不让用 " + at(refused.pos()) + "：" + refused.reason();
        }
        if (use instanceof Effector.Use.Changed changed) {
            StringBuilder out = new StringBuilder("用了 " + at(pos) + "（" + id(before) + "）");
            for (Effector.Change change : changed.changes()) {
                out.append('\n').append("  ").append(at(change.pos())).append("：")
                        .append(id(change.before())).append(" → ").append(id(change.after()));
            }
            return out.toString();
        }
        // Nothing：按了，但世界里没有方块变化。箱子/工作台/熔炉正是这一种——它们开的是界面。
        return "点了 " + at(pos) + "（" + id(before) + "），世界里没有方块变化"
                + "。如果它是箱子、工作台或熔炉，界面已经开在她那一侧了";
    }

    // ==================== 看背包（inventory）====================

    /** 背包里有什么。主背包 36 格 + 盔甲 4 格 + 副手 1 格，空的不列。 */
    public static String bag(NumenPlayer body) {
        StringBuilder out = new StringBuilder();
        out.append("手里 ").append(name(body.getMainHandItem())).append('，')
                .append("副手 ").append(name(body.getOffhandItem())).append('\n');
        out.append("盔甲：");
        for (EquipmentSlot slot : new EquipmentSlot[] {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            out.append(label(slot)).append('=').append(name(body.getItemBySlot(slot))).append(' ');
        }
        out.append('\n').append("主背包：");
        boolean any = false;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = body.inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            any = true;
            out.append('\n').append("  [").append(slot).append("] ")
                    .append(name(stack)).append(" ×").append(stack.getCount());
        }
        if (!any) {
            out.append("空");
        }
        return out.toString();
    }

    // ==================== 穿装备（inventory / gear）====================

    /**
     * 把背包里的 {@code query} 穿到身上：按物品自己的装备槽（{@link Mob#getEquipmentSlotForItem}）
     * 放进盔甲栏或副手，原来穿着的那件换回背包。
     *
     * <p>⚠️ 不调上游的 {@code Wardrobe.wear}（它属于还没搬的 {@code core/gear}），
     * 但换进去的**是同一个槽**，所以穿完之后原版读到的装备状态是对的。
     */
    public static String equip(NumenPlayer body, String query) {
        int from = find(body, query);
        if (from < 0) {
            return "背包里没有「" + query + "」";
        }
        ItemStack stack = body.inventory.getItem(from);
        EquipmentSlot slot = Mob.getEquipmentSlotForItem(stack);
        if (slot == EquipmentSlot.MAINHAND) {
            return name(stack) + " 不是能穿在身上的（它是手持的，用 goto 过去之后直接挖/放就行）";
        }
        int to = inventoryIndex(slot);
        if (to < 0) {
            return "认不出 " + name(stack) + " 该穿在哪（槽 " + slot + "）";
        }
        ItemStack worn = body.inventory.getItem(to);
        body.inventory.setItem(to, stack.copy());
        body.inventory.setItem(from, worn);
        return "穿上了 " + name(stack) + "（" + label(slot) + "）"
                + (worn.isEmpty() ? "" : "，换下来的 " + name(worn) + " 放回了背包");
    }

    // ==================== 吃东西（inventory）====================

    /**
     * 吃掉背包里的 {@code query}。
     *
     * <p>⚠️ 与上游的差别：上游是**真的把东西举起来等够 useDuration**（{@link com.dwinovo.numen.pathing.body.PlayerHands}
     * 那套按住-松开的机制），这里直接调 {@link ItemStack#finishUsingItem}——营养、效果、
     * 创造模式不扣物品这几件事都由原版那一个方法做，但**省掉了举起与咀嚼的那几刻**。
     * 想看得见「举起来吃」的过程，得等任务框架接上之后改成多刻的 {@code Task}。
     */
    public static String eat(NumenPlayer body, String query) {
        int slot = find(body, query);
        if (slot < 0) {
            return "背包里没有「" + query + "」";
        }
        ItemStack stack = body.inventory.getItem(slot);
        if (!stack.getItem().isEdible()) {
            return name(stack) + " 不是吃的";
        }
        int before = body.getFoodData().getFoodLevel();
        ItemStack after = stack.finishUsingItem(body.getLevel(), body);
        body.inventory.setItem(slot, after);
        return "吃了 " + name(stack) + "，饱食度 " + before + " → " + body.getFoodData().getFoodLevel()
                + "，血量 " + Math.round(body.getHealth()) + "/" + Math.round(body.getMaxHealth());
    }

    // ==================== 丢东西（inventory）====================

    /** 丢掉背包里 {@code query} 的 {@code count} 个（count ≤ 0 表示全丢）。 */
    public static String drop(NumenPlayer body, String query, int count) {
        int slot = find(body, query);
        if (slot < 0) {
            return "背包里没有「" + query + "」";
        }
        ItemStack stack = body.inventory.getItem(slot);
        int take = count <= 0 ? stack.getCount() : Math.min(count, stack.getCount());
        ItemStack dropped = stack.split(take);
        if (stack.isEmpty()) {
            body.inventory.setItem(slot, ItemStack.EMPTY);
        }
        body.drop(dropped, false);
        return "丢掉了 " + name(dropped) + " ×" + take;
    }

    // ==================== 小工具 ====================

    /**
     * 按名字找背包里的一件（返回主背包/盔甲/副手那个统一下标，找不到 -1）。
     *
     * <p>三种写法都认：完整的物品 id（{@code minecraft:bread}）、去掉命名空间的 id（{@code bread}）、
     * 以及物品显示名里的一段（中文客户端上就是中文名）。**从前往后第一个匹配的**，盔甲栏排在主背包后面。
     */
    private static int find(NumenPlayer body, String query) {
        if (query == null || query.isBlank()) {
            return -1;
        }
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Integer> hits = new ArrayList<>();
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = body.inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = id(stack);
            String shortId = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            String display = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            if (itemId.equals(needle) || shortId.equals(needle)
                    || shortId.contains(needle) || display.contains(needle)) {
                hits.add(slot);
            }
        }
        return hits.isEmpty() ? -1 : hits.get(0);
    }

    /** 装备槽在 {@code Inventory} 统一下标里的位置；不是盔甲/副手为 -1。 */
    private static int inventoryIndex(EquipmentSlot slot) {
        return switch (slot) {
            case OFFHAND -> 40;
            case FEET -> 36;
            case LEGS -> 37;
            case CHEST -> 38;
            case HEAD -> 39;
            default -> -1;
        };
    }

    private static String label(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "头";
            case CHEST -> "胸";
            case LEGS -> "腿";
            case FEET -> "脚";
            case OFFHAND -> "副手";
            default -> "主手";
        };
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "空手" : stack.getHoverName().getString();
    }

    private static String id(ItemStack stack) {
        return id(stack.getItem());
    }

    private static String id(Item item) {
        ResourceLocation key = Registry.ITEM.getKey(item);
        return key.toString();
    }

    private static String id(BlockState state) {
        return Registry.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String at(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
