package dev.titanvr.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Giants are drawn Voxel-Destruct style: no body, just a big floating head (at the headset) and two big
 * arms ending at the controllers. Uses the player's own skin (head + hat, arms + sleeves).
 */
public final class GiantRender {
	public static final RenderStateDataKey<Pose> POSE = RenderStateDataKey.create(() -> "titanvr:giant_pose");

	/** Head + hands relative to the render state's origin, in world units. */
	public static final class Pose {
		final Vector3f[] offset = {new Vector3f(), new Vector3f(), new Vector3f()};
		final Quaternionf[] rot = {new Quaternionf(), new Quaternionf(), new Quaternionf()};
		float scale;
		boolean leftHanded;
		/** The local player's own first-person view: draw the arms only, the head would sit on the camera. */
		boolean armsOnly;
	}

	/** Model axes -> controller axes for an arm (+Y down the arm = controller forward, front of arm = controller up). */
	private static final Quaternionf ARM_BASIS = new Matrix3f(-1, 0, 0, 0, 0, -1, 0, -1, 0).getNormalizedRotation(new Quaternionf());
	/** Model axes -> headset axes for the head (model -Y up, model -Z = face forward). */
	private static final Quaternionf HEAD_BASIS = new Matrix3f(-1, 0, 0, 0, -1, 0, 0, 0, 1).getNormalizedRotation(new Quaternionf());

	private GiantRender() {
	}

	public static void extract(Avatar entity, AvatarRenderState state, float partialTick) {
		FabricRenderState fs = (FabricRenderState) state;
		if (!(entity instanceof Player player) || !GiantClient.isGiantId(player.getId())) {
			fs.setData(POSE, null);
			return;
		}
		Pose pose = new Pose();
		pose.scale = GiantClient.scaleOf(player.getId());
		Vector3d[] pos = {new Vector3d(), new Vector3d(), new Vector3d()};
		Quaterniond[] rot = {new Quaterniond(), new Quaterniond(), new Quaterniond()};
		int vr = VivecraftClientCompat.playerRenderPose(player, pos, rot);
		if (vr < 0) {
			desktopPose(player, pose.scale, partialTick, pos, rot);
		}
		pose.leftHanded = vr == 1;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		pose.armsOnly = player == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
		for (int i = 0; i < 3; i++) {
			pose.offset[i].set((float) (pos[i].x - state.x), (float) (pos[i].y - state.y), (float) (pos[i].z - state.z));
			pose.rot[i].set((float) rot[i].x, (float) rot[i].y, (float) rot[i].z, (float) rot[i].w);
		}
		fs.setData(POSE, pose);
	}

	/** Non-VR giants: head at the eyes looking where the camera looks, hands floating in front. */
	private static void desktopPose(Player player, float scale, float partialTick, Vector3d[] pos, Quaterniond[] rot) {
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 look = player.getViewVector(partialTick);
		Vector3d f = new Vector3d(look.x, look.y, look.z).normalize();
		Vector3d r = new Vector3d(f).cross(0, 1, 0);
		if (r.lengthSquared() < 1e-6) {
			r.set(1, 0, 0);
		}
		r.normalize();
		Vector3d u = new Vector3d(r).cross(f);
		Quaterniond q = new Quaterniond().setFromNormalized(new org.joml.Matrix3d(r.x, r.y, r.z, u.x, u.y, u.z, -f.x, -f.y, -f.z));
		pos[0].set(eye.x, eye.y, eye.z);
		rot[0].set(q);
		Giant g = new Giant(player.getUUID(), scale);
		Vec3 interp = player.getPosition(partialTick);
		for (int hand = 0; hand < 2; hand++) {
			GiantManager.desktopHand(player, g, hand, pos[hand + 1], new Quaterniond());
			pos[hand + 1].add(interp.x - player.getX(), interp.y - player.getY(), interp.z - player.getZ());
			rot[hand + 1].set(q);
		}
	}

