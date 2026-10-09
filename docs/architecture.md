# SiftCore architecture

SiftCore is the single gameplay plugin of SiftVanilla. It runs on Canvas 26.2 (a Folia fork with region
threading) and is written so the same jar is correct on Paper and Folia. This document is the map: modules,
threading, the money engine, storage, UI, and the public API.

## Module map

```
net.siftvanilla.siftcore
├── SiftCorePlugin          entry point (JavaPlugin); delegates to SiftCore
├── SiftCoreBootstrap       Paper bootstrapper: registers the siftcore:hub dialog into the pause-screen
│                           and quick-actions dialog tags before registries freeze
├── SiftCore                composition root: builds services in dependency order, owns start/reload/stop
├── FeatureCatalog          constructs every feature with exactly what it needs (dependency order)
├── CoreControl             what /sift may do with the plugin (reload, self-test, metrics, debug)
│
├── api                     public, stable surface for other plugins
│   ├── economy             Currency, Flow, Posting, TransactionResult/Status, EconomyApi
│   └── event               SiftEvent/SiftCancellableEvent and one event per economic/combat action
│
├── core                    framework, no gameplay
│   ├── scheduler           Scheduler (global/region/entity/async) over Paper's region scheduler API
│   ├── config              ConfigReader (typed, validating), Configs (all-or-nothing reload), Setting<S>, YamlFiles
│   ├── money               MoneyFormat ($10, 1.5k parsing, compact display)
│   ├── text                Palette, TextStyle (the only MiniMessage), Lang (+LangFiles), MessageKey, Arg,
│   │                       Messenger (+Routing: feedback channel, alerts, quiet in combat), Sounds (per-player
│   │                       volume and kinds, pings), StatusBars (one boss bar per player), Icons, Channel, Feedback
│   ├── command             SiftCommand/SimpleCommand, CommandService (Brigadier lifecycle), CommandSupport,
│   │                       CommandSettings (commands.yml), Cooldowns
│   ├── player              PlayerDirectory (uuid/name/ip-hash, previous visit), PlayerSettings (Registry of
│   │                       Toggle/Choice/NumberSetting in SettingCategories, server Overrides), SharedSettings,
│   │                       options (AlertStyle, Audience, ConfirmAbove, Announce, PingSound), Limits, PlayerLifecycle
│   ├── permission          Permissions (runtime registration + docs)
│   ├── placeholder         Placeholders (feeds PlaceholderAPI, scoreboard, tab)
│   ├── teleport            Teleports (warmup, cancel on move/damage, combat refusal), CombatStatus
│   ├── combat              CombatTags (who is tagged until when)
│   ├── link                contracts between features: StatsRecorder, WorthLookup, TeamLookup, AfkStatus,
│   │                       CrateKeys, SpawnerItems, SpawnArea, MuteStatus, VanishStatus,
│   │                       FreezeStatus (teleports and dialogs refuse frozen players), FriendLookup, IgnoreLookup,
│   │                       Cosmetics, TextChecks; Relations (late-bound friends, teams, ignores for who-can settings);
│   │                       ServerBoosters
│   ├── integration         Ranks (LuckPerms labels)
│   ├── audit               AuditLog
│   └── selftest            SelfTest
│
├── storage                 Database, JdbcDatabase (ordered writer + group commit + read pool),
│                           SqliteSource/MysqlSource, Dialect, Migrations (auto-discovered V###.sql)
│
├── economy                 the money engine: Ledger, LedgerTx, CommittedTx, LedgerHooks, Deliveries (claim box),
│                           SystemAccounts (escrow), IdSequence
│
├── ui
│   ├── dialog              View model + Templates (notice/confirm/list/form), Dialogs (renderer + router),
│   │                       Input, Button, Body, FormValues, Submission, FormBridge (Bedrock)
│   ├── gui                 Menu, PagedMenu, Cycle, Items, MenuListener, MenuContext
│   └── hub                 HubRegistry, HubEntry
│
├── feature                 one package per gameplay feature (see docs/features/)
│   ├── economy  hub  admin  sell  shop  auction  orders  spawners  crates  kits  stats  teams  friends
│   └── combat  bounties  homes  tpa  rtp  spawn  chat  settings  afk  shards  scoreboard  cosmetics  extras  boosters
│
└── integration             vault, placeholderapi, luckperms, floodgate (loaded only when present)
```

