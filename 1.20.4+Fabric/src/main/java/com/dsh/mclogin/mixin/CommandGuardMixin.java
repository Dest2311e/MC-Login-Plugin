package com.dsh.mclogin.mixin;

import com.dsh.mclogin.listener.LoginRestrictionListener;
import com.dsh.mclogin.message.MessageService;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandGuardMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void mclogin$command(ParseResults<CommandSourceStack> parsed, String command, CallbackInfo ci) {
        if (parsed.getContext().getSource().getEntity() instanceof ServerPlayer player && LoginRestrictionListener.blockCommand(player, command)) {
            player.sendSystemMessage(MessageService.component("mclogin.restrict.command"));
            ci.cancel();
        }
    }
}
