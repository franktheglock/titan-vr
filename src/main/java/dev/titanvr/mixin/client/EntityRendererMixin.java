package dev.titanvr.mixin.client;

import dev.titanvr.client.GiantProjectileRender;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void titanvr$extractProjectileScale(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		GiantProjectileRender.extract(entity, (FabricRenderState) state);
	}
}
