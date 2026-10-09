# Integrations and admin tools (`integrations`)

SiftCore's hooks into optional plugins, its public API, and the admin tools under `/sift`: integration status,
database backups, CSV exports, the audit log, the permission and placeholder registries with their generated
reference pages, store delivery (including server sell boosters), `/purchases`, and joining a full server for rank
holders. Package `feature/integrations` (feature, store, admin tools) plus
`integration/placeholderapi`, `integration/luckperms` and `integration/floodgate` (the only classes that touch those
plugins' APIs), public interfaces in `api`, config `features/integrations.yml`, text `lang/integrations.yml`, table
`store_deliveries` (V075).

SiftCore runs the same with none of the optional plugins installed. Each hook is connected in `enable()` only when its
plugin is enabled and its setting is on; the classes that use a plugin's API are loaded only after that check (the
plugin names are compile-time constants, so even the check loads nothing). `/sift reload` connects or disconnects a
hook when its setting changes. The Vault economy (VaultUnlocked, legacy and modern interfaces) belongs to the economy
feature (`integration/vault`); `/sift integrations` reports on it too.

The feature provides one contract and consumes five:

| Contract | Direction | Wired |
|---|---|---|
| `core.integration.Ranks` | provides, `IntegrationsFeature#ranks()` | for chat, the scoreboard and other features that show ranks; one object for the whole run, answering `Ranks.NONE` until LuckPerms is connected |
| `CrateKeys` | consumes | `crates.keys()` (store key delivery) |
| `ServerBoosters` | consumes | `boosters.boosters()` (store booster delivery and revokes, `/purchases` booster states; see `docs/features/boosters.md`) |
| `EconomyApi` | consumes | `economy.economy()` (the public API) |
| `CombatTags` | consumes | the shared combat tags (the public API's read-only combat view) |
| `AdminFeature` | consumes | adds its `/sift` subcommands with `addPart` |

## PlaceholderAPI

`SiftCoreExpansion` (identifier `siftcore`, `persist() = true` so `/papi reload` keeps it) answers
`%siftcore_<name>%` from SiftCore's placeholder registry (`core.placeholder.Placeholders`), the same values the
scoreboard uses. PlaceholderAPI calls expansions on whatever thread asks (verified on Canvas: main, region and async
threads); the registry's resolvers only read thread-safe caches. Unknown names answer null (PlaceholderAPI leaves the
text alone); a resolver that fails (for example one that needs a player, asked without one) answers null and is logged
once per name. `/papi info siftcore` lists every placeholder. The full list is in `docs/placeholders.md`.

This feature adds three placeholders:

| Placeholder | Shows |
|---|---|
| `%siftcore_rank%` | the rank label as plain text, empty for the default group, without LuckPerms or with Show my rank off |
| `%siftcore_rank_group%` | the primary LuckPerms group in lowercase, `default` without LuckPerms or with Show my rank off |
| `%siftcore_rank_color%` | the rank's colour as `#RRGGBB` (a gradient's first colour), empty without one or with Show my rank off |

## LuckPerms

**Rank labels** (`Ranks`): a player's label is their `siftcore-rank` meta value (`luckperms.label-meta`; usually set
on a group: `/lp group baron meta set siftcore-rank Baron`), otherwise their primary group's display name, otherwise
the group name with a capital letter (`baron` becomes `Baron`). Groups in `luckperms.hidden-groups` (shipped:
`default`) show no label unless the meta value is set. Every legacy colour code (`&6`, `§l`, `&#ffaa00`,
`&x&f&f&a&a&0&0`, `{#ffaa00}`) and MiniMessage tag (`<gold>`, `<#ffaa00>`, `<gradient:...>`) is removed from the
label (`RankText.plain`), so `label(uuid)` is always clean text.

**Rank colours**: `component(player)` is the label in the rank's own colour, taken from meta values set on the group
rather than from whatever the prefix contains: `siftcore-rank-gradient` (`luckperms.gradient-meta`, two or more
colours such as `#FF6AD5:#B26BFF`, one colour per letter) wins over `siftcore-rank-color` (`luckperms.color-meta`,
`#RRGGBB` or a colour name); without either it is the secondary colour. The live tiers set them like this:

