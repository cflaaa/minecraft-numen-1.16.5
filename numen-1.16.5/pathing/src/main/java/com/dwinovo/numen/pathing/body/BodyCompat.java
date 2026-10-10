package com.dwinovo.numen.pathing.body;

import com.dwinovo.numen.pathing.world.Reach;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 这一代(1.20.1)的原版里,身体的交互距离、潜行速度、重力不是属性,而是写在各处的常数与公式。寻路的其余代码只问身体"够得多远、
 * 蹲着走多快、重力多大",答案统一从这里取:数值与后来的属性默认值一致,算法照这一代原版。
 */
public final class BodyCompat {

    /** 起跳的初速度(未算脚下方块的起跳系数)。 */
    public static final double JUMP_STRENGTH = 0.42;

    /** 玩家不跳就能迈上去的高度,后来的属性 {@code step_height} 的默认值。 */
    private static final float STEP_HEIGHT = 0.6F;

    private BodyCompat() {}

    /**
     * 让服务端的身体像真玩家那样走路。这一代的 {@code ServerPlayer} 构造时把迈步高度设成 1.0(为了迁就客户端的自动起跳),
     * 一格高的门板、台阶都被它直接迈上去,不跳也不开门;真玩家在客户端上是 0.6,后来的版本里服务端玩家也是 0.6。
     * 寻路(规划与执行读同一个迈步高度)与身体的物理都照 0.6。
     */
    public static void walkLikeAPlayer(ServerPlayer body) {
        // 1.16.5 没有 setMaxUpStep,迈步高度就是一个 public 字段,直接写。
        body.maxUpStep = STEP_HEIGHT;
    }

    /** 方块的交互距离:生存 4.5,创造 5(客户端拾取 {@code getPickRange})。 */
    public static double blockReach(ServerPlayer body) {
        return body.abilities.instabuild ? 5.0 : 4.5;
    }

    /** 实体的交互距离:生存 3,创造 6(客户端 {@code GameRenderer.pick})。 */
    public static double entityReach(ServerPlayer body) {
        return body.abilities.instabuild ? 6.0 : 3.0;
    }

    /** 够不够得着一格方块:眼睛到那一格的碰撞盒不超过方块交互距离加 {@code extra}。 */
    public static boolean canReachBlock(ServerPlayer body, BlockPos pos, double extra) {
        double range = blockReach(body) + extra;
        return Reach.distanceToSqr(new AABB(pos), body.getEyePosition(1.0F)) < range * range;
    }

    /**
     * 够不够得着一只实体,照后来的 {@code Player.canInteractWithEntity}:眼睛到它的碰撞盒(外扩一个拾取半径)不超过实体交互距离加
     * {@code extra}。
     */
    public static boolean canReachEntity(ServerPlayer body, Entity target, double extra) {
        double range = entityReach(body) + extra;
        return Reach.distanceToSqr(target.getBoundingBox().inflate(target.getPickRadius()),
                body.getEyePosition(1.0F)) < range * range;
    }

    /**
     * 服务端对"用方块"一下的距离检查,照 {@code ServerGamePacketListenerImpl.handleUseItemOn}:眼睛到那一格中心不超过 6。
     */
    public static boolean canUseBlock(ServerPlayer body, BlockPos pos) {
        return body.getEyePosition(1.0F).distanceToSqr(Vec3.atCenterOf(pos)) <= 36.0;
    }

    /**
     * 蹲着走时前后左右冲量的乘数:基值 0.3。
     *
     * <p>1.20 这里还会加上迅捷潜行附魔的加成({@code EnchantmentHelper.getSneakingSpeedBonus}),
     * 但**1.16.5 没有迅捷潜行**(那个附魔是 1.19 才加的),所以就是基值一个常数。
     */
    public static float sneakFactor(ServerPlayer body) {
        return 0.3F;
    }

    /** 每刻的重力加速度:0.08,缓降效果下落时 0.01,无重力为 0(原版 {@code LivingEntity.travel})。 */
    public static double gravity(ServerPlayer body) {
        if (body.isNoGravity()) {
            return 0.0;
        }
        return body.getDeltaMovement().y <= 0.0 && body.hasEffect(MobEffects.SLOW_FALLING) ? 0.01 : 0.08;
    }

    /**
     * 从俯仰与偏航算出朝向。
     *
     * <p>⚠️ <b>不能调 {@code Vec3.directionFromRotation(pitch, yaw)}</b>：它在 Loom 编译用的**合并 jar 里有**，
     * 却在**专用服务端的 jar 里被 ProGuard 裁掉了**（纯客户端用的方向助手）——编译期看不出来，
     * 一到服务端就是 NoSuchMethodError。照它的字节码原样抄一份，两个调用点共用。
     */
    public static Vec3 directionFromRotation(float pitch, float yaw) {
        float cosYaw = Mth.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = Mth.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -Mth.cos(-pitch * 0.017453292F);
        float sinPitch = Mth.sin(-pitch * 0.017453292F);
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }
}
