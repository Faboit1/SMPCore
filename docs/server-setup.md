# How the SiftVanilla server is set up

This page describes the live server as configured: software, plugins, worlds, ranks, staff, TAB, backups and
day-to-day operation. Every change made to it is in [server-undo-log.md](server-undo-log.md), with the before and after
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

| Plugin | Version | State | Why |
|--------|---------|-------|-----|
| SiftCore | 1.0.0 | running, 30 features | All gameplay (this repository) |
| LuckPerms | 5.5.87 | running | Ranks, staff groups and permissions; SiftCore reads rank labels, colours and weights from it and delivers store ranks through it |
| PlaceholderAPI | 2.12.3 | running | Exposes SiftCore's placeholders as `%siftcore_<name>%` (and `%siftvanilla_<name>%`) to TAB and other plugins |
| VaultUnlocked | 2.20.3 | running | The Folia-capable Vault; SiftCore registers its economy there (classic and modern interface) so other plugins can use it |
| TAB | 6.2.0 | running, with the owner's config | Sidebar, tab list and nametags; SiftCore's own versions step aside for it (see TAB below) |
| ViaVersion | 5.12.0 | running | Lets newer clients (26.3) join the 26.2 server |
| ViaBackwards | 5.12.0 | running | Lets older clients (1.9 to 26.1) join; shows them SiftCore's dialogs as chest menus |
| Chunky | 1.5.3 | running | Pre-generates the world inside the border (resumes after restarts until done, then idle) |
| AxAuctions | 2.7.2 | **fails to load** | Installed as the planned auction house (undo log rows 17 and 19), but every start fails while it loads its libraries (`NoClassDefFoundError` .../caffeine/cache/Caffeine), so the server skips it. SiftCore's own auction house serves `/ah` and the menu button: `plugins/SiftCore/commands.yml` has `ah: yield-to: AxAuctions`, which hands `/ah` to AxAuctions only while it runs. Details and what to do: [features/auction.md](features/auction.md) |

Clients older than 1.21.6 have no dialog screen, so ViaBackwards shows every SiftCore dialog to them as a
chest menu (buttons are items; clicking one sends the same click a modern client would, and SiftCore checks it
the same way). Its chest labels are restyled to SiftCore's white and gray in `plugins/ViaBackwards/config.yml`.
1.8 clients would need ViaRewind, which is not installed.

spark is built into Canvas (`/spark profiler`, `/tps`). Floodgate is not installed: there is no Bedrock
listener. If Geyser and Floodgate are added later, SiftCore picks Floodgate up automatically and shows Bedrock
players native forms. `/sift integrations` shows which hooks are active.

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
protected spawn area (64 blocks around the spawn point), with a bypass permission for builders. The spawn is a
41 x 41 platform at 0, 0 (floor y 100) built with `tools/spawn-builder`; SiftCore's spawn point is 0.5 101 0.5 facing
north (row 23), and the four spawn displays (welcome, richest, top kills, most active) stand around it (row 24).

## Ranks

LuckPerms groups, each inheriting the one below (undo log row 26):

| Group | Weight | Label (`siftcore-rank` meta) | Rank colour (`siftcore-rank-color`) |
|-------|--------|------------------------------|-------------------------------------|
| default | 0 | | |
| prospector | 10 | Prospector | `#5FA8FF` |
| baron | 20 | Baron | `#FFAA00` |
| tycoon | 30 | Tycoon | `#FF6AD5` (and `siftcore-rank-gradient` `#FF6AD5:#B26BFF`) |

The track `ranks` orders them, so `lp user <name> promote ranks` moves a player up one rank; store purchases arrive
as timed or permanent groups through `/sift store rank` (only these three groups can be delivered:
`store.rank-groups` in `features/integrations.yml`). Each rank's perks are permission nodes: homes, auction slots,
order slots, friends, spawner stack room, kits, perk commands, cosmetics, spawn flight, joining a full server and the
"Show my rank" setting (`siftcore.settings.hide-rank`, given to prospector and inherited by baron and tycoon, row 39).
Never sell multipliers, team size or keys. The exact LuckPerms commands, prices and the reasoning are in
[monetization.md](monetization.md); the cosmetic perks are described in [features/cosmetics.md](features/cosmetics.md);
every node is in [permissions.md](permissions.md).

