package dev.titanvr.mixin.client;

import dev.titanvr.client.ThrowTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Per-frame hook: sample hand poses for throw estimation. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void titanvr$sampleHands(boolean advanceGameTime, CallbackInfo ci) {
		ThrowTracker.sample();
	}
}
