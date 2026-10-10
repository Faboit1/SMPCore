# Random teleport (`rtp`)

`/rtp` sends players to a random safe spot in the overworld, the nether or the end. Package `feature/rtp`, config
`features/rtp.yml`, text `lang/rtp.yml`. No tables (cooldowns are in memory; a cost, when a region has one, goes
through the ledger). Every shipped region is free; a cost stays configurable per region.

| Contract | Wired | Used for |
|---|---|---|
| `SpawnArea` | spawn feature | Never land inside the protected spawn area |
| `WorldBorders` (`SpawnFeature#borders()`) | spawn feature | Rings stay inside the borders; a reload that breaks that is refused |

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/rtp` (aliases `/randomtp`, `/wild`) | everyone | The region picker (also the main menu entry `rtp`, order 60), or the last region (see the settings) |
| `/rtp <region>` | everyone | Straight to a region, by id (`overworld`, `nether`, `end`) or by world name (`/rtp world_nether`). A region that costs money shows the price and asks first (unless the player turned that off) |
| `/rtp <region> <player>` | `siftcore.admin.rtp`, console | Sends a player at once: no warmup, cost or cooldown (audit log `rtp.send`) |

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.rtp` | everyone | Use `/rtp` |
| `siftcore.admin.rtp` | op | Send other players |
| a region's `permission` | (config) | Extra node a region can require |
| `siftcore.teleport.bypass-warmup` | op | No warmup (core node) |
| `siftcore.bypass.cooldown` | op | No region cooldown (core node) |

## The picker

Buttons only (`RtpPicker`, the dialog style): nothing above them, one button per region the player may use, its name
in the region's colour (`colors.<id>` in `features/rtp.yml`) and "(42s)" after it while its cooldown runs. The button's
tooltip says how far out players land ("Lands 300 to 4,800 blocks out"), the price only when the region costs money
("Costs $2,500, paid once a safe spot is found"), and "Ready, click to go" or "Ready again in 42s". A free region says
nothing about money anywhere (picker, confirmation, landing line, the no-spot line). Up to three regions show in one
column, more in two. Regions that are disabled, need a permission the player lacks, or whose world is not loaded are
left out. A region's button starts the teleport and closes the picker at once (the tooltip already shows the price,
so it never asks again).

## Per-player settings (Settings > Teleports & homes)

Registered in `SettingCategories.TELEPORT` by `RtpFeature#registerSettings` (text `rtp.settings.*` in
`lang/rtp.yml`), last in the group:

| Id | Kind | Default | Read in | Meaning |
|---|---|---|---|---|
| `rtp-confirm-cost` | switch | on | `RtpService#start` (`RtpDefault#asksCost`) | "Confirm paid random teleports": a typed `/rtp <region>` that costs money shows "A random teleport to X costs $1,000." with Teleport (its tooltip: "You pay only once a safe spot is found."; it closes the window and starts the warmup; every check runs again) and Cancel. Offered only while some enabled region costs money, so with the shipped free regions it is not shown |
| `rtp-default` | choice menu / last | menu | `RtpService#bare` (`RtpDefault#straight`) | "/rtp on its own": the picker, or straight back to the region of the player's last random teleport that happened (kept as the free-form value `rtp_last`). A last region that is gone, turned off, forbidden, outside its world or cooling down opens the picker instead |

The landing line ("Welcome to X at x, z.", with "Paid $1,000." only for a paid region) is the teleport's arrival line: it shows where the player's
"Teleport countdown" setting says (`Teleports#arrival`); a line that says money was paid shows even with that set to
off. With the shared `hide-coordinates` (streamer mode) on, it leaves the position out (`rtp.landed-hidden`,
`rtp.landed-paid-hidden`). Staff sends (`/rtp <region> <player>`) are unchanged.

## A random teleport, step by step

1. Checks on the player's thread: region enabled, permission, world loaded and the ring usable inside the border,
   region cooldown, enough money for the cost. Failing any of them refuses at once (nothing starts).
2. The shared warmup (`Teleports`): 5 s by default, cancelled by moving or damage, refused in combat.
3. The search, off every world thread (`RtpSearch`):
   - picks a column uniformly by area in the ring (`RingSampler`), inside the border minus `border-margin` and
     outside the protected spawn;
   - loads its chunk with `World#getChunkAtAsync(x, z, false)`: **it never generates terrain**. The worlds are
     pre-generated to their borders; an ungenerated chunk comes back empty and simply counts as a failed attempt;
   - on the thread that owns the chunk, checks the candidate and up to `spots-per-chunk - 1` more columns of the same
     chunk with `SafeSpot` (pure, unit tested on a fake block grid): solid harmless ground and two clear blocks above
     it; never water, lava, fire, campfires, magma, cactus, powder snow, berry bushes, cobwebs, dripstone or portals;
     in the overworld and end the top block of the column (the void of the end has none), in the nether the first
     cave floor scanning down from below the bedrock roof (y 120) to above the lava sea (y 32);
   - one chunk at a time, up to `max-attempts` chunks, and a 30 s timeout so a stuck chunk load can't lock anyone out.
