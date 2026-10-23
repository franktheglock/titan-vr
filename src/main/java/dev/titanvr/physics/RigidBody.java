package dev.titanvr.physics;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** A simulated rigid body. All units are blocks and seconds. */
public final class RigidBody {
	public final int id;
	public BodyShape shape;
	public final Vector3d pos = new Vector3d();
	public final Vector3d vel = new Vector3d();
	public final Vector3d angVel = new Vector3d();
	public final Quaterniond rot = new Quaterniond();

	final Matrix3d rotM = new Matrix3d();
	final Matrix3d invIWorld = new Matrix3d();
	double invMass;
	/** Mass multiplier while held, so a giant's grip overpowers loose debris. */
	double massScale = 1.0;

	public boolean sleeping;
	int stillTicks;
	int sleepTicks;
	int age;
	boolean removed;
	boolean stateDirty = true;

	/** Accumulated contact impulse during the current tick; drives fracturing. */
	double impactImpulse;
	/** Blocks this body has broken in the current tick. */
	int breaksThisTick;
	int soundCooldown;
	int shockCooldown;

	/** Visual entity for debris bodies (null for mob proxies). */
	/** The entity that carries this body to clients (debris only). */
	public net.minecraft.world.entity.Entity entity;
	/** Mob this body stands in for while it is rigidly ragdolled. */
	public LivingEntity proxy;
	/** Whether the proxy mob already had NoAI before being ragdolled. */
	boolean hadNoAi;

	public Hold hold;
	public UUID thrower;
	public int thrownAge = -1000;

	public RigidBody(int id, BodyShape shape) {
		this.id = id;
		this.setShape(shape);
	}

	public void setShape(BodyShape shape) {
		this.density = -1;
		this.shape = shape;
		this.updateMass();
		this.updateDerived();
	}

	void updateMass() {
		this.invMass = 1.0 / (this.shape.mass * this.massScale);
	}

	private double density = -1;

	/** Mean density in tonnes per cubic metre (water = 1). */
	public double density() {
		if (this.density < 0) {
			double vol = 0;
			double[] bx = this.shape.boxes;
			for (int i = 0; i < bx.length; i += 6) {
				vol += 8 * bx[i + 3] * bx[i + 4] * bx[i + 5];
			}
			this.density = vol > 1e-6 ? this.shape.mass / vol : 1.0;
		}
		return this.density;
	}

	public double mass() {
		return this.shape.mass * this.massScale;
	}

	void updateDerived() {
		this.rot.normalize();
		this.rotM.set(this.rot);
		// I_world^-1 = R * I_local^-1 * R^T, scaled down while held (heavier).
		Matrix3d rt = new Matrix3d(this.rotM).transpose();
		this.invIWorld.set(this.rotM).mul(this.shape.invInertiaLocal).mul(rt).scale(1.0 / this.massScale);
	}

	public void localToWorld(double lx, double ly, double lz, Vector3d out) {
		out.set(lx, ly, lz);
		this.rotM.transform(out);
		out.add(this.pos);
	}

	public void worldToLocal(double wx, double wy, double wz, Vector3d out) {
		out.set(wx - this.pos.x, wy - this.pos.y, wz - this.pos.z);
		this.rotM.transformTranspose(out);
	}

	public void localDirToWorld(double lx, double ly, double lz, Vector3d out) {
		out.set(lx, ly, lz);
		this.rotM.transform(out);
	}

	/** Velocity of the body at a world point. */
	public void velocityAt(double px, double py, double pz, Vector3d out) {
		double rx = px - this.pos.x, ry = py - this.pos.y, rz = pz - this.pos.z;
		out.set(
			this.vel.x + this.angVel.y * rz - this.angVel.z * ry,
			this.vel.y + this.angVel.z * rx - this.angVel.x * rz,
			this.vel.z + this.angVel.x * ry - this.angVel.y * rx
		);
	}

	/** Applies an impulse (jx, jy, jz) at a world point. */
	public void applyImpulse(double jx, double jy, double jz, double px, double py, double pz) {
		if (this.sleeping) {
			return;
		}
		this.vel.add(jx * this.invMass, jy * this.invMass, jz * this.invMass);
		double rx = px - this.pos.x, ry = py - this.pos.y, rz = pz - this.pos.z;
		Vector3d t = new Vector3d(ry * jz - rz * jy, rz * jx - rx * jz, rx * jy - ry * jx);
		this.invIWorld.transform(t);
		this.angVel.add(t);
	}

	/** Effective inverse mass of the body along direction n at offset r. */
	double inverseMassAlong(double rx, double ry, double rz, double nx, double ny, double nz) {
		if (this.sleeping) {
			return 0;
		}
		Vector3d c = new Vector3d(ry * nz - rz * ny, rz * nx - rx * nz, rx * ny - ry * nx);
		this.invIWorld.transform(c);
		// (I^-1 (r x n)) x r . n
		double kx = c.y * rz - c.z * ry, ky = c.z * rx - c.x * rz, kz = c.x * ry - c.y * rx;
		return this.invMass + kx * nx + ky * ny + kz * nz;
	}

	public void wake() {
		if (this.sleeping) {
			this.sleeping = false;
			this.stillTicks = 0;
			this.sleepTicks = 0;
			this.stateDirty = true;
		}
	}

	public boolean isDebris() {
		return this.proxy == null;
	}

	/** A giant's grip on this body. Offsets are in the hand's local frame. */
	public static final class Hold {
		public final UUID giant;
		public final int hand;
		public final Vector3d localPos = new Vector3d();
		public final Quaterniond localRot = new Quaterniond();

		public Hold(UUID giant, int hand) {
			this.giant = giant;
			this.hand = hand;
		}
	}
}