Rules: layered (`feature` depends on `core`/`economy`/`ui`/`storage`, never the other way; features talk to each
other only through `core.link` interfaces), constructor injection everywhere, no static mutable state, one
responsibility per class.

## Threading model

Canvas ticks each region of the world on its own thread. SiftCore follows the verified rules in
`docs/research/runtime.md`:

| Work | Where it runs |
|------|---------------|
| Player state (inventory, menus, teleports, effects, `saveData`) | the player's region thread (`scheduler.entity`) |
| Blocks, chunks, spawners | the block's region thread (`scheduler.region`) |
| Game rules, world border, scoreboard structure, console command dispatch | the global region thread |
| Database, leaderboards, chat rendering, file I/O | async threads (`scheduler.async`, the database writer and readers) |
| Packets to a player (messages, action bars, dialogs, sounds) | any thread |

Nothing blocks a region thread: storage calls return futures, and continuations hop back to the right thread.
There are no world or entity scans on timers; features index what they need from events.

## The money engine

Every balance lives in memory (two longs per account) and is changed only by `Ledger.execute(LedgerTx)` under
one lock. A `LedgerTx` bundles:

- **postings**: `transfer` (nets to zero), `source` (creates currency) and `sink` (destroys it);
- **checks**: domain conditions evaluated under the lock, e.g. "listing still active";
- **applies**: in-memory domain changes with their undo;
- **writes**: domain SQL committed together with the ledger rows.

Execution is check → apply → enqueue one database unit (ledger rows, balance deltas, domain SQL) on the
single ordered writer. The result is known immediately; `committed()` completes after the group commit. If
storage fails, the transaction is reverted in memory, its future fails, and callers never hand out items.
After five storage failures the economy turns read-only until `/eco resume`.

Crash safety:

- **Remove before grant.** Items leave the source (an inventory, the claim box, spawner storage) before they
  are granted.
- **Grants go through the claim box.** Granted items are written into the claim box inside the transaction, or
  handed out only after the commit.
- **Idempotent claims.** A claim updates its row with `claimed IS NULL`, so one item can only be claimed once.
- **Player saves after item trades.** Players are saved right after trades that moved items, so a crash can't
  roll back an inventory while keeping the money.

Invariants, checked by `/sift selftest` and the unit tests:

- memory supply = stored balances = Σ ledger deltas = sources − sinks;
- transfers net to zero, overall and per transaction;
- no negative balance;
- every account equals its own ledger history;
- the orders escrow equals the open order value;
- the bounty escrow equals the active bounties.

## Database schema

SQLite by default (WAL, one writer connection, pinned reader connections), MariaDB/MySQL optional (HikariCP).
Migrations are `db/migrations/V###.sql`, auto-discovered, applied once each in a transaction, recorded in
`schema_version`.

| Table | Purpose |
|-------|---------|
| `players` | uuid, name, first/last seen, salted IP hash |
| `accounts` | (uuid, currency) → balance |
| `ledger` | append-only: tx_id, ts, currency, account, delta, balance_after, kind, flow, counterparty, ref, actor, note |
| `settings` | per-player toggles and preferences |
| `deliveries` | claim box: owner, source, ref, serialized item, created, claimed |
| `audit_log` | staff and sensitive actions |
| `auction_listings` | listings with serialized items, price, state, buyer, tax |
| `orders`, `order_fills` | buy orders and each delivery to them |
| `spawners`, `spawner_items` | stacked spawners and their stored drops |
| `teams`, `team_members` | teams, roles, homes, friendly fire |
| `homes` | player homes |
| `stats`, `kills`, `bounties` | lifetime counters, kill log for anti-farm, bounty contributions |
| `crate_keys`, `crate_log`, `kit_claims` | virtual keys, reward log, kit cooldowns |
| `ignores` | ignore lists |
| `player_cosmetics` | chat colours, nicknames, chat tags (and owned monthly tags), join and leave messages, kill effects |
| `friends`, `friend_requests`, `friend_profiles`, `friend_log` | friendships (two directed rows each), requests (pending, hidden, closed), stored rank limits, friends history |

Feature-specific additions live in each feature's migration range (see `docs/development.md`).

## UI

**Dialogs** are the main UI. Every screen is a `View` built from one of four templates:

| Template | Use |
|----------|-----|
| notice | information + one button |
| confirm | yes/no decision |
| list | several actions + back/close footer |
| form | inputs (text, toggle, single choice, number range) + submit/cancel |

Titles are plain text. Buttons are plain labels. Bodies are white/gray, with icons only where they add meaning.

