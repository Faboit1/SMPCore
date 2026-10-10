# Crates (`crates`)

Players open crates with virtual keys. Each opening spends one key and pays one reward, drawn by weight: items,
money, shards, keys of another crate, spawners or a console command. The shipped file has seven tiers, from Common to
Celestial, each in its own colour and each better than the one below; every crate has a small chance of a key of the
next tier. Keys come from the keyall (every player online, every few hours), the shard shop, kits and staff. Keys
are never sold ([monetization](../monetization.md)); `/sift store keys` is a staff and event tool. Crates can also be
blocks in the world, with a floating name, particles in their colour and the reward spinning up out of them. Opening
one key from a screen plays an opening animation (a rolling chest window with ticks and a fanfare). Package
`feature/crates`, config `features/crates.yml`, text `lang/crates.yml`, tables `crate_keys` and `crate_log` (V008)
plus `crate_grants`, `crate_blocks` and `crate_schedule` (V055).

The feature implements `core.link.CrateKeys`; `CratesFeature#keys()` returns it for the features that hand out keys
(the shard shop, kits that include keys, and store delivery, which is built before crates and gets a late-bound
reference):

- `crates()`: the configured crate ids;
- `give(player, crate, amount, actor, ref)`: one transaction, stored before `committed()` completes. With a `ref`
  (at most 64 characters) the grant is applied once: the same `ref` again is refused with reason `duplicate`, even
  after a restart, for as long as `grants.remember` (90 days). Other refusals: `unknown_crate`, `bad_amount`,
  `bad_ref`, `limit` (1,000,000 keys of one crate);
- `keys(player, crate)`: the count, from memory;
- `crateName(crate)`: the crate's name in its colour ("Common" in gray), for the shard shop's key offers.

It consumes five contracts, all wired in `FeatureCatalog`:

| Contract | Wired | Used for |
|---|---|---|
| `WorthLookup` | the sell feature (`SellFeature#worth()`) | "Sells for" in the preview and the item value in `/crates info` |
| `SpawnerItems` | the spawners feature (`SpawnersFeature#items()`) | Spawner rewards. While the provider can't make a mob's spawner, that reward is left out of the crate and the other chances grow to fill in (one INFO line at startup says how many; no warning) |
| `VanishStatus` | the staff feature (`StaffFeature#vanish()`) | Vanished staff get no keyall keys (unless `include-vanished`) and their wins are never announced |
| `CombatStatus` | the shared combat tags (`CombatTags`) | With `block-in-combat` (shipped on) a player in combat can't open crates (no totems or golden apples mid-fight); looking at crates and previews still works |
| `AfkStatus` | the AFK feature (`AfkFeature#status()`) | With `keyall.include-afk: false` AFK players get no keyall keys (shipped `true`: staying online is what earns them) |

Money and shards are paid with the ledger kind `crate_reward`, which the stats feature counts as money earned.

While the spawners feature is off, its provider is `SpawnerItems.NONE` and spawner rewards are left out as above (they
come back on their own once spawners run). The shard shop and store delivery give keys with a `ref` (order id,
purchase id), so a retried delivery is never paid twice. Crate blocks at spawn need no entry in `spawn.yml`'s
`allowed-interactions` (see Crate blocks). Crates is in the shipped pause menu (`hub.yml` `pause-menu.entries`).

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
with `audit: true` (epic and up in the shipped file).

## Screens

