package dev.titanvr.fabric;

import dev.titanvr.AutoTest;
import dev.titanvr.TitanConfig;
import dev.titanvr.TitanEvents;
import dev.titanvr.TitanMod;
import dev.titanvr.arena.Arena;
import dev.titanvr.arena.ArenaGame;
import dev.titanvr.arena.ArenaWorld;
import dev.titanvr.command.TitanCommands;
import dev.titanvr.compat.VivecraftCompat;
import dev.titanvr.entity.TitanEntities;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.GiantProjectiles;
import dev.titanvr.physics.PhysicsWorld;
import dev.titanvr.platform.Platform;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Fabric entrypoint (both sides): wires the shared code to Fabric's events and networking. */
public class FabricMod implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.set(new FabricPlatform());
		TitanConfig.load();
		TitanEntities.register();
		registerPayloads();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(TitanCommands.build()));

		if (AutoTest.ENABLED) {
			ServerTickEvents.START_SERVER_TICK.register(AutoTest::serverTick);
		}
		ServerTickEvents.START_SERVER_TICK.register(GiantManager::tick);
		ServerTickEvents.END_LEVEL_TICK.register(PhysicsWorld::tickLevel);
		ServerTickEvents.END_LEVEL_TICK.register(GiantProjectiles::tick);
		ServerEntityEvents.ENTITY_LOAD.register(GiantProjectiles::onLoad);
		ServerLevelEvents.UNLOAD.register((server, level) -> PhysicsWorld.unload(level));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PhysicsWorld.clearAll());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			GiantManager.syncTo(handler.player);
			ArenaWorld.onJoin(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			GiantManager.onDisconnect(handler.player);
			ArenaGame.onDisconnect(handler.player);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			GiantManager.onRespawn(newPlayer);
			ArenaGame.onRespawn(newPlayer);
		});
		ServerPlayerEvents.ALLOW_DEATH.register((player, source, amount) -> {
			ArenaGame.onDeath(player, source);
			return true;
		});
		ServerTickEvents.END_SERVER_TICK.register(ArenaGame::tickServer);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Arena.load(server);
			ArenaWorld.onServerStarted(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ArenaGame.stop());

		EntityTrackingEvents.START_TRACKING.register(TitanEvents::onStartTracking);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> TitanEvents.allowDamage(entity, source));

		ServerPlayNetworking.registerGlobalReceiver(TitanNet.GrabC2S.TYPE, (payload, context) ->
			context.server().execute(() -> TitanEvents.onGrab(context.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(TitanNet.ResizeC2S.TYPE, (payload, context) ->
			context.server().execute(() -> TitanEvents.onResize(context.player(), payload)));
		TitanMod.LOGGER.info("Titan VR loaded (Vivecraft {})", VivecraftCompat.LOADED ? "found" : "missing - desktop giants only");
	}

	private static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(TitanNet.GrabC2S.TYPE, TitanNet.GrabC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TitanNet.ResizeC2S.TYPE, TitanNet.ResizeC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TitanNet.GiantStateS2C.TYPE, TitanNet.GiantStateS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TitanNet.BodyShapeS2C.TYPE, TitanNet.BodyShapeS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TitanNet.BodyStatesS2C.TYPE, TitanNet.BodyStatesS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TitanNet.RagdollStatesS2C.TYPE, TitanNet.RagdollStatesS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TitanNet.HoldS2C.TYPE, TitanNet.HoldS2C.CODEC);
	}
}
