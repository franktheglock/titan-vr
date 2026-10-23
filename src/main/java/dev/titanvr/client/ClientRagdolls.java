package dev.titanvr.client;

import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.Rig;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Client-side copies of server ragdoll poses, plus the per-frame pose solving used by the render mixins. */
public final class ClientRagdolls {
	public static final RenderStateDataKey<RagdollPose> POSE = RenderStateDataKey.create(() -> "titanvr:ragdoll_pose");

	public static final class Entry {
		byte kind;
		Rig rig;
		float param;
		double[] prev;
		double[] cur;
		int lastUpdateTick;
	}

	private static final Int2ObjectOpenHashMap<Entry> ENTRIES = new Int2ObjectOpenHashMap<>();

	private ClientRagdolls() {
	}

	public static boolean isRagdolled(int entityId) {
		return ENTRIES.containsKey(entityId);
	}

	public static Entry get(int entityId) {
		return ENTRIES.get(entityId);
	}

	public static void clear() {
		ENTRIES.clear();
	}

	public static void handle(TitanNet.RagdollStatesS2C p) {
		int v = 0;
		int tick = Minecraft.getInstance().level != null ? (int) Minecraft.getInstance().level.getGameTime() : 0;
		for (int i = 0; i < p.ids().length; i++) {
			byte kind = p.kinds()[i];
			Rig rig = Rig.byId(p.rigs()[i]);
			int id = p.ids()[i];
			int count = TitanNet.RagdollStatesS2C.valueCount(kind, p.rigs()[i]);
			if (kind == TitanNet.RAGDOLL_END) {
				if (ENTRIES.remove(id) != null) {
					RagdollCamera.onEnd(id);
				}
				continue;
			}
			double[] values = new double[count];
			System.arraycopy(p.values(), v, values, 0, count);
			v += count;
			Entry e = ENTRIES.get(id);
			if (e == null || e.kind != kind || e.rig != rig) {
				e = new Entry();
				e.kind = kind;
				e.rig = rig;
				e.prev = values;
				ENTRIES.put(id, e);
			} else {
				e.prev = e.cur;
			}
			e.param = p.params()[i];
			e.cur = values;
			e.lastUpdateTick = tick;
		}
	}

