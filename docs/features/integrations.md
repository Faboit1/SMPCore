# Integrations and admin tools (`integrations`)

SiftCore's hooks into optional plugins, its public API, and the admin tools under `/sift`: integration status,
database backups, CSV exports, the audit log, the permission and placeholder registries with their generated
reference pages, and store delivery. Package `feature/integrations` (feature, store, admin tools) plus
`integration/placeholderapi`, `integration/luckperms` and `integration/floodgate` (the only classes that touch those
plugins' APIs), public interfaces in `api`, config `features/integrations.yml`, text `lang/integrations.yml`, table
`store_deliveries` (V075).

SiftCore runs the same with none of the optional plugins installed. Each hook is connected in `enable()` only when its
plugin is enabled and its setting is on; the classes that use a plugin's API are loaded only after that check (the
plugin names are compile-time constants, so even the check loads nothing). `/sift reload` connects or disconnects a
hook when its setting changes. The Vault economy (VaultUnlocked, legacy and modern interfaces) belongs to the economy
feature (`integration/vault`); `/sift integrations` reports on it too.

The feature provides one contract and consumes four:

| Contract | Direction | Wired |
|---|---|---|
| `core.integration.Ranks` | provides, `IntegrationsFeature#ranks()` | for chat, the scoreboard and other features that show ranks; one object for the whole run, answering `Ranks.NONE` until LuckPerms is connected |
| `CrateKeys` | consumes | `crates.keys()` (store key delivery) |
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
| `%siftcore_rank%` | the rank label as plain text, empty for the default group or without LuckPerms |
| `%siftcore_rank_group%` | the primary LuckPerms group in lowercase, `default` without LuckPerms |
| `%siftcore_rank_color%` | the rank's colour as `#RRGGBB` (a gradient's first colour), empty without one |

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

Every tool is a `/sift` subcommand, works from the console, and runs anything slow off the server threads.

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
| `/sift store money <player> <amount> <ref>` | `siftcore.admin.store` | Store delivery of money (`1.5k`, `2m` work) |
| `/sift store shards <player> <amount> <ref>` | `siftcore.admin.store` | Store delivery of shards |
| `/sift store keys <player> <crate> <amount> <ref>` | `siftcore.admin.store` | Store delivery of virtual crate keys |
| `/sift store rank <player> <group> [duration] <ref>` | `siftcore.admin.store` | Store delivery of a LuckPerms group, for a time (`30d`, `12h`) or `permanent` (also when the duration is left out) |
| `/sift store revoke <ref> [reason]` | `siftcore.admin.store` | Takes a delivery back after a refund or chargeback (`reason` is one word, `refund` when left out); the reference stays used |
| `/sift store check <ref>` | `siftcore.admin.store` | What a reference delivered, to whom, when, by whom, and whether it is done, waiting or revoked (and why) |
| `/sift store history <player>` | `siftcore.admin.store` | A player's store deliveries, newest first |

All nodes default to operators. `/sift` itself needs `siftcore.admin` (admin feature).

Audit actions: `store.money`, `store.shards`, `store.keys`, `store.rank`, `store.failed` (every refused delivery
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

An online buyer is told "A store purchase was cancelled, so <what> was taken back." (with `store.notify-player`).
A chargeback usually also gets a temporary ban from the store's own command list.

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
| `store.announce` | false | Tell everyone |

## Self-test

`public API is registered`, `optional plugins are connected` (fails when an installed, enabled hook failed to start),
`no store delivery is left pending`, `backups folder is usable`, `dialogs map to Bedrock forms` (a pay-style form
becomes a custom form whose submit and close press the right buttons, a list a simple form, text renders) and
`registry docs render`.

## Integration notes

- Chat takes `integrations.ranks()` in `FeatureCatalog` (the integrations feature is constructed before it); the
  scoreboard and any later feature that shows ranks should do the same. The object never changes, so passing it at
  construction is enough.
- Store deliveries name crates by id; buyers see `3 legendary keys`.
- The feature id is `integrations`; its lang and config files are new, so an existing server gets them on the next
  start.
