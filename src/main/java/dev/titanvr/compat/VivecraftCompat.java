package dev.titanvr.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;
import org.vivecraft.api.server.VRServerAPI;

/**
 * Thin bridge to Vivecraft. Every Vivecraft class reference lives in {@link Impl}, which is only loaded
 * when Vivecraft is actually installed, so Titan VR still runs (desktop giants only) without it.
 */
public final class VivecraftCompat {
	public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("vivecraft");

	private VivecraftCompat() {
	}

	public static boolean isVR(Player player) {
		return LOADED && Impl.isVR(player);
	}

	/**
	 * World-space pose of a hand (0 = main, 1 = off) of a VR player, on either side.
	 * Returns false when the player is not in VR.
	 */
	public static boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut) {
		return LOADED && Impl.handPose(player, hand, posOut, rotOut);
	}

	public static boolean headPose(Player player, Vector3d posOut) {
		return LOADED && Impl.headPose(player, posOut);
	}

	public static void haptic(ServerPlayer player, int hand, float seconds) {
		if (LOADED) {
			Impl.haptic(player, hand, seconds);
		}
	}

	private static final class Impl {
		static boolean isVR(Player player) {
			try {
				return VRAPI.instance().isVRPlayer(player);
			} catch (Throwable t) {
				return false;
			}
		}

		static boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut) {
			VRPose pose;
			try {
				pose = VRAPI.instance().getVRPose(player);
			} catch (Throwable t) {
				return false;
			}
			if (pose == null) {
				return false;
			}
			VRBodyPartData data = pose.getBodyPartData(hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND);
			if (data == null) {
				return false;
			}
			Vec3 p = data.getPos();
			posOut.set(p.x, p.y, p.z);
			Quaternionfc q = data.getRotation();
			rotOut.set(q.x(), q.y(), q.z(), q.w());
			return true;
		}

		static boolean headPose(Player player, Vector3d posOut) {
			try {
				VRPose pose = VRAPI.instance().getVRPose(player);
				if (pose == null || pose.getHead() == null) {
					return false;
				}
				Vec3 p = pose.getHead().getPos();
				posOut.set(p.x, p.y, p.z);
				return true;
			} catch (Throwable t) {
				return false;
			}
		}

		static void haptic(ServerPlayer player, int hand, float seconds) {
			try {
				if (VRServerAPI.instance().hasVivecraft(player)) {
					VRServerAPI.instance().sendHapticPulse(player, hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, seconds);
				}
			} catch (Throwable ignored) {
			}
		}
	}
}
