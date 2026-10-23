package dev.titanvr.mixin;

import dev.titanvr.physics.GiantProjectiles;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Giant projectiles get giant hitboxes. */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "getDimensions", at = @At("RETURN"), cancellable = true)
	private void titanvr$giantProjectile(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		float scale = GiantProjectiles.scaleOf((Entity) (Object) this);
		if (scale > 1) {
			cir.setReturnValue(cir.getReturnValue().scale(scale));
		}
	}
}