The rank label shows in SiftCore's chat, on profiles and in `%siftcore_rank%`; the tab list and nametags are TAB's
(see below). Store purchases must use SiftCore's idempotent store commands (`/sift store ...`) from the store's command
delivery, so a retried delivery never grants twice. No store plugin is installed yet (the Tebex setup is in
[monetization.md](monetization.md#tebex-setup)): nothing is sold until it is, and ranks are given by hand.

Every player, whatever their rank, has the same per-player settings (`/settings`: 133 settings in 14 groups, see
[features/settings.md](features/settings.md)); no setting is a paid perk, and "Show my rank" only appears for players
who have a rank to hide.

## Staff

Staff groups are separate from the ranks and weigh 100 or more (`hierarchy.min-weight` in `features/staff.yml`), so
the staff hierarchy can tell them apart: staff can't ban, mute, kick, warn, freeze or vanish staff of the same or a
higher weight, or take items from their inventory. Use prefix priority 100 or more too, so a staff member who also
bought a rank shows the staff prefix. TAB already sorts `owner`, `admin`, `mod` and `helper` above the ranks.

The owner (Faboit, the only operator) has `siftcore.hierarchy.owner` (row 34): only the console and other holders can
punish them, and they can act on staff of any weight. **The staff groups are not created yet (no row in the undo log):
until they exist every staff member weighs 0 and staff can punish each other.** Set them up before giving anyone a
staff role:

```
lp creategroup helper
lp group helper setweight 100
lp creategroup mod
lp group mod setweight 200
lp group mod parent add helper
lp creategroup admin
lp group admin setweight 300
lp group admin parent add mod
lp group helper permission set siftcore.staff.chat true
lp group helper permission set siftcore.staff.reports true
lp group helper permission set siftcore.staff.mute true
lp group helper permission set siftcore.staff.warn true
lp group helper permission set siftcore.staff.history true
lp group mod permission set siftcore.staff.* true
lp group admin permission set siftcore.admin true
lp group admin permission set siftcore.admin.audit true
lp user <name> parent add helper
```

Every `/sift` subcommand needs `siftcore.admin` (the `/sift` command itself) as well as its own node, so a group
that should run `/sift audit` needs both lines above; `siftcore.staff.*` covers neither. Narrow or widen the nodes as
needed (every `siftcore.staff.*` and `siftcore.admin.*` node is in [permissions.md](permissions.md)); group names must
match TAB's sorting (`owner`, `admin`, `mod`, `helper`) for the tab list order. The owner node
`siftcore.hierarchy.owner` is deliberately outside `siftcore.staff.*`, but `siftcore.*` and `*` cover it: never give a
wildcard that broad to a staff group, or every member is treated as an owner. Check with
`lp user <name> permission check siftcore.hierarchy.owner` (only the owner should get `true`). Details are in
[features/staff.md](features/staff.md) ("Staff hierarchy").

## TAB

TAB 6.2.0 runs with the owner's archive config adjusted for SiftVanilla (`plugins/TAB/`, row 32): SiftCore placeholders
for money, shards, kills, deaths, team, keyall and the visible online count; sorting
`GROUPS:owner,admin,mod,helper,tycoon,baron,prospector,default`; the paid tiers' prefixes and fixed nametag prefixes
(nametag name colours can only be the 16 legacy colours, so each tier's prefix ends with the closest one; Tycoon's tab
name is a gradient through `customtabname`); proxy support off. The sidebar ends with the address `siftvanilla.com`,
which the tab list footer shows too.

- **SiftCore steps aside.** `features/scoreboard.yml` lists `TAB` in the `yield-to` of the sidebar, the tab list and the
  nametags, so while TAB runs SiftCore draws none of them (`/sidebar status` says which plugin shows each part).
  The `scoreboard` and `sidebar-layout` settings are then not offered, and `/sidebar` says which plugin shows the
  sidebar. Removing TAB (delete the jar and `plugins/TAB/`, restart) brings SiftCore's own three parts back by
  themselves.
- **No `-` in output replacement keys.** TAB reads a `placeholder-output-replacements` key containing `-` as a number
  range (`0-10`); a key without numbers around it (such as `"-"`) makes TAB fail while setting up the sidebar for every
  joining player, so the sidebar, address included, never shows (`plugins/TAB/errors.log` names
  `PlaceholderReplacementPattern`). `%siftcore_keyall_countdown%` returns `-` while the keyall is off, so the live config
  turns that into Off with a condition instead (`%condition:keyall%`, row 41).
- **Money in TAB** follows the money format of the player PlaceholderAPI asks for: in the header and footer that is
  the viewer, in tab list names and nametags the player the line is about. Use `%siftcore_balance_server%` there for
  one format for everyone (see [placeholders.md](placeholders.md)).
- **Show my rank does not reach TAB as configured.** TAB's `groups.yml` uses LuckPerms' own `%luckperms-prefix%` for
  everyone and fixed nametag prefixes for prospector, baron and tycoon, which read LuckPerms directly. A player who
  turns "Show my rank" off loses the rank in SiftCore's chat, profiles, join lines and the `%siftcore_rank%`,
  `%siftcore_rank_group%` and `%siftcore_rank_color%` placeholders, but TAB's tab list and nametags still show it. For
  the setting to work there, build TAB's prefixes from `%siftcore_rank%` (and `%siftcore_rank_color%`) instead of
  `%luckperms-prefix%` and the per-group prefixes. See [features/integrations.md](features/integrations.md#show-my-rank).

## Crates

SiftCore's crates feature runs every crate: seven tiers, Common to Celestial, with keys from the keyall (1 Uncommon
key every 4 hours), the shard shop and staff ([features/crates.md](features/crates.md)). No crate plugin is
installed: ExcellentCrates was considered and audited, and its open and reward paths could not be made as safe as
SiftCore's (one transaction for the key and the reward, the claim box for what doesn't fit), so it is not used.

The crate blocks are placed in game, once, by a staff member standing at spawn:

1. Place seven blocks in a row or an arc inside the protected spawn, at least 3 blocks apart, with 3 blocks of air
   above each: a chest (Common), then lime, light blue, purple, orange, red and cyan shulker boxes (Uncommon to
   Celestial). Shulker boxes and chests open their lid while a reward spins above them.
2. Look at each block and run `/crates block add <crate>`: `basic` (the Common crate keeps its old id), `uncommon`,
   `rare`, `epic`, `legendary`, `mythic`, `celestial`.
3. Check with `/crates block list`. Each block shows its floating name and particles within a second; no reload or
   restart is needed. `/crates block remove` while looking at a block undoes it.

Blocks placed before keep working and get their name and particles automatically. If a `/displays` board sits above
a crate block, remove it (the floating name replaces it) or raise `effects.hologram-height` in `features/crates.yml`.
The opening animation, names and particles are switched in that file's `effects` section.

## Backups and recovery

- **Database backups:** SiftCore copies its SQLite database (`VACUUM INTO` after every queued write, checked with
  `PRAGMA quick_check`; safe while running) to
  `plugins/SiftCore/backups/` every 24 hours and keeps the newest 7 (`backups.interval` and `backups.keep` in
  `features/integrations.yml`); `/sift backup` makes one at any time and `/sift backup list` shows them. To restore:
  stop the server, replace `plugins/SiftCore/data/siftcore.db` with a backup (`siftcore-<time>.db`; remove the
  `-wal` and `-shm` files next to the old database), start.
- **Exports:** `/sift export [days]` writes balances and the ledger as CSV to `plugins/SiftCore/exports/`.
- **Config:** every changed file and value is in the undo log with its before value; the repository holds the shipped
  files and `server/plugins/` holds the files written to the live server (SiftCore `commands.yml`, the AxAuctions
  config).
- **The economy turns read-only** after five failed database writes (nothing is lost: transactions that could not be
  stored were undone). Fix the database, then `/eco resume`.

## Operating the server

| Task | How |
|------|-----|
| Change SiftCore settings | Edit `plugins/SiftCore/config.yml`, `features/*.yml` or `lang/*.yml`, then `/sift reload` (a config file with an error applies nothing; a lang entry with an error keeps its shipped text; every problem is listed). Aliases, `yield-to` and the pause menu need a restart |
| Change a player's settings | `/sift settings <player> [setting] [value\|reset]` (works offline, audited); server-wide defaults, locks and hidden settings in `features/settings.yml` |
| Check health | `/sift selftest` (ledger invariants, every feature's state, every icon and message) and `/sift metrics` |
| Back up the economy | Automatic every 24h (7 kept); `/sift backup` writes one now to `plugins/SiftCore/backups/` |
| Record a change to the server | Add a row to [server-undo-log.md](server-undo-log.md): when, what changed (before and after), how to revert it |
| Restart | Use the panel's restart. Canvas has no `/reload`, and SiftCore saves everything on shutdown |
| Find out who changed money | `/eco history <player>` and `/sift audit` |
| Place, move or remove a crate | Look at the block: `/crates block add <crate>`, `/crates block remove`; `/crates block list` shows them (see Crates above) |

Never use `/reload` or plugin managers: Canvas doesn't support reloading plugins, and SiftCore's own
`/sift reload` already covers its config.
