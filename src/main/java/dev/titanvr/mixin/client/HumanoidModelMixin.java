package dev.titanvr.mixin.client;

import dev.titanvr.client.ClientRagdolls;
import dev.titanvr.client.RagdollPose;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every submitted model is posed (setupAnim) right before it is drawn. For humanoid models of a ragdolled
 * entity - the body itself and every armor/fur/clothing layer - overwrite the limb angles with the ragdoll's.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class HumanoidModelMixin {
	@Inject(
		method = "prepareModel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V")
	)
	private void titanvr$poseRagdoll(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		Model<?> model = submit.model();
		if (submit.state() instanceof FabricRenderState state) {
			RagdollPose pose = state.getData(ClientRagdolls.POSE);
			if (pose == null) {
				return;
			}
			if (model instanceof HumanoidModel<?> humanoid) {
				pose.applyModel(humanoid);
			}
			pose.applyRig(model);
		}
	}
}
