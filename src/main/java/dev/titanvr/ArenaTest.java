package dev.titanvr;

import com.mojang.authlib.GameProfile;
import dev.titanvr.arena.Arena;
import dev.titanvr.arena.ArenaGame;
import dev.titanvr.arena.ArenaLayout;
import dev.titanvr.arena.HunterClass;
import dev.titanvr.giant.GiantManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Dev-only arena scenario (-Dtitanvr.autotest.arena=true): the test player is a hunter, a fake player is the giant. */
public final class ArenaTest {
	private static int phase;
	private static int phaseStart;
	private static ServerPlayer giant;
	private static BlockPos center;
	private static int landed;
	/** Ticks at which the client half takes screenshots (it reads {@link #shot}). */
	public static volatile String shot;

	private ArenaTest() {
	}

	private static void next(int t) {
		phase++;
		phaseStart = t;
	}

	private static void log(String msg, Object... args) {
		TitanMod.LOGGER.info("[autotest] " + msg, args);
	}

	private static String inventory(ServerPlayer p) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty()) {
				sb.append(s.getCount()).append("x").append(s.getItem().toString().replace("minecraft:", "")).append(s.isEnchanted() ? "*" : "").append(' ');
			}
		}
		return sb.toString().trim();
	}

	static void tick(ServerPlayer p, int t) {
		ServerLevel level = p.level();
		TitanConfig cfg = TitanConfig.INSTANCE;
		int dt = t - phaseStart;
		switch (phase) {
			case 0 -> {
				p.setGameMode(GameType.CREATIVE);
				if (GiantManager.isGiant(p)) {
					GiantManager.setGiant(p, false, 1);
				}
				center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(1000, 0, 0));
				p.teleportTo(level, center.getX() + 50, center.getY() + 30, center.getZ() + 0.5, Set.of(), 90, 30, false);
				next(t);
			}
			case 1 -> {
				if (dt == 40) {
					center = new BlockPos(1000, -60, 0);
					Arena.create(level.getServer(), level, center, 30, false);
					center = Arena.get().center;
					log("arena build started at {}", center);
				}
				if (dt > 40 && !Arena.get().isRegenerating()) {
					log("arena built in {} ticks, mismatches={}", dt - 40, Arena.get().mismatches(level));
					p.teleportTo(level, center.getX() + 48, center.getY() + 40, center.getZ() + 0.5, Set.of(), 90, 38, false);
					next(t);
				}
			}
			case 2 -> {
				if (dt == 30) {
					shot = "arena_overview";
				}
				if (dt == 40) {
					for (HunterClass c : HunterClass.values()) {
						c.equip(p);
						log("kit {}: {}", c.id(), inventory(p));
					}
					p.getInventory().clearContent();
					p.removeAllEffects();
					p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
					p.setGameMode(GameType.SURVIVAL);
					giant = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "TestGiant"));
					ArenaGame.TEST_PLAYERS.put(giant.getUUID(), giant);
					cfg.arenaCountdownSeconds = 3;
					cfg.arenaRegenSeconds = 12;
					cfg.arenaResupplySeconds = 6;
					String err = ArenaGame.start(level.getServer(), giant, List.of(p), 2);
					log("game start: err={} state={} hunterInLobby={} mode={} giantHP={}/{}", err, ArenaGame.get().state(),
						Arena.get().inLobby(p.position()), p.gameMode(), giant.getHealth(), giant.getMaxHealth());
					next(t);
				}
			}
			case 3 -> {
				if (dt == 5) {
					log("choose smasher: err={} inv={}", ArenaGame.chooseClass(p, HunterClass.SMASHER), inventory(p));
					p.teleportTo(level, center.getX() + 0.5, center.getY() + ArenaLayout.LOBBY_Y + 1, center.getZ() - 5.5, Set.of(), 0, 60, false);
				}
				if (dt == 15) {
					// Walking off during the countdown is undone
					p.teleportTo(level, center.getX() + 0.5, center.getY() + ArenaLayout.LOBBY_Y - 3, center.getZ() + 12.5, Set.of(), 0, 60, false);
				}
				if (dt == 18) {
					log("left lobby during countdown -> back in lobby: {}", Arena.get().inLobby(p.position()));
					shot = "arena_lobby";
				}
				if (ArenaGame.get().state() == ArenaGame.State.RUNNING) {
					log("game running after {} ticks", dt);
					next(t);
				}
			}
			case 4 -> {
				if (dt == 2) {
					// Jump: off the edge of the platform, a 50-block drop
					p.teleportTo(level, center.getX() + 0.5, center.getY() + ArenaLayout.LOBBY_Y - 1, center.getZ() + 12.5, Set.of(), 180, 20, false);
				}
				if (dt > 5 && p.onGround() && ++landed == 20) {
					log("landed after {} ticks: y={} health={}", dt, p.getY() - center.getY(), p.getHealth());
					next(t);
				}
				if (dt > 200) {
					log("FAIL never landed y={}", p.getY());
					next(t);
				}
			}
			case 5 -> {
				if (dt == 5) {
					shot = "arena_ground";
				}
				if (dt == 10) {
					int broken = 0;
					for (int x = -6; x <= 6; x++) {
						for (int y = 0; y < 8; y++) {
							for (int z = -6; z <= 6; z++) {
								BlockPos b = center.offset(x + 15, y, z);
								if (!level.getBlockState(b).isAir()) {
									level.setBlock(b, Blocks.AIR.defaultBlockState(), 3);
									broken++;
								}
							}
						}
					}
					p.getInventory().clearContent();
					log("smashed {} blocks, mismatches={}, emptied inventory", broken, Arena.get().mismatches(level));
				}
				if (dt == 300) {
					log("after regen + resupply: mismatches={} inv={}", Arena.get().mismatches(level), inventory(p));
					// Out of bounds: thrown over the wall
					p.teleportTo(level, center.getX() + 45.5, center.getY() + 3, center.getZ() + 0.5, Set.of(), 0, 0, false);
				}
				if (dt == 302) {
					log("out of bounds -> distance from centre {}", Arena.get().distance(p.position()));
					p.hurtServer(level, level.damageSources().playerAttack(giant), 1000);
					log("killed hunter: alive={}", p.isAlive());
					next(t);
				}
			}
			case 6 -> {
				// The client half presses respawn
				if (!p.isAlive()) {
					phaseStart = t;
				}
				if (p.isAlive() && dt > 3) {
					log("respawned: inLobby={} pos={} mode={} inv={}", Arena.get().inLobby(p.position()), p.position().subtract(Vec3.atLowerCornerOf(center)), p.gameMode(), inventory(p));
					next(t);
				}
			}
			case 7 -> {
				ServerPlayer np = p;
				if (dt == 5) {
					np.teleportTo(level, center.getX() + 0.5, center.getY() + 1, center.getZ() + 10.5, Set.of(), 0, 0, false);
				}
				if (dt == 10) {
					// Fabric's fake players can't be hurt, so the giant's death is simulated
					log("hunter hurting hunter allowed: {}; giant hurt by hunter allowed: {}",
						ArenaGame.allowDamage(np, level.damageSources().playerAttack(np)), ArenaGame.allowDamage(giant, level.damageSources().playerAttack(np)));
				}
				if (dt == 15) {
					ArenaGame.onDeath(giant, level.damageSources().playerAttack(np));
					log("killed giant: state={}", ArenaGame.get() == null ? "none" : ArenaGame.get().state());
				}
				if (dt > 15 && ArenaGame.get() == null) {
					log("game over and restored: mode={} inv={} pos={} giant(player)={}", np.gameMode(), inventory(np), np.blockPosition(), GiantManager.isGiant(np));
					next(t);
				}
				if (dt > 400) {
					log("FAIL game never finished");
					next(t);
				}
			}
			case 8 -> {
				if (dt == 60) {
					log("DONE");
					AutoTest.done = true;
				}
			}
			default -> {
			}
		}
	}
}
