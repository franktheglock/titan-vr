package dev.titanvr.giant;

import dev.titanvr.TitanConfig;
import dev.titanvr.TitanMod;
import dev.titanvr.platform.Platform;
import dev.titanvr.net.TitanNet;
import dev.titanvr.physics.PhysicsWorld;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Server-side registry of giants: applies size/flight and tracks their hands every tick. */
public final class GiantManager {
	private static final Identifier MODIFIER = TitanMod.id("giant");
	private static final Map<UUID, Giant> GIANTS = new ConcurrentHashMap<>();
	/** Players who turned giant mode off themselves; auto-giant leaves them alone. */
	private static final Set<UUID> OPTED_OUT = new HashSet<>();
	private static final Map<UUID, Vector3d> LAST_PLAYER_POS = new ConcurrentHashMap<>();

	private GiantManager() {
	}

	public static Giant get(Player player) {
		return GIANTS.get(player.getUUID());
	}

	public static Giant get(UUID uuid) {
		return GIANTS.get(uuid);
	}

	public static boolean isGiant(Player player) {
		return GIANTS.containsKey(player.getUUID());
	}

	public static Collection<Giant> all() {
		return GIANTS.values();
	}

	public static void setGiant(ServerPlayer player, boolean giant, double scale) {
		if (giant) {
			OPTED_OUT.remove(player.getUUID());
			double s = clampScale(scale);
			Giant g = GIANTS.computeIfAbsent(player.getUUID(), id -> new Giant(id, s));
			g.scale = s;
			apply(player, g);
		} else {
			Giant g = GIANTS.remove(player.getUUID());
			if (g != null) {
				PhysicsWorld.get(player.level()).releaseAll(player, g);
			}
			OPTED_OUT.add(player.getUUID());
			removeEffects(player);
		}
		broadcast(player);
	}

	public static double clampScale(double scale) {
		TitanConfig c = TitanConfig.INSTANCE;
		return Math.max(c.minGiantScale, Math.min(Math.min(16.0, c.maxGiantScale), scale));
	}

	public static void resize(ServerPlayer player, double scale) {
		Giant g = get(player);
		if (g == null) {
			return;
		}
		double s = clampScale(scale);
		if (Math.abs(s - g.scale) < 0.01) {
			return;
		}
		g.scale = s;
		apply(player, g);
		broadcast(player);
	}

