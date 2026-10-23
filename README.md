# Titan VR

A Minecraft **26.2** Fabric mod: the VR player is a **giant**. Fly and hover over the world, rip chunks out of
buildings and terrain, smash through towers with your hands, and pick up mobs and players to throw them, all
with a real physics engine and ragdolls. Inspired by *Voxel Destruct 2* on Roblox.

Built on **Vivecraft** (stereo rendering + head/hand tracking). Titan VR adds the gameplay on top.

## Quick setup (5 minutes)

1. **Install [Prism Launcher](https://prismlauncher.org/)** and add your Microsoft account.
2. **Create an instance**: *Add Instance* → Minecraft **26.2** → Mod loader **Fabric** (latest loader) → OK.
3. **Add the mods**: select the instance → *Edit* → *Mods* → *Download mods*, search for and select
   - **Fabric API**
   - **Vivecraft**

   then press *Review and confirm* → OK.
4. **Add Titan VR**: download `titan-vr-x.y.z.jar` from the
   [latest release](https://github.com/franktheglock/titan-vr/releases/latest) and drag it onto the *Mods* list
   (or drop it in the instance's `mods/` folder).
5. **Recommended for VR**: *Edit* → *Settings* → *Java*: set maximum memory to **6-8 GB** and add the JVM
   argument `-XX:+UseZGC` (fewer frame hitches; Vivecraft asks for it).
6. Launch. You'll be a giant as soon as you're in VR.

Not using Prism? Install [Fabric](https://fabricmc.net/use/installer/) for 26.2 and put
[Fabric API](https://modrinth.com/mod/fabric-api), [Vivecraft](https://modrinth.com/mod/vivecraft) and the
Titan VR jar in `.minecraft/mods/`.

Works without a headset too: desktop players are normal-sized humans (Vivecraft runs fine in non-VR mode), and
can try being a giant with `/titan giant on`.

### Quest 3 / Quest 2

Vivecraft uses OpenVR, so the chain is **Quest → Meta Quest Link (cable or Air Link) → SteamVR → Minecraft**.
Start Quest Link, launch SteamVR, then start Minecraft and press the **VR** button on the title screen (or set
Vivecraft to start in VR).

### Playing on a server

1. Set up a **Fabric server** for **26.2** ([installer](https://fabricmc.net/use/server/), needs Java 25).
2. Put **Fabric API**, **Vivecraft** and **Titan VR** in the server's `mods/` folder. The server needs Vivecraft
   too: that's how it receives everyone's headset and hand positions.
3. Every player needs the same three mods (non-VR players too, or they won't see giants, debris and ragdolls).
4. Run `op <your name>` in the server console for the admin commands below. Settings live in
   `config/titanvr.json` and can be changed live with `/titan config`.

### Paper servers

There's also a Paper plugin, `titan-vr-paper-x.y.z.jar`, for running a [Paper](https://papermc.io/) 26.2 server
instead of a Fabric one. It runs the same physics, giants and arena; players still install the Titan VR mod (and
Vivecraft) on their clients exactly as above.

1. Put `titan-vr-paper-x.y.z.jar` and the
   [Vivecraft Spigot Extension](https://modrinth.com/plugin/vivecraft-spigot-extension) plugin (it gives the server
   everyone's headset and hand positions) in the server's `plugins/` folder. Use a Vivecraft Spigot Extension version
   that matches the players' Vivecraft version.
2. For an arena world, set `level-type=titanvr\:arena` in `server.properties` before the world is first created (the
   plugin brings the world type with it). Or build an arena anywhere with `/titan arena build`.
3. Settings live in `plugins/TitanVR/titanvr.json`; `/titan config` works as usual.

Differences from the Fabric server: arrows and other projectiles fired by giants grow and hit like giant ones, but
their hitbox for hitting mobs stays normal-sized, and the server doesn't stop giants from being squeezed into a
crouching pose when wading through tight spots (it doesn't affect their reach or grabbing).

## Playing as a giant

VR players turn into giants automatically when they join (size 8 by default). Anyone can also use
`/titan giant on [scale]`.

Giants are tougher the bigger they are: every size step adds 10 hearts (a size-8 giant has 80), and they take no fall
or debris damage.

Like Voxel Destruct 2, a giant has **no body**: everyone else sees a huge floating head (their skin) and two
huge arms. The head and hands follow the giant's headset and controllers **live**: Vivecraft streams VR poses to
every client and Titan VR draws them interpolated each frame, so desktop players see every head turn and hand
gesture. Giants hover permanently and pass through blocks with their (invisible) body. Only their hands interact
with the world.

| Action | VR | Desktop |
| --- | --- | --- |
| Grab | Squeeze **grip** near something | Hold **R** (right hand) / **F** (left hand) |
| Throw | Swing and let go of the grip | Let go: it flies where you look |
| Smash | Swing your hands through blocks fast | Whip the camera around (hands are in front of you) |
| Tool smash | Hold a pickaxe/axe/sword/mace/trident/spear and swing it | Same, holding the tool |
| Giant projectiles | Shoot a bow/crossbow, throw a trident or snowball | Same |
| Fly / move / turn | Normal Vivecraft controls (you always hover) | Normal creative flight |
| Break / place blocks, use items | Normal Vivecraft controls (trigger, A) | Normal |
| Resize | `/titan size <n>` | `/titan size <n>` |

Grabbing only uses the grips, so every other button works as usual in Vivecraft. While you're a giant the grips
don't scroll the hotbar; pick items by touching the hotbar with your hand. Squeezing a grip with nothing nearby
does nothing.

What you can grab:

- **Blocks/terrain**: a chunk is torn out of the world around your hand and becomes one rigid body.
- **Debris**: any flying or resting physics voxel.
- **Players and most mobs**: full articulated **ragdolls** with a rig per body plan - humanoids (players,
  zombies, skeletons, piglins), villagers/witches/illagers, cows, sheep, pigs, creepers, spiders and cave
  spiders, wolves, chickens, foxes, cats, ocelots and iron golems.
- **Other mobs** (and babies): they tumble as rigid ragdolls.
- **Anything else**: items, primed TNT, boats, minecarts, arrows.

**Tools have hitboxes.** Whatever a giant holds is giant too: a pickaxe, axe, shovel, hoe, sword, mace,
trident or spear becomes a chain of colliders from your fist to its head, and the head moves faster than your
hand, so it hits harder. The right tool bites deeper (a pickaxe through stone, an axe through wood), and a mace
hits like a wrecking ball. Everyone else sees the giant-sized item in your hand.

**Projectiles are giant too.** Arrows, tridents, snowballs and the like fired by a giant are scaled up to the
giant's size, fly faster, bowl over everything their whole width passes through, punch through blocks while
they still have the momentum, and land with a shockwave.

**Getting flung on desktop**: when you're ragdolled, your first-person camera rides in your ragdoll's head and
tumbles with it. When you land, you face the way you ended up.

## Minigame: kill the giant

A ready-made arena where one VR giant takes on everyone else. The hunters share a pool of lives (25 by default)
and win by killing the giant; the giant wins by using them all up or by surviving the time limit (10 minutes).

**The easy way: an arena world.** In *Create World* → *More* → *World Type*, pick **Titan VR Arena** (on a server,
set `level-type=titanvr\:arena` in `server.properties` before the world is first created). You get an empty sky
world with the arena already built, endless daytime, no mobs or weather, and everyone spawning in the lobby.
Games start by themselves 30 seconds after there's a VR player (the giant) and at least one other player, and the
next one starts after each game ends. With several VR players, they take turns being the giant. Players who join
mid-game become hunters. Turn auto-start off with `/titan config arenaAutoStart false`.

**Or in any world:**

1. Stand where you want the arena and run `/titan arena build [radius]` (default radius 40). It replaces the
   terrain with a walled field of towers, houses, trees and boulders, plus a glass lobby high above the middle.
2. Run `/titan game start <giant> [lives]`. Everyone else online becomes a hunter and is sent to the lobby.
3. Hunters click a class in chat (or `/titan class <name>`), then walk off the edge of the lobby to drop in. The
   landing is safe. When you die you're back in the lobby with your class kit, and you can switch class there.
   Dying when the team is out of lives puts you out as a spectator.

| Class | Kit | Trade-off |
| --- | --- | --- |
| Archer | Power IV bow, endless arrows, Speed | Leather armour |
| Aviator | Elytra, 16 rockets, Multishot crossbow | No chestplate, little ammo |
| Smasher | Mace (Density, Wind Burst), wind charges, iron armour | Slowness |
| Bomber | 6 splash potions of Harming II, TNT, chainmail | Few shots between resupplies |
| Scout | Sharpness sword, ender pearls, Speed II, Jump Boost II | No armour |

Ammo is topped up every 30 seconds. The giant has a health bar everyone can see, can't leave the arena or reach the
lobby, and doesn't heal by eating. The arena rebuilds itself every 2.5 minutes (and after each game), and everyone
gets their inventory, game mode and position back when the game ends. Other commands: `/titan game stop`,
`/titan game join` (join a running game), `/titan arena regen | lobby | forget`. Settings start with `arena`
(`/titan config arenaLives 30`, `arenaTimeLimitSeconds`, `arenaRegenSeconds`, `arenaGiantScale`, ...).

## The physics

- Compound voxel **rigid bodies** with proper mass/inertia tensors, sequential-impulse contact solving with
  friction and restitution, adaptive sub-stepping (no tunnelling through 1-block walls), and sleeping.
- **Destruction**: anything hitting a block with enough kinetic energy breaks it (harder blocks need more),
  turning it into more debris.
- **Fracture**: chunks that slam into something hard shatter into smaller pieces.
- **Structural collapse**: blocks that lose all support (cut a tower's base, chop a tree) fall as rigid
  bodies. Natural stone counts as bedrock-solid ground, so terrain doesn't cave in.
- **Ragdolls**: position-based-dynamics skeletons whose particles sit on the joints of each mob's vanilla model,
  so every leg, arm and head swings on its own. They collide with the world, debris and the giant's hands,
  take impact damage (credited to whoever threw them), and stand back up when they come to rest.
- **Water and lava**: buoyancy from each body's density (logs float, stone sinks) and heavy drag, so a flung
  player splashes down instead of slamming into the bottom of a lake.
- **Rubble**: resting debris eventually turns back into placed blocks (`rubbleMode place`) or vanishes
  (`despawn`).

## Commands

| Command | |
| --- | --- |
| `/titan` | Help |
| `/titan giant [on [scale] \| off]` | Toggle giant mode for yourself |
| `/titan giant <players> on [scale] \| off` | (op) for others |
| `/titan size <1-16>` | Change size |
| `/titan tower` | (op) Build a destructible tower in front of you |
| `/titan ragdoll <targets> [speed]` | (op) Launch entities as ragdolls |
| `/titan stats` | Body/voxel/ragdoll counts and physics cost |
| `/titan clear` | (op) Remove all debris |
| `/titan blast <pos> [radius]` | (op) Blow a ball of blocks apart into debris |
| `/titan config <key> [value]` | (op) Live tuning; saved to `config/titanvr.json` |
| `/titan arena build [radius] \| regen \| lobby \| forget` | (op) The minigame arena |
| `/titan game start <giant> [lives] \| stop` | (op) Start or stop a kill-the-giant game |
| `/titan game join`, `/titan class <name>` | Join a running game; pick a class in the lobby |

Useful config keys: `defaultGiantScale`, `throwMultiplier`, `destructionMultiplier`, `handSmashSpeed`,
`structuralCollapse`, `rubbleMode`, `maxVoxels`, `impactDamageMultiplier`, `giantHealthPerScale`, `autoGiantVR`.

## Building

```
gradlew build      # -> build/libs/titan-vr-0.3.0.jar
gradlew runClient  # dev client (Vivecraft is on the dev classpath)
gradlew runAutotest  # scripted smoke test (needs a flat creative world saved as run/saves/TitanTest)
gradlew runAutotest -PautotestArena=true  # the same for the arena minigame
gradlew runServer    # dev dedicated server in run-server/ (accept the EULA in run-server/eula.txt)
gradlew runJoinServer  # dev client that joins it
gradlew :paper:build   # -> paper/build/libs/titan-vr-paper-0.3.0.jar (the Paper plugin)
```

Requires JDK 25. Vivecraft is pulled from Modrinth's Maven repository.

## License

Apache License 2.0 (see [LICENSE](LICENSE)). Vivecraft is a separate project (LGPLv3) and is not included here.
