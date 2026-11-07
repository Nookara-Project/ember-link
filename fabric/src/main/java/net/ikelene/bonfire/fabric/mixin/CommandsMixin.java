package net.ikelene.bonfire.fabric.mixin;

import net.ikelene.bonfire.fabric.BonfireFabric;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performPrefixedCommand", at = @At("HEAD"))
    private void bonfire$localCommand(CommandSourceStack sender, String command, CallbackInfo ci) {
        BonfireFabric.onPrefixedCommand(sender, command);
    }
}
