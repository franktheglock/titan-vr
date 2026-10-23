package dev.titanvr.fabric;

import dev.titanvr.compat.VivecraftCompat;
import dev.titanvr.entity.PhysicsBodyEntity;
import dev.titanvr.entity.TitanEntities;
import dev.titanvr.physics.RigidBody;
import dev.titanvr.platform.Platform;
import dev.titanvr.platform.VrBridge;
import java.nio.file.Path;
import java.util.Collection;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** The Fabric mod: our own debris entity type, Fabric networking and Vivecraft's Fabric API. */
public final class FabricPlatform implements Platform {
	private static final VrBridge VR = new VrBridge() {
		@Override
		public boolean isVR(Player player) {
			return VivecraftCompat.isVR(player);
		}

		@Override
		public boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut) {
			return VivecraftCompat.handPose(player, hand, posOut, rotOut);
		}

		@Override
		public boolean headPose(Player player, Vector3d posOut) {
			return VivecraftCompat.headPose(player, posOut);
		}

		@Override
		public void haptic(ServerPlayer player, int hand, float seconds) {
			VivecraftCompat.haptic(player, hand, seconds);
		}
	};

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public void send(ServerPlayer player, CustomPacketPayload payload) {
		ServerPlayNetworking.send(player, payload);
	}

	@Override
	public Collection<ServerPlayer> tracking(Entity entity) {
		return PlayerLookup.tracking(entity);
	}

	@Override
	public Entity spawnBodyCarrier(ServerLevel level, RigidBody body) {
		PhysicsBodyEntity entity = new PhysicsBodyEntity(TitanEntities.PHYSICS_BODY, level);
		entity.body = body;
		entity.radius = body.shape.radius;
		entity.setPos(body.pos.x, body.pos.y, body.pos.z);
		level.addFreshEntity(entity);
		return entity;
	}

	@Override
	public VrBridge vr() {
		return VR;
	}
}
