package dev.titanvr.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.titanvr.TitanMod;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.net.TitanNet;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

/** Local giant controls: grip-to-grab (VR) or R/F (desktop), and held-object prediction. */
public final class GiantClient {
	private static final Int2FloatOpenHashMap GIANTS = new Int2FloatOpenHashMap();

	private static final class Hold {
		int entityId;
		byte kind;
		int particle;
		final Vector3d localPos = new Vector3d();
		final Quaterniond localRot = new Quaterniond();
	}

	private static final Hold[] HOLDS = new Hold[2];
	/** Server grab requested for this hand (button held). */
	private static final boolean[] GRABBING = new boolean[2];
	private static final boolean[] GRIP_DOWN = new boolean[2];

	private static KeyMapping grabRight;
	private static KeyMapping grabLeft;
	private static final boolean[] DESKTOP_DOWN = new boolean[2];

	private GiantClient() {
	}

	public static void registerKeys() {
		KeyMapping.Category category = KeyMapping.Category.register(TitanMod.id("titanvr"));
		grabRight = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.titanvr.grab_right", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
		grabLeft = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.titanvr.grab_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, category));
	}

	// ------------------------------------------------------------------ giant status

	public static void handleGiantState(TitanNet.GiantStateS2C p) {
		if (p.giant()) {
			GIANTS.put(p.entityId(), p.scale());
		} else {
			GIANTS.remove(p.entityId());
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getId() == p.entityId() && !p.giant()) {
			resetLocal();
		}
	}

	public static float scaleOf(int entityId) {
		return GIANTS.getOrDefault(entityId, 1.0F);
	}

	public static boolean isGiantId(int entityId) {
		return GIANTS.containsKey(entityId);
	}

	public static boolean isLocalGiant() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && GIANTS.containsKey(player.getId());
	}

	public static float localScale() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? 1 : GIANTS.getOrDefault(player.getId(), 1.0F);
	}

	public static void clear() {
		GIANTS.clear();
		resetLocal();
	}

	private static void resetLocal() {
		HOLDS[0] = HOLDS[1] = null;
		GRABBING[0] = GRABBING[1] = false;
		GRIP_DOWN[0] = GRIP_DOWN[1] = false;
		DESKTOP_DOWN[0] = DESKTOP_DOWN[1] = false;
	}

	// ------------------------------------------------------------------ hand poses

	/** Local hand pose (VR controller, or the desktop stand-in in front of the camera). */
	static boolean handPose(int hand, boolean render, float partialTick, Vector3d pos, Quaterniond rot) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return false;
		}
		if (VivecraftClientCompat.isVRActive() && VivecraftClientCompat.hand(hand, render, pos, rot)) {
			return true;
		}
		Giant g = new Giant(player.getUUID(), localScale());
		GiantManager.desktopHand(player, g, hand, pos, rot);
		if (render) {
			// desktopHand uses the current tick's camera; offset by the interpolated position for smoothness.
			Vec3 interp = player.getPosition(partialTick);
			pos.add(interp.x - player.getX(), interp.y - player.getY(), interp.z - player.getZ());
		}
		return true;
	}

	// ------------------------------------------------------------------ grab input

	private static void press(int hand) {
		if (!GRABBING[hand]) {
			GRABBING[hand] = true;
			ClientPlayNetworking.send(TitanNet.GrabC2S.press(hand));
		}
	}

	private static void release(int hand) {
		if (GRABBING[hand]) {
			GRABBING[hand] = false;
			float[] motion = new float[6];
			boolean ok = ThrowTracker.estimate(hand, motion);
			ClientPlayNetworking.send(new TitanNet.GrabC2S((byte) hand, false, ok, motion));
		}
	}

	/**
	 * Grabbing only ever uses the grips (VR) or R/F (desktop). Everything else - trigger to break/attack,
	 * A to use/place, sticks to fly and turn - stays standard Vivecraft. Gripping nothing does nothing.
	 */
	public static void startTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		// A giant's huge hitbox must not stop blocks being placed around (or inside) it.
		for (var p : mc.level.players()) {
			p.blocksBuilding = !GIANTS.containsKey(p.getId());
		}
		if (!isLocalGiant()) {
			return;
		}
		boolean screenOpen = mc.gui.screen() != null;
		boolean vr = VivecraftClientCompat.isVRActive();
		KeyMapping[] keys = {grabRight, grabLeft};
		for (int h = 0; h < 2; h++) {
			boolean grip = !screenOpen && vr && VivecraftClientCompat.gripDown(h);
			boolean key = !screenOpen && keys[h].isDown();
			boolean down = grip || key;
			boolean wasDown = GRIP_DOWN[h] || DESKTOP_DOWN[h];
			if (down && !wasDown) {
				press(h);
			} else if (!down && wasDown) {
				release(h);
			}
			GRIP_DOWN[h] = grip;
			DESKTOP_DOWN[h] = key;
		}
		if (!player.getAbilities().flying && player.getAbilities().mayfly) {
			player.getAbilities().flying = true;
		}
	}

	public static void endTick(Minecraft mc) {
		// Carried entities (items, TNT...) follow the local hand smoothly instead of 20 Hz server updates.
		for (int h = 0; h < 2; h++) {
			Hold hold = HOLDS[h];
			if (hold == null || hold.kind != TitanNet.HOLD_ENTITY || mc.level == null) {
				continue;
			}
			Entity e = mc.level.getEntity(hold.entityId);
			Vector3d pos = new Vector3d();
			Quaterniond rot = new Quaterniond();
			if (e != null && handPose(h, false, 1, pos, rot)) {
				Vector3d target = new Vector3d(hold.localPos);
				rot.transform(target).add(pos);
				e.setPos(target.x, target.y - e.getBbHeight() * 0.5, target.z);
			}
		}
	}

	// ------------------------------------------------------------------ held-object prediction

	public static void handleHold(TitanNet.HoldS2C p) {
		int hand = p.hand() == 0 ? 0 : 1;
		if (!p.holding()) {
			if (HOLDS[hand] != null && HOLDS[hand].entityId == p.entityId()) {
				HOLDS[hand] = null;
			}
			return;
		}
		Hold hold = new Hold();
		hold.entityId = p.entityId();
		hold.kind = p.kind();
		hold.particle = p.particle();
		float[] l = p.local();
		hold.localPos.set(l[0], l[1], l[2]);
		hold.localRot.set(l[3], l[4], l[5], l[6]);
		HOLDS[hand] = hold;
		VivecraftClientCompat.haptic(hand, 0.08F);
	}

	private static int heldBy(int entityId, byte kind) {
		for (int h = 0; h < 2; h++) {
			if (HOLDS[h] != null && HOLDS[h].entityId == entityId && HOLDS[h].kind == kind) {
				return h;
			}
		}
		return -1;
	}

	/** If the local giant holds this debris body, where it should be drawn this frame. */
	public static boolean predictHeldBody(int entityId, float partialTick, Vector3d posOut, Quaternionf rotOut) {
		int h = heldBy(entityId, TitanNet.HOLD_BODY);
		if (h < 0) {
			return false;
		}
		Vector3d hp = new Vector3d();
		Quaterniond hr = new Quaterniond();
		if (!handPose(h, true, partialTick, hp, hr)) {
			return false;
		}
		Hold hold = HOLDS[h];
		posOut.set(hold.localPos);
		hr.transform(posOut).add(hp);
		Quaterniond r = new Quaterniond(hr).mul(hold.localRot);
		rotOut.set((float) r.x, (float) r.y, (float) r.z, (float) r.w);
		return true;
	}

	/** Shifts an interpolated ragdoll pose so the held part sits in the real-time hand. */
	static void applyHeldPrediction(int entityId, byte ragdollKind, double[] values, float partialTick) {
		if (ragdollKind == TitanNet.RAGDOLL_HUMANOID) {
			int h = heldBy(entityId, TitanNet.HOLD_RAGDOLL);
			if (h < 0) {
				return;
			}
			Vector3d hp = new Vector3d();
			Quaterniond hr = new Quaterniond();
			if (!handPose(h, true, partialTick, hp, hr)) {
				return;
			}
			Hold hold = HOLDS[h];
			Vector3d target = new Vector3d(hold.localPos);
			hr.transform(target).add(hp);
			int o = hold.particle * 3;
			double dx = target.x - values[o], dy = target.y - values[o + 1], dz = target.z - values[o + 2];
			for (int i = 0; i < values.length; i += 3) {
				values[i] += dx;
				values[i + 1] += dy;
				values[i + 2] += dz;
			}
		} else if (ragdollKind == TitanNet.RAGDOLL_RIGID) {
			int h = heldBy(entityId, TitanNet.HOLD_RIGID_RAGDOLL);
			if (h < 0) {
				return;
			}
			Vector3d hp = new Vector3d();
			Quaterniond hr = new Quaterniond();
			if (!handPose(h, true, partialTick, hp, hr)) {
				return;
			}
			Hold hold = HOLDS[h];
			Vector3d target = new Vector3d(hold.localPos);
			hr.transform(target).add(hp);
			Quaterniond r = new Quaterniond(hr).mul(hold.localRot);
			values[0] = target.x;
			values[1] = target.y;
			values[2] = target.z;
			values[3] = r.x;
			values[4] = r.y;
			values[5] = r.z;
			values[6] = r.w;
		}
	}
}
