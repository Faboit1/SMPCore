# How the SiftVanilla server is set up

This page describes the live server as configured: software, plugins, worlds, ranks, and day-to-day
operation. Every change made to it is in [server-undo-log.md](server-undo-log.md), with the before and after
values and how to revert each one. The reasoning behind each tuning value is in
[research/deps-and-tuning.md](research/deps-and-tuning.md).

## Machine

| | |
|---|---|
| Panel | Pterodactyl, server "SiftVanilla 2" (`93babcbd`), address 37.27.67.240:25602 |
| Limits | 14 GB memory, 300% CPU (three cores' worth of time, not dedicated cores), 23.8 GB disk |
| Java | 25, heap `-Xms6G -Xmx8G`, G1 (the egg's startup flags; the client API cannot change them) |
| Server | Canvas 26.2 build 962, a Folia fork that ticks regions of the world in parallel |
| Clients | 26.2 natively; 26.3 through ViaVersion; 1.9 to 26.1 through ViaBackwards (1.8 and older are refused) |

Canvas logs a SIMD notice at every boot because `--add-modules=jdk.incubator.vector` is not in the egg's
startup flags. It only affects plugin-rendered map images, so it is safe to ignore.

## Plugins

| Plugin | Version | Why |
|--------|---------|-----|
| SiftCore | 1.0.0 | All gameplay (this repository) |
| LuckPerms | 5.5.87 | Ranks and permissions |
| PlaceholderAPI | 2.12.3 | Exposes SiftCore's placeholders as `%siftcore_<name>%` to other plugins |
| VaultUnlocked | 2.20.3 | The Folia-capable Vault; SiftCore registers its economy there so other plugins can use it |
| ViaVersion | 5.12.0 | Lets newer clients (26.3) join the 26.2 server |
| ViaBackwards | 5.12.0 | Lets older clients (1.9 to 26.1) join; shows them SiftCore's dialogs as chest menus |
| Chunky | 1.5.3 | Pre-generates the world inside the border (runs once, then idle) |

Clients older than 1.21.6 have no dialog screen, so ViaBackwards shows every SiftCore dialog to them as a
chest menu (buttons are items; clicking one sends the same click a modern client would, and SiftCore checks it
the same way). Its chest labels are restyled to SiftCore's white and gray in `plugins/ViaBackwards/config.yml`.
1.8 clients would need ViaRewind, which is not installed.

spark is built into Canvas (`/spark profiler`, `/tps`). Floodgate is not installed: there is no Bedrock
listener. If Geyser and Floodgate are added later, SiftCore picks Floodgate up automatically and shows Bedrock
players native forms.

## Performance settings

The full list of changed keys is in the undo log (row 8). The ones that matter most:

- **Threads**: 2 region tick threads with Canvas's AFFINITY scheduler, 2 chunk workers, 1 I/O thread. This
  leaves about one core for networking, garbage collection and plugin I/O under the 300% CPU quota.
- **Distances**: view distance 6, simulation distance 4. Mobs spawn within 4 chunks and despawn beyond 56 blocks.
- **Mob caps** (per player): 50 monsters, 8 animals, 3 of each water group, 1 ambient. Monster spawn attempts every
  5 ticks.
- **Entities**: tighter activation and tracking ranges, items merge within 2.5 blocks, arrows despawn after 15 s,
  common junk blocks (cobblestone, dirt, netherrack...) despawn after 15 s, armor stands and markers don't tick.
- **Redstone**: Alternate Current (vanilla behaviour, much cheaper). Hoppers ignore occluding blocks.
- **Canvas**: velocity and empty-move packet filtering, alternative keep-alive, filtered fluid post-processing,
  faster XP orbs, the locator bar off, the chat reporting button off (chat isn't signed, which also stops
  version-translation chat kicks).
- **Anti-xray**: engine mode 1 (cheapest) in the overworld (below y 64) and the nether (below y 128, including
  ancient debris). It hides ores, not bases.

What to watch after launch: `/tps` (utilisation out of 200%), `/spark profiler start --timeout 300`. If
utilisation stays near 200% while TPS drops, lower the simulation distance before adding threads.

## Worlds

| Dimension | Border (diameter, centred on 0, 0) | Pre-generated radius |
|-----------|-------------------------------------|----------------------|
| Overworld (`world`) | 10,000 | 5,112 |
| Nether (`world_nether`) | 5,000 | 2,612 |
| End (`world_the_end`) | 6,000 | 3,112 |

Pre-generation covers the border plus 112 blocks (the chunks a player at the border can see). SiftCore's random
teleport only lands in generated chunks, so players never trigger terrain generation, which is the most expensive
thing a server does. Chunky resumes after restarts until it finishes (`chunky progress` shows where it is).

Game rules, set in all three dimensions: advancement messages off, phantoms off, locator bar off, 25% of players
must sleep to skip the night, spectators don't generate chunks. Difficulty is normal.

Spawn protection is handled by SiftCore (vanilla `spawn-protection` is 0): no building, PvP or damage inside the
protected spawn area, with a bypass permission for builders.

## Ranks

LuckPerms groups, each inheriting the one below:

| Group | Weight | Label (`siftcore-rank` meta) |
|-------|--------|------------------------------|
| default | 0 | |
| prospector | 10 | Prospector |
| baron | 20 | Baron |
| tycoon | 30 | Tycoon |

The track `ranks` orders them, so `lp user <name> promote ranks` moves a player up one rank. The label shows
in chat, tab and nametags. Each rank's perks are permission nodes (homes, auction slots, order slots, friends,
spawner stack room, kits, perk commands and cosmetics; never sell multipliers, team size or keys). The exact LuckPerms
commands, prices and the reasoning are in [monetization.md](monetization.md); every node is in
[permissions.md](permissions.md).

Store purchases should use SiftCore's idempotent store commands (`/sift store ...`) from the store's
command delivery, so a retried delivery never grants twice.

## Staff

Staff groups are separate from the ranks and weigh 100 or more (`hierarchy.min-weight` in `features/staff.yml`), so
the staff hierarchy can tell them apart: staff can't ban, mute, kick, warn, freeze or vanish staff of the same or a
higher weight. **Until these groups exist every staff member weighs 0 and staff can punish each other, and until the
owner has the owner node the owner can be punished like anyone.** Set it up before relying on the hierarchy:

```
lp creategroup moderator
lp group moderator setweight 100
lp group moderator permission set siftcore.staff.* true
lp creategroup admin
lp group admin setweight 200
lp group admin parent add moderator
lp user <owner> permission set siftcore.hierarchy.owner true
lp user <name> parent add moderator
```

Narrow the moderator nodes as needed (every `siftcore.staff.*` node is in [permissions.md](permissions.md)). The
owner node `siftcore.hierarchy.owner` is deliberately outside `siftcore.staff.*`, but `siftcore.*` and `*` cover it:
never give a wildcard that broad to a staff group, or every member is treated as an owner. Check with
`lp user <name> permission check siftcore.hierarchy.owner` (only the owner should get `true`). Details are in
[features/staff.md](features/staff.md) ("Staff hierarchy").

## Operating the server

| Task | How |
|------|-----|
| Change SiftCore settings | Edit `plugins/SiftCore/config.yml`, `features/*.yml` or `lang/*.yml`, then `/sift reload` (nothing is applied if any file has an error; every problem is listed) |
| Check health | `/sift selftest` (ledger invariants, every feature's state, every icon and message) and `/sift metrics` |
| Back up the economy | `/sift backup` writes a consistent copy of the SQLite database to `plugins/SiftCore/backups/` |
| Restart | Use the panel's restart. Canvas has no `/reload`, and SiftCore saves everything on shutdown |
| Find out who changed money | `/eco history <player>` and `/sift audit` |

Never use `/reload` or plugin managers: Canvas doesn't support reloading plugins, and SiftCore's own
`/sift reload` already covers its config.
