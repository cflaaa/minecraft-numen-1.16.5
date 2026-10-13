package com.dwinovo.numen.core.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the backing-container index on a menu slot (public index is the menu index). */
@Mixin(Slot.class)
public interface SlotAccessor {
    @Accessor("slot")
    int numen$containerSlot();
}
