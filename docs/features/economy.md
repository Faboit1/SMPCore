# Money (`economy`)

Balances, `/pay` with confirmation and a daily limit, the money leaderboard, the staff tools and the public API. The
money engine itself (the ledger, `economy/Ledger`) is core; this feature is how players use it. Package
`feature/economy`, config `features/economy.yml`, text `lang/economy.yml`. No tables of its own: it reads the ledger
(`ledger`) and the `settings` table.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/balance` (`/bal`, `/money`) | `siftcore.command.balance` (everyone) | Your money and shards. |
| `/balance <name>` | `siftcore.command.balance.others` (everyone) | Another player's money and shards, when their "Who can see my balance" (`balance-privacy`) lets you; otherwise "Alex keeps their balance private." The console, staff with `siftcore.admin.eco` and the player themselves always see it. An offline player's choice is read from storage (`PlayerSettings.lookup`). |
| `/pay` | `siftcore.command.pay` (everyone) | The pay form (player and amount); refusals come back in the form with what was typed. |
| `/pay <name> <amount>` | `siftcore.command.pay` | Pays at once, or asks first (see Confirmation). |
| `/baltop [page]` (`/balancetop`, `/moneytop`) | `siftcore.command.baltop` (everyone) | The richest players (a dialog; chat lines for the console). |
| `/eco give|take|set <name> <amount> [money|shards]`, `/eco history <name> [page]`, `/eco resume` | `siftcore.admin.eco` | Staff money tools, audited. |

The main menu's Money page (`money`, order 10) shows the balance, shards, leaderboard place and what is left of the
daily pay limit, with Pay a player and Richest players.

## Paying

Every payment runs on the payer's thread:

1. **Checks without storage**: not yourself, the receiver may be offline (`pay.allow-offline-targets`), at least
   `pay.minimum`, the economy is available, enough money. Then the cooldown (`pay.cooldown`).
2. **One step with storage**: the day's total sent (`PayLimits`, loaded once a day per player) and whom the receiver
   accepts payments from (`pay-accept-from`, from memory while they are online, otherwise one settings read) load
   together. A receiver who doesn't accept this payer: "Alex doesn't accept payments from you." (in the form when it
   came from the form). Over the daily limit: "You can send $x more today ...".
3. **Confirmation** when the amount reaches the server's `pay.confirm-above` (100k), or the payer's own lower amount
   ("Confirm payments from": always, $1k, $10k or $100k; never looser than the server). The dialog shows the exact
   amount and what is left today.
4. **Transfer**: who may pay is checked again (the receiver may have changed it, or the two their relation, while the
   confirmation was open), `PlayerPayEvent` (cancellable), then one `LedgerTx` transfer (kind `pay`) with the daily
   limit checked inside the transaction.
5. **Told**: the payer in chat ("You paid Alex $1,500."); the receiver, if online, in their "Payment alerts" style
   (chat, above the hotbar, or not at all) when the amount reaches their "Only alert payments from" minimum. The
   money arrives either way.

**Who may pay** (`pay-accept-from`): everyone, friends and teammates, friends, or nobody, answered by
`services.relations()` (friends from the friends feature, teams from the teams feature). Ignored players can never
pay (unless they can't be ignored: `siftcore.chat.unignorable`, staff). "Friends and teammates" is offered while the
server has friends or teams (with teams only it means teammates); "friends" only with a friends system. An option
that is not offered reads as nobody (never more open than the player chose). There is no staff bypass: "nobody" means
nobody, and staff who must move money use `/eco`.

**While you were away** (`pay-join-summary`): a moment after joining, a player who was paid while offline sees
"While you were away you received $300:" and one line per payer, the largest first ("Alex paid you $300 in 2
payments", at most 5, then "and 2 more players"), with the notify sound. It counts incoming `pay` ledger rows after
the end of the last session (`PlayerDirectory.previousSeen`), so payments seen live are not repeated; nothing on a
first join. One indexed read of the ledger (`PaymentsAway`).

## The leaderboard

`BalanceTop` rebuilds the money and shard leaderboards off the world threads every `baltop.refresh` (60s) from the
ledger in memory: the top `baltop.size` (100) entries and every positive balance sorted for a player's place. Players
who chose "Hide me from leaderboards" (`hide-from-leaderboards`, needs `siftcore.stats.hide`) are left out of the
list and of the places: they take no place from anyone, have none themselves (`baltop_rank` is 0, no "You are
number ..." line, "-" on the Money page), and `EconomyApi.top` leaves them out too. Who is hidden is read at each
rebuild (`HiddenAccounts`): online players' values with their permissions, everyone else's stored choice from the
`settings` table (one read); when that read fails the last list is kept. A change shows at the next rebuild.

## Player settings (`/settings`, Money & selling)

Registered by `EconomyFeature.registerSettings` in `SettingCategories.ECONOMY`, between the selling settings
(`sell.md`), at their catalog places. Text: `lang/economy.yml` `economy.settings`; the option names are the shared
ones of `lang/settings.yml` (plus "Any amount").

| Order | Id | Kind, default | Offered while | Read in | Effect |
|---|---|---|---|---|---|
| 2 | `pay-notifications` "Payment alerts" | choice chat/actionbar/off, chat | always | `PayService.execute` (`PayRules.alerts`, `Messenger.alert`) | How the receiver is told. Was a switch: old rows on read as chat, off as off. |
| 6 | `pay-confirm-above` "Confirm payments from" | choice server/always/1k/10k/100k, server | always | `PayService.proceed` (`PayRules.asks`) | From which amount `/pay` asks the payer, never looser than `pay.confirm-above`. |
| 7 | `pay-accept-from` "Who can pay me" | choice everyone/friends-team/friends/nobody, everyone; no placeholder | friends-team with friends or teams, friends with a friends system | `PayService.proceed` and `execute` (`PayRules.accepts`, `Relations`) | Who may pay the player; ignored players never. Offline receivers through `PlayerSettings.lookup`. |
| 8 | `pay-join-summary` "Offline payments summary" | switch, on | `pay.allow-offline-targets` | `EconomyFeature.onJoin` | The "While you were away" lines. |
| 9 | `pay-alert-minimum` "Only alert payments from" | choice any/100/1k/10k/100k, any | always | `PayService.execute` (`PayRules.alerts`) | Payments below it arrive without an alert. Presets are read with the default money suffixes, so their ids never change. |

Shared settings it acts on (declared with `settings().reads(...)`, defined in `SharedSettings`):
`balance-privacy` (`/balance <name>`, Privacy) and `hide-from-leaderboards` (the money leaderboard, Privacy). The
stats feature reads them for `/stats` and the stat leaderboards.

## Config (`features/economy.yml`)

| Key | Default | Meaning |
|---|---|---|
| `pay.minimum` | 1 | Smallest payment |
| `pay.confirm-above` | 100k | Payments of at least this ask first (0: never; players can ask sooner) |
| `pay.cooldown` | 2s | Time between two payments by one player |
| `pay.allow-offline-targets` | true | Money can be sent to offline players (and the offline payments summary is offered) |
| `pay.daily-limit.enabled` / `base` / `per-hour-played` / `maximum` | true / 250k / 50k / 100m | The daily limit, growing with time played; `siftcore.pay.unlimited` has none |
| `baltop.refresh` / `size` | 60s / 100 | The leaderboard |
| `page-size` | 10 | Lines per page in `/baltop` and `/eco history` |

## Placeholders

`balance`, `balance_exact`, `balance_number`, `balance_raw`, `shards`, `shards_raw`, `baltop_rank` (0 when unranked
or hidden), `baltop_name_<rank>`, `baltop_value_<rank>` (see `docs/placeholders.md`). Placeholders show the player's
own money; `balance-privacy` is about other players asking.

## Self-tests

`pay limit formula`, `money format round trip`, `leaderboard is built`, `Vault economy is SiftCore's`, `payment
settings are in Money & selling`, `payment rules` (the server's confirm amount, a lower personal amount, ignored and
friends-only payers, the alert minimum).

## Tests

Unit: `EconomyLogicTest` (the daily limit; `PayRules`: confirmation never looser than the server, who may pay with
ignores and staff, alerts and minimum, the away summary; `HiddenAccounts.decide`), `EconomySettingsTest` (group and
catalog order, the shared vocabularies, old `pay-notifications` rows, friend options reading as nobody without
friends, the summary offered only with offline payments), `EconomyStoreTest` (real SQLite ledger: payments while away
summed per payer, only incoming `pay` money after the moment; hidden accounts leave the leaderboard list and places),
`EconomyTextTest`.

End to end (`tools/e2e`): `Scenarios` (`pay`, `pay-form`, `pay-ignored`: an ignored payer is refused until unignored,
`double-submit`, `forged-clicks`, `baltop`, `menu`, `feedback`), `VaultScenarios` (`vault-economy`) and
`MoneyScenarios`: `money-settings-dialog` (the Money & selling group and its choices; payment alerts above the hotbar
from $1,000 set in the dialog: $100 arrives quietly, $1,000 shows above the hotbar and not in chat; back to chat
deletes the row), `money-pay-settings` (changed with `/settings <id> <value>`: a lower confirm amount, always, server
default; nobody refused in chat and in the form, also offline; the summary after rejoining, and none with it off; an
ignored payer refused),
`money-balance-privacy` (refused for others online and offline, shown to the player, the console and staff),
`money-leaderboard-hidden` (off the list and without a place, back without the permission or when turned off).

What needs a real client: how the alerts above the hotbar look next to other action bar lines, and the summary's
layout in chat.
