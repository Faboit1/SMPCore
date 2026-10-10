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
├── SiftCore                composition root: builds services in dependency order, owns start/reload/stop, core
│                           self-tests, the economy and settings events
├── FeatureCatalog          constructs every feature with exactly what it needs (dependency order, late-bound links)
├── CoreControl             what /sift may do with the plugin (reload, self-test, metrics, debug, startup problems)
│
├── api                     public, stable surface for other plugins (docs/api.md)
│   ├── SiftCoreApi         the entry point (ServicesManager): economy(), combat(), placeholders(), ranks(), settings()
│   ├── CombatView, PlaceholderView, RankView, SettingsView   read-only views (settings can also be changed)
│   ├── economy             Currency, Flow, Posting, TransactionResult/Status, EconomyApi
│   └── event               SiftEvent/SiftCancellableEvent and 42 events (economy, trades, combat, social, settings)
│
├── core                    framework, no gameplay
│   ├── Feature, Services, CoreSettings (config.yml), CoreMessages (lang/core.yml)
│   ├── scheduler           Scheduler (global/region/entity/async) over Paper's region scheduler API, Task
│   ├── config              ConfigReader (typed, validating), Configs (all-or-nothing reload), Setting<S>, YamlFiles
│   │                       (new keys added; unedited shipped values, comments and retired keys follow the jar), Durations, ConfigProblem
│   ├── money               MoneyFormat ($10, 1.5k parsing, compact display), MoneyStyle (full, short, server)
│   ├── text                Palette, TextStyle (the only MiniMessage), Lang (+LangFiles), MessageKey, Arg,
│   │                       Messenger (+Routing: feedback channel, alerts, quiet in combat), ChatRepeats, Sounds
│   │                       (per-player volume and kinds, pings), StatusBars (one boss bar per player), Icons
│   │                       (+IconSettings), MoneyDisplay (each reader's money format), Channel, Feedback
│   ├── command             SiftCommand/SimpleCommand, CommandService (Brigadier lifecycle, yield-to, cooldowns on
│   │                       every node), CommandSupport (player arguments that hide vanished staff), CommandTrees,
│   │                       CommandSettings (commands.yml), Cooldowns
│   ├── player              PlayerDirectory (uuid/name/ip-hash, previous visit), PlayerLifecycle, Limits, and the
│   │                       settings model: PlayerSettings, Registry, Toggle/Choice/NumberSetting, SettingCategories,
│   │                       SharedSettings, Overrides (features/settings.yml), Change, SetResult, SettingTexts,
│   │                       SettingsCheck; options (AlertStyle, Audience, AutoAccept, ConfirmAbove, Announce, PingSound,
│   │                       Choices, OptionTexts)
│   ├── permission          Permissions (runtime registration + docs)
│   ├── placeholder         Placeholders (feeds PlaceholderAPI, the scoreboard, displays and the API)
│   ├── teleport            Teleports (warmup, cancel on move/damage, combat and freeze refusal), TeleportDisplay,
│   │                       TeleportMessages, CombatStatus
│   ├── combat              CombatTags (who is tagged until when)
│   ├── item                ContainerItems (shulker boxes and bundles), ItemCategories/ItemCategory (the item
│   │                       classifier of sell, orders and the auction house), ItemPatterns (`*` patterns)
│   ├── link                contracts between features: AfkStatus, Cosmetics, CrateKeys, FreezeStatus, FriendLookup,
│   │                       IgnoreLookup, MuteStatus, OrderMarket, ServerBoosters, SpawnArea, SpawnerItems,
│   │                       StatsRecorder, TeamLookup, TextChecks, VanishStatus, WorthLookup; Relations (late-bound
│   │                       friends, teams and ignore lists for every who-can setting)
│   ├── integration         Ranks (rank labels and groups; LuckPerms behind it)
│   ├── audit               AuditLog
│   └── selftest            SelfTest
│
├── storage                 Database, JdbcDatabase (ordered writer + group commit + read pool), ConnectionSource,
│                           SqliteSource/MysqlSource, Dialect, SqlWork, Migrations (auto-discovered V###.sql)
│
├── economy                 the money engine: Ledger, LedgerTx, CommittedTx, LedgerHooks, Deliveries (claim box),
│                           ClaimHandouts and Handoffs (hand claimed items over exactly once), SlotPlan,
│                           SystemAccounts (escrow), IdSequence
│
├── ui
│   ├── dialog              View + Templates (notice/confirm/list/form), Dialogs (renderer + router), DialogSessions,
│   │                       Input, Button, Body, FormValues, Submission, FormBridge (Bedrock)
│   ├── gui                 Menu, PagedMenu, MenuItem, Cycle, Items, MenuListener, MenuContext, ClickContext,
│   │                       GridBackup (a copy of items in an open grid inside the player's data)
│   └── hub                 HubRegistry, HubEntry
│
├── feature                 one package per gameplay feature, 30 in all (docs/features/<id>.md)
│   ├── economy  hub  admin  staff  spawn  afk  stats  teams  boosters  integrations  chat  cosmetics  friends
│   ├── sell  spawners  crates  orders  combat  settings  kits  shop  homes  rtp  tpa  extras  displays
│   └── scoreboard  bounties  shards  auction
│
└── integration             only these classes touch optional plugins' APIs, loaded only when the plugin is present
    ├── vault               VaultHook, VaultBridge, LegacyVaultEconomy, ModernVaultEconomy, VaultMoney, Callers
    ├── placeholderapi      SiftCoreExpansion (%siftcore_<name>% and %siftvanilla_<name>%)
    ├── luckperms           LuckPermsHook, RankText
    └── floodgate           FloodgateForms, FormPlan, BedrockText
```

Rules: layered (`feature` depends on `core`/`economy`/`ui`/`storage`, never the other way; features talk to each
other only through `core.link` interfaces or a small contract a feature exports, such as `AfkZoneInfo` from afk,
`WorldBorders` from spawn and `Pricing.Source`, `SellLink` and `ShopOffers` between sell and the shop), constructor
injection everywhere, no static mutable state, one responsibility per class.

### How the features are wired

`FeatureCatalog.create()` builds the features in dependency order and hands each one the contracts it needs. Where
two features need each other, the one built first gets a late-bound reference (an `AtomicReference` set once the
other is built) or a setter:

| Link | Built first | Bound after |
|---|---|---|
| `WorthLookup` (auction's low price warning) | auction | sell (`worth.set(sell.worth())`) |
| `CrateKeys` (store key delivery) | integrations | crates (`CrateKeys.late(...)`) |
| `Cosmetics` (names, tags and colours in chat) | chat | cosmetics (`Cosmetics.late(...)`) |
| `IgnoreLookup` (payment notices, team invites) | economy, teams | chat (`economy.ignores(...)`, `teams.ignores(...)`) |
| `Cosmetics` (fake join and leave lines) | staff | cosmetics (`staff.cosmetics(...)`) |
| `OrderMarket` (selling into buy orders) | sell | orders (`orderMarket.set(orders.market())`) |
| `ShopOffers` (shop prices in `/worth`) | sell | shop (`sell.shop(shop.offers())`) |
| homes' disabled worlds (team homes) | teams | homes (`teams.homeWorlds(...)`) |
| `FreezeStatus`, `VanishStatus` | core teleports, dialogs, commands | staff (`teleports().freezes`, `dialogs().freezes`, `commands().vanish`) |
| `Relations` (friends, teams, ignores) | every who-can setting | friends, teams, chat (`relations().bind(...)`) |

Every other link is passed straight to the constructor. Each feature page has a table of what it consumes and
provides, with the getter that wires it.

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
- **writes**: domain SQL committed together with the ledger rows;
- **after commit**: callbacks that may only run once the change is stored for good (telling players), never for a
  reverted transaction.

Execution is check → apply → enqueue one database unit (ledger rows, balance deltas, domain SQL) on the
single ordered writer. The result is known immediately; `committed()` completes after the group commit. If
storage fails, the transaction is reverted in memory, its future fails, and callers never hand out items.
A later transaction that already spent money from the failed one is taken back too: the writer stores an account's
debit only while the stored balance covers it (`UPDATE ... WHERE balance >= amount`), and only the ledger writes
`accounts`, so that condition fails exactly for money that was never stored. The unit fails, the transaction is
reverted like the one it relied on (logged as a warning, "was reverted too"), and so on down the chain; a credit, or a
debit the account could pay anyway, still stores. One storage error can therefore fail the transactions that depend
on it, and only those. Reverts run on two callback threads in no fixed order, so a balance may read negative for a
moment while a chain is taken back (credits are still accepted then; debits are refused). After five storage failures
(not counting these follow-on reverts) the economy turns read-only until `/eco resume`.

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

| Table | Migration | Purpose |
|-------|-----------|---------|
| `players` | V001 | uuid, name, first/last seen, salted IP hash |
| `accounts` | V001 | (uuid, currency) → balance |
| `ledger` | V001 | append-only: tx_id, ts, currency, account, delta, balance_after, kind, flow, counterparty, ref, actor, note |
| `settings` | V001 | per-player settings (one row per changed setting) and remembered UI state (sort orders) |
| `deliveries` | V001 | claim box: owner, source, ref, serialized item, created, claimed |
| `audit_log` | V001 | staff and sensitive actions |
| `auction_listings` | V002 | listings with serialized items, price, state, buyer, tax |
| `orders`, `order_fills`, `order_notices` | V003, V020-V023 | buy orders, each delivery to them, notices for owners who were offline |
| `spawners`, `spawner_items`, `spawner_xp` | V004, V030 | stacked spawners, their stored drops, XP waiting for its player |
| `teams`, `team_members` | V005, V035 | teams, roles, homes, friendly fire, the owner's remembered member limit |
| `homes` | V006 | player homes |
| `stats`, `kills`, `bounties` | V007, V045 | lifetime counters, kill log for anti-farm, bounty contributions |
| `crate_keys`, `crate_log`, `kit_claims` | V008 | virtual keys, reward log, kit cooldowns |
| `crate_grants`, `crate_blocks`, `crate_schedule`, `crate_commands` | V055, V056 | applied key grant references, crate blocks, the keyall schedule, command rewards waiting to run |
| `ignores` | V009 | ignore lists |
| `sell_mastery` | V015 | base value each player sold per sell category |
| `shop_recent` | V016 | each player's latest purchases (Buy again) |
| `shard_purchases` | V065 | crate keys bought in the shard shop |
| `store_deliveries` | V075 | store purchases, one row per reference (exactly-once delivery) |
| `boosters` | V076 | server-wide sell boosters and their queue |
| `staff_punishments`, `staff_reports`, `staff_vanish`, `staff_freeze` | V090 | punishments, reports, vanish and freeze |
| `displays` | V095 | positions of leaderboards and info boards placed in game |
| `friends`, `friend_requests`, `friend_profiles`, `friend_log` | V100 | friendships (two directed rows each), requests (pending, hidden, closed), stored rank limits and labels, friends history |
| `player_cosmetics` | V110, V111 | chat colours, nicknames (and their holds), chat tags (and owned monthly tags), join and leave messages, kill effects |

Feature-specific additions live in each feature's migration range (see `docs/development.md`).

## UI

**Dialogs** are the main UI. Every screen is a `View` built from one of four templates:

| Template | Use |
|----------|-----|
| notice | information + one button |
| confirm | yes/no decision |
| list | several actions + back/close footer (`column` and `grid`: many buttons in one or two columns, no paging) |
| form | inputs (text, toggle, single choice, number range) + submit/cancel (`number`: one slider with Done) |

Titles are plain text. A dialog shows buttons, not paragraphs: what a button does is in its tooltip, and the body only
holds what the player needs to decide (an amount, a name, a short status). Switches are buttons reading "Label: ON"
(green) or "Label: OFF" (red), choices "Label: value" (the value coloured); a click changes them at once and the page
shows again. Nothing is paged; the dialog scrolls. See "Dialog style" in `docs/development.md`.

The `Dialogs` router secures every click:

- Each shown view gets a random token, and its buttons send `siftcore:ui/<token>/<button>`.
- A click is accepted only for a live, unconsumed token of the same player. The token is consumed, so double
  clicks and replayed packets do nothing.
- Every input is re-validated against its own definition (length, options, range, step), and handlers run on
  the player's thread.
- Invalid input re-opens the dialog with the typed values kept.
- After a click the dialog stays on screen until the next one replaces it, so moving between screens is instant; a
  second click before the answer arrives hits the consumed token and is ignored. A click whose handler shows nothing
  closes the dialog after a short grace, or at once for a button marked `closes()` (finishing buttons). Searches and
  other slow work use `waits()`, which shows the client's waiting screen.
- Sessions of dialogs on screen (newest 8, for 15 minutes) and of dialogs embedded in chat (32, answered ones dropped
  first, for an hour: the longest a request they answer can last) are kept apart: browsing menus never expires a
  teleport request's answer in chat, and many requests never expire the dialog on screen. A click whose session is
  gone says the menu expired and closes every screen, the waiting screen included.
- Static routes (`siftcore:hub/<id>`) serve the pause-screen dialog registered by the bootstrapper.

Bedrock players (when Floodgate is installed) get the same `View`s as Cumulus forms through `FormBridge`.

**Chest GUIs** are used only where a grid of items is needed: the auction house, your listings and the claim box, the
shop (categories, pages, search), the sell menu, the price list and sell history, the buy orders browser, your orders,
your finished orders, your delivery history, delivering to an order and the item picker, spawner storage, the crate
preview, the trash bin and ender chest views of the perk commands, and the staff inventory inspection. They share
one framework (`Menu`/`PagedMenu`) with one layout:

- rows 1-5: entries;
- bottom row: previous page, back, sort, filter, search, three extra buttons, next page.

Every click is cancelled and routed; double-click gathering and shift-moves into button slots are blocked; a
click-rate limiter and a per-menu busy lock stop spam.

**Text** comes only from lang files. Messages declare their placeholders and channel. `TextStyle` is the only
MiniMessage setup for trusted text, and the loader checks every line against it: allowed are the palette tags
`<primary>`, `<secondary>`, `<money>`, `<error>`, `<shards>`, `<on>`, `<off>` and `<accent>`, named and hex colours (`<red>`, `<#3CC4EE>`, `<color:...>`),
`<bold>`, `<shadow:...>`, `<icon:name>` sprites, `<!italic>`, `<newline>`, `<reset>`, click, hover, key and
translation tags, and the message's own placeholders. Gradients, rainbow, obfuscated text, other decorations
(underline, strikethrough, turning italics on), undeclared placeholders and unknown icons are rejected: the entry keeps
the text the jar ships and the problem is listed. The shipped lang texts keep to the palette: white and gray text, money
green, shards purple, switch states green and red, other values in the accent colour, and the error red for warnings,
with no bold or other colours. The spawn boards SiftCore ships in `features/displays.yml`
go through the same check and do use the extra tags: bold titles and hex colours (gold, silver and bronze places,
coloured values). Player text is always inserted literally. The one exception to these rules is cosmetics: the chat
tag looks the owner writes in `features/cosmetics.yml` have their own parser that allows colours, gradients, rainbow,
decorations and shadows (no clicks or hovers), and players' chat colours and nicknames are built from checked colours,
never parsed (see `features/cosmetics.md`).

## Public API and events

`SiftCoreApi` (ServicesManager) exposes `EconomyApi` (balances, deposit/withdraw/transfer, format, top, history),
`CombatView`, `PlaceholderView`, `RankView` (read-only) and `SettingsView` (every player setting: read, change, reset).
Every economic, combat and social action fires an event, most of them cancellable before anything changes. The 42
events of `api.event`, by area (each with its thread and what cancelling does in `docs/api.md`):

- economy: `EconomyTransactionEvent` (every transaction, both currencies), `EconomyTransactionCommittedEvent` (after
  storage), `PlayerPayEvent`;
- selling and the shop: `ItemSellEvent`, `SellMasteryLevelEvent`, `ShopPurchaseEvent`, `ShardShopPurchaseEvent`,
  `SellBoosterEvent`, `StoreDeliveryEvent`;
- buy orders: `OrderCreateEvent`, `OrderEditEvent`, `OrderFillEvent`, `OrderCancelEvent`, `OrderEndEvent`,
  `OrderCollectEvent`;
- auction house: `AuctionListEvent`, `AuctionPurchaseEvent`;
- spawners: `SpawnerPlaceEvent`, `SpawnerStackEvent`, `SpawnerBreakEvent`, `SpawnerSellEvent`;
- crates and kits: `CrateOpenEvent`, `KeyallEvent`, `KitClaimEvent`;
- combat and bounties: `CombatTagEvent`, `CombatLogEvent`, `PlayerKillCreditEvent`, `BountyPlaceEvent`,
  `BountyClaimEvent`;
- AFK: `AfkStatusChangeEvent`, `AfkZoneRewardEvent`;
- teams: `TeamCreateEvent`, `TeamJoinEvent`, `TeamLeaveEvent`, `TeamDisbandEvent`;
- friends: `FriendRequestEvent`, `FriendAddEvent`, `FriendRemoveEvent`;
- chat and teleports: `PrivateMessageEvent`, `TeleportRequestEvent`, `RandomTeleportEvent`;
- settings: `SettingChangeEvent`.

Events fired from a world thread are synchronous; others are asynchronous (`isAsynchronous()` tells which).

A PlaceholderAPI expansion (`%siftcore_<name>%`, also answered as `%siftvanilla_<name>%`) is registered when
PlaceholderAPI is present.

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
