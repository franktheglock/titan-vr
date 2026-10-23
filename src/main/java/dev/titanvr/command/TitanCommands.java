package dev.titanvr.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.titanvr.TitanConfig;
import dev.titanvr.arena.Arena;
import dev.titanvr.arena.ArenaGame;
import dev.titanvr.arena.HunterClass;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.physics.PhysicsWorld;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

public final class TitanCommands {
	private TitanCommands() {
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("titan")
			.executes(TitanCommands::help)
			.then(Commands.literal("giant")
				.executes(c -> toggleSelf(c, Double.NaN))
				.then(Commands.literal("on")
					.executes(c -> setSelf(c, true, TitanConfig.INSTANCE.defaultGiantScale))
					.then(Commands.argument("scale", DoubleArgumentType.doubleArg(1, 16))
						.executes(c -> setSelf(c, true, DoubleArgumentType.getDouble(c, "scale")))))
				.then(Commands.literal("off").executes(c -> setSelf(c, false, 1)))
				.then(Commands.argument("targets", EntityArgument.players())
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.literal("on")
						.executes(c -> setOthers(c, true, TitanConfig.INSTANCE.defaultGiantScale))
						.then(Commands.argument("scale", DoubleArgumentType.doubleArg(1, 16))
							.executes(c -> setOthers(c, true, DoubleArgumentType.getDouble(c, "scale")))))
					.then(Commands.literal("off").executes(c -> setOthers(c, false, 1)))))
			.then(Commands.literal("size")
				.then(Commands.argument("scale", DoubleArgumentType.doubleArg(1, 16))
					.executes(c -> {
						ServerPlayer p = c.getSource().getPlayerOrException();
						if (!GiantManager.isGiant(p)) {
							GiantManager.setGiant(p, true, DoubleArgumentType.getDouble(c, "scale"));
						} else {
							GiantManager.resize(p, DoubleArgumentType.getDouble(c, "scale"));
						}
						c.getSource().sendSuccess(() -> Component.literal("Giant size: " + String.format("%.1f", GiantManager.get(p).scale)), false);
						return 1;
					})))
			.then(Commands.literal("clear")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> {
					int n = PhysicsWorld.get(c.getSource().getLevel()).clearDebris();
					c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " debris bodies"), true);
					return n;
				}))
			.then(Commands.literal("stats").executes(c -> {
				PhysicsWorld w = PhysicsWorld.get(c.getSource().getLevel());
				c.getSource().sendSuccess(() -> Component.literal(String.format("Bodies: %d, voxels: %d/%d, ragdolls: %d, giants: %d, physics: %.2f ms/tick",
					w.bodyCount(), w.voxelCount(), TitanConfig.INSTANCE.maxVoxels, w.ragdollCount(), GiantManager.all().size(), w.avgTickMs)), false);
				return 1;
			}))
			.then(Commands.literal("ragdoll")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("targets", EntityArgument.entities())
					.executes(c -> ragdoll(c, 6))
					.then(Commands.argument("speed", DoubleArgumentType.doubleArg(0, 200))
						.executes(c -> ragdoll(c, DoubleArgumentType.getDouble(c, "speed"))))))
			.then(Commands.literal("arena")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("build")
					.executes(c -> buildArena(c, TitanConfig.INSTANCE.arenaRadius))
					.then(Commands.argument("radius", IntegerArgumentType.integer(20, 80))
						.executes(c -> buildArena(c, IntegerArgumentType.getInteger(c, "radius")))))
				.then(Commands.literal("regen").executes(c -> {
					Arena a = Arena.get();
					if (a == null) {
						c.getSource().sendFailure(Component.literal("There's no arena yet: /titan arena build"));
						return 0;
					}
					a.regenerate();
					c.getSource().sendSuccess(() -> Component.literal("Rebuilding the arena"), true);
					return 1;
				}))
				.then(Commands.literal("lobby").executes(c -> {
					Arena a = Arena.get();
					if (a == null) {
						c.getSource().sendFailure(Component.literal("There's no arena yet: /titan arena build"));
						return 0;
					}
					ServerPlayer p = c.getSource().getPlayerOrException();
					var s = a.lobbySpawn(0);
					p.teleportTo(a.level(c.getSource().getServer()), s.x, s.y, s.z, java.util.Set.of(), p.getYRot(), p.getXRot(), false);
					return 1;
				}))
				.then(Commands.literal("forget").executes(c -> {
					if (ArenaGame.get() != null) {
						c.getSource().sendFailure(Component.literal("Stop the game first"));
						return 0;
					}
					Arena.remove(c.getSource().getServer());
					c.getSource().sendSuccess(() -> Component.literal("Arena forgotten (its blocks stay where they are)"), true);
					return 1;
				})))
			.then(Commands.literal("game")
				.then(Commands.literal("start")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.then(Commands.argument("giant", EntityArgument.player())
						.executes(c -> startGame(c, TitanConfig.INSTANCE.arenaLives))
						.then(Commands.argument("lives", IntegerArgumentType.integer(1, 500))
							.executes(c -> startGame(c, IntegerArgumentType.getInteger(c, "lives"))))))
				.then(Commands.literal("stop")
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
					.executes(c -> {
						if (ArenaGame.get() == null) {
							c.getSource().sendFailure(Component.literal("No game is running"));
							return 0;
						}
						ArenaGame.stop();
						c.getSource().sendSuccess(() -> Component.literal("Game stopped; everyone got their things back"), true);
						return 1;
					}))
				.then(Commands.literal("join").executes(c -> {
					String err = ArenaGame.join(c.getSource().getPlayerOrException());
					if (err != null) {
						c.getSource().sendFailure(Component.literal(err));
						return 0;
					}
					return 1;
				})))
			.then(Commands.literal("class")
				.then(Commands.argument("class", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.Arrays.stream(HunterClass.values()).map(HunterClass::id), b))
					.executes(c -> {
						HunterClass cls = HunterClass.byId(StringArgumentType.getString(c, "class"));
						if (cls == null) {
							c.getSource().sendFailure(Component.literal("Unknown class"));
							return 0;
						}
						String err = ArenaGame.chooseClass(c.getSource().getPlayerOrException(), cls);
						if (err != null) {
							c.getSource().sendFailure(Component.literal(err));
							return 0;
						}
						return 1;
					})))
			.then(Commands.literal("blast")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(c -> blast(c, 3))
					.then(Commands.argument("radius", IntegerArgumentType.integer(1, 6))
						.executes(c -> blast(c, IntegerArgumentType.getInteger(c, "radius"))))))
			.then(Commands.literal("tower")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> tower(c)))
			.then(Commands.literal("config")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("key", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(TitanConfig.keys(), b))
					.executes(c -> {
						String key = StringArgumentType.getString(c, "key");
						try {
							String v = TitanConfig.get(key);
							c.getSource().sendSuccess(() -> Component.literal(key + " = " + v), false);
						} catch (ReflectiveOperationException e) {
							c.getSource().sendFailure(Component.literal("Unknown key: " + key));
						}
						return 1;
					})
					.then(Commands.argument("value", StringArgumentType.greedyString())
						.executes(c -> {
							String key = StringArgumentType.getString(c, "key");
							try {
								String v = TitanConfig.set(key, StringArgumentType.getString(c, "value"));
								c.getSource().sendSuccess(() -> Component.literal(key + " set to " + v), true);
							} catch (ReflectiveOperationException | NumberFormatException e) {
								c.getSource().sendFailure(Component.literal("Could not set " + key + ": " + e.getMessage()));
							}
							return 1;
						}))));
	}

	private static int help(CommandContext<CommandSourceStack> c) {
		c.getSource().sendSuccess(() -> Component.literal(String.join("\n",
			"§6Titan VR§r",
			"/titan giant [on [scale]|off] - become a giant (VR players are giants automatically)",
			"/titan size <1-16> - change your size",
			"/titan tower - build a destructible test tower in front of you",
			"/titan ragdoll <targets> [speed] - launch entities as ragdolls",
			"/titan clear | stats | config <key> [value]",
			"/titan blast <pos> [radius] - blow a ball of blocks apart into debris",
			"/titan arena build [radius] | regen | lobby | forget - the kill-the-giant arena",
			"/titan game start <giant> [lives] | stop | join, /titan class <name>",
			"VR: squeeze grip near anything to grab it (blocks rip out of the world), let go while swinging to throw.",
			"All other buttons are normal Vivecraft controls.",
			"Desktop giants: hold R / F to grab with right / left hand, release to throw where you look.")), false);
		return 1;
	}

	/** Blows a ball of blocks apart into flying debris. */
	private static int blast(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
		ServerLevel level = c.getSource().getLevel();
		BlockPos center = BlockPosArgument.getLoadedBlockPos(c, "pos");
		PhysicsWorld world = PhysicsWorld.get(level);
		int n = 0;
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
			double dx = pos.getX() - center.getX(), dy = pos.getY() - center.getY(), dz = pos.getZ() - center.getZ();
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			BlockState state = level.getBlockState(pos);
			if (d > radius + 0.3 || state.isAir() || state.getDestroySpeed(level, pos) < 0) {
				continue;
			}
			double speed = 14.0 / (1.0 + d);
			Vector3d vel = d < 0.01 ? new Vector3d(0, speed, 0) : new Vector3d(dx / d * speed, dy / d * speed + 6, dz / d * speed);
			world.destruction.breakBlock(pos.immutable(), state, vel, true, null);
			n++;
		}
		int count = n;
		c.getSource().sendSuccess(() -> Component.literal("Blasted " + count + " blocks"), true);
		return n;
	}

	private static int buildArena(CommandContext<CommandSourceStack> c, int radius) throws CommandSyntaxException {
		if (ArenaGame.get() != null) {
			c.getSource().sendFailure(Component.literal("Stop the game first"));
			return 0;
		}
		ServerPlayer p = c.getSource().getPlayerOrException();
		Arena.create(c.getSource().getServer(), p.level(), p.blockPosition(), radius, false);
		c.getSource().sendSuccess(() -> Component.literal("Building a " + (radius * 2) + "-block arena around you (it replaces the terrain). "
			+ "Start a game with /titan game start <giant>"), true);
		return 1;
	}

	private static int startGame(CommandContext<CommandSourceStack> c, int lives) throws CommandSyntaxException {
		ServerPlayer giant = EntityArgument.getPlayer(c, "giant");
		java.util.List<ServerPlayer> hunters = new java.util.ArrayList<>(c.getSource().getServer().getPlayerList().getPlayers());
		hunters.remove(giant);
		String err = ArenaGame.start(c.getSource().getServer(), giant, hunters, lives);
		if (err != null) {
			c.getSource().sendFailure(Component.literal(err));
			return 0;
		}
		return 1;
	}

	private static int toggleSelf(CommandContext<CommandSourceStack> c, double scale) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		return setSelf(c, !GiantManager.isGiant(p), TitanConfig.INSTANCE.defaultGiantScale);
	}

	private static int setSelf(CommandContext<CommandSourceStack> c, boolean on, double scale) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		if (ArenaGame.isParticipant(p)) {
			c.getSource().sendFailure(Component.literal("Not during a game"));
			return 0;
		}
		GiantManager.setGiant(p, on, scale);
		c.getSource().sendSuccess(() -> Component.literal(on ? "You are now a giant (size " + String.format("%.1f", GiantManager.get(p).scale) + ")" : "You are normal-sized again"), false);
		return 1;
	}

	private static int setOthers(CommandContext<CommandSourceStack> c, boolean on, double scale) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(c, "targets");
		for (ServerPlayer p : targets) {
			GiantManager.setGiant(p, on, scale);
		}
		c.getSource().sendSuccess(() -> Component.literal((on ? "Made giant: " : "Shrunk: ") + targets.size() + " player(s)"), true);
		return targets.size();
	}

	private static int ragdoll(CommandContext<CommandSourceStack> c, double speed) throws CommandSyntaxException {
		int n = 0;
		for (Entity e : EntityArgument.getEntities(c, "targets")) {
			if (e instanceof LivingEntity le) {
				PhysicsWorld w = PhysicsWorld.get(le.level());
				double a = Math.random() * Math.PI * 2;
				w.launch(le, new Vector3d(Math.cos(a) * speed * 0.4, speed, Math.sin(a) * speed * 0.4), null);
				n++;
			}
		}
		int count = n;
		c.getSource().sendSuccess(() -> Component.literal("Ragdolled " + count + " entities"), true);
		return n;
	}

	/** Builds a hollow multi-material tower ~12 blocks in front of the player - good smashing practice. */
	private static int tower(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		ServerLevel level = p.level();
		Giant g = GiantManager.get(p);
		double dist = 6 + (g != null ? g.scale * 1.2 : 4);
		var look = p.getLookAngle().multiply(1, 0, 1).normalize();
		BlockPos base = BlockPos.containing(p.getX() + look.x * dist, p.getY(), p.getZ() + look.z * dist);
		base = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base);
		buildTower(level, base);
		c.getSource().sendSuccess(() -> Component.literal("Built a tower. Go smash it."), false);
		return 1;
	}

	public static void buildTower(ServerLevel level, BlockPos base) {
		BlockState[] walls = {Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState(), Blocks.BRICKS.defaultBlockState()};
		int size = 7, height = 18;
		for (int y = 0; y < height; y++) {
			BlockState wall = walls[(y / 6) % walls.length];
			for (int x = 0; x < size; x++) {
				for (int z = 0; z < size; z++) {
					boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
					boolean floor = y % 6 == 0;
					boolean window = edge && y % 6 == 3 && (x == size / 2 || z == size / 2);
					BlockPos at = base.offset(x - size / 2, y, z - size / 2);
					if (window) {
						level.setBlock(at, Blocks.GLASS.defaultBlockState(), 3);
					} else if (edge || floor) {
						level.setBlock(at, wall, 3);
					} else {
						level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
		}
		// A roof spire
		for (int y = 0; y < 4; y++) {
			level.setBlock(base.offset(0, height + y, 0), Blocks.OAK_LOG.defaultBlockState(), 3);
		}
	}
}