	/** Called instead of the normal body render. */
	public static void submit(AvatarRenderState state, Pose pose, HumanoidModel<?> model, PoseStack poseStack, SubmitNodeCollector collector) {
		var renderType = RenderTypes.entityTranslucent(state.skin.body().texturePath());
		int light = state.lightCoords;
		int overlay = OverlayTexture.NO_OVERLAY;
		// Head: centre of the 8x8x8 head box sits at the headset.
		if (!pose.armsOnly) {
			part(model.head, pose.offset[0], pose.rot[0], HEAD_BASIS, pose.scale, 0, -4, 0, poseStack, collector, renderType, light, overlay);
		}
		// Arms: the hand end (bottom of the 12px arm) sits at each controller; main hand is the right arm unless left-handed.
		ModelPart mainArm = pose.leftHanded ? model.leftArm : model.rightArm;
		ModelPart offArm = pose.leftHanded ? model.rightArm : model.leftArm;
		armPart(mainArm, pose.offset[1], pose.rot[1], pose.scale, poseStack, collector, renderType, light, overlay);
		armPart(offArm, pose.offset[2], pose.rot[2], pose.scale, poseStack, collector, renderType, light, overlay);
		// Held items, giant-sized (first person already shows them through the normal hand renderer).
		if (!pose.armsOnly) {
			boolean mainRight = !pose.leftHanded;
			heldItem(state, state.getMainHandItemState(), mainRight, pose.offset[1], pose.rot[1], pose.scale, poseStack, collector, light);
			ItemStackRenderState off = state.getMainHandItemState() == state.rightHandItemState ? state.leftHandItemState : state.rightHandItemState;
			heldItem(state, off, !mainRight, pose.offset[2], pose.rot[2], pose.scale, poseStack, collector, light);
		}
	}

	/**
	 * The vanilla third-person held-item transform from the arm's frame, with the item turned to point on along
	 * the arm - i.e. along the controller, which is where the tool's hitbox is (see HeldTools).
	 */
	private static void heldItem(AvatarRenderState state, ItemStackRenderState item, boolean rightArm, Vector3f offset, Quaternionf rot, float scale,
		PoseStack poseStack, SubmitNodeCollector collector, int light) {
		if (item.isEmpty()) {
			return;
		}
		float side = rightArm ? 1 : -1;
		poseStack.pushPose();
		poseStack.translate(offset.x, offset.y, offset.z);
		poseStack.mulPose(rot);
		poseStack.mulPose(ARM_BASIS);
		poseStack.scale(scale, scale, scale);
		// Origin is at the hand end of the arm; the grip is 2px in front of it. Turn about the grip, then step
		// back to the arm's pivot frame for the vanilla transform.
		poseStack.translate(0.0F, 0.0F, -2.0F / 16.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
		poseStack.translate(side / 16.0F, -10.0F / 16.0F, 2.0F / 16.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		poseStack.translate(side / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
		item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
		poseStack.popPose();
	}

	private static void armPart(ModelPart arm, Vector3f offset, Quaternionf rot, float scale, PoseStack poseStack, SubmitNodeCollector collector,
		net.minecraft.client.renderer.rendertype.RenderType renderType, int light, int overlay) {
		PartPose initial = arm.getInitialPose();
		// Arm boxes are 4 wide starting 1-3px off the pivot; the hand end is 10px below the pivot.
		float centerX = initial.x() < 0 ? -1 : 1;
		part(arm, offset, rot, ARM_BASIS, scale, centerX, 10, 0, poseStack, collector, renderType, light, overlay);
	}

	/**
	 * Draws a model part so that the model-space point (pivot + local cx, cy, cz) lands at the given world offset,
	 * oriented by rot * basis and scaled. Model.Simple resets the part to its initial pose before drawing,
	 * so the initial pivot translation is compensated here.
	 */
	private static void part(ModelPart part, Vector3f offset, Quaternionf rot, Quaternionf basis, float scale, float cx, float cy, float cz,
		PoseStack poseStack, SubmitNodeCollector collector, net.minecraft.client.renderer.rendertype.RenderType renderType, int light, int overlay) {
		PartPose initial = part.getInitialPose();
		poseStack.pushPose();
		poseStack.translate(offset.x, offset.y, offset.z);
		poseStack.mulPose(rot);
		poseStack.mulPose(basis);
		poseStack.scale(scale, scale, scale);
		poseStack.translate(-(initial.x() + cx) / 16.0F, -(initial.y() + cy) / 16.0F, -(initial.z() + cz) / 16.0F);
		collector.submitModelPart(part, poseStack, renderType, light, overlay, null);
		poseStack.popPose();
	}
}
