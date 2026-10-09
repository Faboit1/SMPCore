# Stats and leaderboards (`stats`)

Lifetime counters per player and cached leaderboards. Package `feature/stats`, config `features/stats.yml`, text
`lang/stats.yml`, table `stats` (migration V007, no schema change).

| Counter | Source |
|---|---|
| Kills, deaths, streak, best streak | The combat feature calls `StatsRecorder#kill(killer, victim)` and `#death(victim)` |
| Mobs killed | `EntityDeathEvent` of a `Mob` (never players or armor stands) with a player killer in survival or adventure |
| Blocks mined | `BlockBreakEvent` in survival or adventure, with the anti-farm rules below |
| Money earned | Committed ledger transactions (`Ledger#subscribe`), see below |
| Playtime | One second per second for every online player who is not AFK (`core.link.AfkStatus`) and not vanished (`core.link.VanishStatus`) |

The feature implements `core.link.StatsRecorder`; `StatsFeature#recorder()` returns it.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/stats` | `siftcore.command.stats` (everyone) | Your stats in a dialog, with buttons to every leaderboard |
| `/stats <player>` | `siftcore.command.stats.others` (everyone) | Someone else's stats (online or offline); their balance shows as `hidden` unless their `balance-privacy` allows you (staff with `siftcore.admin.eco` always see it). From the console: printed in chat, with the balance |
| `/top` (`/leaderboard`, `/leaderboards`) | `siftcore.command.top` (everyone) | Leaderboard picker. From the console: the list of boards |
| `/top <board> [page]` | `siftcore.command.top` | One page of a board in a dialog with previous/next. From the console: printed in chat |
| `/playtime` | `siftcore.command.playtime` (everyone) | Your active playtime, as a chat line |
| `/playtime <player>` | `siftcore.command.playtime.others` (everyone) | Someone else's playtime (console too) |
| `/sift stats add <player> <stat> <value>` | `siftcore.admin` + `siftcore.admin.stats` (op) | Adds to a counter and stores it at once (audited as `stats.add`) |
| `/sift stats set <player> <stat> <value>` | same | Sets a counter (audited as `stats.set`) |
| `/sift stats reset <player>` | same | Every counter, the streak and the best streak back to zero (audited as `stats.reset`) |
| `/sift stats refresh` | same | Saves pending stats and rebuilds the leaderboards now |

Boards: `kills`, `deaths`, `kdr`, `streak` (best streak), `playtime`, `mobs`, `blocks`, `earned`, `money` (balance).
Staff stats: `kills`, `deaths`, `mobs`, `blocks`, `earned` (an amount like `1.5k`), `playtime` (a duration like
`2h30m`). The streak is not a staff stat; `reset` clears it.

The main menu has a **Stats** entry (hub id `stats`, order 70), which is also the `stats` pause-menu entry.

## Placeholders

Exposed to PlaceholderAPI as `%siftcore_<name>%` and used by the scoreboard and tab list. All of them read memory or
the leaderboard snapshot, never the database.

| Name | Value |
|---|---|
| `stats_kills`, `stats_deaths`, `stats_mobs`, `stats_blocks` | Whole numbers with separators (`1,234`) |
| `stats_kdr` | Kills per death with exactly two decimals (`1.50`) |
| `stats_streak`, `stats_best_streak` | Current and best kill streak |
| `stats_playtime` | Active playtime, formatted (`3d 4h`, `5h 12m`, `40s`) |
| `stats_playtime_hours` | Active playtime in whole hours |
| `stats_earned` | Money earned, formatted (`$1.5m`) |
| `top_<board>_name_<n>` | Name at place `n` (1-100) of a board, `-` when empty |
| `top_<board>_value_<n>` | Value at place `n`, formatted like the board (`-` when empty) |
| `top_<board>_rank` | Your place on a board, `0` when not listed |

`stats_*` are exact for online players and for offline players whose stats are in memory (viewed in the last few
minutes); for other offline players they show zeros. `top_*` work for anyone. Players who hide from the leaderboards
are on no board, so they show up in no `top_*` placeholder and on no leaderboard hologram.

## Player settings

| Id | Group (order) | Options (default first) | Read in |
|---|---|---|---|
| `leaderboard-rank-alerts` | Combat & stats (9) | top-10, all, off | `StatsFeature.climbed` after each rebuild (`Climbs`) |
| `hide-from-leaderboards` (shared, `siftcore.stats.hide`) | Privacy | off | `HiddenPlayers` at each rebuild |
| `balance-privacy` (shared) | Privacy | everyone, friends, nobody | `StatsViews.withBalanceVisibility` |

**Climb alerts.** After each rebuild the old and the new boards are compared for the online players. Moving to a
better place, or onto a board, is a climb; dropping, staying and the first build after a start are not, and the
deaths board never counts. Top 10 only (default) tells climbs that end inside the top 10, Any place every climb; at
most one line per board per rebuild: `You climbed to number 3 on the Kills leaderboard.`

**Hiding from the leaderboards.** Each rebuild reads the setting's rows from the `settings` table (most accounts are
offline) in the same async step as the board queries; online players read their loaded value with their permissions
applied. A hidden player is left off every board, including the money board (`top_money_*`; `/baltop` is the economy's
and must read the setting itself) and the holograms built from them. The stats boards fetch one more row per hidden
player (at most 1,000 more) so they still fill up. The money board can't: it comes from the economy's top list, a
snapshot of only `baltop.size` entries (`features/economy.yml`, 100 by default, the same as `leaderboards.size`), so
asking for more rows returns no more and each hidden account among the richest leaves the money board one place
short. It fills up again once the economy keeps more entries than `baltop.size` or leaves hidden accounts out itself
(economy's part, see Known limits). A server default or lock in `features/settings.yml` applies to everyone who never chose; an offline player's
stored choice counts until they join again, even if they lost `siftcore.stats.hide` meanwhile. If the read fails the
previous boards stay, so a failed read never lists a hidden player.

**Balance privacy.** `/stats <player>` reads the target's `balance-privacy` (from memory when online, one settings
read when offline); Friends needs the friends system and otherwise reads as Nobody. Your own stats and the console
always show the balance.

## Config summary (`features/stats.yml`)

| Key | Default | Meaning |
|---|---|---|
| `save-interval` | `60s` | Write-behind period (10s-10m) |
| `keep-offline` | `5m` | How long an offline player's loaded stats stay cached after use |
| `leaderboards.refresh` | `60s` | Leaderboard rebuild period (10s-1h) |
| `leaderboards.size` | `100` | Places kept per board (10-100) |
| `leaderboards.page-size` | `10` | Lines per `/top` page |
| `leaderboards.kdr-min-kills` | `25` | Kills needed to appear on the KDR board |
| `blocks-mined.count-instant-blocks` | `false` | Count blocks that break instantly (grass, flowers, crops, torches) |
| `blocks-mined.ignore-placed-for` | `15m` | A recently placed block does not count when mined (0s turns it off) |
| `blocks-mined.placed-memory` | `50000` | Recently placed blocks remembered (bounded, oldest forgotten first) |
| `blocks-mined.ignore` | melon, pumpkin, cactus, bamboo, sugar cane, kelp, cocoa, chorus | Blocks that never count |
| `money-earned.kinds` | `sell`, `ah_sale`, `order_fill`, `bounty_claim`, `spawner_sell`, `crate_reward` | Ledger kinds that count as earned |
| `money-earned.tax-kinds` | `ah_tax`, `order_tax` | Taxes subtracted from what the same player earned in the same transaction |

Everything applies with `/sift reload` (timers are rescheduled when their period changes). Block names are checked
against the server's block registry and unknown ones are reported.

## Design decisions

**Write-behind without reads.** Each player's unsaved changes are one `StatsDelta`: per counter "keep or replace the
old value, then add", plus a `StreakChange` for streak and best streak (`streak' = keep ? streak + n : n`,
`best' = max(keepBest ? best : 0, carry ? streak + k : 0, peak)`). Deltas compose exactly (`a.then(b)` equals
applying `a` then `b`; the composition is associative), so any number of events, kills, deaths, staff sets and resets
collapse into one delta, and a delta is written as one atomic upsert that needs no prior read:
`INSERT ... ON CONFLICT(uuid) DO UPDATE SET best_streak = MAX(best_streak * ?, (streak + ?) * ?, ?), streak = streak * ? + ?, kills = kills * ? + ?, ...`
(`GREATEST` and `ON DUPLICATE KEY UPDATE` on MySQL, where `best_streak` is assigned first because MySQL assigns left to
right). This is what makes offline increments safe: money earned from an auction while the seller is offline is
recorded in memory and written as an increment, even if the seller's row was never loaded.

