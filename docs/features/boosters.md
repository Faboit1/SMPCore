# Sell boosters (`boosters`)

Server-wide sell boosters: for a while, everything anyone online sells to the server pays a percent more. Boosters
come from the store (a supporter buys one for everybody, or the server runs one for a community goal) and from staff,
run one after another, never stack, and their time only counts while the server runs. Package `feature/boosters`,
config `features/boosters.yml`, text `lang/boosters.yml`, table `boosters` (migration `V076`), contract
`core.link.ServerBoosters`, event `api.event.SellBoosterEvent`.

A booster is a convenience for the whole server, not an advantage for the buyer: the buyer gets exactly what everyone
else online gets, and the announcement thanks them by name. Paid ranks never get a personal sell multiplier
(`docs/monetization.md`).

## What players see

- **Announcements** (chat, everyone online): "Alex started a +10% sell booster for 30 minutes. Thank you!" (store),
  "Mod started a +10% sell booster for 30 minutes." (staff in game), "A +10% sell booster started for 30 minutes.
  Everything you sell pays more." (console or a goal), with the staff reason on the next line in quotes. A store
  booster that has to wait: "Alex bought a +10% sell booster for 30 minutes. It starts after the boosters before it
  (number 2 in line). Thank you!". At the end: "The +10% sell booster has ended." or "... was ended early." Each
  can be turned off in `announce.*`. Each player picks which they see with "Sell booster announcements" in
  `/settings` (Server announcements): all, only new boosters (starts and their reason line) or none; announcements
  about their own booster always show, and the console always gets every one.
