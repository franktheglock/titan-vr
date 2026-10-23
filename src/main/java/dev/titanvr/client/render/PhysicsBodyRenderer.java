package dev.titanvr.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.titanvr.client.GiantClient;
import dev.titanvr.entity.PhysicsBodyEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.AbstractMinecartRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Draws a voxel rigid body as real block models, oriented by its interpolated rotation. */
public class PhysicsBodyRenderer extends EntityRenderer<PhysicsBodyEntity, PhysicsBodyRenderer.State> {
	private final BlockModelResolver blockModelResolver;

	public static class State extends EntityRenderState {
		ClientShape shape;
		final Quaternionf rot = new Quaternionf();
		/** Extra translation for locally predicted (held) bodies. */
		double ox, oy, oz;
	}

	public PhysicsBodyRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.blockModelResolver = context.getBlockModelResolver();
		this.shadowRadius = 0.0F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(PhysicsBodyEntity entity, Frustum culler, double camX, double camY, double camZ) {
		return entity.clientShape != null && super.shouldRender(entity, culler, camX, camY, camZ);
	}

	@Override
	public void extractRenderState(PhysicsBodyEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		ClientShape shape = (ClientShape) entity.clientShape;
		state.shape = shape;
		state.ox = state.oy = state.oz = 0;
		if (shape == null) {
			return;
		}
		if (shape.models == null) {
			shape.models = new BlockModelRenderState[shape.count()];
			for (int i = 0; i < shape.count(); i++) {
				BlockModelRenderState m = new BlockModelRenderState();
				if (shape.states[i] != null) {
					this.blockModelResolver.update(m, shape.states[i], AbstractMinecartRenderer.BLOCK_DISPLAY_CONTEXT);
				}
				shape.models[i] = m;
			}
		}
		entity.rotO.slerp(entity.rot, partialTicks, state.rot);
		Vector3d predicted = new Vector3d();
		Quaternionf predictedRot = new Quaternionf();
		if (GiantClient.predictHeldBody(entity.getId(), partialTicks, predicted, predictedRot)) {
			state.ox = predicted.x - state.x;
			state.oy = predicted.y - state.y;
			state.oz = predicted.z - state.z;
			state.rot.set(predictedRot);
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		ClientShape shape = state.shape;
		if (shape == null || shape.models == null) {
			return;
		}
		poseStack.pushPose();
		poseStack.translate(state.ox, state.oy, state.oz);
		poseStack.mulPose(state.rot);
		for (int i = 0; i < shape.count(); i++) {
			BlockModelRenderState model = shape.models[i];
			if (model.isEmpty()) {
				continue;
			}
			float hx = shape.boxes[i * 6 + 3], hy = shape.boxes[i * 6 + 4], hz = shape.boxes[i * 6 + 5];
			poseStack.pushPose();
			poseStack.translate(shape.boxes[i * 6] - hx, shape.boxes[i * 6 + 1] - hy, shape.boxes[i * 6 + 2] - hz);
			model.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
