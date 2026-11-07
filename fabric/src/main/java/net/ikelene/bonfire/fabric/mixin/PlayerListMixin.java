package net.ikelene.bonfire.fabric.mixin;

import net.ikelene.bonfire.fabric.BonfireFabric;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void bonfire$remove(ServerPlayer player, CallbackInfo ci) {
        BonfireFabric.onPlayerRemoved(player);
    }
}
