# Crates (`crates`)

Players open crates with virtual keys. Each opening spends one key and pays one reward, drawn by weight: items,
money, shards, keys of another crate, spawners or a console command. Keys come from the keyall (every player online,
every few hours), the store, the shard shop and staff. Crates can also be blocks in the world. Package
`feature/crates`, config `features/crates.yml`, text `lang/crates.yml`, tables `crate_keys` and `crate_log` (V008)
plus `crate_grants`, `crate_blocks` and `crate_schedule` (V055).

The feature implements `core.link.CrateKeys`; `CratesFeature#keys()` returns it for the features that hand out keys
(shard shop, store delivery):

- `crates()`: the configured crate ids;
- `give(player, crate, amount, actor, ref)`: one transaction, stored before `committed()` completes. With a `ref`
  (at most 64 characters) the grant is applied once: the same `ref` again is refused with reason `duplicate`, even
  after a restart, for as long as `grants.remember` (90 days). Other refusals: `unknown_crate`, `bad_amount`,
  `bad_ref`, `limit` (1,000,000 keys of one crate);
- `keys(player, crate)`: the count, from memory.

It consumes five contracts:

| Contract | Wired | Used for |
|---|---|---|
| `WorthLookup` | the sell feature | "Sells for" in the preview and the item value in `/crates info` |
| `SpawnerItems` | `NONE` until the spawners feature is integrated | Spawner rewards. While the provider can't make a mob's spawner, that reward is left out of the crate and the other chances grow to fill in (one INFO line at startup says how many; no warning) |
| `VanishStatus` | the staff feature | Vanished staff get no keyall keys (unless `include-vanished`) and their wins are never announced |
| `CombatStatus` | the shared combat tags (`CombatTags`) | With `block-in-combat` (shipped on) a player in combat can't open crates (no totems or golden apples mid-fight); looking at crates and previews still works |
| `AfkStatus` | `NONE` until the AFK feature is integrated | With `keyall.include-afk: false` AFK players get no keyall keys (shipped `true`: staying online is what earns them) |

Money and shards are paid with the ledger kind `crate_reward`, which the stats feature counts as money earned.

Integration notes: when the spawners and AFK features are merged, `FeatureCatalog` passes their `SpawnerItems` and
`AfkStatus` here instead of `NONE` (nothing else changes; the left-out spawner rewards come back on their own). The
shard shop and store delivery take `crates.keys()` and should give keys with a `ref` (order id, purchase id) so a
retried delivery is never paid twice. Crate blocks at spawn need no entry in `spawn.yml`'s `allowed-interactions`
(see Crate blocks), and `crates` can be added to `hub.yml`'s `pause-menu.entries` to put Crates in the pause screen.

## Commands and permissions

`/crates` has the aliases `/crate` and `/keys`. Player-only subcommands are hidden from the console.

| Command | Who | What it does |
|---|---|---|
| `/crates` | everyone | The crates dialog (below). From the console: every crate with its reward count and the keys players hold |
| `/crates open <crate> [amount]` | everyone | Opens one key without a dialog (the receipt goes to chat), or up to `bulk-open` keys in a row with one summary |
| `/crates preview <crate>` | everyone | The preview menu |
| `/keyall` | everyone | When the next keyall is and what it gives |
| `/crates give <player> <crate> <amount> [ref]` | staff, console | Gives keys (also to offline players). With `ref` the grant is applied once (store deliveries) |
| `/crates take <player> <crate> <amount>` | staff, console | Takes keys; refused when they have fewer |
| `/crates check <player>` | staff, console | Their keys |
| `/crates log <player> [page]` | staff, console | Their openings, newest first, with the total |
| `/crates info <crate>` | staff, console | Every reward's chance, rarity, and what a key pays on average |
| `/crates block add <crate> [<world> <x> <y> <z>]` | staff, console | Makes the block the player looks at (or the typed block) a crate |
| `/crates block remove [<world> <x> <y> <z>]` | staff, console | Stops a block placed in-game from being a crate |
| `/crates block list` | staff, console | Every crate block and where it comes from |
| `/keyall <crate> <amount>` | staff, console | A keyall right now: everyone online gets the keys |
| `/keyall in <time>` | staff, console | Moves the next scheduled keyall to that time from now (10s to 7d) |

