package dev.titanvr.mixin;

import dev.titanvr.physics.GiantProjectiles;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Remembers how much a giant's projectile has been scaled up. */
@Mixin(Projectile.class)
public abstract class ProjectileMixin implements GiantProjectiles.Scaled {
	@Unique
	private float titanvr$scale;

	@Override
	public float titanvr$getScale() {
		return this.titanvr$scale;
	}

	@Override
	public void titanvr$setScale(float scale) {
		this.titanvr$scale = scale;
	}
}
