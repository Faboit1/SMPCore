# SiftVanilla 2 server change log (undo log)

Server: "SiftVanilla 2" on the Pterodactyl panel, identifier `93babcbd` (uuid 93babcbd-cf82-4a57-a61e-d5fbd91f83bd), allocation 37.27.67.240:25602.
No other server on the panel was touched.

Every change made to the server is listed here in order, with how to revert it. All paths are relative to the server root.
The owner said full backups are optional (the server was brand new), so only small config files were kept as `.bak` copies.

| # | When (UTC) | Change | How to revert |
|---|------------|--------|---------------|
| 1 | 2026-10-07 21:27 | Pulled `canvas-26.2-962.jar` (Canvas build 962, MC 26.2, sha256 a4139189d3bc09695afea21a2595d435f530e21ebb099065b8082d9f14ec93e5) from jenkins.canvasmc.io via the panel's remote-download endpoint. | Delete the file. |
| 2 | 2026-10-07 21:27 | Stopped the server. Renamed `server.jar` (Paper 26.3-159) to `paper-26.3-159.jar.bak-20261007`, renamed `canvas-26.2-962.jar` to `server.jar`. | Stop, rename `paper-26.3-159.jar.bak-20261007` back to `server.jar`. |
| 3 | 2026-10-07 21:27 | Deleted `world/` (an empty 26.3 world that 26.2 cannot load), `versions/`, `libraries/`, `cache/`, `.paper/`, `.cache/`, `usercache.json`. All regenerate on start. | Not needed; Paper re-creates them. A 26.3 world cannot be opened by 26.2 anyway. |
| 4 | 2026-10-07 21:28 | Renamed `config/paper-global.yml` and `config/paper-world-defaults.yml` (Paper 26.3 schema) to `*.bak-26.3` so Canvas generates its own. | Rename back (only valid when going back to Paper 26.3). |
| 5 | 2026-10-07 21:28 | Started the server on Canvas: clean boot, new 26.2 world generated. | See row 2. |
| 6 | 2026-10-08 06:13 | Pulled five plugins into `plugins/` with the panel's remote download (sizes checked against the release files): `LuckPerms-Bukkit-5.5.87.jar` (1,509,479 bytes), `PlaceholderAPI-2.12.3.jar` (1,160,690), `VaultUnlocked-2.20.3.jar` (134,932), `ViaVersion-5.12.0.jar` (6,503,775), `Chunky-Bukkit-1.5.3.jar` (304,616). Each one creates its own folder under `plugins/` on first start. | Stop the server, delete the jar and its `plugins/<Name>/` folder. |
| 7 | 2026-10-08 06:18 | Stopped the server for the config window. | Start it. |
| 8 | 2026-10-08 06:18 | Rewrote `server.properties`, `bukkit.yml`, `spigot.yml` and `config/{paper-global,paper-world-defaults,canvas-server,canvas-worlds}.yml` with the tuned values in the table below (comments and every other key unchanged). The set was boot-tested first on a copy of the server; see `docs/research/deps-and-tuning.md` part 2 for the reasoning behind each value. | Stop the server and set each key back to its "before" value from the table, or delete the YAML file to let the server regenerate defaults (server.properties: edit the lines). |
| 9 | 2026-10-08 06:18 | Wrote anti-xray settings (engine mode 1) into `world/dimensions/minecraft/overworld/paper-world.yml` (max height 64, default ore list) and `world/dimensions/minecraft/the_nether/paper-world.yml` (max height 128; ancient debris, nether gold and quartz ore, gilded blackstone). Both files previously held only the header and `_version: 31`. | Delete the `anticheat:` block from both files and restart. |
| 10 | 2026-10-08 06:19 | Started the server: clean boot on Canvas 962 (AFFINITY scheduler, 2 tick threads, 2 chunk workers); all five plugins enabled; only the expected Canvas SIMD notice (a JVM flag the panel cannot set). | - |
| 11 | 2026-10-08 06:20 | Game rules in all three dimensions (`execute in <dim> run gamerule ...`): `show_advancement_messages false`, `spawn_phantoms false`, `locator_bar false`, `players_sleeping_percentage 25`, `spectators_generate_chunks false`. | Run the same commands with the defaults: `true`, `true`, `true`, `100`, `true`. |
| 12 | 2026-10-08 06:20 | World borders, centred on 0.0, 0.0: overworld 10,000 blocks wide, nether 5,000, end 6,000 (`worldborder center 0.0 0.0` and `worldborder set <size>`, nether/end through `execute in`). SiftCore's RTP rings are sized to fit inside them. | `worldborder set 59999968` in each dimension. |
| 13 | 2026-10-08 06:21 | `plugins/Chunky/config.yml`: `continue-on-restart: true` (was `false`) and `update-interval: 60` (was `1`). Started pre-generation: `world` square radius 5112, `world_nether` 2612, `world_the_end` 3112 (border radius plus 112 blocks for the view distance). Chunky resumes after restarts until done. | `chunky cancel` stops the tasks. Generated chunks are ordinary world data and stay. |
| 14 | 2026-10-08 06:21 | LuckPerms: created groups `supporter` (weight 10), `patron` (20), `elite` (30), `legend` (40). Each inherits the one below it (`supporter` inherits `default`), has a display name, and has meta `siftcore-rank` = its label. Also created track `ranks` (supporter, patron, elite, legend). Rank permissions are added when SiftCore is deployed (row 15 onward). | `lp deletegroup <name>` for each group and `lp deletetrack ranks`. |

