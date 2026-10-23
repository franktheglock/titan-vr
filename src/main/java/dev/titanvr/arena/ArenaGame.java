package dev.titanvr.arena;

import dev.titanvr.TitanConfig;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.physics.PhysicsWorld;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * "Kill the giant": one giant against a team of hunters sharing a pool of lives. Hunters wait in the sky lobby, pick a
 * class and jump down into the arena; they respawn in the lobby while the team has lives left. The arena rebuilds
 * itself every few minutes. Everyone's inventory, game mode and position are put back when the game ends.
 */
public final class ArenaGame {
	public enum State { COUNTDOWN, RUNNING, ENDING }

	private static ArenaGame current;
	/** Dev tests only: players that aren't in the player list (fake players) but should count as online. */
	public static final Map<UUID, ServerPlayer> TEST_PLAYERS = new LinkedHashMap<>();
	/** Players who were dead when the game ended; they get their things back when they respawn. */
	private static final Map<UUID, Saved> PENDING_RESTORE = new LinkedHashMap<>();

	/** What a player had before the game, restored at the end. */
	private static final class Saved {
		final ItemStack[] items;
		final GameType mode;
		final ResourceKey<Level> dimension;
		final Vec3 pos;
		final float yRot, xRot;
		final boolean wasGiant;
		final double giantScale;

		Saved(ServerPlayer p) {
			this.items = new ItemStack[p.getInventory().getContainerSize()];
			for (int i = 0; i < this.items.length; i++) {
				this.items[i] = p.getInventory().getItem(i).copy();
			}
			this.mode = p.gameMode();
			this.dimension = p.level().dimension();
			this.pos = p.position();
			this.yRot = p.getYRot();
			this.xRot = p.getXRot();
			Giant g = GiantManager.get(p);
			this.wasGiant = g != null;
			this.giantScale = g != null ? g.scale : TitanConfig.INSTANCE.defaultGiantScale;
		}
	}

	private static final class Hunter {
		boolean out;
		HunterClass cls;
		boolean kitted;
		boolean inLobby = true;
		boolean dropping;
		int groundTicks;
		/** Respawned; goes to the lobby next tick (vanilla positions the player after the respawn event). */
		boolean respawned;
		int index;
	}

	private final MinecraftServer server;
	private final Arena arena;
	private final UUID giant;
	private final String giantName;
	private final Map<UUID, Hunter> hunters = new LinkedHashMap<>();
	private final Map<UUID, Saved> saved = new LinkedHashMap<>();
	private final ServerBossEvent bar;
	private State state = State.COUNTDOWN;
	/** The hunters' shared lives: each death spends one; dying with none left puts that hunter out. */
	private int lives;
	private boolean giantRespawned;
	private int ticks;
	private int endTicks;
	private Component result;

	private ArenaGame(MinecraftServer server, Arena arena, ServerPlayer giant) {
		this.server = server;
		this.arena = arena;
		this.giant = giant.getUUID();
		this.giantName = giant.getGameProfile().name();
		this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Giant " + this.giantName), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
	}

	public static ArenaGame get() {
		return current;
	}

	public static boolean isParticipant(Player p) {
		return current != null && (current.giant.equals(p.getUUID()) || current.hunters.containsKey(p.getUUID()));
	}

	public static boolean isHunter(Player p) {
		return current != null && current.hunters.containsKey(p.getUUID());
	}

	// ------------------------------------------------------------------ start / stop

	public static String start(MinecraftServer server, ServerPlayer giant, List<ServerPlayer> hunters, int lives) {
		Arena arena = Arena.get();
		if (arena == null) {
			return "There's no arena yet: stand where you want it and run /titan arena build";
		}
		if (current != null) {
			return "A game is already running (/titan game stop to end it)";
		}
		ServerLevel level = arena.level(server);
		if (level == null) {
			return "The arena's dimension isn't loaded";
		}
		ArenaGame g = new ArenaGame(server, arena, giant);
		g.lives = lives;
		current = g;
		arena.regenerate();
		g.saved.put(giant.getUUID(), new Saved(giant));
		g.setupGiant(giant, level);
		for (ServerPlayer p : hunters) {
			if (p != giant) {
				g.addHunter(p);
			}
		}
		g.broadcast(Component.literal("[Titan VR] ").withStyle(ChatFormatting.GOLD)
			.append(Component.literal("Kill the giant! " + g.giantName + " is the giant; " + g.hunters.size() + " hunters share " + lives + " lives.").withStyle(ChatFormatting.WHITE)));
		return null;
	}

