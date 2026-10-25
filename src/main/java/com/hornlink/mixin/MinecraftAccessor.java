package com.hornlink.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the private "right click" method of the game client. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Invoker("startUseItem")
    void hornlink$startUseItem();
}
