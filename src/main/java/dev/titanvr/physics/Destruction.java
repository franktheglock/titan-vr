package dev.titanvr.physics;

import dev.titanvr.TitanConfig;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.HandState;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/** Everything that turns the world into debris: impacts, giant hands, ripping, collapse and fracturing. */
public final class Destruction {
	private final PhysicsWorld world;
	private final ServerLevel level;
	private final LongOpenHashSet brokenThisTick = new LongOpenHashSet();
	private final LongArrayFIFOQueue collapseQueue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private int debrisBudget;
	private int fxBudget;
	private final Map<Integer, Integer> handHitCooldown = new HashMap<>();

	Destruction(PhysicsWorld world) {
		this.world = world;
		this.level = world.level;
	}

	void beginTick() {
		this.brokenThisTick.clear();
		this.debrisBudget = TitanConfig.INSTANCE.maxNewDebrisPerTick;
		this.fxBudget = 40;
		this.handHitCooldown.entrySet().removeIf(e -> e.setValue(e.getValue() - 1) <= 0);
	}

	// ------------------------------------------------------------------ rules

	static boolean breakable(BlockState state, ServerLevel level, BlockPos pos) {
		if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
			return false;
		}
		return !state.liquid();
	}

	/** Kinetic energy (0.5 m v^2, blocks & seconds) needed to smash a block. */
	static double breakEnergy(BlockState state, ServerLevel level, BlockPos pos) {
		float h = state.getDestroySpeed(level, pos);
		if (h < 0) {
			return Double.MAX_VALUE;
		}
		double terrain = isTerrain(state) ? 4.0 : 1.0;
		// Calibrated so a stone voxel (2.6 t) thrown at ~10 b/s breaks planks, a leaf barely breaks other leaves.
		return 130.0 * (h + 0.4) * terrain / Math.max(0.05, TitanConfig.INSTANCE.destructionMultiplier);
	}

	/** Natural ground is tougher against debris so falling rubble doesn't pockmark the landscape. */
	static boolean isTerrain(BlockState state) {
		return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
			|| state.is(BlockTags.BASE_STONE_NETHER) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL);
	}

	private boolean structure(int x, int y, int z) {
		return this.world.collider.solid(x, y, z);
	}

	/** Blocks that hold up whatever touches them: terrain stone, unbreakables, block entities, world bottom. */
	private boolean anchor(BlockState state, BlockPos pos) {
		if (pos.getY() <= this.level.getMinY() + 1) {
			return true;
		}
		if (state.hasBlockEntity() || state.getDestroySpeed(this.level, pos) < 0) {
			return true;
		}
		return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
	}

	// ------------------------------------------------------------------ breaking

	/**
	 * Called when a body point hits a world cell at speed. Breaks the block if the impact carries enough energy,
	 * transferring some momentum to the new debris. Returns true if the block broke (no contact needed).
	 */
	boolean tryImpactBreak(RigidBody b, int cx, int cy, int cz, double approach, double effMass, Vector3d vp,
		double nx, double ny, double nz, double px, double py, double pz) {
		int cap = 3 + (int) Math.sqrt(b.shape.count()) + (b.hold != null ? 6 : 0);
		if (b.breaksThisTick >= cap) {
			return false;
		}
		BlockPos pos = new BlockPos(cx, cy, cz);
		BlockState state = this.world.collider.state(cx, cy, cz);
		if (state == null || !breakable(state, this.level, pos)) {
			return false;
		}
		double energy = 0.5 * effMass * approach * approach;
		if (energy < breakEnergy(state, this.level, pos)) {
			return false;
		}
		b.breaksThisTick++;
		double blockMass = BodyShape.voxelMass(state);
		double j = Math.min(blockMass * approach * 0.9, b.mass() * approach * 0.5);
		b.applyImpulse(nx * j, ny * j, nz * j, px, py, pz);
		Vector3d debrisVel = new Vector3d(vp).mul(0.65);
		this.breakBlock(pos, state, debrisVel, true, b.thrower);
		return true;
	}

	boolean tryRagdollBreak(Ragdoll r, int cx, int cy, int cz, double approach, double vx, double vy, double vz) {
		BlockPos pos = new BlockPos(cx, cy, cz);
		BlockState state = this.world.collider.state(cx, cy, cz);
		if (state == null || !breakable(state, this.level, pos)) {
			return false;
		}
		double energy = 0.5 * 0.08 * r.scale * approach * approach;
		if (energy < breakEnergy(state, this.level, pos)) {
			return false;
		}
		this.breakBlock(pos, state, new Vector3d(vx, vy, vz).mul(0.5), true, r.thrower);
		return true;
	}

	/** Removes a block, optionally turning it into a flying voxel. */
	public void breakBlock(BlockPos pos, BlockState state, Vector3d vel, boolean debris, UUID thrower) {
		long key = pos.asLong();
		if (!this.brokenThisTick.add(key)) {
			return;
		}
		if (state.hasBlockEntity()) {
			this.level.destroyBlock(pos, true);
			this.afterRemoval(pos);
			return;
		}
		boolean solid = this.world.collider.solid(pos.getX(), pos.getY(), pos.getZ());
		this.level.setBlock(pos, state.getFluidState().createLegacyBlock(), Block.UPDATE_ALL);
		if (this.fxBudget > 0) {
			this.fxBudget--;
			this.level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
		}
		if (debris && solid && this.debrisBudget > 0) {
			this.debrisBudget--;
			Vector3d v = new Vector3d(vel).add(
				(this.world.random.nextDouble() - 0.5) * 3.0,
				(this.world.random.nextDouble() - 0.2) * 3.0,
				(this.world.random.nextDouble() - 0.5) * 3.0);
			RigidBody body = this.world.spawnVoxelBody(List.of(pos.immutable()), List.of(state), v, false);
			if (body != null) {
				body.angVel.set(
					(this.world.random.nextDouble() - 0.5) * 10,
					(this.world.random.nextDouble() - 0.5) * 10,
					(this.world.random.nextDouble() - 0.5) * 10);
				body.thrower = thrower;
				body.thrownAge = 0;
			}
		}
		this.afterRemoval(pos);
	}

	void afterRemoval(BlockPos pos) {
		this.world.collider.invalidate(pos);
		for (int f = 0; f < 6; f++) {
			BlockPos n = pos.offset(BodyShape.FACE_X[f], BodyShape.FACE_Y[f], BodyShape.FACE_Z[f]);
			this.world.collider.invalidate(n);
			if (TitanConfig.INSTANCE.structuralCollapse) {
				long k = n.asLong();
				if (this.queued.add(k)) {
					this.collapseQueue.enqueue(k);
				}
			}
		}
		// Wake anything resting on or against the removed block.
		double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
		for (RigidBody b : this.world.bodies()) {
			if (b.sleeping && !b.removed) {
				double r = b.shape.radius + 1.5;
				double dx = b.pos.x - cx, dy = b.pos.y - cy, dz = b.pos.z - cz;
				if (dx * dx + dy * dy + dz * dz < r * r) {
					b.wake();
				}
			}
		}
	}

	// ------------------------------------------------------------------ shockwaves

	private record Shockwave(double x, double y, double z, double energy, RigidBody source, UUID cause) {
	}

	private final java.util.ArrayList<Shockwave> shockwaves = new java.util.ArrayList<>();

	void queueShockwave(double x, double y, double z, double energy, RigidBody source) {
		this.shockwaves.add(new Shockwave(x, y, z, energy, source, source != null ? source.thrower : null));
	}

	void queueShockwave(double x, double y, double z, double energy, UUID cause) {
		this.shockwaves.add(new Shockwave(x, y, z, energy, null, cause));
	}

	/**
	 * A heavy impact's energy radiates outwards: nearby blocks that can't take it shatter into debris flung away from
	 * the impact, creatures are knocked back (and hurt) and loose debris is pushed. Strength falls off with distance.
	 */
	void processShockwaves() {
		if (this.shockwaves.isEmpty()) {
			return;
		}
		java.util.List<Shockwave> list = new java.util.ArrayList<>(this.shockwaves);
		this.shockwaves.clear();
		for (Shockwave w : list) {
			double e = w.energy();
			double radius = Math.max(1.5, Math.min(10.0, 0.18 * Math.cbrt(e)));
			double root = Math.sqrt(e);
			int broken = 0;
			int r = (int) Math.ceil(radius);
			BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
			int cx = (int) Math.floor(w.x()), cy = (int) Math.floor(w.y()), cz = (int) Math.floor(w.z());
			for (int x = cx - r; x <= cx + r && broken < 140; x++) {
				for (int y = cy - r; y <= cy + r && broken < 140; y++) {
					for (int z = cz - r; z <= cz + r && broken < 140; z++) {
						double dx = x + 0.5 - w.x(), dy = y + 0.5 - w.y(), dz = z + 0.5 - w.z();
						double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
						if (d > radius || !this.world.collider.solid(x, y, z)) {
							continue;
						}
						double f = 1.0 - d / radius;
						mp.set(x, y, z);
						BlockState state = this.world.collider.state(x, y, z);
						if (state == null || !breakable(state, this.level, mp) || breakEnergy(state, this.level, mp) > e * f * f * 0.02) {
							continue;
						}
						double out = Math.min(30.0, 2.0 + root / 12.0 * f);
						Vector3d v = d > 0.01 ? new Vector3d(dx, dy, dz).div(d).mul(out).add(0, out * 0.4, 0) : new Vector3d(0, out, 0);
						this.breakBlock(mp.immutable(), state, v, true, w.cause());
						broken++;
					}
				}
			}
			// Creatures
			double reach = radius * 1.6;
			AABB box = new AABB(w.x() - reach, w.y() - reach, w.z() - reach, w.x() + reach, w.y() + reach, w.z() + reach);
			for (Entity ent : this.level.getEntities((Entity) null, box, en -> en instanceof LivingEntity && en.isAlive())) {
				LivingEntity le = (LivingEntity) ent;
				if (le instanceof Player p && dev.titanvr.giant.GiantManager.isGiant(p)) {
					continue;
				}
				Vector3d away = new Vector3d(le.getX() - w.x(), le.getY() + le.getBbHeight() * 0.5 - w.y(), le.getZ() - w.z());
				double d = away.length();
				if (d > reach) {
					continue;
				}
				double f = 1.0 - d / reach;
				double m = PhysicsWorld.entityMass(le);
				double dvMag = Math.min(45.0, root * f / (6.0 * Math.sqrt(m)));
				Vector3d dv = (d > 0.01 ? away.div(d) : new Vector3d(0, 1, 0)).mul(dvMag).add(0, dvMag * 0.35, 0);
				if (this.world.isRagdolled(le)) {
					Object rd = this.world.ragdoll(le);
					if (rd instanceof Ragdoll hr) {
						hr.addVelocity(dv.x, dv.y, dv.z);
					} else if (rd instanceof RigidBody rb) {
						rb.wake();
						rb.vel.add(dv);
					}
				} else {
					this.world.damage(le, (float) PhysicsWorld.impactDamage(dvMag, 0), w.cause(), true);
					this.world.shoveOrLaunch(le, dv, w.cause());
				}
			}
			// Loose debris
			for (RigidBody b : this.world.bodies()) {
				if (b.removed || b == w.source() || b.hold != null) {
					continue;
				}
				Vector3d away = new Vector3d(b.pos).sub(w.x(), w.y(), w.z());
				double d = away.length();
				if (d > radius * 1.3 || d < 0.01) {
					continue;
				}
				double f = 1.0 - d / (radius * 1.3);
				b.wake();
				b.vel.add(away.div(d).mul(Math.min(30.0, root * f / (8.0 * Math.sqrt(b.mass())))));
			}
			if (dev.titanvr.AutoTest.ENABLED) {
				dev.titanvr.TitanMod.LOGGER.info("[autotest] shockwave energy={} radius={} blocks broken={}", (int) e, String.format("%.1f", radius), broken);
			}
			float volume = (float) Math.min(6.0, 0.5 + e / 20000.0);
			this.level.playSound(null, w.x(), w.y(), w.z(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, volume,
				(float) Math.max(0.4, 1.1 - e / 200000.0));
			this.level.sendParticles(e > 40000 ? net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER : net.minecraft.core.particles.ParticleTypes.EXPLOSION,
				w.x(), w.y(), w.z(), 1 + (int) (radius / 2), radius * 0.3, 0.2, radius * 0.3, 0.0);
		}
	}

	// ------------------------------------------------------------------ giant hands

	void handSmash(List<Giant> giants, Map<Giant, ServerPlayer> players) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		Vector3d from = new Vector3d(), to = new Vector3d(), vel = new Vector3d();
		for (Giant g : giants) {
			ServerPlayer player = players.get(g);
			double sizeFactor = Math.sqrt(g.scale / 8.0);
			double threshold = cfg.handSmashSpeed * sizeFactor;
			for (int handIdx = 0; handIdx < 2; handIdx++) {
				HandState hand = g.hands[handIdx];
				if (!hand.valid || hand.held != null) {
					continue;
				}
				int broken = 0;
				// Fist first, then along the tool out to its head (which swings fastest).
				for (int k = 0; k < hand.colliderCount && broken < cfg.maxHandBreaksPerTick; k++) {
					double r = hand.colliderRadius[k];
					hand.colliderVel(k, vel);
					double speed = vel.length();
					hand.colliderPos(k, 0, from);
					hand.colliderPos(k, 1, to);
					this.handHitsEntities(g, hand, handIdx, player, to, vel, r, k == hand.toolHead ? hand.toolMass : 1.0);
					if (speed < threshold) {
						continue;
					}
					broken += this.smashAlong(g, hand, from, to, vel, r, speed, threshold, cfg.maxHandBreaksPerTick - broken);
				}
				if (broken > 0 && player != null && hand.hitCooldown == 0) {
					PhysicsWorld.haptic(player, handIdx, 0.08F);
					hand.hitCooldown = 3;
				}
			}
		}
	}

	/** Breaks what a hand collider sweeps through this tick. A matching tool (pickaxe on stone...) bites deeper. */
	private int smashAlong(Giant g, HandState hand, Vector3d from, Vector3d to, Vector3d vel, double r, double speed, double threshold, int budget) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		int broken = 0;
		double len = from.distance(to);
		int steps = Math.max(1, (int) Math.ceil(len / 0.4));
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		for (int s = 0; s <= steps; s++) {
			double t = (double) s / steps;
			double cx = from.x + (to.x - from.x) * t, cy = from.y + (to.y - from.y) * t, cz = from.z + (to.z - from.z) * t;
			int x0 = (int) Math.floor(cx - r), x1 = (int) Math.floor(cx + r);
			int y0 = (int) Math.floor(cy - r), y1 = (int) Math.floor(cy + r);
			int z0 = (int) Math.floor(cz - r), z1 = (int) Math.floor(cz + r);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
						if (dx * dx + dy * dy + dz * dz > (r + 0.3) * (r + 0.3)) {
							continue;
						}
						byte type = this.world.collider.type(x, y, z);
						if (type == WorldCollider.EMPTY || type == WorldCollider.UNLOADED) {
							continue;
						}
						mp.set(x, y, z);
						BlockState state = this.world.collider.state(x, y, z);
						if (state == null || !breakable(state, this.level, mp)) {
							continue;
						}
						float hardness = state.getDestroySpeed(this.level, mp);
						double toolBonus = hand.tool.isEmpty() ? 1.0 : Math.max(1.0, hand.tool.getDestroySpeed(state) / 2.0);
						double effHardness = hardness / toolBonus;
						if (effHardness > 2.5 * g.scale * cfg.destructionMultiplier || speed < threshold * (1 + effHardness * 0.12)) {
							continue;
						}
						this.breakBlock(mp.immutable(), state, new Vector3d(vel).mul(0.85), true, g.uuid);
						if (++broken >= budget) {
							return broken;
						}
					}
				}
			}
		}
		return broken;
	}

	/**
	 * A giant's hand (or the tool in it) hitting a creature: a collision between the (very heavy) hand and the
	 * creature's body. The velocity change it receives decides both how far it flies and how much it hurts.
	 */
	private void handHitsEntities(Giant g, HandState hand, int handIdx, ServerPlayer player, Vector3d pos, Vector3d vel, double r, double massMul) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		if (vel.length() < 2.0) {
			return;
		}
		AABB box = new AABB(pos.x - r, pos.y - r, pos.z - r, pos.x + r, pos.y + r, pos.z + r);
		double handMass = cfg.handMass * Math.pow(g.scale / 8.0, 3) * massMul;
		for (Entity e : this.level.getEntities((Entity) null, box, e -> e instanceof LivingEntity && e.isAlive())) {
			LivingEntity le = (LivingEntity) e;
			if (le instanceof Player p && (p.getUUID().equals(g.uuid) || dev.titanvr.giant.GiantManager.isGiant(p))) {
				continue;
			}
			if (this.world.isRagdolled(le) || this.handHitCooldown.containsKey(le.getId())) {
				continue; // ragdolls are already pushed by the hand colliders inside the solver
			}
			net.minecraft.world.phys.Vec3 ev = le.getDeltaMovement().scale(20);
			Vector3d rel = new Vector3d(vel).sub(ev.x, ev.y, ev.z);
			double vRel = rel.length();
			if (vRel < 1.0) {
				continue;
			}
			this.handHitCooldown.put(le.getId(), 10);
			double m = PhysicsWorld.entityMass(le);
			Vector3d dv = PhysicsWorld.collide(handMass, m, rel);
			double energy = 0.5 * (handMass * m / (handMass + m)) * vRel * vRel;
			this.world.damage(le, (float) PhysicsWorld.impactDamage(dv.length(), energy), g.uuid, true);
			this.world.shoveOrLaunch(le, dv, g.uuid);
			if (player != null && dv.length() > cfg.ragdollLaunchSpeed) {
				PhysicsWorld.haptic(player, handIdx, 0.15F);
			}
		}
	}

	// ------------------------------------------------------------------ ripping chunks out

	/**
	 * Tears whatever the fist has hold of out of the world - shearing along the weakest seam (see {@link Shear}) -
	 * and returns it as one body. Grip a trunk and the whole tree comes with it.
	 */
	RigidBody rip(Vector3d center, double radius, double region, int maxVoxels, double maxHardness, UUID giant) {
		// (positions come from the min-cut, not a sphere)
		LongArrayList cluster = new Shear(this.level, this.world.collider).solve(center, radius, region, maxVoxels, maxHardness);
		List<long[]> found = new ArrayList<>();
		for (int i = 0; i < cluster.size(); i++) {
			found.add(new long[] {cluster.getLong(i), 0});
		}
		if (found.isEmpty()) {
			return null;
		}
		List<BlockPos> positions = new ArrayList<>();
		List<BlockState> states = new ArrayList<>();
		for (int i = 0; i < found.size(); i++) {
			BlockPos p = BlockPos.of(found.get(i)[0]);
			positions.add(p);
			states.add(this.level.getBlockState(p));
		}
		RigidBody body = this.world.spawnVoxelBody(positions, states, new Vector3d(), true);
		if (body == null) {
			return null;
		}
		// Carve the whole cluster out first, then notify neighbours, so half-removed trees don't drop leaves/items.
		for (int i = 0; i < positions.size(); i++) {
			BlockPos p = positions.get(i);
			this.brokenThisTick.add(p.asLong());
			this.level.setBlock(p, states.get(i).getFluidState().createLegacyBlock(), Block.UPDATE_CLIENTS);
			if (i % 8 == 0 && this.fxBudget > 0) {
				this.fxBudget--;
				this.level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, p, Block.getId(states.get(i)));
			}
		}
		for (BlockPos p : positions) {
			this.level.updateNeighborsAt(p, this.level.getBlockState(p).getBlock());
			this.afterRemoval(p);
		}
		this.level.playSound(null, center.x, center.y, center.z, states.get(0).getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.5F, 0.6F);
		body.thrower = giant;
		return body;
	}

	// ------------------------------------------------------------------ structural collapse

	void processCollapse() {
		TitanConfig cfg = TitanConfig.INSTANCE;
		if (!cfg.structuralCollapse) {
			this.collapseQueue.clear();
			this.queued.clear();
			return;
		}
		int visitBudget = 40000;
		int seeds = 0;
		LongOpenHashSet groundedThisTick = new LongOpenHashSet();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		while (!this.collapseQueue.isEmpty() && visitBudget > 0 && seeds < 24) {
			long seed = this.collapseQueue.dequeueLong();
			this.queued.remove(seed);
			if (groundedThisTick.contains(seed)) {
				continue;
			}
			int sx = BlockPos.getX(seed), sy = BlockPos.getY(seed), sz = BlockPos.getZ(seed);
			if (!this.structure(sx, sy, sz)) {
				continue;
			}
			seeds++;
			LongOpenHashSet visited = new LongOpenHashSet();
			LongArrayFIFOQueue frontier = new LongArrayFIFOQueue();
			LongArrayList component = new LongArrayList();
			visited.add(seed);
			frontier.enqueue(seed);
			boolean grounded = false;
			while (!frontier.isEmpty()) {
				long cur = frontier.dequeueLong();
				int x = BlockPos.getX(cur), y = BlockPos.getY(cur), z = BlockPos.getZ(cur);
				mp.set(x, y, z);
				BlockState state = this.world.collider.state(x, y, z);
				if (this.world.collider.type(x, y, z) == WorldCollider.UNLOADED || state == null || this.anchor(state, mp)
					|| groundedThisTick.contains(cur)) {
					grounded = true;
					break;
				}
				component.add(cur);
				visitBudget--;
				if (component.size() > cfg.collapseSearchLimit) {
					grounded = true;
					break;
				}
				for (int f = 0; f < 6; f++) {
					int nx = x + BodyShape.FACE_X[f], ny = y + BodyShape.FACE_Y[f], nz = z + BodyShape.FACE_Z[f];
					long nk = BlockPos.asLong(nx, ny, nz);
					if (visited.add(nk) && (this.structure(nx, ny, nz) || this.world.collider.type(nx, ny, nz) == WorldCollider.UNLOADED)) {
						frontier.enqueue(nk);
					}
				}
			}
			if (grounded) {
				groundedThisTick.addAll(visited);
				continue;
			}
			this.detach(component);
		}
	}

	/** Turns an unsupported group of blocks into falling rigid bodies. */
	private void detach(LongArrayList component) {
		Map<Long, List<BlockPos>> groups = new HashMap<>();
		boolean split = component.size() > 1200;
		for (int i = 0; i < component.size(); i++) {
			long k = component.getLong(i);
			BlockPos p = BlockPos.of(k);
			long g = split ? BlockPos.asLong(Math.floorDiv(p.getX(), 8), Math.floorDiv(p.getY(), 8), Math.floorDiv(p.getZ(), 8)) : 0L;
			groups.computeIfAbsent(g, x -> new ArrayList<>()).add(p);
		}
		List<BlockPos> removed = new ArrayList<>();
		for (List<BlockPos> group : groups.values()) {
			List<BlockPos> positions = new ArrayList<>();
			List<BlockState> states = new ArrayList<>();
			for (BlockPos p : group) {
				BlockState s = this.level.getBlockState(p);
				if (s.isAir()) {
					continue;
				}
				if (s.hasBlockEntity()) {
					this.level.destroyBlock(p, true);
					removed.add(p);
					continue;
				}
				positions.add(p);
				states.add(s);
			}
			if (positions.isEmpty()) {
				continue;
			}
			RigidBody body = this.world.spawnVoxelBody(positions, states, new Vector3d(0, -0.5, 0), true);
			if (body == null) {
				continue;
			}
			for (int i = 0; i < positions.size(); i++) {
				// UPDATE_CLIENTS only while carving the group out; neighbours get notified afterwards.
				this.level.setBlock(positions.get(i), states.get(i).getFluidState().createLegacyBlock(), Block.UPDATE_CLIENTS);
				this.brokenThisTick.add(positions.get(i).asLong());
			}
			removed.addAll(positions);
		}
		for (BlockPos p : removed) {
			this.level.updateNeighborsAt(p, this.level.getBlockState(p).getBlock());
			this.world.collider.invalidate(p);
		}
		for (BlockPos p : removed) {
			for (int f = 0; f < 6; f++) {
				this.world.collider.invalidate(p.offset(BodyShape.FACE_X[f], BodyShape.FACE_Y[f], BodyShape.FACE_Z[f]));
			}
		}
		if (!removed.isEmpty()) {
			BlockPos p = removed.get(removed.size() / 2);
			this.level.playSound(null, p, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.6F, 0.5F);
		}
	}

	// ------------------------------------------------------------------ fracturing

	/** Breaks a voxel body into several pieces (Voronoi-style partition). */
	void fracture(RigidBody b) {
		if (b.removed || !b.shape.voxel) {
			return;
		}
		BodyShape shape = b.shape;
		int n = shape.count();
		int k = Math.max(2, Math.min(14, n / 4));
		if (n <= 3) {
			k = n;
		}
		int[] seedIdx = new int[k];
		for (int i = 0; i < k; i++) {
			seedIdx[i] = this.world.random.nextInt(n);
		}
		int[] owner = new int[n];
		for (int i = 0; i < n; i++) {
			double best = Double.MAX_VALUE;
			for (int s = 0; s < k; s++) {
				int a = i * 3, c = seedIdx[s] * 3;
				double dx = shape.cells[a] - shape.cells[c], dy = shape.cells[a + 1] - shape.cells[c + 1], dz = shape.cells[a + 2] - shape.cells[c + 2];
				double d = dx * dx + dy * dy + dz * dz + this.world.random.nextDouble() * 0.8;
				if (d < best) {
					best = d;
					owner[i] = s;
				}
			}
		}
		List<RigidBody> pieces = new ArrayList<>();
		Vector3d w = new Vector3d();
		for (int s = 0; s < k; s++) {
			List<int[]> cells = new ArrayList<>();
			List<BlockState> states = new ArrayList<>();
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				if (owner[i] == s) {
					minX = Math.min(minX, shape.cells[i * 3]);
					minY = Math.min(minY, shape.cells[i * 3 + 1]);
					minZ = Math.min(minZ, shape.cells[i * 3 + 2]);
				}
			}
			for (int i = 0; i < n; i++) {
				if (owner[i] == s) {
					cells.add(new int[] {shape.cells[i * 3] - minX, shape.cells[i * 3 + 1] - minY, shape.cells[i * 3 + 2] - minZ});
					states.add(shape.states[i]);
				}
			}
			if (cells.isEmpty()) {
				continue;
			}
			double[] com = new double[3];
			BodyShape ps = BodyShape.voxels(cells, states, com);
			RigidBody piece = new RigidBody(this.world.nextId++, ps);
			// Piece COM in the parent's local frame -> world.
			b.localToWorld(minX + com[0] + shape.gox, minY + com[1] + shape.goy, minZ + com[2] + shape.goz, w);
			piece.pos.set(w);
			piece.rot.set(b.rot);
			b.velocityAt(w.x, w.y, w.z, piece.vel);
			Vector3d out = new Vector3d(w).sub(b.pos);
			if (out.lengthSquared() > 1e-6) {
				out.normalize().mul(1.2 + this.world.random.nextDouble() * 1.5);
				piece.vel.add(out);
			}
			piece.angVel.set(b.angVel).add(
				(this.world.random.nextDouble() - 0.5) * 3,
				(this.world.random.nextDouble() - 0.5) * 3,
				(this.world.random.nextDouble() - 0.5) * 3);
			piece.thrower = b.thrower;
			piece.thrownAge = b.thrownAge - b.age;
			piece.updateDerived();
			pieces.add(piece);
		}
		RigidBody.Hold hold = b.hold;
		this.world.removeBody(b);
		for (RigidBody p : pieces) {
			this.world.addBody(p);
		}
		if (hold != null) {
			this.world.grabbing.rebindAfterFracture(hold, pieces);
		}
		Vector3d c = b.pos;
		this.level.playSound(null, c.x, c.y, c.z, shape.states[0] != null ? shape.states[0].getSoundType().getBreakSound()
			: net.minecraft.sounds.SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.2F, 0.7F);
		if (shape.states[0] != null && this.fxBudget > 0) {
			this.fxBudget--;
			this.level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, BlockPos.containing(c.x, c.y, c.z), Block.getId(shape.states[0]));
		}
	}
}
