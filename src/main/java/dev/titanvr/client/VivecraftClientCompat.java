package dev.titanvr.client;

import dev.titanvr.compat.VivecraftCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/** Client-side Vivecraft bridge. All Vivecraft types stay inside {@link Impl}. */
public final class VivecraftClientCompat {
	private VivecraftClientCompat() {
	}

	/**
	 * Whether a physical grip button is squeezed (hand 0 = main, 1 = off). Vivecraft binds the grips to its
	 * hidden "hotbar next/prev" keys (right grip = next, left grip = prev).
	 */
	public static boolean gripDown(int hand) {
		return VivecraftCompat.LOADED && Impl.gripDown(hand);
	}

	public static boolean isVRActive() {
		return VivecraftCompat.LOADED && Impl.isVRActive();
	}

	/** World-space hand pose. render = interpolated for this frame, otherwise the pose at the start of the tick. */
	public static boolean hand(int hand, boolean render, Vector3d pos, Quaterniond rot) {
		return VivecraftCompat.LOADED && Impl.hand(hand, render, pos, rot);
	}

	/** Hand position in room space (real-world meters, unscaled). */
	public static boolean roomHand(int hand, Vector3d pos) {
		return VivecraftCompat.LOADED && Impl.roomHand(hand, pos);
	}

	/**
	 * Interpolated (per-frame) world pose of any player in VR, as Vivecraft syncs it to every client:
	 * index 0 = head, 1 = main hand, 2 = off hand. Returns -1 if not in VR, else 1 if left-handed, 0 if not.
	 */
	public static int playerRenderPose(net.minecraft.world.entity.player.Player player, Vector3d[] pos, Quaterniond[] rot) {
		return VivecraftCompat.LOADED ? Impl.playerRenderPose(player, pos, rot) : -1;
	}

	public static void haptic(int hand, float seconds) {
		if (VivecraftCompat.LOADED) {
			Impl.haptic(hand, seconds);
		}
	}

	private static final class Impl {
		static boolean gripDown(int hand) {
			try {
				boolean leftHanded = VRClientAPI.instance().isLeftHanded();
				boolean right = (hand == 0) != leftHanded;
				org.vivecraft.client.VivecraftVRMod mod = org.vivecraft.client.VivecraftVRMod.INSTANCE;
				return (right ? mod.keyHotbarNext : mod.keyHotbarPrev).isDown();
			} catch (Throwable t) {
				return false;
			}
		}

		static boolean isVRActive() {
			try {
				return VRClientAPI.instance().isVRActive();
			} catch (Throwable t) {
				return false;
			}
		}

		static boolean hand(int hand, boolean render, Vector3d pos, Quaterniond rot) {
			VRPose pose;
			try {
				pose = render ? VRClientAPI.instance().getWorldRenderPose() : VRClientAPI.instance().getPreTickWorldPose();
			} catch (Throwable t) {
				return false;
			}
			if (pose == null) {
				return false;
			}
			VRBodyPartData data = pose.getHand(hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
			if (data == null) {
				return false;
			}
			Vec3 p = data.getPos();
			pos.set(p.x, p.y, p.z);
			Quaternionfc q = data.getRotation();
			rot.set(q.x(), q.y(), q.z(), q.w());
			return true;
		}

		static boolean roomHand(int hand, Vector3d pos) {
			try {
				VRPose pose = VRClientAPI.instance().getLatestRoomPose();
				if (pose == null) {
					return false;
				}
				VRBodyPartData data = pose.getHand(hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
				if (data == null) {
					return false;
				}
				Vec3 p = data.getPos();
				pos.set(p.x, p.y, p.z);
				return true;
			} catch (Throwable t) {
				return false;
			}
		}

		static int playerRenderPose(net.minecraft.world.entity.player.Player player, Vector3d[] pos, Quaterniond[] rot) {
			try {
				if (!org.vivecraft.api.VRAPI.instance().isVRPlayer(player)) {
					return -1;
				}
				VRPose pose = org.vivecraft.api.client.VRRenderingAPI.instance().getWorldRenderPose(player);
				if (pose == null) {
					return -1;
				}
				VRBodyPartData[] parts = {pose.getHead(), pose.getMainHand(), pose.getOffHand()};
				for (int i = 0; i < 3; i++) {
					if (parts[i] == null) {
						return -1;
					}
					Vec3 p = parts[i].getPos();
					pos[i].set(p.x, p.y, p.z);
					Quaternionfc q = parts[i].getRotation();
					rot[i].set(q.x(), q.y(), q.z(), q.w());
				}
				return pose.isLeftHanded() ? 1 : 0;
			} catch (Throwable t) {
				return -1;
			}
		}

		static void haptic(int hand, float seconds) {
			try {
				VRClientAPI.instance().triggerHapticPulse(hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, seconds);
			} catch (Throwable ignored) {
			}
		}
	}
}
