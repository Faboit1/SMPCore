# Building a SiftCore feature

This is the contract every feature follows. Read `docs/research/runtime.md` (threading on Canvas),
`docs/research/sprites.md` (icons) and `docs/research/api-reference.md` (exact API signatures) before writing code.
The economy feature (`feature/economy`) is the reference implementation: copy its shape.

## Layout

```
src/main/java/net/siftvanilla/siftcore/feature/<id>/
  <Name>Feature.java      implements core.Feature; constructor wires everything
  <Name>Settings.java     record + static parse(ConfigReader) for features/<id>.yml
  <Name>Messages.java     static final MessageKey fields for lang/<id>.yml
  ...                     services, menus, commands, listeners (one responsibility per class)
src/main/resources/features/<id>.yml   fully commented defaults (every key explained)
src/main/resources/lang/<id>.yml       every string; top-level key is <id>
src/main/resources/db/migrations/V0NN.sql  only if the feature needs schema changes (see ranges below)
src/test/java/net/siftvanilla/siftcore/feature/<id>/...  unit tests for pure logic
docs/features/<id>.md   commands, permissions, placeholders, config summary, design decisions
```

Register the feature in `FeatureCatalog.create()` (one line, in dependency order).

## Wiring (constructor injection, no static state)

- The feature constructor takes `Services services, List<ConfigProblem> problems` plus any other feature's
  service it depends on (for example `WorthLookup`, `StatsRecorder`, `TeamLookup`, `CrateKeys`, `AfkStatus`, `MuteStatus`, `VanishStatus`, `FriendLookup`, `IgnoreLookup`,
  `Cosmetics`, `TextChecks`, `CombatTags`). Interfaces for cross-feature contracts live in `core.link`; use them, never another feature's
  internals.
