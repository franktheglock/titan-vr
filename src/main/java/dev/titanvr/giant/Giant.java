package dev.titanvr.giant;

import java.util.UUID;

/** A player currently playing as a giant. */
public final class Giant {
	public final UUID uuid;
	public double scale;
	public boolean vr;
	public final HandState[] hands = {new HandState(), new HandState()};

	public Giant(UUID uuid, double scale) {
		this.uuid = uuid;
		this.scale = scale;
	}

	/** Radius of a hand's collider / grab sphere. */
	public double handRadius() {
		return Math.max(0.35, 0.12 * this.scale);
	}
}