- **Crates dialog** (`/crates`, the main menu's Crates button with order 45): one line with the next keyall and what
  it gives, then a button per crate from the lowest tier up, "Common crate: 3 keys" (the name in the crate's colour,
  the key count in the accent colour, "no keys" in gray). Its tooltip says how many rewards the crate has and the
  rarest of them ("14 rewards, the best of them Epic"). Nothing else is written on the screen; there are no pages
  (the dialog scrolls). From the main menu the footer goes back to it.
- **A crate's page** (a crate button, or right-click a crate block): the crate's icon and your keys, then Open, "Open
  10" (with 2 or more keys, the player's Keys per bulk open, at most `bulk-open`) and Preview; what each does is in its
  tooltip ("Open one key. Click the window to skip to the reward." while the animation is on). Back returns to the
  list (or Close, from a block).
- **Opening one key from a screen** (Open, Open another, the preview's Open one) keeps the client on its waiting
  screen until the reward is stored, then plays the opening animation (below) when it is on, and shows the result:
  the reward's item with "You won ..." (in its rarity's colour) and its rarity, the keys left (or "That was your last
  key"), a line when it went to the claim box, and Open another (while keys are left), "Open 10 more", Preview and
  Back. A refusal (no keys, in combat, economy paused, a plugin cancelled it) shows the screen again with the reason.
- **Several in a row** ("Open 10", "Open 10 more", `/crates open <crate> <amount>`, right-click on the preview's open
  button): that many single openings one after another, each with every check, its own transaction, log row,
  announcement and hand-over, the next starting only when the last is stored. The player gets one result: the
  rarest win's item, "You opened 10 crates" and a line per reward ("5 diamonds, 10 times"), the keys left, and one
  summary in chat instead of a receipt per key. Running out of keys just ends it; anything else that stops a key
  (combat, a cancelled event, storage) stops the rest and says why. More than `bulk-open` at once is refused.
- **Preview menu** (left-click a crate block, Preview, `/crates preview`): every reward that can be won now, named in
  its rarity's colour (money in the money colour, shards in purple, a spawner keeps its name "Blaze spawner" in its
  rarity's colour), with "Chance 12%", "Rarity Rare" (in its colour), "Sells for $1,200" for plain items and "Opens
  the Uncommon crate" for keys. The shown chances are rounded with the largest remainder method so they always add up
  to exactly 100% (a chance below 0.01% reads "<0.01%"). Sort (slot 47): by rarity (most common first, the default),
  most likely first, rarest first. Slot 50: "Open one" with your keys (animated like Open; right-click opens up to
  `bulk-open` in a row without the animation), or "No keys". Back (slot 46) when opened from a dialog.
- **Chat**: the receipt "You won 5 diamonds from the Common crate." after every opening, the reward in its rarity's
  colour and the crate's name in the crate's colour ("It didn't fit, so it's waiting in your claim box." when it went
  there; where the receipt shows is the player's Crate win receipt); the announcement "Name won $250,000 from the
  Legendary crate." to everyone else for rarities with `announce: true` (legendary, mythic and celestial in the
  shipped file; filtered by each player's Crate win announcements, never for vanished winners); "You got 3 Rare keys."
  when staff give keys; a join reminder "You have 3 keys to open." (clickable) when `join-reminder` is on and the
  player's Unopened key reminder is.

## The opening animation

`effects.animation` (shipped on). Opening one key from a screen (Open, Open another, the preview's Open one) opens a
three-row chest window titled after the crate:

- The middle row is a reel of the crate's rewards (each named in its rarity's colour) rolling right to left past the
  pointer (the glass above and below the middle slot, in the crate's colour), while the other glass flashes. Each
  step plays a tick (`sounds.tick`, a hi-hat) whose pitch rises as the roll slows down; the steps slow down on an
  ease-out curve over `length` (shipped 4s). The reel is drawn by the rewards' weights, so common rewards pass by
  more often; the rarest reward of the crate always passes right after the pointer for a near miss.
- At the end the window turns the colour of the won reward's rarity (its `glass`, or the stained glass pane closest
  to its colour), the reward glows under the pointer with its rarity below its name, and the reveal sound plays
  (`sounds.reveal`, or `sounds.big-reveal` for a rarity that is announced). That is when the reward reaches the
  inventory, the receipt is sent and an announced win is announced. The window closes by itself after `reveal`
  (shipped 2s), then the result dialog shows.
- Clicking the window during the roll skips to the reward; clicking it while the reward shows closes it. Closing the
  window (Esc) during the roll hands the reward over at once. The sounds follow each player's sound settings (ticks
  are click sounds, the reveals success sounds).
- Opened at a crate block (from its page), the reward also spins up out of the block (below), and the block's lid
  opens while it spins (chests, ender chests, barrels, shulker boxes).

The animation only shows what was already decided and stored: the key was spent and the reward stored in the same
transaction as every opening, before the window opens (the items wait in the claim box meanwhile). A player who
leaves during the roll finds the reward in the claim box (`/ah claims`); one who dies keeps it there (nothing
drops); the win is announced either way. A player can't open another crate while one rolls ("Your last crate is
still opening."). Sneak + right-click on a crate block (quick open), `/crates open` and opening several keys at once
never animate, so a player who wants it fast has those.

The window is a SiftCore menu on the player's own thread (an entity timer that follows them across regions and
stops when they leave); it never touches other players or blocks. Players see nothing different with the animation
off, except that the result comes at once.

## Player settings

Registered by `CratePlayerSettings` (text in `lang/crates.yml` under `crates.settings`). Each is offered only while
`crates.yml` turns its behaviour on, so a switch that does nothing never shows.

| Id | Group | Kind, default | What it does | Offered while |
|---|---|---|---|---|
| `crate-wins` | Server announcements (3) | choice all/rarest/off, all | Other players' announced wins in chat: every one, only the rarest rarity the server announces (the last rarity with `announce: true`), or none. Was a switch: stored `true` reads as all, `false` as off | a rarity announces; rarest only while two or more do (otherwise it reads as all) |
| `crate-receipt` | Crates & kits (1) | choice chat/actionbar/off, chat | Where the player's own "You won ..." line shows (`Messenger.alert`). Several openings in a row: the whole list in chat, or "You opened 10 Common crates. Best: ..." above the hotbar. Rewards sent to the claim box are always told in chat; refusals are unaffected, except that the reason a bulk opening stopped early goes to chat when its receipt took the action bar (it would replace the receipt there at once); the result dialog always shows | always |
| `crate-key-reminder` | Crates & kits (3) | toggle, on | The join reminder about unopened keys | `join-reminder` |
| `keyall-countdown` | Crates & kits (4) | choice both/chat/actionbar/off, both | The keyall's chat announcements and/or its action bar count. The console always gets the announcements; "Keyall: everyone online got ..." always shows. Only what the server shows is offered: chat while `keyall.countdown.chat` has times, actionbar while `countdown.action-bar` is above 0s, both while both are. On a server with only one, both and the missing one read as the one there is (except both on a server with only the action bar count and a configured default of off, which reads as off) | the keyall is on and announces or counts down |
| `crate-quick-open` | Crates & kits (5) | choice one/bulk/off, one | Sneak + right-click a crate block: open one key, open the player's Keys per bulk open in a row (one receipt), or show the crate window like a plain right-click | `quick-open`; bulk while `bulk-open` is 2 or more (otherwise it reads as one) |
| `crate-bulk-amount` | Crates & kits (7) | number 2-64 keys, 10 | How many keys "Open n", "Open n more", a bulk quick-open and a right-click on the preview's open button open at once; never more than the player has or `bulk-open`. `/crates open <crate> <amount>` keeps its explicit amount | `bulk-open` is 2 or more |

The kits feature fills the other places of Crates & kits (see `kits.md`). The preview menu remembers the sort a
player last picked (`crate-preview-sort`, a remembered value, not a setting).

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
5. After `committed()`: the audit row (epic and up), command rewards (stored in the opening's transaction in `crate_commands`, migration V056, then run from the console
   on the global thread and deleted; a reward whose commands had not run when the server stopped runs at the next
   start, logged, and a crash right after a command ran makes it run again at the next start, also logged; each
   command in try/catch, a failing command is logged with what to give by
   hand), then on the player's thread the opening animation plays (when asked for and on), and at its reveal, or at
   once without it: the announcement (legendary and up, not for vanished players), the items claimed out of the claim
   box into the inventory when they all fit (marked claimed in storage first, `saveData()` after; a dead player's
   stay in the claim box), and the receipt.

Double clicks, two menus, replayed dialog clicks or a reload in between can never spend a key twice or pay without
one: everything is checked again inside the transaction and dialog clicks are one-shot. Items never touch the ground:
what doesn't fit (or can't be handed over because the player left or the server stops) waits in the claim box
(`/ah claims`). At shutdown, hand-overs that were claimed but not delivered yet go back into the claim box before
storage closes.

