package dev.titanvr.giant;

import net.minecraft.world.item.ItemStack;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Server-side tracking of one of a giant's hands. */
public final class HandState {
	public final Vector3d pos = new Vector3d();
	public final Vector3d prevPos = new Vector3d();
	public final Quaterniond rot = new Quaterniond();
	public final Quaterniond prevRot = new Quaterniond();
	/** Velocity in blocks/second, averaged over the last two ticks. */
	public final Vector3d vel = new Vector3d();
	private final Vector3d[] history = {new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()};
	private int historyIndex;
	public boolean valid;

	/** Whether the grab button is currently held. */
	public boolean gripping;
	/** What the hand holds: a RigidBody, a Ragdoll, or a carried Entity. */
	public Object held;
	/** Ticks since the hand last smashed into something (for haptics rate limiting). */
	public int hitCooldown;

	/**
	 * Collision spheres: index 0 is the fist, the rest run along a held tool. Each sits colliderDist out along the
	 * hand's pointing direction (local -Z for VR controllers, +Z for the desktop stand-in).
	 */
	public static final int MAX_COLLIDERS = 12;
	public int colliderCount = 1;
	public final double[] colliderDist = new double[MAX_COLLIDERS];
	public final double[] colliderRadius = new double[MAX_COLLIDERS];
	public double forwardZ = -1;
	/** The held tool (empty for a bare fist), how much heavier it hits, and the index of its striking head. */
	public ItemStack tool = ItemStack.EMPTY;
	public double toolMass = 1;
	public int toolHead = 0;

	/** Collider k's center at substep fraction t of this tick (0 = last tick, 1 = now). */
	public Vector3d colliderPos(int k, double t, Vector3d out) {
		out.set(this.prevPos).lerp(this.pos, t);
		double d = this.colliderDist[k];
		if (d != 0) {
			Quaterniond q = new Quaterniond(this.prevRot).slerp(this.rot, t);
			out.add(q.transform(new Vector3d(0, 0, this.forwardZ * d)));
		}
		return out;
	}

	/** Collider k's velocity (blocks/s): the hand's plus the swing of the tool about it. */
	public Vector3d colliderVel(int k, Vector3d out) {
		out.set(this.vel);
		double d = this.colliderDist[k];
		if (d != 0) {
			Vector3d now = this.rot.transform(new Vector3d(0, 0, this.forwardZ * d));
			Vector3d before = this.prevRot.transform(new Vector3d(0, 0, this.forwardZ * d));
			out.add(now.sub(before).mul(20.0));
		}
		return out;
	}

	public void update(Vector3d newPos, Quaterniond newRot, boolean teleported) {
		if (!this.valid || teleported) {
			this.pos.set(newPos);
			this.rot.set(newRot);
			this.prevPos.set(newPos);
			this.prevRot.set(newRot);
			this.vel.zero();
			for (Vector3d v : this.history) {
				v.zero();
			}
			this.valid = true;
			return;
		}
		this.prevPos.set(this.pos);
		this.prevRot.set(this.rot);
		this.pos.set(newPos);
		this.rot.set(newRot);
		Vector3d tickVel = new Vector3d(this.pos).sub(this.prevPos).mul(20.0);
		this.history[this.historyIndex].set(tickVel);
		this.historyIndex = (this.historyIndex + 1) % this.history.length;
		Vector3d last = this.history[(this.historyIndex + this.history.length - 2) % this.history.length];
		this.vel.set(tickVel).add(last).mul(0.5);
		if (this.hitCooldown > 0) {
			this.hitCooldown--;
		}
	}

	/**
	 * Throw velocity: people decelerate right as they let go, so use the fastest of the last few ticks
	 * (keeping the latest direction if the peak was in the same general direction).
	 */
	public Vector3d throwVelocity() {
		Vector3d best = new Vector3d(this.vel);
		for (int i = 0; i < 3; i++) {
			Vector3d h = this.history[(this.historyIndex + this.history.length - 1 - i) % this.history.length];
			if (h.lengthSquared() > best.lengthSquared() && (best.lengthSquared() < 1e-6 || h.dot(best) > 0)) {
				best.set(h);
			}
		}
		return best;
	}

	public double speed() {
		return this.vel.length();
	}
}
