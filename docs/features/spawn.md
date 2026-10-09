# Spawn (`spawn`)

`/spawn`, `/setspawn`, the protected spawn area, `/fly` inside it, where new and respawning players arrive, and the
world borders.
Package `feature/spawn`, config `features/spawn.yml`, text `lang/spawn.yml`, the spawn point in `data/spawn.yml`
(per server, not in the shared database, because it belongs to this server's worlds).

The feature implements `core.link.SpawnArea`: `SpawnFeature#area()` returns it. It is plain arithmetic on
coordinates (an immutable region swapped on `/setspawn` and reload), safe from any thread. Homes and random teleport
use it today; combat and AFK are meant to (no combat logging out of spawn, AFK zone). `SpawnFeature#borders()`
returns `WorldBorders`, which random teleport uses to keep its rings inside the borders.

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/spawn` | everyone | Teleports to spawn after the warmup (also the main menu entry `spawn`, order 65). The countdown and "Teleported." show where the player's "Teleport countdown" setting (`teleport-display`, Settings > Teleports & homes) says |
| `/spawn <player>` | `siftcore.admin.spawn`, console | Sends a player to spawn at once (audit log `spawn.send`) |
| `/setspawn` | `siftcore.admin.setspawn` | Sets the spawn where the player stands |
| `/setspawn <world> <x> <y> <z> [yaw pitch]` | `siftcore.admin.setspawn`, console | Sets it to exact coordinates (audit log `spawn.set`) |
| `/fly` | `siftcore.spawn.fly` (Baron and up) | Turns flying on or off, inside the protected spawn area only (see below) |

`/setspawn` saves `data/spawn.yml` (written to a temporary file that replaces the real one), moves the world's own
spawn point there too (on the global thread), moves a radius-shaped protected area with it, and refuses a point
outside the world border. Until `/setspawn` is used, the spawn is the default world's own spawn point.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.spawn` | everyone | Use `/spawn` |
| `siftcore.admin.spawn` | op | `/spawn <player>` |
| `siftcore.admin.setspawn` | op | `/setspawn` |
| `siftcore.spawn.bypass` | op | Builders: build and use everything inside the protected area (they still can't be hurt there) |
| `siftcore.spawn.fly` | op | `/fly` inside the protected spawn area. A rank perk: give it to `baron` (and so `tycoon`) in LuckPerms |

## The protected spawn area

A radius around the spawn point (default 64 blocks, measured flat, from the bottom to the top of the world) or a
cuboid between two corners. Inside it:

| Rule | How |
|---|---|
| No building | Block break and place, buckets, item frames, paintings, armor stands, minecarts, boats, end crystals, entities changing blocks (endermen, withers, players trampling) |
| No using blocks except the allowlist | Right clicks and pressure (physical) on blocks are refused unless the block is in `allowed-interactions` (default: buttons, doors, trapdoors, fence gates, pressure plates). Chests and every other container can't be opened |
| No editing item frames and armor stands | Rotating, taking, punching or breaking them |
| No PvP | Hits, arrows, harmful splash and lingering potions and fishing rods between players, when the victim or the attacker is inside |
| No damage of any kind to players | Everything except the void and `/kill` |
| No natural mob spawning | The reasons in `blocked-spawn-reasons` (natural, patrols, raids, jockeys...); spawners, eggs and commands still work |
| No explosions, fire or flooding from outside | Explosions keep their damage outside spawn but break nothing inside; fire can't spread or burn; liquids from outside don't flow in; pistons outside can't push into it |

Every handler runs at `LOW` priority, so features listening with `ignoreCancelled` (combat tags, stats) never see
refused actions. Refusals tell the player on the action bar at most once a second.

Note for the crates feature: a crate opened by right-clicking a block at spawn must either use a block type listed in
`allowed-interactions`, or handle `PlayerInteractEvent` at `LOWEST` and cancel it itself.

## Flying at spawn (`/fly`)

A convenience perk for the Baron rank and up: flying around the hub, never outside it (flight in the open world would
be a gameplay advantage, see `docs/monetization.md`). `/fly` toggles it and answers on the action bar.

| When | What happens |
|---|---|
| `/fly` outside the protected area | Refused: "You can only fly inside the spawn area." |
| `/fly` above the height limit | Refused: "You're too high to fly here." (`fly.max-height` blocks above the spawn point) |
| `/fly` while combat-tagged | Refused with the time left. |
| `/fly` in creative or spectator | Nothing to do: "You can already fly in this game mode." Staff in those modes are never touched. |
| The player leaves the area | Flight turns off within half a second. |
| The player flies above the height limit | They drop ("You can't fly higher here."); flight stays allowed, so they can fly again lower down. |
| A combat tag | Flight turns off at once (`CombatTagEvent`), or at the next check for tags that fire no event (`/combat tag`). |
| Game mode or world change | Flight turns off at once. |
| The permission goes away | Flight turns off at the next check. |
| `fly.enabled: false` (reload) | Flight turns off at the next check and `/fly` is refused. |
| Quit, shutdown | Flight turns off before the player is saved; flight left over from a crash is taken away on the next join. |

How it is checked: only players who turned `/fly` on are followed, each by a timer on their own entity scheduler
every 10 ticks (position, height, combat, permission, setting). Nothing scans all players and nothing listens to
movement events.

**Height limit.** The default area is a flat radius from the bottom to the top of the world, so without a limit a
player could fly very high inside spawn and then leave it sideways: a fall long enough to kill, or a free launch for
an elytra glide. Flight therefore only goes `fly.max-height` blocks (default 48) above the spawn point; keep it a
little above the tallest part of the spawn build. A cuboid area's own top applies as well (above it is outside).

**Soft landing.** When flight stops while the player is in the air (they left spawn: "You left spawn, so flight is
off. Your landing won't hurt.", the height limit, combat, a game mode change, a crash), the fall that follows does no
damage until they land, however long it takes. The fall is followed by a timer on the player's own thread every 5
ticks (`FlightRules.Fall`); the protection ends with the first fall damage (taken away), or as soon as the fall ends
any other way: standing on solid ground, water or lava, a ladder or vine, an elytra (they steer their own landing
then), a vehicle, flying again, or a fall distance that went back down (slime, cobweb, a teleport). A stale "on
ground" flag in the air does not end it (only solid ground under the feet does), and a minute is the backstop, so the
protection never lingers for a later fall. It is not given when they stood on the ground. Flight turned on this way
is marked on the player (a persistent data key), so a crash can never leave someone with free flight.

## Arrival and respawn

- Brand-new players start at spawn: `AsyncPlayerSpawnLocationEvent` places them there before they enter the world
  (no visible teleport), with a teleport as a fallback on join. One second later they get a short welcome title and a
  chat line pointing to the menu (`/menu` or the pause menu). The quiet join broadcast itself belongs to extras.
- Players who die without a bed or charged respawn anchor respawn at spawn. Canvas does not fire
  `PlayerRespawnEvent`; it fires `io.canvasmc.canvas.event.PlayerRespawnAsyncEvent`, which is registered by class
  name (no compile-time Canvas dependency); on Paper the regular event is used. The self-test reports when neither is
  available.

## World borders

`world-border.worlds.<world>` sets each world's border (size = full width, centre), applied on the global region
thread at startup and after every reload, and logged when it changes. The defaults match the live server, which is
pre-generated to exactly these borders: overworld 10,000, nether 5,000, end 6,000, all around 0, 0. Random teleport
validates its rings against these values (see `docs/features/rtp.md`). `enabled: false` leaves every border alone.

## Config (`features/spawn.yml`)

| Key | Default | Meaning |
|---|---|---|
| `spawn.default-world` | `world` | Spawn world until `/setspawn` is used |
| `spawn.warmup` | `3s` | Standing still before `/spawn` |
| `spawn.cooldown` | `0s` | Time between two `/spawn` teleports |
| `arrival.first-join-at-spawn` | `true` | New players start at spawn |
| `arrival.first-join-welcome` | `true` | The welcome title and chat line |
| `arrival.respawn-at-spawn` | `true` | Respawn at spawn without a bed or anchor |
| `protection.enabled` | `true` | The protected area |
| `protection.shape` | `radius` | `radius` or `cuboid` |
| `protection.radius` | `64` | Radius around the spawn point |
| `protection.cuboid.world`, `.from`, `.to` | `world`, `-64 -64 -64`, `64 320 64` | The corners for `cuboid` |
| `protection.allowed-interactions` | buttons, doors, trapdoors, fence gates, pressure plates | Block names or block tags (`#minecraft:doors`); unknown names are config problems |
| `protection.blocked-spawn-reasons` | natural, jockey, patrol, raid, reinforcements, village-invasion, trap, silverfish-block, ender-pearl, drowned, nether-portal | Mob spawn reasons stopped inside |
| `fly.enabled` | `true` | `/fly` inside the protected area for `siftcore.spawn.fly` |
| `fly.max-height` | `48` | How many blocks above the spawn point players may fly (1 to 2048); higher up, flight drops them and `/fly` is refused |
| `fly.fall-protection` | `true` | When flight stops in the air, the fall that follows does no damage until they land, however long it is (`false` turns it off) |
| `world-border.enabled` | `true` | Manage the borders below |
| `world-border.worlds.<world>.size`, `.center-x`, `.center-z` | see above | Each world's border |

## Self-test

Spawn point available and its world loaded; the protected area covers the spawn point; the area answers from any
thread (a point 30 million blocks away is outside); the live borders match the config; the spawn is inside the
border; the respawn hook is installed.

## Tests

Unit: `ProtectedRegionTest`, `SpawnResourcesTest` (the shipped config and text, precise config problems, and a wrong
`fly.max-height` that falls back to 48, never to no limit), `FlightRulesTest` (the ceiling; a 30 second fall stays
protected until it lands, which the old fixed 10 second window failed; a stale "on ground" flag in the air doesn't
end it; water, a ladder, an elytra, a vehicle, flying again or a reset fall distance do; the one minute backstop).
End to end (`tools/e2e`, `BoostersScenarios#spawn-fly`): no `/fly` without the node; on and off inside spawn; refused
in combat and ended by a combat tag; ended when the node is taken away; dropped above the height limit and refused up
there; leaving spawn in the air turns it off and a 12 block fall 12 seconds later does no damage, while the same fall
without the protection hurts; refused outside spawn.
