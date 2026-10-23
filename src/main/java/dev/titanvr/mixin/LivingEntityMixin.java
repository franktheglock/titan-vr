package dev.titanvr.mixin;

import dev.titanvr.RagdollHooks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ragdolls are moved by the physics engine; their own movement (AI, input, gravity) is switched off. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void titanvr$freezeRagdoll(Vec3 input, CallbackInfo ci) {
		if (RagdollHooks.isRagdolled((LivingEntity) (Object) this)) {
			ci.cancel();
		}
	}
}
