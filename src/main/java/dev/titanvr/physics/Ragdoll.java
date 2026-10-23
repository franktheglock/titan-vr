package dev.titanvr.physics;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Position-based-dynamics ragdoll: a particle skeleton described by a {@link Rig}, matched to the entity's
 * vanilla model so the client can pose model parts directly from the particles.
 */
public final class Ragdoll {
	public final LivingEntity entity;
	public final Rig rig;
	/** World size of one model unit. */
	public final double scale;
	public final double[] x;
	public final double[] v;
	final double[] p;
	final double[] invMass;
	final double[] radius;
	private final int[] cA, cB;
	private final double[] cRest;
	private final boolean[] cMin;

	public int pinned = -1;
	public RigidBody.Hold hold;
	public final Vector3d pinFrom = new Vector3d();
	public final Vector3d pinTo = new Vector3d();

	public int age;
	public int stillTicks;
	public double maxImpact;
	public int damageCooldown;
	public UUID thrower;
	public int thrownAge = -1000;
	boolean ended;
	final boolean hadNoAi;

	public Ragdoll(LivingEntity entity, Rig rig, boolean hadNoAi) {
		this.entity = entity;
		this.rig = rig;
		this.hadNoAi = hadNoAi;
		this.scale = modelScale(entity) * rig.scale;
		int N = rig.n;
		double[] REST = rig.rest;
		this.x = new double[N * 3];
		this.v = new double[N * 3];
		this.p = new double[N * 3];
		this.invMass = new double[N];
		this.radius = new double[N];
		double yaw = Math.toRadians(entity.yBodyRot);
		double cos = Math.cos(-yaw), sin = Math.sin(-yaw);
		Vector3d base = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
		for (int i = 0; i < N; i++) {
			double lx = REST[i * 3] * this.scale, ly = REST[i * 3 + 1] * this.scale, lz = REST[i * 3 + 2] * this.scale;
			// rotate about Y by -yaw (canonical forward +Z -> entity look direction)
			double wx = lx * cos + lz * sin;
			double wz = -lx * sin + lz * cos;
			this.x[i * 3] = base.x + wx;
			this.x[i * 3 + 1] = base.y + ly;
			this.x[i * 3 + 2] = base.z + wz;
			this.radius[i] = rig.radius[i] * this.scale;
			this.invMass[i] = 1.0 / rig.mass[i];
		}
		int n = rig.rigid.length + rig.min.length;
		this.cA = new int[n];
		this.cB = new int[n];
		this.cRest = new double[n];
		this.cMin = new boolean[n];
		int k = 0;
		for (int[] pair : rig.rigid) {
			this.cA[k] = pair[0];
			this.cB[k] = pair[1];
			this.cRest[k] = rig.restDistance(pair[0], pair[1]) * this.scale;
			k++;
		}
		for (double[] pair : rig.min) {
			this.cA[k] = (int) pair[0];
			this.cB[k] = (int) pair[1];
			this.cRest[k] = pair[2] * this.scale;
			this.cMin[k] = true;
			k++;
		}
		Vector3d mv = new Vector3d(entity.getDeltaMovement().x, entity.getDeltaMovement().y, entity.getDeltaMovement().z).mul(20.0);
		this.setVelocity(mv.x, mv.y, mv.z);
	}

	/** Humanoid models are 2 model units tall; players are additionally rendered at 15/16. */
	public static double modelScale(LivingEntity entity) {
		return entity.getScale() * entity.getAgeScale() * (entity instanceof Player ? 0.9375 : 1.0);
	}

	public void setVelocity(double vx, double vy, double vz) {
		for (int i = 0; i < this.rig.n; i++) {
			this.v[i * 3] = vx;
			this.v[i * 3 + 1] = vy;
			this.v[i * 3 + 2] = vz;
		}
	}

	public void addVelocity(double vx, double vy, double vz) {
		for (int i = 0; i < this.rig.n; i++) {
			this.v[i * 3] += vx;
			this.v[i * 3 + 1] += vy;
			this.v[i * 3 + 2] += vz;
		}
	}

	/** Adds a spin so thrown bodies tumble a bit instead of flying rigidly. */
	public void addSpin(double wx, double wy, double wz) {
		Vector3d c = this.center(new Vector3d());
		for (int i = 0; i < this.rig.n; i++) {
			double rx = this.x[i * 3] - c.x, ry = this.x[i * 3 + 1] - c.y, rz = this.x[i * 3 + 2] - c.z;
			this.v[i * 3] += wy * rz - wz * ry;
			this.v[i * 3 + 1] += wz * rx - wx * rz;
			this.v[i * 3 + 2] += wx * ry - wy * rx;
		}
	}

	public Vector3d particle(int i, Vector3d out) {
		return out.set(this.x[i * 3], this.x[i * 3 + 1], this.x[i * 3 + 2]);
	}

	public Vector3d center(Vector3d out) {
		out.set(0, 0, 0);
		double m = 0;
		for (int i = 0; i < this.rig.n; i++) {
			double w = this.rig.mass[i];
			out.add(this.x[i * 3] * w, this.x[i * 3 + 1] * w, this.x[i * 3 + 2] * w);
			m += w;
		}
		return out.div(m);
	}

