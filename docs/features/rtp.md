# Random teleport (`rtp`)

`/rtp` sends players to a random safe spot in the overworld, the nether or the end. Package `feature/rtp`, config
`features/rtp.yml`, text `lang/rtp.yml`. No tables (cooldowns are in memory; the cost goes through the ledger).

| Contract | Wired | Used for |
|---|---|---|
| `SpawnArea` | spawn feature | Never land inside the protected spawn area |
| `WorldBorders` (`SpawnFeature#borders()`) | spawn feature | Rings stay inside the borders; a reload that breaks that is refused |

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/rtp` (aliases `/randomtp`, `/wild`) | everyone | The region picker (also the main menu entry `rtp`, order 60) |
| `/rtp <region>` | everyone | Straight to a region, by id (`overworld`, `nether`, `end`) or by world name (`/rtp world_nether`) |
| `/rtp <region> <player>` | `siftcore.admin.rtp`, console | Sends a player at once: no warmup, cost or cooldown (audit log `rtp.send`) |

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.rtp` | everyone | Use `/rtp` |
| `siftcore.admin.rtp` | op | Send other players |
| a region's `permission` | (config) | Extra node a region can require |
| `siftcore.teleport.bypass-warmup` | op | No warmup (core node) |
| `siftcore.bypass.cooldown` | op | No region cooldown (core node) |

## The picker

A dialog with one line per region the player may use: name, ring (`300 to 4,800 blocks out`), cost or free, and
whether it is ready or how long the cooldown still runs; then one button per region. Regions that are disabled,
need a permission the player lacks, or whose world is not loaded are left out.

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
| `regions.<id>` | overworld, nether, end | `name`, `enabled`, `world`, `permission`, `cost`, `cooldown`, `center-x`, `center-z`, `min-radius`, `max-radius` |

Shipped regions (the live worlds are pre-generated to borders of 10,000 / 5,000 / 6,000 around 0, 0):

| Region | World | Ring | Cost | Cooldown |
|---|---|---|---|---|
| `overworld` | `world` | 300 to 4,800 | free | 60s |
| `nether` | `world_nether` | 200 to 2,300 | $2,500 | 60s |
| `end` | `world_the_end` | 1,000 to 2,800 (outer islands; the main island and the void around it are skipped) | $5,000 | 60s |

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
