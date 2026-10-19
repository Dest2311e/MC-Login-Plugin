package com.dsh.mclogin.mixin;

import com.dsh.mclogin.listener.LoginRestrictionListener;
import com.dsh.mclogin.ui.LoginUiMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ConnectionGuardMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = {"handleMovePlayer", "handleMoveVehicle"}, at = @At("HEAD"), cancellable = true)
    private void mclogin$movement(CallbackInfo ci) {
        if (LoginRestrictionListener.blockMovement(player)) ci.cancel();
    }
    @Inject(method = {"handlePlayerAction", "handleUseItemOn", "handleUseItem", "handleInteract", "handleContainerButtonClick", "handleSetCreativeModeSlot", "handlePlaceRecipe"}, at = @At("HEAD"), cancellable = true)
    private void mclogin$interaction(CallbackInfo ci) {
        if (LoginRestrictionListener.blockInteraction(player)) ci.cancel();
    }
    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void mclogin$container(CallbackInfo ci) {
        if (LoginRestrictionListener.blockInteraction(player) && !(player.containerMenu instanceof LoginUiMenu)) ci.cancel();
    }
}
