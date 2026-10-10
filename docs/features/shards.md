# Shards (`shards`)

Shards are SiftCore's second currency (`Currency.SHARDS`). Players earn them in the AFK zone (see `afk.md`) and spend
them in the shard shop on crate keys and items. Package `feature/shards`, config `features/shards.yml`, text
`lang/shards.yml`, table `shard_purchases` (migration `V065`).

| Contract | Wired | Used for |
|---|---|---|
| `CrateKeys` | crates feature | Key offers: shown only for crates the crates feature knows, given with `CrateKeys#give`; the purchase dialog shows how many keys of that crate the player has |
| `AfkZoneInfo` | AFK feature | The shards page: what the zone pays this player, today's progress and the way there |
| `CombatStatus` | core combat tags | Combat-tagged players can't open the shop or buy (`shop.block-in-combat`) |

The balance placeholders (`shards`, `shards_raw`) and the shard side of `/eco` belong to the economy feature.

## Why shards never become money

Shards are created out of thin air for being online. If they could be exchanged for money, every AFK hour would print
money and push prices up for everyone who plays. So the shop sells perks (keys, items) for shards and nothing sells
shards or money for each other; the config has no money offer type at all.

Crates can pay shards and keys of other crates, so the key prices must stay well above what a key pays back in
shards. With the shipped crates (`/crates info <crate>` shows the averages) a key returns, counting the keys it gives
at their shop price, 17% (Common: 1.1 shards, 0.05 Uncommon and 0.01 Rare keys), 8% (Uncommon), 15% (Rare), 12%
(Epic), 14% (Legendary), 12% (Mythic) and 8% (Celestial) of what it costs in the shard shop, so buying keys never
makes shards. Keep it that way when changing prices or rewards.

Shards are purple everywhere (`colors.shards` in `config.yml`, `#915DFF`): every amount, every mention of the word in
the shop, the AFK zone, `/shards`, receipts and staff answers.

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/shards` (alias `/shard`) | everyone | Your shards, and where to earn and spend them |
| `/shards <player>` | everyone, console | Another player's shards |
| `/shards shop`, `/shardshop` (alias `/sshop`) | everyone | Opens the shard shop |
| `/shards give\|take\|set <player> <amount>` | `siftcore.admin.shards`, console | Changes a balance (one ledger transaction, kinds `admin_give`, `admin_take`, `admin_set`; audit log `shards.give` ...). An online player who is given shards is told |
| `/shards pending [retry]` | `siftcore.admin.shards`, console | Key purchases still waiting for their keys or refund; `retry` tries them now |

Amounts accept the usual shortcuts (`1.5k`). Store deliveries call these commands through the integrations feature.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.shards` | everyone | `/shards` |
| `siftcore.command.shards.others` | everyone | `/shards <player>` |
| `siftcore.command.shardshop` | everyone | The shard shop |
| `siftcore.admin.shards` | op | Staff changes and waiting purchases |
| an offer's `permission` | nobody | Only those players see and buy that offer |

## The shard shop

A dialog with one line, the balance, then a button per offer in `order`: `Common key, 50 shards` (a key's name in its
crate's colour, the price in purple), or `32x Bottle o' Enchanting, 40 shards` when one unit gives several. What the
offer is ("Opens the Common crate at spawn") and how many one purchase may take are in the button's tooltip. There
are no pages; the dialog scrolls. An offer opens a purchase dialog: the item (for item offers), the price, the
balance, the keys the player already has (key offers), an amount slider when more than one may be bought, and a Buy
button that always names the amount and total (what one unit gives is in its tooltip). Moving the slider
first shows the new total instead of buying. Purchases at or above `shop.confirm-above` (500) ask once more, unless
the player chose otherwise (`shard-confirm-above`, below). Receipts name what was given (`You bought 2x Common key for
100 shards.`, with a click to `/crates` for keys). After a purchase the dialog closes, or shows the shop again with the
new balance for players who keep it open (`shard-shop-stay-open`; not in combat).

**In combat** (`shop.block-in-combat`, on by default) the shop doesn't open and nothing can be bought: "You can't use
the shard shop in combat. <time> left." It is checked when the shop or an offer opens, on every Buy and confirm press,
and once more right before the transaction (the tag can start while the dialog is open), so no totem or golden apple
is bought mid-fight. `shardshop` is also in the combat feature's default `while-tagged.blocked-commands`.

When the player buys, everything is checked again: the offer still exists and is still available to them (permission,
the crate still exists, the item is real), the price is the one they saw, they have the shards. The cancellable
`ShardShopPurchaseEvent` fires, then one ledger transaction takes the shards (kind `shard_shop`, a unique ref) and
re-checks the offer under the economy lock (a reload changing the price or contents in between refuses the purchase).
Dialogs are one-shot, so a double click buys once.

**Items.** All of them go into the claim box (source `shard_shop`, the purchase's ref) in that same transaction, so the
shards and the items are stored together and a crash at any moment loses neither. Once stored, `ShardHandouts` claims
the stacks that fit into the inventory (marked claimed in storage first) and hands them over on the player's thread;
what doesn't fit, or can't be handed over (the player left, the inventory filled up meanwhile, the server is
stopping), stays in or goes back to the claim box. Never onto the ground. The player is told how many wait there.

**Crate keys** live in the crates feature's storage, so they can't join the shard transaction. The purchase is a saga
with a journal (`KeyGrants`):

1. One transaction takes the shards and writes the purchase to `shard_purchases` as `pending`. Both commit together or
   not at all.
2. After that commit, `CrateKeys#give` is called (on an async thread) with the purchase's ref. The crates feature gives
   keys for a ref at most once and answers `duplicate` for a second grant, so retrying is always safe.
3. Keys stored: the purchase becomes `done` and the player gets the receipt. Keys refused, the crates feature failing,
   or the keys' storage failing (which takes them back): a compensating transaction gives the shards back (kind
   `shard_refund`, same ref) and marks the purchase `refunded`, atomically, and the player is told.