	/** Adds a player to a running game as a hunter (they share the team's remaining lives). */
	public static String join(ServerPlayer p) {
		if (current == null) {
			return "No game is running";
		}
		if (isParticipant(p)) {
			return "You're already in the game";
		}
		current.addHunter(p);
		return null;
	}

	public static void stop() {
		if (current != null) {
			current.finish();
		}
	}

	private void setupGiant(ServerPlayer p, ServerLevel level) {
		p.setGameMode(GameType.SURVIVAL);
		p.getInventory().clearContent();
		p.removeAllEffects();
		GiantManager.setGiant(p, true, TitanConfig.INSTANCE.arenaGiantScale);
		p.setHealth(p.getMaxHealth());
		Vec3 c = this.arena.floorCenter();
		p.teleportTo(level, c.x, c.y + 1, c.z, Set.of(), p.getYRot(), 0, false);
		this.bar.addPlayer(p);
		title(p, Component.literal("You are the giant").withStyle(ChatFormatting.RED), Component.literal("Squash the hunters before they bring you down"));
	}

	private void addHunter(ServerPlayer p) {
		this.saved.put(p.getUUID(), new Saved(p));
		if (GiantManager.isGiant(p)) {
			GiantManager.setGiant(p, false, 1);
		}
		Hunter h = new Hunter();
		h.index = this.hunters.size();
		this.hunters.put(p.getUUID(), h);
		this.bar.addPlayer(p);
		toLobby(p, h);
		title(p, Component.literal("Kill the giant").withStyle(ChatFormatting.GOLD), Component.literal("Pick a class, then jump"));
	}

	/** Sends a hunter (back) to the lobby, healed, kitted with their class, or as a spectator if they're out. */
	private void toLobby(ServerPlayer p, Hunter h) {
		ServerLevel level = this.arena.level(this.server);
		Vec3 s = this.arena.lobbySpawn(h.index);
		p.teleportTo(level, s.x, s.y, s.z, Set.of(), p.getYRot(), 20, false);
		p.setDeltaMovement(Vec3.ZERO);
		p.resetFallDistance();
		h.inLobby = true;
		h.dropping = false;
		if (h.out) {
			p.setGameMode(GameType.SPECTATOR);
			return;
		}
		p.setGameMode(GameType.SURVIVAL);
		p.setHealth(p.getMaxHealth());
		p.getFoodData().setFoodLevel(20);
		p.getFoodData().setSaturation(5);
		if (h.cls != null) {
			h.cls.equip(p);
			h.kitted = true;
		} else {
			p.getInventory().clearContent();
			p.removeAllEffects();
			h.kitted = false;
		}
		classMenu(p, h);
	}

	private void classMenu(ServerPlayer p, Hunter h) {
		MutableComponent msg = Component.literal("Choose your class: ").withStyle(ChatFormatting.GOLD);
		for (HunterClass c : HunterClass.values()) {
			MutableComponent button = Component.literal("[" + c.title + "]").withStyle(s -> s.withColor(c.color).withBold(c == h.cls)
				.withClickEvent(new ClickEvent.RunCommand("/titan class " + c.id()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(c.description))));
			msg.append(button).append(" ");
		}
		p.sendSystemMessage(msg);
		p.sendSystemMessage(Component.literal(h.cls == null
			? "Then walk off the edge of the platform to drop into the arena. Team lives: " + this.lives
			: "You're a " + h.cls.title + ". Jump when ready. Team lives: " + this.lives).withStyle(ChatFormatting.GRAY));
	}

	public static String chooseClass(ServerPlayer p, HunterClass cls) {
		Hunter h = current == null ? null : current.hunters.get(p.getUUID());
		if (h == null) {
			return "You're not a hunter in a game";
		}
		if (h.out) {
			return "You're out";
		}
		if (!h.inLobby) {
			return "You can only change class in the lobby (you'll be back there when you die)";
		}
		h.cls = cls;
		cls.equip(p);
		h.kitted = true;
		p.sendSystemMessage(Component.literal("You are now a ").append(cls.label()).append(Component.literal(": " + cls.description).withStyle(ChatFormatting.GRAY)));
		return null;
	}

	// ------------------------------------------------------------------ tick

