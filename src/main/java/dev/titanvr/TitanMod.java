package dev.titanvr;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants. The entrypoints are {@code fabric.FabricMod} and the Paper plugin. */
public final class TitanMod {
	public static final String MOD_ID = "titanvr";
	public static final Logger LOGGER = LoggerFactory.getLogger("Titan VR");
	/** Dev autotest running (extra logging). */
	public static final boolean DEV_TEST = Boolean.getBoolean("titanvr.autotest");
	/** Dev autotest: keeps ragdolls down while it photographs them. */
	public static volatile boolean holdRagdolls;

	private TitanMod() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static ServerLevel levelOf(Player p) {
		return (ServerLevel) p.level();
	}
}
