package dev.titanvr.paper;

import dev.titanvr.platform.VrBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.bukkit.util.Vector;
import org.joml.Quaterniond;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/** Vivecraft through the Vivecraft Spigot Extension plugin's API. Only loaded when that plugin is present. */
final class PaperVr implements VrBridge {
	private static org.bukkit.entity.Player bukkit(Player player) {
		return player instanceof ServerPlayer sp ? sp.getBukkitEntity() : null;
	}

	@Override
	public boolean isVR(Player player) {
		org.bukkit.entity.Player p = bukkit(player);
		try {
			return p != null && VRAPI.instance().isVRPlayer(p);
		} catch (Throwable t) {
			return false;
		}
	}

	private static VRBodyPartData part(Player player, VRBodyPart part) {
		org.bukkit.entity.Player p = bukkit(player);
		if (p == null) {
			return null;
		}
		try {
			VRPose pose = VRAPI.instance().getVRPose(p);
			return pose == null ? null : pose.getBodyPartData(part);
		} catch (Throwable t) {
			return null;
		}
	}

	@Override
	public boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut) {
		VRBodyPartData data = part(player, hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND);
		if (data == null) {
			return false;
		}
		Vector v = data.getPos();
		posOut.set(v.getX(), v.getY(), v.getZ());
		Quaternionfc q = data.getRotation();
		rotOut.set(q.x(), q.y(), q.z(), q.w());
		return true;
	}

	@Override
	public boolean headPose(Player player, Vector3d posOut) {
		VRBodyPartData data = part(player, VRBodyPart.HEAD);
		if (data == null) {
			return false;
		}
		Vector v = data.getPos();
		posOut.set(v.getX(), v.getY(), v.getZ());
		return true;
	}

	@Override
	public void haptic(ServerPlayer player, int hand, float seconds) {
		try {
			if (VRAPI.instance().hasVivecraft(player.getBukkitEntity())) {
				VRAPI.instance().sendHapticPulse(player.getBukkitEntity(), hand == 0 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, seconds);
			}
		} catch (Throwable ignored) {
		}
	}
}
