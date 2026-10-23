package dev.titanvr.arena;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The arena's design, generated from a seed: a walled circular field with towers, houses, trees and boulders, plus a
 * glass lobby floating high above the middle that hunters jump down from. Coordinates are relative to the centre of
 * the arena floor (y = 0 is the first air block above the floor). Regeneration compares the world against this.
 */
public final class ArenaLayout {
	public static final int BOTTOM = -5;
	public static final int WALL_HEIGHT = 10;
	public static final int LOBBY_Y = 50;
	public static final int LOBBY_RADIUS = 7;
	public static final int TOP = LOBBY_Y + 6;

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState[] BRICKS = {
		Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(),
		Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), Blocks.CRACKED_STONE_BRICKS.defaultBlockState()};
	private static final BlockState[][] WOODS = {
		{Blocks.OAK_LOG.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()},
		{Blocks.SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()},
		{Blocks.BIRCH_LOG.defaultBlockState(), Blocks.BIRCH_PLANKS.defaultBlockState()}};

	public final int radius;
	public final long seed;
	private final Long2ObjectOpenHashMap<BlockState> structures = new Long2ObjectOpenHashMap<>();

	public ArenaLayout(int radius, long seed) {
		this.radius = radius;
		this.seed = seed;
		generate();
	}

	/** Whether (dx, dz) is inside the area the arena owns; everything outside is left alone. */
	public boolean owns(int dx, int dz) {
		double r = this.radius + 2.5;
		return dx * dx + dz * dz <= r * r;
	}

	/** The block that belongs at this spot, or null if the arena doesn't own it. */
	public BlockState at(int dx, int y, int dz) {
		if (y < BOTTOM || y > TOP || !owns(dx, dz)) {
			return null;
		}
		BlockState s = this.structures.get(BlockPos.asLong(dx, y, dz));
		if (s != null) {
			return s;
		}
		double d = Math.sqrt(dx * dx + dz * dz);
		if (y == BOTTOM) {
			return Blocks.BEDROCK.defaultBlockState();
		}
		if (y < -1) {
			return Blocks.STONE.defaultBlockState();
		}
		if (d > this.radius) {
			if (y <= WALL_HEIGHT) {
				return brick(dx, y, dz);
			}
			if (y == WALL_HEIGHT + 1) {
				int step = (int) Math.floor(Math.atan2(dz, dx) * this.radius / 2.0);
				return (step & 1) == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : AIR;
			}
			return AIR;
		}
		if (y == -1) {
			if (d < 5) {
				return d < 1.5 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Blocks.POLISHED_ANDESITE.defaultBlockState();
			}
			if (Math.abs(d - this.radius * 0.55) < 1.0) {
				return brick(dx, y, dz);
			}
			BlockState above = this.structures.get(BlockPos.asLong(dx, 0, dz));
			if (above != null && !above.isAir()) {
				// Grass under a block turns to dirt by itself; start as dirt so regen has nothing to fix
				return Blocks.DIRT.defaultBlockState();
			}
			return (hash(dx, 0, dz) % 9 == 0) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
		}
		return AIR;
	}

	private BlockState brick(int x, int y, int z) {
		return BRICKS[(int) (hash(x, y, z) % BRICKS.length)];
	}

	private long hash(int x, int y, int z) {
		long h = this.seed ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		return h & Long.MAX_VALUE;
	}

	// ------------------------------------------------------------------ generation

	private record Footprint(int x, int z, double r) {
	}

	private void generate() {
		Random rnd = new Random(this.seed);
		List<Footprint> used = new ArrayList<>();
		int towers = Math.max(3, this.radius / 8);
		int houses = Math.max(4, this.radius / 5);
		int trees = Math.max(6, this.radius / 3);
		int boulders = Math.max(4, this.radius / 6);
		for (int i = 0; i < towers; i++) {
			Footprint f = place(rnd, used, 4.5, this.radius * 0.35, this.radius - 6);
			if (f != null) {
				tower(f.x, f.z, 14 + rnd.nextInt(9), rnd);
			}
		}
		for (int i = 0; i < houses; i++) {
			int w = 5 + rnd.nextInt(3), l = 5 + rnd.nextInt(3);
			Footprint f = place(rnd, used, Math.max(w, l) * 0.75, 11, this.radius - 5);
			if (f != null) {
				house(f.x, f.z, w, l, rnd);
			}
		}
		for (int i = 0; i < trees; i++) {
			Footprint f = place(rnd, used, 3, 9, this.radius - 4);
			if (f != null) {
				tree(f.x, f.z, 5 + rnd.nextInt(3));
			}
		}
		for (int i = 0; i < boulders; i++) {
			Footprint f = place(rnd, used, 2.5, 9, this.radius - 3);
			if (f != null) {
				boulder(f.x, f.z, 1.5 + rnd.nextDouble(), rnd);
			}
		}
		lobby();
	}

	/** A random free spot between two distances from the centre, or null if it can't find one. */
	private Footprint place(Random rnd, List<Footprint> used, double r, double minDist, double maxDist) {
		for (int attempt = 0; attempt < 40; attempt++) {
			double a = rnd.nextDouble() * Math.PI * 2;
			double dist = minDist + rnd.nextDouble() * Math.max(0, maxDist - minDist);
			int x = (int) Math.round(Math.cos(a) * dist), z = (int) Math.round(Math.sin(a) * dist);
			boolean free = true;
			for (Footprint o : used) {
				double dx = o.x - x, dz = o.z - z;
				if (dx * dx + dz * dz < (o.r + r + 1.5) * (o.r + r + 1.5)) {
					free = false;
					break;
				}
			}
			if (free) {
				Footprint f = new Footprint(x, z, r);
				used.add(f);
				return f;
			}
		}
		return null;
	}

	private void put(int x, int y, int z, BlockState s) {
		this.structures.put(BlockPos.asLong(x, y, z), s);
	}

	/** A round stone-brick tower with floors every 6 blocks, a ladder up the inside, windows and a battlement. */
	private void tower(int cx, int cz, int height, Random rnd) {
		// Door faces the arena centre
		Direction door = Math.abs(cx) > Math.abs(cz) ? (cx > 0 ? Direction.WEST : Direction.EAST) : (cz > 0 ? Direction.NORTH : Direction.SOUTH);
		// Ladder on the wall opposite the door, climbing it faces the door
		Direction ladderWall = door.getOpposite();
		int lx = cx + ladderWall.getStepX() * 2, lz = cz + ladderWall.getStepZ() * 2;
		for (int y = 0; y <= height + 1; y++) {
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d > 3.6) {
						continue;
					}
					int wx = cx + x, wz = cz + z;
					boolean wall = d > 2.6;
					if (y == height + 1) {
						if (wall && ((x + z) & 1) == 0) {
							put(wx, y, wz, brick(wx, y, wz));
						}
						continue;
					}
					if (wall) {
						boolean doorway = y <= 1 && x * door.getStepX() + z * door.getStepZ() >= 3 && Math.abs(x * door.getStepZ() + z * door.getStepX()) == 0;
						boolean window = y % 6 == 3 && (x == 0 || z == 0) && !doorway;
						put(wx, y, wz, doorway ? AIR : window ? Blocks.GLASS.defaultBlockState() : brick(wx, y, wz));
					} else if (y > 0 && (y % 6 == 0 || y == height) && !(wx == lx && wz == lz)) {
						put(wx, y, wz, Blocks.SPRUCE_PLANKS.defaultBlockState());
					} else {
						put(wx, y, wz, AIR);
					}
				}
			}
			if (y <= height) {
				put(lx, y, lz, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, door));
			}
		}
	}

	/** A small wooden house with log corners, a doorway, windows and a flat roof. */
	private void house(int cx, int cz, int w, int l, Random rnd) {
		BlockState[] wood = WOODS[rnd.nextInt(WOODS.length)];
		int h = 4;
		int x0 = cx - w / 2, z0 = cz - l / 2, x1 = x0 + w - 1, z1 = z0 + l - 1;
		boolean doorOnX = rnd.nextBoolean();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				put(x, -1, z, wood[1]);
				boolean ex = x == x0 || x == x1, ez = z == z0 || z == z1;
				for (int y = 0; y <= h; y++) {
					BlockState s;
					if (y == h) {
						s = wood[1];
					} else if (ex && ez) {
						s = wood[0];
					} else if (ex || ez) {
						boolean doorway = y <= 1 && (doorOnX ? (x == x0 && z == cz) : (z == z0 && x == cx));
						boolean window = y == 2 && (ex ? (z - z0) % 2 == 1 : (x - x0) % 2 == 1);
						s = doorway ? AIR : window ? Blocks.GLASS.defaultBlockState() : wood[1];
					} else {
						s = AIR;
					}
					put(x, y, z, s);
				}
			}
		}
		for (int x = x0 + 1; x <= x1 - 1; x++) {
			for (int z = z0 + 1; z <= z1 - 1; z++) {
				put(x, h + 1, z, wood[1]);
			}
		}
	}

	private void tree(int cx, int cz, int height) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		for (int x = -2; x <= 2; x++) {
			for (int y = height - 2; y <= height + 1; y++) {
				for (int z = -2; z <= 2; z++) {
					double d = Math.sqrt(x * x + (y - height + 0.5) * (y - height + 0.5) * 1.3 + z * z);
					if (d < 2.6) {
						put(cx + x, y, cz + z, leaves);
					}
				}
			}
		}
		for (int y = 0; y < height; y++) {
			put(cx, y, cz, Blocks.OAK_LOG.defaultBlockState());
		}
	}

	private void boulder(int cx, int cz, double r, Random rnd) {
		BlockState[] rock = {Blocks.COBBLESTONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState()};
		int n = (int) Math.ceil(r);
		for (int x = -n; x <= n; x++) {
			for (int y = 0; y <= n; y++) {
				for (int z = -n; z <= n; z++) {
					if (x * x + y * y * 1.6 + z * z <= r * r) {
						put(cx + x, y, cz + z, rock[(int) (hash(cx + x, y, cz + z) % rock.length)]);
					}
				}
			}
		}
	}

	/** The hunters' glass platform: a disc with lanterns, a low rail and four gaps to jump through. */
	private void lobby() {
		int r = LOBBY_RADIUS;
		for (int x = -r - 1; x <= r + 1; x++) {
			for (int z = -r - 1; z <= r + 1; z++) {
				double d = Math.sqrt(x * x + z * z);
				if (d <= r + 0.5) {
					boolean lamp = Math.abs(d - 4) < 0.5 && (x == 0 || z == 0 || Math.abs(x) == Math.abs(z));
					put(x, LOBBY_Y, z, lamp ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.STAINED_GLASS.white().defaultBlockState());
				} else if (d <= r + 1.5) {
					boolean gap = Math.abs(x) <= 1 || Math.abs(z) <= 1;
					if (!gap) {
						put(x, LOBBY_Y + 1, z, Blocks.GLASS.defaultBlockState());
					}
				}
			}
		}
	}
}
