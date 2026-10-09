# SiftCore API

Other plugins on the server can read and change SiftCore money, read combat state, placeholders and ranks, and react
to what players do through events. Everything is in the package `net.siftvanilla.siftcore.api` of the SiftCore jar.

## Getting it

Depend on SiftCore so it is enabled first. In a `paper-plugin.yml`:

```yaml
dependencies:
  server:
    SiftCore:
      load: BEFORE
      required: true
      join-classpath: true
```

(or `depend: [SiftCore]` in a `plugin.yml`), compile against the SiftCore jar with scope `provided`, and fetch the
API in `onEnable`:

```java
SiftCoreApi siftcore = SiftCoreApi.get();   // from Bukkit's ServicesManager
```

`SiftCoreApi` is registered in the `ServicesManager` while SiftCore is enabled
(`Bukkit.getServicesManager().load(SiftCoreApi.class)` works too). `get()` throws `IllegalStateException` when
SiftCore is not running.

Every method is thread-safe and non-blocking: SiftCore runs on Folia/Canvas region threads, so you may call it from
any thread. Methods that change money return immediately; the change is applied in memory at once and stored
shortly after (see `TransactionResult#committed()`).

## What it offers

| Method | Gives |
|---|---|
| `version()` | SiftCore's version |
| `economy()` | `EconomyApi`: money and shards |
| `combat()` | `CombatView`: who is in combat (read-only) |
| `placeholders()` | `PlaceholderView`: SiftCore's placeholders without PlaceholderAPI (read-only) |
| `ranks()` | `RankView`: rank labels and primary groups (read-only; from LuckPerms when installed) |

### Economy

Balances are whole numbers (`long`) in two currencies, `Currency.MONEY` (shown as `$1,500`) and `Currency.SHARDS`.

```java
EconomyApi economy = siftcore.economy();
long money = economy.balance(player.getUniqueId(), Currency.MONEY);
boolean canPay = economy.has(player.getUniqueId(), Currency.MONEY, 500);

TransactionResult result = economy.deposit(player.getUniqueId(), Currency.MONEY, 1_000, "quest_reward", "quest-17");
if (result.success()) {
    result.committed().thenRun(() -> /* stored in the database */ { });
} else {
    // result.status(): INSUFFICIENT_FUNDS, BALANCE_LIMIT, REJECTED, CANCELLED or UNAVAILABLE; nothing changed
}
economy.withdraw(uuid, Currency.SHARDS, 50, "cosmetic_buy", null);
economy.transfer(from, to, Currency.MONEY, 2_500, "trade", "trade-881");
String shown = economy.format(Currency.MONEY, 2_500_000);   // "$2.5m"
List<EconomyApi.TopEntry> top = economy.top(Currency.MONEY, 10);
economy.history(uuid, 20).thenAccept(rows -> { /* newest first */ });
```

- `kind` names the reason in the ledger (lowercase, at most 32 characters, e.g. `quest_reward`); `/eco history`,
  `/sift audit` and the CSV export show it. `ref` is an optional id of yours (at most 64 characters).
- Amounts must be more than zero. Every change is one atomic ledger transaction: it either fully happens or nothing
  changes. `committed()` completes once it is durably stored; if storing fails it completes exceptionally and the
  change was undone in memory, so hand out anything valuable only after `committed()`.
- Plugins that use Vault get the same money: SiftCore registers the classic `net.milkbowl.vault.economy.Economy` and
  the VaultUnlocked `net.milkbowl.vault2.economy.Economy` when VaultUnlocked is installed. Vault amounts are rounded to
  whole dollars in the server's favour (money paid out rounds down, money taken rounds up), and each change is
  recorded with the kind `vault_<plugin>`.

### Combat

```java
CombatView combat = siftcore.combat();
if (combat.tagged(uuid)) {
    Duration left = combat.remaining(uuid);
    Optional<UUID> attacker = combat.lastAttacker(uuid);
}
```

A tagged player can't teleport, use most menus or log out safely (they die and the last attacker gets the kill).

### Placeholders

```java
PlaceholderView placeholders = siftcore.placeholders();
Optional<String> balance = placeholders.resolve(player, "balance");          // "$1,500"
String line = placeholders.apply(player, "Kills: %siftcore_stats_kills%");   // unknown names stay as they are
Map<String, String> all = placeholders.available();                          // name -> description
```

The same values are offered to PlaceholderAPI as `%siftcore_<name>%` when it is installed. The full list is in
`docs/placeholders.md` (`/sift docs` regenerates it).

### Ranks

```java
String label = siftcore.ranks().label(uuid);   // "Baron", empty for the default group or without LuckPerms
String group = siftcore.ranks().group(uuid);   // "baron", "default" when unknown
```

Labels are plain text: SiftCore removes colour codes and tags from LuckPerms display names and meta values. A rank's
colour (the `siftcore-rank-color` or `siftcore-rank-gradient` meta, see `docs/features/integrations.md`) is available
as the placeholder `rank_color` (`#RRGGBB`, empty without one).

## Events

All events are in `net.siftvanilla.siftcore.api.event` and extend `SiftEvent`. An event is synchronous when fired
from a world thread (the main thread on Paper, a region thread on Folia/Canvas) and asynchronous otherwise, so check
`isAsynchronous()` and use the entity or region scheduler before touching world state. Cancellable events extend
`SiftCancellableEvent`: cancelling stops the action before anything changes.

