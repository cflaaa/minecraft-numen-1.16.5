package com.dwinovo.numen.core.mc;

import com.dwinovo.numen.core.mixin.IngredientAccessor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 取 {@link Ingredient} 能匹配的全部物品。
 *
 * <p>1.16.5 差异：{@code Ingredient.getItems()} 在专用服务端 jar 里被裁掉（配方书物品枚举只在客户端做）。
 * 这里经 {@link IngredientAccessor} 走同一条路（{@code dissolve()} 填缓存字段后读回），返回同一份数组。
 * 见 `PORTING.md` §11.3。
 */
public final class Ingredients {

    private Ingredients() {}

    /** {@code ing.getItems()} 的服务端等价；返回 {@code null} 当且仅当原版也会返回 null。 */
    public static ItemStack[] items(Ingredient ing) {
        // Ingredient 是 final 类：编译期不认它实现了 accessor 接口，强转要经 Object 中转
        // （mixin 在运行期才把接口织进去）。
        IngredientAccessor a = (IngredientAccessor) (Object) ing;
        a.numen$dissolve();
        return a.numen$getItemStacks();
    }
}