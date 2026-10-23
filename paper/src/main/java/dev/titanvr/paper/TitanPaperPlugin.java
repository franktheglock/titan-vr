package dev.titanvr.paper;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.titanvr.TitanConfig;
import dev.titanvr.TitanEvents;
import dev.titanvr.TitanMod;
import dev.titanvr.arena.Arena;
import dev.titanvr.arena.ArenaGame;
import dev.titanvr.arena.ArenaWorld;
import dev.titanvr.command.TitanCommands;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.GiantProjectiles;
import dev.titanvr.physics.PhysicsWorld;
import dev.titanvr.platform.Platform;
import io.netty.buffer.Unpooled;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Paper plugin: the same server code as the Fabric mod, wired to Paper's events. Players still need the Titan VR
 * Fabric mod (and Vivecraft for VR); the server needs the Vivecraft Spigot Extension plugin for VR poses.
 */
public class TitanPaperPlugin extends JavaPlugin implements Listener {
	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void onEnable() {
		Platform.set(new PaperPlatform(this));
		TitanConfig.load();
		for (CustomPacketPayload.Type<?> type : PaperPlatform.CLIENTBOUND.keySet()) {
			getServer().getMessenger().registerOutgoingPluginChannel(this, type.id().toString());
		}
		listen(TitanNet.GrabC2S.TYPE, TitanNet.GrabC2S.CODEC, TitanEvents::onGrab);
		listen(TitanNet.ResizeC2S.TYPE, TitanNet.ResizeC2S.CODEC, TitanEvents::onResize);
		getServer().getPluginManager().registerEvents(this, this);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
			event.registrar().register((LiteralCommandNode) TitanCommands.build().build(), "Titan VR: giants, debris and the arena"));
		TitanMod.LOGGER.info("Titan VR loaded on Paper (Vivecraft {})",
			getServer().getPluginManager().isPluginEnabled("Vivecraft-Spigot-Extension") ? "found" : "missing - desktop giants only");
	}

	@Override
	public void onDisable() {
		ArenaGame.stop();
		PhysicsWorld.clearAll();
	}

	private <T extends CustomPacketPayload> void listen(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec,
		BiConsumer<ServerPlayer, T> handler) {
		getServer().getMessenger().registerIncomingPluginChannel(this, type.id().toString(), (channel, player, bytes) -> {
			RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), MinecraftServer.getServer().registryAccess());
			T payload;
			try {
				payload = codec.decode(buf);
			} catch (Exception e) {
				return;
			}
			ServerPlayer sp = ((CraftPlayer) player).getHandle();
			// Plugin messages are handled on the main thread
			handler.accept(sp, payload);
		});
	}

	private static ServerPlayer handle(org.bukkit.entity.Player p) {
		return ((CraftPlayer) p).getHandle();
	}

	// ------------------------------------------------------------------ ticking

	@EventHandler
	public void onTickStart(ServerTickStartEvent event) {
		GiantManager.tick(MinecraftServer.getServer());
	}

	@EventHandler
	public void onTickEnd(ServerTickEndEvent event) {
		MinecraftServer server = MinecraftServer.getServer();
		for (ServerLevel level : server.getAllLevels()) {
			PhysicsWorld.tickLevel(level);
			GiantProjectiles.tick(level);
		}
		ArenaGame.tickServer(server);
		// Giants wade through the world. (On Fabric a mixin does this inside the player tick; here it holds from the
		// end of one tick through the movement packets handled before the next.)
		for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
			if (GiantManager.isGiant(sp) && sp.getAbilities().flying) {
				sp.noPhysics = true;
			}
		}
	}

	@EventHandler
	public void onServerLoad(ServerLoadEvent event) {
		MinecraftServer server = MinecraftServer.getServer();
		Arena.load(server);
		ArenaWorld.onServerStarted(server);
	}

	@EventHandler
	public void onWorldUnload(WorldUnloadEvent event) {
		PhysicsWorld.unload(((CraftWorld) event.getWorld()).getHandle());
	}

	// ------------------------------------------------------------------ entities

	@EventHandler
	public void onEntityAdd(EntityAddToWorldEvent event) {
		net.minecraft.world.entity.Entity e = ((CraftEntity) event.getEntity()).getHandle();
		if (e.level() instanceof ServerLevel level) {
			GiantProjectiles.onLoad(e, level);
		}
	}

	@EventHandler
	public void onTrack(PlayerTrackEntityEvent event) {
		TitanEvents.onStartTracking(((CraftEntity) event.getEntity()).getHandle(), handle(event.getPlayer()));
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		DamageSource source = ((CraftDamageSource) event.getDamageSource()).getHandle();
		if (!TitanEvents.allowDamage(((CraftEntity) event.getEntity()).getHandle(), source)) {
			event.setCancelled(true);
		}
	}

	// ------------------------------------------------------------------ players

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		ServerPlayer sp = handle(event.getPlayer());
		GiantManager.syncTo(sp);
		ArenaWorld.onJoin(sp);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		ServerPlayer sp = handle(event.getPlayer());
		GiantManager.onDisconnect(sp);
		ArenaGame.onDisconnect(sp);
	}

	@EventHandler
	public void onRespawn(PlayerPostRespawnEvent event) {
		ServerPlayer sp = handle(event.getPlayer());
		GiantManager.onRespawn(sp);
		ArenaGame.onRespawn(sp);
	}

	@EventHandler
	public void onDeath(PlayerDeathEvent event) {
		ServerPlayer sp = handle(event.getPlayer());
		boolean participant = ArenaGame.isParticipant(sp);
		ArenaGame.onDeath(sp, ((CraftDamageSource) event.getDamageSource()).getHandle());
		if (participant) {
			// Arena kits never drop
			event.getDrops().clear();
		}
	}

	/** While ragdolled the server drives the player's position; ignore what the client says. */
	@EventHandler(ignoreCancelled = true)
	public void onMove(PlayerMoveEvent event) {
		if (PhysicsWorld.RAGDOLLED.contains(handle(event.getPlayer()).getId())) {
			event.setCancelled(true);
		}
	}

	/** Neither giants (always hovering) nor airborne ragdolls get kicked for flying. */
	@EventHandler(ignoreCancelled = true)
	public void onKick(PlayerKickEvent event) {
		if (event.getCause() != PlayerKickEvent.Cause.FLYING_PLAYER) {
			return;
		}
		ServerPlayer sp = handle(event.getPlayer());
		if (GiantManager.isGiant(sp) || PhysicsWorld.RAGDOLLED.contains(sp.getId())) {
			event.setCancelled(true);
		}
	}
}
