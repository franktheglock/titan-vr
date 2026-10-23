package dev.titanvr.physics;

/**
 * One contact point between body A and either the static world, body B, or a kinematic collider (a giant's
 * hand) with a prescribed velocity. Normal points from B (or the world / hand) towards A.
 */
final class Contact {
	RigidBody a;
	RigidBody b;
	boolean kinematic;
	double kvx, kvy, kvz;
	double px, py, pz;
	double nx, ny, nz;
	double depth;
	double rax, ray, raz, rbx, rby, rbz;
	double massN;
	double bias;
	double accN;
	double accT;
	double friction;

	void set(RigidBody a, RigidBody b, double px, double py, double pz, double nx, double ny, double nz, double depth) {
		this.a = a;
		this.b = b;
		this.kinematic = false;
		this.kvx = this.kvy = this.kvz = 0;
		this.px = px;
		this.py = py;
		this.pz = pz;
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.depth = depth;
		this.accN = 0;
		this.accT = 0;
		this.friction = 0.6;
	}
}