- In the constructor:
  - `services.configs().register("features/<id>.yml", Settings::parse, problems)` returns a `Setting<S>` holder.
    Always read `holder.get()` when you need a value so `/sift reload` takes effect.
  - `services.lang().register(XMessages.class)`.
  - `services.permissions().declare(node, description, everyoneByDefault)` for every node.
  - `services.settings().register(SettingCategories.X, setting, options)` for per-player settings: a `Toggle`, a
    `Choice` (build it with the shared vocabularies in `core.player.options.Choices`) or a `NumberSetting`, always in
    one of the shared groups of `SettingCategories`. Their MessageKeys must be static fields of your Messages class.
    Read settings with `settings().get(uuid, setting)` (or `get(player, setting)` to apply permissions). Settings
    several features read are in `core.player.SharedSettings`; call `settings().reads(SharedSettings.X)` where you act
    on one, and never import another feature's setting constants. Who-can settings ask `services.relations()`.
    Deliver notifications with `services.messenger().alert(player, style, key, args)` (quiet in combat and the
    player's choices apply); see `docs/features/settings.md`.
- In `enable()`: load state from storage (blocking `.get()` is fine here, it's startup), register listeners with
  `Bukkit.getPluginManager().registerEvents(listener, services.plugin())`, start timers with `services.scheduler()`,
  register the hub entry, placeholders and dialog routes.
- `commands()` returns `SiftCommand`s (use `SimpleCommand` and `CommandSupport`).
- `disable()` must flush everything **synchronously** (see shutdown below) and must not throw.
- `selfTest(SelfTest)` adds checks that `/sift selftest` runs (cheap, read-only, return null on pass).
- Static state is forbidden: no static mutable fields, no singletons. Static final constants and pure static
  helpers are fine.

## Threading (Folia/Canvas, strictly)

- Never use `Bukkit.getScheduler()` (it throws on Canvas). Use `services.scheduler()`:
  `entity(player, ...)` for anything touching a player/entity (inventory, health, opening menus, potion effects,
  `saveData`), `region(location, ...)` for blocks and chunks, `global(...)` for world settings (game rules, time,
  world border, scoreboard structure), `async(...)` for I/O and CPU work.
- Events run on the thread that owns the thing: player events on the player's region, block events on the block's
  region, `AsyncChatEvent` on an async thread, `AsyncPlayerPreLoginEvent` on an auth thread.
- Player commands run on the player's thread; console commands on the global thread.
- Safe from any thread: `sendMessage`, `sendActionBar`, `showDialog`, `closeDialog`, `playSound` to a player,
  `teleportAsync`, `Bukkit.getOnlinePlayers()`, `getPlayer(uuid)`. Not on that list: `showBossBar`/`hideBossBar`
  (CraftPlayer keeps a player's bars in a plain `HashSet`, checked with javap on Canvas 26.2): put lasting status
  lines on the shared per-player bar, `services.statusBars()` (`core.text.StatusBars`, an owner name and a priority),
  which changes each player's bar only on their thread.
- Only `teleportAsync`; sync `teleport` throws.
- `Bukkit.dispatchCommand` works only on the global thread and throws on parse errors (wrap in try/catch).
- No world/entity scans on timers. Track what you need from events (chunk load/unload, place/break).
- `onDisable` runs after the region scheduler stopped and players never get a quit event at shutdown: flush
  online players' data directly in `disable()` (core already records their last-seen time before features stop).
- Shared in-memory state must be thread-safe (`ConcurrentHashMap`, immutable snapshots, or the economy lock).

## Money, items and crash safety

- All money/shards changes go through `services.ledger().execute(LedgerTx)`. Never keep your own balances.
- A trade is ONE `LedgerTx`: postings (`transfer`, `source`, `sink`), `check(...)` suppliers that validate domain
  state under the economy lock, `apply(change, undo)` for in-memory domain changes, `write(sql)` for domain rows that
  must commit atomically with the ledger rows, and `afterCommit(callback)` for what may only happen once the change is
  stored for good (telling players about it: the callback runs on the database callback thread before `committed()`
  completes, and never for a reverted transaction). Results: `SUCCESS` (applied in memory, `committed()` completes
  after the database commit), or a failure status with nothing applied.
- Domain state that takes part in trades (listings, orders, spawner storage, bounties, keys) is held in memory,
  loaded at startup, and mutated only inside `apply` (under the economy lock).
- Remove before grant:
  - Taking items from a player (selling, listing, delivering to an order): remove them from the inventory on the
    player's thread first, then run the transaction. If it fails, give them back.
  - Giving items (purchases, claims): put them in the claim box inside the transaction
    (`services.deliveries().add(tx, ...)`), or wait for `result.committed()` before giving anything, then hop to
    the player's thread. Items that don't fit go to the claim box (`deliveries().give(...)`), never on the ground.
- After a trade moved items in or out of an inventory, if `services.core().get().savePlayerAfterTrade()`, call
  `player.saveData()` on the player's thread.
- Serialize items with `ItemStack#serializeAsBytes()` / `ItemStack.deserializeBytes(...)` (full data components).
- Click spam and double submits: menus use `runBusy`/`lock`; dialogs are one-shot by design; re-check everything
  inside the transaction (`check`), never trust the state you showed the player.
- Never trust client numbers: dialog inputs are re-validated by the router, but also validate meaning (ownership,
  balance, limits, cooldowns, listing still there, price unchanged) at execution time.
- Every economic action fires a cancellable event from `api.event` before the transaction (and the ledger fires
  `EconomyTransactionEvent` for every transaction). Events extend `SiftCancellableEvent` / `SiftEvent`.

## Text and design system

- Every player-facing string lives in `lang/<id>.yml` and is referenced by a `MessageKey`. The factory method
  picks the channel: `chat` (worth keeping), `notify` (chat + sound), `success`/`error`/`info` (action bar,
  transient), `title` (rare), `ui` (lore, dialog text, names, never sent alone).
- Allowed tags: `<primary>` (white), `<secondary>` (gray), `<money>` (#1AFF1A, money only), `<icon:name>`,
  `<!italic>`, `<newline>`, plus the message's declared placeholders. Bold, gradients, other colours, decorations
  and prefixes like "[Server] »" are rejected when lang loads.
- Placeholders are typed: `Arg.money` (renders `$1,500` in the money colour), `Arg.number` (white), `Arg.amount`,
  `Arg.decimal`, `Arg.time`, `Arg.text` (untrusted text, inserted literally), `Arg.component` (pre-built safe
  component). Never concatenate player text into MiniMessage.
- Wording: sentence case, short lines, no ALL CAPS, no emoji, no stray space before punctuation
  (`<primary>You got <amount>.`). State is told by words and sound, never colour.
- Icons: `<icon:name>` followed by a space; names come from `icons.yml` (already verified against the 26.2 atlases).
  Use sparingly: scoreboard/stat lines, dialog bodies, hover cards. Never in titles or buttons.
- Item names/lore via `ui.gui.Items` (italics off). GUI titles: plain `Component.text(...)` from lang with no tags.
- Money format is `$<amount>` (config `currency.format`); amounts parse `1.5k`, `2m` and reject non-whole results.

## UI

- Dialogs are the main UI (forms, lists, settings, confirmations). Build them with `services.templates()`
  (`notice`, `confirm`, `list`, `form`, plus input helpers `text`, `toggle`, `choice`, `range`) and show them with
  `services.dialogs().show(player, view)`. Buttons are plain labels from lang; handlers get a `Submission`
  (`values()`, `show(next)`, `error(message)` to re-open with typed values kept, `close()`).
- After a click a dialog stays on screen until the next one replaces it (`Button` default `After.NEXT`); a handler
  that shows nothing gets its dialog closed after a short grace. Mark a button that finishes something
  `.closes()`: the dialog then closes at once when its handler shows nothing (on the client already when every button
  closes; `view.closing()` marks them all, for confirmations whose answers all finish). Mark slow work (searches)
  `.waits()` / `view.waiting()`: the client shows its waiting screen until the answer. Errors still re-open the dialog
  with the red error line (`submission.error(...)`).
- Chat messages may embed a dialog: `ClickEvent.showDialog(services.dialogs().inline(viewer, view))`. Its session is
  kept apart from the screens the player opens, so browsing menus never expires it, and stays clickable for an hour
  (screens: 15 minutes), as long as the longest request it can answer. Check the request itself in the handler.
- Chest GUIs only where a grid is needed (auction house, sell, order delivery, spawner storage, crate preview).
  Extend `ui.gui.Menu` or `ui.gui.PagedMenu` (standard layout: rows 1-5 entries; 45 prev, 46 back, 47 sort,
  48 filter, 49 search, 50-52 extras, 53 next). Buttons: white name, gray description. Sort/filter buttons use
  `Cycle` (gray bullets, selected in white). No filler glass.
- Hub: register a `HubEntry` in `enable()`. Pause-menu ids are fixed: `shop`, `sell`, `auction`, `orders`,
  `spawners`, `teams`, `friends`, `homes`, `rtp`, `spawn`, `stats`, `settings`, `money`. Use them if you own that area.
- Sounds: the messenger plays the key's feedback sound; use `messenger.feedback(player, Feedback.CLICK)` for clicks.
  Every sound goes through `Sounds`, which applies each player's volume and sound switches; personal pings use
  `Sounds.ping(player, choice)`. Never add a sound switch of your own.
- Repeating action-bar lines (timers, countdowns) are `MessageKey.status(...)`: they stay on the action bar whatever
  the player's feedback channel. One-off results and errors (`success`/`error`) follow the player's choice. A refusal
  an event repeats many times a second (a move into a border) is `MessageKey.error(...).asStatus()`, so it stays on
  the action bar in the error colours, and should be told at most once a second; the messenger also shows an error
  the player keeps repeating in chat only once per burst.
- Lasting status that may show as a boss bar goes through `services.statusBars()` (one bar per player).

## Commands and permissions

- `SimpleCommand(name, aliases, description, permission, label -> Commands.literal(label)...)`.
- `CommandSupport`: `permission(node)` / `playerPermission(node)` predicates (players only see what they can use),
  `player(ctx)`, `cooldown(player, name[, duration])`, `onlinePlayer(arg)` / `knownPlayer(arg)` (word arguments with
  suggestions, never selectors), `online(ctx, arg)`, `known(ctx, arg)`, `amount(arg)` + `money(ctx, arg)`.
- Vanished staff stay hidden: `onlinePlayer` and `knownPlayer` suggest and `online` finds only online players the
  sender may see (`support.canSee(viewer, target)`: the server's hide list plus the staff feature's vanish, unless the
  viewer has `siftcore.staff.vanish.see`). Use `canSee` / `visibleOnline(viewer, uuid)` wherever you tell a player
  that someone is online.
- The `commands.yml` cooldown of a command applies to every player who runs it or any of its subcommands
  (`CommandService` gates every node). Call `cooldown(player, name)` only for paths outside the command that do the
  same thing (a dialog button); inside the command's own run it passes without charging twice. For an action on a
  screen the command opens (a friend request), use `cooldown(player, name, action)`: it has its own key, so opening the
  screen doesn't use it up.
- Node naming: `siftcore.command.<command>` for using a command, `siftcore.<feature>.<thing>` for extras,
  `siftcore.admin.<feature>` for staff tools. Limits use numeric nodes read by `core.player.Limits.highest(...)`
  (e.g. `siftcore.homes.5`).
- Console-friendly where it makes sense (admin and store commands must work from the console).

## Storage

- Tables for every feature already exist (`db/migrations/V001`-`V009`). Read them before adding anything.
- Need a change? Add `src/main/resources/db/migrations/V0NN.sql` in your range (first line is a `-- name`
  comment; use `{autoinc} {blob} {bigint} {text} {uuid} {engine}` tokens; one statement per `;` line ending):
  economy 10-14, shop/sell 15-19, orders 20-24, auction 25-29, spawners 30-34, teams 35-39, teleport 40-44,
  combat/bounties 45-49, stats 50-54, crates 55-59, chat 60-64, afk/shards 65-69, kits 70-74, admin/store/boosters 75-79
  (V075 store deliveries, V076 boosters),
  scoreboard 80-84, integrations 85-89, staff 90-94, displays 95-99, friends 100-104, cosmetics 110-114.
- Reads: `services.database().read(conn -> ...)` (off-thread). Writes outside trades: `database().write(...)`.
  Never `join()` a database future on a world thread.
- Write-behind for high-frequency counters (stats): keep in memory, flush on a timer, on quit and in `disable()`.

## Placeholders

`services.placeholders().register(name, description, player -> value)` and `registerPrefix(...)` for top-N style.
Resolvers must be cheap and thread-safe (read caches). Names are exposed to PlaceholderAPI as
`%siftcore_<name>%`; prefix them with your area (`teams_name`, `stats_kills`, `top_kills_name_<n>`).

## Testing (mandatory)

1. `mvn -q -B package` (JDK 25: `JAVA_HOME=<scratchpad>/dl/jdk/jdk-25.0.4.1+1`). Zero compile warnings from your code.
2. Unit tests for pure logic (`src/test/java`, JUnit 5). Keep logic that can be tested free of Bukkit calls.
3. Boot test on a private copy of the local server (see the brief for the harness and your port): clean enable,
   zero SiftCore warnings, your commands registered, `/sift selftest` passing your checks, console commands
   exercised, clean shutdown, data persisted across a restart.
4. Record what you could not verify (things that need a real client: dialog rendering, sprite looks).
