package dev.titanvr.physics;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A particle skeleton for a ragdoll. Particles sit on the pivots of the entity's vanilla model so the client can
 * pose the model's parts from them. Canonical body frame: +X = the body's left, +Y = up, +Z = forward, feet at
 * y = 0, one unit = one block for a scale-1 entity.
 *
 * Particles flagged as torso move as one rigid piece (every pair is constrained), which is what the model's root
 * follows. Every other particle hangs off the skeleton by its links and drives one model part through a bone.
 */
public final class Rig {
	/** A model part (a direct child of the model root) that points from one particle towards another. */
	public record Bone(String part, int from, int to) {
	}

	private static final List<Rig> BY_ID = new ArrayList<>();

	public final int id;
	public final String name;
	/** Extra world scale on top of the entity's own scale (models baked at a different size). */
	public final double scale;
	public final int n;
	public final double[] rest;
	public final double[] radius;
	public final double[] mass;
	public final double[] impactWeight;
	public final int[] torso;
	/** The particle the entity's eyes follow. */
	public final int head;
	final int[][] rigid;
	final double[][] min;
	public final Bone[] bones;
	/** Torso centroid at rest and the inverse of the torso's rest covariance, for fitting its rotation. */
	public final Vector3d restCentroid = new Vector3d();
	private final Matrix3d invCov = new Matrix3d();

	private Rig(String name, double scale, double[] rest, double[] radius, double[] mass, double[] impactWeight, int[] torso, int head,
		int[][] rigid, double[][] min, Bone[] bones) {
		this.id = BY_ID.size();
		this.name = name;
		this.scale = scale;
		this.n = radius.length;
		this.rest = rest;
		this.radius = radius;
		this.mass = mass;
		this.impactWeight = impactWeight;
		this.torso = torso;
		this.head = head;
		this.rigid = rigid;
		this.min = min;
		this.bones = bones;
		for (int i : torso) {
			this.restCentroid.add(rest[i * 3], rest[i * 3 + 1], rest[i * 3 + 2]);
		}
		this.restCentroid.div(torso.length);
		Matrix3d cov = new Matrix3d().zero();
		for (int i : torso) {
			double[] r = {rest[i * 3] - this.restCentroid.x, rest[i * 3 + 1] - this.restCentroid.y, rest[i * 3 + 2] - this.restCentroid.z};
			for (int row = 0; row < 3; row++) {
				for (int col = 0; col < 3; col++) {
					cov.set(col, row, cov.get(col, row) + r[row] * r[col]);
				}
			}
		}
		this.invCov.set(cov).invert();
		BY_ID.add(this);
	}

	public static Rig byId(int id) {
		return id >= 0 && id < BY_ID.size() ? BY_ID.get(id) : null;
	}

