package com.dwinovo.numen.core.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 直取 {@link Ingredient} 的原料物品数组。
 *
 * <p>⚠️ 源码本来写的是 {@code ing.getItems()}，但**这个方法在专用服务端 jar 里被 ProGuard 裁掉了**
 * （配方书的物品枚举只在客户端做；服务端合成只问 {@code test(ItemStack)}）。
 * {@code getItems()} 的实现就是「先 {@code dissolve()} 填缓存字段 {@code itemStacks}，再返回它」，
 * 而 {@code dissolve} 与字段在服务端 jar 里都保留。这里照抄同一条路：{@code dissolve()} + 读字段。
 * 这是「合并 jar 有、专用服务端 jar 没有」那一类坑的又一例（见 `PORTING.md` §11.3）。
 */
@Mixin(Ingredient.class)
public interface IngredientAccessor {

    @Invoker("dissolve")
    void numen$dissolve();

    @Accessor("itemStacks")
    ItemStack[] numen$getItemStacks();
}