```
lp group prospector meta set siftcore-rank-color #5FA8FF
lp group baron meta set siftcore-rank-color #FFAA00
lp group tycoon meta set siftcore-rank-color #FF6AD5
lp group tycoon meta set siftcore-rank-gradient #FF6AD5:#B26BFF
```

Chat currently shows the plain label (`Arg.text`); to show the coloured rank it can use `ranks.component(player)`.

Labels are cached per player and dropped whenever LuckPerms recalculates that player (`UserDataRecalculateEvent`),
unloads them, or recalculates any group, so a lookup is a map read and safe from any thread; a reload clears the
cache too. LuckPerms only holds online players, so offline players have no label and the group `default`.

**Store ranks** use the same hook (see Store delivery). Without LuckPerms, rank deliveries are refused cleanly
("LuckPerms is not installed, so ranks can't be granted") and nothing is recorded.

### Show my rank

`show-my-rank` (toggle, on by default) is a Privacy setting (5th in the group, after `hide-coordinates`,
`seen-privacy`, `balance-privacy` and `order-announce-mine`) for players with `siftcore.settings.hide-rank`
(nobody by default: give it to the rank groups, `/lp group prospector permission set siftcore.settings.hide-rank`).
It is offered while LuckPerms is connected (rank labels come from it), is never a placeholder (`placeholder(false)`)
and is read every time a rank is shown, so a change applies at once. While it is not offered (LuckPerms missing, turned
off with `luckperms.enabled: false` and `/sift reload`, or failing to connect) a choice stored earlier is kept but
applies nowhere: chat and the placeholders have no rank to hide, and the scoreboard, which can still draw ranks from
`group.<name>` permissions and its own labels, reads the switch only while it is offered (`Boards.RankPrivacy`), so
nobody is left hidden without a switch to undo it. It applies again once LuckPerms is back.

Off hides the player's rank tag:

| Where | How |
|---|---|
| Public chat (`<rank> <tag> <name>`), chat cards, rank join and leave lines, friend profiles, `RankView#label` of the public API | `ranks().label(uuid)` and `component(player)` are empty for them (`SwitchableRanks`), so every feature that shows ranks through the shared `Ranks` follows without code of its own |
| `%siftcore_rank%`, `%siftcore_rank_group%`, `%siftcore_rank_color%` | empty, `default` and empty: a tab list plugin such as TAB that builds names from these shows them as an ordinary member |
| Tab list names and order, nametags, the sidebar's `{rank}` (SiftCore's scoreboard) | the scoreboard shows them exactly like a `default` member (see scoreboard.md) |

