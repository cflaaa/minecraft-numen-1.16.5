package com.dwinovo.numen.core.mixin;

import net.minecraft.world.entity.vehicle.Boat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 船的桨物理入口。原版把 {@code controlBoat}(输入 → 转向 → 前进加速 → 划桨
 * 动画)锁在客户端分支里;同伴的驾驶在服务端,每刻 {@code setInput} 后从这里
 * 调同一段原版数学——不复制一个常数,版本升级时物理永远跟官方走。
 *
 * <p>⚠️ {@code Boat.setInput(ZZZZ)} 在**专用服务端 jar 里被裁掉了**——那 4 个入参只写 4 个私有
 * boolean 字段，是客户端驾驶才调的方法；服务端只跑 {@code controlBoat} 读它们。字段本身在服务端 jar 里
 * 保留（{@code controlBoat} 要用）。所以这里用 {@link Accessor} 直写 4 个输入字段，等价于原版 {@code setInput}。
 * 见 `PORTING.md` §11.3。
 */
@Mixin(Boat.class)
public interface BoatAccessor {

    @Invoker("controlBoat")
    void numen$controlBoat();

    @Accessor("inputLeft")
    void numen$setInputLeft(boolean value);

    @Accessor("inputRight")
    void numen$setInputRight(boolean value);

    @Accessor("inputUp")
    void numen$setInputUp(boolean value);

    @Accessor("inputDown")
    void numen$setInputDown(boolean value);
}