**No double counting.** A change lives in exactly one place: pending, in flight, or confirmed. A save moves pending to
in flight; success folds it into the confirmed values, failure puts it back in front of newer changes. Only one
database operation (a write or a load) runs per player at a time, so writes reach the database in event order and a
load never races a write. Covered by randomized tests against an event model and against real SQLite.

**One bad row never blocks the others.** A save writes all players in one database unit, but each player's upsert
runs in its own savepoint: a row the database refuses rolls back alone, its change goes back to memory and is retried
with every save, and the warning names the player (logged on the 1st, 10th, 20th... failure in a row). Values are
capped at their column's range, identically in memory and in SQL (`MIN`/`LEAST`): kills, deaths, streak and best streak
at 2,147,483,647 (`INTEGER` is 32 bits on MySQL), the other counters at 10^18. Capping commutes with the delta algebra
because nothing ever subtracts. Staff values above a counter's cap are refused.

**Loading.** Online players are loaded during `AsyncPlayerPreLoginEvent` (bounded wait, the join retries if it was
slow), so `/stats`, placeholders and the combat feature see exact values. Offline players are loaded on demand
(`/stats <player>`) and dropped again after `keep-offline`. `StatsRecorder#get` returns 0 for players not in memory.

**Saving.** Every `save-interval`, when a player quits, before every leaderboard rebuild, and synchronously in
`disable()` (players online at shutdown get no quit event). Saves and the final shutdown save are serialized, the
final save waits for running saves, and changes that arrive after it (late events while stopping) are written
straight away. Staff corrections are saved immediately.