	public double maxSpeed() {
		double max = 0;
		for (int i = 0; i < this.rig.n; i++) {
			double s = this.v[i * 3] * this.v[i * 3] + this.v[i * 3 + 1] * this.v[i * 3 + 1] + this.v[i * 3 + 2] * this.v[i * 3 + 2];
			max = Math.max(max, s);
		}
		return Math.sqrt(max);
	}

	public int nearestParticle(double px, double py, double pz) {
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < this.rig.n; i++) {
			double dx = this.x[i * 3] - px, dy = this.x[i * 3 + 1] - py, dz = this.x[i * 3 + 2] - pz;
			double d = dx * dx + dy * dy + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/** Current torso orientation (canonical body frame -> world). */
	public Quaterniond torsoRotation(Quaterniond out) {
		return this.rig.torsoRotation(this.x, out);
	}

	/**
	 * Advances one substep. The physics world calls collide() in between predict() and finish().
	 */
	void predict(double h, double gravity) {
		for (int i = 0; i < this.rig.n; i++) {
			int o = i * 3;
			this.v[o + 1] -= gravity * h;
			double drag = 0.998;
			this.v[o] *= drag;
			this.v[o + 1] *= drag;
			this.v[o + 2] *= drag;
			this.p[o] = this.x[o] + this.v[o] * h;
			this.p[o + 1] = this.x[o + 1] + this.v[o + 1] * h;
			this.p[o + 2] = this.x[o + 2] + this.v[o + 2] * h;
		}
	}

	void applyPin(double t) {
		if (this.pinned >= 0) {
			int o = this.pinned * 3;
			this.p[o] = this.pinFrom.x + (this.pinTo.x - this.pinFrom.x) * t;
			this.p[o + 1] = this.pinFrom.y + (this.pinTo.y - this.pinFrom.y) * t;
			this.p[o + 2] = this.pinFrom.z + (this.pinTo.z - this.pinFrom.z) * t;
		}
	}

	void solveConstraints(int iterations) {
		for (int it = 0; it < iterations; it++) {
			for (int k = 0; k < this.cA.length; k++) {
				int a = this.cA[k], b = this.cB[k];
				int oa = a * 3, ob = b * 3;
				double dx = this.p[ob] - this.p[oa], dy = this.p[ob + 1] - this.p[oa + 1], dz = this.p[ob + 2] - this.p[oa + 2];
				double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (d < 1e-9) {
					continue;
				}
				double rest = this.cRest[k];
				if (this.cMin[k] && d >= rest) {
					continue;
				}
				double wa = a == this.pinned ? 0 : this.invMass[a];
				double wb = b == this.pinned ? 0 : this.invMass[b];
				double w = wa + wb;
				if (w <= 0) {
					continue;
				}
				double stiffness = this.cMin[k] ? 0.6 : 1.0;
				double corr = (d - rest) / d / w * stiffness;
				this.p[oa] += dx * corr * wa;
				this.p[oa + 1] += dy * corr * wa;
				this.p[oa + 2] += dz * corr * wa;
				this.p[ob] -= dx * corr * wb;
				this.p[ob + 1] -= dy * corr * wb;
				this.p[ob + 2] -= dz * corr * wb;
			}
		}
	}

	/** Pushes particle i out along n by depth with friction; records impact speed. Returns approach speed. */
	double resolve(int i, double nx, double ny, double nz, double depth, double h, double friction) {
		if (i == this.pinned) {
			return 0;
		}
		int o = i * 3;
		double vx = (this.p[o] - this.x[o]) / h, vy = (this.p[o + 1] - this.x[o + 1]) / h, vz = (this.p[o + 2] - this.x[o + 2]) / h;
		double vn = vx * nx + vy * ny + vz * nz;
		this.p[o] += nx * depth;
		this.p[o + 1] += ny * depth;
		this.p[o + 2] += nz * depth;
		// Coulomb-ish friction: kill part of the tangential motion this substep.
		double tx = vx - vn * nx, ty = vy - vn * ny, tz = vz - vn * nz;
		this.p[o] -= tx * h * friction;
		this.p[o + 1] -= ty * h * friction;
		this.p[o + 2] -= tz * h * friction;
		double approach = -vn;
		if (approach * this.rig.impactWeight[i] > this.maxImpact) {
			this.maxImpact = approach * this.rig.impactWeight[i];
		}
		return approach;
	}

	void finish(double h) {
		for (int i = 0; i < this.rig.n * 3; i++) {
			this.v[i] = (this.p[i] - this.x[i]) / h;
			this.x[i] = this.p[i];
		}
		// Restitution-free PBD tends to keep tiny jitter alive; damp very slow motion so ragdolls settle.
		if (this.maxSpeed() < 0.6) {
			for (int i = 0; i < this.rig.n * 3; i++) {
				this.v[i] *= 0.85;
			}
		}
	}
}
