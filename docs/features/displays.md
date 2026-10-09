# Displays: spawn leaderboards and info boards

Floating text at fixed places, usually spawn: the richest players, top killers, most active players and a
welcome board. Built from vanilla text display entities, so no hologram plugin is needed. Right-clicking a
leaderboard opens the full list.

Package `feature/displays`, id `displays`, config `features/displays.yml`, text `lang/displays.yml`,
table `displays` (migration `V095`).

## Commands

All under `/displays` (alias `/display`), permission `siftcore.admin.displays` (op by default). Players without
it do not see the command.

| Command | What it does |
|---|---|
| `/displays` or `/displays list` | Players: a dialog listing every display (template, position, state) with a "Go to" button for each placed one. Console: the same list in chat. |
| `/displays create <name> <template>` | Creates a display where the admin stands. A fixed display faces the admin (snapped to 45 degrees). |
| `/displays create <name> <template> <world> <x> <y> <z>` | Same at typed coordinates (works from the console). |
| `/displays move <name>` | Moves a display (also one from displays.yml) to where the admin stands. |
| `/displays move <name> <world> <x> <y> <z>` | Same at typed coordinates. |
| `/displays delete <name>` | Players get a confirmation dialog. For a display created in-game it is deleted; for one from displays.yml the in-game position is removed (it goes back to the file's position, or stays hidden until placed again). |
| `/displays delete <name> confirm` | Deletes without the dialog (the console needs this form). |
| `/displays refresh` | Re-reads every placeholder now and brings back entities something else removed. |

Names use up to 32 lowercase letters, digits, `-` and `_`. Every change is written to the database first; the
display only moves after the write committed. A second change of the same display while the first is saving is
refused ("still being saved"), so double submits are harmless. Changes are recorded in the audit log
(`displays.create`, `displays.move`, `displays.delete`).

The "Go to" button re-checks the permission and the display's current position, then teleports through the shared
teleport service (no warmup, refused while combat-tagged).

## Permissions

| Node | Default | Use |
|---|---|---|
| `siftcore.admin.displays` | op | `/displays` |

Right-clicking a leaderboard needs no permission of its own: its command runs as the player, with the player's
permissions.

## Player setting

| Id | Kind | Group (place) | Offered | What it does |
|---|---|---|---|---|
| `show-spawn-holograms` | toggle, on | Display (5th) | while at least one display is placed with a template | Spawn holograms: show the floating leaderboards and info boards at spawn. Off hides every display and its click box from that player (screenshots, slow PCs); applied at once |

Hiding uses `Player#hideEntity(plugin, entity)` on both entities of each display, so the player's client is never sent
them (nothing to render, nothing to click). The hides follow the entities:

- turning it off or on (the change hook, on the player's thread) hides or shows every live display, each on the
  region thread that owns it (directly when that is the player's own thread);
- a joining player who turned it off has every display hidden in `PlayerJoinEvent`: displays in the joining player's
  region at once, before the server starts tracking them, so they never flash; displays elsewhere on their own region
  thread, long before the player can walk there;
- a display entity that is spawned (startup, chunk load, move, a click box added) is hidden from every online player
  who turned it off inside the spawn call, before it enters the world.

Why the display's region thread: hiding takes the player out of the entity's tracker and showing adds them back,
and the tracker belongs to the region that ticks the entity (verified in the Canvas 962 `CraftPlayer` and
`ChunkMap$TrackedEntity`: both go through `removePlayer`/`updatePlayer`, guarded only by a tick-thread check). Players
near a display are in its region, so for them it is also their own thread. The server forgets the hides when an
entity is removed (`CraftPlayer#onEntityRemove`) or the player leaves, and nothing is stored besides the setting.

## Config (`features/displays.yml`)

- `templates`: name to a list of lines. Lines use the lang tags (`<primary>`, `<secondary>`, `<money>` for money
  amounts only, `<icon:name>` followed by a space, `<!italic>`). `{name}` inserts a SiftCore placeholder that is
  the same for every player. A placeholder with no value (an empty leaderboard place) or no provider shows `-`.
  Shipped: `richest` (baltop 1 to 10), `top-kills` (`top_kills_*` 1 to 10), `most-active` (`top_playtime_*` 1 to 10)
  and `welcome` (server name, /menu and /rules hints, links).
- `defaults`: the looks of every display: `billboard` (center, vertical, horizontal or fixed; default center),
  `alignment`, `line-width` (pixels), `scale`, `see-through` (default false), `text-shadow`, `background`
  (`none` by default for the quiet look, `default` for the client's translucent box, or `"#AARRGGBB"`),
  `view-range` (blocks), `full-bright` and `refresh` (default 60s). All keys are required here.
- `displays`: name to `template` plus optional `world`, `x`, `y`, `z` (all four or none), `yaw` and any key from
  `defaults`. Without a position a display waits until it is placed with `/displays move`. The four shipped boards
  have no position, so nothing appears until an admin places them.
- `interaction`: `enabled`, `commands` (template name to the command a right-click runs, without the slash;
  shipped: `richest: baltop`, `top-kills: top kills`, `most-active: top playtime`) and `cooldown` between clicks.

Everything is validated and each mistake is one precise problem (unknown template with the list of templates,
unknown world with the loaded worlds, numbers out of range, a partial position, bad tags with the line number, bad
placeholder names, click commands for unknown templates, a template removed while a display placed in-game still
uses it).

Placeholders are checked against the registry only on `/sift reload` (at startup the providers register after this
file is read): a placeholder no feature provides, or one that differs per player (such as `{balance}`), is then a
problem for that template. At startup the templates still load, unknown placeholders show `-`, and one INFO line
names them. With the economy and stats features present every shipped placeholder resolves (no INFO line, and
`/sift reload` of the shipped file succeeds).

Positions set in-game always win over the file; the file's template and looks win over a stored template.

## Placeholders

None provided. The shipped templates use `baltop_name_<n>`, `baltop_value_<n>` (economy), `top_kills_name_<n>`,
`top_kills_value_<n>`, `top_playtime_name_<n>` and `top_playtime_value_<n>` (stats).

## Storage

`displays (id PK, template NULL, world, x, y, z, yaw, placed_by, placed_at)`: one row per display placed or moved
in-game. `template` is null for a displays.yml display that was moved in-game. Loaded once at startup into memory;
writes go through the ordered writer and never count as failed writes for expected outcomes (a taken name is
checked inside the write).

## How it works

**Entities.** Each display is one `TextDisplay` plus, when its template has a click command, one `Interaction`
box over the text. Both are created with `Entity#setPersistent(false)`, so they are never written to chunk files
(verified: `save-all flush` with live displays leaves no text display or interaction in the entity region files),
and carry the display name in their persistent data (`siftcore:display`). The entities this feature spawned are
tracked by UUID; a display that moves gets a new generation and its old entities are removed on their own region
thread.

**Threads (Folia/Canvas).** Entities are only created, changed and removed on the region thread that owns the
display's position (`scheduler.region`). `EntitiesLoadEvent` fires on that region right after a chunk loads,
with every entity of the chunk: leftovers (tagged entities that are not the tracked ones, from a crash or an old
version) are removed, and the displays indexed for that chunk are spawned if missing, one tick later so nothing
changes while the chunk is being set up. Right before a display is spawned, its own chunk is checked for leftovers
too (one chunk, only when spawning). `EntitiesUnloadEvent` marks them inactive. At startup a region task per
placed display spawns the ones whose chunk is already loaded; region tasks never load chunks (Folia only keeps a
lowest-level ticket). There are no world or entity scans on timers.

**Text.** One async timer per refresh interval renders the templates (MiniMessage with internal placeholder tags;
values are inserted with `Placeholder.unparsed`, so player names cannot add formatting) and compares the result with
the last render. Only a display whose text changed and whose entity is live gets a region hop, and the hop sets the
text only if it differs from what the entity shows. The same timer brings back an entity that something else removed
(for example `/kill @e[type=text_display]`) while its chunk stayed loaded.

**Reload.** `/sift reload` re-merges the file with the stored positions: text, looks and click boxes change in
place on the same entities, displays with a new position are respawned there, and removed displays disappear.
Nothing changes if any file has a problem.

**Clicks.** `PlayerInteractEntityEvent` on the player's thread (in 26.2 the server fires the `PlayerInteractAtEntityEvent`
subclass, which shares its handler list). For a tagged click box the event is cancelled, a per-player cooldown applies,
and the template's command runs with `Player#performCommand` (allowed on the player's own thread). If the command does
not exist or the player may not use it, the player sees "That leaderboard isn't available right now." The box size
follows the client's text geometry (0.025 blocks per pixel, 10-pixel lines growing up from the entity, wrapping at the
line width) with glyph widths estimated from the default font.

**Shutdown.** Canvas runs `onDisable` on the region shutdown thread after the region scheduler halted. That thread
passes the ownership check (`TickThread.isTickThreadFor(entity)` is true there) but has no region world data, so
`Entity#remove()` throws an NPE in `ServerLevel$EntityCallbacks.onTickingEnd` half way through the removal (verified
on Canvas 962). `disable()` therefore leaves the entities alone when `Bukkit.isStopping()` (true before plugins are
disabled) and only stops the timers. What the feature relies on: the entities are non-persistent, so they are never
saved (verified with `save-all flush` and after shutdown: no text display or interaction in the entity region files)
and end with the process. If the plugin were disabled while the server keeps running, `disable()` removes the
entities the current thread owns; anything left is removed when its chunk loads again or right before its display is
spawned again, so a later enable cannot create duplicates.

## Self-test (`/sift selftest`)

- every placed display has a template and a loaded world;
- every template renders;
- for every display whose chunk is loaded, its chunk holds exactly one tagged text display, one click box when it has
  a command, and no leftovers (counted on the region thread, rechecked once after a second because a display spawns
  one tick after its chunk loads).

## Tests

- Unit (`src/test/java/.../feature/displays`): template compiling and validation, placeholder substitution with
  missing, failing and hostile values, change detection (also concurrent), settings validation including the shipped
  file, how the file and in-game positions merge, positions and facing, click box geometry, the hologram switch's
  group, order and offering and who the displays are hidden from (`HologramSettingTest`).
- End-to-end (`tools/e2e`, `DisplaysScenarios`): `displays-text` (a client near a new board receives the text
  display with the configured metadata), `displays-click` (right-clicking a leaderboard opens /baltop, with the
  cooldown; a top-kills board renders stats values and opens /top kills), `displays-admin` (list dialog teleport,
  move, delete with confirmation and a replayed click, no access without the permission), `displays-hide` (Spawn
  holograms off in the Display settings removes the text display and the click box from that client at once while
  another player keeps them, a board placed meanwhile and a rejoin bring none, `/settings display
  show-spawn-holograms on` brings every board and the click box back and deletes the row), `display-settings` (the
  Display group as a whole: cosmetics' `show-kill-effects`, which this package places, is the 6th Display setting and
  listed after the sidebar and hologram switches; `/settings display show-kill-effects off` stores it; with
  `kill-effects.enabled: false` in features/cosmetics.yml it leaves the group and can't be changed while the stored
  choice is kept; on again deletes the row).

Not verifiable without a real client: how the text actually looks (font rendering, shadow, sprites inside text
displays, full-bright at night) and how well the estimated click box matches the rendered text.
