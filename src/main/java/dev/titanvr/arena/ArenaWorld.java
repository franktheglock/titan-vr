package dev.titanvr.arena;

import dev.titanvr.TitanConfig;
import dev.titanvr.TitanMod;
import dev.titanvr.compat.VivecraftCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

/**
 * The "Titan VR Arena" world type: a void world with the arena built at spawn. Everyone waits in the lobby, and a
 * game starts by itself whenever a VR player (the giant) and at least one other player are there.
 */
public final class ArenaWorld {
	public static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME, TitanMod.id("arena"));
	private static final int AUTO_START_TICKS = 30 * 20;

	private static int autoStartIn = -1;
	private static UUID lastGiant;

	private ArenaWorld() {
	}

	public static boolean isArenaWorld(MinecraftServer server) {
		return server.overworld().getChunkSource().getGenerator() instanceof FlatLevelSource flat && flat.settings().getBiome().is(BIOME);
	}

	/** First start of an arena world: build the arena and make the world a calm, sunny place to fight in. */
	public static void onServerStarted(MinecraftServer server) {
		autoStartIn = -1;
		if (Arena.get() != null || !isArenaWorld(server)) {
			return;
		}
		ServerLevel level = server.overworld();
		long start = System.currentTimeMillis();
		Arena arena = Arena.create(server, level, new BlockPos(0, 64, 0), TitanConfig.INSTANCE.arenaRadius, true);
		arena.buildNow(level);
		GameRules rules = level.getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		rules.set(GameRules.IMMEDIATE_RESPAWN, true, server);
		rules.set(GameRules.RESPAWN_RADIUS, 0, server);
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
		Vec3 lobby = arena.lobbySpawn(0);
		level.setRespawnData(LevelData.RespawnData.of(level.dimension(), BlockPos.containing(lobby), 0, 0));
		TitanMod.LOGGER.info("Built the arena world's arena in {} ms", System.currentTimeMillis() - start);
	}

	public static boolean isDedicated() {
		Arena a = Arena.get();
		return a != null && a.dedicated;
	}

	/** Players arriving in an arena world start in the lobby (and in adventure mode, so the lobby stays in one piece). */
	public static void onJoin(ServerPlayer p) {
		if (!isDedicated()) {
			return;
		}
		if (ArenaGame.get() != null) {
			if (!ArenaGame.isParticipant(p)) {
				ArenaGame.join(p);
			}
			return;
		}
		toLobby(p);
		p.sendSystemMessage(Component.literal("[Titan VR] ").withStyle(ChatFormatting.GOLD).append(Component.literal(
			"Welcome to the arena! A game starts once a VR player (the giant) and at least one hunter are here.").withStyle(ChatFormatting.WHITE)));
	}

	private static void toLobby(ServerPlayer p) {
		Arena arena = Arena.get();
		ServerLevel level = arena.level(p.level().getServer());
		Vec3 s = arena.lobbySpawn(p.getRandom().nextInt(12));
		p.teleportTo(level, s.x, s.y, s.z, Set.of(), p.getYRot(), 0, false);
		p.resetFallDistance();
		if (p.gameMode() == GameType.SURVIVAL) {
			p.setGameMode(GameType.ADVENTURE);
		}
	}

	/** Between games nobody in an arena world gets hurt (e.g. jumping off the lobby to look around). */
	public static boolean allowIdleDamage(Entity entity) {
		return !(isDedicated() && ArenaGame.get() == null && entity instanceof ServerPlayer);
	}

	/** Between games: count down to the next one while there's a giant and someone to hunt them. */
	static void tickIdle(MinecraftServer server, Arena arena, ServerLevel level) {
		if (!arena.dedicated || !TitanConfig.INSTANCE.arenaAutoStart) {
			return;
		}
		List<ServerPlayer> players = new ArrayList<>();
		List<ServerPlayer> vr = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.level() == level) {
				players.add(p);
				if (VivecraftCompat.isVR(p)) {
					vr.add(p);
				}
			}
		}
		if (vr.isEmpty() || players.size() < 2) {
			if (autoStartIn >= 0) {
				broadcast(players, Component.literal("Not enough players any more; waiting for a VR giant and a hunter.").withStyle(ChatFormatting.GRAY));
			}
			autoStartIn = -1;
			return;
		}
		if (autoStartIn < 0) {
			autoStartIn = AUTO_START_TICKS;
		}
		if (autoStartIn % 200 == 0 || (autoStartIn <= 100 && autoStartIn % 20 == 0)) {
			if (autoStartIn > 0) {
				broadcast(players, Component.literal("Next game in " + autoStartIn / 20 + "s").withStyle(ChatFormatting.YELLOW));
			}
		}
		if (autoStartIn-- > 0 || arena.isRegenerating()) {
			return;
		}
		autoStartIn = -1;
		// Take turns being the giant when there's more than one VR player
		ServerPlayer giant = vr.get(0);
		for (ServerPlayer p : vr) {
			if (!p.getUUID().equals(lastGiant)) {
				giant = p;
				break;
			}
		}
		lastGiant = giant.getUUID();
		players.remove(giant);
		String err = ArenaGame.start(server, giant, players, TitanConfig.INSTANCE.arenaLives);
		if (err != null) {
			TitanMod.LOGGER.warn("Arena auto-start failed: {}", err);
		}
	}

	private static void broadcast(List<ServerPlayer> players, Component msg) {
		for (ServerPlayer p : players) {
			p.sendSystemMessage(msg);
		}
	}
}
