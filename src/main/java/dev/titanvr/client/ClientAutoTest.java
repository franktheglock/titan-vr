package dev.titanvr.client;

import dev.titanvr.AutoTest;
import dev.titanvr.TitanMod;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Client half of the dev autotest: timed screenshots, camera changes, then quit. */
public final class ClientAutoTest {
	private static final int[] SHOTS = {4, 30, 56, 65, 72, 76, 80, 90, 105, 118, 122, 140, 160, 175, 192, 200, 210, 222, 232, 241, 246,
		255, 270, 286, 300, 320, 334, 340, 344, 348, 352, 358, 364, 370, 373, 376, 380, 386, 392, 400, 404, 408, 412, 416, 420, 430, 441, 446, 450, 455, 462, 470, 490,
		516, 518, 520, 523, 527, 532, 538, 550, 553, 557, 562, 568, 575, 587, 590, 593, 596, 600, 605, 615,
		648, 653, 656, 659, 663, 668, 675, 684, 693, 697, 702, 710,
		715, 719, 723, 727, 731, 735, 739, 743, 747, 751, 755, 759, 763, 767, 771,
		819, 822, 824, 826, 829, 834, 852, 854, 856, 858, 861, 865, 875};
	private static final IntOpenHashSet TAKEN = new IntOpenHashSet();
	private static int doneTicks;

	private ClientAutoTest() {
	}

	/** Dev: screenshot every N ticks while in a world (-Dtitanvr.shotEvery=N), for checking multiplayer by eye. */
	private static final int SHOT_EVERY = Integer.getInteger("titanvr.shotEvery", 0);
	private static int shotTicks;
	/** Dev: every N ticks (counted like the screenshots), grab and then throw (-Dtitanvr.grabAt=N). */
	private static final int GRAB_AT = Integer.getInteger("titanvr.grabAt", 0);

	public static void tick(Minecraft mc) {
		if (GRAB_AT > 0 && mc.player != null) {
			// Dev: grab with the right hand, then throw it where we look
			if (shotTicks % GRAB_AT == 0 && shotTicks > 0) {
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(dev.titanvr.net.TitanNet.GrabC2S.press(0));
			} else if (shotTicks % GRAB_AT == 40 && shotTicks > GRAB_AT) {
				var look = mc.player.getLookAngle().scale(25);
				net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.titanvr.net.TitanNet.GrabC2S((byte) 0, false, true,
					new float[] {(float) look.x, (float) look.y + 6, (float) look.z, 0, 2, 0}));
			}
		}
		if (SHOT_EVERY > 0 && mc.player != null && ++shotTicks % SHOT_EVERY == 0) {
			String name = String.format("join_%04d.png", shotTicks / SHOT_EVERY);
			Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> TitanMod.LOGGER.info("[shot] {}", name));
		}
		if (!AutoTest.ENABLED || mc.player == null) {
			return;
		}
		int t = AutoTest.tick;
		if (AutoTest.ARENA) {
			String name = dev.titanvr.ArenaTest.shot;
			if (name != null) {
				dev.titanvr.ArenaTest.shot = null;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				Screenshot.grab(mc.gameDirectory, name + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> TitanMod.LOGGER.info("[autotest] screenshot {}", name));
			}
			if (mc.player.isDeadOrDying() && mc.player.deathTime == 15) {
				mc.player.respawn();
			}
			if (AutoTest.done && ++doneTicks == 40) {
				mc.stop();
			}
			return;
		}
		if (t == 300) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		if (t == 345) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		}
		if (t == 395) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		if (t == 438 || t == 505 || t == 580 || t == 640) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		if (t == 790) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		}
		if (t == 540 || t == 586 || t == 843) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		for (int s : SHOTS) {
			if (t >= s && TAKEN.add(s)) {
				String name = String.format("autotest_%03d.png", s);
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, msg -> TitanMod.LOGGER.info("[autotest] screenshot {}", name));
			}
		}
		if (AutoTest.done && ++doneTicks == 40) {
			mc.stop();
		}
	}
}
