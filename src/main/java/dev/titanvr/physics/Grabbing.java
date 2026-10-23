package dev.titanvr.physics;

import dev.titanvr.TitanConfig;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.giant.HandState;
import dev.titanvr.net.TitanNet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.titanvr.platform.Platform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Grabbing, holding and throwing for giants. Server authoritative. */
public final class Grabbing {
	private final PhysicsWorld world;

	/** A non-living entity (item, TNT, boat, arrow...) carried in a hand. */
	public static final class Carried {
		public final Entity entity;
		public final UUID giant;
		public final int hand;
		public final Vector3d localPos = new Vector3d();

		Carried(Entity entity, UUID giant, int hand) {
			this.entity = entity;
			this.giant = giant;
			this.hand = hand;
		}
	}

	Grabbing(PhysicsWorld world) {
		this.world = world;
	}

	// ------------------------------------------------------------------ grab

	public void grab(ServerPlayer player, Giant g, int handIdx) {
		HandState hs = g.hands[handIdx];
		hs.gripping = true;
		if (hs.held != null || !hs.valid) {
			return;
		}
		TitanConfig cfg = TitanConfig.INSTANCE;
		Vector3d hp = new Vector3d(hs.pos);
		double reach = Math.max(0.9, cfg.grabRadiusPerScale * g.scale);

		Object best = null;
		double bestDist = Double.MAX_VALUE;

		// Living things (players, mobs), including ones already flopping around as ragdolls.
		AABB area = new AABB(hp.x - reach, hp.y - reach, hp.z - reach, hp.x + reach, hp.y + reach, hp.z + reach);
		for (Entity e : this.world.level.getEntities(player, area, e -> this.world.bodyByEntity(e) == null && e.isAlive())) {
			double d;
			Ragdoll hr = this.world.ragdolls.get(e.getId());
			if (hr != null) {
				int i = hr.nearestParticle(hp.x, hp.y, hp.z);
				d = hr.particle(i, new Vector3d()).distance(hp) - hr.radius[i];
			} else {
				d = distanceToBox(e.getBoundingBox(), hp);
			}
			if (e instanceof Player p && GiantManager.isGiant(p)) {
				Giant other = GiantManager.get(p);
				if (other.scale > g.scale * 0.5) {
					continue; // can't pick up someone your own size
				}
			}
			if (e instanceof LivingEntity le && !PhysicsWorld.canRagdoll(le) && !this.world.isRagdolled(le)) {
				continue;
			}
			// Non-living entities are fine too (items, TNT, minecarts...), but prefer living targets slightly.
			if (!(e instanceof LivingEntity)) {
				d += 0.15;
			}
			if (d < reach * 0.55 && d < bestDist) {
				bestDist = d;
				best = e;
			}
		}

		// Loose debris and rigidly ragdolled mobs.
		double[] out = new double[4];
		Vector3d l = new Vector3d();
		for (RigidBody b : this.world.bodies()) {
			if (b.removed) {
				continue;
			}
			double rr = b.shape.radius + reach;
			if (b.pos.distanceSquared(hp) > rr * rr) {
				continue;
			}
			b.worldToLocal(hp.x, hp.y, hp.z, l);
			if (b.shape.spherePenetration(l.x, l.y, l.z, reach * 0.6, out)) {
				double d = Math.max(0, reach * 0.6 - out[3]);
				if (d < bestDist) {
					bestDist = d;
					best = b.proxy != null ? b.proxy : b;
				}
			}
		}

		boolean grabbed = false;
		if (best instanceof RigidBody b) {
			grabbed = this.holdBody(player, g, handIdx, b, TitanNet.HOLD_BODY, b.entity.getId());
		} else if (best instanceof LivingEntity le) {
			Object r = this.world.ragdoll(le);
			if (r instanceof Ragdoll hr) {
				grabbed = this.holdRagdoll(player, g, handIdx, hr);
			} else if (r instanceof RigidBody rb) {
				grabbed = this.holdBody(player, g, handIdx, rb, TitanNet.HOLD_RIGID_RAGDOLL, le.getId());
			}
		} else if (best instanceof Entity e) {
			grabbed = this.holdEntity(player, g, handIdx, e);
		}
		if (!grabbed) {
			// Rip a chunk out of the world.
			double rr = Math.max(0.8, cfg.ripRadiusPerScale * g.scale);
			double region = Math.max(8, Math.min(22, g.scale * 1.8 + 4));
			int maxVoxels = (int) Math.max(40, cfg.maxGrabVoxels * (g.scale / 8.0) * (g.scale / 8.0));
			RigidBody chunk = this.world.destruction.rip(hp, rr, region, maxVoxels, 3.0 * g.scale * cfg.destructionMultiplier, g.uuid);
			if (chunk != null) {
				grabbed = this.holdBody(player, g, handIdx, chunk, TitanNet.HOLD_BODY, chunk.entity.getId());
			}
		}
		if (grabbed) {
			PhysicsWorld.haptic(player, handIdx, 0.12F);
		}
	}

