package com.dsh.mclogin.mixin;

import com.dsh.mclogin.listener.LoginRestrictionListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemPickupGuardMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void mclogin$pickup(Player toucher, CallbackInfo ci) {
        if (toucher instanceof ServerPlayer player && LoginRestrictionListener.blockInteraction(player)) ci.cancel();
    }
}
