# Spawners (`feature/spawners`, id `spawners`)

Stackable mob spawners that never spawn a mob. While a player is near, a spawner works out what its mobs would have
dropped and puts the loot and XP into its storage. Players open the storage, take items, sell everything at once and
collect the XP. Spawners are bought in the shop (or given by staff), stacked by right clicking, shared with the
owner's team, and picked up with silk touch.

## Commands

| Command | Permission (default) | What it does |
|---|---|---|
| `/spawners` (`/spawner`) | `siftcore.command.spawners` (everyone) | A dialog listing your spawners, one row each with its item, stack, place and how full, and a button per spawner that opens its details (status, storage, XP, worth, loot rate) with an Open storage button. While you're in a team, Team spawners switches to your teammates' spawners, which you may use too. |
| `/spawners give <player> <mob> [amount]` | `siftcore.admin.spawners` (op) | Gives spawner items (1 to 6400). Online players get them in their inventory (the rest in the claim box), offline players in their claim box. Console friendly, written to the audit log (`spawners.give`). |
| `/spawners list <player>` | `siftcore.admin.spawners` | Every spawner of a player in chat: id, mob, stack, location, storage and XP. Console friendly. |
| `/spawners cycle` | `siftcore.admin.spawners` | Runs a loot cycle now in every loaded chunk with spawners (for checking balance or after a config change). |
| `/spawners info` | `siftcore.admin.spawners` | Totals: spawners, stacks, owners, chunks, loaded chunks, stored items and XP, spawners waiting to be written. |

Other permissions:

| Node | Default | Meaning |
|---|---|---|
| `siftcore.spawners.bypass` | op | Use, open and pick up anyone's spawners, without silk touch, and open any storage from `/spawners` anywhere. |
| `siftcore.spawners.stack.<n>` | not set | Rank bonus: stack `<n>` more spawners than the mob's cap (the highest number wins). Give it to LuckPerms groups, e.g. `siftcore.spawners.stack.250` for `elite`. |
| `siftcore.spawners.stack.unlimited` | false | Stack up to the hard limit of 10,000. |

