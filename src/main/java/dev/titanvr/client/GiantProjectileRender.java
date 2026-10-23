package dev.titanvr.client;

import dev.titanvr.physics.GiantProjectiles;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/** Client half of giant projectiles: size them from their owner when they appear, and scale their rendering. */
public final class GiantProjectileRender {
	public static final RenderStateDataKey<Float> SCALE = RenderStateDataKey.create(() -> "titanvr:projectile_scale");

	private GiantProjectileRender() {
	}

	public static void onLoad(Entity entity) {
		if (entity instanceof Projectile p && p.getOwner() != null && GiantClient.isGiantId(p.getOwner().getId())) {
			float scale = GiantClient.scaleOf(p.getOwner().getId());
			if (scale > 1.5F) {
				((GiantProjectiles.Scaled) p).titanvr$setScale(scale);
				p.refreshDimensions();
			}
		}
	}

	public static void extract(Entity entity, FabricRenderState state) {
		float scale = GiantProjectiles.scaleOf(entity);
		state.setData(SCALE, scale > 1 ? scale : null);
	}
}
