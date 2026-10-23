package dev.titanvr;

import dev.titanvr.arena.ArenaGame;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.PhysicsWorld;
import dev.titanvr.physics.RigidBody;
import dev.titanvr.platform.Platform;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Event handlers shared by the Fabric mod and the Paper plugin; each platform wires them to its own events. */
public final class TitanEvents {
	private TitanEvents() {
	}

	public static boolean allowDamage(Entity entity, DamageSource source) {
		if (!ArenaGame.allowDamage(entity, source)) {
			return false;
		}
		boolean ragdolled = PhysicsWorld.RAGDOLLED.contains(entity.getId());
		if (ragdolled && (source.is(DamageTypes.IN_WALL) || source.is(DamageTypeTags.IS_FALL))) {
			return false;
		}
		if (entity instanceof Player p && GiantManager.isGiant(p)) {
			// Giants don't suffocate in the scenery they're wading through, or take fall damage.
			return !(source.is(DamageTypes.IN_WALL) || source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypes.FLY_INTO_WALL));
		}
		return true;
	}

	/** A player's client just started showing this entity. */
	public static void onStartTracking(Entity entity, ServerPlayer player) {
		PhysicsWorld world = PhysicsWorld.get(entity.level());
		RigidBody body = world.bodyByEntity(entity);
		if (body != null) {
			world.sendShape(body, player);
		} else if (entity instanceof Player p && GiantManager.isGiant(p)) {
			Giant g = GiantManager.get(p);
			Platform.get().send(player, new TitanNet.GiantStateS2C(p.getId(), true, (float) g.scale));
		}
	}

	/** Call on the server thread. */
	public static void onGrab(ServerPlayer player, TitanNet.GrabC2S payload) {
		Giant g = GiantManager.get(player);
		int hand = payload.hand() == 0 ? 0 : 1;
		if (g == null || !player.isAlive()) {
			return;
		}
		PhysicsWorld world = PhysicsWorld.get(player.level());
		if (payload.pressed()) {
			world.grabbing.grab(player, g, hand);
		} else if (payload.hasMotion()) {
			float[] m = payload.motion();
			world.grabbing.release(player, g, hand, new org.joml.Vector3d(m[0], m[1], m[2]), new org.joml.Vector3d(m[3], m[4], m[5]));
		} else {
			world.grabbing.release(player, g, hand);
		}
	}

	/** Call on the server thread. */
	public static void onResize(ServerPlayer player, TitanNet.ResizeC2S payload) {
		if (TitanConfig.INSTANCE.allowStretchResize && Float.isFinite(payload.scale())) {
			GiantManager.resize(player, payload.scale());
		}
	}
}
