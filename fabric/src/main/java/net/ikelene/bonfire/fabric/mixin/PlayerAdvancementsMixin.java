package net.ikelene.bonfire.fabric.mixin;

import net.ikelene.bonfire.fabric.AdvancementHookMarker;
import net.ikelene.bonfire.fabric.BonfireFabric;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin implements AdvancementHookMarker {
    @Shadow
    private ServerPlayer player;

    @Shadow
    public abstract AdvancementProgress getOrStartProgress(AdvancementHolder holder);

    @Unique
    private final ArrayDeque<Boolean> bonfire$wasDone = new ArrayDeque<>();

    @Inject(method = "award", at = @At("HEAD"))
    private void bonfire$awardHead(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        bonfire$wasDone.push(this.getOrStartProgress(holder).isDone());
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void bonfire$awardReturn(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        Boolean was = bonfire$wasDone.poll();
        if (was != null && !was && this.getOrStartProgress(holder).isDone()) {
            BonfireFabric.onAdvancementDone(this.player, holder);
        }
    }
}
