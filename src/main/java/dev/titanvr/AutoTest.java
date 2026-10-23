package dev.titanvr;

import dev.titanvr.command.TitanCommands;
import dev.titanvr.giant.Giant;
import dev.titanvr.giant.GiantManager;
import dev.titanvr.physics.PhysicsWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3d;

/**
 * Dev-only scripted scenario (-Dtitanvr.autotest=true): turns the first player into a desktop giant and
 * exercises ripping, throwing, hand smashing, collapse and ragdolls while logging physics stats.
 */
public final class AutoTest {
	public static final boolean ENABLED = Boolean.getBoolean("titanvr.autotest");
	/** Run the arena minigame scenario instead of the physics one. */
	public static final boolean ARENA = Boolean.getBoolean("titanvr.autotest.arena");
	/** Skip ahead to a later phase (scene setup still runs). */
	private static final int START_AT = Integer.getInteger("titanvr.autotest.start", 0);
	/** Ticks since the scenario started (read by the client half to time screenshots). */
	public static volatile int tick = -1;
	public static volatile boolean done;
	/** Keeps ragdolls down while the test photographs them. */
	// (holdRagdolls lives in TitanMod so the shared physics code can read it)

	private static BlockPos ground;
	private static LivingEntity heldZombie;

	private AutoTest() {
	}

