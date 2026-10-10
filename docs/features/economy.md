# Money (`economy`)

Balances, `/pay` with confirmation and a daily limit, the money leaderboard, the staff tools and the public API. The
money engine itself (the ledger, `economy/Ledger`) is core; this feature is how players use it. Package
`feature/economy`, config `features/economy.yml`, text `lang/economy.yml`. No tables of its own: it reads the ledger
(`ledger`) and the `settings` table.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/balance` (`/bal`, `/money`) | `siftcore.command.balance` (everyone) | Your money (green) and shards (purple, the amount and the word). |
| `/balance <name>` | `siftcore.command.balance.others` (everyone) | Another player's money and shards, when their "Who can see my balance" (`balance-privacy`) lets you; otherwise "Alex keeps their balance private." The console, staff with `siftcore.admin.eco` and the player themselves always see it. An offline player's choice is read from storage (`PlayerSettings.lookup`). |
| `/pay` | `siftcore.command.pay` (everyone) | The pay form (player and amount); refusals come back in the form with what was typed. |
| `/pay <name> <amount>` | `siftcore.command.pay` | Pays at once, or asks first (see Confirmation). |
| `/baltop [page]` (`/balancetop`, `/moneytop`) | `siftcore.command.baltop` (everyone) | The richest players, laid out like every leaderboard (the stats boards, `/sell top`): the player's own place ("You are number 3 with $5,000.") and how many are listed ("Top 100, the richest first."), then one button per place, "1. Alex $5,000", the player's own name highlighted. One dialog without pages (it scrolls), at most `baltop.size` (100) places. The console gets chat lines, `page-size` at a time (the page argument is for the console). |
| `/eco give\|take\|set <name> <amount> [money\|shards]` (`/economy`) | `siftcore.admin.eco` (operators) | Adds, removes or sets a balance (money unless `shards` is named; `set` re-checks the balance inside the transaction, ledger kinds `admin_give`, `admin_take`, `admin_set`). Console too. Audited as `eco.give`, `eco.take`, `eco.set`. |
| `/eco history <name> [page]` | `siftcore.admin.eco` | The player's ledger rows, newest first (`page-size` per page): transaction id, amount, kind, how long ago and the balance after. |
| `/eco resume` | `siftcore.admin.eco` | Takes the economy out of read-only mode after storage failures (the ledger turns read-only after five failed writes). Audited as `eco.resume`. |

Other nodes: `siftcore.pay.unlimited` (operators) removes the daily pay limit, and `siftcore.bypass.cooldown`
(operators) skips the `pay.cooldown`.

The main menu's Money page (`money`, order 10) shows the balance and the shards (purple), with two buttons whose
tooltips carry the rest (the dialog style, `docs/development.md`): Pay a player ("Send money to another player.", and
"You can still send $x today." for players with a daily limit) and Richest players ("The 100 richest players.", and
"You are number 4." for a player on the leaderboard). When the day's total can't be loaded, a red line says so.

## Paying

Every payment runs on the payer's thread:

1. **Checks without storage**: not yourself, the receiver may be offline (`pay.allow-offline-targets`), at least
   `pay.minimum`, the economy is available, enough money. Then the cooldown (`pay.cooldown`).
2. **One step with storage**: the day's total sent (`PayLimits`, loaded once a day per player) and whom the receiver
   accepts payments from (`pay-accept-from`, from memory while they are online, otherwise one settings read) load
   together. A receiver who doesn't accept this payer: "Alex doesn't accept payments from you." (in the form when it
   came from the form). Over the daily limit: "You can send $x more today ...".
3. **Confirmation** when the amount reaches the server's `pay.confirm-above` (100k), or the payer's own lower amount
   ("Confirm payments from": always, $1k, $10k or $100k; never looser than the server). The dialog asks "Send
   $150,000 to Alex?" with the exact amount and what is left today; "Payments can't be undone." is the Pay button's
   tooltip. The pay form's Submit says in its tooltip that big payments ask first.
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
number ..." line, none in the Richest players tooltip), and `EconomyApi.top` leaves them out too. Who is hidden is read at each
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
| `pay.daily-limit.enabled` / `base` / `per-hour-played` / `maximum` | true / 250k / 50k / 100m | The daily limit of `/pay`, growing with time played; `siftcore.pay.unlimited` has none. It covers `/pay` only (see below) |
| `baltop.refresh` / `size` | 60s / 100 | The leaderboard |
| `page-size` | 10 | Lines per page of `/baltop` in the console and of `/eco history` (players get the whole list in one dialog) |

### What the limit covers

The daily limit applies to `/pay` only. Buying another player's auction listing, filling their buy order and claiming
a bounty also move money between players, at any price the players agree on, and since player trades carry no fee
(auction and order tax 0, bounty claim tax 0) they cost nothing either. So a payment `/pay` refuses can still reach the
other account through a listing priced at the amount. This is an accepted policy gap (dupe audit R14, option A): no
money is created, every such trade is in the ledger and the audit log (`/eco history`, `/auction` history, the order
fills), and staff can see it there. Should alt-account money moving become a problem, the audit's option B (one shared
daily budget across `/pay`, auction buys, order fills paid to one seller and bounties between related accounts) is the
fix; it was not done because it would also limit what new players can buy on their first day.

## Placeholders

`balance`, `balance_exact`, `balance_number`, `balance_raw`, `balance_server`, `shards`, `shards_raw`, `baltop_rank`
(0 when unranked or hidden), `baltop_name_<rank>`, `baltop_value_<rank>` (see `docs/placeholders.md`). Placeholders
show the player's own money; `balance-privacy` is about other players asking.

`balance`, `balance_number` and `baltop_value_<rank>` are written in the "Money format" (`money-format`, Display) of
the player PlaceholderAPI asks for: $1,234,567 in full, $1.2m short, $1.23m the server's way. That player is the one
who sees the line on the SiftCore sidebar and in TAB's header and footer, but the one the line is about in TAB's tab list
names, nametags and below-name lines and in chat plugins' formats, where everyone then sees that player's choice. For
one format for everyone there use `balance_server` (always the server's way), `balance_exact` (always every digit) or
`balance_raw` (the plain number). Without a player (holograms, plugins asking for no one) they are the server's way;
for a player who is offline they follow the server's default (or lock) for `money-format`. The other features' money
placeholders (bounties, orders, sell, stats) follow the same rule.

## Money format

Every amount of money a player reads follows their "Money format" setting (core, see `docs/features/settings.md`):
`/balance`, payment receipts and alerts, the money page and `/baltop`, the leaderboard lines, the away summary. The
payment confirmation keeps every digit (its key is a confirmation), as do the exact prices of other features and the
buttons that pay or charge at once. The money page (opened after the day's pay total loads), the away summary and the
pay form's daily-limit refusal (after the day's total loads) are rendered for their player. "In full" is not offered
while `currency.compact-from` is 0, and "Short" while the server already shortens from `k` with one decimal: they
would change nothing.

## Integration

- Provides `EconomyFeature#economy()` (`EconomyService`, the public `EconomyApi`): the integrations feature exposes it as
  `SiftCoreApi#economy()`, and stats reads money earned and the money leaderboard from it.