	private static void apply(ServerPlayer player, Giant g) {
		setModifier(player.getAttribute(Attributes.SCALE), g.scale - 1.0);
		setModifier(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE), (g.scale - 1.0) * 0.6);
		setModifier(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE), (g.scale - 1.0) * 0.6);
		setModifier(player.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER), -1.0);
		setMaxHealth(player, (g.scale - 1.0) * TitanConfig.INSTANCE.giantHealthPerScale);
		// A giant's hitbox is enormous; it must not count as "in the way" when placing blocks.
		player.blocksBuilding = false;
		player.getAbilities().mayfly = true;
		if (TitanConfig.INSTANCE.alwaysHover) {
			player.getAbilities().flying = true;
		}
		player.getAbilities().setFlyingSpeed((float) (0.05 * (1.0 + (g.scale - 1.0) * 0.3)));
		player.onUpdateAbilities();
		player.refreshDimensions();
		for (HandState h : g.hands) {
			h.valid = false;
		}
	}

	private static void removeEffects(ServerPlayer player) {
		removeModifier(player.getAttribute(Attributes.SCALE));
		removeModifier(player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE));
		removeModifier(player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE));
		removeModifier(player.getAttribute(Attributes.FALL_DAMAGE_MULTIPLIER));
		setMaxHealth(player, 0.0);
		player.blocksBuilding = true;
		GameType mode = player.gameMode();
		boolean canFly = mode == GameType.CREATIVE || mode == GameType.SPECTATOR;
		player.getAbilities().mayfly = canFly;
		if (!canFly) {
			player.getAbilities().flying = false;
		}
		player.getAbilities().setFlyingSpeed(0.05F);
		player.onUpdateAbilities();
		player.refreshDimensions();
	}

	private static void setModifier(AttributeInstance attr, double amount) {
		if (attr != null) {
			attr.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	/** Adds {@code bonus} to max health, keeping the player at the same fraction of their health bar. */
	private static void setMaxHealth(ServerPlayer player, double bonus) {
		AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
		if (attr == null) {
			return;
		}
		float fraction = player.getHealth() / player.getMaxHealth();
		if (bonus > 0) {
			attr.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, bonus, AttributeModifier.Operation.ADD_VALUE));
		} else {
			attr.removeModifier(MODIFIER);
		}
		if (player.isAlive()) {
			player.setHealth(Math.max(1.0F, fraction * player.getMaxHealth()));
		}
	}

	/** Respawning creates a new player entity without our modifiers; a giant comes back as a giant at full health. */
	public static void onRespawn(ServerPlayer newPlayer) {
		Giant g = get(newPlayer);
		if (g == null) {
			return;
		}
		apply(newPlayer, g);
		newPlayer.setHealth(newPlayer.getMaxHealth());
		LAST_PLAYER_POS.remove(newPlayer.getUUID());
		broadcast(newPlayer);
	}

	private static void removeModifier(AttributeInstance attr) {
		if (attr != null) {
			attr.removeModifier(MODIFIER);
		}
	}

	public static void broadcast(ServerPlayer player) {
		Giant g = get(player);
		TitanNet.GiantStateS2C packet = new TitanNet.GiantStateS2C(player.getId(), g != null, g != null ? (float) g.scale : 1.0F);
		for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
			Platform.get().send(other, packet);
		}
	}

	/** Sends the giant status of everyone to a player who just joined. */
	public static void syncTo(ServerPlayer joiner) {
		for (ServerPlayer other : joiner.level().getServer().getPlayerList().getPlayers()) {
			Giant g = get(other);
			if (g != null) {
				Platform.get().send(joiner, new TitanNet.GiantStateS2C(other.getId(), true, (float) g.scale));
			}
		}
	}

	public static void onDisconnect(ServerPlayer player) {
		Giant g = GIANTS.remove(player.getUUID());
		if (g != null) {
			PhysicsWorld.get(player.level()).releaseAll(player, g);
			removeEffects(player);
		}
		LAST_PLAYER_POS.remove(player.getUUID());
	}

	/** Called at the start of every server tick, before physics. */
	public static void tick(MinecraftServer server) {
		boolean autoCheck = server.getTickCount() % 20 == 0;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Giant g = get(player);
			if (g == null) {
				if (autoCheck && TitanConfig.INSTANCE.autoGiantVR && !OPTED_OUT.contains(player.getUUID()) && Platform.get().vr().isVR(player)
					&& !dev.titanvr.arena.ArenaGame.isParticipant(player)) {
					setGiant(player, true, TitanConfig.INSTANCE.defaultGiantScale);
					player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
						"§6[Titan VR]§r You are a giant! Squeeze grip to grab, swing and let go to throw. /titan size <n> to resize."));
				}
				continue;
			}
			if (!player.isAlive()) {
				continue;
			}
			updateHands(player, g);
			if (TitanConfig.INSTANCE.alwaysHover && !player.getAbilities().flying && !player.isSpectator()) {
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
			player.resetFallDistance();
		}
	}

	private static void updateHands(ServerPlayer player, Giant g) {
		Vector3d pos = new Vector3d();
		Quaterniond rot = new Quaterniond();
		Vector3d last = LAST_PLAYER_POS.get(player.getUUID());
		Vector3d now = new Vector3d(player.getX(), player.getY(), player.getZ());
		// Big jumps (teleports, respawns) must not register as a lightning-fast hand swing.
		double jump = Math.max(12.0, g.scale * 2.5);
		boolean teleported = last == null || last.distanceSquared(now) > jump * jump;
		LAST_PLAYER_POS.put(player.getUUID(), now);
		g.vr = Platform.get().vr().isVR(player);
		for (int hand = 0; hand < 2; hand++) {
			boolean tracked = g.vr && Platform.get().vr().handPose(player, hand, pos, rot);
			if (!tracked) {
				desktopHand(player, g, hand, pos, rot);
			}
			g.hands[hand].update(pos, rot, teleported);
			HeldTools.configure(g.hands[hand], player.getItemInHand(hand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND), g.scale,
				g.handRadius(), tracked);
		}
	}

	/** Non-VR fallback: hands float in front of the camera, so desktop players can be giants too. */
	public static void desktopHand(Player player, Giant g, int hand, Vector3d posOut, Quaterniond rotOut) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		if (right.lengthSqr() < 1e-6) {
			right = new Vec3(1, 0, 0);
		}
		right = right.normalize();
		Vec3 up = right.cross(look).normalize();
		double reach = 0.75 * g.scale;
		double side = (hand == 0 ? 1 : -1) * 0.22 * g.scale;
		Vec3 p = eye.add(look.scale(reach)).add(right.scale(side)).add(up.scale(-0.12 * g.scale));
		posOut.set(p.x, p.y, p.z);
		rotOut.identity().rotateY(Math.toRadians(-player.getYRot())).rotateX(Math.toRadians(player.getXRot()));
	}

	public static boolean isGiantId(ServerLevel level, int entityId) {
		return level.getEntity(entityId) instanceof Player p && isGiant(p);
	}
}
