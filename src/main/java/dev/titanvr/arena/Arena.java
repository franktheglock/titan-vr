package dev.titanvr.arena;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.titanvr.TitanMod;
import dev.titanvr.physics.PhysicsWorld;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The world's one arena: where it is, and the job that (re)builds it a slice at a time. */
public final class Arena {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CHECKS_PER_TICK = 40000;
	private static final int CHANGES_PER_TICK = 6000;

	private static Arena current;

	/** Saved form. */
	private static final class Data {
		String dimension;
		int x, y, z;
		int radius;
		long seed;
		boolean dedicated;
	}

	public final ResourceKey<Level> dimension;
	public final BlockPos center;
	public final ArenaLayout layout;
	/** The arena of a "Titan VR Arena" world: games start by themselves and players wait in the lobby. */
	public final boolean dedicated;

	// Regen job: walks every column of the arena's bounding square, bottom to top.
	private boolean regenerating;
	private int cursor;
	private int changed;

	private Arena(ResourceKey<Level> dimension, BlockPos center, int radius, long seed, boolean dedicated) {
		this.dimension = dimension;
		this.dedicated = dedicated;
		this.center = center;
		this.layout = new ArenaLayout(radius, seed);
	}

	public static Arena get() {
		return current;
	}

	public static Arena create(MinecraftServer server, ServerLevel level, BlockPos center, int radius, boolean dedicated) {
		// The bedrock floor and the lobby both have to fit inside the world
		int y = Math.max(level.getMinY() - ArenaLayout.BOTTOM, Math.min(level.getMaxY() - ArenaLayout.TOP - 1, center.getY()));
		center = new BlockPos(center.getX(), y, center.getZ());
		current = new Arena(level.dimension(), center, radius, level.getRandom().nextLong(), dedicated);
		save(server);
		current.regenerate();
		return current;
	}

	public static void remove(MinecraftServer server) {
		current = null;
		try {
			Files.deleteIfExists(path(server));
		} catch (Exception e) {
			TitanMod.LOGGER.error("Failed to delete arena file", e);
		}
	}

	public int radius() {
		return this.layout.radius;
	}

	public ServerLevel level(MinecraftServer server) {
		return server.getLevel(this.dimension);
	}

	public boolean isRegenerating() {
		return this.regenerating;
	}

	/** For tests: how many owned blocks differ from the design. */
	public int mismatches(ServerLevel level) {
		int n = 0, span = this.radius() + 3;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -span; dx <= span; dx++) {
			for (int dz = -span; dz <= span; dz++) {
				for (int y = ArenaLayout.BOTTOM; y <= ArenaLayout.TOP; y++) {
					BlockState want = this.layout.at(dx, y, dz);
					if (want == null) {
						break;
					}
					pos.set(this.center.getX() + dx, this.center.getY() + y, this.center.getZ() + dz);
					if (!level.isOutsideBuildHeight(pos) && level.getBlockState(pos) != want) {
						n++;
					}
				}
			}
		}
		return n;
	}

	public void regenerate() {
		this.regenerating = true;
		this.cursor = 0;
		this.changed = 0;
	}

	public Vec3 lobbySpawn(int index) {
		double a = index * 2.39996;
		double r = 1.5 + (index % 3);
		return new Vec3(this.center.getX() + 0.5 + Math.cos(a) * r, this.center.getY() + ArenaLayout.LOBBY_Y + 1, this.center.getZ() + 0.5 + Math.sin(a) * r);
	}

	public Vec3 floorCenter() {
		return new Vec3(this.center.getX() + 0.5, this.center.getY(), this.center.getZ() + 0.5);
	}

	/** Horizontal distance from the arena's centre. */
	public double distance(Vec3 p) {
		double dx = p.x - (this.center.getX() + 0.5), dz = p.z - (this.center.getZ() + 0.5);
		return Math.sqrt(dx * dx + dz * dz);
	}

	public boolean inLobby(Vec3 p) {
		double y = p.y - this.center.getY();
		return distance(p) <= ArenaLayout.LOBBY_RADIUS + 1.5 && y >= ArenaLayout.LOBBY_Y - 0.5 && y <= ArenaLayout.TOP + 4;
	}

	/** Builds the whole arena right now (for a new arena world, before anyone spawns into the void). */
	public void buildNow(ServerLevel level) {
		regenerate();
		while (this.regenerating) {
			this.tick(level, List.of(), Integer.MAX_VALUE);
		}
	}

	public void tick(ServerLevel level, List<ServerPlayer> protect) {
		this.tick(level, protect, CHANGES_PER_TICK);
	}

	private void tick(ServerLevel level, List<ServerPlayer> protect, int maxChanges) {
		if (!this.regenerating || level.dimension() != this.dimension) {
			return;
		}
		if (this.cursor == 0) {
			PhysicsWorld.get(level).clearDebris();
		}
		// Don't grow blocks inside hunters standing in the rubble; the next regen fills those in.
		List<AABB> boxes = new ArrayList<>();
		for (ServerPlayer p : protect) {
			if (p.level() == level) {
				boxes.add(p.getBoundingBox().inflate(0.3));
			}
		}
		int span = this.radius() + 3;
		int side = span * 2 + 1;
		int height = ArenaLayout.TOP - ArenaLayout.BOTTOM + 1;
		int total = side * side * height;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int checks = 0, changes = 0;
		int maxChecks = maxChanges == Integer.MAX_VALUE ? Integer.MAX_VALUE : CHECKS_PER_TICK;
		while (this.cursor < total && checks < maxChecks && changes < maxChanges) {
			int i = this.cursor++;
			checks++;
			int y = i % height + ArenaLayout.BOTTOM;
			int col = i / height;
			int dx = col % side - span, dz = col / side - span;
			BlockState want = this.layout.at(dx, y, dz);
			if (want == null) {
				// Skip the rest of a column the arena doesn't own
				this.cursor += height - 1 - (y - ArenaLayout.BOTTOM);
				continue;
			}
			pos.set(this.center.getX() + dx, this.center.getY() + y, this.center.getZ() + dz);
			if (level.isOutsideBuildHeight(pos) || level.getBlockState(pos) == want) {
				continue;
			}
			if (!want.isAir() && blocked(boxes, pos)) {
				continue;
			}
			level.setBlock(pos, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			changes++;
			this.changed++;
		}
		if (this.cursor >= total) {
			this.regenerating = false;
			TitanMod.LOGGER.info("Arena regenerated: {} blocks changed", this.changed);
		}
	}

	private static boolean blocked(List<AABB> boxes, BlockPos pos) {
		for (AABB b : boxes) {
			if (b.intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ persistence

	private static Path path(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("titanvr_arena.json");
	}

	public static void load(MinecraftServer server) {
		current = null;
		Path p = path(server);
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p)) {
			Data d = GSON.fromJson(r, Data.class);
			ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(d.dimension));
			current = new Arena(dim, new BlockPos(d.x, d.y, d.z), d.radius, d.seed, d.dedicated);
		} catch (Exception e) {
			TitanMod.LOGGER.error("Failed to read {}", p, e);
		}
	}

	private static void save(MinecraftServer server) {
		Data d = new Data();
		d.dimension = current.dimension.identifier().toString();
		d.x = current.center.getX();
		d.y = current.center.getY();
		d.z = current.center.getZ();
		d.radius = current.radius();
		d.seed = current.layout.seed;
		d.dedicated = current.dedicated;
		try (Writer w = Files.newBufferedWriter(path(server))) {
			GSON.toJson(d, w);
		} catch (Exception e) {
			TitanMod.LOGGER.error("Failed to save arena", e);
		}
	}
}
