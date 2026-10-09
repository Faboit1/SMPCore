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
| 15 | 2026-10-08 06:31 | `plugins/PlaceholderAPI/config.yml`: `check_updates: false`, `cloud_enabled: false` (both were `true`; SiftCore registers its own expansion, nothing is downloaded from the eCloud). `plugins/ViaVersion/config.yml`: `check-for-updates: false` (was `true`). Takes effect at the next restart. | Set the three keys back to `true`. |
| 16 | 2026-10-08 07:28 | Installed ViaBackwards 5.12.0 (the release matching ViaVersion 5.12.0; 1,430,897 bytes, sha256 `f902f7da7eb99e8bfaf461f80283c4e2750b7d9727e6b508ea4bb9163f55b1db`, Modrinth sha512 checked) into `plugins/`, so clients from 1.9 up to 26.3 can join. Wrote `plugins/ViaBackwards/config.yml` before its first start: the defaults, except `dialog-style` restyled to white/gray without bold, matching SiftCore (older clients see SiftCore's dialogs as chest menus). Restarted the server: clean boot, Chunky resumed on its own, and the row 15 settings took effect. | Stop the server, delete `plugins/ViaBackwards-5.12.0.jar` and `plugins/ViaBackwards/`, start it. |
| 17 | 2026-10-08 17:29 | Restarted the server (nobody online) to check whether AxAuctions 2.7.2 starts outside the build sandbox. It does not: its library download crashes (`NoClassDefFoundError` .../caffeine/cache/Caffeine). No files changed. The jar stays in `plugins/`; SiftCore's `/ah` takes over while it fails (row 19). | Nothing to revert. |
| 18 | 2026-10-08 21:16 | Uploaded `plugins/SiftCore-1.0.0.jar` (1,909,690 bytes, sha256 `89d7edfede5a423055e158a01dd9db3ccde6eaffeb985fe964875af60149ef6a`, built from commit `1c51b6f`; read back from the panel and checksum-compared). | Stop, delete the jar and `plugins/SiftCore/`, start. |
| 19 | 2026-10-08 21:16 | Uploaded the repository's `server/plugins/SiftCore/commands.yml` (`ah: yield-to: AxAuctions`) and `server/plugins/AxAuctions/` (restyled config, lang, categories and 12 GUI files) before either plugin first wrote its own. | Delete the files; each plugin writes its defaults on the next start. |
| 20 | 2026-10-08 21:17 | Restarted: SiftCore enabled in 797 ms (18 features, 58 commands, schema v95, SQLite), no SiftCore warnings or errors, Vault reports SiftCore for the classic and modern economy. `/sift selftest`: 75 passed, 1 failed (the pause menu lists orders, spawners and settings, which are not deployed yet). | See row 18. |
| 21 | 2026-10-08 21:18 | Removed the vanilla scoreboard team `1` (display name `D`) that was created while testing commands before SiftCore was installed (`minecraft:team remove 1`). SiftCore teams are separate and not stored in the scoreboard. | `minecraft:team add 1 D`. |
| 22 | 2026-10-08 21:18 | Installed the temporary `plugins/SiftBuild.jar` (the spawn builder in `tools/spawn-builder`) and restarted. `siftbuild probe`: highest terrain under the platform y=82. `siftbuild spawn 100`: placed 8,405 blocks in 16 chunks (41 x 41 platform at 0, 0, floor y=100, 4 blocks of air cleared above) and set the world spawn to 0.5 101 0.5. | Reinstall `SiftBuild.jar`, restart, `siftbuild clear 100`, then `setworldspawn` to the old point. The jar was deleted from `plugins/` right after the build (it stays loaded until the next restart). |
| 23 | 2026-10-08 21:19 | SiftCore spawn: `setspawn world 0.5 101 0.5 180 0` (stored in `plugins/SiftCore/data/spawn.yml`; arrivals face north toward the AFK pad). The protected spawn area is the 64-block radius around it. | Delete `plugins/SiftCore/data/spawn.yml` (falls back to the world spawn) or `/setspawn` elsewhere. |
| 24 | 2026-10-08 21:20 | Placed the four spawn displays: `welcome` at 0.5 102 -7.5, `richest` at -9.5 102 0.5, `top-kills` at 10.5 102 0.5, `most-active` at 0.5 102 9.5 (`displays move <name> world x y z`; stored in the SiftCore database). | `displays delete <name> confirm`. |
| 25 | 2026-10-08 22:17 | Redeployed SiftCore (commit `8cfbfcf`: spawners, dialogs that stay open until the next one, red errors, coloured upright boards, automatic new config keys) with `bin/deploy.sh` (checksum-verified upload, restart). Replaced `plugins/SiftCore/features/displays.yml` with the new shipped one (it had not been edited). On boot SiftCore added `palette.error` and `palette.error-secondary` to `config.yml` by itself. 19 features, no warnings or errors, self-test 81 of 82 (the pause menu still lists orders and settings, not deployed yet). | Upload the previous jar (row 18) and restart. |
| 26 | 2026-10-08 22:28 | LuckPerms ranks replaced by the final three paid tiers (docs/monetization.md). Checked first that no player held the draft groups (`lp search group.<name>`: 0 users). Deleted `supporter`, `patron`, `elite`, `legend` and the track `ranks`. Created `prospector` (weight 10, parent default), `baron` (20, parent prospector) and `tycoon` (30, parent baron), each with display name, `siftcore-rank` and `siftcore-rank-color` meta (tycoon also `siftcore-rank-gradient`), a coloured prefix, and the capacity and perk nodes from the plan (homes 6/15/40, auction listings 8/20/40, spawner stack bonus 250/500/1000, order slots 8/20/40, friends 100/200/500, kit and perk nodes). Track `ranks`: prospector, baron, tycoon. Every command is in the scratchpad file lp-ranks.txt and docs/monetization.md. | `lp deletegroup tycoon`, `baron`, `prospector`, `lp deletetrack ranks`, then recreate row 14's groups. |
| 27 | 2026-10-08 22:50 | Redeployed SiftCore (commit `4d19335`: AFK and shards, sell and shop upgrade, orders, rank configs without sell or AFK bonuses, balance_number placeholder). Replaced `plugins/SiftCore/features/sell.yml` and `shop.yml` with the new shipped ones (not edited; the old shop prices fail the new arbitrage check and the old sell.yml still had the draft rank multipliers). SiftCore added 115 new texts to `lang/sell.yml` and 16 to `lang/shop.yml` by itself. 22 features, no warnings or errors, self-test 105 of 106 (the pause menu lists settings, not deployed yet). | Upload the previous jar and the old sell.yml/shop.yml (git history) and restart. |
| 28 | 2026-10-08 23:42 | Redeployed SiftCore (commit `fc6448a`: chat, private messages, ignore lists, mentions, grouped settings, friends). SiftCore added the friends texts and hub entry by itself. 25 features, no warnings or errors, self-test 124 of 124. | Upload the jar of row 27 and restart. |
| 29 | 2026-10-08 23:47 | Uploaded `plugins/SiftCore/data/shipped/` (53 files: what earlier versions shipped, rebuilt from git history) and redeployed SiftCore (commit `cae6c87`: entries nobody edited follow new defaults). On boot SiftCore updated the three unedited entries that were out of date: `features/hub.yml` `pause-menu.entries` (adds friends), `lang/extras.yml` `extras.help.body` (adds /friend), `lang/afk.yml` `afk.admin.rewards`. Self-test 124 of 124. | Delete `plugins/SiftCore/data/shipped/`, restore the three entries from git history, upload the jar of row 28 and restart. |
| 30 | 2026-10-09 00:01 | Redeployed SiftCore (commit `fe426df`: kits and rank perks). SiftCore added `features/kits.yml`, `lang/kits.yml` and 16 pause-menu labels to `lang/hub.yml`, and updated the unedited `pause-menu.entries` (adds kits). Restarted once more at 00:02 so the pause screen (built before the sync) shows the new labels. 26 features, no warnings or errors, self-test 130 of 130. | Upload the jar of row 29 and restart; delete `features/kits.yml` and `lang/kits.yml` if wanted (claims stay in the database table `kit_claims`). |
| 31 | 2026-10-09 01:19 | Redeployed SiftCore (commit `93a4b0c`: integrations and scoreboard). PlaceholderAPI registered both `siftcore` and `siftvanilla` expansions (108 placeholders), LuckPerms ranks connected, store deliveries available through `/sift store` (console). Daily database backups now go to `plugins/SiftCore/backups/` (7 kept). 28 features, no SiftCore warnings or errors, self-test 140 of 140. | Upload the jar of row 30 and restart. |
| 32 | 2026-10-09 01:20 | Installed TAB 6.2.0 (`plugins/TAB-6.2.0.jar`, 8,482,800 bytes, sha1 0e5288cd...) with the owner's archive config adjusted for SiftVanilla in `plugins/TAB/` (config.yml, groups.yml, users.yml, animations.yml, messages.yml, playerdata.yml, skincache.yml): SiftCore placeholders for money, shards, kills, deaths, team, keyall and the visible online count; sorting owner, admin, mod, helper, tycoon, baron, prospector, default; rank tag prefixes for the paid tiers; proxy support off. Restarted: TAB enabled in 190 ms with the paper_26_2 NMS provider; SiftCore's sidebar, tab list and nametags yield to TAB (`/sidebar status`). Self-test 140 of 140. | Delete `plugins/TAB-6.2.0.jar` and `plugins/TAB/`, restart: SiftCore's own sidebar, tab list and nametags take over again by themselves. |
| 33 | 2026-10-09 09:34 | Redeployed SiftCore (commit `ac0a93d`: audit fixes for items, staff/combat safety and UI; cosmetics; the settings framework). Database schema v100 to v111 (spawner XP box, crate command rewards, cosmetics tables). SiftCore added and updated unedited texts by itself (spawners, crates, orders, shop, homes, tpa, shards, integrations). 29 features, 95 commands, no SiftCore warnings or errors, self-test 151 of 151. Tested first: full e2e 270 of 271 (the one needs LuckPerms on the test server; it passes where LuckPerms is installed). | Upload the jar of row 31 and restart; the new tables stay and are ignored. |
| 34 | 2026-10-09 09:35 | LuckPerms: cosmetic perk nodes per docs/monetization.md: prospector `siftcore.tags.prospector`; baron `siftcore.chat.color`, `siftcore.command.nick`, `siftcore.join.message`, `siftcore.tags.baron`; tycoon `siftcore.chat.color.hex`, `siftcore.nick.gradient`, `siftcore.join.message.custom`, `siftcore.killeffect.*`, `siftcore.tags.tycoon`. User Faboit (the only operator, the owner) `siftcore.hierarchy.owner` so no other staff can punish the owner. Commands in the scratchpad file lp-cosmetics.txt. | `lp group <group> permission unset <node>` for each; `lp user Faboit permission unset siftcore.hierarchy.owner`. |
| 35 | 2026-10-09 11:06 | Redeployed SiftCore (commit `d0a8edd`: boosters, spawn flight, joining when full, /purchases, console-only store deliveries). Tested first: full e2e with LuckPerms 283 of 283. 30 features, 98 commands, self-test 158 of 158. LuckPerms: baron `siftcore.spawn.fly` and `siftcore.join.full`. | Upload the jar of row 33 and restart; `lp group baron permission unset siftcore.spawn.fly` and `... siftcore.join.full`. Booster state stays in its table and is ignored. |

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
