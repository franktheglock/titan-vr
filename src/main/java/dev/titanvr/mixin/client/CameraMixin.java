package dev.titanvr.mixin.client;

import dev.titanvr.client.RagdollCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First person while ragdolled: the camera sits in the ragdoll's head and tumbles with it. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private Entity entity;
	@Shadow
	private boolean detached;
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private Quaternionf rotation;
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private Vector3f forwards;
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private Vector3f up;
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private Vector3f left;
	@Shadow
	private int matrixPropertiesDirty;

	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void titanvr$ragdollCamera(float partialTicks, CallbackInfo ci) {
		if (this.detached || this.entity == null) {
			return;
		}
		double[] pos = new double[3];
		Quaternionf rot = new Quaternionf();
		if (!RagdollCamera.pose(this.entity, partialTicks, pos, rot)) {
			return;
		}
		this.setPosition(pos[0], pos[1], pos[2]);
		this.rotation.set(rot);
		this.forwards.set(0, 0, -1).rotate(this.rotation);
		this.up.set(0, 1, 0).rotate(this.rotation);
		this.left.set(-1, 0, 0).rotate(this.rotation);
		this.matrixPropertiesDirty |= 3;
	}
}
