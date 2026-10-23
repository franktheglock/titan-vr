package dev.titanvr.physics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Cached view of the voxel world for collision queries. A cell is EMPTY, FULL (unit cube), PARTIAL (collides
 * with the bounding box of its collision shape, e.g. slabs/fences) or UNLOADED. The cache is flushed every tick
 * and invalidated on any block change Titan makes itself.
 */
public final class WorldCollider {
	public static final byte EMPTY = 0;
	public static final byte FULL = 1;
	public static final byte PARTIAL = 2;
	public static final byte UNLOADED = 3;

	private static final class Cell {
		final byte type;
		final double x0, y0, z0, x1, y1, z1;
		final BlockState state;

		Cell(byte type, AABB box, BlockState state) {
			this.type = type;
			this.state = state;
			if (box != null) {
				this.x0 = Math.max(0, box.minX);
				this.y0 = Math.max(0, box.minY);
				this.z0 = Math.max(0, box.minZ);
				this.x1 = Math.min(1, box.maxX);
				this.y1 = Math.min(1, box.maxY);
				this.z1 = Math.min(1, box.maxZ);
			} else {
				this.x0 = this.y0 = this.z0 = 0;
				this.x1 = this.y1 = this.z1 = 1;
			}
		}
	}

	private static final Cell EMPTY_CELL = new Cell(EMPTY, null, null);
	private static final Cell UNLOADED_CELL = new Cell(UNLOADED, null, null);

	private final ServerLevel level;
	private final Long2ObjectOpenHashMap<Cell> cache = new Long2ObjectOpenHashMap<>();
	private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();

	public WorldCollider(ServerLevel level) {
		this.level = level;
	}

	public void clear() {
		this.cache.clear();
	}

	public void invalidate(BlockPos pos) {
		this.cache.remove(pos.asLong());
	}

	private Cell cell(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Cell c = this.cache.get(key);
		if (c != null) {
			return c;
		}
		this.mpos.set(x, y, z);
		if (y < this.level.getMinY() || y > this.level.getMaxY()) {
			c = EMPTY_CELL;
		} else if (!this.level.hasChunkAt(this.mpos)) {
			c = UNLOADED_CELL;
		} else {
			BlockState state = this.level.getBlockState(this.mpos);
			if (state.isAir()) {
				c = EMPTY_CELL;
			} else {
				VoxelShape shape = state.getCollisionShape(this.level, this.mpos);
				if (shape.isEmpty()) {
					c = new Cell(EMPTY, null, state);
				} else if (state.isCollisionShapeFullBlock(this.level, this.mpos)) {
					c = new Cell(FULL, null, state);
				} else {
					c = new Cell(PARTIAL, shape.bounds(), state);
				}
			}
		}
		this.cache.put(key, c);
		return c;
	}

	public byte type(int x, int y, int z) {
		return this.cell(x, y, z).type;
	}

	public BlockState state(int x, int y, int z) {
		Cell c = this.cell(x, y, z);
		return c.state;
	}

	public static final int NO_FLUID = 0, WATER = 1, LAVA = 2;

	/** Which fluid (if any) the point is submerged in. */
	public int fluid(double px, double py, double pz) {
		int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
		BlockState st = this.cell(cx, cy, cz).state;
		if (st == null) {
			return NO_FLUID;
		}
		FluidState fs = st.getFluidState();
		if (fs.isEmpty()) {
			return NO_FLUID;
		}
		BlockState above = this.cell(cx, cy + 1, cz).state;
		boolean full = above != null && !above.getFluidState().isEmpty();
		if (!full && py - cy > fs.getOwnHeight() + 0.1) {
			return NO_FLUID;
		}
		return fs.is(FluidTags.LAVA) ? LAVA : WATER;
	}

	public boolean solid(int x, int y, int z) {
		byte t = this.cell(x, y, z).type;
		return t == FULL || t == PARTIAL;
	}

