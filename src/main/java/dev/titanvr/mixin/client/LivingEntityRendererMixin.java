package dev.titanvr.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.titanvr.client.ClientRagdolls;
import dev.titanvr.client.GiantRender;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Avatar;
import dev.titanvr.client.RagdollPose;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ragdolled entities: capture the solved pose and swap the body rotation for the ragdoll's root transform. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
		at = @At("TAIL")
	)
	private void titanvr$extractRagdoll(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		ClientRagdolls.extract(entity, state, partialTicks);
		if (entity instanceof Avatar avatar && state instanceof AvatarRenderState avatarState) {
			GiantRender.extract(avatar, avatarState, partialTicks);
		}
	}

	/** Giants: draw only the floating head and arms instead of a body. */
	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void titanvr$giantBody(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		if (state instanceof AvatarRenderState avatarState && ((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel() instanceof HumanoidModel<?> model) {
			GiantRender.Pose pose = ((FabricRenderState) state).getData(GiantRender.POSE);
			if (pose != null) {
				GiantRender.submit(avatarState, pose, model, poseStack, collector);
				ci.cancel();
			}
		}
	}

	@WrapOperation(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V"
		)
	)
	private void titanvr$ragdollRotations(LivingEntityRenderer<?, ?, ?> self, LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float scale,
		Operation<Void> original) {
		RagdollPose pose = ((FabricRenderState) state).getData(ClientRagdolls.POSE);
		if (pose != null) {
			pose.applyRoot(poseStack);
		} else {
			original.call(self, state, poseStack, bodyRot, scale);
		}
	}
}
