package dev.titanvr.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Vivecraft access, through whichever Vivecraft API the platform has (or none). */
public interface VrBridge {
	VrBridge NONE = new VrBridge() {
		@Override
		public boolean isVR(Player player) {
			return false;
		}

		@Override
		public boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut) {
			return false;
		}

		@Override
		public boolean headPose(Player player, Vector3d posOut) {
			return false;
		}

		@Override
		public void haptic(ServerPlayer player, int hand, float seconds) {
		}
	};

	boolean isVR(Player player);

	/** World-space pose of a hand (0 = main, 1 = off); false when the player isn't in VR. */
	boolean handPose(Player player, int hand, Vector3d posOut, Quaterniond rotOut);

	boolean headPose(Player player, Vector3d posOut);

	void haptic(ServerPlayer player, int hand, float seconds);
}
