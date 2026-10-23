package dev.titanvr.client;

import dev.titanvr.net.TitanNet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** First-person view of your own ragdoll: eyes in its head, looking where its face points. */
public final class RagdollCamera {
	/** Camera-local axes (forward -Z, left -X) -> canonical body axes (forward +Z, left +X). */
	private static final Quaterniond CAMERA_TO_BODY = new Quaterniond().rotationY(Math.PI);
	private static final Quaternionf smoothed = new Quaternionf();
	private static boolean active;

	private RagdollCamera() {
	}

	public static boolean pose(Entity entity, float partialTick, double[] posOut, Quaternionf rotOut) {
		ClientRagdolls.Entry e = ClientRagdolls.get(entity.getId());
		if (e == null || e.cur == null || e.kind != TitanNet.RAGDOLL_HUMANOID || e.rig == null) {
			active = false;
			return false;
		}
		double[] v = ClientRagdolls.interpolated(entity, e, partialTick);
		Quaterniond body = e.rig.torsoRotation(v, new Quaterniond());
		// Eyes sit on the front of the head.
		int o = e.rig.head * 3;
		Vector3d eye = body.transform(new Vector3d(0, 0.03, 0.12).mul(e.param)).add(v[o], v[o + 1], v[o + 2]);
		Quaterniond cam = new Quaterniond(body).mul(CAMERA_TO_BODY);
		Quaternionf target = new Quaternionf((float) cam.x, (float) cam.y, (float) cam.z, (float) cam.w);
		if (!active) {
			smoothed.set(target);
			active = true;
		} else {
			// Take the edge off the per-frame jitter of the particle solve without lagging behind a fast tumble.
			smoothed.slerp(target, 0.5F);
		}
		posOut[0] = eye.x;
		posOut[1] = eye.y;
		posOut[2] = eye.z;
		rotOut.set(smoothed);
		return true;
	}

	/** When you get back up, keep facing roughly the way you landed instead of snapping back. */
	static void onEnd(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (!active || mc.player == null || mc.player.getId() != entityId) {
			return;
		}
		active = false;
		org.joml.Vector3f fwd = smoothed.transform(new org.joml.Vector3f(0, 0, -1));
		if (fwd.x * fwd.x + fwd.z * fwd.z > 1e-4F) {
			float yaw = (float) Math.toDegrees(Math.atan2(-fwd.x, fwd.z));
			mc.player.setYRot(yaw);
			mc.player.setYHeadRot(yaw);
			mc.player.yRotO = yaw;
		}
		mc.player.setXRot(0);
		mc.player.xRotO = 0;
	}
}