- **Boss bar** while a booster runs, for everyone online: "+10% sell booster from Alex - 29m 41s left" (or without
  "from" for the server), counting down every second, green progress by default (`bar.*`). Players hide it with the
  "Booster bar" switch in `/settings` (Display, id `booster-bar`, on by default; flipping it shows or hides the bar at
  once) or the button in `/booster` (not shown while the server locks or hides the setting).
  The bar is the booster's line on the shared per-player status bar (`services.statusBars()`, owner `boosters`, the
  lowest priority `StatusBars.PRIORITY_SERVER`): the combat timer and the AFK zone countdown take the bar while they
  last and the booster comes back after, so a player never sees two bars. The status bar shows, changes and hides each
  player's bar only on that player's thread (the server keeps a player's shown bars in an unsynchronised set); the
  global thread only works out what the line says, once a second.
- **Sidebar line** `booster` in the default sidebar ("Booster +10% 29m 41s"), shown only while a booster runs (a line
  disappears while one of its placeholders is empty).
- **`/booster`** (`/boosters`, `/boost`; also the main menu entry "Sell booster", order 27): the running booster
  (percent, time left of its length, who it is from) and what waits in line (the first `queue.shown`, then "and 3
  more"), plus the bar switch. From the console it prints the same lines.
- **Selling**: receipts, the confirmation, the sell menu, `/worth`, the price list, the details dialog and the spawner
  storage menu name the booster ("You sold 64 diamond for $28,160 incl. +10% booster."). See `sell.md`.

## How a booster raises prices

The running booster's percent `p` multiplies what the server pays: a sale line pays
`worth x (rank + mastery) x (1 + p/100)`, rounded down once per category with exact decimals (64 diamonds worth $400
at +10%: exactly $28,160). The sell feature applies it in one place (`WorthService.Rates#multiplier`), so `/sell`,
`/sell hand`, `/sell hand all`, `/sell all`, category selling, the sell menu, the shop's quick sell and sell-back
prices, and spawner storage "Sell all" (and selling a broken spawner's storage) all pay it. Buy orders and the auction
house are between players and are never boosted: in a sale split between orders and the server, only the server's
part is.

The percent paid is never more than the current `sell.max-percent`, even for a booster started before the limit was
lowered or a store booster bought for more, so the shop's arbitrage check (shop price above best sell bonus x largest
booster x 1.1, see `shop.md`) always covers what is paid. Everything players see says what sales pay: the
announcements, the boss bar, `/booster`, the placeholders, the buyer's notice, `/purchases` and `SellBoosterEvent`
all show the capped percent. Only staff see what was bought: `/sift booster list` adds "(bought for +40%, capped by
sell.max-percent)", and the store's console reply and a warning in the log say so at delivery. Raising the limit
again lets the booster pay what was bought.

## The line

| Rule | How |
|---|---|
| One at a time | The oldest booster runs; the others wait in the order they were added. Percentages never add up. |
| Back to back | When one runs out, the next starts in the same tick with its full length; time left over in that tick is carried into it, so no time is lost or gained. |
| Time counts only while the server runs | The feature ticks every second on the global thread and passes exactly the time measured with `System.nanoTime()` since the last tick. Every change (start, stop, refund, delivery) catches up first. The running booster's remaining time is stored every minute and at shutdown (after a last tick, then the database writer is flushed); after a restart it resumes with what was stored (after a crash it resumes from the last stored minute, so it can run up to a minute longer than it should). |
| Staff limit | `/sift booster start` is refused with "20 boosters are already waiting" at `queue.staff-limit`. A store purchase is always accepted. |
| Ends | A booster ends when it ran its length (state `ended`), when staff stop it (`stopped`), or when its store purchase is refunded (`revoked`). |

Concurrency: the line lives in memory and is changed only under the economy lock; store deliveries, staff starts and
refunds change it inside their own ledger transaction (with an undo), so one store reference starts at most one booster
and a failed write takes the change back. Rows are stored on the database writer in the same order. Sale prices, the
placeholders and the bar read an immutable snapshot published after every change, without locking, so prices rise
the moment a booster is added. Announcements (and `SellBoosterEvent`) wait until the change is stored: each such
change is "unsettled" from its apply until its transaction commits (`LedgerTx#afterCommit`) or is undone, and the
once-a-second comparison of snapshots (`LineWatch`) skips a second while anything is unsettled. A change that was
taken back is therefore never announced, neither as started nor as ended, and a refund that was taken back never
announces an end.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/booster` | `siftcore.command.booster` (everyone) | The running booster and the line |
| `/sift booster` or `/sift booster list` | `siftcore.admin.booster` | Every running and waiting booster: id, percent, time left, who, store reference or staff reason |
| `/sift booster start <percent> <length> [reason]` | `siftcore.admin.booster` | Starts a booster now, or puts it in line (percent 1 to `sell.max-percent`, length like `30m`, `2h`, `1d` within `sell.min-duration`..`sell.max-duration`). From the console it is from the server; in game it carries the staff member's name. Audit `booster.start` |
| `/sift booster stop [id]` | `siftcore.admin.booster` | Ends the running booster (or the one with that id, running or waiting) early. Audit `booster.stop` |
| `/sift store booster <player\|uuid\|console> sell <percent> <length> <ref>` | `siftcore.admin.store`, console only | Store delivery: exactly once per reference, recorded in `store_deliveries`, revocable with `/sift store revoke <ref>` (see `integrations.md`). Not available to players at all, so nobody in game can start a "thank you" booster under someone's name |

Staff errors use the error colours and sound ("The percent must be from 1 to 25 (sell.max-percent in
boosters.yml).", "There is no booster #7 running or in line.").

## Placeholders

| Placeholder | Value |
|---|---|
| `%siftcore_booster_active%` | `true` while a booster runs, else `false` |
| `%siftcore_booster_percent%` | The running booster's percent, like `10` (`0` when none runs) |
| `%siftcore_booster_time_left%` | Its time left, like `29m 41s` (empty when none runs) |
| `%siftcore_booster_by%` | The buyer or staff member's name, `Server` for one from the server (empty when none runs) |
| `%siftcore_booster_queue%` | How many boosters wait |

## Store purchases

The store runs `sift store booster {uuid} sell 10 30m tebex-{transaction}-{packageId}`; a community goal can run
`sift store booster console sell 15 48h goal-2026-10`. Only the console runs it (the web store's console commands,
RCON or staff at the console). A purchase is only refused for the hard limits, 1 to 50% and 1 minute to 30 days (a
mistyped command); `sell.max-percent` and `sell.min-duration`/`max-duration` are for staff boosters. A store booster
above `sell.max-percent` is delivered all the same (the buyer paid; Tebex does not retry) and pays the limit while it
is lower; the self-test `the store's booster packages arrive and pay in full` names any package of
`docs/monetization.md` the config would cap. The delivery is one ledger transaction (reference check,
booster, delivery row), so a retried command never starts a second booster. A refund
(`sift store revoke <ref> refund`) ends the booster at once if it runs (the next one starts), takes it out of line if
it waits, and changes nothing if it already ran; the reference can't be delivered again. The buyer sees the purchase
in `/purchases` with "running now, 12m left", "waiting, number 2 in line", or "taken back (refund, booster ended
early)".

