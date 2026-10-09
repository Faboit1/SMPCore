# Bounties (`bounties`)

Players put money on other players' heads. The money waits in the bounty escrow account
(`SystemAccounts.BOUNTY_ESCROW`) until a killer claims it or it runs out and goes back to its sponsors. Package
`feature/bounties`, config `features/bounties.yml`, text `lang/bounties.yml`, table `bounties` (migration V007; V045
adds the index the startup load uses).

Kills come from the combat feature through `PlayerKillCreditEvent`, which only fires for kills that passed the
anti-farm rules (see `combat.md`), so bounties are never claimed within a team, between friends, between players on
the same IP address or by the same killer on the same victim again too soon. That includes players killed for
leaving in combat.

## Placing

`/bounty <player> <amount>` (or the form in `/bounties`):

- The target must have joined the server before; you can't target yourself; the amount is at least
  `place.minimum` ($1,000) and parses like every amount (`1500`, `1.5k`, `2m`).
- Bounties stack: every placement is a separate contribution, and the bounty on a player is the sum of all of them.
- A confirmation dialog shows the exact amount, the tax and when it runs out from the sponsor's
  `bounty-confirm-above` (Server default follows `place.confirm-above`, $100k, where `0` never asks; Always; or from
  $10k, $100k or $1m). There is no Never: a bounty can't be taken back. A preset replaces the server's amount for
  that player, stricter or looser (from $1m skips the server's $100k question), as core `ConfirmAbove` decides for
  every "confirm from" setting; `place.confirm-above` only sets what Server default means.
- `place.cooldown` (5s) between two placements of the same player.
- The cancellable `BountyPlaceEvent` fires, then one ledger transaction moves the money from the sponsor to the
  escrow (kind `bounty_place`), adds the contribution to the in-memory book and inserts its `bounties` row, all or
  nothing. Every check runs again when the transaction runs, under the economy lock.
- The sponsor gets a receipt, the target a notice (`notify-target`) in their `bounty-target-alert` style (chat,
  above the hotbar, a title `Bounty on you: $5,000`, or off; the sponsor stays anonymous), and everyone else a chat
  announcement when the amount is at least `announce-above` ($50k) and their `bounty-announcements` filter shows it.
  Targets are reminded of the bounty on their head when they join (`remind-on-join`, unless they turned
  `bounty-join-reminder` off).

## Claiming

On a counted kill the killer claims every contribution on the victim except the ones they put up themselves (a
sponsor who kills their own target gets the others' part and is told why theirs stays). The cancellable
`BountyClaimEvent` fires, then one transaction pays the escrow to the killer minus `claim.tax-percent` (10%,
kind `bounty_claim`), destroys the tax (`bounty_tax`, a sink) and closes the claimed rows as `CLAIMED` with the
killer. The killer can be offline (a fall after a hit, or a combat log). If a contribution changed between planning
and paying (claimed, refunded or added at the same moment), the claim is planned again. If the killer can't hold the
money (the balance limit), the bounty stays.

The killer gets `You claimed $18,000 for killing Alex. $2,000 went to tax.`, everyone else an announcement
(`claim.announce`) when their `bounty-announcements` filter shows the claimed total, and the sponsors a notice
(`claim.notify-sponsors`).

## Player settings

Registered at construction (`BountiesFeature.registerSettings`), text in `lang/bounties.yml` under
`bounties.settings`. The config rules stay the master switches: a setting is only offered while its message is sent.

| Id | Group (order) | Options (default first) | Read in | Offered while |
|---|---|---|---|---|
| `bounty-target-alert` | Combat & stats (6) | chat, actionbar, title, off | `BountyActions.placed` (`BountyActions.targetLine`; title: `bounties.place.target-title`; quiet in combat turns it into the chat line) | `place.notify-target` |
| `bounty-confirm-above` | Combat & stats (10) | server, always, 10k, 100k, 1m | `BountyActions.request` (`BountyActions.asks`) | always |
| `bounty-join-reminder` | Combat & stats (11) | on | `BountiesFeature.onJoin` (checked again when the reminder is sent) | `place.remind-on-join` |
| `bounty-announcements` | Server announcements (4) | all, 100k, 1m, 10m, off | `BountyActions.broadcast` (placements by amount, claims by the claimed total) | `place.announce` or `claim.announce` |

## Expiry and refunds

Each contribution goes back to its sponsor `expiry.after` (14 days) after it was placed if nobody claimed it. An async
timer checks every `expiry.check-every` (5m); each refund is one transaction (escrow to sponsor, kind
`bounty_refund`, no cancellable event: refunds must never be blocked) that closes the row as `EXPIRED`. A refund that
fails (for example the sponsor is at the balance limit) stays active, is retried on every run and is logged once.
Staff removal refunds the same way and closes rows as `REMOVED`.

## The escrow invariant

The escrow balance always equals the sum of the active contributions, in memory and in storage: money, book and rows
change in one transaction, and a failed write reverts all three. `/sift selftest` checks both (memory under the
economy lock; storage after every queued write is committed) and the server log reports a mismatch at startup.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/bounties` (`/bounty`) | `siftcore.command.bounties` (everyone) | The biggest bounties (target, total, sponsor count) with a button per target, your own bounty and a place button. From the console: printed in chat |
| `/bounties <player>` | same | A target's bounty: total, sponsor count, your part, when the oldest part runs out, a button to add to it. From the console: every contribution |
| `/bounty <player> <amount>` | `siftcore.bounties.place` (everyone, players only) | Places or adds to a bounty |
| `/bountyadmin` | `siftcore.admin.bounties` (op) | Summary: players with a bounty, total, escrow balance, stored rows per state |
| `/bountyadmin info <player>` | same | Every contribution with its id, amount, sponsor, age and time left |
| `/bountyadmin remove <player>` | same | Refunds every contribution on a player (audited as `bounties.remove`) |
| `/bountyadmin expire` | same | Runs the expiry now (audited as `bounties.expire` when something was refunded) |

The main menu has a **Bounties** entry (hub id `bounties`, order 75) that opens the list with a back button.

## Placeholders

| Name | Value |
|---|---|
| `bounty_total` | The bounty on you in your money format (`$50,000`; `$0` without one) |
| `bounty_total_raw` | The same as a plain number |
| `bounty_top_name_<n>` | Name of the player with the n-th biggest bounty (1-20), `-` when there is none |
| `bounty_top_value_<n>` | The n-th biggest bounty in the viewer's money format (the server's way without a viewer), `-` when there is none |

They read the in-memory book (the ranking is rebuilt only after a change).

## Events (`api.event`)

| Event | When | Cancelling |
|---|---|---|
| `BountyPlaceEvent` | Before a placement's transaction | Nothing is paid |
| `BountyClaimEvent` | Before a claim's transaction (after a counted kill) | The bounty stays on the victim |

The ledger also fires `EconomyTransactionEvent` for placements and claims (refunds are silent bookkeeping).

## Config summary (`features/bounties.yml`)

| Key | Default | Meaning |
|---|---|---|
| `place.minimum` | `1k` | Smallest amount per placement |
| `place.confirm-above` | `100k` | Ask players on Server default (`bounty-confirm-above`) for confirmation from this amount (`0` never asks them) |
| `place.cooldown` | `5s` | Between two placements of one player (0s-10m) |
| `place.announce` / `announce-above` | `true` / `50k` | Announce placements of at least this much (each player's `bounty-announcements` filters further) |
| `place.notify-target` | `true` | Tell the target (in their `bounty-target-alert` style) |
| `place.remind-on-join` | `true` | Remind players of their bounty when they join (unless their `bounty-join-reminder` is off) |
| `claim.tax-percent` | `10` | Destroyed part of a claim (0-90) |
| `claim.announce` | `true` | Announce claims (filtered by each player's `bounty-announcements`) |
| `claim.notify-sponsors` | `true` | Tell online sponsors their bounty was claimed |
| `expiry.after` | `14d` | When a contribution goes back to its sponsor (1h-365d) |
| `expiry.check-every` | `5m` | Expiry timer period (30s-1h; rescheduled on reload) |
| `list-size` | `10` | Bounties shown by `/bounties` (1-20) |

## Design decisions

**Contributions, not totals.** Each placement is its own row and its own book entry, so stacking, partial claims (a
sponsor killing their own target), per-contribution expiry and refunds to the right sponsor all fall out of one model.
A target's bounty is an immutable value replaced as a whole, so readers on any thread always see a consistent total.

**One transaction per money move.** The book is only changed inside ledger transactions (their apply and revert steps
under the economy lock) and at startup, and every row change is written with the ledger rows. Closing a row requires it
to still be active in storage, so a double claim or a claim racing a refund can't both succeed: the loser's
transaction fails its check in memory, or its write in storage and is reverted.

**Threads.** Claims run on the victim's thread inside the death event; placement runs on the sponsor's thread; the
expiry timer is async. The ledger is safe from any thread and messages are packets. Nothing waits on the database.
Player settings are read from memory (by UUID for the target and the announcement audience, so another region's
player is never touched).

## Tests

Unit: `BountyBookTest`, `BountyMathTest`, `BountyServiceTest`, `BountiesResourcesTest` and `BountySettingsTest`
(groups and order, config-dependent offering, the confirmation threshold against the server rule, the target alert
line per style, the announcement filter). End to end (`tools/e2e/CombatScenarios.java`): `bounty-place`,
`bounty-claim`, `bounty-admin` and `bounty-settings` (the target alert as a title through the dialog and above the
hotbar through the API, the announcement filter and confirmation threshold through the API, the join reminder).