	public static void tickServer(MinecraftServer server) {
		Arena arena = Arena.get();
		if (arena == null) {
			return;
		}
		ServerLevel level = arena.level(server);
		if (level == null) {
			return;
		}
		List<ServerPlayer> protect = new ArrayList<>();
		if (current != null) {
			for (UUID id : current.hunters.keySet()) {
				ServerPlayer p = server.getPlayerList().getPlayer(id);
				if (p != null && p.isAlive() && !p.isSpectator()) {
					protect.add(p);
				}
			}
			current.tick(level);
		} else {
			ArenaWorld.tickIdle(server, arena, level);
		}
		arena.tick(level, protect);
	}

	private void tick(ServerLevel level) {
		TitanConfig cfg = TitanConfig.INSTANCE;
		this.ticks++;
		ServerPlayer giant = player(this.giant);

		if (this.giantRespawned && giant != null && giant.isAlive()) {
			this.giantRespawned = false;
			Vec3 c = this.arena.floorCenter();
			giant.teleportTo(level, c.x, c.y + 1, c.z, Set.of(), giant.getYRot(), 0, false);
		}
		for (Map.Entry<UUID, Hunter> e : this.hunters.entrySet()) {
			ServerPlayer p = player(e.getKey());
			if (e.getValue().respawned && p != null && p.isAlive()) {
				e.getValue().respawned = false;
				toLobby(p, e.getValue());
			}
		}

		if (this.state == State.ENDING) {
			if (--this.endTicks <= 0) {
				finish();
			}
			return;
		}

		if (this.state == State.COUNTDOWN) {
			int left = cfg.arenaCountdownSeconds * 20 - this.ticks;
			if (left % 20 == 0 && left > 0 && (left <= 100 || left % 100 == 0)) {
				for (ServerPlayer p : participants()) {
					p.sendSystemMessage(Component.literal("Starting in " + left / 20 + "...").withStyle(ChatFormatting.YELLOW), true);
				}
			}
			if (left <= 0) {
				this.state = State.RUNNING;
				this.ticks = 0;
				for (ServerPlayer p : participants()) {
					title(p, Component.literal("GO!").withStyle(ChatFormatting.GREEN), Component.empty());
				}
			}
		}

		if (giant == null || !this.saved.containsKey(this.giant)) {
			end(Component.literal("The giant left. Hunters win!").withStyle(ChatFormatting.GREEN));
			return;
		}

		// Giant: stays inside the walls and below the lobby, never heals by eating.
		if (giant.isAlive()) {
			keepInside(giant, true);
			giant.getFoodData().setFoodLevel(17);
			this.bar.setProgress(Math.max(0, Math.min(1, giant.getHealth() / giant.getMaxHealth())));
		}

		int hunting = 0;
		for (Map.Entry<UUID, Hunter> e : this.hunters.entrySet()) {
			Hunter h = e.getValue();
			ServerPlayer p = player(e.getKey());
			if (p == null || h.out) {
				continue;
			}
			hunting++;
			if (!p.isAlive()) {
				continue;
			}
			boolean inLobby = this.arena.inLobby(p.position());
			if (h.inLobby && !inLobby && p.level() == level) {
				if (this.state == State.COUNTDOWN) {
					p.sendSystemMessage(Component.literal("Wait for the countdown!").withStyle(ChatFormatting.YELLOW), true);
					Vec3 s = this.arena.lobbySpawn(h.index);
					p.teleportTo(level, s.x, s.y, s.z, Set.of(), p.getYRot(), p.getXRot(), false);
					continue;
				}
				if (!h.kitted) {
					h.cls = HunterClass.ARCHER;
					h.cls.equip(p);
					h.kitted = true;
					p.sendSystemMessage(Component.literal("No class picked, so you're an ").append(h.cls.label()));
				}
				h.inLobby = false;
				h.dropping = true;
				h.groundTicks = 0;
			}
			// Safe until they've properly landed (onGround is stale for a moment after stepping off the platform)
			if (h.dropping) {
				h.groundTicks = p.onGround() || p.isInWater() ? h.groundTicks + 1 : 0;
				if (h.groundTicks > 10) {
					h.dropping = false;
				}
			}
			if (!h.inLobby) {
				keepInside(p, false);
			}
			if (h.kitted && this.state == State.RUNNING && this.ticks % (cfg.arenaResupplySeconds * 20) == 0) {
				h.cls.resupply(p);
			}
			if (this.ticks % 20 == 0) {
				p.sendSystemMessage(Component.literal("♥ " + this.lives).withStyle(ChatFormatting.RED)
					.append(Component.literal(" team lives  ").withStyle(ChatFormatting.GRAY))
					.append(h.cls != null ? h.cls.label() : Component.literal("pick a class").withStyle(ChatFormatting.GRAY))
					.append(Component.literal("  " + timeLeft()).withStyle(ChatFormatting.GRAY)), true);
			}
		}
		if (this.ticks % 20 == 0 && giant.isAlive()) {
			giant.sendSystemMessage(Component.literal(hunting + " hunters, " + this.lives + " team lives left  " + timeLeft()).withStyle(ChatFormatting.GOLD), true);
		}

		if (this.ticks % 20 == 0) {
			// Monsters wandering in would steal kills (and lives) from the giant
			Vec3 c = this.arena.floorCenter();
			double r = this.arena.radius() + 2;
			AABB box = new AABB(c.x - r, c.y + ArenaLayout.BOTTOM, c.z - r, c.x + r, c.y + ArenaLayout.TOP + 8, c.z + r);
			for (Monster m : level.getEntitiesOfClass(Monster.class, box)) {
				m.discard();
			}
		}

		if (this.state == State.RUNNING) {
			if (hunting == 0) {
				end(Component.literal("All hunters are out. The giant wins!").withStyle(ChatFormatting.RED));
				return;
			}
			if (this.ticks >= cfg.arenaTimeLimitSeconds * 20) {
				end(Component.literal("Time's up. The giant wins!").withStyle(ChatFormatting.RED));
				return;
			}
			if (cfg.arenaRegenSeconds > 0 && this.ticks % (cfg.arenaRegenSeconds * 20) == 0) {
				this.arena.regenerate();
				broadcast(Component.literal("The arena rebuilds itself!").withStyle(ChatFormatting.AQUA));
			}
		}
	}

