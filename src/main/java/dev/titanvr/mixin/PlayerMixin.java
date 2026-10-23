package dev.titanvr.mixin;

import dev.titanvr.RagdollHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Giants wade through the world: their huge hitbox passes through blocks (they always hover, so no falling
 * through floors) and they are never squeezed into crouching/crawling poses by nearby buildings.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z", opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void titanvr$giantNoclip(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (RagdollHooks.isGiant(self) && self.getAbilities().flying) {
			((Entity) self).noPhysics = true;
		}
	}

	/**
	 * Sprinting doubles flying speed, and a giant's flying speed is already scaled up - Vivecraft's auto-sprint
	 * (stick pushed nearly all the way) or a stick click would fling the giant off in a sudden, coasting dash.
	 */
	@Inject(method = "getFlyingSpeed", at = @At("HEAD"), cancellable = true)
	private void titanvr$giantNoSprintBoost(CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (self.getAbilities().flying && !self.isPassenger() && RagdollHooks.isGiant(self)) {
			cir.setReturnValue(self.getAbilities().getFlyingSpeed());
		}
	}

	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
	private void titanvr$giantsAlwaysFit(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		if (RagdollHooks.isGiant((Player) (Object) this)) {
			cir.setReturnValue(true);
		}
	}
}