The `Dialogs` router secures every click:

- Each shown view gets a random token, and its buttons send `siftcore:ui/<token>/<button>`.
- A click is accepted only for a live, unconsumed token of the same player. The token is consumed, so double
  clicks and replayed packets do nothing.
- Every input is re-validated against its own definition (length, options, range, step), and handlers run on
  the player's thread.
- Invalid input re-opens the dialog with the typed values kept.
- Dialogs wait for the server's response, so the client can't submit twice.
- Static routes (`siftcore:hub/<id>`) serve the pause-screen dialog registered by the bootstrapper.

Bedrock players (when Floodgate is installed) get the same `View`s as Cumulus forms through `FormBridge`.

**Chest GUIs** are used only for grids (auction house, sell, order delivery, spawner storage, crate preview). They
share one framework (`Menu`/`PagedMenu`) with one layout:

- rows 1-5: entries;
- bottom row: previous page, back, sort, filter, search, three extra buttons, next page.

Every click is cancelled and routed; double-click gathering and shift-moves into button slots are blocked; a
click-rate limiter and a per-menu busy lock stop spam.

**Text** comes only from lang files. Messages declare their placeholders and channel; the loader rejects
anything outside the design system (bold, gradients, other colours, undeclared placeholders, unknown icons).
Player text is always inserted literally.

## Public API and events

`SiftCoreApi` (ServicesManager) exposes `EconomyApi` (balances, deposit/withdraw/transfer, format, top,
history) and read-only views. Every economic and combat action fires a cancellable event before anything
changes:

- `EconomyTransactionEvent` (every transaction, both currencies) and `EconomyTransactionCommittedEvent` (after
  storage);
- `PlayerPayEvent`, `ItemSellEvent`, `ShopPurchaseEvent`;
- `AuctionListEvent`, `AuctionPurchaseEvent`;
- `OrderCreateEvent`, `OrderFillEvent`, `OrderCancelEvent`;
- `SpawnerPlaceEvent`, `SpawnerBreakEvent`, `SpawnerStackEvent`;
- `BountyPlaceEvent`, `BountyClaimEvent`;
- `CombatTagEvent`, `CombatLogEvent`, `PlayerKillCreditEvent`;
- `CrateOpenEvent`, `KeyallEvent`;
- `TeamCreateEvent`, `TeamJoinEvent`, `TeamLeaveEvent`, `TeamDisbandEvent`;
- `FriendRequestEvent`, `FriendAddEvent`, `FriendRemoveEvent`.

Events fired from a world thread are synchronous; others are asynchronous (`isAsynchronous()` tells which).

A PlaceholderAPI expansion (`%siftcore_<name>%`) is registered when PlaceholderAPI is present.

### Vault

When VaultUnlocked (plugin name `Vault`) is installed, the economy feature registers SiftCore's money as the
server's Vault economy (`integration/vault`). It registers both interfaces at the highest priority, because
VaultUnlocked does not bridge them: the classic `net.milkbowl.vault.economy.Economy`, which most shop and auction
plugins use, and the modern `net.milkbowl.vault2.economy.Economy`. `/vault-info` lists SiftCore for both, and
`/sift selftest` fails if another economy plugin sits above it.

- **Whole dollars.** `fractionalDigits()` is 0. Every rounding favours the server: money paid to a player rounds
  down and money taken rounds up, so rounding can never create money. Amounts are first rounded to six decimals,
  so floating-point noise such as 10.000000000000002 is charged as $10, not $11. An amount that rounds to nothing
  succeeds without a ledger row. Negative, NaN, infinite or out-of-range amounts fail.
- **One ledger transaction per call.** Each call is a ledger transaction (source or sink), applied in memory at
  once and stored write-behind. The ledger kind is `vault_<plugin>`, for example `vault_axauctions`, and the actor
  is `vault:<Plugin>`. The modern interface names its caller. For the classic interface, the plugin is found on
  the call stack. `/eco history` therefore shows which plugin moved money, and stats can count a plugin's payouts
  as earnings (`money-earned.kinds` in `features/stats.yml` includes `vault_axauctions`).
- **Accounts are implicit.** Every player who joined has one, and so does any account holding money.
  `createPlayerAccount` always succeeds. Worlds are ignored. Banks, shared accounts and other currencies are
  refused (`NOT_IMPLEMENTED` or a failure with a reason).
- **Threads.** Any thread may call it: balances come from memory and never block on the database.