	/**
	 * Point vs world. On penetration writes outward normal (xyz), depth and the cell coordinates into out
	 * (out[0..3] normal+depth, out[4..6] cell) and returns true. Unloaded cells also report via returning false
	 * and setting out[7] = 1 so callers can freeze bodies at the edge of the loaded world.
	 */
	public boolean pointContact(double px, double py, double pz, double[] out) {
		int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
		Cell c = this.cell(cx, cy, cz);
		out[7] = 0;
		if (c.type == EMPTY) {
			return false;
		}
		if (c.type == UNLOADED) {
			out[7] = 1;
			return false;
		}
		double fx = px - cx, fy = py - cy, fz = pz - cz;
		double best = Double.MAX_VALUE;
		int face = -1;
		if (c.type == FULL) {
			double[] depth = {fx, 1 - fx, fy, 1 - fy, fz, 1 - fz};
			for (int f = 0; f < 6; f++) {
				if (depth[f] < best && !isFull(cx + BodyShape.FACE_X[f], cy + BodyShape.FACE_Y[f], cz + BodyShape.FACE_Z[f])) {
					best = depth[f];
					face = f;
				}
			}
			if (face < 0) {
				// Buried: pop upwards through the column.
				face = 3;
				best = 1 - fy;
			}
		} else {
			if (fx < c.x0 || fx > c.x1 || fy < c.y0 || fy > c.y1 || fz < c.z0 || fz > c.z1) {
				return false;
			}
			double[] depth = {fx - c.x0, c.x1 - fx, fy - c.y0, c.y1 - fy, fz - c.z0, c.z1 - fz};
			for (int f = 0; f < 6; f++) {
				if (depth[f] < best) {
					best = depth[f];
					face = f;
				}
			}
		}
		out[0] = BodyShape.FACE_X[face];
		out[1] = BodyShape.FACE_Y[face];
		out[2] = BodyShape.FACE_Z[face];
		out[3] = best;
		out[4] = cx;
		out[5] = cy;
		out[6] = cz;
		return true;
	}

	private boolean isFull(int x, int y, int z) {
		return this.cell(x, y, z).type == FULL;
	}

	/**
	 * Sphere vs world, resolving against the deepest overlapping cell. Same output layout as pointContact.
	 */
	public boolean sphereContact(double px, double py, double pz, double r, double[] out) {
		int x0 = (int) Math.floor(px - r), x1 = (int) Math.floor(px + r);
		int y0 = (int) Math.floor(py - r), y1 = (int) Math.floor(py + r);
		int z0 = (int) Math.floor(pz - r), z1 = (int) Math.floor(pz + r);
		double bestDepth = 0;
		boolean hit = false;
		out[7] = 0;
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					Cell c = this.cell(x, y, z);
					if (c.type == EMPTY) {
						continue;
					}
					if (c.type == UNLOADED) {
						out[7] = 1;
						continue;
					}
					double bx0 = x + c.x0, by0 = y + c.y0, bz0 = z + c.z0;
					double bx1 = x + c.x1, by1 = y + c.y1, bz1 = z + c.z1;
					double qx = Math.max(bx0, Math.min(bx1, px));
					double qy = Math.max(by0, Math.min(by1, py));
					double qz = Math.max(bz0, Math.min(bz1, pz));
					double dx = px - qx, dy = py - qy, dz = pz - qz;
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 >= r * r) {
						continue;
					}
					double nx, ny, nz, depth;
					if (d2 > 1e-10) {
						double d = Math.sqrt(d2);
						nx = dx / d;
						ny = dy / d;
						nz = dz / d;
						depth = r - d;
						// Ignore contacts against faces buried between two full cells (avoids snagging on seams).
						if (c.type == FULL) {
							int sx = (int) Math.signum(Math.round(nx)), sy = (int) Math.signum(Math.round(ny)), sz = (int) Math.signum(Math.round(nz));
							if ((sx != 0 || sy != 0 || sz != 0) && Math.abs(sx) + Math.abs(sy) + Math.abs(sz) == 1 && isFull(x + sx, y + sy, z + sz)) {
								continue;
							}
						}
					} else {
						double[] tmp = new double[8];
						if (!this.pointContact(px, py, pz, tmp)) {
							continue;
						}
						nx = tmp[0];
						ny = tmp[1];
						nz = tmp[2];
						depth = tmp[3] + r;
					}
					if (depth > bestDepth) {
						bestDepth = depth;
						out[0] = nx;
						out[1] = ny;
						out[2] = nz;
						out[3] = depth;
						out[4] = x;
						out[5] = y;
						out[6] = z;
						hit = true;
					}
				}
			}
		}
		return hit;
	}
}
