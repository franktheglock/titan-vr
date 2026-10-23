package dev.titanvr.physics;

import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * Projectiles fired by a giant are giant too: scaled up (hitbox and model), faster, heavy enough to bowl creatures
 * over along their whole width, and they land with a shockwave sized by their momentum.
 */
public final class GiantProjectiles {
	/** Implemented on every Projectile by a mixin; 0 or 1 = normal size. */
	public interface Scaled {
		float titanvr$getScale();

		void titanvr$setScale(float scale);
	}

	private static final class Tracked {
		final Projectile projectile;
		final double scale;
		final double mass;
		final UUID owner;
		Vec3 lastPos;
		Vec3 lastVel;
		final IntOpenHashSet hit = new IntOpenHashSet();
		int age;

		Tracked(Projectile projectile, double scale, double mass, UUID owner) {
			this.projectile = projectile;
			this.scale = scale;
			this.mass = mass;
			this.owner = owner;
			this.lastPos = projectile.position();
			this.lastVel = projectile.getDeltaMovement().scale(20);
		}
	}

	private static final Map<Projectile, Tracked> TRACKED = new IdentityHashMap<>();

	/** Projectile scales where the Projectile mixin isn't available (the Paper plugin). */
	private static final Map<Entity, Float> UNMIXED_SCALES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	private GiantProjectiles() {
	}

	public static void setScale(Entity entity, float scale) {
		if (entity instanceof Scaled s) {
			s.titanvr$setScale(scale);
		} else {
			UNMIXED_SCALES.put(entity, scale);
		}
	}

	public static float scaleOf(Entity entity) {
		if (entity instanceof Scaled s) {
			return s.titanvr$getScale();
		}
		Float f = UNMIXED_SCALES.get(entity);
		return f != null ? f : 0;
	}