**Leaderboards.** An async timer saves pending stats, runs one query per board (`ORDER BY <column> DESC, uuid LIMIT
size`, indexed columns from V007) and swaps in immutable snapshots; readers (dialogs, placeholders, console) never
touch the database. Only players who have joined the server are listed (rows recorded for anything else, such as an
NPC, are stored but left out; 25 extra rows are fetched so the boards still fill up). Ranks use competition ranking
(equal values share a place: 1, 1, 3). KDR is
`kills / max(1, deaths)`, shown with exactly two decimals and ranked by the exact ratio (cross-multiplied, never
rounded), then by kills; players need `kdr-min-kills` kills to be listed. The `streak` board ranks the best streak
(the current streak changes too often to rank). The `money` board reuses the economy feature's balance leaderboard
(`EconomyApi#top`, which the economy rebuilds from memory on its own timer) instead of querying balances again, so
`/baltop` and `/top money` always agree; it is copied into the stats snapshot at each rebuild.

**Money earned.** Per committed transaction and per player: the sum of credits with an earning kind, minus that same
player's debits with a tax kind in that same transaction, counted only when positive. Shards, system accounts (escrow),
accounts that are not players, payments between players (`pay`), admin grants and debits of an earning kind (the
buyer's side) never count. If the auction or order feature charges the tax in a separate transaction, it is not
subtracted; they post sale and tax together.

**Anti-farm for blocks mined.** Not counted: creative and spectator breaking; blocks with zero hardness unless
`count-instant-blocks`; block types in `blocks-mined.ignore` (crops that regrow on their own and could be harvested
forever); and any block a player placed less than `ignore-placed-for` ago, no matter which player mines it, so
two players cannot farm each other's blocks either. Placed positions are kept in a bounded, striped LRU
(`placed-memory`), carried along when a piston moves them and forgotten when a piston breaks them, and swept every
five minutes. The rule is checked on the region thread of the event, in memory only. The cache is not persisted, so
a block placed just before a restart counts when mined after it.

**Playtime.** An async one-second timer adds a second for every online player who is not AFK and not vanished. It
touches no world state. The AFK feature is wired in through `AfkStatus`, the staff feature through `VanishStatus`.
Vanished time is not active play, and a counter that kept growing while a staff member is hidden would tell anyone
polling `/playtime <name>` or `/stats <name>` that they are online.

**Threading.** Event handlers run on the event's region thread and only touch memory. Loads, saves and leaderboard
queries run on the database threads; dialogs opened after a load hop back to the viewer's thread. Console output is
thread-safe. Nothing blocks a region thread.

## Integration

- `FeatureCatalog`: `new StatsFeature(services, problems, afk.status(), economy.economy(), admin, staff.vanish())`.
- The combat feature takes `stats.recorder()` (a `StatsRecorder`) and calls `kill(killer, victim)` for a counted kill
  and `death(victim)` for a death without kill credit. It must not call both for one death.
- Other features may call `recorder().add(player, stat, amount)`; it is thread-safe and memory-only.

## Known limits

- **The money board and hidden players.** The money board reads the economy's top list, which holds only
  `baltop.size` entries. Every account hidden with `hide-from-leaderboards` among those leaves the money board (and
  `top_money_*`, and the holograms built from it) one place short. Fixing it belongs to the economy: keep more entries
  than `baltop.size` (for example size + 25 + the hidden count) or leave hidden accounts out of the list itself.
- **`/baltop`** is the economy's command and lists hidden players until the economy reads `hide-from-leaderboards`.
- **Losing the permission.** An offline player's stored `hide-from-leaderboards` keeps counting until they join
  again, even if they lost `siftcore.stats.hide` meanwhile (permissions of offline players can't be checked).
- **Balance privacy and ignores.** `everyone` means everyone: a player you ignore can still see your balance in your
  `/stats` (`Relations.allows` leaves ignores out). `/balance <name>` and the chat card should use the same rule, so
  the three places agree.

## Tests

- Unit (`src/test/java/.../feature/stats`): KDR formatting and exact comparison; streak algebra against an event model,
  split saves and associativity; delta composition; leaderboard ordering, ties and paging; earnings rules; the placed
  block cache; the write-behind store (no double counting across saves, failed saves retried in order, loads waiting
  for writes, offline updates without loads, eviction, shutdown and late changes, concurrency); the real SQL against
  SQLite (the upsert matches the in-memory algebra, leaderboard queries); config and lang resources.
- Settings (`StatsPlayerSettingsTest`): the group and order, climbs and the top-10 rule, hidden players (stored,
  online, locked and default-on cases, the fetch size), boards without hidden players that still fill up, the money
  board stopping at the economy's snapshot (one place short per hidden account in it), a failed hidden read keeping
  the previous boards, the swap listener, balance visibility.
- End to end (`tools/e2e`, `StatsScenarios`): `stats-dialog`, `stats-sources` (real ledger kinds, mob kills, block
  breaking and placing by a protocol bot), `stats-top`, `stats-persist`, `stats-vanish-playtime` and `stats-settings`
  (hiding from the leaderboards, a climb alert changed through the dialog, balance privacy for an offline player).