### Row 8 detail: tuned config values

| File | Key | Before | After |
|------|-----|--------|-------|
| `server.properties` | `view-distance` | `10` | `6` |
| `server.properties` | `simulation-distance` | `10` | `4` |
| `server.properties` | `sync-chunk-writes` | `true` | `false` |
| `server.properties` | `max-players` | `20` | `150` |
| `server.properties` | `network-compression-threshold` | `256` | `256` |
| `bukkit.yml` | `spawn-limits.monsters` | `70` | `50` |
| `bukkit.yml` | `spawn-limits.animals` | `10` | `8` |
| `bukkit.yml` | `spawn-limits.water-animals` | `5` | `3` |
| `bukkit.yml` | `spawn-limits.water-ambient` | `20` | `3` |
| `bukkit.yml` | `spawn-limits.water-underground-creature` | `5` | `3` |
| `bukkit.yml` | `spawn-limits.axolotls` | `5` | `3` |
| `bukkit.yml` | `spawn-limits.ambient` | `15` | `1` |
| `bukkit.yml` | `ticks-per.monster-spawns` | `1` | `5` |
| `bukkit.yml` | `ticks-per.water-spawns` | `1` | `400` |
| `bukkit.yml` | `ticks-per.water-ambient-spawns` | `1` | `400` |
| `bukkit.yml` | `ticks-per.water-underground-creature-spawns` | `1` | `400` |
| `bukkit.yml` | `ticks-per.axolotl-spawns` | `1` | `400` |
| `bukkit.yml` | `ticks-per.ambient-spawns` | `1` | `400` |
| `spigot.yml` | `settings.save-user-cache-on-stop-only` | `false` | `true` |
| `spigot.yml` | `world-settings.default.mob-spawn-range` | `8` | `4` |
| `spigot.yml` | `world-settings.default.entity-activation-range.animals` | `32` | `16` |
| `spigot.yml` | `world-settings.default.entity-activation-range.monsters` | `32` | `24` |
| `spigot.yml` | `world-settings.default.entity-activation-range.raiders` | `64` | `48` |
| `spigot.yml` | `world-settings.default.entity-activation-range.misc` | `16` | `8` |
| `spigot.yml` | `world-settings.default.entity-activation-range.water` | `16` | `8` |
| `spigot.yml` | `world-settings.default.entity-activation-range.villagers` | `32` | `16` |
| `spigot.yml` | `world-settings.default.entity-activation-range.flying-monsters` | `32` | `48` |
| `spigot.yml` | `world-settings.default.entity-activation-range.tick-inactive-villagers` | `true` | `false` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.players` | `128` | `64` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.animals` | `96` | `48` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.monsters` | `96` | `48` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.misc` | `96` | `32` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.display` | `128` | `64` |
| `spigot.yml` | `world-settings.default.entity-tracking-range.other` | `64` | `64` |
| `spigot.yml` | `world-settings.default.merge-radius.item` | `0.5` | `2.5` |
| `spigot.yml` | `world-settings.default.merge-radius.exp` | `-1.0` | `3.0` |
| `spigot.yml` | `world-settings.default.arrow-despawn-rate` | `1200` | `300` |
| `spigot.yml` | `world-settings.default.nerf-spawner-mobs` | `false` | `true` |
| `config/paper-global.yml` | `threaded-regions.threads` | `-1` | `2` |
| `config/paper-global.yml` | `threaded-regions.scheduler` | `EDF` | `AFFINITY` |
| `config/paper-global.yml` | `chunk-system.worker-threads` | `-1` | `2` |
| `config/paper-global.yml` | `chunk-system.io-threads` | `-1` | `1` |
| `config/paper-global.yml` | `chunk-loading-basic.player-max-chunk-generate-rate` | `-1.0` | `25.0` |
| `config/paper-world-defaults.yml` | `chunks.max-auto-save-chunks-per-tick` | `24` | `8` |
| `config/paper-world-defaults.yml` | `chunks.entity-per-chunk-save-limit.arrow` | `-1` | `16` |
| `config/paper-world-defaults.yml` | `chunks.entity-per-chunk-save-limit.experience_orb` | `-1` | `16` |
| `config/paper-world-defaults.yml` | `chunks.entity-per-chunk-save-limit.fireball` | `-1` | `8` |
| `config/paper-world-defaults.yml` | `chunks.entity-per-chunk-save-limit.small_fireball` | `-1` | `8` |
| `config/paper-world-defaults.yml` | `chunks.entity-per-chunk-save-limit.snowball` | `-1` | `8` |
| `config/paper-world-defaults.yml` | `collisions.max-entity-collisions` | `8` | `2` |
| `config/paper-world-defaults.yml` | `entities.armor-stands.do-collision-entity-lookups` | `true` | `false` |
| `config/paper-world-defaults.yml` | `entities.armor-stands.tick` | `true` | `false` |
| `config/paper-world-defaults.yml` | `entities.markers.tick` | `true` | `false` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.monster.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.monster.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.ambient.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.ambient.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.water_ambient.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.water_ambient.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.water_creature.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.water_creature.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.underground_water_creature.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.underground_water_creature.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.axolotls.soft` | `default` | `30` |
| `config/paper-world-defaults.yml` | `entities.spawning.despawn-ranges.axolotls.hard` | `default` | `56` |
| `config/paper-world-defaults.yml` | `entities.spawning.non-player-arrow-despawn-rate` | `default` | `60` |
| `config/paper-world-defaults.yml` | `entities.spawning.creative-arrow-despawn-rate` | `default` | `60` |
| `config/paper-world-defaults.yml` | `entities.spawning.alt-item-despawn-rate.enabled` | `false` | `true` |
| `config/paper-world-defaults.yml` | `entities.spawning.alt-item-despawn-rate.items` | `{cobblestone: 300}` | `[cobblestone: 300, cobbled_deepslate: 300, netherrack: 300, dirt: 300, tuff: 300, granite: 300, diorite: 300, andesite: 300]` |
| `config/paper-world-defaults.yml` | `environment.optimize-explosions` | `false` | `true` |
| `config/paper-world-defaults.yml` | `environment.treasure-maps.find-already-discovered.loot-tables` | `default` | `true` |
| `config/paper-world-defaults.yml` | `environment.treasure-maps.find-already-discovered.villager-trade` | `false` | `true` |
| `config/paper-world-defaults.yml` | `fixes.fix-items-merging-through-walls` | `false` | `true` |
| `config/paper-world-defaults.yml` | `hopper.ignore-occluding-blocks` | `false` | `true` |
| `config/paper-world-defaults.yml` | `misc.redstone-implementation` | `VANILLA` | `ALTERNATE_CURRENT` |
| `config/paper-world-defaults.yml` | `misc.update-pathfinding-on-block-update` | `true` | `false` |
| `config/paper-world-defaults.yml` | `tick-rates.grass-spread` | `1` | `4` |
| `config/paper-world-defaults.yml` | `tick-rates.mob-spawner` | `1` | `2` |
| `config/paper-world-defaults.yml` | `tick-rates.behavior.villager.validatenearbypoi` | `-1` | `60` |
| `config/paper-world-defaults.yml` | `tick-rates.behavior.villager.acquirepoi` | `(absent)` | `120` |
| `config/paper-world-defaults.yml` | `tick-rates.sensor.villager.secondarypoisensor` | `40` | `80` |
| `config/paper-world-defaults.yml` | `tick-rates.sensor.villager.nearestbedsensor` | `(absent)` | `80` |
| `config/canvas-server.yml` | `networking.filter-velocity-packet` | `false` | `true` |
| `config/canvas-server.yml` | `networking.filter-move-packets` | `false` | `true` |
| `config/canvas-server.yml` | `networking.alternative-player-list-tick` | `false` | `true` |
| `config/canvas-server.yml` | `networking.purpur-alternative-keepalive` | `false` | `true` |
| `config/canvas-server.yml` | `chunk-system.fluid-post-processing-algorithm` | `"VANILLA"` | `"FILTERED"` |
| `config/canvas-server.yml` | `chunk-system.optimize-treasure-map-locating` | `false` | `true` |
| `config/canvas-server.yml` | `cache-minecraft2-bukkit-entity-type-conversion` | `false` | `true` |
| `config/canvas-server.yml` | `disable-locator-bar-in-all-worlds` | `false` | `true` |
| `config/canvas-server.yml` | `logs.enable-log-cleaner` | `false` | `true` |
| `config/canvas-server.yml` | `logs.cleaner-time-span` | `"30d"` | `"14d"` |
| `config/canvas-worlds.yml` | `entities.fast-orbs` | `false` | `true` |
| `config/canvas-worlds.yml` | `entities.villagers.villager-acquire-poi-tasks-load-chunks` | `true` | `false` |
| `config/canvas-worlds.yml` | `cactus-check-survival-before-growth` | `false` | `true` |
| `config/canvas-worlds.yml` | `enable-suffocation-optimization` | `false` | `true` |
| `config/canvas-worlds.yml` | `visuals.particles.disable-sprint-particles` | `false` | `true` |
| `config/canvas-worlds.yml` | `visuals.particles.disable-effect-particles` | `false` | `true` |
| `config/canvas-server.yml` | `vanilla-fixes.mc298464` | `false` | `true` |
| `config/canvas-server.yml` | `chat.disable-chat-reporting` | `false` | `true` |
| `server.properties` | `spawn-protection` | `16` | `0` |
| `server.properties` | `difficulty` | `easy` | `normal` |
| `server.properties` | `enforce-secure-profile` | `true` | `false` |
| `server.properties` | `motd` | `A Minecraft Server` | `\u00A7fSiftVanilla\n\u00A77Survival, economy and PvP` |
