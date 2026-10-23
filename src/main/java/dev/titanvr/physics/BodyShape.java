package dev.titanvr.physics;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3d;

/**
 * Immutable collision/mass description of a rigid body, expressed in the body's local frame whose origin is
 * the center of mass.
 *
 * Two flavours:
 *  - voxel shapes: unit cubes on an integer grid (debris, ripped chunks, collapsed buildings). The grid cell
 *    (i, j, k) spans [i, i+1] x [j, j+1] x [k, k+1] in "grid space"; local = grid + gridOrigin.
 *  - box shapes: a single box (stand-in collider for a ragdolled mob).
 */
public final class BodyShape {
	public final boolean voxel;
	/** Box data, 6 doubles per box: local center xyz, half extents xyz. */
	public final double[] boxes;
	/** Grid coordinates per voxel (3 ints per voxel); empty for box shapes. */
	public final int[] cells;
	public final BlockState[] states;
	/** Local position of the grid-space origin. */
	public final double gox, goy, goz;
	/** Surface sample points in local space, 3 doubles each. */
	public final double[] samples;
	public final double mass;
	public final Matrix3d invInertiaLocal;
	public final double radius;
	private final Long2IntOpenHashMap cellIndex;

	private BodyShape(boolean voxel, double[] boxes, int[] cells, BlockState[] states, double gox, double goy, double goz,
		double[] samples, double mass, Matrix3d invInertiaLocal, double radius, Long2IntOpenHashMap cellIndex) {
		this.voxel = voxel;
		this.boxes = boxes;
		this.cells = cells;
		this.states = states;
		this.gox = gox;
		this.goy = goy;
		this.goz = goz;
		this.samples = samples;
		this.mass = mass;
		this.invInertiaLocal = invInertiaLocal;
		this.radius = radius;
		this.cellIndex = cellIndex;
	}

	public int count() {
		return this.states.length;
	}

	public static long key(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (long) (z & 0x1FFFFF);
	}

	public boolean hasCell(int x, int y, int z) {
		return this.cellIndex != null && this.cellIndex.containsKey(key(x, y, z));
	}

	private static final java.util.Map<net.minecraft.world.level.block.Block, Double> DENSITY = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Mass of one voxel (a 1 m cube) in tonnes, i.e. the material's real density in t/m^3. A leaf block weighs
	 * 150 kg, a block of gold 19 tonnes - which is what makes a thrown gold cube devastating and a leaf harmless.
	 */
	public static double voxelMass(BlockState state) {
		if (state == null) {
			return 1.0;
		}
		return DENSITY.computeIfAbsent(state.getBlock(), b -> density(state));
	}

