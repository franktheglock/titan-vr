package dev.titanvr.platform;

import dev.titanvr.physics.RigidBody;
import java.nio.file.Path;
import java.util.Collection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * What the shared server code needs from the loader it runs on: the Fabric mod or the Paper plugin. Installed once by
 * the entrypoint before anything else runs.
 */
public interface Platform {
	static Platform get() {
		return Holder.instance;
	}

	static void set(Platform platform) {
		Holder.instance = platform;
	}

	Path configDir();

	void send(ServerPlayer player, CustomPacketPayload payload);

	/** Players whose clients have this entity loaded. */
	Collection<ServerPlayer> tracking(Entity entity);

	/**
	 * Spawns the entity that carries a debris body to clients (vanilla tracking decides who sees it); its id is what the
	 * body packets refer to.
	 */
	Entity spawnBodyCarrier(ServerLevel level, RigidBody body);

	VrBridge vr();

	final class Holder {
		private static Platform instance;

		private Holder() {
		}
	}
}