- **Vault.** When VaultUnlocked (plugin name `Vault`) is enabled, `enable()` registers SiftCore's money as the
  server's Vault economy, both the classic and the modern interface (`integration/vault`, `VaultHook`); whole dollars,
  rounding in the server's favour, one ledger transaction of kind `vault_<plugin>` per call. Details:
  [architecture](../architecture.md#vault). The self-test `Vault economy is SiftCore's` fails when another economy
  plugin is registered above it; `/sift integrations` shows the state.
- Consumes `IgnoreLookup` (chat, installed with `EconomyFeature#ignores` once chat is built, because economy is built
  first) and `services.relations()` for "Who can pay me".
- Events: `api.event.PlayerPayEvent` (cancellable, before a payment moves money); every transaction also fires the
  ledger's `EconomyTransactionEvent` and, once stored, `EconomyTransactionCommittedEvent` (see [api](../api.md)).

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
`money-leaderboard-hidden` (off the list and without a place, back without the permission or when turned off),
`money-dialogs` (`/balance` with purple shards; the Money page's body is the balance and shards, the limit left and the
place are in the Pay and Richest players tooltips; `/baltop` with the own place first, every place and no page
buttons; the pay confirmation's question with "can't be undone" in the Pay tooltip),
and `MoneyFormatScenarios`: `money-format-views` (the Display page's three formats with their samples, changed in the
dialog and with `/settings money-format short`; one balance of $1,234,567 read three ways in `/balance`, the sidebar,
the money page, the shop menu and the `balance`, `balance_number` and `baltop_value_1` placeholders, `balance_exact` exact for
all; the Richest players dialog opened by a click on the money page and by `/baltop` in each player's format) and `money-format-receipts` (a short-format payer's confirmation shows $123,456 and what is left exactly; their
own receipt is short; the same "paid you" receipt reads $123,456 for a receiver in full and $123.4k for one in short;
one bounty announcement reads $60,000 and $60k to two watchers) and `money-format-later` (screens built after a wait,
for one player: the chat hover card's balance per reader, `/stats` of an offline player after the database read, a
crate's $1,234,567 reward in the result dialog and its chat receipt, in full and short; a short reader's sale
confirmation with every digit in the body and on the Sell button, and a short receipt; `balance_server` the server's way;
with `compact-from: 0` the Display page offers only the server's way and short, a stored "in full" is kept and reads
every digit, and all three come back after the reload).

What needs a real client: how the alerts above the hotbar look next to other action bar lines, and the summary's
layout in chat.