	private static double density(BlockState s) {
		String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
		if (s.is(net.minecraft.tags.BlockTags.LEAVES)) {
			return 0.15;
		}
		if (s.is(net.minecraft.tags.BlockTags.WOOL) || s.is(net.minecraft.tags.BlockTags.WOOL_CARPETS) || id.contains("snow")) {
			return 0.25;
		}
		if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.PLANKS) || id.contains("wood") || id.contains("bamboo")) {
			return 0.6;
		}
		if (id.equals("gold_block")) {
			return 19.3;
		}
		if (id.equals("netherite_block")) {
			return 15.0;
		}
		if (id.equals("raw_gold_block")) {
			return 10.0;
		}
		if (id.contains("copper") && !id.contains("ore")) {
			return 8.9;
		}
		if (id.equals("iron_block") || id.contains("anvil")) {
			return 7.9;
		}
		if (id.equals("raw_iron_block") || id.equals("ancient_debris") || id.equals("redstone_block")) {
			return 5.5;
		}
		if (id.equals("diamond_block") || id.equals("emerald_block") || id.equals("lapis_block")) {
			return 3.5;
		}
		if (id.contains("obsidian")) {
			return 2.6;
		}
		if (id.contains("ice")) {
			return 0.9;
		}
		if (id.contains("glass")) {
			return 2.5;
		}
		if (s.is(net.minecraft.tags.BlockTags.DIRT)) {
			return 1.3;
		}
		if (s.is(net.minecraft.tags.BlockTags.SAND) || id.contains("gravel")) {
			return 1.7;
		}
		if (id.contains("brick")) {
			return 2.0;
		}
		if (s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER) || id.contains("stone")
			|| id.contains("ore") || id.contains("deepslate")) {
			return 2.6;
		}
		float hardness = s.getBlock().defaultDestroyTime();
		if (hardness <= 0.3F) {
			return 0.3;
		}
		return hardness >= 1.5F ? 2.2 : 1.2;
	}

	/**
	 * Builds a voxel shape from grid cells. The returned array's first three entries give the grid-space position
	 * of the center of mass (so world position of the body = worldPositionOfGridOrigin + com).
	 */
	public static BodyShape voxels(List<int[]> gridCells, List<BlockState> blockStates, double[] comOut) {
		int n = gridCells.size();
		int[] cells = new int[n * 3];
		BlockState[] states = blockStates.toArray(new BlockState[0]);
		double totalMass = 0;
		double cx = 0, cy = 0, cz = 0;
		double[] masses = new double[n];
		Long2IntOpenHashMap index = new Long2IntOpenHashMap(n * 2);
		index.defaultReturnValue(-1);
		for (int i = 0; i < n; i++) {
			int[] c = gridCells.get(i);
			cells[i * 3] = c[0];
			cells[i * 3 + 1] = c[1];
			cells[i * 3 + 2] = c[2];
			index.put(key(c[0], c[1], c[2]), i);
			double m = voxelMass(states[i]);
			masses[i] = m;
			totalMass += m;
			cx += (c[0] + 0.5) * m;
			cy += (c[1] + 0.5) * m;
			cz += (c[2] + 0.5) * m;
		}
		cx /= totalMass;
		cy /= totalMass;
		cz /= totalMass;
		comOut[0] = cx;
		comOut[1] = cy;
		comOut[2] = cz;
		double gox = -cx, goy = -cy, goz = -cz;

		// Inertia tensor: each voxel is a unit cube (I = m/6 on the diagonal) plus the parallel-axis term.
		double ixx = 0, iyy = 0, izz = 0, ixy = 0, ixz = 0, iyz = 0;
		double[] boxes = new double[n * 6];
		double radius = 0;
		for (int i = 0; i < n; i++) {
			double m = masses[i];
			double x = cells[i * 3] + 0.5 + gox;
			double y = cells[i * 3 + 1] + 0.5 + goy;
			double z = cells[i * 3 + 2] + 0.5 + goz;
			boxes[i * 6] = x;
			boxes[i * 6 + 1] = y;
			boxes[i * 6 + 2] = z;
			boxes[i * 6 + 3] = 0.5;
			boxes[i * 6 + 4] = 0.5;
			boxes[i * 6 + 5] = 0.5;
			ixx += m * (y * y + z * z + 1.0 / 6.0);
			iyy += m * (x * x + z * z + 1.0 / 6.0);
			izz += m * (x * x + y * y + 1.0 / 6.0);
			ixy -= m * x * y;
			ixz -= m * x * z;
			iyz -= m * y * z;
			radius = Math.max(radius, Math.sqrt(x * x + y * y + z * z) + 0.87);
		}
		Matrix3d inertia = new Matrix3d(ixx, ixy, ixz, ixy, iyy, iyz, ixz, iyz, izz);
		Matrix3d inv = new Matrix3d(inertia).invert();

		// Surface sample points: lattice corners touched by at least one voxel but not buried in voxels on all sides,
		// plus centers of exposed faces (catches block edges poking into big flat faces).
		LongOpenHashSet seen = new LongOpenHashSet();
		it.unimi.dsi.fastutil.doubles.DoubleArrayList samples = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
		for (int i = 0; i < n; i++) {
			int vx = cells[i * 3], vy = cells[i * 3 + 1], vz = cells[i * 3 + 2];
			for (int dx = 0; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = 0; dz <= 1; dz++) {
						int px = vx + dx, py = vy + dy, pz = vz + dz;
						long k = key(px, py, pz);
						if (!seen.add(k)) {
							continue;
						}
						int around = 0;
						for (int ax = -1; ax <= 0; ax++) {
							for (int ay = -1; ay <= 0; ay++) {
								for (int az = -1; az <= 0; az++) {
									if (index.containsKey(key(px + ax, py + ay, pz + az))) {
										around++;
									}
								}
							}
						}
						if (around < 8) {
							samples.add(px + gox);
							samples.add(py + goy);
							samples.add(pz + goz);
						}
					}
				}
			}
			if (n <= 600) {
				for (int f = 0; f < 6; f++) {
					int ox = FACE_X[f], oy = FACE_Y[f], oz = FACE_Z[f];
					if (!index.containsKey(key(vx + ox, vy + oy, vz + oz))) {
						samples.add(vx + 0.5 + ox * 0.5 + gox);
						samples.add(vy + 0.5 + oy * 0.5 + goy);
						samples.add(vz + 0.5 + oz * 0.5 + goz);
					}
				}
			}
		}
		// Pull samples slightly inwards so a body carved out of the world isn't wedged by zero-depth contacts
		// against the blocks it was flush with.
		double[] s = samples.toDoubleArray();
		for (int i = 0; i < s.length; i += 3) {
			double len = Math.sqrt(s[i] * s[i] + s[i + 1] * s[i + 1] + s[i + 2] * s[i + 2]);
			if (len > 0.05) {
				double f = (len - 0.02) / len;
				s[i] *= f;
				s[i + 1] *= f;
				s[i + 2] *= f;
			}
		}
		return new BodyShape(true, boxes, cells, states, gox, goy, goz, s, totalMass, inv, radius, index);
	}

	/** A single solid box, used as the collider of a rigidly-ragdolled mob. */
	public static BodyShape box(double hx, double hy, double hz, double mass) {
		double[] boxes = {0, 0, 0, hx, hy, hz};
		double ixx = mass / 3.0 * (hy * hy + hz * hz);
		double iyy = mass / 3.0 * (hx * hx + hz * hz);
		double izz = mass / 3.0 * (hx * hx + hy * hy);
		Matrix3d inv = new Matrix3d(1 / ixx, 0, 0, 0, 1 / iyy, 0, 0, 0, 1 / izz);
		it.unimi.dsi.fastutil.doubles.DoubleArrayList s = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if (x == 0 && y == 0 && z == 0) {
						continue;
					}
					s.add(x * hx);
					s.add(y * hy);
					s.add(z * hz);
				}
			}
		}
		double radius = Math.sqrt(hx * hx + hy * hy + hz * hz);
		return new BodyShape(false, boxes, new int[0], new BlockState[] {null}, 0, 0, 0, s.toDoubleArray(), mass, inv, radius, null);
	}

	static final int[] FACE_X = {-1, 1, 0, 0, 0, 0};
	static final int[] FACE_Y = {0, 0, -1, 1, 0, 0};
	static final int[] FACE_Z = {0, 0, 0, 0, -1, 1};

	/**
	 * Tests whether a local-space point is inside the shape. If so writes the outward local normal (xyz) and
	 * penetration depth into out[0..3] and returns true.
	 */
	public boolean penetration(double lx, double ly, double lz, double[] out) {
		if (!this.voxel) {
			double hx = this.boxes[3], hy = this.boxes[4], hz = this.boxes[5];
			double ax = hx - Math.abs(lx), ay = hy - Math.abs(ly), az = hz - Math.abs(lz);
			if (ax <= 0 || ay <= 0 || az <= 0) {
				return false;
			}
			if (ax < ay && ax < az) {
				out[0] = Math.signum(lx);
				out[1] = 0;
				out[2] = 0;
				out[3] = ax;
			} else if (ay < az) {
				out[0] = 0;
				out[1] = Math.signum(ly);
				out[2] = 0;
				out[3] = ay;
			} else {
				out[0] = 0;
				out[1] = 0;
				out[2] = Math.signum(lz);
				out[3] = az;
			}
			return true;
		}
		double gx = lx - this.gox, gy = ly - this.goy, gz = lz - this.goz;
		int cx = (int) Math.floor(gx), cy = (int) Math.floor(gy), cz = (int) Math.floor(gz);
		if (!this.hasCell(cx, cy, cz)) {
			return false;
		}
		double fx = gx - cx, fy = gy - cy, fz = gz - cz;
		double best = Double.MAX_VALUE;
		int bestFace = -1;
		double[] depth = {fx, 1 - fx, fy, 1 - fy, fz, 1 - fz};
		for (int f = 0; f < 6; f++) {
			if (depth[f] < best && !this.hasCell(cx + FACE_X[f], cy + FACE_Y[f], cz + FACE_Z[f])) {
				best = depth[f];
				bestFace = f;
			}
		}
		if (bestFace < 0) {
			// Fully buried point: push out along the nearest face anyway.
			for (int f = 0; f < 6; f++) {
				if (depth[f] < best) {
					best = depth[f];
					bestFace = f;
				}
			}
		}
		out[0] = FACE_X[bestFace];
		out[1] = FACE_Y[bestFace];
		out[2] = FACE_Z[bestFace];
		out[3] = best;
		return true;
	}

	/**
	 * Sphere (local center, radius r) vs shape. Writes outward local normal + depth; returns true on overlap.
	 */
	public boolean spherePenetration(double lx, double ly, double lz, double r, double[] out) {
		if (!this.voxel) {
			double hx = this.boxes[3], hy = this.boxes[4], hz = this.boxes[5];
			double qx = Math.max(-hx, Math.min(hx, lx));
			double qy = Math.max(-hy, Math.min(hy, ly));
			double qz = Math.max(-hz, Math.min(hz, lz));
			double dx = lx - qx, dy = ly - qy, dz = lz - qz;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > r * r) {
				return false;
			}
			if (d2 > 1e-10) {
				double d = Math.sqrt(d2);
				out[0] = dx / d;
				out[1] = dy / d;
				out[2] = dz / d;
				out[3] = r - d;
				return true;
			}
			if (this.penetration(lx, ly, lz, out)) {
				out[3] += r;
				return true;
			}
			return false;
		}
		double gx = lx - this.gox, gy = ly - this.goy, gz = lz - this.goz;
		int x0 = (int) Math.floor(gx - r), x1 = (int) Math.floor(gx + r);
		int y0 = (int) Math.floor(gy - r), y1 = (int) Math.floor(gy + r);
		int z0 = (int) Math.floor(gz - r), z1 = (int) Math.floor(gz + r);
		double bestDepth = 0;
		boolean hit = false;
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					if (!this.hasCell(x, y, z)) {
						continue;
					}
					double qx = Math.max(x, Math.min(x + 1, gx));
					double qy = Math.max(y, Math.min(y + 1, gy));
					double qz = Math.max(z, Math.min(z + 1, gz));
					double dx = gx - qx, dy = gy - qy, dz = gz - qz;
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
					} else {
						double[] tmp = new double[4];
						if (!this.penetration(lx, ly, lz, tmp)) {
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
						hit = true;
					}
				}
			}
		}
		return hit;
	}
}