	static double distanceToBox(AABB box, Vector3d p) {
		double dx = Math.max(0, Math.max(box.minX - p.x, p.x - box.maxX));
		double dy = Math.max(0, Math.max(box.minY - p.y, p.y - box.maxY));
		double dz = Math.max(0, Math.max(box.minZ - p.z, p.z - box.maxZ));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private boolean holdBody(ServerPlayer player, Giant g, int handIdx, RigidBody b, byte kind, int entityId) {
		this.forgetHeld(b);
		HandState hs = g.hands[handIdx];
		b.wake();
		RigidBody.Hold hold = new RigidBody.Hold(g.uuid, handIdx);
		Quaterniond inv = new Quaterniond(hs.rot).conjugate();
		hold.localPos.set(b.pos).sub(hs.pos);
		inv.transform(hold.localPos);
		hold.localRot.set(inv).mul(b.rot);
		b.hold = hold;
		b.massScale = 6.0;
		b.updateMass();
		b.updateDerived();
		hs.held = b;
		this.sendHold(player, entityId, handIdx, true, kind, 0, hold.localPos, hold.localRot);
		return true;
	}

	private boolean holdRagdoll(ServerPlayer player, Giant g, int handIdx, Ragdoll r) {
		this.forgetHeld(r);
		HandState hs = g.hands[handIdx];
		int i = r.nearestParticle(hs.pos.x, hs.pos.y, hs.pos.z);
		RigidBody.Hold hold = new RigidBody.Hold(g.uuid, handIdx);
		Quaterniond inv = new Quaterniond(hs.rot).conjugate();
		Vector3d p = r.particle(i, new Vector3d());
		// Snap the grabbed limb most of the way into the palm so it reads as "grabbed".
		Vector3d offset = new Vector3d(p).sub(hs.pos).mul(0.35);
		hold.localPos.set(offset);
		inv.transform(hold.localPos);
		r.pinned = i;
		r.hold = hold;
		r.pinFrom.set(p);
		r.pinTo.set(hs.pos).add(offset);
		hs.held = r;
		this.sendHold(player, r.entity.getId(), handIdx, true, TitanNet.HOLD_RAGDOLL, i, hold.localPos, new Quaterniond());
		this.world.level.playSound(null, p.x, p.y, p.z, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 0.8F, 0.6F);
		return true;
	}

	private boolean holdEntity(ServerPlayer player, Giant g, int handIdx, Entity e) {
		this.forgetHeld(e);
		HandState hs = g.hands[handIdx];
		if (e.isPassenger()) {
			e.stopRiding();
		}
		Carried c = new Carried(e, g.uuid, handIdx);
		Vector3d center = new Vector3d(e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ());
		c.localPos.set(center).sub(hs.pos).mul(0.5);
		new Quaterniond(hs.rot).conjugate().transform(c.localPos);
		hs.held = c;
		this.sendHold(player, e.getId(), handIdx, true, TitanNet.HOLD_ENTITY, 0, c.localPos, new Quaterniond());
		return true;
	}

	private void sendHold(ServerPlayer player, int entityId, int hand, boolean holding, byte kind, int particle, Vector3d lp, Quaterniond lr) {
		float[] local = {(float) lp.x, (float) lp.y, (float) lp.z, (float) lr.x, (float) lr.y, (float) lr.z, (float) lr.w};
		Platform.get().send(player, new TitanNet.HoldS2C(entityId, (byte) hand, holding, kind, particle, local));
	}

	// ------------------------------------------------------------------ release / throw

	public void release(ServerPlayer player, Giant g, int handIdx) {
		this.release(player, g, handIdx, null, null);
	}

	/**
	 * Lets go of whatever the hand holds. clientVel/clientSpin are the hand's linear and angular velocity measured
	 * by the client at frame rate over the end of the swing (far more accurate than the server's 20 Hz samples);
	 * the held object's centre then gets v_hand + omega x r, so long objects whip off the end of the swing.
	 */
	public void release(ServerPlayer player, Giant g, int handIdx, Vector3d clientVel, Vector3d clientSpin) {
		HandState hs = g.hands[handIdx];
		hs.gripping = false;
		Object held = hs.held;
		if (held == null) {
			return;
		}
		hs.held = null;
		TitanConfig cfg = TitanConfig.INSTANCE;
		Vector3d handVel;
		Vector3d spin = new Vector3d();
		if (clientVel != null && Double.isFinite(clientVel.lengthSquared())) {
			handVel = new Vector3d(clientVel);
			// Sanity limit relative to what the server saw (guards against bogus packets).
			double limit = Math.max(40.0, hs.throwVelocity().length() * 3.0 + 25.0);
			if (handVel.length() > limit) {
				handVel.normalize(limit);
			}
			if (clientSpin != null && Double.isFinite(clientSpin.lengthSquared())) {
				spin.set(clientSpin);
				if (spin.length() > 40.0) {
					spin.normalize(40.0);
				}
			}
		} else {
			handVel = hs.throwVelocity();
		}
		if (!g.vr) {
			Vec3 look = player.getLookAngle();
			handVel.add(look.x * cfg.desktopThrowSpeed, look.y * cfg.desktopThrowSpeed + 2.0, look.z * cfg.desktopThrowSpeed);
		}
		int entityId = -1;
		double releaseSpeed = 0;
		if (held instanceof RigidBody b) {
			Vector3d r = b.hold != null ? hs.rot.transform(new Vector3d(b.hold.localPos)) : new Vector3d(b.pos).sub(hs.pos);
			Vector3d v = new Vector3d(spin).cross(r).add(handVel).mul(cfg.throwMultiplier);
			b.hold = null;
			b.massScale = 1.0;
			b.updateMass();
			b.updateDerived();
			b.vel.set(v);
			b.angVel.set(spin);
			b.thrower = g.uuid;
			b.thrownAge = b.age;
			b.wake();
			releaseSpeed = v.length();
			entityId = b.proxy != null ? b.proxy.getId() : b.entity != null ? b.entity.getId() : -1;
		} else if (held instanceof Ragdoll rd) {
			Vector3d c = rd.center(new Vector3d()).sub(hs.pos);
			Vector3d v = new Vector3d(spin).cross(c).add(handVel).mul(cfg.throwMultiplier);
			rd.pinned = -1;
			rd.hold = null;
			for (int i = 0; i < rd.rig.n; i++) {
				int o = i * 3;
				rd.v[o] += (v.x - rd.v[o]) * 0.8;
				rd.v[o + 1] += (v.y - rd.v[o + 1]) * 0.8;
				rd.v[o + 2] += (v.z - rd.v[o + 2]) * 0.8;
			}
			rd.addSpin(spin.x * 0.5, spin.y * 0.5, spin.z * 0.5);
			rd.thrower = g.uuid;
			rd.thrownAge = rd.age;
			rd.stillTicks = 0;
			releaseSpeed = v.length();
			entityId = rd.entity.getId();
		} else if (held instanceof Carried c) {
			Vector3d r = hs.rot.transform(new Vector3d(c.localPos));
			Vector3d v = new Vector3d(spin).cross(r).add(handVel).mul(cfg.throwMultiplier);
			c.entity.setDeltaMovement(v.x / 20.0, v.y / 20.0, v.z / 20.0);
			c.entity.hurtMarked = true;
			c.entity.resetFallDistance();
			releaseSpeed = v.length();
			entityId = c.entity.getId();
		}
		if (releaseSpeed > 10 && entityId >= 0) {
			Vector3d p = hs.pos;
			this.world.level.playSound(null, p.x, p.y, p.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.5F);
		}
		if (entityId >= 0) {
			this.sendHold(player, entityId, handIdx, false, (byte) 0, 0, new Vector3d(), new Quaterniond());
		}
	}

	// ------------------------------------------------------------------ per tick

	void tickHolds(List<Giant> giants, Map<Giant, ServerPlayer> players) {
		for (Giant g : giants) {
			ServerPlayer player = players.get(g);
			for (int handIdx = 0; handIdx < 2; handIdx++) {
				HandState hs = g.hands[handIdx];
				Object held = hs.held;
				if (held == null) {
					continue;
				}
				if (held instanceof RigidBody b && b.removed) {
					hs.held = null;
				} else if (held instanceof Ragdoll r) {
					if (r.hold == null || r.entity.isRemoved()) {
						hs.held = null;
						continue;
					}
					Vector3d target = new Vector3d(r.hold.localPos);
					hs.rot.transform(target).add(hs.pos);
					r.pinFrom.set(r.pinTo);
					r.pinTo.set(target);
				} else if (held instanceof Carried c) {
					if (c.entity.isRemoved()) {
						hs.held = null;
						continue;
					}
					Vector3d target = new Vector3d(c.localPos);
					hs.rot.transform(target).add(hs.pos);
					c.entity.setPos(target.x, target.y - c.entity.getBbHeight() * 0.5, target.z);
					c.entity.setDeltaMovement(hs.vel.x / 20.0, hs.vel.y / 20.0, hs.vel.z / 20.0);
					c.entity.resetFallDistance();
					c.entity.hurtMarked = true;
				}
				if (!hs.gripping && player != null) {
					this.release(player, g, handIdx);
				}
			}
		}
	}

	/** Steers a held body towards the hand pose (velocity control, so it still collides). */
	void driveHold(RigidBody b, double t, double h) {
		Giant g = GiantManager.get(b.hold.giant);
		if (g == null) {
			b.hold = null;
			b.massScale = 1.0;
			b.updateMass();
			return;
		}
		HandState hs = g.hands[b.hold.hand];
		Vector3d hp = new Vector3d(hs.prevPos).lerp(hs.pos, t);
		Quaterniond hr = new Quaterniond(hs.prevRot).slerp(hs.rot, t);
		Vector3d target = new Vector3d(b.hold.localPos);
		hr.transform(target).add(hp);
		Quaterniond targetRot = new Quaterniond(hr).mul(b.hold.localRot);
		double k = 2.0;
		Vector3d v = target.sub(b.pos).div(h * k);
		double max = 180;
		if (v.lengthSquared() > max * max) {
			v.normalize(max);
		}
		b.vel.set(v);
		Quaterniond err = new Quaterniond(targetRot).mul(new Quaterniond(b.rot).conjugate());
		if (err.w < 0) {
			err.set(-err.x, -err.y, -err.z, -err.w);
		}
		double sinHalf = Math.sqrt(err.x * err.x + err.y * err.y + err.z * err.z);
		if (sinHalf > 1e-7) {
			double angle = 2 * Math.atan2(sinHalf, err.w);
			double wmag = Math.min(60, angle / (h * k));
			b.angVel.set(err.x / sinHalf * wmag, err.y / sinHalf * wmag, err.z / sinHalf * wmag);
		} else {
			b.angVel.zero();
		}
	}

	/** Drops anything (body/ragdoll/entity) from whichever hand holds it. */
	void forgetHeld(Object o) {
		for (Giant g : GiantManager.all()) {
			for (int handIdx = 0; handIdx < 2; handIdx++) {
				HandState hs = g.hands[handIdx];
				Object held = hs.held;
				boolean match = held == o || held instanceof Carried c && c.entity == o;
				if (!match) {
					continue;
				}
				hs.held = null;
				if (this.world.level.getPlayerByUUID(g.uuid) instanceof ServerPlayer sp) {
					int id = o instanceof RigidBody b ? (b.proxy != null ? b.proxy.getId() : b.entity != null ? b.entity.getId() : -1)
						: o instanceof Ragdoll r ? r.entity.getId() : o instanceof Entity e ? e.getId() : -1;
					if (id >= 0) {
						this.sendHold(sp, id, handIdx, false, (byte) 0, 0, new Vector3d(), new Quaterniond());
					}
				}
			}
		}
		if (o instanceof RigidBody b) {
			b.hold = null;
			b.massScale = 1.0;
			b.updateMass();
		} else if (o instanceof Ragdoll r) {
			r.hold = null;
			r.pinned = -1;
		}
	}

	/** When a held chunk shatters, keep holding the piece closest to the palm. */
	void rebindAfterFracture(RigidBody.Hold hold, List<RigidBody> pieces) {
		Giant g = GiantManager.get(hold.giant);
		if (g == null || pieces.isEmpty() || !(this.world.level.getPlayerByUUID(hold.giant) instanceof ServerPlayer sp)) {
			return;
		}
		HandState hs = g.hands[hold.hand];
		RigidBody best = null;
		double bestD = Double.MAX_VALUE;
		for (RigidBody p : pieces) {
			double d = p.pos.distanceSquared(hs.pos);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		if (best != null && best.entity != null && hs.gripping) {
			this.holdBody(sp, g, hold.hand, best, TitanNet.HOLD_BODY, best.entity.getId());
		}
	}
}