## Config (`features/boosters.yml`)

| Key | Default | Meaning |
|---|---|---|
| `sell.max-percent` | 25 | Largest booster (1 to 50). No booster pays more: staff boosters above it are refused, a store booster above it is delivered and pays this much while it is lower. The shop is checked against it, so raising it can make `/sift reload` refuse shop prices that become too low |
| `sell.min-duration` | `1m` | Shortest staff booster (store boosters: 1m to 30d) |
| `sell.max-duration` | `3d` | Longest staff booster (raised to `min-duration` if shorter) |
| `queue.staff-limit` | 20 | Most waiting boosters before staff can't add more (store boosters always go in) |
| `queue.shown` | 5 | Waiting boosters `/booster` lists |
| `announce.started` / `queued` / `ended` | true | The chat announcements (with all off, the announcement filter is not offered) |
| `bar.enabled` | true | The boss bar (and its switch in `/booster` and `/settings`) |
| `bar.color` | `green` | pink, blue, red, green, yellow, purple or white |
| `bar.style` | `progress` | progress, notched_6, notched_10, notched_12 or notched_20 |

Wrong values are config problems and fall back to the default (a reload with a problem is refused as a whole).

## Player settings (`/settings`)

Registered by `BoosterNews.register` (text: `lang/boosters.yml` `toggle` and `settings`; "All" and "Off" are the
shared announcement words of `lang/settings.yml`).