	private String timeLeft() {
		if (this.state != State.RUNNING) {
			return "";
		}
		int s = Math.max(0, TitanConfig.INSTANCE.arenaTimeLimitSeconds - this.ticks / 20);
		return String.format("%d:%02d", s / 60, s % 60);
	}

	/** Pushes a player back inside the walls (and the giant below the lobby); teleports them if they're well outside. */
	private void keepInside(ServerPlayer p, boolean isGiant) {
		if (PhysicsWorld.get(p.level()).isRagdolled(p) || p.level().dimension() != this.arena.dimension) {
			return;
		}
		Vec3 pos = p.position();
		Vec3 c = this.arena.floorCenter();
		double limit = this.arena.radius() - (isGiant ? 2.5 : 0.5);
		double ceiling = c.y + (isGiant ? TitanConfig.INSTANCE.arenaGiantCeiling : ArenaLayout.TOP + 6);
		double d = this.arena.distance(pos);
		double x = pos.x, y = pos.y, z = pos.z;
		boolean move = false;
		if (d > limit) {
			double k = (limit - 1) / d;
			x = c.x + (pos.x - c.x) * k;
			z = c.z + (pos.z - c.z) * k;
			move = true;
		}
		if (y > ceiling) {
			y = ceiling - 0.5;
			move = true;
		}
		if (y < c.y + ArenaLayout.BOTTOM - 3) {
			y = c.y + 1;
			move = true;
		}
		if (move) {
			p.teleportTo((ServerLevel) p.level(), x, y, z, Set.of(), p.getYRot(), p.getXRot(), false);
			p.setDeltaMovement(Vec3.ZERO);
			p.resetFallDistance();
		}
	}

	// ------------------------------------------------------------------ events

	/** Damage filter: no friendly fire, no hurting anyone during the countdown or in the lobby, safe landings. */
	public static boolean allowDamage(Entity entity, DamageSource source) {
		if (!ArenaWorld.allowIdleDamage(entity)) {
			return false;
		}
		if (current == null || !(entity instanceof ServerPlayer p) || !isParticipant(p)) {
			return true;
		}
		if (current.state != State.RUNNING) {
			return false;
		}
		Hunter h = current.hunters.get(p.getUUID());
		if (h != null) {
			if (h.inLobby) {
				return false;
			}
			if (h.dropping && source.is(DamageTypeTags.IS_FALL)) {
				p.resetFallDistance();
				return false;
			}
			if (source.getEntity() instanceof Player attacker && attacker != p && current.hunters.containsKey(attacker.getUUID())) {
				return false;
			}
		}
		return true;
	}