	/** Server: a projectile entered the world. If a giant fired it, grow it and start tracking it. */
	public static void onLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof Projectile p) || TRACKED.containsKey(p) || p.tickCount > 1) {
			return;
		}
		if (!(p.getOwner() instanceof Player owner) || !(GiantManager.get(owner) instanceof Giant g) || g.scale <= 1.5) {
			return;
		}
		float scale = (float) g.scale;
		setScale(p, scale);
		p.refreshDimensions();
		// Bigger, so a bit faster: keeps the arc looking like a throw at the giant's own size.
		p.setDeltaMovement(p.getDeltaMovement().scale(Math.min(2.0, 1.0 + 0.075 * scale)));
		p.needsSync = true;
		double kind = p instanceof ThrownTrident ? 2.0 : p instanceof AbstractArrow ? 1.0 : 0.4;
		double mass = 0.02 * kind * scale * scale * scale;
		TRACKED.put(p, new Tracked(p, scale, mass, owner.getUUID()));
	}

	public static void tick(ServerLevel level) {
		if (TRACKED.isEmpty()) {
			return;
		}
		PhysicsWorld world = PhysicsWorld.get(level);
		List<Projectile> done = new ArrayList<>();
		for (Tracked t : TRACKED.values()) {
			Projectile p = t.projectile;
			if (p.level() != level) {
				if (p.isRemoved()) {
					done.add(p);
				}
				continue;
			}
			Vec3 pos = p.position();
			Vec3 vel = p.getDeltaMovement().scale(20);
			double lastSpeed = t.lastVel.length();
			sweepCreatures(world, t, t.lastPos, p.isRemoved() ? t.lastPos.add(t.lastVel.scale(0.05)) : pos);
			// Impact: it vanished (snowball, trident hit...) or stopped dead (arrow stuck in a block).
			boolean impact = lastSpeed > 8 && (p.isRemoved() || vel.length() < lastSpeed * 0.3);
			if (impact) {
				double energy = 0.5 * t.mass * lastSpeed * lastSpeed;
				Vec3 at = p.isRemoved() ? t.lastPos.add(t.lastVel.scale(0.05)) : pos;
				if (dev.titanvr.TitanMod.DEV_TEST) {
					dev.titanvr.TitanMod.LOGGER.info("[autotest] giant projectile impact at {} speed={} energy={} hits={}", at, lastSpeed, energy, t.hit.size());
				}
				if (energy > 400) {
					world.destruction.queueShockwave(at.x, at.y, at.z, energy, t.owner);
				}
			}
			if (!p.isRemoved() && !impact) {
				carveAhead(world, level, t, p, pos, p.getDeltaMovement().scale(20));
			}
			t.lastPos = pos;
			t.lastVel = p.getDeltaMovement().scale(20);
			if (impact || p.isRemoved() || ++t.age > 400) {
				done.add(p);
			}
		}
		for (Projectile p : done) {
			TRACKED.remove(p);
		}
	}

	/**
	 * Punches through whatever lies on next tick's path, paying each block's break energy out of the projectile's
	 * kinetic energy - so it tears through leaves and planks, slows in stone, and stops when it runs out.
	 */
	private static void carveAhead(PhysicsWorld world, ServerLevel level, Tracked t, Projectile p, Vec3 pos, Vec3 vel) {
		double speed = vel.length();
		if (speed < 15) {
			return;
		}
		double ke = 0.5 * t.mass * speed * speed;
		double budget = ke * 0.7;
		double r = Math.max(0.5, Math.min(p.getBbWidth(), p.getBbHeight()) * 0.3);
		Vec3 dir = vel.scale(1.0 / speed);
		double len = speed * 0.05 + r;
		int steps = (int) Math.ceil(len / 0.5);
		double spent = 0;
		int broken = 0;
		it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		Vector3d debrisVel = new Vector3d(vel.x, vel.y, vel.z).mul(0.5);
		outer:
		for (int s = 0; s <= steps; s++) {
			Vec3 c = pos.add(dir.scale(len * s / steps));
			int x0 = (int) Math.floor(c.x - r), x1 = (int) Math.floor(c.x + r);
			int y0 = (int) Math.floor(c.y - r), y1 = (int) Math.floor(c.y + r);
			int z0 = (int) Math.floor(c.z - r), z1 = (int) Math.floor(c.z + r);
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						double dx = x + 0.5 - c.x, dy = y + 0.5 - c.y, dz = z + 0.5 - c.z;
						if (dx * dx + dy * dy + dz * dz > (r + 0.4) * (r + 0.4) || !world.collider.solid(x, y, z) || !seen.add(BlockPos.asLong(x, y, z))) {
							continue;
						}
						mp.set(x, y, z);
						BlockState state = world.collider.state(x, y, z);
						if (state == null || !Destruction.breakable(state, level, mp)) {
							break outer;
						}
						double cost = Destruction.breakEnergy(state, level, mp);
						if (spent + cost > budget) {
							break outer;
						}
						spent += cost;
						world.destruction.breakBlock(mp.immutable(), state, debrisVel, true, t.owner);
						if (++broken >= 80) {
							break outer;
						}
					}
				}
			}
		}
		if (spent > 0) {
			p.setDeltaMovement(p.getDeltaMovement().scale(Math.sqrt(Math.max(0.05, 1.0 - spent / ke))));
			p.needsSync = true;
		}
	}

	/** Everything the projectile's full (giant) width passes through gets hit, once. */
	private static void sweepCreatures(PhysicsWorld world, Tracked t, Vec3 from, Vec3 to) {
		Projectile p = t.projectile;
		double r = Math.max(p.getBbWidth(), p.getBbHeight()) * 0.5;
		AABB box = new AABB(from, to).inflate(r);
		for (Entity e : p.level().getEntities(p, box, e -> e instanceof LivingEntity && e.isAlive())) {
			LivingEntity le = (LivingEntity) e;
			if (le.getUUID().equals(t.owner) || (le instanceof Player pl && GiantManager.isGiant(pl)) || t.hit.contains(le.getId())) {
				continue;
			}
			Vec3 c = le.getBoundingBox().getCenter();
			if (distanceToSegment(c, from, to) > r + le.getBbWidth() * 0.5 + le.getBbHeight() * 0.25) {
				continue;
			}
			t.hit.add(le.getId());
			Vec3 ev = le.getDeltaMovement().scale(20);
			Vector3d rel = new Vector3d(t.lastVel.x - ev.x, t.lastVel.y - ev.y, t.lastVel.z - ev.z);
			double m = PhysicsWorld.entityMass(le);
			Vector3d dv = PhysicsWorld.collide(t.mass, m, rel);
			double energy = 0.5 * (t.mass * m / (t.mass + m)) * rel.lengthSquared();
			world.damage(le, (float) PhysicsWorld.impactDamage(dv.length(), energy), t.owner, true);
			world.shoveOrLaunch(le, dv, t.owner);
		}
	}

	private static double distanceToSegment(Vec3 c, Vec3 a, Vec3 b) {
		Vec3 ab = b.subtract(a);
		double len2 = ab.lengthSqr();
		double f = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, c.subtract(a).dot(ab) / len2));
		return c.distanceTo(a.add(ab.scale(f)));
	}
}
