package dev.titanvr.physics;

import dev.titanvr.TitanConfig;
import dev.titanvr.compat.VivecraftCompat;
import dev.titanvr.entity.PhysicsBodyEntity;
import dev.titanvr.entity.TitanEntities;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.giant.HandState;
import dev.titanvr.net.TitanNet;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * The physics simulation for one dimension: voxel rigid bodies (sequential-impulse solver against the voxel
 * world, each other and giants' hands), PBD humanoid ragdolls, rigid mob ragdolls, destruction and syncing.
 */
public final class PhysicsWorld {
	private static final Map<ServerLevel, PhysicsWorld> WORLDS = new IdentityHashMap<>();
	/** Entity ids currently ragdolled server-side (read from the network thread, hence concurrent). */
	public static final Set<Integer> RAGDOLLED = ConcurrentHashMap.newKeySet();

	public final ServerLevel level;
	public final WorldCollider collider;
	public final Destruction destruction;
	public final Grabbing grabbing;
	final List<RigidBody> bodies = new ArrayList<>();
	final List<RigidBody> pendingAdd = new ArrayList<>();
	final Int2ObjectOpenHashMap<Ragdoll> ragdolls = new Int2ObjectOpenHashMap<>();
	final Int2ObjectOpenHashMap<RigidBody> rigidRagdolls = new Int2ObjectOpenHashMap<>();
	private final List<Contact> contacts = new ArrayList<>();
	private int contactCount;
	int nextId = 1;
	int tickCount;
	int voxelCount;
	final Random random = new Random();
	int soundBudget;
	private final List<RagdollEnd> endedRagdolls = new ArrayList<>();

	private record RagdollEnd(int entityId, Entity entity) {
	}

	private PhysicsWorld(ServerLevel level) {
		this.level = level;
		this.collider = new WorldCollider(level);
		this.destruction = new Destruction(this);
		this.grabbing = new Grabbing(this);
	}

	public static PhysicsWorld get(Level level) {
		return WORLDS.computeIfAbsent((ServerLevel) level, PhysicsWorld::new);
	}

	public static void tickLevel(ServerLevel level) {
		PhysicsWorld world = WORLDS.get(level);
		boolean hasGiants = false;
		for (Giant g : GiantManager.all()) {
			if (level.getEntity(g.uuid) != null) {
				hasGiants = true;
				break;
			}
		}
		if (world == null && !hasGiants) {
			return;
		}
		get(level).tick();
	}

	public static void clearAll() {
		WORLDS.clear();
		RAGDOLLED.clear();
	}

	public static void unload(ServerLevel level) {
		PhysicsWorld world = WORLDS.remove(level);
		if (world != null) {
			for (int id : world.ragdolls.keySet()) {
				RAGDOLLED.remove(id);
			}
			for (int id : world.rigidRagdolls.keySet()) {
				RAGDOLLED.remove(id);
			}
		}
	}

	public int bodyCount() {
		return this.bodies.size();
	}

	public int voxelCount() {
		return this.voxelCount;
	}

	public int ragdollCount() {
		return this.ragdolls.size() + this.rigidRagdolls.size();
	}

	// ======================================================================== body management

	/**
	 * Creates a voxel body from world blocks (already removed from the world by the caller). Returns null if the
	 * physics budget is exhausted and force is false.
	 */
	public RigidBody spawnVoxelBody(List<BlockPos> positions, List<BlockState> states, Vector3d vel, boolean force) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		int n = positions.size();
		if (n == 0) {
			return null;
		}
		if (this.voxelCount + n > cfg.maxVoxels || this.bodies.size() + this.pendingAdd.size() >= cfg.maxBodies) {
			this.evict(n);
			if (!force && (this.voxelCount + n > cfg.maxVoxels || this.bodies.size() + this.pendingAdd.size() >= cfg.maxBodies)) {
				return null;
			}
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		for (BlockPos p : positions) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
		}
		List<int[]> cells = new ArrayList<>(n);
		for (BlockPos p : positions) {
			cells.add(new int[] {p.getX() - minX, p.getY() - minY, p.getZ() - minZ});
		}
		double[] com = new double[3];
		BodyShape shape = BodyShape.voxels(cells, states, com);
		RigidBody body = new RigidBody(this.nextId++, shape);
		body.pos.set(minX + com[0], minY + com[1], minZ + com[2]);
		if (vel != null) {
			body.vel.set(vel);
		}
		body.updateDerived();
		this.addBody(body);
		return body;
	}

	void addBody(RigidBody body) {
		if (body.isDebris()) {
			PhysicsBodyEntity entity = new PhysicsBodyEntity(TitanEntities.PHYSICS_BODY, this.level);
			entity.body = body;
			entity.radius = body.shape.radius;
			entity.setPos(body.pos.x, body.pos.y, body.pos.z);
			body.entity = entity;
			this.level.addFreshEntity(entity);
			this.voxelCount += body.shape.count();
		}
		// Always queued: bodies get created from inside loops over the body list (impacts, launches...).
		this.pendingAdd.add(body);
	}

	private void flushPending() {
		if (!this.pendingAdd.isEmpty()) {
			this.bodies.addAll(this.pendingAdd);
			this.pendingAdd.clear();
		}
	}

	void removeBody(RigidBody body) {
		if (body.removed) {
			return;
		}
		body.removed = true;
		if (body.entity != null) {
			body.entity.discard();
			this.voxelCount -= body.shape.count();
		}
		this.grabbing.forgetHeld(body);
	}

	/** Frees room for new debris by settling the oldest resting debris. */
	private void evict(int needed) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		List<RigidBody> candidates = new ArrayList<>();
		for (RigidBody b : this.bodies) {
			if (!b.removed && b.isDebris() && b.hold == null) {
				candidates.add(b);
			}
		}
		// Sleeping first, then oldest.
		candidates.sort((a, b) -> a.sleeping != b.sleeping ? (a.sleeping ? -1 : 1) : Integer.compare(b.age, a.age));
		for (RigidBody b : candidates) {
			if (this.voxelCount + needed <= cfg.maxVoxels * 0.9 && this.bodies.size() < cfg.maxBodies * 0.9) {
				break;
			}
			this.settle(b);
		}
	}

	/** Turns resting debris back into blocks (or just removes it, depending on config). */
	void settle(RigidBody b) {
		if (b.removed) {
			return;
		}
		boolean place = "place".equalsIgnoreCase(TitanConfig.INSTANCE.rubbleMode);
		if (place && b.shape.voxel) {
			Vector3d w = new Vector3d();
			BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
			for (int i = 0; i < b.shape.count(); i++) {
				BlockState state = b.shape.states[i];
				if (state == null) {
					continue;
				}
				b.localToWorld(b.shape.boxes[i * 6], b.shape.boxes[i * 6 + 1], b.shape.boxes[i * 6 + 2], w);
				mp.set(Math.floor(w.x), Math.floor(w.y), Math.floor(w.z));
				if (this.level.hasChunkAt(mp) && this.level.getBlockState(mp).canBeReplaced() && this.level.getFluidState(mp).isEmpty()) {
					this.level.setBlock(mp, state, 3);
					this.collider.invalidate(mp);
				}
			}
		}
		this.removeBody(b);
	}

	public int clearDebris() {
		int n = 0;
		for (RigidBody b : this.bodies) {
			if (b.isDebris() && !b.removed) {
				this.removeBody(b);
				n++;
			}
		}
		return n;
	}

	public RigidBody bodyByEntity(Entity entity) {
		return entity instanceof PhysicsBodyEntity pbe ? pbe.body : null;
	}

	List<RigidBody> bodies() {
		return this.bodies;
	}

	// ======================================================================== ragdolls

	/** For logging: the rig name, "rigid" or "none". */
	public String ragdollKind(Entity entity) {
		Ragdoll r = this.ragdolls.get(entity.getId());
		return r != null ? r.rig.name : this.rigidRagdolls.containsKey(entity.getId()) ? "rigid" : "none";
	}

	public Vector3d ragdollCenter(Entity entity) {
		Ragdoll r = this.ragdolls.get(entity.getId());
		return r == null ? null : r.center(new Vector3d());
	}

	public boolean isRagdolled(Entity entity) {
		return this.ragdolls.containsKey(entity.getId()) || this.rigidRagdolls.containsKey(entity.getId());
	}

	public static boolean canRagdoll(LivingEntity e) {
		if (!e.isAlive() || e.isSpectator() || e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
			|| e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
			return false;
		}
		return !(e instanceof Player p && GiantManager.isGiant(p));
	}

	/** Puts an entity into ragdoll mode (or returns the existing ragdoll). Returns a Ragdoll or RigidBody. */
	public Object ragdoll(LivingEntity e) {
		Ragdoll hr = this.ragdolls.get(e.getId());
		if (hr != null) {
			return hr;
		}
		RigidBody rr = this.rigidRagdolls.get(e.getId());
		if (rr != null) {
			rr.wake();
			return rr;
		}
		if (!canRagdoll(e)) {
			return null;
		}
		if (e.isPassenger()) {
			e.stopRiding();
		}
		e.ejectPassengers();
		boolean hadNoAi = e instanceof Mob mob && mob.isNoAi();
		if (e instanceof Mob mob) {
			mob.setNoAi(true);
		}
		RAGDOLLED.add(e.getId());
		Rig rig = Rig.forEntity(e);
		if (rig != null) {
			hr = new Ragdoll(e, rig, hadNoAi);
			this.ragdolls.put(e.getId(), hr);
			return hr;
		}
		double hw = Math.max(0.15, e.getBbWidth() * 0.5);
		double hh = Math.max(0.15, e.getBbHeight() * 0.5);
		double mass = Math.max(0.6, hw * 2 * hw * 2 * hh * 2 * 1.2);
		RigidBody body = new RigidBody(this.nextId++, BodyShape.box(hw, hh, hw, mass));
		body.proxy = e;
		body.pos.set(e.getX(), e.getY() + hh, e.getZ());
		Vec3 dm = e.getDeltaMovement();
		body.vel.set(dm.x * 20, dm.y * 20, dm.z * 20);
		body.updateDerived();
		body.hadNoAi = hadNoAi;
		this.rigidRagdolls.put(e.getId(), body);
		this.addBody(body);
		return body;
	}

	/** Launches an entity as a ragdoll with a velocity (blocks/s). */
	public void launch(LivingEntity e, Vector3d vel, UUID thrower) {
		Object r = this.ragdoll(e);
		if (r instanceof Ragdoll hr) {
			hr.addVelocity(vel.x, vel.y, vel.z);
			hr.addSpin((this.random.nextDouble() - 0.5) * 6, (this.random.nextDouble() - 0.5) * 6, (this.random.nextDouble() - 0.5) * 6);
			if (thrower != null) {
				hr.thrower = thrower;
				hr.thrownAge = hr.age;
			}
		} else if (r instanceof RigidBody rb) {
			rb.wake();
			rb.vel.add(vel);
			rb.angVel.add((this.random.nextDouble() - 0.5) * 8, (this.random.nextDouble() - 0.5) * 8, (this.random.nextDouble() - 0.5) * 8);
			if (thrower != null) {
				rb.thrower = thrower;
				rb.thrownAge = rb.age;
			}
		}
	}

	// ------------------------------------------------------------------ collision physics for creatures

	/** Body mass of a creature in tonnes (bounding box volume at roughly the density of water). */
	public static double entityMass(LivingEntity e) {
		AABB b = e.getBoundingBox();
		return Math.max(0.05, b.getXsize() * b.getYsize() * b.getZsize());
	}

	/**
	 * Velocity change of a body of mass m hit by a mass M closing at relative velocity rel (impulse along rel,
	 * restitution 0.3): dv = (1 + e) * M / (M + m) * rel.
	 */
	public static Vector3d collide(double bigM, double m, Vector3d rel) {
		return new Vector3d(rel).mul(1.3 * bigM / (bigM + m));
	}

	/**
	 * Damage from a sudden velocity change dv (blocks/s), consistent with vanilla fall damage: stopping from dv hurts
	 * like falling dv^2 / 2g blocks (g = 32, so anything under ~14 b/s is harmless). Heavy projectiles add a term
	 * for the kinetic energy exchanged in the collision.
	 */
	public static double impactDamage(double dv, double energy) {
		double fallBlocks = dv * dv / (2 * 32.0);
		double damage = Math.max(0, fallBlocks - 3.0) + Math.max(0, energy - 60.0) / 40.0;
		return damage * TitanConfig.INSTANCE.impactDamageMultiplier;
	}

	/** Applies a velocity change: small ones just shove the creature, big ones send it flying as a ragdoll. */
	public void shoveOrLaunch(LivingEntity e, Vector3d dv, UUID cause) {
		if (dv.length() >= TitanConfig.INSTANCE.ragdollLaunchSpeed) {
			this.launch(e, new Vector3d(dv).add(0, 1.0, 0), cause);
		} else {
			e.push(dv.x / 20.0, dv.y / 20.0 + 0.05, dv.z / 20.0);
			e.hurtMarked = true;
		}
	}

	private void endRagdoll(int id, Entity e, boolean hadNoAi) {
		RAGDOLLED.remove(id);
		if (e instanceof Mob mob && !hadNoAi) {
			mob.setNoAi(false);
		}
		this.endedRagdolls.add(new RagdollEnd(id, e));
		if (e == null || e.isRemoved()) {
			return;
		}
		// Stand back up at a free spot near where the ragdoll came to rest.
		Vec3 base = e.position();
		for (int dy = 0; dy < 4; dy++) {
			Vec3 at = base.add(0, dy * 0.5, 0);
			if (this.level.noCollision(e, e.getDimensions(e.getPose()).makeBoundingBox(at))) {
				base = at;
				break;
			}
		}
		e.setDeltaMovement(Vec3.ZERO);
		e.resetFallDistance();
		if (e instanceof ServerPlayer sp) {
			sp.connection.teleport(base.x, base.y, base.z, sp.getYRot(), sp.getXRot());
		} else {
			e.setPos(base.x, base.y, base.z);
		}
	}

	public void stopRagdoll(Entity e) {
		Ragdoll hr = this.ragdolls.remove(e.getId());
		if (hr != null) {
			this.grabbing.forgetHeld(hr);
			this.endRagdoll(e.getId(), e, hr.hadNoAi);
		}
		RigidBody rb = this.rigidRagdolls.remove(e.getId());
		if (rb != null) {
			this.grabbing.forgetHeld(rb);
			this.removeBody(rb);
			this.endRagdoll(e.getId(), e, rb.hadNoAi);
		}
	}

	public void releaseAll(ServerPlayer player, Giant g) {
		for (int hand = 0; hand < 2; hand++) {
			this.grabbing.release(player, g, hand);
		}
	}

	// ======================================================================== main loop

	/** Smoothed physics cost per tick in milliseconds. */
	public double avgTickMs;

	private void tick() {
		long start = System.nanoTime();
		this.tickInner();
		this.avgTickMs = this.avgTickMs * 0.9 + (System.nanoTime() - start) / 1.0e6 * 0.1;
	}

	private void tickInner() {
		this.tickCount++;
		this.collider.clear();
		this.soundBudget = 12;
		this.destruction.beginTick();

		// Drop bodies whose carrier entity went away (chunk unload, /kill...).
		for (RigidBody b : this.bodies) {
			if (!b.removed && b.entity != null && b.entity.isRemoved()) {
				b.removed = true;
				this.voxelCount -= b.shape.count();
				this.grabbing.forgetHeld(b);
			}
		}
		this.cleanRagdolls();
		this.flushPending();

		List<Giant> giants = new ArrayList<>();
		Map<Giant, ServerPlayer> giantPlayers = new HashMap<>();
		for (Giant g : GiantManager.all()) {
			if (this.level.getEntity(g.uuid) instanceof ServerPlayer sp && sp.isAlive()) {
				giants.add(g);
				giantPlayers.put(g, sp);
			}
		}
		this.grabbing.tickHolds(giants, giantPlayers);

		// Adaptive substeps keep fast debris from tunnelling through 1-block walls.
		double maxSpeed = 0;
		for (RigidBody b : this.bodies) {
			if (!b.removed && !b.sleeping) {
				maxSpeed = Math.max(maxSpeed, b.vel.length() + b.angVel.length() * b.shape.radius);
			}
		}
		for (Ragdoll r : this.ragdolls.values()) {
			maxSpeed = Math.max(maxSpeed, r.maxSpeed());
		}
		int substeps = (int) Math.max(3, Math.min(12, Math.ceil(maxSpeed * 0.05 / 0.3)));

		for (int s = 0; s < substeps; s++) {
			this.substep(0.05 / substeps, (s + 1.0) / substeps, giants);
			this.destruction.processShockwaves();
		}
		this.flushPending();

		this.postBodies();
		this.flushPending();
		this.postRagdolls();
		this.destruction.handSmash(giants, giantPlayers);
		this.destruction.processCollapse();
		this.flushPending();
		this.bodies.removeIf(b -> b.removed);
		this.sync();
	}

	private void cleanRagdolls() {
		IntArrayList gone = new IntArrayList();
		for (var entry : this.ragdolls.int2ObjectEntrySet()) {
			LivingEntity e = entry.getValue().entity;
			if (e.isRemoved() || e.level() != this.level) {
				gone.add(entry.getIntKey());
			}
		}
		for (int id : gone) {
			Ragdoll r = this.ragdolls.remove(id);
			this.grabbing.forgetHeld(r);
			RAGDOLLED.remove(id);
			this.endedRagdolls.add(new RagdollEnd(id, null));
		}
		gone.clear();
		for (var entry : this.rigidRagdolls.int2ObjectEntrySet()) {
			LivingEntity e = entry.getValue().proxy;
			if (e.isRemoved() || e.level() != this.level) {
				gone.add(entry.getIntKey());
			}
		}
		for (int id : gone) {
			RigidBody b = this.rigidRagdolls.remove(id);
			this.removeBody(b);
			RAGDOLLED.remove(id);
			this.endedRagdolls.add(new RagdollEnd(id, null));
		}
	}

	private Contact nextContact() {
		if (this.contactCount == this.contacts.size()) {
			this.contacts.add(new Contact());
		}
		return this.contacts.get(this.contactCount++);
	}

	private void substep(double h, double t, List<Giant> giants) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		double gravity = cfg.gravity;
		this.contactCount = 0;

		for (RigidBody b : this.bodies) {
			if (b.removed || b.sleeping) {
				continue;
			}
			if (b.hold != null) {
				this.grabbing.driveHold(b, t, h);
			} else {
				b.vel.y -= gravity * h;
				this.fluidForces(b, h, gravity);
			}
			b.vel.mul(0.9995);
			b.angVel.mul(0.997);
		}

		// --- contact generation
		double[] out = new double[8];
		Vector3d w = new Vector3d();
		Vector3d vp = new Vector3d();
		DoubleArrayList hits = new DoubleArrayList();
		for (RigidBody b : this.bodies) {
			if (b.removed || b.sleeping) {
				continue;
			}
			this.worldContacts(b, out, w, vp, hits);
		}
		this.bodyPairs(out, w);
		this.handContacts(giants, t, out, w);

		// --- solve
		this.prepareContacts(h);
		for (int it = 0; it < 10; it++) {
			this.solveContacts();
		}

		// --- integrate
		for (RigidBody b : this.bodies) {
			if (b.removed || b.sleeping) {
				continue;
			}
			b.pos.fma(h, b.vel);
			double wl = b.angVel.length();
			if (wl > 1e-9) {
				double maxW = 80;
				if (wl > maxW) {
					b.angVel.mul(maxW / wl);
					wl = maxW;
				}
				Quaterniond dq = new Quaterniond().fromAxisAngleRad(b.angVel.x / wl, b.angVel.y / wl, b.angVel.z / wl, wl * h);
				b.rot.premul(dq);
			}
			b.updateDerived();
			if (b.pos.y < this.level.getMinY() - 64) {
				this.removeBody(b);
			}
		}

		// --- ragdolls
		for (Ragdoll r : this.ragdolls.values()) {
			this.stepRagdoll(r, h, t, gravity, giants, out);
		}
	}

	// ------------------------------------------------------------------ contact generation

	private void worldContacts(RigidBody b, double[] out, Vector3d w, Vector3d vp, DoubleArrayList hits) {
		double[] s = b.shape.samples;
		hits.clear();
		boolean unloaded = false;
		for (int i = 0; i < s.length; i += 3) {
			b.localToWorld(s[i], s[i + 1], s[i + 2], w);
			if (this.collider.pointContact(w.x, w.y, w.z, out)) {
				hits.add(w.x);
				hits.add(w.y);
				hits.add(w.z);
				hits.add(out[0]);
				hits.add(out[1]);
				hits.add(out[2]);
				hits.add(out[3]);
				hits.add(out[4]);
				hits.add(out[5]);
				hits.add(out[6]);
			} else if (out[7] == 1) {
				unloaded = true;
			}
		}
		if (unloaded) {
			// Reached the edge of the loaded world: freeze rather than fall into the void.
			b.vel.zero();
			b.angVel.zero();
			b.sleeping = true;
			b.stateDirty = true;
			return;
		}
		int k = hits.size() / 10;
		if (k == 0) {
			return;
		}
		double effMass = Math.min(b.mass() / k, 2000.0) * (b.hold != null ? 2.5 : 1.0);
		double worst = 0;
		double sx = 0, sy = 0, sz = 0;
		for (int i = 0; i < k; i++) {
			int o = i * 10;
			double px = hits.getDouble(o), py = hits.getDouble(o + 1), pz = hits.getDouble(o + 2);
			double nx = hits.getDouble(o + 3), ny = hits.getDouble(o + 4), nz = hits.getDouble(o + 5);
			double depth = hits.getDouble(o + 6);
			int cx = (int) hits.getDouble(o + 7), cy = (int) hits.getDouble(o + 8), cz = (int) hits.getDouble(o + 9);
			b.velocityAt(px, py, pz, vp);
			double approach = -(vp.x * nx + vp.y * ny + vp.z * nz);
			if (approach > worst) {
				worst = approach;
				sx = px;
				sy = py;
				sz = pz;
			}
			if (approach > 4.0 && this.destruction.tryImpactBreak(b, cx, cy, cz, approach, effMass, vp, nx, ny, nz, px, py, pz)) {
				continue;
			}
			if (approach > 5.0 && b.soundCooldown <= 0 && this.soundBudget > 0) {
				this.impactSound(b, px, py, pz, approach);
			}
			Contact c = this.nextContact();
			c.set(b, null, px, py, pz, nx, ny, nz, depth);
			c.friction = 0.65;
		}
		// A heavy, fast impact sends out a shockwave (queued; resolved after the substep).
		double impactEnergy = 0.5 * b.mass() * worst * worst;
		if (b.shockCooldown <= 0 && b.hold == null && impactEnergy > TitanConfig.INSTANCE.shockwaveMinEnergy) {
			b.shockCooldown = 10;
			this.destruction.queueShockwave(sx, sy, sz, impactEnergy, b);
		}
	}

	private void impactSound(RigidBody b, double x, double y, double z, double speed) {
		BlockState state = b.shape.states.length > 0 ? b.shape.states[0] : null;
		if (state == null) {
			return;
		}
		this.soundBudget--;
		b.soundCooldown = 6;
		float volume = (float) Math.min(1.5, 0.25 + speed * 0.04 * Math.sqrt(b.mass()));
		this.level.playSound(null, x, y, z, state.getSoundType().getHitSound(), SoundSource.BLOCKS, volume, 0.7F + this.random.nextFloat() * 0.3F);
	}

	private final List<RigidBody> sorted = new ArrayList<>();

	private void bodyPairs(double[] out, Vector3d w) {
		this.sorted.clear();
		for (RigidBody b : this.bodies) {
			if (!b.removed) {
				this.sorted.add(b);
			}
		}
		this.sorted.sort((a, b) -> Double.compare(a.pos.x - a.shape.radius, b.pos.x - b.shape.radius));
		int n = this.sorted.size();
		for (int i = 0; i < n; i++) {
			RigidBody a = this.sorted.get(i);
			double maxX = a.pos.x + a.shape.radius;
			for (int j = i + 1; j < n; j++) {
				RigidBody b = this.sorted.get(j);
				if (b.pos.x - b.shape.radius > maxX) {
					break;
				}
				if (a.sleeping && b.sleeping) {
					continue;
				}
				double rr = a.shape.radius + b.shape.radius;
				if (a.pos.distanceSquared(b.pos) > rr * rr) {
					continue;
				}
				// A ragdolled mob held by the same hand as a body would be odd; otherwise everything collides.
				this.pointsInto(a, b, out, w);
				this.pointsInto(b, a, out, w);
			}
		}
	}

	/** Sample points of A that are inside B. */
	private void pointsInto(RigidBody a, RigidBody b, double[] out, Vector3d w) {
		double[] s = a.shape.samples;
		Vector3d l = new Vector3d();
		Vector3d n = new Vector3d();
		for (int i = 0; i < s.length; i += 3) {
			a.localToWorld(s[i], s[i + 1], s[i + 2], w);
			b.worldToLocal(w.x, w.y, w.z, l);
			if (b.shape.penetration(l.x, l.y, l.z, out)) {
				b.localDirToWorld(out[0], out[1], out[2], n);
				if (a.sleeping || b.sleeping) {
					// Wake resting bodies only when something hits them with some speed.
					Vector3d va = new Vector3d(), vb = new Vector3d();
					a.velocityAt(w.x, w.y, w.z, va);
					b.velocityAt(w.x, w.y, w.z, vb);
					if (va.sub(vb).lengthSquared() > 2.0) {
						a.wake();
						b.wake();
					}
				}
				Contact c = this.nextContact();
				c.set(a, b, w.x, w.y, w.z, n.x, n.y, n.z, out[3]);
				c.friction = 0.5;
			}
		}
	}

	private void handContacts(List<Giant> giants, double t, double[] out, Vector3d w) {
		Vector3d l = new Vector3d();
		Vector3d n = new Vector3d();
		Vector3d cpos = new Vector3d();
		Vector3d cvel = new Vector3d();
		for (Giant g : giants) {
			for (HandState hand : g.hands) {
				if (!hand.valid || hand.held != null) {
					continue;
				}
				for (int k = 0; k < hand.colliderCount; k++) {
					double r = hand.colliderRadius[k];
					hand.colliderPos(k, t, cpos);
					hand.colliderVel(k, cvel);
					double cx = cpos.x, cy = cpos.y, cz = cpos.z;
					for (RigidBody b : this.bodies) {
						if (b.removed) {
							continue;
						}
						double rr = b.shape.radius + r;
						double dx = b.pos.x - cx, dy = b.pos.y - cy, dz = b.pos.z - cz;
						if (dx * dx + dy * dy + dz * dz > rr * rr) {
							continue;
						}
						b.worldToLocal(cx, cy, cz, l);
						if (!b.shape.spherePenetration(l.x, l.y, l.z, r, out)) {
							continue;
						}
						b.localDirToWorld(out[0], out[1], out[2], n);
						double depth = out[3];
						if (cvel.length() > 1.5) {
							b.wake();
							if (b.proxy == null) {
								b.thrower = g.uuid;
								b.thrownAge = b.age;
							}
						}
						if (b.sleeping) {
							continue;
						}
						Contact c = this.nextContact();
						double px = cx - n.x * (r - depth * 0.5), py = cy - n.y * (r - depth * 0.5), pz = cz - n.z * (r - depth * 0.5);
						c.set(b, null, px, py, pz, -n.x, -n.y, -n.z, Math.min(depth, 0.5));
						c.kinematic = true;
						c.kvx = cvel.x;
						c.kvy = cvel.y;
						c.kvz = cvel.z;
						c.friction = 0.8;
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ solver

	private void prepareContacts(double h) {
		Vector3d va = new Vector3d(), vb = new Vector3d();
		for (int i = 0; i < this.contactCount; i++) {
			Contact c = this.contacts.get(i);
			RigidBody a = c.a, b = c.b;
			c.rax = c.px - a.pos.x;
			c.ray = c.py - a.pos.y;
			c.raz = c.pz - a.pos.z;
			double k = a.inverseMassAlong(c.rax, c.ray, c.raz, c.nx, c.ny, c.nz);
			if (b != null) {
				c.rbx = c.px - b.pos.x;
				c.rby = c.py - b.pos.y;
				c.rbz = c.pz - b.pos.z;
				k += b.inverseMassAlong(c.rbx, c.rby, c.rbz, c.nx, c.ny, c.nz);
			}
			c.massN = k > 0 ? 1.0 / k : 0;
			this.relativeVelocity(c, va, vb);
			double vn = va.x * c.nx + va.y * c.ny + va.z * c.nz;
			double restitution = vn < -3.0 ? -0.18 * vn : 0;
			double pen = Math.min(8.0, 0.3 / h * Math.max(0, c.depth - 0.01));
			c.bias = Math.max(restitution, pen);
		}
	}

	/** Writes relative velocity (A minus B/world/hand) at the contact point into va. */
	private void relativeVelocity(Contact c, Vector3d va, Vector3d vb) {
		c.a.velocityAt(c.px, c.py, c.pz, va);
		if (c.a.sleeping) {
			va.zero();
		}
		if (c.b != null && !c.b.sleeping) {
			c.b.velocityAt(c.px, c.py, c.pz, vb);
			va.sub(vb);
		} else if (c.kinematic) {
			va.sub(c.kvx, c.kvy, c.kvz);
		}
	}

	private void solveContacts() {
		Vector3d va = new Vector3d(), vb = new Vector3d();
		for (int i = 0; i < this.contactCount; i++) {
			Contact c = this.contacts.get(i);
			if (c.massN == 0) {
				continue;
			}
			this.relativeVelocity(c, va, vb);
			double vn = va.x * c.nx + va.y * c.ny + va.z * c.nz;
			double lambda = c.massN * (c.bias - vn);
			double acc = Math.max(c.accN + lambda, 0);
			lambda = acc - c.accN;
			c.accN = acc;
			if (lambda != 0) {
				this.applyPair(c, c.nx * lambda, c.ny * lambda, c.nz * lambda);
				c.a.impactImpulse += lambda;
				if (c.b != null) {
					c.b.impactImpulse += lambda;
				}
			}
			// Friction along the current sliding direction.
			this.relativeVelocity(c, va, vb);
			vn = va.x * c.nx + va.y * c.ny + va.z * c.nz;
			double tx = va.x - vn * c.nx, ty = va.y - vn * c.ny, tz = va.z - vn * c.nz;
			double vt = Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (vt < 1e-7) {
				continue;
			}
			tx /= vt;
			ty /= vt;
			tz /= vt;
			double kt = c.a.inverseMassAlong(c.rax, c.ray, c.raz, tx, ty, tz);
			if (c.b != null) {
				kt += c.b.inverseMassAlong(c.rbx, c.rby, c.rbz, tx, ty, tz);
			}
			if (kt <= 0) {
				continue;
			}
			double jt = -vt / kt;
			double maxF = c.friction * c.accN;
			double newAcc = Math.max(-maxF, Math.min(maxF, c.accT + jt));
			jt = newAcc - c.accT;
			c.accT = newAcc;
			this.applyPair(c, tx * jt, ty * jt, tz * jt);
		}
	}

	private void applyPair(Contact c, double jx, double jy, double jz) {
		c.a.applyImpulse(jx, jy, jz, c.px, c.py, c.pz);
		if (c.b != null) {
			c.b.applyImpulse(-jx, -jy, -jz, c.px, c.py, c.pz);
		}
	}

	// ------------------------------------------------------------------ ragdoll step

	/**
	 * Water and lava: buoyancy from the body's density against the submerged fraction, and heavy drag, so things
	 * splash down and slow instead of slamming into the bottom at full speed.
	 */
	private void fluidForces(RigidBody b, double h, double gravity) {
		double r = Math.min(b.shape.radius, 1.5) * 0.5;
		int wet = 0, lava = 0;
		for (int k = -1; k <= 1; k++) {
			int f = this.collider.fluid(b.pos.x, b.pos.y + r * k, b.pos.z);
			if (f != WorldCollider.NO_FLUID) {
				wet++;
				if (f == WorldCollider.LAVA) {
					lava++;
				}
			}
		}
		if (wet == 0) {
			return;
		}
		double frac = wet / 3.0;
		double fluidDensity = lava > 0 ? 3.1 : 1.0;
		b.vel.y += gravity * frac * Math.min(3.0, fluidDensity / b.density());
		double speed = b.vel.length();
		double k = (lava > 0 ? 5.0 : 1.8) + (lava > 0 ? 0.25 : 0.08) * speed;
		b.vel.mul(Math.exp(-k * frac * h));
		b.angVel.mul(Math.exp(-2.5 * frac * h));
	}

	private void ragdollFluid(Ragdoll r, double h, double gravity) {
		for (int i = 0; i < r.rig.n; i++) {
			int o = i * 3;
			int f = this.collider.fluid(r.x[o], r.x[o + 1], r.x[o + 2]);
			if (f == WorldCollider.NO_FLUID) {
				continue;
			}
			boolean lava = f == WorldCollider.LAVA;
			// Bodies are a touch denser than water: they sink, slowly.
			r.v[o + 1] += gravity * (lava ? 1.3 : 0.93) * h;
			double vx = r.v[o], vy = r.v[o + 1], vz = r.v[o + 2];
			double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
			double damp = Math.exp(-((lava ? 5.0 : 2.5) + (lava ? 0.3 : 0.15) * speed) * h);
			r.v[o] *= damp;
			r.v[o + 1] *= damp;
			r.v[o + 2] *= damp;
		}
	}

	private void stepRagdoll(Ragdoll r, double h, double t, double gravity, List<Giant> giants, double[] out) {
		this.ragdollFluid(r, h, gravity);
		r.predict(h, r.pinned >= 0 && r.hold != null ? gravity * 0.5 : gravity);
		r.applyPin(t);
		r.solveConstraints(4);
		// World
		for (int i = 0; i < r.rig.n; i++) {
			int o = i * 3;
			if (this.collider.sphereContact(r.p[o], r.p[o + 1], r.p[o + 2], r.radius[i], out)) {
				int cx = (int) out[4], cy = (int) out[5], cz = (int) out[6];
				double nx = out[0], ny = out[1], nz = out[2], depth = out[3];
				double vx = (r.p[o] - r.x[o]) / h, vy = (r.p[o + 1] - r.x[o + 1]) / h, vz = (r.p[o + 2] - r.x[o + 2]) / h;
				double approach = -(vx * nx + vy * ny + vz * nz);
				if (approach > 11.0 && this.destruction.tryRagdollBreak(r, cx, cy, cz, approach, vx, vy, vz)) {
					continue;
				}
				r.resolve(i, nx, ny, nz, depth, h, 0.55);
			}
		}
		// Rigid bodies (two-way coupling)
		Vector3d c = r.center(new Vector3d());
		Vector3d l = new Vector3d(), n = new Vector3d(), vb = new Vector3d();
		double reach = 2.2 * r.scale + 0.5;
		for (RigidBody b : this.bodies) {
			if (b.removed) {
				continue;
			}
			double rr = b.shape.radius + reach;
			if (b.pos.distanceSquared(c) > rr * rr) {
				continue;
			}
			for (int i = 0; i < r.rig.n; i++) {
				if (i == r.pinned) {
					continue;
				}
				int o = i * 3;
				b.worldToLocal(r.p[o], r.p[o + 1], r.p[o + 2], l);
				if (!b.shape.spherePenetration(l.x, l.y, l.z, r.radius[i], out)) {
					continue;
				}
				b.localDirToWorld(out[0], out[1], out[2], n);
				double depth = out[3];
				b.velocityAt(r.p[o], r.p[o + 1], r.p[o + 2], vb);
				if (b.sleeping) {
					vb.zero();
				}
				double vx = (r.p[o] - r.x[o]) / h - vb.x, vy = (r.p[o + 1] - r.x[o + 1]) / h - vb.y, vz = (r.p[o + 2] - r.x[o + 2]) / h - vb.z;
				double vn = vx * n.x + vy * n.y + vz * n.z;
				double mp = r.rig.mass[i];
				double rx = r.p[o] - b.pos.x, ry = r.p[o + 1] - b.pos.y, rz = r.p[o + 2] - b.pos.z;
				double invB = b.inverseMassAlong(rx, ry, rz, n.x, n.y, n.z);
				double dvParticle = 0;
				if (vn < 0) {
					double j = -vn / (1.0 / mp + invB);
					dvParticle = j / mp;
					if (j > 0.5 && !b.sleeping) {
						b.applyImpulse(-n.x * j, -n.y * j, -n.z * j, r.p[o], r.p[o + 1], r.p[o + 2]);
					} else if (j > 3.0) {
						b.wake();
					}
					if (-vn * r.rig.impactWeight[i] > r.maxImpact) {
						r.maxImpact = -vn * r.rig.impactWeight[i];
						if (b.thrower != null && b.age - b.thrownAge < 100) {
							r.thrower = b.thrower;
							r.thrownAge = r.age;
						}
					}
				}
				r.p[o] += n.x * (depth + dvParticle * h);
				r.p[o + 1] += n.y * (depth + dvParticle * h);
				r.p[o + 2] += n.z * (depth + dvParticle * h);
			}
		}
		// Giants' hands
		Vector3d cpos = new Vector3d(), cvel = new Vector3d();
		for (Giant g : giants) {
			for (HandState hand : g.hands) {
				if (!hand.valid || hand.held == r) {
					continue;
				}
				for (int k = 0; k < hand.colliderCount; k++) {
					double hr = hand.colliderRadius[k];
					hand.colliderPos(k, t, cpos);
					hand.colliderVel(k, cvel);
					double hx = cpos.x, hy = cpos.y, hz = cpos.z;
					for (int i = 0; i < r.rig.n; i++) {
						if (i == r.pinned) {
							continue;
						}
						int o = i * 3;
						double dx = r.p[o] - hx, dy = r.p[o + 1] - hy, dz = r.p[o + 2] - hz;
						double rad = hr + r.radius[i];
						double d2 = dx * dx + dy * dy + dz * dz;
						if (d2 >= rad * rad || d2 < 1e-10) {
							continue;
						}
						double d = Math.sqrt(d2);
						double nx = dx / d, ny = dy / d, nz = dz / d;
						double vx = (r.p[o] - r.x[o]) / h - cvel.x, vy = (r.p[o + 1] - r.x[o + 1]) / h - cvel.y, vz = (r.p[o + 2] - r.x[o + 2]) / h - cvel.z;
						double vn = vx * nx + vy * ny + vz * nz;
						double push = rad - d;
						if (vn < 0) {
							push += -vn * h;
							r.maxImpact = Math.max(r.maxImpact, -vn * r.rig.impactWeight[i]);
							if (cvel.length() > 3) {
								r.thrower = g.uuid;
								r.thrownAge = r.age;
							}
						}
						r.p[o] += nx * push;
						r.p[o + 1] += ny * push;
						r.p[o + 2] += nz * push;
					}
				}
			}
		}
		r.applyPin(t);
		r.solveConstraints(2);
		r.finish(h);
	}

	// ------------------------------------------------------------------ post tick

	private void postBodies() {
		TitanConfig cfg = TitanConfig.INSTANCE;
		List<RigidBody> toFracture = new ArrayList<>();
		for (RigidBody b : this.bodies) {
			if (b.removed) {
				continue;
			}
			b.age++;
			if (b.soundCooldown > 0) {
				b.soundCooldown--;
			}
			if (b.shockCooldown > 0) {
				b.shockCooldown--;
			}
			if (b.sleeping) {
				b.sleepTicks++;
				if (b.isDebris() && b.hold == null && b.sleepTicks > cfg.rubbleSettleSeconds * 20) {
					this.settle(b);
					continue;
				}
			} else {
				boolean slow = b.vel.lengthSquared() < 0.06 && b.angVel.lengthSquared() < 0.08;
				if (slow && b.hold == null) {
					if (++b.stillTicks > 10) {
						b.sleeping = true;
						b.vel.zero();
						b.angVel.zero();
						b.stateDirty = true;
						b.sleepTicks = 0;
					}
				} else {
					b.stillTicks = 0;
				}
				if (b.entity != null) {
					b.entity.setPos(b.pos.x, b.pos.y, b.pos.z);
				}
			}
			// Fracture when hit hard enough (impulse expressed as velocity change of the whole body).
			if (b.shape.voxel && b.shape.count() > 1) {
				double dv = b.impactImpulse / b.mass();
				double threshold = (b.hold != null ? 28.0 : 10.0) / Math.max(0.25, cfg.destructionMultiplier);
				if (dv > threshold) {
					toFracture.add(b);
				}
			}
			if (b.proxy != null) {
				this.proxyDamage(b);
			} else if (!b.sleeping && b.vel.lengthSquared() > cfg.ragdollLaunchSpeed * cfg.ragdollLaunchSpeed) {
				this.bodyHitsEntities(b);
			}
			b.impactImpulse = 0;
			b.breaksThisTick = 0;
		}
		for (RigidBody b : toFracture) {
			this.destruction.fracture(b);
		}
	}

	/** Debris / thrown chunks hitting creatures: momentum exchange decides launch speed and damage. */
	private void bodyHitsEntities(RigidBody b) {
		double r = b.shape.radius;
		AABB box = new AABB(b.pos.x - r, b.pos.y - r, b.pos.z - r, b.pos.x + r, b.pos.y + r, b.pos.z + r);
		Vector3d l = new Vector3d();
		double[] out = new double[4];
		for (Entity e : this.level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && e.isAlive())) {
			LivingEntity le = (LivingEntity) e;
			if (this.isRagdolled(le) || le instanceof Player p && GiantManager.isGiant(p)) {
				continue;
			}
			if (b.thrower != null && le.getUUID().equals(b.thrower)) {
				continue;
			}
			AABB eb = le.getBoundingBox();
			double er = Math.max(eb.getXsize(), eb.getZsize()) * 0.5 + 0.1;
			boolean hit = false;
			for (double fy : new double[] {0.25, 0.75}) {
				b.worldToLocal(eb.getCenter().x, eb.minY + eb.getYsize() * fy, eb.getCenter().z, l);
				if (b.shape.spherePenetration(l.x, l.y, l.z, er, out)) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				continue;
			}
			Vec3 ev = le.getDeltaMovement().scale(20);
			Vector3d rel = new Vector3d(b.vel).sub(ev.x, ev.y, ev.z);
			double vRel = rel.length();
			if (vRel < 3.0) {
				continue;
			}
			double m = entityMass(le);
			double mb = b.mass();
			Vector3d dv = collide(mb, m, rel);
			double energy = 0.5 * (mb * m / (mb + m)) * vRel * vRel;
			this.damage(le, (float) impactDamage(dv.length(), energy), b.thrower, b.age - b.thrownAge < 200);
			this.shoveOrLaunch(le, dv, b.thrower);
			// Equal and opposite: the body loses the momentum it handed over.
			b.vel.sub(new Vector3d(dv).mul(m / mb));
		}
	}

	void damage(LivingEntity e, float amount, UUID thrower, boolean recent) {
		if (amount <= 0.5F) {
			return;
		}
		TitanConfig cfg = TitanConfig.INSTANCE;
		if (cfg.giantsImmuneToPhysicsDamage && e instanceof Player p && GiantManager.isGiant(p)) {
			return;
		}
		DamageSource source;
		Player throwerPlayer = thrower != null && recent ? this.level.getPlayerByUUID(thrower) : null;
		if (throwerPlayer != null && throwerPlayer != e) {
			source = this.level.damageSources().playerAttack(throwerPlayer);
		} else {
			source = this.level.damageSources().flyIntoWall();
		}
		// Normal invulnerability frames apply, so one big hit isn't followed by a pile of small ones.
		e.hurtServer(this.level, source, amount);
	}

	private void proxyDamage(RigidBody b) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		double dv = b.impactImpulse / b.mass();
		if (b.age > 2) {
			this.damage(b.proxy, (float) impactDamage(dv, 0), b.thrower, b.age - b.thrownAge < 200);
		}
	}

	private void postRagdolls() {
		TitanConfig cfg = TitanConfig.INSTANCE;
		List<Integer> toEnd = new ArrayList<>();
		for (var entry : this.ragdolls.int2ObjectEntrySet()) {
			Ragdoll r = entry.getValue();
			r.age++;
			if (r.damageCooldown > 0) {
				r.damageCooldown--;
			}
			double hurt = impactDamage(r.maxImpact, 0);
			if (hurt > 0.5 && r.damageCooldown == 0 && r.age > 2) {
				this.damage(r.entity, (float) hurt, r.thrower, r.age - r.thrownAge < 200);
				r.damageCooldown = 8;
			}
			r.maxImpact = 0;
			this.syncRagdollEntity(r);
			boolean held = r.hold != null;
			if (!held && r.maxSpeed() < 1.2) {
				r.stillTicks++;
			} else {
				r.stillTicks = 0;
			}
			boolean dead = !r.entity.isAlive();
			if (!held && !dead && !dev.titanvr.AutoTest.holdRagdolls && (r.stillTicks > 30 || r.age > cfg.maxRagdollSeconds * 20)) {
				toEnd.add(entry.getIntKey());
			}
		}
		for (int id : toEnd) {
			Ragdoll r = this.ragdolls.remove(id);
			this.endRagdoll(id, r.entity, r.hadNoAi);
		}
		toEnd.clear();
		for (var entry : this.rigidRagdolls.int2ObjectEntrySet()) {
			RigidBody b = entry.getValue();
			LivingEntity e = b.proxy;
			e.setPos(b.pos.x, b.pos.y - b.shape.boxes[4], b.pos.z);
			e.setDeltaMovement(Vec3.ZERO);
			e.resetFallDistance();
			boolean held = b.hold != null;
			if (!held && e.isAlive() && (b.sleeping || b.age > cfg.maxRagdollSeconds * 20)) {
				toEnd.add(entry.getIntKey());
			}
		}
		for (int id : toEnd) {
			RigidBody b = this.rigidRagdolls.remove(id);
			this.removeBody(b);
			this.endRagdoll(id, b.proxy, b.hadNoAi);
		}
	}

	private void syncRagdollEntity(Ragdoll r) {
		LivingEntity e = r.entity;
		int o = r.rig.head * 3;
		// Put the eyes in the ragdoll's head so a ragdolled player's camera rides along.
		double eye = e.getEyeHeight();
		double x = r.x[o], y = r.x[o + 1] - eye, z = r.x[o + 2];
		e.setPos(x, y, z);
		e.setDeltaMovement(Vec3.ZERO);
		e.resetFallDistance();
		if (e instanceof ServerPlayer sp) {
			this.level.getChunkSource().move(sp);
		}
	}

	// ------------------------------------------------------------------ networking

	public void sendShape(PhysicsBodyEntity entity, ServerPlayer player) {
		RigidBody b = entity.body;
		if (b == null) {
			return;
		}
		int n = b.shape.count();
		float[] boxes = new float[n * 6];
		int[] states = new int[n];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 6; k++) {
				boxes[i * 6 + k] = (float) b.shape.boxes[i * 6 + k];
			}
			BlockState s = b.shape.states[i];
			states[i] = s == null ? -1 : net.minecraft.world.level.block.Block.getId(s);
		}
		ServerPlayNetworking.send(player, new TitanNet.BodyShapeS2C(entity.getId(), boxes, states));
		double[] pos = {b.pos.x, b.pos.y, b.pos.z};
		float[] rot = {(float) b.rot.x, (float) b.rot.y, (float) b.rot.z, (float) b.rot.w};
		ServerPlayNetworking.send(player, new TitanNet.BodyStatesS2C(new int[] {entity.getId()}, pos, rot));
	}

	private static final class Batch {
		final IntArrayList ids = new IntArrayList();
		final DoubleArrayList pos = new DoubleArrayList();
		final FloatArrayList rot = new FloatArrayList();
		final ByteArrayList rkinds = new ByteArrayList();
		final ByteArrayList rrigs = new ByteArrayList();
		final IntArrayList rids = new IntArrayList();
		final FloatArrayList rparams = new FloatArrayList();
		final DoubleArrayList rvalues = new DoubleArrayList();
	}

	private void sync() {
		Map<ServerPlayer, Batch> batches = new HashMap<>();
		for (RigidBody b : this.bodies) {
			if (b.removed || b.entity == null || !(!b.sleeping || b.stateDirty)) {
				continue;
			}
			b.stateDirty = false;
			for (ServerPlayer p : PlayerLookup.tracking(b.entity)) {
				Batch batch = batches.computeIfAbsent(p, k -> new Batch());
				batch.ids.add(b.entity.getId());
				batch.pos.add(b.pos.x);
				batch.pos.add(b.pos.y);
				batch.pos.add(b.pos.z);
				batch.rot.add((float) b.rot.x);
				batch.rot.add((float) b.rot.y);
				batch.rot.add((float) b.rot.z);
				batch.rot.add((float) b.rot.w);
			}
		}
		for (Ragdoll r : this.ragdolls.values()) {
			for (ServerPlayer p : this.watchers(r.entity)) {
				Batch batch = batches.computeIfAbsent(p, k -> new Batch());
				batch.rkinds.add(TitanNet.RAGDOLL_HUMANOID);
				batch.rrigs.add((byte) r.rig.id);
				batch.rids.add(r.entity.getId());
				batch.rparams.add((float) r.scale);
				for (int i = 0; i < r.rig.n * 3; i++) {
					batch.rvalues.add(r.x[i]);
				}
			}
		}
		for (RigidBody b : this.rigidRagdolls.values()) {
			for (ServerPlayer p : this.watchers(b.proxy)) {
				Batch batch = batches.computeIfAbsent(p, k -> new Batch());
				batch.rkinds.add(TitanNet.RAGDOLL_RIGID);
				batch.rrigs.add((byte) 0);
				batch.rids.add(b.proxy.getId());
				batch.rparams.add((float) b.shape.boxes[4]);
				batch.rvalues.add(b.pos.x);
				batch.rvalues.add(b.pos.y);
				batch.rvalues.add(b.pos.z);
				batch.rvalues.add(b.rot.x);
				batch.rvalues.add(b.rot.y);
				batch.rvalues.add(b.rot.z);
				batch.rvalues.add(b.rot.w);
			}
		}
		for (RagdollEnd end : this.endedRagdolls) {
			List<ServerPlayer> targets = end.entity != null ? this.watchers(end.entity) : new ArrayList<>(this.level.players());
			for (ServerPlayer p : targets) {
				Batch batch = batches.computeIfAbsent(p, k -> new Batch());
				batch.rkinds.add(TitanNet.RAGDOLL_END);
				batch.rrigs.add((byte) 0);
				batch.rids.add(end.entityId);
				batch.rparams.add(0F);
			}
		}
		this.endedRagdolls.clear();
		for (var entry : batches.entrySet()) {
			Batch b = entry.getValue();
			if (!b.ids.isEmpty()) {
				ServerPlayNetworking.send(entry.getKey(), new TitanNet.BodyStatesS2C(b.ids.toIntArray(), b.pos.toDoubleArray(), b.rot.toFloatArray()));
			}
			if (!b.rids.isEmpty()) {
				ServerPlayNetworking.send(entry.getKey(), new TitanNet.RagdollStatesS2C(b.rkinds.toByteArray(), b.rrigs.toByteArray(), b.rids.toIntArray(), b.rparams.toFloatArray(), b.rvalues.toDoubleArray()));
			}
		}
	}

	private List<ServerPlayer> watchers(Entity e) {
		List<ServerPlayer> list = new ArrayList<>(PlayerLookup.tracking(e));
		if (e instanceof ServerPlayer sp && !list.contains(sp)) {
			list.add(sp);
		}
		return list;
	}

	static void haptic(ServerPlayer player, int hand, float seconds) {
		VivecraftCompat.haptic(player, hand, seconds);
	}
}
