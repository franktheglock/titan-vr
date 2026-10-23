package dev.titanvr.entity;

import dev.titanvr.physics.RigidBody;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Visual/tracking carrier for a voxel rigid body. The physics engine owns the simulation; this entity only
 * exists so vanilla handles tracking, chunk visibility and culling. Never saved: debris is transient.
 */
public class PhysicsBodyEntity extends Entity {
	/** Server: the simulated body. */
	public RigidBody body;
	/** Bounding sphere radius used for the culling box (synced via the shape packet on the client). */
	public double radius = 0.9;

	// --- client-side state (filled by TitanClient) ---
	public Object clientShape;
	public final Quaternionf rot = new Quaternionf();
	public final Quaternionf rotO = new Quaternionf();
	public final Quaternionf rotTarget = new Quaternionf();
	public double tx, ty, tz;
	public boolean hasTarget;

	public PhysicsBodyEntity(EntityType<? extends PhysicsBodyEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		if (!this.level().isClientSide()) {
			if (this.body == null || this.body.entity != this) {
				this.discard();
			}
			return;
		}
		// Client: step towards the latest server snapshot; vanilla's xo/x lerp + rotO/rot slerp smooth frames.
		this.setOldPosAndRot();
		this.rotO.set(this.rot);
		if (this.hasTarget) {
			this.setPos(this.tx, this.ty, this.tz);
			this.rot.set(this.rotTarget);
		}
	}

	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		double r = this.radius;
		return new AABB(position.x - r, position.y - r, position.z - r, position.x + r, position.y + r, position.z + r);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		double d = 96 + this.radius * 4;
		return distance < d * d;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