## Keyall

A global-thread timer ticks once a second. At the configured moments (`countdown.chat`, shipped 5m and 1m) it
announces "Keyall in 5m. Everyone online gets 1 Uncommon key." in chat; for the last `countdown.action-bar` seconds
(shipped 10s) everyone's action bar counts down. Each player's Keyall countdown picks both, only the chat lines, only
the action bar, or neither (the console always gets the chat lines). At zero `KeyallEvent` (cancellable) is fired with the crate, amount
and recipients, then every online player (vanished staff left out unless `include-vanished`, AFK players left out
when `include-afk` is false) gets the keys through
`CrateKeys.give` with the reference `keyall:<run>:<uuid>`, so a player can never get one keyall twice. Everyone
gets "Keyall: everyone online got 1 Uncommon key." with a click to open their crates; with `include-afk: false`, AFK
players who were left out are told "You were away, so you didn't get 1 Uncommon key from this keyall." With
`one-per-connection: true` (shipped) only the first account of each connection gets keys, in the order players joined
(the one online longest), like the AFK zone: a connection is an IPv4 address or an IPv6 /64
(`PlayerDirectory#connection`, dupe audit R17), so alt accounts parked online don't multiply the keyall. The others are
told "Another account on your connection got 1 Uncommon key from this keyall." Vanished staff and left-out AFK players
don't take their connection's share. The shipped
keyall gives 1 Uncommon key every 4 hours (it gave a Basic key, now called Common, before the tiers).

