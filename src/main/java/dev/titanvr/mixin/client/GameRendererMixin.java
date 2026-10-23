package dev.titanvr.mixin.client;

import dev.titanvr.client.ClientRagdolls;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand floating in front of a tumbling ragdoll camera. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void titanvr$hideHandWhileRagdolled(CameraRenderState cameraState, float deltaPartialTick, Matrix4fc modelViewMatrix, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && ClientRagdolls.isRagdolled(mc.player.getId())) {
			ci.cancel();
		}
	}
}