Suggested rank bonuses for the LuckPerms groups: `supporter` `siftcore.spawners.stack.100`, `patron` `.250`, `elite`
`.500`, `legend` `.1000` (`default` gets none, so its cap is the mob's 1,000). The bonus of the player who stacks counts,
so a teammate of a higher rank can stack an owner's spawner further.

Hub entry `spawners` (order 40, also in the pause menu) opens the `/spawners` list.

Placeholders (`%siftcore_<name>%`): `spawners_count` (spawner blocks you own), `spawners_stacked` (spawners in all,
counting stacks), `spawners_stored` (items waiting in your storages), `spawners_xp` (XP waiting).

## How it works

### Items and placing
A spawner item is a vanilla spawner item with its mob id in its persistent data (`siftcore:spawner_mob`) and a plain
name and lore ("Skeleton spawner"). Block entity data is never used: vanilla ignores it when a non-op places a spawner.
Items of one mob stack with each other; plain vanilla spawner items are not SiftCore spawners. The worth table never
sells them (custom data), so they can't be sold to the server, but they can be traded and auctioned.

Placing one records a spawner (owner, mob, stack 1) in a transaction. Checks run at `HIGHEST` (mob enabled, world
allowed, `placement.max-per-chunk`, `api.event.SpawnerPlaceEvent`); the record is made at `MONITOR`, once no plugin
can cancel the placement any more (a placement refused with `setBuild(false)` is skipped too), so a refused placement
never leaves a record behind. The block is set to show the mob, with a spawn count of 0 (the vanilla spawner then
skips its tick entirely: Canvas's `BaseSpawner#serverTick` returns first thing when the spawn count is 0, so it
never spawns, checks for players or fires events) and a required player range equal to
the activation radius (it spins while it is generating). `PreSpawnerSpawnEvent` and `SpawnerSpawnEvent` are also
cancelled for managed spawners as a second guard. If storing the record fails, the block is removed again and the
item goes back to the player's claim box. Once the server has used the item up, the player's data is saved
(`save-player-after-trade`), so a crash can't hand the item back while the spawner stays recorded.

### Stacking
Right click a placed spawner with a spawner item of the same mob to add one; sneak and right click to add the whole
held stack. The stack cap is the mob's `stack-cap` (default `stacking.default-cap`, 1000) plus the player's
`siftcore.spawners.stack.<n>` bonus, never above 10,000. Only what fits is taken; a full stack says so. The items
leave the hand before the transaction; if it fails they go back (to the hand, else the inventory, else the claim
box). Another mob's spawner says "Only zombie spawners stack here." and spawn eggs are refused, so a spawner's mob
can never change. A teammate who stacks someone else's spawner is told whose stack it is ("Added 1 to Alex's zombie
stack, now 4 of 1,000."): the added spawners belong to its owner from then on. `api.event.SpawnerStackEvent` fires
first.

### Virtual loot
Spawners are kept in memory, indexed by block, chunk and owner (loaded from the database at startup). Chunk load and
unload events track which chunks with spawners are loaded; there are no world scans. Once a second a global timer
(which touches no world state) picks the loaded chunks whose cycle is due (every `cycle.interval`, 30s) and hands each
to its region thread. There one nearby-player query covers the whole chunk. A spawner is active when a counted player
is within `activation.radius` (32) blocks: alive, not spectating, not vanished (`count-vanished: false`) and, if
`count-afk: false`, not AFK.

An active spawner makes `stack x kills-per-cycle` kills (a fractional total is rounded up with the probability of its
fraction). For each drop entry the number of kills that drop it is a binomial count and the amount a sum of uniform
amounts (`LootMath`): small counts are simulated exactly, rare and near-certain drops by geometric jumps, everything
else with the normal approximation. Each entry costs a few dozen random numbers at most, so a stack of 1000 is as
cheap as one; results stay in range and the mean is exact. XP is `kills x xp-per-kill`.

The loot of a whole chunk is added in one pass under the economy lock. Storage holds `slots x 64` items of any kind
per stacked spawner (`storage.slots-per-spawner`, or the mob's `slots`); when the new loot doesn't fit, every kind gets
its proportional share of the free space and the rest is lost. XP is capped at `storage.xp-per-spawner` per stacked
spawner. The menu and `/spawners` say when a storage is full.

Cycles also repair: a spawner whose block is no longer a spawner (removed by an admin tool, a world edit, or a pickup
whose storage write failed) is refunded in one transaction: its spawner items and stored items go to the owner's claim
box and the record is deleted. An online owner is told in chat ("Your spider spawner stack of 4 at 130, 71, -62 in
world is gone. Its spawners and 11 stored items are waiting in your claim box.") and gets the stored XP; XP can't wait
in a claim box, so it is lost while the owner is offline. The console logs every refund.

### Storage and crash safety
Storage is memory plus write-behind: every changed storage gets its complete snapshot (XP and item rows) written every
`storage.flush-interval` (60s), when its chunk unloads, and synchronously at shutdown. Snapshots are taken and queued
while holding the economy lock, so they reach the ordered database writer in the same order as the transactions that
change storages: a write-behind snapshot can never overwrite a newer sale. A crash loses at most the loot made since
the last write, never duplicates it. Every transaction that takes from a storage writes that storage's snapshot in
the same database unit as its money or claim box rows.

### The storage menu
Sneak and right click a spawner with an empty hand (`interaction.open-requires-sneak: false` makes any empty-hand
right click open it), or use Open storage in `/spawners` (within `interaction.remote-range` blocks, 32, same world;
staff with the bypass anywhere). Players in combat can't open a storage or take, sell and collect XP from one until
their combat timer ends ("You can't open spawners in combat. 12s left."; `interaction.block-in-combat`), like the
shop and the auction house: storages hold ender pearls and other loot that must not be fetched mid-fight. Stacking
and picking up still work in combat. A six-row menu shows one icon per stored item with its amount and worth, sorted by
most stored, most valuable or name, and searchable. Bottom row: Back (when opened from the dialog), Sort, Search,
Collect XP (slot 50), Sell all (51) and the spawner's details (52). It redraws while open when the storage changes.

- **Take**: left click takes a stack, right (or shift) click takes as much as fits. Only what fits the inventory is
  taken: the storage is reduced first, the items are handed out after the commit (anything that no longer fits goes
  to the claim box; if the player left, everything goes there).
- **Sell all**: everything that sells is priced with the worth table (`WorthLookup`) times the player's sell bonus,
  `api.event.SpawnerSellEvent` fires, and one `LedgerTx` pays it: `source(player, MONEY, total, "spawner_sell",
  "spawner:<id>")`, checks that every sold amount is still there and every unit price is unchanged (prices only move
  with `/sift reload`), the storage reduction as the apply, and the storage snapshot as the write. A chat receipt lists the items on hover. Items that can't be sold (sticks, poppies, glass
  bottles) stay. The stats feature counts `spawner_sell` as money earned.
- **Collect XP**: the XP is removed in a transaction and given after the commit (`xp.apply-mending` repairs Mending
  gear first, like orbs). If the player left in between, it goes back into the spawner.

The player who sells or takes gets the money or items, whether owner, team member or staff.

### Picking up
Mining a spawner with a silk touch tool picks it up (`breaking.require-silk-touch`; staff with the bypass never need
it). Without silk touch the break is cancelled with "Use a silk touch pickaxe to pick up spawners." One transaction
deletes the spawner, sends its stored items to the owner's claim box (`breaking.storage: claim-box`, the default) or
sells what sells for the owner at their bonus (`sell`; the rest goes to their claim box), and puts the spawner items
that won't fit the player's inventory into the player's claim box. After the commit the player gets the rest of the
spawner items (one per stacked spawner) and the stored XP; vanilla drops and XP are switched off. If someone else
picks up a spawner, its owner is told who (a vanished staff member stays unnamed: "Staff picked up your zombie spawner
stack of 3."). `api.event.SpawnerBreakEvent` fires first.

Decision: stored items go to the owner (they made them) and the XP to the player who picks it up (XP can't be stored
in a claim box). The spawner items and XP are handed over on the player's thread right after the commit; a player who
disconnects in that instant finds the spawner items in their claim box, but the XP can't follow them there and is lost. A storage that would need more than `breaking.max-claim-stacks` (108) claim box stacks can't be
picked up until it is sold or emptied, so one pickup can't flood a claim box.

### Who may use a spawner
Its owner, members of the owner's team (`TeamLookup`), and staff with `siftcore.spawners.bypass`. Everyone else is
told "This spawner belongs to <owner>." for stacking, opening and picking up.

### Protection
Explosions (entities and blocks) skip managed spawners, pistons can't move them, and withers and other entities can't
change them. Nothing listens to the hopper, physics or entity move events that would slow the server, nor to Paper's
`BlockDestroyEvent`: the server only builds that event while a plugin listens, and it fires for every block that
breaks by itself (cactus and sugar cane farms, crops, vines), so one listener would cost every farm. Spawners never
break that way; only admin tools such as `/setblock ... destroy` or world editors remove one, and the next loot cycle
then refunds it (see Virtual loot).

## Config (`features/spawners.yml`)

| Key | Default | Meaning |
|---|---|---|
| `cycle.interval` | 30s | How often active spawners make loot (5s to 10m). |
| `activation.radius` | 32 | Blocks within which a player keeps a spawner going (4 to 128). |
| `activation.count-vanished` / `count-afk` | false / true | Whether vanished staff / AFK players count. |
| `storage.slots-per-spawner` | 9 | Slots of 64 items per stacked spawner (mobs may set `slots`). |
| `storage.xp-per-spawner` | 6000 | XP one stacked spawner holds. |
| `storage.flush-interval` | 60s | Write-behind interval. |
| `stacking.default-cap` | 1000 | Stack cap unless the mob sets `stack-cap`. |
| `interaction.open-requires-sneak` | true | Opening needs sneaking. |
| `interaction.remote-range` | 32 | Range for Open storage in `/spawners` (at least 6). |
| `interaction.block-in-combat` | true | Combat-tagged players can't open or use storages. |
| `breaking.require-silk-touch` | true | Picking up needs silk touch. |
| `breaking.storage` | claim-box | `claim-box` or `sell`. |
| `breaking.max-claim-stacks` | 108 | Largest storage (in claim box stacks) a pickup accepts. |
| `placement.max-per-chunk` | 0 | Spawner blocks per chunk, 0 = no limit. |
| `placement.disabled-worlds` | [] | Worlds where spawners can't be placed. |
| `natural-spawners.silk-touch-pickup` | false | Mining a natural spawner with silk touch (not in creative) gives a spawner item of its mob. A block SiftCore once set up (spawn count 0, e.g. a picked-up spawner brought back by a crash rollback) never counts as natural. |
| `xp.apply-mending` | true | Collected XP repairs Mending gear first. |
| `page-size` | 10 | Spawners per page in `/spawners`. |
| `mobs.<id>` | 16 mobs | `name`, `enabled`, `kills-per-cycle`, `xp-per-kill`, optional `stack-cap` and `slots`, and `drops.<item>` with `min`, `max`, `chance`. |

Default mobs: zombie, zombified piglin, chicken, skeleton, spider, cave spider, pig, creeper, slime, magma cube, cow,
sheep, enderman, witch, blaze and iron golem, with vanilla-like drop tables (iron golem: 3 to 5 iron ingots and up to
two poppies; witch: 4 to 8 redstone plus its rarer drops; drops that need a player kill in vanilla, like blaze rods,
are included). A mob removed from the config keeps its spawners as storage that can be emptied and picked up, but
they make no loot; `enabled: false` also stops placing and stacking.

## Balance

The target: at the base sell price, a spawner bought in the shop pays its price back in 50 to 80 hours of a player
being nearby. With a 30s cycle a spawner runs 120 cycles an hour, so it earns
`120 x kills-per-cycle x worth per kill` an hour, where worth per kill is the sum over drops of
`chance x (min + max) / 2 x sell price`. Sell prices are the generated worth table (`data/worth-generated.yml`:
rotten flesh $3, bone $6, arrow $2, string $6, spider eye $8, gunpowder $12, porkchop $6, beef $6, leather $10,
chicken $5, feather $4, mutton $5, white wool $3, slime ball $15, magma cream $25, ender pearl $40, blaze rod $60,
redstone $6, glowstone dust $4, sugar $5, iron ingot $25, gold ingot $35, gold nugget $3, carrot and potato $4; glass
bottles, sticks and poppies don't sell). `BalanceTest` recomputes this from the bundled configs and fails if any shop
spawner leaves the 50 to 80 hour band.

| Mob | Shop price | Worth per kill | Kills per cycle | Money per hour | Pays back in | Items per hour | Storage of one | Full after | XP per hour |
|---|---|---|---|---|---|---|---|---|---|
| zombie | $60,000 | $3.83 | 2.0 | $918 | 65.4 h | 258 | 576 | 2.2 h | 1,200 |
| zombified piglin | $80,000 | $3.88 | 2.6 | $1,209 | 66.2 h | 320 | 1,152 | 3.6 h | 1,560 |
| chicken | $120,000 | $9.00 | 1.7 | $1,836 | 65.4 h | 408 | 1,152 | 2.8 h | 408 |
| skeleton | $150,000 | $8.00 | 2.4 | $2,304 | 65.1 h | 576 | 1,152 | 2.0 h | 1,440 |
| spider | $160,000 | $8.66 | 2.4 | $2,495 | 64.1 h | 384 | 1,152 | 3.0 h | 1,440 |
| cave spider | $160,000 | $8.66 | 2.4 | $2,495 | 64.1 h | 384 | 1,152 | 3.0 h | 1,440 |
| pig | $200,000 | $12.00 | 2.1 | $3,024 | 66.1 h | 504 | 1,152 | 2.3 h | 504 |
| creeper | $220,000 | $12.00 | 2.35 | $3,384 | 65.0 h | 282 | 576 | 2.0 h | 1,410 |
| slime | $300,000 | $15.00 | 2.5 | $4,500 | 66.7 h | 300 | 1,152 | 3.8 h | 600 |
| magma cube | $300,000 | $12.50 | 3.0 | $4,500 | 66.7 h | 180 | 576 | 3.2 h | 720 |
| cow | $350,000 | $22.00 | 2.0 | $5,280 | 66.3 h | 720 | 1,728 | 2.4 h | 480 |
| sheep | not sold | $10.50 | 2.0 | $2,520 | (65 h at $165k) | 600 | 1,728 | 2.9 h | 480 |
| enderman | $400,000 | $20.00 | 2.5 | $6,000 | 66.7 h | 150 | 576 | 3.8 h | 1,500 |
| witch | $450,000 | $43.25 | 1.3 | $6,747 | 66.7 h | 1,209 | 2,304 | 1.9 h | 780 |
| blaze | $600,000 | $30.00 | 2.5 | $9,000 | 66.7 h | 150 | 576 | 3.8 h | 3,000 |
| iron golem | $2,500,000 | $100.00 | 3.2 | $38,400 | 65.1 h | 1,920 | 3,456 | 1.8 h | 0 |

Everything scales linearly with the stack: 10 stacked zombies earn $9,180 an hour and hold 5,760 items. Rank sell
bonuses shorten the payback (a 1.5x legend pays back in about 44 hours). Storage holds at least 1.8 hours of loot, so
players come back to sell; the XP cap of 6,000 per spawner fills in two hours for blazes and later for the rest.

## Storage (database)

Tables `spawners` (id, world, x, y, z, owner, mob, stack, xp, created; unique per block) and `spawner_items`
(spawner_id, item_type, amount) from `V004`; no new migration. At startup item rows whose spawner no longer exists
are deleted (left behind by a failed write) before ids are handed out.

## Integration

- Implements `core.link.SpawnerItems` (`SpawnersFeature#items()`): enabled mobs and their spawner items. The shop and
  the crates are constructed with it (after this feature), so the shop's spawners category is live and crate spawner
  rewards (zombie, skeleton and blaze spawners in the shipped crates) are given and previewed as SiftCore spawners.
- Consumes `WorthLookup` (`SellFeature#worth()`) for selling, `TeamLookup` (`TeamsFeature#lookup()`) for access and
  the team list, `VanishStatus` (`StaffFeature#vanish()`) for activation, the shared `CombatTags` (as `CombatStatus`)
  to keep storages closed in combat, and `AfkStatus` (`AfkStatus.NONE` until the AFK feature exists).
- Ledger kind `spawner_sell` (counted as earnings by the stats feature); claim box source `spawner`, refs
  `spawner:<id>`.
- Events: `SpawnerPlaceEvent`, `SpawnerStackEvent`, `SpawnerBreakEvent`, `SpawnerSellEvent` (all cancellable, fired
  on the player's thread before anything changes).

## Self-tests (`/sift selftest`)

`loot math` (10,000 kills average within 1%), `storage fills to capacity`, `spawner items` (round trip, plain
spawners are not SiftCore spawners, they never sell, unknown mobs make no item), `every drop is an item`,
`indexes agree` (block, chunk, owner and loaded-chunk indexes), `stored spawners match memory`.

## Tests

Unit tests (`src/test/java/.../feature/spawners`): `LootMathTest` (means and spreads on every path, ranges, a bounded
number of random draws for up to 10^12 kills, fair rounding), `StorageMathTest` (capacity, XP cap, stack caps,
proportional fitting, storage counts), `SpawnersSettingsTest` (bundled file, optional values, precise problems),
`SpawnerRegistryTest` (indexes, due cycles, access rules), `SpawnerStoreTest` (SQLite round trip, unique blocks,
orphan rows, write-behind ordering, a sale's money and storage committing together), `BalanceTest` (the table above).

End to end (`tools/e2e`, `SpawnerScenarios`): placing and stacking up to the cap with refusals, loot cycles, the
storage menu (take, collect XP, sell all, ledger rows), silk touch pickup with claim box and XP, access for strangers
and team members, `/spawners` with its details, Open storage and the team list, activation (a vanished player and a
player 40 blocks away keep nothing going, the status says so), storages closed in combat from the block and the
dialog while stacking still works, buying from the shop, TNT next to a spawner, a spawner removed by `/setblock ...
destroy` refunded (stack and stored loot) to the owner's claim box by the next cycle, spawner rewards in the legendary
crate preview, and loot kept across a restart.