	/** Called before a participant dies: nothing drops, and a death costs a hunter a life. */
	public static void onDeath(ServerPlayer p, DamageSource source) {
		if (current == null || !isParticipant(p)) {
			return;
		}
		p.getInventory().clearContent();
		if (current.state != State.RUNNING) {
			return;
		}
		if (p.getUUID().equals(current.giant)) {
			String by = source.getEntity() instanceof Player killer ? " " + killer.getGameProfile().name() + " landed the final blow." : "";
			current.end(Component.literal("The giant has fallen! Hunters win!" + by).withStyle(ChatFormatting.GREEN));
			return;
		}
		Hunter h = current.hunters.get(p.getUUID());
		if (current.lives > 0) {
			current.lives--;
			current.broadcast(Component.literal(p.getGameProfile().name() + " died. " + (current.lives > 0 ? current.lives + " team lives left" : "No lives left: next death is final!"))
				.withStyle(current.lives > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED));
		} else {
			h.out = true;
			current.broadcast(Component.literal(p.getGameProfile().name() + " is out!").withStyle(ChatFormatting.RED));
		}
	}

	public static void onRespawn(ServerPlayer p) {
		Saved pending = PENDING_RESTORE.remove(p.getUUID());
		if (pending != null) {
			restore(p, pending);
		}
		if (current == null) {
			return;
		}
		Hunter h = current.hunters.get(p.getUUID());
		if (h != null) {
			h.respawned = true;
		} else if (p.getUUID().equals(current.giant)) {
			current.giantRespawned = true;
		}
		current.bar.addPlayer(p);
	}

	public static void onDisconnect(ServerPlayer p) {
		if (current == null || !isParticipant(p)) {
			return;
		}
		Saved s = current.saved.remove(p.getUUID());
		if (s != null && p.isAlive()) {
			restore(p, s);
		} else if (s != null) {
			PENDING_RESTORE.put(p.getUUID(), s);
		}
		current.bar.removePlayer(p);
	}

	// ------------------------------------------------------------------ ending

	private void end(Component result) {
		if (this.state == State.ENDING) {
			return;
		}
		this.state = State.ENDING;
		this.endTicks = 160;
		this.result = result;
		for (ServerPlayer p : participants()) {
			title(p, result, Component.literal("Game over"));
		}
		broadcast(result);
	}

	private void finish() {
		current = null;
		for (ServerPlayer p : participants()) {
			Saved s = this.saved.get(p.getUUID());
			if (p.isAlive()) {
				restore(p, s);
			} else {
				PENDING_RESTORE.put(p.getUUID(), s);
			}
		}
		this.bar.removeAllPlayers();
		this.arena.regenerate();
	}

	private static void restore(ServerPlayer p, Saved s) {
		boolean isGiant = GiantManager.isGiant(p);
		if (s.wasGiant && !isGiant) {
			GiantManager.setGiant(p, true, s.giantScale);
		} else if (!s.wasGiant && isGiant) {
			GiantManager.setGiant(p, false, 1);
		} else if (isGiant) {
			GiantManager.resize(p, s.giantScale);
		}
		p.removeAllEffects();
		p.getInventory().clearContent();
		for (int i = 0; i < s.items.length && i < p.getInventory().getContainerSize(); i++) {
			p.getInventory().setItem(i, s.items[i]);
		}
		p.setGameMode(s.mode);
		ServerLevel level = p.level().getServer().getLevel(s.dimension);
		if (level != null) {
			p.teleportTo(level, s.pos.x, s.pos.y, s.pos.z, Set.of(), s.yRot, s.xRot, false);
			p.setHealth(p.getMaxHealth());
			p.resetFallDistance();
		}
	}

	private ServerPlayer player(UUID id) {
		ServerPlayer p = this.server.getPlayerList().getPlayer(id);
		return p != null ? p : TEST_PLAYERS.get(id);
	}

	private List<ServerPlayer> participants() {
		List<ServerPlayer> list = new ArrayList<>();
		for (UUID id : this.saved.keySet()) {
			ServerPlayer p = player(id);
			if (p != null) {
				list.add(p);
			}
		}
		return list;
	}

	private void broadcast(Component msg) {
		for (ServerPlayer p : participants()) {
			p.sendSystemMessage(msg);
		}
	}

	private static void title(ServerPlayer p, Component title, Component subtitle) {
		p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
		p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		p.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	public State state() {
		return this.state;
	}
}
