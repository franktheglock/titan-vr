package dev.titanvr.physics;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Works out which blocks come away when a giant grips the world and pulls.
 *
 * Blocks near the hand form a graph; each pair of touching blocks is joined by a bond whose strength depends on
 * the materials (hard blocks bond strongly, different materials meet along weak seams - a trunk meets the dirt,
 * glass meets a wall, a house meets its foundation). A max-flow/min-cut (Dinic) between the blocks in the fist and
 * the anchored surroundings finds the cheapest set of bonds to snap; everything on the fist's side of that cut is
 * what gets torn off. Grip a trunk and the whole tree shears off at the roots.
 */
final class Shear {
	private static final double INF = 1e18;

	private final ServerLevel level;
	private final WorldCollider collider;

	Shear(ServerLevel level, WorldCollider collider) {
		this.level = level;
		this.collider = collider;
	}

	/** Coarse material groups: bonds inside a group are full strength, across groups they form seams. */
	private static int materialClass(BlockState s) {
		if (s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES)) {
			return 1; // a tree is one piece
		}
		if (Destruction.isTerrain(s)) {
			return 2;
		}
		if (s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_FENCES)) {
			return 3;
		}
		// Anything else bonds fully only with its own block type (brick to brick, glass to glass...).
		return 100 + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(s.getBlock());
	}

	private double strength(BlockState s, BlockPos pos) {
		return Math.max(0.05, s.getDestroySpeed(this.level, pos)) + 0.5;
	}

	private double bond(BlockState a, BlockPos pa, BlockState b, BlockPos pb) {
		double base = Math.min(this.strength(a, pa), this.strength(b, pb));
		return materialClass(a) == materialClass(b) ? base : base * 0.25;
	}

	/**
	 * @param hand        where the fist is
	 * @param seedRadius  blocks within this radius of the fist (of the material the fist is closest to) are held
	 * @param region      how far around the fist the cut may wander; beyond this everything counts as anchored
	 * @param maxVoxels   strength limit - bigger clusters fall back to just the seed
	 * @param maxHardness blocks harder than this can't be torn away (they anchor)
	 * @return block positions to tear off (empty if nothing grippable)
	 */
	LongArrayList solve(Vector3d hand, double seedRadius, double region, int maxVoxels, double maxHardness) {
		int r = (int) Math.ceil(region);
		int hx = (int) Math.floor(hand.x), hy = (int) Math.floor(hand.y), hz = (int) Math.floor(hand.z);
		Long2IntOpenHashMap index = new Long2IntOpenHashMap();
		index.defaultReturnValue(-1);
		LongArrayList nodes = new LongArrayList();
		IntArrayList anchored = new IntArrayList();
		BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
		java.util.ArrayList<BlockState> states = new java.util.ArrayList<>();

		// Seed: the material class of the solid block nearest the fist.
		int seedClass = Integer.MIN_VALUE;
		double nearest = Double.MAX_VALUE;
		int sr = (int) Math.ceil(seedRadius);
		for (int x = hx - sr; x <= hx + sr; x++) {
			for (int y = hy - sr; y <= hy + sr; y++) {
				for (int z = hz - sr; z <= hz + sr; z++) {
					if (!this.collider.solid(x, y, z)) {
						continue;
					}
					double d = dist2(x, y, z, hand);
					BlockState s = this.collider.state(x, y, z);
					mp.set(x, y, z);
					if (d < nearest && d <= seedRadius * seedRadius && s != null && grippable(s, mp, maxHardness)) {
						nearest = d;
						seedClass = materialClass(s);
					}
				}
			}
		}
		LongArrayList result = new LongArrayList();
		if (seedClass == Integer.MIN_VALUE) {
			return result;
		}

		// Nodes: solid blocks inside the region sphere.
		for (int x = hx - r; x <= hx + r; x++) {
			for (int y = hy - r; y <= hy + r; y++) {
				for (int z = hz - r; z <= hz + r; z++) {
					double d2 = dist2(x, y, z, hand);
					if (d2 > region * region) {
						continue;
					}
					byte type = this.collider.type(x, y, z);
					if (type != WorldCollider.FULL && type != WorldCollider.PARTIAL) {
						continue;
					}
					BlockState s = this.collider.state(x, y, z);
					int id = nodes.size();
					long key = BlockPos.asLong(x, y, z);
					index.put(key, id);
					nodes.add(key);
					states.add(s);
					mp.set(x, y, z);
					boolean edge = Math.sqrt(d2) > region - 1.0;
					if (edge || !grippable(s, mp, maxHardness)) {
						anchored.add(id);
					}
				}
			}
		}
		int n = nodes.size();
		int source = n, sink = n + 1;
		Graph g = new Graph(n + 2, n * 4 + 64);
		BlockPos.MutableBlockPos pa = new BlockPos.MutableBlockPos(), pb = new BlockPos.MutableBlockPos();
		int seedCount = 0;
		boolean[] isAnchor = new boolean[n];
		for (int i = 0; i < anchored.size(); i++) {
			isAnchor[anchored.getInt(i)] = true;
			g.add(anchored.getInt(i), sink, INF, 0);
		}
		for (int i = 0; i < n; i++) {
			long key = nodes.getLong(i);
			int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
			BlockState s = states.get(i);
			pa.set(x, y, z);
			// Bonds to the +x/+y/+z neighbours (each pair once, capacity both ways).
			for (int f = 1; f < 6; f += 2) {
				long nk = BlockPos.asLong(x + BodyShape.FACE_X[f], y + BodyShape.FACE_Y[f], z + BodyShape.FACE_Z[f]);
				int j = index.get(nk);
				if (j < 0) {
					continue;
				}
				pb.set(nk);
				double c = this.bond(s, pa, states.get(j), pb);
				g.add(i, j, c, c);
			}
			if (!isAnchor[i] && materialClass(s) == seedClass && dist2(x, y, z, hand) <= seedRadius * seedRadius) {
				g.add(source, i, INF, 0);
				seedCount++;
			}
		}
		if (seedCount == 0) {
			return result;
		}
		g.maxFlow(source, sink);
		boolean[] side = g.reachable(source);
		LongArrayList seed = new LongArrayList();
		for (int i = 0; i < n; i++) {
			if (side[i]) {
				result.add(nodes.getLong(i));
			}
			long key = nodes.getLong(i);
			if (!isAnchor[i] && materialClass(states.get(i)) == seedClass
				&& dist2(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), hand) <= seedRadius * seedRadius) {
				seed.add(key);
			}
		}
		if (result.size() > maxVoxels) {
			// Too big to tear away: just rip out what's in the fist.
			return seed.size() > maxVoxels ? new LongArrayList(seed.subList(0, maxVoxels)) : seed;
		}
		return result;
	}

	private boolean grippable(BlockState s, BlockPos pos, double maxHardness) {
		float h = s.getDestroySpeed(this.level, pos);
		return !s.hasBlockEntity() && !s.liquid() && h >= 0 && h <= maxHardness;
	}

	private static double dist2(int x, int y, int z, Vector3d p) {
		double dx = x + 0.5 - p.x, dy = y + 0.5 - p.y, dz = z + 0.5 - p.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Dinic max-flow on an adjacency-array graph. */
	private static final class Graph {
		int[] head, next, to;
		double[] cap;
		int edges;
		final int n;
		int[] level, it;

		Graph(int n, int expectedEdges) {
			this.n = n;
			this.head = new int[n];
			Arrays.fill(this.head, -1);
			int m = Math.max(16, expectedEdges * 2);
			this.next = new int[m];
			this.to = new int[m];
			this.cap = new double[m];
			this.level = new int[n];
			this.it = new int[n];
		}

		/** Edge u->v with capacity c and its paired reverse v->u with capacity rc. */
		void add(int u, int v, double c, double rc) {
			this.ensure(this.edges + 2);
			this.to[this.edges] = v;
			this.cap[this.edges] = c;
			this.next[this.edges] = this.head[u];
			this.head[u] = this.edges++;
			this.to[this.edges] = u;
			this.cap[this.edges] = rc;
			this.next[this.edges] = this.head[v];
			this.head[v] = this.edges++;
		}

		private void ensure(int m) {
			if (m > this.to.length) {
				int size = Math.max(m, this.to.length * 2);
				this.next = Arrays.copyOf(this.next, size);
				this.to = Arrays.copyOf(this.to, size);
				this.cap = Arrays.copyOf(this.cap, size);
			}
		}

		private boolean bfs(int s, int t) {
			Arrays.fill(this.level, -1);
			int[] queue = new int[this.n];
			int qh = 0, qt = 0;
			queue[qt++] = s;
			this.level[s] = 0;
			while (qh < qt) {
				int u = queue[qh++];
				for (int e = this.head[u]; e >= 0; e = this.next[e]) {
					if (this.cap[e] > 1e-9 && this.level[this.to[e]] < 0) {
						this.level[this.to[e]] = this.level[u] + 1;
						queue[qt++] = this.to[e];
					}
				}
			}
			return this.level[t] >= 0;
		}

		private double dfs(int u, int t, double f) {
			if (u == t) {
				return f;
			}
			for (; this.it[u] >= 0; this.it[u] = this.next[this.it[u]]) {
				int e = this.it[u];
				int v = this.to[e];
				if (this.cap[e] > 1e-9 && this.level[v] == this.level[u] + 1) {
					double d = this.dfs(v, t, Math.min(f, this.cap[e]));
					if (d > 1e-9) {
						this.cap[e] -= d;
						this.cap[e ^ 1] += d;
						return d;
					}
				}
			}
			return 0;
		}

		double maxFlow(int s, int t) {
			double flow = 0;
			int rounds = 0;
			while (this.bfs(s, t) && rounds++ < 10000) {
				System.arraycopy(this.head, 0, this.it, 0, this.n);
				double f;
				while ((f = this.dfs(s, t, INF)) > 1e-9) {
					flow += f;
					if (flow >= INF / 2) {
						return flow; // seed touches an anchor directly: nothing can come loose
					}
				}
			}
			return flow;
		}

		/** Nodes still reachable from s in the residual graph = the side of the min cut that comes away. */
		boolean[] reachable(int s) {
			boolean[] seen = new boolean[this.n];
			int[] stack = new int[this.n];
			int sp = 0;
			stack[sp++] = s;
			seen[s] = true;
			while (sp > 0) {
				int u = stack[--sp];
				for (int e = this.head[u]; e >= 0; e = this.next[e]) {
					if (this.cap[e] > 1e-9 && !seen[this.to[e]]) {
						seen[this.to[e]] = true;
						stack[sp++] = this.to[e];
					}
				}
			}
			return seen;
		}
	}
}