It is cosmetic only: `ranks().group(uuid)` and `RankView#group` stay the real group, and perks, friend limits, store
ranks and permissions read LuckPerms directly. Without the node a player reads the default, so a player who loses
the node shows their rank again. Offline players have no label anyway (LuckPerms only holds online players). The
label friends see on the profile of a friend who is offline (or vanished) is the one the friends feature stored while
they were online (`RankLimits`): it is written at join, every 60 seconds, at once when the switch changes (the
friends feature's `SettingChangeEvent` listener) and again at quit, so turning the switch off never leaves the old
label on that profile (see friends.md).

Not covered: TAB (or another plugin) configured with LuckPerms' own placeholders (`%luckperms_prefix%`) shows the
rank anyway; point it at `%siftcore_rank%` for the switch to work there. The TAB setup used on the live server
(`plugins/TAB/groups.yml`: `%luckperms-prefix%` for everyone, fixed nametag prefixes for prospector, baron and
tycoon) is such a setup, so there the switch hides the rank in chat, on profiles and in the placeholders, but not in
TAB's tab list and nametags.

**Cosmetic chat tags stay.** The chat tag from `/tags` (cosmetics) is not a rank: it is a perk the player picked and
can remove themselves at any time (`/tags`, "No tag"). The setting's text names the rank tag, so hiding a purchased
tag as a side effect would surprise players; `show-my-rank` leaves `/tags` alone.

Tests: `ShowMyRankTest` (group, order, permission, no placeholder, offered only while LuckPerms is connected; the
label hidden, the group kept, the placeholder group `default`) and the `show-my-rank` e2e scenario with LuckPerms: a
player in a weighted `Knight` group with the node sees the switch on in the Privacy page, turns it off there, and at
once chat, the three placeholders, the public API and the scoreboard's tab name show no rank while `ranks().group`
stays the group (on a server where the scoreboard leaves the tab list to TAB, as live, the tab name checks are
skipped: TAB builds those names); without the node the rank shows again and the choice is kept; with it again the
choice applies; with `luckperms.enabled: false` and `/sift reload` the switch is not offered and the scoreboard shows
the rank it reads from `group.prospector` again (the stored choice is kept, not applied), and once the hook is back
the choice hides the rank again everywhere; on again deletes the row. `ShowMyRankTest` also checks that the
descriptions of all three rank placeholders (the registry text behind `/sift placeholders` and the generated
docs/placeholders.md) say they read as unranked with Show my rank off.

## Bedrock players (Floodgate)

When Floodgate (plugin name `floodgate`) is installed, `FloodgateForms` is installed as the dialog router's Bedrock
bridge (`services.dialogs().bedrock(...)`), so a Bedrock player gets a Cumulus form wherever a Java player gets a
dialog, with the same buttons and inputs. `FormPlan` (pure, unit-tested) decides the form; the bridge only renders it
and turns the answer back into the click the router expects, which then validates it exactly like a Java dialog
click (one-shot session token, every input checked against the view, handler on the player's thread).

| Dialog | Bedrock form | Answers |
|---|---|---|
| Notice | Modal form: its button, plus Close | the button presses it; Close and closing do nothing (like Escape) |
| Confirmation | Modal form: yes, no | each button presses its dialog button |
| List | Simple form: the list buttons, then the footer (Back or Close) | each button presses its dialog button; closing does nothing |
| Form (or any view with inputs) | Custom form: the body as a label, then text → input, toggle → toggle, choice → dropdown, range → slider | submitting presses the first button with the values; closing presses the second (the form's Back or Cancel) with the values as shown, so Back still goes back |
| A view with inputs and more than submit and back | Custom form plus an "Action" dropdown of every button | submitting presses the picked button; closing does nothing |

Text keeps the palette as the nearest legacy colours (white `§f`, gray `§7`, money green `§a`); sprite icons are left
out; item names in dialog bodies are rendered with the server's translations (or turned into words from the
translation key). Chest GUIs need nothing: Geyser shows them natively.

Floodgate is not installed on the test or live server; the bridge is compile-verified against the Floodgate 2.2.5 API
and Cumulus 1.1.2 (which Floodgate bundles unrelocated). The mapping and the answer path are verified end to end by the
`bedrock-forms` scenario, which installs a stand-in bridge built on the same `FormPlan` for one bot and walks the menu,
the money page, Back, the pay form, its confirmation and an invalid amount; the form rendering on a real Bedrock
client could not be checked here.

## Public API

`api.SiftCoreApi` is registered in Bukkit's `ServicesManager` at enable (priority Normal) and unregistered at disable:
`SiftCoreApi.get()` returns it. See `docs/api.md` for usage and every event.

## Commands and permissions

Every tool is a `/sift` subcommand, works from the console, and runs anything slow off the server threads. Store
deliveries and revokes are console only (the web store's console commands, RCON or staff at the server console): they
don't exist for players, command blocks or entities at all, whatever their permissions, so nobody in game can give
out a purchase under any name and reference. The store lookups (`check`, `history`) stay open to staff in game.

| Command | Permission | What it does |
|---|---|---|
| `/sift integrations` | `siftcore.admin.integrations` | Each integration's state (active, not installed, turned off, failed, or overridden by another economy plugin), the public API, store references delivered and pending, and backups |
| `/sift backup` | `siftcore.admin.backup` | Backs the database up now (SQLite); for MySQL/MariaDB it tells you to use mysqldump |
| `/sift backup list` | `siftcore.admin.backup` | The backups on disk with size and age, and when the next automatic one runs |
| `/sift export [days]` | `siftcore.admin.export` | Writes `balances-<time>.csv` and `ledger-<time>.csv` to `plugins/SiftCore/exports` (the ledger of the last `days` days, or all of it) |
| `/sift audit [action] [player] [page]` | `siftcore.admin.audit` | The audit log, newest first; `action` is a prefix (`store`, `store.money`, `eco.`), `any` for all; `player` is a name or any target id, `any` for all. Hover a time for the exact UTC time; "Older entries" is clickable |
| `/sift permissions [filter] [page]` | `siftcore.admin.registry` | Every declared permission with its description and default (filter by text, `any` for all); the console gets the whole list |
| `/sift placeholders [filter] [page]` | `siftcore.admin.registry` | Every placeholder with its description and your current value |
| `/sift docs` | `siftcore.admin.registry` | Writes `permissions.md` and `placeholders.md` to `plugins/SiftCore/docs` |
| `/sift store money <player> <amount> <ref>` | `siftcore.admin.store`, console only | Store delivery of money (`1.5k`, `2m` work) |
| `/sift store shards <player> <amount> <ref>` | `siftcore.admin.store`, console only | Store delivery of shards |
| `/sift store keys <player> <crate> <amount> <ref>` | `siftcore.admin.store`, console only | Delivery of virtual crate keys, for staff and events (keys are never sold, see [monetization](../monetization.md)). The texts word the keys like the crates do, with the crate's name (`1 Basic key`, `3 Basic keys`) |
| `/sift store rank <player> <group> [duration] <ref>` | `siftcore.admin.store`, console only | Store delivery of a LuckPerms group, for a time (`30d`, `12h`) or `permanent` (also when the duration is left out) |
| `/sift store booster <player\|uuid\|console> sell <percent> <length> <ref>` | `siftcore.admin.store`, console only | Store delivery of a server-wide sell booster (`console` for one from the server itself, such as a community goal) |
| `/sift store revoke <ref> [reason]` | `siftcore.admin.store`, console only | Takes a delivery back after a refund or chargeback (`reason` is one word, `refund` when left out); the reference stays used |
| `/sift store check <ref>` | `siftcore.admin.store` | What a reference delivered, to whom, when, by whom, and whether it is done, waiting or revoked (and why) |
| `/sift store history <player>` | `siftcore.admin.store` | A player's store deliveries, newest first |
| `/purchases` (`/mypurchases`) | `siftcore.command.purchases` (everyone) | Your own store purchases, read only (see below) |
| `/purchases <player\|uuid>` | `siftcore.admin.store` | Anyone's purchases (a dialog in game, chat lines from the console) |

All nodes except `siftcore.command.purchases` default to operators. `/sift` itself needs `siftcore.admin` (admin
feature). Two more nodes are read from LuckPerms at login (see Joining a full server): `siftcore.join.full` (a rank
perk, Baron and up) and `siftcore.join.full.staff`. They default to nobody, operators included: only a LuckPerms
grant counts.

Audit actions: `store.money`, `store.shards`, `store.keys`, `store.rank`, `store.booster`, `store.failed` (every refused delivery
with its reason), `store.resumed` (an interrupted rank delivery finished at startup), `store.revoke` (with the reason
and what was taken back), `store.revoke.failed`, `store.revoke.resumed`, `admin.backup`, `admin.export`, `admin.docs`.

Command output that reports a failure (not delivered, backup or export failed, an unknown reference) carries the
error feedback, so it shows in the error colours with the error sound like every other error.

## Store delivery

A web store (Tebex, CraftingStore and the like) runs the store commands from the console after a purchase, for
example:

```
sift store rank {uuid} prospector 30d tebex-{transaction}-{packageId}
sift store rank {uuid} tycoon permanent tebex-{transaction}-{packageId}
sift store money {uuid} 250k tebex-{transaction}-{packageId}
sift store booster {uuid} sell 10 30m tebex-{transaction}-{packageId}
sift store booster console sell 15 48h goal-2026-10
sift store revoke tebex-{transaction}-{packageId} refund
```

`<player>` is a name of anyone who joined before, or an account id (UUID with dashes) to deliver before the buyer ever
joined. `<ref>` is the store's order or transaction id: 1 to 48 letters, digits, `.`, `-`, `+` or `_`. **Every
reference is delivered at most once**, whatever the store retries, even across restarts and crashes:

- **Money and shards**: one ledger transaction pays (kind `store`, the reference on the ledger row) and inserts the
  `store_deliveries` row; the reference check runs under the economy lock, so concurrent duplicates can't both pass
  (unit test: 64 concurrent deliveries of one reference pay once).
- **Keys**: the crates feature grants them with its own reference `store:<ref>` atomically (and refuses that reference
  a second time for 90 days); the delivery is recorded once the grant committed. If the server dies between the two,
  the next attempt finds the crate grant reference, records the delivery and reports it as already delivered, without
  giving keys again.
- **Ranks**: LuckPerms is outside SiftCore's database, so the delivery is first recorded as pending with the rank's
  end worked out (a timed rank adds its duration to the player's current timed grant of that group, or to now; a
  permanent holder stays permanent; ends are whole seconds like LuckPerms stores them), then LuckPerms is told to
  make the player hold the group at least that long (idempotent: a shorter timed grant of that group is replaced,
  anything as long or longer is left alone), then the delivery is marked done. A pending delivery (crash, or LuckPerms
  failing) is finished on the next start, or by the same command again; finishing never adds time twice. Rank grants
  of one player run one at a time, so two 30-day purchases always give 60 days.
- **Boosters**: one silent ledger transaction holds the reference check, the booster (it starts, or waits in line
  behind the running one) and the `store_deliveries` row (kind `booster`, item the booster kind `sell`, amount the
  percent as bought, duration in seconds; the buyer is the nil UUID for `console`). Only the hard limits refuse a
  purchase: the percent must be 1 to 50 and the length 1 minute to 30 days (a mistyped command); anything else is
  refused and records nothing. The limits in `features/boosters.yml` are for staff boosters: a store booster above
  `sell.max-percent` (a package made before the owner lowered it) is delivered all the same, because the buyer paid
  and the store won't retry, and pays the limit while it is lower; the console reply adds "It was bought for +15% but
  pays +10% while sell.max-percent in boosters.yml is lower." and the log gets a warning. A store booster is never
  refused for a long line either (only staff boosters are limited by `queue.staff-limit`). Everyone online is told by
  the boosters feature, once the delivery is stored ("Alex started a +10% sell booster for 30 minutes. Thank you!" or
  that it waits in line); the buyer, when online, is told that it runs now or which number in line it is. Every
  percent players see is the one it pays. Details: `docs/features/boosters.md`.

Before anything is applied, `StoreDeliveryEvent` (cancellable) is fired; a cancelled delivery records nothing, so the
same reference can be delivered later. Refusals (all recorded as `store.failed`): reference malformed, amount zero or
over the limits (`store.max-money`, `max-shards`, `max-keys`; a guard against mistyped commands), unknown crate, group
not in `store.rank-groups` (shipped: prospector, baron, tycoon; staff groups can never be bought), group missing
in LuckPerms, duration outside `min-rank-duration`..`max-rank-duration`, LuckPerms not installed, balance limit,
economy read-only.

The sender gets "Delivered $25,000 to Alex (ref tbx-1)." or "Already delivered ref tbx-1 gave $25,000 to Alex 3m ago,
nothing changed." or "Not delivered: <reason>". An online buyer is told in chat ("Your store purchase arrived: ...",
`store.notify-player`); with `store.announce` everyone online is told too (shipped off).

### Refunds and chargebacks

`/sift store revoke <ref> [reason]` takes a delivery back and keeps the reference recorded as revoked, so the store
can never deliver it again (a later delivery with it answers "was revoked, so it can't be delivered again"). It is
idempotent and crash-safe like delivery:

- **Money and shards**: one ledger transaction (kind `store_revoke`) takes back as much of the amount as the player
  still has and marks the reference revoked; the note records "took back 15000 of 25000" when they had spent some.
- **Ranks**: a timed purchase takes its time off the player's current grant of that group (60 days left and a 30-day
  purchase revoked leaves 30 days; when nothing is left the grant is removed), a permanent purchase removes the
  permanent grant, and time bought on top of a permanent grant leaves nothing to take. The plan is recorded as
  revoking before LuckPerms is changed and the reference is marked revoked afterwards; an interrupted revoke is
  finished on the next start or by the same command again, never taking time twice. A delivery still waiting for
  LuckPerms is finished first, so only time it really added is taken.
- **Keys**: the crates contract has no way to take keys, so the reference is marked revoked and the sender is told the
  `/crates take` command to run.
- **Boosters**: in the same transaction as the revoke, a running booster ends at once (the next one in line starts), a
  waiting one is taken out of line, and one that already ran has nothing left to take. The note records which
  ("refund, booster ended early", "booster taken out of line", "booster had already ended").

An online buyer is told "A store purchase was cancelled, so <what> was taken back." (with `store.notify-player`).
A chargeback usually also gets a temporary ban from the store's own command list.

## Purchases (`/purchases`)

A read-only dialog of the player's own store deliveries, newest first, six per page with Previous and Next: what it
gave (a rank and its length, a booster, money, shards or keys), the day (UTC) and how long ago, the reference
(shortened to its first 8 and last 7 characters when longer than 16) and what became of it: delivered, being
delivered (a rank waiting for LuckPerms), being taken back, taken back (with the reason), or for a booster whether it
runs now (time left) or waits (place in line). What it gave is the purchase as bought (a booster bought for +15%
says +15% even while `sell.max-percent` caps what it pays). The list is read from the store's in-memory book (up to 200 entries),
never from another player: `/purchases <player>` needs `siftcore.admin.store`, and a player without it who tries
gets their own page. Staff can pass a UUID for buyers who never joined. From the console it prints the same lines.
Bedrock players get the same list as a form (through the dialog-to-form bridge; not tried with a real Bedrock
client, see Unverified).

## Joining a full server

Players with `siftcore.join.full` (Baron and up) or `siftcore.join.full.staff` may join when the server is full.
Paper decides "full" before the player exists, in `PlayerServerFullCheckEvent` (verified on Paper/Canvas 26.2: it is
fired from `PlayerList.canBypassFullServerLogin`, twice per login), where Bukkit permissions are not available yet.
So the node is read from LuckPerms while the player logs in (`AsyncPlayerPreLoginEvent`, `MONITOR`, on the login
thread: the user LuckPerms loaded for that login, or `loadUser`, checked with LuckPerms' static query options, waiting
at most 3 seconds) and remembered for one minute; the full check then only reads that answer and calls
`allow(true)`. Without LuckPerms nobody gets past a full server this way. Only LuckPerms grants count: the
server's "operators have it by default" does not exist before join, so the nodes default to nobody and an operator
needs the node set in LuckPerms too. Vanilla's own bypass, an ops.json entry with `"bypassesPlayerLimit": true`, is
already part of the event's answer (Canvas 26.2 checks `ServerOpList.canBypassPlayerLimit` before firing it) and keeps
working. The player sees nothing special; the
console logs "Let Alex join the full server (20/20 online; siftcore.join.full)." once per login, naming the node that
let them in. `join-full.enabled: false` turns it off.

## Backups

`/sift backup` and the automatic backups (`backups.interval`, shipped every 24h; 0 turns them off) copy the SQLite
database to `plugins/SiftCore/backups/siftcore-<UTC time>.db` with `VACUUM INTO`:

1. An empty write is queued and waited for. The database applies writes strictly in order, so every write made before
   the backup started (every committed trade) is in the file.
2. The copy runs on its own connection inside one read transaction (WAL), so it is a consistent snapshot even while
   new trades keep committing, and nothing waits for it.
3. The new file must pass `PRAGMA quick_check`; only then are backups beyond the newest `backups.keep` (shipped 7)
   deleted.

The first automatic backup runs one interval after the newest backup on disk (at least 5 minutes after startup).
Failures are logged as errors. To restore: stop the server, copy the backup over `plugins/SiftCore/data/siftcore.db`,
delete `siftcore.db-wal` and `siftcore.db-shm` next to it, start the server.

A MySQL or MariaDB database lives on the database server; SiftCore makes no copies of it and `/sift backup` says to use
`mysqldump --single-transaction <database>` there.

## Exports

`/sift export [days]` waits for every earlier write, then writes two RFC 4180 CSV files (UTF-8, CRLF) under a
temporary name and renames them when complete:

- `balances-<time>.csv`: `account, name, type (player or system), currency, balance`, every non-zero balance, highest
  first per currency.
- `ledger-<time>.csv`: `id, transaction, time_utc, time_ms, currency, account, name, delta, balance_after, kind, flow,
  counterparty, counterparty_name, ref, actor, actor_name, note`, oldest first.

Text that starts with `=`, `+`, `-` or `@` (and is not a number) gets a leading apostrophe, so a player name or note
can't run as a formula in a spreadsheet. Escrow accounts are named (`orders escrow`, `bounty escrow`).

## Generated reference pages

`/sift docs` writes `docs/permissions.md` (every declared node grouped by area, with its default in words) and
`docs/placeholders.md` (every placeholder) from the live registries; the copies in the repository were generated on
the test server and are regenerated after integration.

## Config (`features/integrations.yml`)

| Key | Shipped | Meaning |
|---|---|---|
| `placeholderapi.enabled` | true | Register the expansion when PlaceholderAPI is installed |
| `luckperms.enabled` | true | Rank labels and store ranks from LuckPerms when it is installed |
| `luckperms.label-meta` | `siftcore-rank` | Meta key that sets a label |
| `luckperms.color-meta` | `siftcore-rank-color` | Meta key with the rank's colour |
| `luckperms.gradient-meta` | `siftcore-rank-gradient` | Meta key with the rank's gradient |
| `luckperms.hidden-groups` | `[default]` | Primary groups without a label |
| `floodgate.enabled` | true | Bedrock forms when Floodgate is installed |
| `backups.interval` | 24h | Automatic backups (0 off) |
| `backups.keep` | 7 | Backups kept (0 all) |
| `audit.page-size` | 10 | Lines per `/sift audit` page |
| `registries.page-size` | 20 | Lines per `/sift permissions`/`placeholders` page in game |
| `store.max-money`, `max-shards`, `max-keys` | 100m, 1,000,000, 1,000 | Most one command may give |
| `store.rank-groups` | prospector, baron, tycoon | Groups the store may grant (empty: any, not advised) |
| `store.min-rank-duration`, `max-rank-duration` | 1h, 3650d | Timed rank bounds |
| `store.notify-player` | true | Tell the buyer when it arrives |
| `store.announce` | false | Tell everyone (boosters are always announced by the boosters feature instead) |
| `join-full.enabled` | true | Let `siftcore.join.full` and `siftcore.join.full.staff` holders join a full server (needs LuckPerms) |

## Self-test

`public API is registered`, `optional plugins are connected` (fails when an installed, enabled hook failed to start),
`no store delivery is left pending`, `backups folder is usable`, `dialogs map to Bedrock forms` (a pay-style form
becomes a custom form whose submit and close press the right buttons, a list a simple form, text renders) and
`registry docs render`.

## Integration notes

- Chat takes `integrations.ranks()` in `FeatureCatalog` (the integrations feature is constructed before it); the
  scoreboard and any later feature that shows ranks should do the same. The object never changes, so passing it at
  construction is enough, and it applies Show my rank for them.
- Store deliveries name crates by id; buyers see `3 legendary keys`.
- The feature id is `integrations`; its lang and config files are new, so an existing server gets them on the next
  start.

## Unverified

- Bedrock: `/purchases` and the other dialogs reach Bedrock players as forms through the dialog-to-form bridge (the
  self-test `dialogs map to Bedrock forms` checks the mapping), but Floodgate is not installed on the test servers, so
  no real Bedrock client has seen them.
- Joining a full server is tested end to end with bots and LuckPerms grants; a real proxy-less join with a vanilla
  client behaves the same (the same event), but was not tried.