`/keys give ...` and `/keys take ...` are the same commands through the alias.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.crates` | everyone | `/crates`, opening, previews, crate blocks |
| `siftcore.command.keyall` | everyone | `/keyall` (when the next one is) |
| `siftcore.admin.crates` | op | Give, take, check, log, info, crate blocks, `/keyall <crate> <amount>` and `/keyall in` |
| `siftcore.bypass.cooldown` | op | No open cooldown (core node) |

Audit log actions: `crates.give`, `crates.take`, `crates.keyall` (one row per keyall with the player count),
`crates.keyall.schedule`, `crates.block.add`, `crates.block.remove`, and `crates.reward` for every win of a rarity
with `audit: true` (rare and up in the shipped file).

## Screens

- **Crates dialog** (`/crates`, the main menu's Crates button with order 45): every crate with its icon and your key
  count, an Open and a Preview button for each, and the next keyall. From the main menu the footer goes back to it.
- **Opening from a dialog** keeps the client on its waiting screen until the reward is stored and handed over, then
  shows the result: the reward's item with "You won ..." and its rarity, the keys left (or "That was your last key"),
  a line when it went to the claim box, and Open another (while keys are left), "Open 10 more" (with 2 or more keys,
  at most `bulk-open`), Preview and Back. A refusal (no keys, in combat, economy paused, a plugin cancelled it) shows
  the screen again with the reason.
- **Several in a row** ("Open 10", "Open 10 more", `/crates open <crate> <amount>`, right-click on the preview's open
  button): that many single openings one after another, each with every check, its own transaction, log row,
  announcement and hand-over, the next starting only when the last is stored. The player gets one result: the
  rarest win's item, "You opened 10 crates" and a line per reward ("5 diamonds, 10 times"), the keys left, and one
  summary in chat instead of a receipt per key. Running out of keys just ends it; anything else that stops a key
  (combat, a cancelled event, storage) stops the rest and says why. More than `bulk-open` at once is refused.
- **Crate view** (right-click a crate block): the crate's icon, your keys, the number of rewards, Open, "Open 10"
  (with 2 or more keys) and Preview.
- **Preview menu** (left-click a crate block, Preview, `/crates preview`): every reward that can be won now, with
  "Chance 12.5%", "Rarity Rare", "Sells for $1,200" for plain items and "Opens the Rare crate" for keys. The shown
  chances are rounded with the largest remainder method so they always add up to exactly 100% (a chance below
  0.01% reads "<0.01%"). Sort (slot 47): crate order, most likely first, rarest first. Slot 50: "Open one" with your
  keys (the menu is locked while the opening is stored; right-click opens up to `bulk-open` in a row), or "No keys". Back (slot 46) when opened from a dialog.
- **Chat**: the receipt "You won 5 diamonds from the Test crate." after every opening ("It didn't fit, so it's
  waiting in your claim box." when it went there); the announcement "Name won $200,000 from the Legendary crate." to
  everyone else for rarities with `announce: true` (players can turn it off with the "Crate wins" switch, id
  `crate-wins`, in the settings dialog); "You got 3 Rare keys." when staff give keys; a join reminder "You have
  3 keys to open." (clickable) when `join-reminder` is on.

## How an opening works

1. On the player's thread: the crate exists, the player has a key, the player is not in combat (`block-in-combat`),
   the economy accepts transactions, the crate has something to win, no other opening of this player is running, and
   (for `/crates open` and crate blocks) the open cooldown has passed.
2. The reward is drawn from the rewards that can be won right now (`WeightedTable`, chance = weight / total).
3. `CrateOpenEvent` (cancellable) is fired with the player, crate, reward id, display text and rarity.
4. **One `LedgerTx`**: check the crate still exists and the player still has a key (under the economy lock); apply
   keys - 1 (and the keys reward, if any); write the `crate_keys` deltas, the `crate_log` row and, for money and
   shards, the `crate_reward` source postings; reward items go into the claim box (`deliveries().add`) in the same
   transaction. Either all of it is stored or none of it (a storage failure rolls the key back).
5. After `committed()`: the audit row (rare and up), the announcement (epic and up, not for vanished players),
   command rewards (stored in the opening's transaction in `crate_commands`, migration V056, then run from the console
   on the global thread and deleted; a reward whose commands had not run when the server stopped runs at the next
   start, logged, and a crash right after a command ran makes it run again at the next start, also logged; each
   command in try/catch, a failing command is logged with what to give by
   hand), then on the player's thread the items are claimed out of the claim box into the inventory when they all
   fit (marked claimed in storage first, `saveData()` after), and the receipt is sent.

Double clicks, two menus, replayed dialog clicks or a reload in between can never spend a key twice or pay without
one: everything is checked again inside the transaction and dialog clicks are one-shot. Items never touch the ground:
what doesn't fit (or can't be handed over because the player left or the server stops) waits in the claim box
(`/ah claims`). At shutdown, hand-overs that were claimed but not delivered yet go back into the claim box before
storage closes.

## Keyall

A global-thread timer ticks once a second. At the configured moments (`countdown.chat`, shipped 5m and 1m) it
announces "Keyall in 5m. Everyone online gets 1 Basic key." in chat; for the last `countdown.action-bar` seconds
(shipped 10s) everyone's action bar counts down. At zero `KeyallEvent` (cancellable) is fired with the crate, amount
and recipients, then every online player (vanished staff left out unless `include-vanished`, AFK players left out
when `include-afk` is false) gets the keys through
`CrateKeys.give` with the reference `keyall:<run>:<uuid>`, so a player can never get one keyall twice. Everyone
gets "Keyall: everyone online got 1 Basic key." with a click to open their crates; with `include-afk: false`, AFK
players who were left out are told "You were away, so you didn't get 1 Basic key from this keyall."

The next time is stored in `crate_schedule`, so restarts keep the schedule. When the server was offline at keyall
time, the keyall runs `missed-delay` (shipped 10m) after the next startup. A reload never moves it further away than
one interval; switching it back on after its time passed schedules it one interval from then.

## Crate blocks

Blocks come from `crates.<id>.blocks` ("world x y z") and from `/crates block add` (table `crate_blocks`). A file
entry wins when both name the same block; a placed block of a crate that no longer exists (or in a world that is not
loaded) stays stored but does nothing, and the self-test points it out. The blocks don't need to be anything special
(a chest, an ender chest, a beacon...); interacting never opens or uses the block itself.

- Right-click: the crate view. Sneak + right-click (`quick-open`): open a key straight away.
- Left-click: the preview menu.
- Protection: breaking is cancelled for everyone (staff are told how to remove it), explosions skip the block,
  pistons can't move it, fire can't burn it and mobs can't change it. No physics, hopper or move events are used.
- Clicks are handled in two steps. At LOWEST priority a click on a crate block is marked handled (the block itself is
  never used), so protections that skip handled clicks, such as the spawn area, neither refuse it nor show "You
  can't use that at spawn": crate blocks work inside the protected spawn without listing their block type in
  `spawn.yml`'s `allowed-interactions` (that list then keeps protecting every other chest at spawn). At HIGHEST the
  crate acts, unless another plugin refused the player in between by denying the item use as well (a frozen player
  gets the freeze's "You can't do that while frozen." and no crate screen).

To label a crate block, place a display board above it with `/displays` (the displays feature): its templates can
show `{keyall_countdown}` and `{keyall_reward}`, and its click command can be `crates preview <crate>`.

## Placeholders

| Placeholder | Value |
|---|---|
| `keys_<crate>` | The player's keys of that crate (`keys_basic`) |
| `keys_total` | All of the player's keys together |
| `keyall_countdown` | Time until the next keyall (`3h 59m`), `-` when it is off |
| `keyall_reward` | What the next keyall gives (`1 Basic key`), `-` when it is off |

## Config (`features/crates.yml`)

- `open-cooldown` (shipped 1s): between openings with `/crates open` or a crate block. Dialogs and the preview wait
  for each opening anyway.
- `block-in-combat` (shipped true): players in combat can't open crates ("You can't open crates in combat. 12s
  left."); the combat feature sets how long a tag lasts.
- `bulk-open` (shipped 10, 0 to 64; below 2 turns it off): the most keys one click or command opens in a row.
- `quick-open`, `join-reminder`, `grants.remember` (1d to 3650d).
- `rarities`: id to `label` (plain text), `audit` and `announce`, from most common to rarest.
- `keyall`: `enabled`, `interval` (5m to 7d), `crate`, `amount` (1 to 64), `missed-delay` (0s to 1h),
  `include-vanished`, `include-afk`, `countdown.chat` (each at least 10s and shorter than the interval), `countdown.action-bar`
  (0s to 1m).
- `crates`: id (1 to 32 lowercase letters, digits, `-`, `_`; `total` and `in` are reserved) to `name` (plain short
  name; text says "Basic crate", "Basic key"), `icon`, `blocks`, `rewards`.
- A reward: id to `weight`, `rarity`, optional `display` (plain text, generated otherwise: "16 iron ingot", "$750",
  "10 shards", "2 Rare keys", "zombie spawner"), optional `icon`, and exactly one of `item` (with `amount`, `name`,
  `lore`, `enchants`, `unsafe-enchants`), `money`, `shards`, `keys` (with `amount`), `spawner` (with `amount`) or
  `commands` (needs `display` and `icon`; `%player%` and `%uuid%`).

Everything is validated and each mistake is one precise problem: unknown items, enchantments, mobs, worlds, crates
and rarities, levels above the enchantment's maximum without `unsafe-enchants`, rewards with no kind or two kinds,
unknown keys (typos), tags or colour codes in plain text, duplicate crate blocks, bad countdown moments. At startup a
broken reward is left out, a crate without a working reward is left out, and a keyall with an unknown crate is off;
`/sift reload` refuses the whole change.

## Shipped crates and what a key is worth

The shipped weights of every crate add up to 100, so each weight is its chance in percent. Money is kept a small
extra: the economy's real money comes from selling, and a keyall gives one basic key every 4 hours. Measured with
`/crates info` against the shipped worth table (items count at what the server pays for them; enchanted gear,
totems, elytras and books can't be sold, so they count as $0 here):

| Crate | Where keys come from | Money per key | Shards per key | Items (sell value) per key | Keys per key |
|---|---|---|---|---|---|
| Basic | keyall, staff | $175 | 0.6 | $284 | 0.02 Rare |
| Rare | 2% of basic, store, shard shop | $810 | 3.2 | $1,064 | 0.18 Basic, 0.03 Epic |
| Epic | 3% of rare, store, shard shop | $3,800 ($3,958 without spawners) | 12 (12.5) | $1,953 without spawners | 0.19 Rare, 0.02 Legendary |
| Legendary | 2% of epic, store | $20,750 ($22,554 without spawners) | 40 (43) | $8,101 without spawners | 0.17 Epic |

The figures in brackets apply while spawner items are not available (the spawner rewards are left out and the other
chances grow). For scale: the shop sells a zombie spawner for $60,000 and a diamond sells for $400.

- Basic: iron and gold ingots, steak, experience bottles, an enchanted iron pickaxe and chestplate, 3 diamonds,
  3 golden apples, $750, $2,500, 10 shards, and a 2% Rare key.
- Rare: 8 diamonds, 16 emeralds, 8 golden apples, enchanted diamond pickaxe, sword and chestplate, a Mending book,
  a totem, $3,000, $7,500, 40 shards, 3 Basic keys, and a 3% Epic key.
- Epic: 4 diamond blocks, 2 netherite scrap, Protection IV diamond armor pieces, Sharpness V sword, Efficiency V
  pickaxe, an enchanted golden apple, an elytra (2%), a zombie spawner (4%), $15,000, $40,000, 150 shards, 3 Rare
  keys, and a 2% Legendary key.
- Legendary: netherite ingots, maxed netherite sword, pickaxe and chestplate with Mending, 3 enchanted golden
  apples, a Mending elytra, a beacon, skeleton (5%) and blaze (3%) spawners, $75,000, $200,000 (4%), 500 shards and
  2 Epic keys.

## Storage

- `crate_keys (uuid, crate, amount)`: changed only by deltas inside transactions, in transaction order, so a rolled
  back transaction never leaves a wrong count; a row that reaches 0 is deleted. Loaded into memory at startup.
- `crate_log (id, ts, uuid, crate, reward, detail)`: one row per opening, written in its transaction (`detail` is the
  display text and the opening's reference, which is also the ledger posting ref and the claim box ref).
- `crate_grants (ref PK, uuid, crate, amount, actor, ts)`: applied grant references, written with the grant; rows
  older than `grants.remember` are deleted at startup and every 6 hours (the delete is queued under the economy lock,
  so a reference reused after that is stored after the delete).
- `crate_blocks (world, x, y, z PK, crate, placed_by, placed_at)`: blocks placed with `/crates block add`.
- `crate_schedule (id PK, next_run, last_run, runs)`: the keyall schedule (`id = 'keyall'`).

## Self-test (`/sift selftest`)

- every crate has something to win right now;
- the weighted draw splits evenly spread draws exactly by weight;
- every crate's shown chances add up to 100%;
- every item and spawner reward can be made and survives a storage round trip;
- keys in memory equal storage exactly (the memory snapshot and the storage read are taken in the same moment under
  the economy lock and the ordered writer), and the remembered references match;
- no placed crate block points at a missing crate or an unloaded world;
- the keyall timer is ticking and the next keyall is within one interval.

## Tests

- Unit (`src/test/java/.../feature/crates`): the weighted draw against the shipped basic crate with a chi-square
  test over 200,000 draws per seed, boundaries and bad weights; shown chances adding up to exactly 100% for 2,000
  random tables; keys against a real SQLite database and ledger (give and take, persistence across a restart,
  references applied once even across a restart and under 16 racing threads, forgotten references, refusals, an
  opening's key, money, keys and log row in one transaction, a storage failure rolling the key back); settings
  parsing of the shipped file and every mistake reported at its exact path; the keyall clock (restarts, missed
  keyalls, reloads, countdown moments); who gets keyall keys (vanished and AFK players with every combination of
  `include-vanished` and `include-afk`); inventory fitting; expected values; the lang file against the design system;
  command rewards stored with the opening, run once it is stored and deleted, kept for the next start when the
  scheduler stopped or refused them (`RewardCommandsTest`).
- End-to-end (`tools/e2e`, `CratesScenarios`): `crates-open` (dialog, result, Open another, Back, refusal, main
  menu entry), `crates-rewards` (money with the announcement and the ledger row, keys, a command, the Crate wins
  setting), `crates-claim-box`
  (full inventory, then claimed from `/ah claims`), `crates-double-submit` (a double click, a replayed click, command
  spam), `crates-preview` (chances, rarity, sell value, sorting, opening from the menu, back to the dialog),
  `crates-admin` (give with a reference twice, take, check, log, info, placeholders, the `CrateKeys` contract,
  refusals), `crates-keyall` (staff keyall without vanished staff, the 1m chat countdown, the action bar and the
  scheduled run with its references), `crates-block` (right-click, left-click, sneak quick open, breaking, an
  explosion, removal), `crates-bulk` (Open 10 more after a
  single opening, the batch result and its one chat summary, `/crates open <crate> 5` stopping when the keys run out,
  the bulk limit, a batch into the claim box with one note, right-click on the preview's open button),
  `crates-combat` (a real hit tags the player: the command and the dialog refuse with the time
  left, nothing is spent, previews still open; after `/combat untag` the crate opens), `crates-spawn-block` (a crate
  block inside the protected spawn opens without the spawn refusal while an ordinary chest next to it stays protected;
  a frozen player gets the freeze refusal and no crate screen, even sneaking; after the unfreeze it works), `crates-persist-setup`/`crates-persist-check` (keys, references and the keyall schedule
  across a restart), `crates-command-stored` (a command reward waits for its opening to be stored, runs once, and its
  stored row is deleted).

Not verifiable without a real client: how the dialogs and the preview menu look, the waiting screen between Open and
the result, and icons in the keyall line.