| Group, order | Id | Kind, default | Offered while | Read in | Effect |
|---|---|---|---|---|---|
| Display, 6 | `booster-bar` "Booster bar" | switch, on | `bar.enabled` | `BoosterBar.sync` (every second, and at once through the setting's change hook and the `/booster` button) | Show the boss bar while a booster runs. |
| Server announcements, 8 | `booster-announcements` "Sell booster announcements" | choice all/starts/off, all | any `announce.*` is on; "Only new boosters" only while starts and something else are announced (otherwise it reads as all) | `BoosterAnnouncer.announce` (`BoosterNews.shows`) | Which announcements the player sees in chat; their own booster's always show. |

The `/booster` button flips `booster-bar` through `PlayerSettings.set(player, ...)` and says "The booster bar is set by
the server." when the server locked or hid it meanwhile; the button is not shown while it is locked or hidden.

## Storage

Table `boosters` (V076): id, kind (`sell`), percent, seconds (whole length), remaining (milliseconds), state
(`queued`, `active`, `ended`, `stopped`, `revoked`), owner, source (`store` or `staff`), ref, reason, actor, created,
started, ended. Only `queued` and `active` rows are loaded at startup; an `active` row resumes with its stored
remaining time. Ids come from the shared id sequence (`IdSequence`).

## API

- `core.link.ServerBoosters` (`BoostersFeature#boosters()`): the running percent, the limit and the limit being
  loaded, `paid(percent)` (what a booster bought for that pays now), the hard store limits (`PERCENT_CAP` 50,
  `MIN_LENGTH` 1m, `MAX_LENGTH` 30d), `problem(kind, percent, duration)` (only those hard limits), `deliver(tx, grant)`
  and `revoke(tx, ref)` inside a ledger transaction, and `status(ref)` (running or waiting, the percent it pays, time
  left, place in line). The sell feature, the integrations feature (store and `/purchases`) and the shop's price check
  use it.
- `api.event.SellBoosterEvent` (`STARTED` / `ENDED`, id, the percent it pays, length, time left, owner, store
  reference, whether it ended early): fired on the global thread at most a second after the change, once the change is
  stored (never for one that was taken back), for example to post it to Discord.
- `api.event.StoreDeliveryEvent` with `Kind.BOOSTER` (cancellable) before a store booster is delivered.

## Self-tests (`/sift selftest`)

- `boosters run back to back`: two 1-minute boosters at +10% and +20% raise prices by 10%, after 90 seconds the
  second runs with 30 seconds left at +20%, and after two minutes nothing runs.
- `the running booster stays within max-percent`.
- `the store's booster packages arrive and pay in full`: the packages of `docs/monetization.md` (+15% for 30m and 2h,
  the +10% Tycoon and community goal boosters) are within the hard limits and not capped by `sell.max-percent`.
- `every booster change is stored`: no change waits for its database commit.
- `boosters are loaded`: the table was read at startup.
- `the booster bar switch is in /settings`.
- `booster announcements follow each player's filter`: the setting is registered and the filter lets through what
  it should (only new boosters hides the end, off hides everything but the player's own booster).
- From the sell feature: `a sell booster raises sales by exactly its percent` and `shop safe with mastery and
  boosters`.

## Tests

Unit: `BoosterQueueTest` (one runs and the rest wait without stacking, back to back with the leftover carried over,
time passes only for the running one, ending early, undoing an add and an end, loading, lookups by reference),
`BoosterTimeTest` (lengths in words), `BoosterServiceTest` (real SQLite and ledger with a fake clock: staff limits,
store boosters always queue and run back to back, the remaining time survives a restart and the downtime doesn't
count, refunds, staff stop, never past the current limit; a delivery whose commit is held is not announced until it
commits; a delivery or refund whose commit fails is never announced, neither as started nor as ended; a store
booster above `max-percent` is accepted, stored as bought and shown and paid at the limit), `LineWatchTest` (each
start, wait and end told once, nothing told or remembered while unsettled), `BoostersResourcesTest` (config, the
store packages arrive and pay in full with the defaults and the self-test names one that would be capped, wrong
values, the announcement text), `economy.LedgerTest#afterCommitRunsOnlyForAStoredTransactionAndBeforeCommittedCompletes`,
`sell.BoostsTest` (exact percent math), `shop.ShopValidatorTest#theLargestBoosterIsPricedIn`,
`integrations.StoreServiceTest` (concurrent deliveries start one booster, refusals record nothing, a paid booster above
the limit is delivered, revokes).

End to end (`tools/e2e`, `BoostersScenarios`): `boosters-sell` (exact +10% payout and receipt, the boss bar arrives
and counts down, `/worth`, `/booster`, hiding and showing the bar, stopping), `boosters-queue` (+10% then +20% back to
back, never +30%, the second starts by itself), `boosters-store-revoke`, `boosters-spawner` (storage "Sell all" pays
exactly worth x 1.1), `boosters-orders` (order part unboosted, server part boosted), `boosters-shop-guard`,
`boosters-capped` (with `max-percent: 10` a +15% store booster is delivered, staff are told, the announcement, the
buyer's notice, the bar, the placeholder and the payout all say +10%, and raising the limit lets it pay +15%; in game a
player with `siftcore.admin.store` can look purchases up but can't deliver money or boosters or revoke),
`boosters-persist-setup` / `boosters-persist-check` (run with a restart in between: the booster lost only the time
the server ran), `boosters-settings` (announcements off through the settings dialog and only new boosters with
`/settings booster-announcements starts` next to a player on all: who is told about the start and the end;
`/settings booster-bar off` and `on` hide and show the bar at once). Unit: `BoosterNewsTest` (the filter, groups and order, offered only while the config
announces or shows a bar, the shared option words).

What needs a real client: how the boss bar and the `/booster` dialog look.