	public static void serverTick(MinecraftServer server) {
		if (!ENABLED || done) {
			return;
		}
		if (server.getPlayerList().getPlayers().isEmpty()) {
			return;
		}
		ServerPlayer p = server.getPlayerList().getPlayers().get(0);
		if (tick < 0 && p.tickCount < 40) {
			return;
		}
		tick++;
		ServerLevel level = p.level();
		PhysicsWorld world = PhysicsWorld.get(level);
		Giant g = GiantManager.get(p);
		int t = tick;
		if (ARENA) {
			try {
				ArenaTest.tick(p, t);
			} catch (Throwable e) {
				TitanMod.LOGGER.error("[autotest] failed at t={}", t, e);
				done = true;
			}
			return;
		}
		try {
			if (t == 1) {
				p.setGameMode(GameType.CREATIVE);
				// The player may have logged out far away (e.g. after the arena test): load the scene's chunk first
				level.getChunk(0, 0);
				ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(0, 0, 0));
				GiantManager.setGiant(p, true, 6.0);
				look(p, 0.5, ground.getY() + 1, -2.5, 0, 5);
			}
			if (t == 3) {
				BlockPos inside = p.blockPosition().above(3);
				boolean ok = level.isUnobstructed(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), inside,
					net.minecraft.world.phys.shapes.CollisionContext.empty());
				TitanMod.LOGGER.info("[autotest] can place block inside giant hitbox: {}", ok);
			}
			if (t == 5) {
				TitanCommands.buildTower(level, ground.offset(0, 0, 20));
				for (int i = 0; i < 3; i++) {
					spawn(level, EntityTypes.ZOMBIE, -9 - i * 2, 18 + i);
				}
				spawn(level, EntityTypes.VILLAGER, 9, 16);
				spawn(level, EntityTypes.VILLAGER, 11, 18);
				spawn(level, EntityTypes.COW, -8, 10);
				spawn(level, EntityTypes.PIG, 8, 10);
				TitanMod.LOGGER.info("[autotest] scene built at ground y={}", ground.getY());
			}
			// --- Phase A: rip a chunk out of the tower, wind up, throw it at the zombies.
			if (t == 50) {
				look(p, 0.5, ground.getY() + 3, 13.0, 0, 20);
			}
			if (t == 55) {
				world.grabbing.grab(p, g, 0);
				TitanMod.LOGGER.info("[autotest] grab -> holding {}", describe(g.hands[0].held));
			}
			if (t > 55 && t <= 70) {
				look(p, p.getX(), p.getY(), p.getZ(), (t - 55) * 4.0F, 25 - (t - 55) * 2.0F);
			}
			if (t > 70 && t <= 74) {
				look(p, p.getX(), p.getY(), p.getZ(), 60 + (t - 70) * 6.0F, 0);
			}
			if (t == 74) {
				world.grabbing.release(p, g, 0);
				TitanMod.LOGGER.info("[autotest] threw chunk");
			}
			// --- Phase B: smash sweeps through the tower with the (empty) hand.
			if (t == 110) {
				look(p, 0.5, ground.getY() + 2, 13.5, -50, 10);
			}
			for (int sweep = 0; sweep < 3; sweep++) {
				int s0 = 115 + sweep * 20;
				if (t > s0 && t <= s0 + 5) {
					float pitch = 20 - sweep * 25;
					float yaw = sweep % 2 == 0 ? -50 + (t - s0) * 20 : 50 - (t - s0) * 20;
					look(p, p.getX(), p.getY(), p.getZ(), yaw, pitch);
				}
			}
			// --- Phase C: ragdolls - launch villagers, grab a zombie and fling it.
			if (t == 190) {
				for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(40), e -> e.getType() == EntityTypes.VILLAGER)) {
					world.launch(e, new Vector3d(0, 16, -8), null);
				}
				TitanMod.LOGGER.info("[autotest] launched villagers, ragdolls={}", world.ragdollCount());
			}
			if (t == 215) {
				heldZombie = level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(40), e -> e.getType() == EntityTypes.ZOMBIE).stream().findFirst().orElse(null);
				if (heldZombie != null) {
					// Stand next to it so the hand is right on top of it.
					look(p, heldZombie.getX() + 5.2, ground.getY() + 1, heldZombie.getZ(), 90, 35);
				}
			}
			if (t == 220) {
				world.grabbing.grab(p, g, 1);
				TitanMod.LOGGER.info("[autotest] grabbed {}", describe(g.hands[1].held));
			}
			if (t > 220 && t <= 236) {
				look(p, p.getX(), p.getY(), p.getZ(), 90 + (t - 220) * 3, 35 - (t - 220) * 3);
			}
			if (t > 236 && t <= 240) {
				look(p, p.getX(), p.getY(), p.getZ(), 138 - (t - 236) * 25, -10);
			}
			if (t == 240) {
				world.grabbing.release(p, g, 1);
				TitanMod.LOGGER.info("[autotest] threw zombie");
			}
			// --- Phase D: knock out the tower's base so the top collapses.
			if (t == 280) {
				look(p, 0.5, ground.getY() + 1, 13.0, 0, 30);
			}
			if (t == 285 || t == 290 || t == 295) {
				world.grabbing.grab(p, g, 0);
			}
			if (t == 287 || t == 292 || t == 297) {
				look(p, p.getX(), p.getY(), p.getZ(), 0, -30);
				world.grabbing.release(p, g, 0);
				look(p, p.getX(), p.getY(), p.getZ(), 0, 30);
			}
			// --- Phase E: close-ups. Giant head/arms in third person, dangle a zombie, fling it, launch others.
			if (t == 330) {
				// Giants no-clip, so sink the giant into the ground until its hand is at zombie height.
				look(p, 0.5, ground.getY() - 6, -14, 0, 20);
			}
			if (t == 336) {
				org.joml.Vector3d hp = g.hands[0].pos;
				BlockPos at = BlockPos.containing(hp.x, ground.getY(), hp.z);
				var z = EntityTypes.ZOMBIE.spawn(level, at, EntitySpawnReason.COMMAND);
				if (z != null) {
					z.setNoAi(true);
				}
				var armored = EntityTypes.ZOMBIE.spawn(level, at.offset(-4, 0, 6), EntitySpawnReason.COMMAND);
				if (armored != null) {
					armored.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
					armored.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
				}
				EntityTypes.SKELETON.spawn(level, at.offset(4, 0, 6), EntitySpawnReason.COMMAND);
			}
			if (t == 342) {
				for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(30),
					e -> e.getType() == EntityTypes.SKELETON || e.getType() == EntityTypes.ZOMBIE && e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() == false)) {
					world.launch(e, new Vector3d(0, 11, 3), null);
				}
			}
			if (t == 350) {
				world.grabbing.grab(p, g, 0);
				TitanMod.LOGGER.info("[autotest] close-up grab -> {}", describe(g.hands[0].held));
			}
			if (t > 350 && t <= 370) {
				look(p, p.getX(), p.getY(), p.getZ(), (t - 350) * 1.5F, 20 - (t - 350) * 1.5F);
			}
			if (t > 370 && t <= 374) {
				look(p, p.getX(), p.getY(), p.getZ(), 30 - (t - 370) * 18, -15);
			}
			if (t == 374) {
				world.grabbing.release(p, g, 0);
			}
			if (t == 6 && START_AT > 6) {
				tick = START_AT - 1;
			}
			// --- Phase F: grab a tree by the trunk (shear solver should take logs + leaves, no dirt) and throw it.
			if (t == 390) {
				look(p, 30.5, ground.getY() - 6, -14, 0, 20);
			}
			if (t == 394) {
				org.joml.Vector3d hp = g.hands[0].pos;
				BlockPos trunk = BlockPos.containing(hp.x, ground.getY(), hp.z);
				for (int y = 0; y < 6; y++) {
					level.setBlock(trunk.above(y), net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState(), 3);
				}
				for (int x = -2; x <= 2; x++) {
					for (int y = 3; y <= 6; y++) {
						for (int z = -2; z <= 2; z++) {
							BlockPos lp = trunk.offset(x, y, z);
							if ((x != 0 || z != 0 || y == 6) && Math.abs(x) + Math.abs(z) < (y >= 5 ? 3 : 4)) {
								level.setBlock(lp, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
									.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 3);
							}
						}
					}
				}
			}
			if (t == 398) {
				world.grabbing.grab(p, g, 0);
				Object held = g.hands[0].held;
				String what = describe(held);
				if (held instanceof dev.titanvr.physics.RigidBody b) {
					int logs = 0, leaves = 0, other = 0;
					for (var st : b.shape.states) {
						if (st == null) {
							continue;
						}
						if (st.is(net.minecraft.tags.BlockTags.LOGS)) {
							logs++;
						} else if (st.is(net.minecraft.tags.BlockTags.LEAVES)) {
							leaves++;
						} else {
							other++;
						}
					}
					what += String.format(" (logs=%d leaves=%d other=%d, mass=%.1f t)", logs, leaves, other, b.mass() / 6.0);
				}
				TitanMod.LOGGER.info("[autotest] tree grab -> {}", what);
			}
			if (t > 398 && t <= 410) {
				look(p, p.getX(), p.getY(), p.getZ(), (t - 398) * 3.0F, 20 - (t - 398) * 3.0F);
			}
			if (t > 410 && t <= 414) {
				look(p, p.getX(), p.getY(), p.getZ(), 36 - (t - 410) * 20, -10);
			}
			if (t == 414) {
				world.grabbing.release(p, g, 0);
				TitanMod.LOGGER.info("[autotest] threw tree");
			}
			// --- Phase G: drop a 3x3x3 gold cube from height: density -> big shockwave.
			if (t == 440) {
				look(p, 0.5, ground.getY() + 4, -40, 0, 30);
				java.util.List<BlockPos> ps = new java.util.ArrayList<>();
				java.util.List<net.minecraft.world.level.block.state.BlockState> sts = new java.util.ArrayList<>();
				for (int x = 0; x < 3; x++) {
					for (int y = 0; y < 3; y++) {
						for (int z = 0; z < 3; z++) {
							ps.add(new BlockPos(-1 + x, ground.getY() + 16 + y, -26 + z));
							sts.add(net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
						}
					}
				}
				var cube = world.spawnVoxelBody(ps, sts, new org.joml.Vector3d(0, -5, 0), true);
				TitanMod.LOGGER.info("[autotest] gold cube mass={} t", cube == null ? -1 : String.format("%.0f", cube.mass()));
				// A leaf block from the same height, for comparison.
				world.spawnVoxelBody(java.util.List.of(new BlockPos(8, ground.getY() + 16, -26)),
					java.util.List.of(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()), new org.joml.Vector3d(0, -5, 0), true);
				for (int i = 0; i < 4; i++) {
					spawn(level, EntityTypes.VILLAGER, -6 + i * 4, -22);
				}
			}
			if (t % 20 == 0) {
				TitanMod.LOGGER.info("[autotest] t={} bodies={} voxels={} ragdolls={} physics={}ms", t, world.bodyCount(), world.voxelCount(), world.ragdollCount(),
					String.format("%.2f", world.avgTickMs));
			}
			// --- Phase H: the player as a normal (PC) player getting flung, first person then third person.
			if (t == 505) {
				GiantManager.setGiant(p, false, 1.0);
				world.stopRagdoll(p);
				look(p, 30.5, ground.getY(), 0.5, -90, 0);
			}
			if (t == 512 || t == 545) {
				look(p, 30.5, ground.getY(), 0.5, -90, 0);
			}
			if (t == 515 || t == 548) {
				world.launch(p, new org.joml.Vector3d(16, 15, 3), null);
				TitanMod.LOGGER.info("[autotest] flung the player");
			}
			// --- Phase I: flung into deep water in survival - should not take fall damage on the pool floor.
			if (t == 580) {
				world.stopRagdoll(p);
				p.setGameMode(GameType.SURVIVAL);
				p.setHealth(p.getMaxHealth());
				// A glass tank of water standing on the flat ground.
				for (int x = 37; x <= 54; x++) {
					for (int z = -5; z <= 5; z++) {
						for (int y = 0; y < 6; y++) {
							boolean wall = x == 37 || x == 54 || z == -5 || z == 5;
							level.setBlockAndUpdate(new BlockPos(x, ground.getY() + y, z),
								(wall ? net.minecraft.world.level.block.Blocks.GLASS : net.minecraft.world.level.block.Blocks.WATER).defaultBlockState());
						}
					}
				}
				look(p, 30.5, ground.getY(), 0.5, -90, 0);
			}
			if (t == 585) {
				world.launch(p, new org.joml.Vector3d(15, 22, 0), null);
			}
			if (t == 585 || t == 600 || t == 615 || t == 630) {
				TitanMod.LOGGER.info("[autotest] water fling t={} health={} pos={} ragdolled={}", t, p.getHealth(), p.position(), world.isRagdolled(p));
			}
			// --- Phase J: every rigged mob launched into the air, watched from the side.
			if (t == 640) {
				world.stopRagdoll(p);
				p.setGameMode(GameType.CREATIVE);
				p.getAbilities().flying = true;
				p.onUpdateAbilities();
				EntityType<?>[] zoo = {EntityTypes.COW, EntityTypes.SHEEP, EntityTypes.PIG, EntityTypes.CREEPER, EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER,
					EntityTypes.WOLF, EntityTypes.CHICKEN, EntityTypes.FOX, EntityTypes.CAT, EntityTypes.OCELOT, EntityTypes.IRON_GOLEM, EntityTypes.VILLAGER,
					EntityTypes.WITCH, EntityTypes.PILLAGER};
				for (int i = 0; i < zoo.length; i++) {
					var e = spawn(level, zoo[i], -100 + (i % 8) * 3, 60 + (i / 8) * 5);
					if (e instanceof LivingEntity le) {
						ZOO.add(le);
					}
				}
				look(p, -89.5, ground.getY() + 3.5, 49, 0, 12);
			}
			if (t == 650 || t == 690) {
				for (LivingEntity e : ZOO) {
					if (e.isAlive()) {
						world.launch(e, new org.joml.Vector3d(0, t == 650 ? 10 : 6, t == 650 ? -2 : 3), null);
					}
				}
				TitanMod.LOGGER.info("[autotest] launched zoo of {}", ZOO.size());
			}
			if (t == 665) {
				for (LivingEntity e : ZOO) {
					TitanMod.LOGGER.info("[autotest] zoo {} ragdoll={}", e.getType().toShortString(), world.ragdollKind(e));
				}
			}
			// Close-ups of each mob lying where it landed (ragdolls are held down meanwhile).
			if (t == 705) {
				TitanMod.holdRagdolls = true;
			}
			if (t >= 712 && (t - 712) % 4 == 0 && (t - 712) / 4 < ZOO.size()) {
				LivingEntity e = ZOO.get((t - 712) / 4);
				org.joml.Vector3d c = world.ragdollCenter(e);
				if (c != null) {
					double dist = 2.2 + e.getBbHeight() * 1.6;
					look(p, c.x - dist * 0.6, c.y + dist * 0.55 - 1.6, c.z - dist * 0.8, (float) Math.toDegrees(Math.atan2(-0.6, 0.8)), 30);
				}
			}
			if (t == 780) {
				TitanMod.holdRagdolls = false;
			}
			// --- Phase K: giant again, slamming a diamond pickaxe into the ground.
			if (t == 790) {
				for (LivingEntity e : ZOO) {
					world.stopRagdoll(e);
				}
				p.getAbilities().flying = true;
				p.onUpdateAbilities();
				GiantManager.setGiant(p, true, 8.0);
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
				look(p, 0.5, ground.getY() - 5, -100.5, 180, -30);
			}
			if (t >= 820 && t <= 826) {
				look(p, p.getX(), p.getY(), p.getZ(), 180, -30 + (t - 820) * 15);
			}
			if (t == 830) {
				TitanMod.LOGGER.info("[autotest] pickaxe slam: bodies={} voxels={}", world.bodyCount(), world.voxelCount());
				TitanMod.LOGGER.info("[autotest] giant health at size 8: {}/{}", p.getHealth(), p.getMaxHealth());
				GiantManager.resize(p, 4.0);
				TitanMod.LOGGER.info("[autotest] giant health at size 4: {}/{}", p.getHealth(), p.getMaxHealth());
				GiantManager.resize(p, 8.0);
				try {
					java.lang.reflect.Method fly = net.minecraft.world.entity.player.Player.class.getDeclaredMethod("getFlyingSpeed");
					fly.setAccessible(true);
					Object walking = fly.invoke(p);
					p.setSprinting(true);
					Object sprinting = fly.invoke(p);
					p.setSprinting(false);
					TitanMod.LOGGER.info("[autotest] giant flying speed: normal={} sprinting={}", walking, sprinting);
				} catch (ReflectiveOperationException e) {
					TitanMod.LOGGER.warn("[autotest] flying speed check failed", e);
				}
				look(p, p.getX(), p.getY(), p.getZ(), 180, 40);
			}
			// --- Phase L: the giant fires an arrow at a little village scene.
			if (t == 845) {
				p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
				for (int i = 0; i < 4; i++) {
					spawn(level, EntityTypes.VILLAGER, -3 + i * 2, -160);
				}
				for (int x = -4; x <= 4; x++) {
					for (int y = 0; y < 4; y++) {
						level.setBlockAndUpdate(new BlockPos(x, ground.getY() + y, -164), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
					}
				}
				look(p, 0.5, ground.getY() - 3, -120.5, 180, 10);
			}
			if (t == 851) {
				var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(level, p, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
				arrow.shootFromRotation(p, p.getXRot(), p.getYRot(), 0.0F, 3.0F, 0.0F);
				level.addFreshEntity(arrow);
				TitanMod.LOGGER.info("[autotest] giant arrow: scale={} size={}x{} speed={}", dev.titanvr.physics.GiantProjectiles.scaleOf(arrow),
					arrow.getBbWidth(), arrow.getBbHeight(), arrow.getDeltaMovement().length() * 20);
			}
			if (t == 895) {
				TitanMod.LOGGER.info("[autotest] DONE");
				done = true;
			}
		} catch (Throwable e) {
			TitanMod.LOGGER.error("[autotest] failed at t={}", t, e);
			done = true;
		}
	}

	private static String describe(Object o) {
		if (o == null) {
			return "nothing";
		}
		if (o instanceof dev.titanvr.physics.RigidBody b) {
			return "body of " + b.shape.count() + " voxels";
		}
		return o.getClass().getSimpleName();
	}

	private static final java.util.List<LivingEntity> ZOO = new java.util.ArrayList<>();

	private static net.minecraft.world.entity.Entity spawn(ServerLevel level, EntityType<?> type, int x, int z) {
		BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
		var e = type.spawn(level, pos, EntitySpawnReason.COMMAND);
		if (e instanceof net.minecraft.world.entity.Mob mob) {
			mob.setPersistenceRequired();
		}
		return e;
	}

	private static void look(ServerPlayer p, double x, double y, double z, float yaw, float pitch) {
		p.connection.teleport(x, y, z, yaw, pitch);
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
	}
}