4. When a spot exists, everything from step 1 is checked again, `api.event.RandomTeleportEvent` (cancellable) fires,
   and then the cost is taken in one `LedgerTx` (sink, kind `rtp_cost`, ref = region id).
5. `teleportAsync` to the spot; the region cooldown starts and the player sees where they landed and what they paid.

### Money: when the cost is charged

The cost is charged only after a safe spot was found, immediately before the teleport. A failed search (no land, no
safe spot, timeout) never charges. Charging after the teleport instead would let a player spend their money while
the teleport runs and get it for free; charging before the search would charge for failures. So if the charged
teleport then does not happen (the player left in that instant, got combat-tagged, or the server refused the move),
the same amount is paid back at once in a silent transaction (kind `rtp_refund`, a source) and the player is told.
The ledger therefore records exactly one net `rtp_cost` for every random teleport that happened, and nothing for one
that did not.

## Config (`features/rtp.yml`)

| Key | Default | Meaning |
|---|---|---|
| `warmup` | `5s` | Standing still before the search starts |
| `max-attempts` | `12` | Chunks tried per teleport |
| `spots-per-chunk` | `8` | Columns checked in each loaded chunk |
| `border-margin` | `32` | Landing spots keep this far from the border |
| `colors.<id>` | overworld `#86EFAC`, nether `#FF8A65`, end `#F5F0A8` | The colour of a region's button in the picker ("#RRGGBB"); a region left out is white |
| `regions.<id>` | overworld, nether, end | `name`, `enabled`, `world`, `permission`, `cost` (0 = free, the shipped value; then no money text shows), `cooldown`, `center-x`, `center-z`, `min-radius`, `max-radius` |

Shipped regions (the live worlds are pre-generated to borders of 10,000 / 5,000 / 6,000 around 0, 0):

| Region | World | Ring | Cost | Cooldown |
|---|---|---|---|---|
| `overworld` | `world` | 300 to 4,800 | free | 60s |
| `nether` | `world_nether` | 200 to 2,300 | free (was $2,500 before the owner removed the fees) | 60s |
| `end` | `world_the_end` | 1,000 to 2,800 (outer islands; the main island and the void around it are skipped) | free (was $5,000) | 60s |

The live server's `features/rtp.yml` takes the new costs by itself where the owner never edited them (`YamlFiles`
updates unedited shipped values); a cost the owner set by hand stays.

### Rings and world borders

Each ring is checked against the border its world will have: the one in `features/spawn.yml` as it is on disk (so a
reload that changes both files is judged on the new border), or the live border for worlds SiftCore doesn't manage.
A ring that doesn't fit is a config problem with the exact numbers, for example:

`features/rtp.yml regions.overworld.max-radius: is 5,000, but the world border of 'world' (10,000 wide around 0, 0)
leaves room for at most 4,968 around 0, 0 while keeping 32 blocks from the border; lower it to 4,968 or grow the
border by 64`

At startup the server still starts (with the problem listed); `/sift reload` applies nothing until it is fixed.
When the live border is smaller than the ring anyway, the search uses only the part of the ring inside it.

### Near and far rings

There is deliberately no separate near/far choice: the ring is sampled uniformly by area, so most landings are in
the outer, untouched part of the world, and a server that wants a closer option adds another region with a smaller
ring (same world, different `min-radius`/`max-radius`, its own cost and cooldown).

## Design decisions

- Region cooldowns are per player and region, kept across relogs but not restarts.
- One search per player at a time; asking again while one runs says so.
- Staff sends use the same search (so they never land somewhere unsafe either) but skip warmup, cost and cooldown.
- The self-test checks that every region's world is loaded, every ring fits its border, the sampler stays in the
  ring, and that searches find spots (fewer than one in four successful after 20 searches points at rings outside
  pre-generated land).

## Tests

- Unit: `RingSamplerTest`, `RtpBorderTest` (the shipped regions are free, an explicit `5k` cost parses), `SafeSpotTest`,
  `RtpPickerTest` (buttons only, each region in its colour, no money text for a free region, the price in the tooltip of
  a paid one), `RtpSettingsChoiceTest` (when /rtp goes to the last region, when a paid teleport asks first, the price
  question offered only while an enabled region costs money);
  `TpaSettingsTest` registers the whole Teleports & homes group (random teleport's two settings last, rtp-confirm-cost
  following the paid regions, streamer mode declared as read).
- E2E (`TeleportScenarios`): `rtp-flow` (the picker: no line above the buttons, the shipped regions free with no money
  text, a test region costing $1,000 shows its price in the tooltip and charges it once), `rtp-free` (a shipped region
  from the picker and typed: no question, no money line, the balance untouched), `rtp-limits` (a typed paid region asks
  with its price first; a failed search of a paid region says nothing was charged), and
  `rtp-settings` (Cancel on the price charges nothing; the confirmation turned off in the settings dialog; the last
  region typed with `/settings`, the picker while it cools down, straight there once it is ready; streamer mode in the
  landing line).
