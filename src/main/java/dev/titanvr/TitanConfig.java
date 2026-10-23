package dev.titanvr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side tuning knobs. Persisted as config/titanvr.json and editable live with /titan config.
 */
public final class TitanConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path path() {
		return dev.titanvr.platform.Platform.get().configDir().resolve("titanvr.json");
	}

	public static TitanConfig INSTANCE = new TitanConfig();

	// --- giants ---
	/** VR players automatically become giants when they join. */
	public boolean autoGiantVR = true;
	/** Size multiplier a new giant starts at (vanilla scale attribute, 1..16). */
	public double defaultGiantScale = 8.0;
	public double minGiantScale = 2.0;
	public double maxGiantScale = 16.0;
	/** Lets giants resize themselves by stretching both hands apart while air-gripping. */
	public boolean allowStretchResize = true;
	/** Giants always hover (flight is forced on). */
	public boolean alwaysHover = true;

	// --- grabbing / throwing ---
	/** Hand reach for grabbing, as a fraction of the giant's scale. */
	public double grabRadiusPerScale = 0.32;
	/** Radius of the "fist" when grabbing the world, per scale. What's in the fist is always held. */
	public double ripRadiusPerScale = 0.28;
	/**
	 * Most blocks a size-8 giant can tear away in one go (scales with size squared). Gripping something bonded
	 * to more than this (e.g. a big building) only rips out the fistful.
	 */
	public int maxGrabVoxels = 900;
	public double throwMultiplier = 1.2;
	/** Desktop (non-VR) giants get this extra forward speed (blocks/s) when they let go. */
	public double desktopThrowSpeed = 28.0;

	// --- destruction ---
	/** Global multiplier on how easily impacts break blocks. Higher = more destruction. */
	public double destructionMultiplier = 1.0;
	/** Giant hands smash blocks they move through faster than this (blocks/s, scaled by giant size / 8). */
	public double handSmashSpeed = 7.0;
	public int maxHandBreaksPerTick = 28;
	/** Blocks that lose support fall as rigid bodies (buildings collapse, cut trees topple). */
	public boolean structuralCollapse = true;
	/** Unsupported groups bigger than this are assumed to be anchored (keeps the check cheap). */
	public int collapseSearchLimit = 3000;
	/** Debris that comes to rest is turned back into blocks ("place") or removed ("despawn"). */
	public String rubbleMode = "place";
	public int rubbleSettleSeconds = 25;

	// --- physics budget ---
	public int maxVoxels = 2600;
	public int maxBodies = 700;
	public int maxNewDebrisPerTick = 80;
	public double gravity = 28.0;

	// --- ragdolls / damage ---
	/**
	 * Impact damage is physical: a creature's sudden velocity change hurts exactly like vanilla fall damage would
	 * (stopping from v blocks/s = falling v^2/64 blocks), plus a term for the energy of heavy projectiles.
	 */
	public double impactDamageMultiplier = 1.0;
	/** Mass (tonnes) of a size-8 giant's hand; creatures weigh ~0.1-1. Scales with size cubed. */
	public double handMass = 6.0;
	/** A hit that changes a creature's velocity by more than this (blocks/s) knocks it over as a ragdoll; less just shoves. */
	public double ragdollLaunchSpeed = 9.0;
	/** Heavy impacts above this kinetic energy (tonne * (blocks/s)^2 / 2) send out a shockwave. */
	public double shockwaveMinEnergy = 3000.0;
	public boolean giantsImmuneToPhysicsDamage = true;
	/** Extra max health (half-hearts) per size step above 1: a size-8 giant has 20 + 7 * this. */
	public double giantHealthPerScale = 20.0;

	// --- arena minigame ---
	public int arenaRadius = 40;
	/** Lives shared by the whole hunter team. */
	public int arenaLives = 25;
	public double arenaGiantScale = 8.0;
	/** How high above the arena floor the giant's feet may go (the hunters' lobby is at 50). */
	public int arenaGiantCeiling = 18;
	public int arenaCountdownSeconds = 15;
	public int arenaTimeLimitSeconds = 600;
	/** The arena rebuilds itself this often during a game (0 = only between games). */
	public int arenaRegenSeconds = 150;
	public int arenaResupplySeconds = 30;
	/** In a "Titan VR Arena" world, start games by themselves when a VR player and a hunter are around. */
	public boolean arenaAutoStart = true;
	public int maxRagdollSeconds = 15;

	public static void load() {
		if (Files.exists(path())) {
			try (Reader reader = Files.newBufferedReader(path())) {
				TitanConfig loaded = GSON.fromJson(reader, TitanConfig.class);
				if (loaded != null) {
					INSTANCE = loaded;
				}
			} catch (Exception e) {
				TitanMod.LOGGER.error("Failed to read {}, using defaults", path(), e);
			}
		}
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer writer = Files.newBufferedWriter(path())) {
				GSON.toJson(INSTANCE, writer);
			}
		} catch (IOException e) {
			TitanMod.LOGGER.error("Failed to write {}", path(), e);
		}
	}

	public static List<String> keys() {
		List<String> keys = new ArrayList<>();
		for (Field f : TitanConfig.class.getDeclaredFields()) {
			if (!Modifier.isStatic(f.getModifiers())) {
				keys.add(f.getName());
			}
		}
		return keys;
	}

	public static String get(String key) throws ReflectiveOperationException {
		return String.valueOf(TitanConfig.class.getDeclaredField(key).get(INSTANCE));
	}

	/** Sets a field from its string form. Returns the stored value. */
	public static String set(String key, String value) throws ReflectiveOperationException {
		Field f = TitanConfig.class.getDeclaredField(key);
		Class<?> t = f.getType();
		if (t == boolean.class) {
			f.setBoolean(INSTANCE, Boolean.parseBoolean(value));
		} else if (t == int.class) {
			f.setInt(INSTANCE, Integer.parseInt(value));
		} else if (t == double.class) {
			f.setDouble(INSTANCE, Double.parseDouble(value));
		} else {
			f.set(INSTANCE, value);
		}
		save();
		return get(key);
	}
}