	/** Drops ragdolls we stopped hearing about (entity left tracking range). */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			ENTRIES.clear();
			return;
		}
		int now = (int) mc.level.getGameTime();
		ENTRIES.int2ObjectEntrySet().removeIf(e -> now - e.getValue().lastUpdateTick > 40 || mc.level.getEntity(e.getIntKey()) == null);
		// Keep the local player's own entity glued to its ragdoll so the camera rides along smoothly.
		if (mc.player != null) {
			Entry e = ENTRIES.get(mc.player.getId());
			if (e != null && e.kind == TitanNet.RAGDOLL_HUMANOID) {
				double eye = mc.player.getEyeHeight();
				int o = e.rig.head * 3;
				mc.player.setPos(e.cur[o], e.cur[o + 1] - eye, e.cur[o + 2]);
				mc.player.setDeltaMovement(0, 0, 0);
			} else if (e != null && e.kind == TitanNet.RAGDOLL_RIGID) {
				mc.player.setPos(e.cur[0], e.cur[1] - e.param, e.cur[2]);
				mc.player.setDeltaMovement(0, 0, 0);
			}
		}
	}

	/** Interpolated values for this frame, including local "held by my hand" prediction. */
	static double[] interpolated(Entity entity, Entry e, float partialTick) {
		int n = e.cur.length;
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			out[i] = e.prev[i] + (e.cur[i] - e.prev[i]) * partialTick;
		}
		if (e.kind == TitanNet.RAGDOLL_RIGID) {
			Quaternionf a = new Quaternionf((float) e.prev[3], (float) e.prev[4], (float) e.prev[5], (float) e.prev[6]);
			Quaternionf b = new Quaternionf((float) e.cur[3], (float) e.cur[4], (float) e.cur[5], (float) e.cur[6]);
			a.slerp(b, partialTick);
			out[3] = a.x;
			out[4] = a.y;
			out[5] = a.z;
			out[6] = a.w;
		}
		GiantClient.applyHeldPrediction(entity.getId(), e.kind, out, partialTick);
		return out;
	}

	/** Called from the LivingEntityRenderer mixin at the end of extractRenderState. */
	public static void extract(LivingEntity entity, LivingEntityRenderState state, float partialTick) {
		FabricRenderState fs = (FabricRenderState) state;
		Entry e = ENTRIES.get(entity.getId());
		if (e == null || e.cur == null) {
			fs.setData(POSE, null);
			return;
		}
		double[] v = interpolated(entity, e, partialTick);
		RagdollPose pose = new RagdollPose();
		pose.kind = e.kind;
		float scale = state.scale <= 0 ? 1 : state.scale;
		if (e.kind == TitanNet.RAGDOLL_RIGID) {
			pose.rootOffset.set((float) ((v[0] - state.x) / scale), (float) ((v[1] - state.y) / scale), (float) ((v[2] - state.z) / scale));
			pose.rootRot.set((float) v[3], (float) v[4], (float) v[5], (float) v[6]);
			pose.pivotRest.set(0, e.param / scale, 0);
			pose.bodyYaw = state.bodyRot;
		} else if (e.rig == Rig.HUMANOID) {
			solveHumanoid(v, e.param, state, scale, pose);
		} else {
			solveRig(v, e.rig, e.param, state, scale, pose);
		}
		fs.setData(POSE, pose);
	}

	private static Vector3d p(double[] v, int i) {
		return new Vector3d(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
	}

	private static void solveHumanoid(double[] v, float skeletonScale, LivingEntityRenderState state, float scale, RagdollPose pose) {
		// Indices mirror Ragdoll.
		Vector3d head = p(v, 0), ls = p(v, 1), rs = p(v, 2), lh = p(v, 3), rh = p(v, 4);
		Vector3d lHand = p(v, 5), rHand = p(v, 6), lFoot = p(v, 7), rFoot = p(v, 8);
		Vector3d hipMid = new Vector3d(lh).add(rh).mul(0.5);
		Vector3d shMid = new Vector3d(ls).add(rs).mul(0.5);
		Vector3d up = new Vector3d(shMid).sub(hipMid).normalize();
		Vector3d left = new Vector3d(ls).sub(rs);
		left.sub(new Vector3d(up).mul(left.dot(up))).normalize();
		Vector3d fwd = new Vector3d(left).cross(up);
		Matrix3f basis = new Matrix3f(
			(float) left.x, (float) left.y, (float) left.z,
			(float) up.x, (float) up.y, (float) up.z,
			(float) fwd.x, (float) fwd.y, (float) fwd.z);
		pose.rootRot.setFromNormalized(basis);
		pose.rootOffset.set((float) ((hipMid.x - state.x) / scale), (float) ((hipMid.y - state.y) / scale), (float) ((hipMid.z - state.z) / scale));
		pose.pivotRest.set(0, 0.75F * skeletonScale / scale, 0);
		Matrix3f toBody = new Matrix3f(basis).transpose();
		limb(toBody, ls, lHand, false, pose.leftArm);
		limb(toBody, rs, rHand, false, pose.rightArm);
		limb(toBody, lh, lFoot, false, pose.leftLeg);
		limb(toBody, rh, rFoot, false, pose.rightLeg);
		limb(toBody, shMid, head, true, pose.head);
	}

	/**
	 * Any other rig: the root follows the best-fit rotation of the torso particles, and every bone's model part
	 * swings (shortest arc from its rest direction) to point along its particles, pivoting about its anchor.
	 */
	private static void solveRig(double[] v, Rig rig, float skeletonScale, LivingEntityRenderState state, float scale, RagdollPose pose) {
		Quaterniond rot = rig.torsoRotation(v, new Quaterniond());
		double cx = 0, cy = 0, cz = 0;
		for (int i : rig.torso) {
			cx += v[i * 3];
			cy += v[i * 3 + 1];
			cz += v[i * 3 + 2];
		}
		cx /= rig.torso.length;
		cy /= rig.torso.length;
		cz /= rig.torso.length;
		float k = skeletonScale / scale;
		pose.rig = rig;
		pose.rootRot.set((float) rot.x, (float) rot.y, (float) rot.z, (float) rot.w);
		pose.rootOffset.set((float) ((cx - state.x) / scale), (float) ((cy - state.y) / scale), (float) ((cz - state.z) / scale));
		pose.pivotRest.set((float) rig.restCentroid.x * k, (float) rig.restCentroid.y * k, (float) rig.restCentroid.z * k);
		Quaterniond inv = new Quaterniond(rot).conjugate();
		int nb = rig.bones.length;
		pose.boneRot = new Quaternionf[nb];
		pose.boneFrom = new Vector3f[nb];
		for (int b = 0; b < nb; b++) {
			Rig.Bone bone = rig.bones[b];
			int f = bone.from() * 3, t = bone.to() * 3;
			Vector3d rest = new Vector3d(rig.rest[t] - rig.rest[f], rig.rest[t + 1] - rig.rest[f + 1], rig.rest[t + 2] - rig.rest[f + 2]);
			Vector3d cur = inv.transform(new Vector3d(v[t] - v[f], v[t + 1] - v[f + 1], v[t + 2] - v[f + 2]));
			Quaterniond swing = new Quaterniond();
			if (rest.lengthSquared() > 1e-12 && cur.lengthSquared() > 1e-12) {
				swing.rotationTo(rest.normalize(), cur.normalize());
			}
			// Body frame -> model frame is a half turn about X: (x, y, z) -> (x, -y, -z).
			pose.boneRot[b] = new Quaternionf((float) swing.x, (float) -swing.y, (float) -swing.z, (float) swing.w);
			pose.boneFrom[b] = new Vector3f((float) (rig.rest[f] * k * 16), (float) ((1.501 - rig.rest[f + 1] * k) * 16), (float) (-rig.rest[f + 2] * k * 16));
		}
	}

	/**
	 * Converts a bone direction into HumanoidModel part angles (x, z). Limbs hang along model +Y by default,
	 * heads point along model -Y. Model space is the body frame with Y and Z flipped.
	 */
	private static void limb(Matrix3f toBody, Vector3d from, Vector3d to, boolean pointsUp, float[] out) {
		Vector3f d = new Vector3f((float) (to.x - from.x), (float) (to.y - from.y), (float) (to.z - from.z));
		if (d.lengthSquared() < 1e-8F) {
			out[0] = out[1] = 0;
			return;
		}
		d.normalize();
		toBody.transform(d);
		float mx = d.x, my = -d.y, mz = -d.z;
		if (pointsUp) {
			mx = -mx;
			my = -my;
			mz = -mz;
		}
		out[0] = (float) Math.asin(Math.max(-1, Math.min(1, mz)));
		out[1] = (float) Math.atan2(-mx, my);
	}
}
