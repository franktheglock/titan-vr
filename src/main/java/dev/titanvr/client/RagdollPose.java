package dev.titanvr.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.Rig;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A solved ragdoll pose for one frame, attached to a LivingEntityRenderState. */
public final class RagdollPose {
	public byte kind;
	/** Translation from the entity's render origin to the pivot, in (already entity-scaled) pose-stack units. */
	public final Vector3f rootOffset = new Vector3f();
	public final Quaternionf rootRot = new Quaternionf();
	/** The pivot's position in the canonical body frame at rest (feet origin at 0), in pose-stack units. */
	public final Vector3f pivotRest = new Vector3f();
	/** Particle rigs other than the humanoid: per-bone swing in model space, and the bone anchor in model pixels. */
	public Rig rig;
	public Quaternionf[] boneRot;
	public Vector3f[] boneFrom;
	public float bodyYaw;
	/** [xRot, zRot] per part. */
	public final float[] head = new float[2], leftArm = new float[2], rightArm = new float[2], leftLeg = new float[2], rightLeg = new float[2];

	/** Replaces LivingEntityRenderer.setupRotations. */
	public void applyRoot(PoseStack poseStack) {
		poseStack.translate(this.rootOffset.x, this.rootOffset.y, this.rootOffset.z);
		poseStack.mulPose(this.rootRot);
		poseStack.translate(-this.pivotRest.x, -this.pivotRest.y, -this.pivotRest.z);
		if (this.kind == TitanNet.RAGDOLL_RIGID) {
			poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - this.bodyYaw));
		} else {
			// Canonical body frame faces +Z; vanilla's model frame expects the 180 degree turn.
			poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		}
	}

	/** Poses the bone parts of any model (the body itself and every layer sharing its part names). */
	public void applyRig(Model<?> model) {
		if (this.rig == null || this.boneRot == null) {
			return;
		}
		ModelPart root = model.root();
		Quaternionf q = new Quaternionf();
		Vector3f euler = new Vector3f();
		Vector3f d = new Vector3f();
		for (int b = 0; b < this.boneRot.length; b++) {
			String name = this.rig.bones[b].part();
			if (!root.hasChild(name)) {
				continue;
			}
			ModelPart part = root.getChild(name);
			PartPose init = part.getInitialPose();
			Quaternionf swing = this.boneRot[b];
			swing.mul(q.rotationZYX(init.zRot(), init.yRot(), init.xRot()), q);
			q.getEulerAnglesZYX(euler);
			part.setRotation(euler.x, euler.y, euler.z);
			// Rotate about the bone's anchor rather than the part's own pivot.
			Vector3f s = this.boneFrom[b];
			swing.transform(d.set(init.x() - s.x, init.y() - s.y, init.z() - s.z));
			part.setPos(s.x + d.x, s.y + d.y, s.z + d.z);
		}
	}

	/** Poses a humanoid model's limbs from the ragdoll (runs after the model's own animation). */
	public void applyModel(HumanoidModel<?> model) {
		if (this.kind != TitanNet.RAGDOLL_HUMANOID) {
			return;
		}
		model.body.setRotation(0, 0, 0);
		model.head.setRotation(this.head[0], 0, this.head[1]);
		model.leftArm.setRotation(this.leftArm[0], 0, this.leftArm[1]);
		model.rightArm.setRotation(this.rightArm[0], 0, this.rightArm[1]);
		model.leftLeg.setRotation(this.leftLeg[0], 0, this.leftLeg[1]);
		model.rightLeg.setRotation(this.rightLeg[0], 0, this.rightLeg[1]);
	}
}