| Event | Cancellable | Fired | What cancelling does |
|---|---|---|---|
| `EconomyTransactionEvent` | yes | before every money or shards transaction (payments, sales, purchases, taxes, refunds, rewards, admin changes, Vault and API calls), on the caller's thread | the transaction fails with status `CANCELLED`; nothing changes |
| `EconomyTransactionCommittedEvent` | no | asynchronously, after a transaction was durably stored; carries the postings and the balances after | |
| `PlayerPayEvent` | yes | before `/pay` moves money | the payment is stopped |
| `ItemSellEvent` | yes | before `/sell` (hand, all, menu) takes items and pays, on the player's thread; has the items, total and multiplier | the items stay where they are |
| `SellMasteryLevelEvent` | no | after a stored sale raised the player's sell mastery level in a category, on the player's thread | |
| `ShopPurchaseEvent` | yes | before a server shop purchase, on the player's thread | nothing is charged |
| `ShardShopPurchaseEvent` | yes | before a shard shop purchase, on the player's thread | no shards move |
| `OrderCreateEvent` | yes | before a player places a buy order, before any money is held | the order is not placed; nothing is charged |
| `OrderEditEvent` | yes | before the owner raises an order's price or quantity, before the extra money is held | the order stays as it is |
| `OrderFillEvent` | yes | before items are delivered to a buy order, before they leave the seller | that delivery stops; the items stay, no money moves |
| `OrderCancelEvent` | yes | before the owner or staff cancel a buy order, before the refund (expiry fires no cancel event) | the order stays open |
| `OrderEndEvent` | no | after an order ended early (cancelled or expired) and its refund was stored | |
| `OrderCollectEvent` | no | after the owner collected delivered items and the collection was stored | |
| `AuctionListEvent` | yes | before an item is listed on the auction house, before it leaves the inventory | the listing is not made |
| `AuctionPurchaseEvent` | yes | before an auction listing is bought | the purchase is stopped |
| `CrateOpenEvent` | yes | after the reward was drawn and before the key is spent, on the player's thread | the key is kept, nothing is given |
| `KeyallEvent` | yes | before a keyall hands out keys (global thread for scheduled ones) | nobody gets keys this time |
| `SpawnerPlaceEvent` | yes | before a player places a SiftCore spawner, on the player's thread | the block is not placed; the item stays in hand |
| `SpawnerStackEvent` | yes | before a player adds spawners to a placed stack, on the player's thread | the items stay in hand |
| `SpawnerBreakEvent` | yes | before a player picks up a placed spawner (after access, silk touch and storage checks) | the spawner stays with its stack and storage |
| `SpawnerSellEvent` | yes | before the loot stored in a spawner is sold, after pricing, on the player's thread | the loot stays in the spawner |
| `AfkStatusChangeEvent` | no | after a player became AFK or came back (player's thread, or the chat thread when chatting brought them back) | |
| `AfkZoneRewardEvent` | yes | before a player in the AFK zone is paid shards for an interval, on the player's thread | this payment is skipped; they keep earning towards the next |
| `StoreDeliveryEvent` | yes | before a store purchase (`/sift store ...`) is delivered, on the command's thread | nothing is delivered or recorded; the store may deliver the same reference later |
| `TeamCreateEvent` | yes | before a team is created and paid for | nothing is charged |
| `TeamJoinEvent` | yes | before a player joins a team (with the cause) | they stay out |
| `TeamLeaveEvent` | yes | before a player leaves or is removed (with the reason) | they stay in |
| `TeamDisbandEvent` | yes | before a team is disbanded | the team stays |
| `CombatTagEvent` | yes | before a player is tagged (once for the hit player, once for the attacker), on the hit player's thread | that player's combat state is unchanged; the hit still counts |
| `CombatLogEvent` | yes | when a tagged player leaves, before the punishment (listeners may change the punishment) | they leave without punishment |
| `PlayerKillCreditEvent` | yes | when a kill passed the anti-farm rules, on the victim's thread | the kill is not counted (no streak, no bounty) |
| `BountyPlaceEvent` | yes | before money is put on a player's head | nothing is paid |
| `BountyClaimEvent` | yes | before a killer is paid a bounty | the bounty stays |
| `TeleportRequestEvent` | yes | before a `/tpa` or `/tpahere` request reaches its target | the sender is told they can't send one |
| `PrivateMessageEvent` | yes | before a `/msg` or `/r` message is delivered, after mutes, ignore lists, the receiver's setting, anti-spam and the filter passed | the message is not delivered; the sender is told they can't message that player |
| `RandomTeleportEvent` | yes | after random teleport found a spot, before charging and teleporting, on the player's thread | nothing is charged |

Higher-level events (for example `AuctionPurchaseEvent`) fire before the `EconomyTransactionEvent` of the same action,
with more context. Transactions that only record internal state (claim box bookkeeping, refunds that must not be
blocked) fire no economy event.

Example:

```java
@EventHandler
public void onStore(StoreDeliveryEvent event) {
    if (event.kind() == StoreDeliveryEvent.Kind.RANK) {
        getLogger().info(event.player() + " bought " + event.item() + " (" + event.ref() + ")");
    }
}
```

## Admin and store integration

Web stores deliver purchases with console commands (`/sift store money|shards|keys|rank ... <ref>`); each reference is
delivered at most once. See `docs/features/integrations.md` for those commands, backups, exports and the audit log,
and `docs/permissions.md` for every permission node.
