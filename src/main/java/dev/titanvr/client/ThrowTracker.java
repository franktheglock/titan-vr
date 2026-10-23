package dev.titanvr.client;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Samples the local giant's hand poses every rendered frame and estimates the release velocity of a throw with a
 * weighted least-squares fit over the end of the swing (plus the hand's spin). Much steadier than picking one
 * 20 Hz server sample, so things fly where the swing was going.
 */
public final class ThrowTracker {
	private static final int CAP = 96;
	/** How much of the end of the swing is fitted, in seconds. */
	private static final double WINDOW = 0.12;
	private static final long[][] TIME = new long[2][CAP];
	private static final double[][] POS = new double[2][CAP * 3];
	private static final Quaterniond[][] ROT = new Quaterniond[2][CAP];
	private static final int[] HEAD = new int[2];
	private static final int[] COUNT = new int[2];

	static {
		for (int h = 0; h < 2; h++) {
			for (int i = 0; i < CAP; i++) {
				ROT[h][i] = new Quaterniond();
			}
		}
	}

	private ThrowTracker() {
	}

	/** Called once per frame. */
	public static void sample() {
		if (!GiantClient.isLocalGiant()) {
			COUNT[0] = COUNT[1] = 0;
			return;
		}
		long now = System.nanoTime();
		Vector3d pos = new Vector3d();
		Quaterniond rot = new Quaterniond();
		for (int h = 0; h < 2; h++) {
			if (!GiantClient.handPose(h, true, 1.0F, pos, rot)) {
				continue;
			}
			int i = HEAD[h];
			// Skip duplicate poses (no new tracking data this frame).
			int prev = (i + CAP - 1) % CAP;
			if (COUNT[h] > 0 && POS[h][prev * 3] == pos.x && POS[h][prev * 3 + 1] == pos.y && POS[h][prev * 3 + 2] == pos.z) {
				continue;
			}
			TIME[h][i] = now;
			POS[h][i * 3] = pos.x;
			POS[h][i * 3 + 1] = pos.y;
			POS[h][i * 3 + 2] = pos.z;
			ROT[h][i].set(rot);
			HEAD[h] = (i + 1) % CAP;
			COUNT[h] = Math.min(CAP, COUNT[h] + 1);
		}
	}

	/** Writes hand velocity (blocks/s) and angular velocity (rad/s) into out[0..5]; false if there isn't enough data. */
	public static boolean estimate(int h, float[] out) {
		int n = COUNT[h];
		if (n < 3) {
			return false;
		}
		int newest = (HEAD[h] + CAP - 1) % CAP;
		long tEnd = TIME[h][newest];
		// Gather samples inside the window (newest backwards).
		double sw = 0, st = 0;
		double[] sp = new double[3];
		int used = 0;
		int oldest = newest;
		for (int k = 0; k < n; k++) {
			int i = (newest - k + CAP) % CAP;
			double t = (TIME[h][i] - tEnd) / 1e9;
			if (-t > WINDOW) {
				break;
			}
			// Favour the end of the swing, but keep the whole window for stability.
			double w = 1.0 + (WINDOW + t) / WINDOW;
			sw += w;
			st += w * t;
			for (int a = 0; a < 3; a++) {
				sp[a] += w * POS[h][i * 3 + a];
			}
			oldest = i;
			used++;
		}
		double span = (tEnd - TIME[h][oldest]) / 1e9;
		if (used < 3 || span < 0.025) {
			return false;
		}
		double tm = st / sw;
		double stt = 0;
		double[] stp = new double[3];
		for (int k = 0; k < used; k++) {
			int i = (newest - k + CAP) % CAP;
			double t = (TIME[h][i] - tEnd) / 1e9;
			double w = 1.0 + (WINDOW + t) / WINDOW;
			double dt = t - tm;
			stt += w * dt * dt;
			for (int a = 0; a < 3; a++) {
				stp[a] += w * dt * (POS[h][i * 3 + a] - sp[a] / sw);
			}
		}
		if (stt <= 1e-12) {
			return false;
		}
		for (int a = 0; a < 3; a++) {
			out[a] = (float) (stp[a] / stt);
		}
		// Angular velocity from the rotation across the window: q_new * q_old^-1 as axis * angle / time.
		Quaterniond dq = new Quaterniond(ROT[h][newest]).mul(new Quaterniond(ROT[h][oldest]).conjugate());
		if (dq.w < 0) {
			dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
		}
		double sinHalf = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
		if (sinHalf > 1e-6) {
			double angle = 2 * Math.atan2(sinHalf, dq.w);
			double rate = angle / span;
			out[3] = (float) (dq.x / sinHalf * rate);
			out[4] = (float) (dq.y / sinHalf * rate);
			out[5] = (float) (dq.z / sinHalf * rate);
		}
		return true;
	}
}
