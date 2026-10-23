package dev.titanvr;

import dev.titanvr.physics.PhysicsWorld;
import java.util.function.IntPredicate;
import dev.titanvr.giant.GiantManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Side-aware "is this entity currently a ragdoll?" check usable from common mixins. */
public final class RagdollHooks {
	/** Installed by the client entrypoint; consulted for client-side entities. */
	public static volatile IntPredicate clientCheck = id -> false;
	/** Installed by the client entrypoint: is this (client-side) player a giant? */
	public static volatile IntPredicate clientGiant = id -> false;

	private RagdollHooks() {
	}

	public static boolean isRagdolled(Entity entity) {
		if (entity.level().isClientSide()) {
			return clientCheck.test(entity.getId());
		}
		return PhysicsWorld.RAGDOLLED.contains(entity.getId());
	}

	public static boolean isGiant(Player player) {
		if (player.level().isClientSide()) {
			return clientGiant.test(player.getId());
		}
		return GiantManager.isGiant(player);
	}
}
