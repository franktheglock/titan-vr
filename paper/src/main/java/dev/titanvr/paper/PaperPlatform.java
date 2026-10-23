package dev.titanvr.paper;

import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.RigidBody;
import dev.titanvr.platform.Platform;
import dev.titanvr.platform.VrBridge;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Paper: packets go out as plugin messages (the Fabric client decodes them with the same codecs), and debris rides on
 * an invisible vanilla block display that the client mod swaps for its own body entity.
 */
final class PaperPlatform implements Platform {
	/** Clientbound payload codecs by channel. */
	static final Map<CustomPacketPayload.Type<?>, StreamCodec<RegistryFriendlyByteBuf, ?>> CLIENTBOUND = new HashMap<>();

	static {
		CLIENTBOUND.put(TitanNet.GiantStateS2C.TYPE, TitanNet.GiantStateS2C.CODEC);
		CLIENTBOUND.put(TitanNet.BodyShapeS2C.TYPE, TitanNet.BodyShapeS2C.CODEC);
		CLIENTBOUND.put(TitanNet.BodyStatesS2C.TYPE, TitanNet.BodyStatesS2C.CODEC);
		CLIENTBOUND.put(TitanNet.RagdollStatesS2C.TYPE, TitanNet.RagdollStatesS2C.CODEC);
		CLIENTBOUND.put(TitanNet.HoldS2C.TYPE, TitanNet.HoldS2C.CODEC);
	}

	private final TitanPaperPlugin plugin;
	private final VrBridge vr;

	PaperPlatform(TitanPaperPlugin plugin) {
		this.plugin = plugin;
		this.vr = Bukkit.getPluginManager().isPluginEnabled("Vivecraft-Spigot-Extension") ? new PaperVr() : VrBridge.NONE;
	}

	@Override
	public Path configDir() {
		return this.plugin.getDataFolder().toPath();
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void send(ServerPlayer player, CustomPacketPayload payload) {
		StreamCodec codec = CLIENTBOUND.get(payload.type());
		if (codec == null) {
			throw new IllegalArgumentException("Not a clientbound payload: " + payload.type());
		}
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), MinecraftServer.getServer().registryAccess());
		try {
			codec.encode(buf, payload);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			player.getBukkitEntity().sendPluginMessage(this.plugin, payload.type().id().toString(), bytes);
		} finally {
			buf.release();
		}
	}

	@Override
	public Collection<ServerPlayer> tracking(Entity entity) {
		List<ServerPlayer> list = new ArrayList<>();
		for (org.bukkit.entity.Player p : entity.getBukkitEntity().getTrackedBy()) {
			list.add(((CraftPlayer) p).getHandle());
		}
		return list;
	}

	@Override
	public Entity spawnBodyCarrier(ServerLevel level, RigidBody body) {
		// A block display showing air: invisible to anyone without the mod, and never saved
		Display.BlockDisplay carrier = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		carrier.setPos(body.pos.x, body.pos.y, body.pos.z);
		carrier.getBukkitEntity().setPersistent(false);
		level.addFreshEntity(carrier, CreatureSpawnEvent.SpawnReason.CUSTOM);
		return carrier;
	}

	@Override
	public VrBridge vr() {
		return this.vr;
	}
}