	public double restDistance(int a, int b) {
		double dx = this.rest[a * 3] - this.rest[b * 3], dy = this.rest[a * 3 + 1] - this.rest[b * 3 + 1], dz = this.rest[a * 3 + 2] - this.rest[b * 3 + 2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Best-fit rotation (canonical body frame -> world) of the torso particles in x. Least-squares linear fit of
	 * the rest shape onto the current one, then polar decomposition to strip the shear.
	 */
	public Quaterniond torsoRotation(double[] x, Quaterniond out) {
		double cx = 0, cy = 0, cz = 0;
		for (int i : this.torso) {
			cx += x[i * 3];
			cy += x[i * 3 + 1];
			cz += x[i * 3 + 2];
		}
		cx /= this.torso.length;
		cy /= this.torso.length;
		cz /= this.torso.length;
		Matrix3d a = new Matrix3d().zero();
		for (int i : this.torso) {
			double[] p = {x[i * 3] - cx, x[i * 3 + 1] - cy, x[i * 3 + 2] - cz};
			double[] r = {this.rest[i * 3] - this.restCentroid.x, this.rest[i * 3 + 1] - this.restCentroid.y, this.rest[i * 3 + 2] - this.restCentroid.z};
			for (int row = 0; row < 3; row++) {
				for (int col = 0; col < 3; col++) {
					a.set(col, row, a.get(col, row) + p[row] * r[col]);
				}
			}
		}
		Matrix3d m = a.mul(this.invCov);
		// Newton iteration for the polar decomposition: M <- (M + M^-T) / 2.
		Matrix3d inv = new Matrix3d();
		for (int it = 0; it < 8; it++) {
			if (Math.abs(m.determinant()) < 1e-12) {
				break;
			}
			m.invert(inv).transpose();
			m.add(inv).scale(0.5);
		}
		if (m.determinant() <= 0) {
			return out.identity();
		}
		return out.setFromNormalized(m);
	}

	// ======================================================================== rig selection

	public static Rig forEntity(LivingEntity e) {
		if (e instanceof net.minecraft.world.entity.player.Player
			|| e instanceof net.minecraft.world.entity.monster.zombie.Zombie
			|| e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
			|| e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) {
			return HUMANOID;
		}
		if (e.isBaby()) {
			// Babies use separate, differently shaped models.
			return null;
		}
		if (e instanceof net.minecraft.world.entity.npc.villager.AbstractVillager || e instanceof net.minecraft.world.entity.monster.Witch
			|| e instanceof net.minecraft.world.entity.monster.illager.AbstractIllager) {
			return VILLAGER;
		}
		if (e instanceof net.minecraft.world.entity.animal.cow.AbstractCow) {
			return COW;
		}
		if (e instanceof net.minecraft.world.entity.animal.sheep.Sheep) {
			return SHEEP;
		}
		if (e instanceof net.minecraft.world.entity.animal.pig.Pig) {
			return PIG;
		}
		if (e instanceof net.minecraft.world.entity.monster.Creeper) {
			return CREEPER;
		}
		if (e instanceof net.minecraft.world.entity.monster.spider.CaveSpider) {
			return CAVE_SPIDER;
		}
		if (e instanceof net.minecraft.world.entity.monster.spider.Spider) {
			return SPIDER;
		}
		if (e instanceof net.minecraft.world.entity.animal.wolf.Wolf) {
			return WOLF;
		}
		if (e instanceof net.minecraft.world.entity.animal.chicken.Chicken) {
			return CHICKEN;
		}
		if (e instanceof net.minecraft.world.entity.animal.fox.Fox) {
			return FOX;
		}
		if (e instanceof net.minecraft.world.entity.animal.feline.Ocelot) {
			return OCELOT;
		}
		if (e instanceof net.minecraft.world.entity.animal.feline.Cat) {
			return CAT;
		}
		if (e instanceof net.minecraft.world.entity.animal.golem.IronGolem) {
			return IRON_GOLEM;
		}
		return null;
	}

	// ======================================================================== definitions

	public static final int H_HEAD = 0, H_L_SHOULDER = 1, H_R_SHOULDER = 2, H_L_HIP = 3, H_R_HIP = 4;
	public static final int H_L_HAND = 5, H_R_HAND = 6, H_L_FOOT = 7, H_R_FOOT = 8, H_CHEST = 9;

	/** Players, zombies, skeletons, piglins. Hand-tuned joint limits; posed by its own client solver. */
	public static final Rig HUMANOID = new Rig("humanoid", 1.0,
		new double[] {
			0, 1.75, 0, // head center
			0.3125, 1.375, 0, // left shoulder (arm pivot)
			-0.3125, 1.375, 0, // right shoulder
			0.11875, 0.75, 0, // left hip (leg pivot)
			-0.11875, 0.75, 0, // right hip
			0.3125, 0.75, 0, // left hand
			-0.3125, 0.75, 0, // right hand
			0.11875, 0.0, 0, // left foot
			-0.11875, 0.0, 0, // right foot
			0, 1.06, 0.16 // chest (front of torso, gives the torso depth so it can't fold)
		},
		new double[] {0.25, 0.13, 0.13, 0.13, 0.13, 0.11, 0.11, 0.11, 0.11, 0.15},
		new double[] {1.0, 2.0, 2.0, 2.0, 2.0, 0.5, 0.5, 0.8, 0.8, 2.0},
		new double[] {1.0, 0.9, 0.9, 0.9, 0.9, 0.55, 0.55, 0.7, 0.7, 0.9},
		new int[] {H_L_SHOULDER, H_R_SHOULDER, H_L_HIP, H_R_HIP, H_CHEST},
		H_HEAD,
		new int[][] {
			{H_L_SHOULDER, H_R_SHOULDER}, {H_L_SHOULDER, H_L_HIP}, {H_L_SHOULDER, H_R_HIP}, {H_R_SHOULDER, H_L_HIP}, {H_R_SHOULDER, H_R_HIP},
			{H_L_HIP, H_R_HIP}, {H_CHEST, H_L_SHOULDER}, {H_CHEST, H_R_SHOULDER}, {H_CHEST, H_L_HIP}, {H_CHEST, H_R_HIP},
			{H_HEAD, H_L_SHOULDER}, {H_HEAD, H_R_SHOULDER}, {H_HEAD, H_CHEST},
			{H_L_HAND, H_L_SHOULDER}, {H_R_HAND, H_R_SHOULDER}, {H_L_FOOT, H_L_HIP}, {H_R_FOOT, H_R_HIP}
		},
		new double[][] {
			{H_L_FOOT, H_R_FOOT, 0.14}, {H_L_FOOT, H_R_HIP, 0.2}, {H_R_FOOT, H_L_HIP, 0.2},
			{H_L_FOOT, H_L_SHOULDER, 0.55}, {H_R_FOOT, H_R_SHOULDER, 0.55}, {H_L_FOOT, H_CHEST, 0.3}, {H_R_FOOT, H_CHEST, 0.3},
			{H_L_HAND, H_R_SHOULDER, 0.28}, {H_R_HAND, H_L_SHOULDER, 0.28}, {H_L_HAND, H_CHEST, 0.12}, {H_R_HAND, H_CHEST, 0.12},
			{H_L_HAND, H_HEAD, 0.3}, {H_R_HAND, H_HEAD, 0.3}
		},
		new Bone[0]);

	/** Villagers, wandering traders, witches, illagers: crossed arms stay on the torso; head and legs swing. */
	public static final Rig VILLAGER = new Builder("villager", 0.9375, 13.6)
		.torso("l_shoulder", 4, 1, 0, 2.5).torso("r_shoulder", -4, 1, 0, 2.5).torso("chest", 0, 6, -3, 3).torso("back", 0, 6, 3, 3)
		.torso("l_hip", 2, 12, 0, 2).torso("r_hip", -2, 12, 0, 2).torso("neck", 0, 0, 0, 2)
		.limb("head", "neck", 0, -5, 0, 4.5, 1.0)
		.limb("left_leg", "l_hip", 2, 24, 0, 2, 0.7)
		.limb("right_leg", "r_hip", -2, 24, 0, 2, 0.7)
		.build();

	public static final Rig COW = quadruped("cow", 26, 4, 12, 7, -5, 3, 12, -8, 10, 0, 4, -8, 0, 4, -11, 4);
	public static final Rig SHEEP = quadruped("sheep", 20, 3, 12, 7, -5, 6, 12, -8, 8, 0, 6, -8, 0, 5, -10, 3.5);
	public static final Rig PIG = quadruped("pig", 18, 3, 18, 7, -5, 10, 18, -8, 8, 0, 12, -6, 0, 12, -10, 4);

	public static final Rig CREEPER = new Builder("creeper", 1.0, 14)
		.torso("l_shoulder", 3, 8, 0, 2.5).torso("r_shoulder", -3, 8, 0, 2.5).torso("neck", 0, 6, 0, 2)
		.torso("lh", 2, 18, 4, 1.5).torso("rh", -2, 18, 4, 1.5).torso("lf", 2, 18, -4, 1.5).torso("rf", -2, 18, -4, 1.5)
		.torso("belly", 0, 14, 0, 3.5)
		.limb("head", "neck", 0, 2, 0, 4, 1.0)
		.limb("left_hind_leg", "lh", 2, 24, 4, 2, 0.6)
		.limb("right_hind_leg", "rh", -2, 24, 4, 2, 0.6)
		.limb("left_front_leg", "lf", 2, 24, -4, 2, 0.6)
		.limb("right_front_leg", "rf", -2, 24, -4, 2, 0.6)
		.build();

	public static final Rig SPIDER = spider("spider", 1.0);
	public static final Rig CAVE_SPIDER = spider("cave_spider", 0.7);

	public static final Rig WOLF = new Builder("wolf", 1.0, 12)
		.torso("lh", 1.5, 16, 7, 1.5).torso("rh", -1.5, 16, 7, 1.5).torso("lf", 1.5, 16, -4, 1.5).torso("rf", -1.5, 16, -4, 1.5)
		.torso("spine_f", 0, 11, -3, 3).torso("spine_b", 0, 12, 7, 2.5).torso("neck", -1, 13.5, -7, 2)
		.limb("head", "neck", 0, 13.5, -10, 3, 1.0)
		.limb("left_hind_leg", "lh", 1.5, 24, 7, 1.2, 0.6)
		.limb("right_hind_leg", "rh", -1.5, 24, 7, 1.2, 0.6)
		.limb("left_front_leg", "lf", 1.5, 24, -4, 1.2, 0.6)
		.limb("right_front_leg", "rf", -1.5, 24, -4, 1.2, 0.6)
		.build();

	public static final Rig CHICKEN = new Builder("chicken", 1.0, 4)
		.torso("lh", 1.5, 19, -0.5, 1).torso("rh", -1.5, 19, -0.5, 1).torso("top", 0, 13.5, 0, 2.5)
		.torso("front", 0, 16, -3.5, 2).torso("back", 0, 16, 3.5, 2).torso("neck", 0, 15, -4, 1.5)
		.limb("head", "neck", 0, 11, -4.5, 2.2, 1.0)
		.limb("left_leg", "lh", 1.5, 24, -0.5, 1, 0.6)
		.limb("right_leg", "rh", -1.5, 24, -0.5, 1, 0.6)
		.build();

	public static final Rig FOX = new Builder("fox", 1.0, 8)
		.torso("lh", 2, 18, 7, 1.2).torso("rh", -2, 18, 7, 1.2).torso("lf", 2, 18, 0, 1.2).torso("rf", -2, 18, 0, 1.2)
		.torso("spine_f", 0, 14.5, 0, 2.5).torso("spine_b", 0, 14.5, 7, 2.5).torso("neck", -1, 16.5, -3, 1.5)
		.limb("head", "neck", 0, 17.5, -6, 3, 1.0)
		.limb("left_hind_leg", "lh", 2, 24, 7, 1, 0.6)
		.limb("right_hind_leg", "rh", -2, 24, 7, 1, 0.6)
		.limb("left_front_leg", "lf", 2, 24, 0, 1, 0.6)
		.limb("right_front_leg", "rf", -2, 24, 0, 1, 0.6)
		.build();

	public static final Rig CAT = feline("cat", 0.8);
	public static final Rig OCELOT = feline("ocelot", 1.0);

	public static final Rig IRON_GOLEM = new Builder("iron_golem", 1.0, 60)
		.torso("l_shoulder", 11, -7, 0, 3).torso("r_shoulder", -11, -7, 0, 3).torso("chest", 0, -3, -5, 5).torso("back", 0, -3, 4, 5)
		.torso("waist", 0, 5, 0, 4).torso("lh", 4.5, 8, -0.5, 3).torso("rh", -4.5, 8, -0.5, 3).torso("neck", 0, -7, -2, 3)
		.limb("head", "neck", 0, -14, -3.5, 4, 1.0)
		.limb("left_arm", "l_shoulder", 11, 20, 0, 2.5, 0.6)
		.limb("right_arm", "r_shoulder", -11, 20, 0, 2.5, 0.6)
		.limb("left_leg", "lh", 4.5, 24, -0.5, 3, 0.7)
		.limb("right_leg", "rh", -4.5, 24, -0.5, 3, 0.7)
		.build();

	/**
	 * Four-legged QuadrupedModel animals. Leg pivots at (+-legX, legY, hindZ / frontZ), feet at y = 24, body
	 * spanning bodyTop..bodyBottom (model y) and bodyFront..bodyBack (model z), head pivot and head center.
	 */
	private static Rig quadruped(String name, double totalMass, double legX, double legY, double hindZ, double frontZ, double bodyTop,
		double bodyBottom, double bodyFront, double bodyBack, double neckX, double neckY, double neckZ, double headX, double headY, double headZ,
		double headR) {
		double bodyR = Math.min(5, (bodyBottom - bodyTop) * 0.45);
		double legR = Math.max(1.2, legX * 0.5);
		return new Builder(name, 1.0, totalMass)
			.torso("lh", legX, legY, hindZ, legR).torso("rh", -legX, legY, hindZ, legR)
			.torso("lf", legX, legY, frontZ, legR).torso("rf", -legX, legY, frontZ, legR)
			.torso("spine_f", 0, bodyTop + bodyR, bodyFront + bodyR, bodyR).torso("spine_b", 0, bodyTop + bodyR, bodyBack - bodyR, bodyR)
			.torso("neck", neckX, neckY, neckZ, 2)
			.limb("head", "neck", headX, headY, headZ, headR, 1.0)
			.limb("left_hind_leg", "lh", legX, 24, hindZ, legR, 0.6)
			.limb("right_hind_leg", "rh", -legX, 24, hindZ, legR, 0.6)
			.limb("left_front_leg", "lf", legX, 24, frontZ, legR, 0.6)
			.limb("right_front_leg", "rf", -legX, 24, frontZ, legR, 0.6)
			.build();
	}

	private static Rig spider(String name, double scale) {
		Builder b = new Builder(name, scale, 16)
			.torso("thorax", 0, 15, 0, 3).torso("abdomen", 0, 15, 9, 4.5).torso("abdomen_top", 0, 12, 9, 3.5).torso("neck", 0, 15, -3, 2);
		String[] parts = {"hind", "middle_hind", "middle_front", "front"};
		double[] zs = {2, 1, 0, -1};
		double[] yRots = {Math.PI / 4, Math.PI / 8, -Math.PI / 8, -Math.PI / 4};
		double[] zRots = {Math.PI / 4, 0.58119464, 0.58119464, Math.PI / 4};
		for (int i = 0; i < 4; i++) {
			for (int side = -1; side <= 1; side += 2) {
				// Left legs point along model +X, right legs along -X; vanilla mirrors the rotations per side.
				String prefix = side > 0 ? "left_" : "right_";
				String anchor = prefix + parts[i] + "_anchor";
				b.torso(anchor, 4 * side, 15, zs[i], 1.5);
				Vector3d end = new Vector3d(15 * side, 0, 0);
				new Quaterniond().rotationZYX(zRots[i] * side, -yRots[i] * side, 0).transform(end);
				b.limb(prefix + parts[i] + "_leg", anchor, 4 * side + end.x, 15 + end.y, zs[i] + end.z, 1.2, 0.5);
			}
		}
		b.limb("head", "neck", 0, 15, -7, 4, 1.0);
		return b.build();
	}

	private static Rig feline(String name, double scale) {
		return new Builder(name, scale, 4)
			.torso("lh", 1.1, 18, 7, 1.1).torso("rh", -1.1, 18, 7, 1.1).torso("lf", 1.2, 14.1, -4, 1.1).torso("rf", -1.2, 14.1, -4, 1.1)
			.torso("spine_f", 0, 15, -5, 2).torso("spine_b", 0, 15, 6, 2).torso("neck", 0, 15, -9, 1.5)
			.limb("head", "neck", 0, 15.5, -11, 2.5, 1.0)
			.limb("left_hind_leg", "lh", 1.1, 24, 7, 1, 0.6)
			.limb("right_hind_leg", "rh", -1.1, 24, 7, 1, 0.6)
			.limb("left_front_leg", "lf", 1.2, 24.1, -4, 1, 0.6)
			.limb("right_front_leg", "rf", -1.2, 24.1, -4, 1, 0.6)
			.build();
	}

	/**
	 * Builds a rig from coordinates in vanilla model space (pixels, y down from the head, feet at y = 24, -Z
	 * forward), which is what model definitions use. Torso pairs are made rigid; every limb gets one link to its
	 * anchor and soft minimum distances to everything else so it can't pass through the body.
	 */
	private static final class Builder {
		private final String name;
		private final double scale;
		private final double totalMass;
		private final List<String> names = new ArrayList<>();
		private final List<double[]> pos = new ArrayList<>();
		private final List<Double> radius = new ArrayList<>();
		private final List<Double> impact = new ArrayList<>();
		private final List<Integer> torso = new ArrayList<>();
		private final List<int[]> links = new ArrayList<>();
		private final List<Bone> bones = new ArrayList<>();
		private int head = -1;

		Builder(String name, double scale, double totalMass) {
			this.name = name;
			this.scale = scale;
			this.totalMass = totalMass;
		}

		private int add(String name, double mx, double my, double mz, double r, double impact) {
			this.names.add(name);
			this.pos.add(new double[] {mx / 16.0, (24.0 - my) / 16.0, -mz / 16.0});
			this.radius.add(r / 16.0);
			this.impact.add(impact);
			return this.names.size() - 1;
		}

		Builder torso(String name, double mx, double my, double mz, double r) {
			this.torso.add(this.add(name, mx, my, mz, r, 0.9));
			return this;
		}

		Builder limb(String part, String anchor, double mx, double my, double mz, double r, double impact) {
			int a = this.names.indexOf(anchor);
			int i = this.add(part, mx, my, mz, r, impact);
			this.links.add(new int[] {a, i});
			this.bones.add(new Bone(part, a, i));
			if (part.equals("head")) {
				this.head = i;
			}
			return this;
		}

		Rig build() {
			int n = this.names.size();
			double[] rest = new double[n * 3];
			double[] rad = new double[n];
			double[] mass = new double[n];
			double[] imp = new double[n];
			double sum = 0;
			for (int i = 0; i < n; i++) {
				System.arraycopy(this.pos.get(i), 0, rest, i * 3, 3);
				rad[i] = this.radius.get(i);
				imp[i] = this.impact.get(i);
				// Heavier where it's bulkier, with a floor so thin legs aren't weightless.
				mass[i] = Math.max(rad[i] * rad[i] * rad[i], 0.15 * 0.15 * 0.15 * 0.2);
				sum += mass[i];
			}
			for (int i = 0; i < n; i++) {
				mass[i] *= this.totalMass / sum;
			}
			List<int[]> rigid = new ArrayList<>(this.links);
			for (int a = 0; a < this.torso.size(); a++) {
				for (int b = a + 1; b < this.torso.size(); b++) {
					rigid.add(new int[] {this.torso.get(a), this.torso.get(b)});
				}
			}
			List<double[]> mins = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				if (this.torso.contains(i)) {
					continue;
				}
				for (int j = 0; j < n; j++) {
					if (j == i || (!this.torso.contains(j) && j < i) || this.linked(i, j)) {
						continue;
					}
					double d = dist(rest, i, j);
					double frac = this.torso.contains(j) ? 0.7 : 0.55;
					mins.add(new double[] {i, j, Math.max(d * frac, (rad[i] + rad[j]) * 0.8)});
				}
			}
			int[] torsoArr = this.torso.stream().mapToInt(Integer::intValue).toArray();
			return new Rig(this.name, this.scale, rest, rad, mass, imp, torsoArr, this.head >= 0 ? this.head : torsoArr[0],
				rigid.toArray(new int[0][]), mins.toArray(new double[0][]), this.bones.toArray(new Bone[0]));
		}

		private boolean linked(int a, int b) {
			for (int[] l : this.links) {
				if ((l[0] == a && l[1] == b) || (l[0] == b && l[1] == a)) {
					return true;
				}
			}
			return false;
		}

		private static double dist(double[] rest, int a, int b) {
			double dx = rest[a * 3] - rest[b * 3], dy = rest[a * 3 + 1] - rest[b * 3 + 1], dz = rest[a * 3 + 2] - rest[b * 3 + 2];
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}
	}
}