The next time is stored in `crate_schedule`, so restarts keep the schedule. When the server was offline at keyall
time, the keyall runs `missed-delay` (shipped 10m) after the next startup. A reload never moves it further away than
one interval; switching it back on after its time passed schedules it one interval from then.

## Crate blocks

Blocks come from `crates.<id>.blocks` ("world x y z") and from `/crates block add` (table `crate_blocks`). A file
entry wins when both name the same block; a placed block of a crate that no longer exists (or in a world that is not
loaded) stays stored but does nothing, and the self-test points it out. The blocks don't need to be anything special
(a chest, an ender chest, a beacon...); interacting never opens or uses the block itself.

- Right-click: the crate's page. Sneak + right-click (`quick-open`): open a key straight away (no animation), several
  in a row, or the crate's page, as the player's Sneak + right-click a crate setting says.
- Left-click: the preview menu.
- Protection: breaking is cancelled for everyone (staff are told how to remove it), explosions skip the block,
  pistons can't move it, fire can't burn it and mobs can't change it. No physics, hopper or move events are used.
- Clicks are handled in two steps. At LOWEST priority a click on a crate block is marked handled (the block itself is
  never used), so protections that skip handled clicks, such as the spawn area, neither refuse it nor show "You
  can't use that at spawn": crate blocks work inside the protected spawn without listing their block type in
  `spawn.yml`'s `allowed-interactions` (that list then keeps protecting every other chest at spawn). At HIGHEST the
  crate acts, unless another plugin refused the player in between by denying the item use as well (a frozen player
  gets the freeze's "You can't do that while frozen." and no crate screen).

### What a crate block shows (`effects`)

- **A floating name** (`holograms`, shipped on): a text display `hologram-height` (shipped 0.5) blocks above the
  block, "Common crate" in the crate's colour (bold), then "Right-click to open" and "Left-click to see the rewards"
  (`lang/crates.yml` `crates.hologram`). It faces every player, has no background and is lit fully.
- **Particles** (`particle-range`, shipped 16 blocks; 0 turns them off): two motes in the crate's colour circle the
  block, rising and falling, every quarter second while a player is within range; Legendary and up also sparkle
  (end rods). Nothing is sent while nobody is near.
- **The spin**: opened from the block's page with the animation on, the reward appears above the block, spins and
  rises as the reel steps (showing the item under the pointer), then shows the won reward bigger, glowing in its
  rarity's colour, with a burst of that colour (a totem burst for announced rarities). It goes away about two seconds
  after the reveal. One spin per block at a time: a second player opening there meanwhile gets only their window.

Threads: one global timer (every 5 ticks) hops to each crate block's region, only for blocks whose chunk is loaded;
everything that touches an entity or the block happens on that region's thread. Nothing scans the world. Every
display entity is non-persistent (never saved; gone when its chunk unloads or the server stops) and tagged, and
whenever a chunk's entities load, tagged crate displays that are not the current ones (after a crash or a reload)
are removed, so a name is never doubled. A block that stops being a crate loses its name within a quarter second.

A display board from `/displays` above a crate block still works (its templates can show `{keyall_countdown}` and
`{keyall_reward}`, its click command can be `crates preview <crate>`); raise `effects.hologram-height` or turn
`holograms` off so the two don't overlap.

## Placeholders

| Placeholder | Value |
|---|---|
| `keys_<crate>` | The player's keys of that crate, by id (`keys_basic` is the Common crate) |
| `keys_total` | All of the player's keys together |
| `keyall_countdown` | Time until the next keyall (`3h 59m`), `-` when it is off |
| `keyall_reward` | What the next keyall gives (`1 Uncommon key`), `-` when it is off |

## Config (`features/crates.yml`)

- `open-cooldown` (shipped 1s): between openings with `/crates open` or a crate block. Dialogs and the preview wait
  for each opening anyway.
- `block-in-combat` (shipped true): players in combat can't open crates ("You can't open crates in combat. 12s
  left."); the combat feature sets how long a tag lasts.
- `bulk-open` (shipped 10, 0 to 64; below 2 turns it off): the most keys one click or command opens in a row.
- `quick-open`, `join-reminder`, `grants.remember` (1d to 3650d).
- `rarities`: id to `label` (plain text), `color` (hex), `glass` (the animation's glass for a win of that rarity;
  the closest stained glass pane by default), `audit` and `announce`, from most common to rarest. Shipped: common
  (gray), uncommon (green), rare (blue), epic (purple), legendary (orange), mythic (red) and celestial (cyan); epic
  and up are audited, legendary and up announced.
- `effects`: `holograms`, `hologram-height` (0 to 3), `particle-range` (0 to 64), and `animation` with `enabled`,
  `length` (1s to 10s), `reveal` (0s to 10s) and the `sounds` `tick`, `reveal` and `big-reveal` (each a sound id,
  `volume` 0 to 2 and `pitch` 0.5 to 2).
- `keyall`: `enabled`, `interval` (5m to 7d), `crate`, `amount` (1 to 64), `missed-delay` (0s to 1h),
  `include-vanished`, `include-afk`, `countdown.chat` (each at least 10s and shorter than the interval), `countdown.action-bar`
  (0s to 1m).
- `crates`: id (1 to 32 lowercase letters, digits, `-`, `_`; `total` and `in` are reserved) to `name` (plain short
  name; text says "Common crate", "Common key"), `tier` (1 to 100, its place in the ladder: crates are listed by tier,
  and "the next tier" is the lowest tier above; the crate's place in the file when missing), `color` (hex: its name
  everywhere, its hologram, its particles and the animation's pointer), `icon`, `blocks`, `rewards`. The id is what
  players' keys are stored under, so the shipped `basic` crate keeps its id and is called Common.
- A reward: id to `weight`, `rarity`, optional `display` (plain text, generated otherwise: "16 iron ingot", "$750",
  "10 shards", "2 Rare keys", "zombie spawner"), optional `icon`, and exactly one of `item` (with `amount`, `name`,
  `lore`, `enchants`, `unsafe-enchants`), `money`, `shards`, `keys` (with `amount`), `spawner` (with `amount`) or
  `commands` (needs `display` and `icon`; `%player%` and `%uuid%`).

Everything is validated and each mistake is one precise problem: unknown items, enchantments, mobs, worlds, crates
and rarities, levels above the enchantment's maximum without `unsafe-enchants`, rewards with no kind or two kinds,
unknown keys (typos), tags or colour codes in plain text, duplicate crate blocks, bad countdown moments. At startup a
broken reward is left out, a crate without a working reward is left out, and a keyall with an unknown crate is off;
`/sift reload` refuses the whole change.

## The seven tiers and what a key is worth

The shipped weights of every crate add up to 100, so each weight is its chance in percent. Each tier is worth two to
four times the one below, every crate has 14 to 18 rewards from common to its own top rarity, and every crate but the
last has a 1 to 2% chance of a key of the next tier (most also give a few keys of lower tiers). Measured with
`/crates info` and the shipped price tables: items at what the server pays for them (enchanted gear can't be sold and
counts as $0), spawners at the shop's price, a shard at $20 and keys at their own tier's value:

| Tier | Crate (id), colour | Where keys come from | Money | Items | Spawners | Shards | Keys of other tiers | Worth per key |
|---|---|---|---|---|---|---|---|---|
| 1 | Common (`basic`), gray | shard shop (50), Uncommon and Rare crates | $320 | $381 | | 1.1 | 0.05 Uncommon, 0.01 Rare | $880 |
| 2 | Uncommon (`uncommon`), green | keyall (1 every 4h), shard shop (110), 5% of Common | $860 | $1,169 | | 1.8 | 0.06 Common, 0.02 Rare | $2,212 |
| 3 | Rare (`rare`), blue | shard shop (200), 1 to 2% of Common and Uncommon | $2,210 | $1,847 | | 3.6 | 0.12 Common, 0.08 Uncommon, 0.02 Epic | $4,721 |
| 4 | Epic (`epic`), purple | shard shop (600), 2% of Rare | $7,000 | $3,943 | $2,400 | 12 | 0.15 Rare, 0.02 Legendary | $15,497 |
| 5 | Legendary (`legendary`), orange | shard shop (1,500), 2% of Epic | $25,000 | $9,655 | $19,500 | 52.5 | 0.14 Epic, 0.02 Mythic | $60,321 |
| 6 | Mythic (`mythic`), red | shard shop (4,000), 2% of Legendary | $64,000 | $30,700 | $36,000 | 120 | 0.10 Legendary, 0.02 Celestial | $147,326 |
| 7 | Celestial (`celestial`), cyan | shard shop (11,000), 2% of Mythic | $190,000 | $84,795 | $110,000 | 360 | 0.12 Mythic | $409,674 |

Money stays under half of what a key pays at every tier. Keys that existed before the tiers are worth more than they
were (Common $880, was $540 as Basic; Rare $4,721, was $2,373; Epic $15,497, was $10,479; Legendary $60,321, was
$58,207), so nobody's stored keys lost value (`ExpectedValueTest` keeps it that way).

**Against how keys are earned** (keys are never sold for real money, see [monetization](../monetization.md)):

- The keyall gives everyone online 1 Uncommon key every 4 hours: $2,212 every 4 hours, about $550 an hour online.
- The AFK zone pays 1 shard a minute (60 an hour, no daily limit in the shipped file). Spent in the shard shop, an
  hour of AFK buys about $1,060 (Common keys) to $2,400 (Legendary keys) of crate value; the higher tiers give more
  per shard, as a reward for saving up: a Celestial key takes about 183 hours of AFK. The price of each key is in
  `features/shards.yml`; raising one lowers what an AFK hour is worth.
- Buying keys never makes shards: a key pays back 8 to 17% of its shard price in shards and other keys (at their
  shard prices), see [shards](shards.md#why-shards-never-become-money).
- For scale: a diamond sells for $400, the shop's zombie spawner costs $60,000 and its blaze spawner $600,000.

What each crate holds (the full list, with chances, is in `/crates preview <crate>` and `features/crates.yml`):

- **Common**: iron and gold ingots, steak, experience bottles, ender pearls, an Efficiency IV iron pickaxe, a
  Protection III iron chestplate, 4 diamonds, 4 golden apples, $1,000, $4,000, 15 shards, an Uncommon key (5%) and a
  Rare key (1%).
- **Uncommon**: iron and gold blocks, 64 experience bottles, 10 diamonds, 12 emeralds, 6 golden apples, enchanted
  diamond pickaxe, sword and boots, $3,000, $10,000, 30 shards, 2 Common keys and a Rare key (2%).
- **Rare**: 16 diamonds, 24 emeralds, 12 golden apples, 16 ender pearls, 2 diamond blocks, netherite scrap,
  enchanted diamond pickaxe, sword and chestplate, a totem, a Mending book, $8,000, $25,000, 60 shards, Common and
  Uncommon keys and an Epic key (2%).
- **Epic**: 8 diamond blocks, 3 netherite scrap, 128 experience bottles, 3 totems, Protection IV diamond armour,
  Sharpness V sword, Efficiency V pickaxe, an enchanted golden apple, a shulker box, an elytra (3%), a zombie spawner
  (4%), $25,000, $80,000, 200 shards, 3 Rare keys and a Legendary key (2%).
- **Legendary**: netherite ingots, the Legendary netherite sword, pickaxe and chestplate (vanilla's best enchants with
  Mending), 3 enchanted golden apples, 5 totems, 2 shulker boxes, Legendary wings (a Mending elytra), a beacon, skeleton (5%) and blaze (2%) spawners, $100,000, $250,000, 750 shards,
  2 Epic keys and a Mythic key (2%).
- **Mythic**: 8 netherite ingots, 2 netherite blocks, the Mythic netherite set and sword (every useful enchant, Thorns,
  Swift Sneak and Soul Speed included, and Unbreaking IV), Mythic wings (Unbreaking IV), 8 enchanted golden apples, 4 shulker boxes, a beacon, blaze (4%) and enderman (3%) spawners, $200,000, $600,000,
  2,000 shards, 2 Legendary keys and a Celestial key (2%).
- **Celestial**: the Celestial armour, blade, pickaxe and wings, past vanilla's limits (Protection V, Sharpness VI,
  Efficiency VI, Fortune and Looting IV, Unbreaking V, with Mending; `unsafe-enchants: true`), 6 netherite blocks, 3 beacons, 32 enchanted golden apples, 16 totems, 8 shulker boxes, 2 blaze spawners, an iron
  golem spawner (2%), $500,000, $2,000,000 (5%), 6,000 shards and 2 Mythic keys.

The same piece of gear is better in every higher crate (each enchant at least as high, one higher or added) and is
named after its crate ("Legendary sword", "Mythic sword", "Celestial blade"), so receipts and announcements show which
one was won. `CratesSettingsTest#shippedGearGetsBetterWithEveryTier` keeps it that way.

While spawner items are not available (the spawners feature is off) the spawner rewards are left out and the other
chances grow to fill in.

### Upgrading a server that ran the old four crates

`features/crates.yml` is upgraded in place like every shipped file: crates, rewards, rarities and settings the server
never edited take the new values, new ones are added, and nothing is ever removed. Every old crate id (`basic`,
`rare`, `epic`, `legendary`) and every old reward id is kept with the same kind of reward, so stored keys, the crate
log, crate blocks, `keys_<crate>` placeholders, kits and store commands that name a crate keep working. A crate or
reward the server edited keeps the server's version (`TierUpgradeTest` checks both). The keyall moves from 1 Basic to
1 Uncommon key unless it was changed.

### Placing the crates at spawn

Crate blocks are placed in game and stored in the database (or listed under `blocks:` in the file). A shulker box,
chest, ender chest or barrel opens its lid while a reward spins above it. A layout that matches the colours:

| Crate | Block | Command (look at the block) |
|---|---|---|
| Common | chest | `/crates block add basic` |
| Uncommon | lime shulker box | `/crates block add uncommon` |
| Rare | light blue shulker box | `/crates block add rare` |
| Epic | purple shulker box | `/crates block add epic` |
| Legendary | orange shulker box | `/crates block add legendary` |
| Mythic | red shulker box | `/crates block add mythic` |
| Celestial | cyan shulker box | `/crates block add celestial` |

Put them in a row or an arc inside the protected spawn, at least 3 blocks apart (each has a name 1.5 blocks above it
and particles circling it), with open sky or 3 blocks of air above. Crate blocks at spawn need no change to
`spawn.yml`. `/crates block list` shows them all; `/crates block remove` while looking at one undoes it. Each block
shows its floating name within a second; nothing else is needed (no reload).

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
- the opening animation's reel ends on the reward won, and a roll lasts the configured length;
- keys in memory equal storage exactly (the memory snapshot and the storage read are taken in the same moment under
  the economy lock and the ordered writer), and the remembered references match;
- no placed crate block points at a missing crate or an unloaded world;
- the keyall timer is ticking and the next keyall is within one interval.

## Tests

- Unit (`src/test/java/.../feature/crates`): the seven shipped tiers (order, colours, 10 to 20 rewards each, a key of
  the next tier in every crate but the last, each tier worth more than the one below, old keys worth at least what
  they were: `CratesSettingsTest`, `ExpectedValueTest`); the opening animation's plan (`AnimationPlanTest`: the reel
  lands on the reward won for every length, the rarest passes right after it, the steps slow down and add up to the
  length); the upgrade of a server's old four-crate `crates.yml` and `shards.yml` (`TierUpgradeTest`: unedited files
  become the shipped ones, edits and old ids are kept); the weighted draw against the shipped basic crate with a chi-square
  test over 200,000 draws per seed, boundaries and bad weights; shown chances adding up to exactly 100% for 2,000
  random tables; keys against a real SQLite database and ledger (give and take, persistence across a restart,
  references applied once even across a restart and under 16 racing threads, forgotten references, refusals, an
  opening's key, money, keys and log row in one transaction, a storage failure rolling the key back); settings
  parsing of the shipped file and every mistake reported at its exact path; the keyall clock (restarts, missed
  keyalls, reloads, countdown moments); who gets keyall keys (vanished and AFK players with every combination of
  `include-vanished` and `include-afk`); inventory fitting; expected values; the lang file against the design system;
  command rewards stored with the opening, run once it is stored and deleted, kept for the next start when the
  scheduler stopped or refused them (`RewardCommandsTest`).
- End-to-end (`tools/e2e`, `CratesScenarios`; the test crates are added with the animation off, except in the
  animation and block effect scenarios): `crates-open` (the list of buttons in tier order with their colours and
  tooltips, a crate's page, result, Open another, Back, refusal, main menu entry), `crates-tiers` (every tier's
  preview with 10 to 20 rewards in colour and the next tier's key, and a key of every tier opened),
  `crates-animation` (the chest window with the reel, the rising ticks, the reveal in the rarity's colour and its
  sound, the reward reaching the inventory only at the reveal, a click skipping and a second closing, closing the
  window mid-roll, no second crate while one rolls, an announced win announced at the reveal with the big fanfare),
  `crates-animation-leave` (quitting mid-roll keeps the reward in the claim box until it is claimed; dying mid-roll
  keeps it there and drops nothing; the next opening works), `crates-block-effects` (the floating name with its
  text, non-persistent, never doubled; dust particles near the block; the spin above the block during an opening and
  gone after it; the name gone when the block stops being a crate), `crates-rewards` (money with the announcement and the ledger row, keys, a command, the Crate wins
  setting), `crates-claim-box`
  (full inventory, then claimed from `/ah claims`), `crates-double-submit` (a double click, a replayed click, command
  spam), `crates-preview` (chances, rarity, sell value, names in their rarity's colour, sorting, opening from the menu, back
  to the crate's page),
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
  stored row is deleted), `crates-settings` (the settings and their options in Crates & kits and Server
  announcements; the receipt above the hotbar and 3 keys per bulk open saved in the settings dialog, then the receipt
  above the hotbar, Open 3 more and the short batch receipt, a bulk opening a plugin cancels on its third key keeping
  the short receipt above the hotbar with the reason in chat; through the API: rarest wins only, sneak + right-click
  opening 3 keys from a crate block or showing the crate window, the keyall countdown in chat only while another player
  gets the hotbar count, and no unopened key reminder on join while the other player gets one).
- Unit: `CratePlayerSettingsTest` (groups and order, defaults, `crate-wins` legacy rows and config values, offering
  that follows `crates.yml`, options that fall back, the keyall countdown offering only the chat lines and action bar
  count the server shows, the win filter, the bulk amount, quick open, countdown and stop-reason deciders)
  and the setting texts in `CratesResourcesTest`.

Not verifiable without a real client: how the dialogs, the preview menu and the opening window look, the waiting
screen between Open and the window, how the floating names, particles and the spin look (the bot sees the entities,
their text and the particle packets, not the picture), the lid moving, and icons in the keyall line.