Which purchases are pending is held in memory and changed only inside ledger transactions, under the economy lock, so
a purchase finishes exactly once: two attempts can never both refund, and an attempt never refunds keys that were
given. Purchases cut off by a crash, or whose refund could not be stored, are resumed at startup and every 5 minutes
(`/shards pending retry` does it at once). While the crates feature is not installed (`CrateKeys.NONE`), key offers are
hidden, and anything still pending is refunded. A crate removed from `features/crates.yml` hides its offers the same
way, and a player who already holds the most keys the crates feature allows gets the shards back.

## Player settings (AFK & shards group)

| Id | Kind | Default | What it does |
|---|---|---|---|
| `shard-confirm-above` | choice server/always/100/1000/5000/never | server | from which total a purchase asks once more: `Server default` follows `shop.confirm-above`, `Always` asks for every purchase, a preset asks from that many shards, `Never` buys at once |
| `shard-shop-stay-open` | toggle | off | go back to the shop after a purchase instead of closing it |

They follow the five AFK settings in the group ([afk](afk.md)). The decision is `ShardMath.needsConfirmation(total,
choice, shop.confirm-above)` (unit tested). Errors (not enough shards, a changed price, combat) behave the same with
either setting.

## The shards page

Main menu entry `shards` (order 85): the balance on one line, then two buttons: Shard shop and `Go to the AFK zone`,
whose tooltip says what the zone pays this player (their rank tier) and every how long, and what they earned there
today (of the daily limit, if any). Inside the zone the button is gone and the page says so instead: "You're in the
AFK zone now, earning shards: 1 every 1m" and today's total. While the zone is closed it says that.

## Config (`features/shards.yml`)

| Key | Default | Meaning |
|---|---|---|
| `shop.confirm-above` | `500` | Ask once more at or above this total (0 = always) |
| `shop.block-in-combat` | `true` | Combat-tagged players can't open the shop or buy |
| `shop.offers.<id>.type` | | `key` or `item` |
| `.crate`, `.keys` | | Key offers: the crate id, keys per unit (1 to 64, default 1) |
| `.item`, `.amount` | | Item offers: the item id, items per unit (1 to 64, default 1) |
| `.price` | | Shards per unit |
| `.max` | `1` | Most units per purchase (1 to 64) |
| `.name`, `.description` | empty | Plain text; an empty name means the item's own name or `<crate> key` (the crate's name as players know it, in its colour); the description is the button's tooltip |
| `.order` | `100` | Where the offer is listed (lowest first; offers with the same order keep their file order) |
| `.permission` | empty | Only players with it see and buy the offer |

The defaults sell a key of every crate tier, Common (50), Uncommon (110), Rare (200), Epic (600), Legendary (1,500),
Mythic (4,000) and Celestial (11,000), then 32 bottles o' enchanting (40), 8 golden apples (120), a totem of undying
(250) and a shulker box (300). The key prices follow what each key is worth ([crates](crates.md#the-seven-tiers-and-what-a-key-is-worth)):
an hour in the AFK zone (60 shards) buys about $1,060 of crate value in Common keys and up to $2,400 in the higher
tiers, so saving up pays. An offer with a mistake is left out and reported when the config loads.

## Self-test

Every item offer sells a real item and, with a crates feature installed, every key offer names a known crate; the
purchase arithmetic; the pending key purchases in memory match `shard_purchases` in storage.

## Tests

- Unit (`src/test/java/.../feature/shards`): the shipped shop, purchase arithmetic and text (`ShardsResourcesTest`),
  the key purchase saga (`KeyGrantsTest`), and the shard settings: group and order, the thresholds and how they
  combine with `shop.confirm-above` (`ShardSettingsTest`).
- End to end (`tools/e2e/.../AfkScenarios.java`): `shard-shop` (the balance line, a key of every tier in order in
  its crate's colour with a purple price, the tooltips, buying items and keys, refunds), `shard-shop-combat`, and
  `shard-settings` (Always confirm picked in the dialog, Never and Keep the shard shop open by API).
