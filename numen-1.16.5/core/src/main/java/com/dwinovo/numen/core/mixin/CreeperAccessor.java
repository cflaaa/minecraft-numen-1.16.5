package com.dwinovo.numen.core.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link Creeper} 的充能状态键 {@code DATA_SWELL_DIR} 与点火键 {@code DATA_IS_IGNITED}。
 *
 * <p>⚠️ 源码本来写的是 {@code creeper.getSwellDir() > 0 || creeper.isIgnited()}（`Menace.fusing`），
 * 但这两个方法在**专用服务端 jar 里被 ProGuard 裁掉了**——服务端判充能看的是实体数据，
 * 不带"getSwellDir/isIgnited"这两个便捷方法（`getSwelling(float)` 那条线保留在 `Creeper` 自己身上）。
 * 这里直取私有静态键，用 {@code creeper.getEntityData().get(key)} 读——和原版自己读充能状态同一条路。
 * 这是「合并 jar 有、专用服务端 jar 没有」那一类坑的又一例（见 `PORTING.md` §11.3）。
 */
@Mixin(Creeper.class)
public interface CreeperAccessor {

    @Accessor("DATA_SWELL_DIR")
    EntityDataAccessor<Integer> numen$getSwellDirKey();

    @Accessor("DATA_IS_IGNITED")
    EntityDataAccessor<Boolean> numen$getIsIgnitedKey();

    @Accessor("DATA_IS_POWERED")
    EntityDataAccessor<Boolean> numen$getIsPoweredKey();
}