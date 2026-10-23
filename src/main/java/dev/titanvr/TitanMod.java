package dev.titanvr;

import dev.titanvr.command.TitanCommands;
import dev.titanvr.entity.PhysicsBodyEntity;
import dev.titanvr.entity.TitanEntities;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.GiantProjectiles;
import dev.titanvr.physics.PhysicsWorld;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TitanMod implements ModInitializer {
	public static final String MOD_ID = "titanvr";
	public static final Logger LOGGER = LoggerFactory.getLogger("Titan VR");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		TitanConfig.load();
		TitanEntities.register();
		TitanNet.registerPayloads();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> TitanCommands.register(dispatcher));

		if (AutoTest.ENABLED) {
			ServerTickEvents.START_SERVER_TICK.register(AutoTest::serverTick);
		}
		ServerTickEvents.START_SERVER_TICK.register(GiantManager::tick);
		ServerTickEvents.END_LEVEL_TICK.register(PhysicsWorld::tickLevel);
		ServerTickEvents.END_LEVEL_TICK.register(GiantProjectiles::tick);
		ServerEntityEvents.ENTITY_LOAD.register(GiantProjectiles::onLoad);
		ServerLevelEvents.UNLOAD.register((server, level) -> PhysicsWorld.unload(level));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PhysicsWorld.clearAll());

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> GiantManager.syncTo(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> GiantManager.onDisconnect(handler.player));

		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof PhysicsBodyEntity pbe && pbe.body != null) {
				PhysicsWorld.get(entity.level()).sendShape(pbe, player);
			} else if (entity instanceof Player p && GiantManager.isGiant(p)) {
				Giant g = GiantManager.get(p);
				ServerPlayNetworking.send(player, new TitanNet.GiantStateS2C(p.getId(), true, (float) g.scale));
			}
		});

		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			boolean ragdolled = PhysicsWorld.RAGDOLLED.contains(entity.getId());
			if (ragdolled && (source.is(DamageTypes.IN_WALL) || source.is(DamageTypeTags.IS_FALL))) {
				return false;
			}
			if (entity instanceof Player p && GiantManager.isGiant(p)) {
				// Giants don't suffocate in the scenery they're wading through, or take fall damage.
				return !(source.is(DamageTypes.IN_WALL) || source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypes.FLY_INTO_WALL));
			}
			return true;
		});

		ServerPlayNetworking.registerGlobalReceiver(TitanNet.GrabC2S.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
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
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(TitanNet.ResizeC2S.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> {
				if (TitanConfig.INSTANCE.allowStretchResize && Float.isFinite(payload.scale())) {
					GiantManager.resize(player, payload.scale());
				}
			});
		});
		LOGGER.info("Titan VR loaded (Vivecraft {})", dev.titanvr.compat.VivecraftCompat.LOADED ? "found" : "missing - desktop giants only");
	}

	public static ServerLevel levelOf(Player p) {
		return (ServerLevel) p.level();
	}
}
