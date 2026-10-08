# Buy orders (`orders`)

A player asks for a number of items at a price each. The money for the whole order is held right away; other
players deliver matching items and are paid from that money (less a tax); the buyer collects the items. Package
`feature/orders`, config `features/orders.yml`, text `lang/orders.yml`, tables `orders` and `order_fills` (V003)
plus migrations V020-V023 (below). The held money lives in the system account `ORDERS_ESCROW`.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/orders` (`/order`, `/buyorders`) | `siftcore.command.orders` (everyone) | Opens the orders browser |
| `/orders <search>` | `siftcore.command.orders` | The browser searching item names; suggests items that have open orders |
| `/orders create` | `siftcore.orders.create` (everyone) | The new-order form |
| `/orders create <item> <quantity> <price>` | `siftcore.orders.create` | Straight to the confirmation (chat-only or older clients); item suggestions leave out what can't be ordered |
| `/orders mine` | `siftcore.command.orders` | Your orders (active, and ended ones still holding items) |
| `/orders history` | `siftcore.command.orders` | Your past orders; from there your deliveries |
| `/orders history <player>` | `siftcore.admin.orders` (op) | Someone else's past orders (offline players too) |
| `/orders deliveries` | `siftcore.command.orders` | The deliveries you made to other players' orders |
| `/orders order <id>` | `siftcore.command.orders` | Opens one of your orders (used by clickable messages); staff get the staff dialog |
| `/orders admin list [player\|item]` | `siftcore.admin.orders` | Orders in memory, active first, filtered by owner or item |
| `/orders admin info <id>` | `siftcore.admin.orders` | One order, from memory or storage |
| `/orders admin cancel <id> [reason]` | `siftcore.admin.orders` | Cancels and refunds; the owner is told with the reason (audited as `orders.cancel`) |
| `/orders admin check` | `siftcore.admin.orders` | The consistency check (below) |
| `/orders admin history <player>` | `siftcore.admin.orders` | A player's past orders in chat |
| `/orders admin expire` | `siftcore.admin.orders` | Runs the expiry check now |

Every staff command works from the console. `/orders` uses the `orders` entry of `commands.yml` for its cooldown and
aliases.

Active-order limit: `siftcore.orders.limit.<n>` (numeric nodes on LuckPerms groups, the highest wins, read with
`Limits.highest`), otherwise `limits.active-orders` (3). `siftcore.orders.limit.unlimited` removes the limit; it is
declared with default `false`, so nobody (not even ops) has it unless granted. Only active orders count; finished
ones never do. Suggested rank values: default 3, supporter 5, patron 7, elite 10, legend 15.

## What an order takes: the plain rule and variants

An order takes one exact kind of item and only that. Deliveries are matched with `isSimilar` against a canonical
prototype (`OrderItem`), and the buyer collects copies of that prototype, so nothing an item carried can be lost or
duplicated on the way.

- **Plain items**: the type's default item. A custom name, lore, enchantments, damage, an anvil repair cost, custom
  data (any plugin's), container contents, trims, dye, banner patterns or potion contents all make a stack a different
  item, which is not accepted and goes back to the player. `blocked-items` adds types nobody may order at all (items
  survival players can't get, and items whose worth is in data a plain item never has: written books, filled maps,
  suspicious stew, firework stars, every spawn egg).
- **Enchanted books** (`books.enabled`): exactly one stored enchantment at one level and nothing else. Books combined
  in an anvil (they carry a repair cost) or with an extra enchantment do not match, so no extra enchantment is lost to
  an order and anvil costs can't be laundered. Curses only with `books.allow-curses`. Multi-enchantment books and
  enchanted tools or armour can't be ordered.
- **Potions** (`potions.enabled`): potion, splash potion, lingering potion or tipped arrow of one base potion type
  (water bottles only as that explicit type).
- **Spawners** (`spawners.enabled`): a SiftCore spawner of one mob through `core.link.SpawnerItems` (the spawners
  feature's `items()`), variant `spawner:<mob>`, prototype `SpawnerItems.create(mob, 1)`, matched with `isSimilar`,
  which includes the spawner feature's own item identity in the item's persistent data: a vanilla spawner, one renamed
  to look like it, or another mob's spawner never matches. The buyer collects real SiftCore spawners. Offered only
  while the provider has mobs (the browser and the picker show a Spawners filter then); plain spawners are always
  blocked. Spawner items made before the spawner feature's name or lore text was changed in its lang file no longer
  match new orders' prototypes.

Order keys: a plain item is its type key (`minecraft:diamond`); a variant adds the variant id
(`minecraft:enchanted_book|enchant:minecraft:mending:1`). The variant is stored in `orders.variant` (null for plain).
An order whose variant can't be built any more (an enchantment removed by a data pack, a spawner mob no longer
provided) is kept and can be cancelled, but refuses deliveries ("That order can't take deliveries right now.") and is
reported by `/orders admin check` and the self-test.

Shulker boxes: a single shulker box is opened for matching items (in the delivery grid, by quick deliver and by
"Fill from inventory"); a stacked box is never opened, and every other item in a box stays exactly where it was.

## Placing an order

The form (`/orders create` or New order in the browser): item (an item key or English name, `mending book` or
`sharpness 4 book` for books), quantity and price each. The held item fills it in; the price field gets a suggestion
that leaves sellers `pricing.suggest-margin` more than the server pays after the tax:
`ceil(worth x (1 + margin) / (1 - tax))`, empty for items the server does not buy.

- Quantity accepts `1500`, `1,500`, `1.5k`, `2m`, `3 stacks`/`3 stack`/`3st` (times the item's stack size) and
  `1 shulker`/`2 shulkers`/`2sb` (27 stacks each), capped by `limits.max-quantity`. Exponents, hex and anything else are
  refused. The confirmation shows the resolved number.
- Choose item opens the item picker: every orderable item plus the variant families, filter by category, sort by name
  or most ordered (orders placed per item in the last 30 days, read at startup and hourly), search; each icon shows what
  the server pays and the open orders with the best price. A family opens a list (enchantment, then level unless the
  maximum is I; potion type; mob). Everything typed is kept per player for 10 minutes (forgotten on quit), so leaving
  the form for the picker never loses input.
- The confirmation shows the item, quantity, price each, the total held, the duration, what the server pays for the
  item and, when `price x (1 - tax)` is not more than the worth at the best rank multiplier, the warning that players
  get more from /sell. Confirming re-checks everything, fires `OrderCreateEvent`, and runs one transaction
  (owner to `ORDERS_ESCROW`, kind `order_escrow`, ref `order:<id>`) whose check counts the owner's active orders under
  the economy lock.
- Limits: `limits.min-price` per item, `limits.max-quantity`, `limits.max-total` per order, and the optional
  `pricing.min-vs-worth` (percent of the worth, a floor) and `pricing.max-vs-worth` (multiple of the worth, a ceiling
  against $1m cobblestone orders between alts).

Big orders (at least `announce.min-total`) are announced in chat at most once per `announce.cooldown` per owner,
clickable to `/orders <item>`, to players who did not turn announcements off and don't ignore the owner
(`IgnoreLookup`). Vanished owners are never announced.

## The browser and delivering

`/orders` (`PagedMenu`): every open order. Entry: the item (as many as are still wanted, at most one stack) with
`Price each`, `You get <net> each after <tax>% tax`, `Delivered <filled> of <quantity>`, `Ordered by`, `Ends in`,
`Server pays <worth> each` (when the server buys it), `You carry <n>` (plain items including box contents, counted once
per redraw) and the click hints. Bottom row: 47 sort (highest price each, highest total, most wanted, newest, ending
soon), 48 filter (all, and each category with orderable items), 49 search, 50 New order (`You have <count> of <limit>
active orders`), 51 Your orders (items waiting), 52 History. Sort and filter are remembered per player (`settings`
rows `orders_sort`, `orders_filter`).

- **Click** someone else's order: the delivery menu. Rows 1-5 are a grid for items (shift click, drag or 51 "Fill from
  inventory", which moves matching stacks and boxes holding matching items, whole, up to what is still wanted); 46
  back, 48 the order and the item rule, 50 Deliver (`Accepted <amount> of <remaining> still wanted (<inner> from shulker
  boxes)` and the payout after tax; what is not accepted is counted). Deliver takes the plain stacks first, then the
  boxes' contents (each box is swapped for a rebuilt copy), saves the player, then runs the fill; everything else goes
  back, as does the whole grid when the menu closes (also on quit and shutdown). A player who dies with the menu open
  and does not keep their inventory drops the grid with the rest of the death drops, where they died: the death event
  moves the grid into the drops, because the menu closes only after the death drops were made (giving it back then
  would lose it). So the menu can't keep items safe from a death. A price change after the menu opened refuses the
  delivery ("That order changed.").
- **Right click**: quick deliver, a dialog with what you carry (and how much of it in boxes), what is still wanted,
  the payout after tax and, when the server pays you more, that /sell pays more. Confirming re-reads the inventory and
  the order: if the count, price or remaining amount changed, the dialog comes back with "That order or your inventory
  changed. Check, then deliver." Otherwise the items are taken with snapshot checks per slot, the player is saved, and
  the fill runs; a refusal restores the exact slots.
- **Click your own order**: the owner dialog (below). **Shift right click** (staff): the staff dialog.

A fill is one transaction: escrow to seller (`order_fill`), the seller pays the tax to the sink (`order_tax`), and a
check under the economy lock refuses it when the order is gone, not active, the seller's own, at another price,
expired, can't be built, or wants fewer items (and, with `refuse-same-ip`, when the owner shares the seller's address
hash). The order's row is updated with a guarded `UPDATE` and an `order_fills` row (with its source `menu`, `quick` or
`sell`) is inserted in the same database transaction. Concurrent deliveries can't overfill: the first transaction
wins and the rest are refused for the count.

## Your orders

`/orders mine`: active orders and ended ones still holding items, newest first; 50 New order, 51 Collect all (one
transaction with a guarded update per order, collecting what fits from each order, newest first), 52 Past orders.

The owner dialog: Collect items (what fits), Collect one stack, Send the rest to my claim box (what fits goes to the
inventory and the rest into the claim box in the same transaction), Raise price and Add more (one form: a new price
each, which must not be lower, and items to add; the confirmation says `Hold <extra> more for this order?` where
`extra = (newQuantity - filled) x newPrice - held`), Extend (free; to `min(created + extend.max-lifetime, now +
duration)`), Cancel order (confirmation; everything held comes back, delivered items stay collectable), Order again
(for ended orders), Details (order id, age, paid out, latest deliveries).

Collecting changes only the count, in a transaction; the items are handed over on the owner's thread after the commit,
and anything that no longer fits goes back into the order (or, if that can't be stored, the claim box). Nothing is
ever dropped on the ground.

An order closes (leaves memory, stays in storage as history) when it is complete, cancelled or expired and every
delivered item was collected.

## History

- Past orders (`/orders history`): the owner's ended orders, newest first, at most `history.max-entries`, read from
  storage when opened. Lore: state, delivered, price each, paid out, refunded, ended ago. Clicking one orders the same
  again (item, quantity, price) through every normal check.
- Your deliveries (`/orders deliveries`): `order_fills` joined with `orders` by seller: item, amount, earned after
  tax, buyer, time ago; the header shows `You earned <total> from <count> deliveries`.
- `history.keep` (0: forever) purges closed orders, their fills and notices older than that once a day.

## Telling owners

Online owners are told when items arrive (unless they turned order messages off), when an order completes, expires
(always), is cancelled by staff (always, with the reason) or ends within `expiry-warning` (once per order, clickable to
the order). Offline owners get a row in `order_notices` instead (additive upsert per owner, order and kind, best effort:
the money is always in the ledger). Two seconds after joining, the owner gets one message: "While you were away: <n>
items were delivered to your orders, <c> completed, <refund> came back from ended orders.", up to four detail lines,
"and <n> more", the waiting-items reminder (`join-reminder`) and a clickable "Open /orders". Shown rows are deleted;
a row that grew meanwhile is kept for next time.

## Staff

Staff (`siftcore.admin.orders`) shift right click any order in the browser: owner, item, delivered of quantity,
money held, placed and ends, state and the last five deliveries, with Cancel and refund (a form with a required reason
of at most 64 characters, then a confirmation) and Open <owner>'s orders. The cancel writes
`AuditLog.record(staff, "orders.cancel", owner, "#<id> refund $<amount>: <reason>")` and tells the owner (now, or
when they next join). There is no delete: delivered items always stay collectable.

## Selling into orders (`core.link.OrderMarket`)

`OrdersFeature#market()` implements `OrderMarket` for the sell feature's routing (spec 1, S8-S10):

- `bids(seller, key)`: active, unexpired orders for the key, best price each first, then oldest, never the seller's
  own (and none of a related account with `refuse-same-ip`), from an immutable index rebuilt lazily when the book's
  `revision()` moves (every put, update and remove bumps it).
- `usable(player)`: permission `siftcore.command.orders` and the combat block. `approve` fires `OrderFillEvent` with
  source `SELL` per take (a take for more than the order still wants is left out). `contribute` adds one fill per order
  (takes for the same order are merged) to the sale's own transaction through the same `OrderEngine#addFill` the
  delivery menu uses, so any refusal fails the whole sale with nothing applied. It never throws at a caller that
  already took the items: takes it can't fill as given (one order at two prices, more units than an int) add a check
  that refuses the transaction instead. `committed` tells each owner once ("<name> sold <amount> <item> to your
  order."; one line per owner when a sale filled several of their orders; offline owners get notice rows).
- `openOrderForm(player, key, back)`: the worth details' "Order it" button opens the new-order form with the item set
  (typed quantity and price kept); false, with a message, when the player can't order that item now.
- Refusals: every check in the engine fails with a reason `order_<name>`, the format of `OrderMarket.Refusal`, so a
  sale recognizes an order refusal and retries with fresh bids. A fill refused because the order's item can't be built
  uses `order_not_active`, and one refused by `refuse-same-ip` uses `order_own_order` (fresh bids leave both orders
  out); the delivery menu and quick deliver still show the precise message from their own checks.

`core/link/OrderMarket.java` and `core/item/*` are byte-identical to the sell branch's versions, so the two features
merge without conflicts. Wiring at integration (the orders feature is built after selling because it prices with
`sell.worth()`):

```java
AtomicReference<OrderMarket> orderMarket = new AtomicReference<>(OrderMarket.NONE);
SellFeature sell = new SellFeature(this.services, this.problems, this.combatTags, orderMarket::get);
...
OrdersFeature orders = new OrdersFeature(this.services, this.problems, this.combatTags, sell.worth(),
    () -> sell.worth().current().highestMultiplier(), spawners.items(), IgnoreLookup.NONE, staff.vanish());
orderMarket.set(orders.market());
```

## Placeholders

| Name | Value |
|---|---|
| `orders_active` | Your active orders |
| `orders_limit` | How many orders you may have at once (`unlimited` or a number; read on join and when you use orders) |
| `orders_waiting` | Delivered items waiting for you |
| `orders_held` | Money your active orders hold |
| `orders_open` | Active orders on the server |
| `orders_best_<item>` | Best price each of open orders for an item (`orders_best_diamond`), empty when none |
| `orders_wanted_<item>` | Items still wanted by open orders for an item |
| `orders_top_item_<n>`, `orders_top_price_<n>`, `orders_top_left_<n>`, `orders_top_owner_<n>` | The n-th biggest open order by money held (1-10), rebuilt every minute |

All read memory only. A display template for the displays feature (add to `features/displays.yml`):

```yaml
templates:
  top-orders:
    - "<icon:order> <primary>Biggest buy orders"
    - ""
    - "<secondary>1. <primary>{orders_top_item_1} <money>{orders_top_price_1} <secondary>x{orders_top_left_1}"
    - "<secondary>2. <primary>{orders_top_item_2} <money>{orders_top_price_2} <secondary>x{orders_top_left_2}"
    - "<secondary>3. <primary>{orders_top_item_3} <money>{orders_top_price_3} <secondary>x{orders_top_left_3}"
    - ""
    - "<secondary>Right click to see every order"
interaction:
  commands:
    top-orders: "orders"
```

## Main menu entry, settings and events

Hub entry `orders` (order 35, permission `siftcore.command.orders`), also the `orders` pause-menu entry.

Player settings: `order-notices` ("Order messages", default on: deliveries, completions and ending warnings; refunds and
staff cancels always show) and `orders_announce` ("Big order announcements", default on).

Events (`api.event`):

- `OrderCreateEvent(owner, item, variant, quantity, priceEach)`: cancellable, before any money is held.
- `OrderFillEvent(order, owner, seller, item, variant, amount, priceEach, tax, source)`: cancellable, before the items
  leave the seller; `source()` is `MENU`, `QUICK` or `SELL`.
- `OrderEditEvent(order, owner, oldPrice, newPrice, oldQuantity, newQuantity, extra)`: cancellable.
- `OrderCancelEvent(order, owner, itemType, refund, cause, reason)`: cancellable, owner (`OWNER`) or staff (`STAFF`).
  Expiry can't be blocked.
- `OrderCollectEvent` and `OrderEndEvent` (cancelled or expired, with the refund): after the commit, information only.

The ledger also fires `EconomyTransactionEvent` for create, fill, edit and cancel; expiry refunds are silent
bookkeeping. `order_fill` and `order_tax` count towards the stats' money earned like any other income.

## Config summary (`features/orders.yml`)

| Key | Default | Meaning |
|---|---|---|
| `duration` | `7d` | How long a new order stays open (30s-90d) |
| `expiry-check` | `30s` | How often ended orders are refunded (5s-1h) |
| `expiry-warning` | `12h` | Tell owners this long before the end (once per order; 0 off) |
| `limits.active-orders` | `3` | Active orders without a `siftcore.orders.limit.<n>` node (0: only ranks) |
| `limits.min-price` | `1` | Lowest price each |
| `limits.max-quantity` | `100000` | Most items one order asks for |
| `limits.max-total` | `100b` | Most money one order holds |
| `tax` | `2` | Percent the deliverer pays (0-50, decimals allowed), rounded down per delivery |
| `block-in-combat` | `true` | Tagged players can't browse, deliver, quick deliver, place, collect or sell to orders |
| `join-reminder` | `true` | Remind owners of waiting items on join |
| `refuse-same-ip` | `false` | Refuse deliveries and routed sales to orders of a player with the seller's address hash |
| `pricing.suggest-margin` | `10` | Percent over the server price the suggested price leaves sellers |
| `pricing.min-vs-worth` | `0` | Lowest price each as a percent of the worth (0 off) |
| `pricing.max-vs-worth` | `0` | Highest price each as a multiple of the worth (0 off, else at least 1) |
| `books.enabled` / `books.allow-curses` | `true` / `false` | Enchanted book orders; curses |
| `potions.enabled` | `true` | Potion orders by base type |
| `spawners.enabled` | `true` | Spawner orders (only with a spawner provider) |
| `history.max-entries` | `200` | Entries the history menus show (10-1000) |
| `history.keep` | `0` | Purge closed orders older than this daily (0 keeps them; else at least 1d) |
| `extend.enabled` / `extend.max-lifetime` | `true` / `30d` | Free extensions; the latest end after placing |
| `announce.min-total` / `announce.cooldown` | `1m` / `10m` | Announce orders holding at least this much; per owner |
| `blocked-items` | see the file | Item types that can't be ordered as plain items (`*` wildcards) |

Enchanted books, potions and spawners are switched with their own sections, never through `blocked-items` (their
plain forms are never orderable). Everything applies with `/sift reload`; a broken value is reported with its path and
falls back to the default.

## Storage

- V003 `orders`, `order_fills` (base).
- V020 `orders.ended` (when it stopped taking deliveries), `orders.refunded` (money that came back), `order_fills.source`
  (`menu`, `quick`, `sell`); expired orders get `ended = expires`.
- V021 `order_notices(owner, order_id, kind, units, amount, detail, created)`, primary key (owner, order_id, kind).
- V022 `orders.variant` (null for plain).
- V023 `orders.warned` (the ending warning was sent; reset by an extension).

Orders that are active or still hold items are kept in memory (`OrderBook`), loaded at startup and changed only inside
economy transactions. Every update of a row is guarded by the state it expects (count, price, quantity, state or end
time): if an earlier transaction failed to store, the guard fails, this one is rolled back as well, and memory and
storage never drift apart.

## Self-test and consistency check

`/sift selftest`: orders escrow equals open orders (the escrow account equals the money active orders hold, and the
book follows its own rules), bid index matches book, no variant order points at an unknown enchantment, every open
order loaded, memory matches storage (snapshot under the economy lock, compared on the ordered writer), tax and
suggested-price math, the expiry timer runs, only exact items match (renamed, damaged, enchanted and custom-data stacks
don't), book orders match exactly (the server's `isSimilar` agrees with the documented rule), spawner orders match only
SiftCore spawners (the provider's item of that mob, not a vanilla, renamed or other mob's spawner; passes trivially
without a provider), and a shulker rebuild keeps the other contents. `/orders admin check` runs the escrow, book and index checks, compares the stored total with the
escrow account and lists orders whose item can't be built.

## Testing

- Unit tests (`src/test/java/.../feature/orders`): the state machine against the real ledger and SQLite schema
  (escrow on create, partial and final fills, fills never exceed the quantity, concurrent fills can't overfill, exact
  refunds, double cancel harmless, expiry, collect and collect-all, edit and extend, storage reload, history and
  notices aggregation: `OrderEngineTest`), the market (bid order, own and ended orders skipped, revision, a guarded
  refusal rolling back a whole mixed sale, merged takes: `OrderMarketTest`), the book rule (`VariantMatchTest`),
  quantities and prices (`OrderInputTest`) and the browser's sorts and filter (`BrowserSortTest`).
- End to end (`tools/e2e`, `OrdersScenarios`): placing with the command, the form and the picker (typed input kept),
  quick deliver (including the changed-inventory re-check), shulker deliveries, Fill from inventory, raising the price
  and adding items (and the stale delivery menu), order again, collect all and the claim box, the history views, the
  join summary for offline owners, staff cancel with a reason, enchanted book orders with the exact rule, spawner
  orders through the spawner feature's items (`orders-spawner`), the browser's
  sort, filter and search, combat blocking, an order surviving a restart (`orders-persist-setup`, restart,
  `orders-persist-check`), and selling into orders (`orders-sell-routing`: an order above the server price takes the
  units first and its owner is told once, an order at or below it takes nothing, and orders filled by someone else
  between planning and the sale make it end with the server only, saying so; the price list's "Order it" opens the
  form with the item set), and dying with a delivery menu open
  (`orders-delivery-death`). `orders-sell-routing` skips itself on builds whose selling does not route to orders; it
  was run on a test-only merge of this branch with the sell branch.

## Not included

- Sell routing is implemented here (`OrderMarket`) but switched on by the sell feature, which consumes it (wiring
  above).
- The auction house link (cheapest listing in the confirmation, order prices on new listings) needs an
  `AuctionLookup` from the auction feature.
- The top-orders display template is the snippet above; it belongs in the displays feature's config.
- Not built (optional): a dialog-list item picker with item sprites for 1.21.6+ clients (it needs an `Icons#item`
  lookup in core; the chest picker and the typed item field cover every client), and a shared sorted snapshot of the
  browser per (revision, sort, filter) (each redraw sorts the open orders in memory, which is cheap at server scale).
- Rejected on purpose: matching by name, lore or custom model data, deleting orders with their items, ground drops,
  enchanted tool and armour orders, multi-enchantment books, potion orders without an exact type, orders that never
  expire, limits that count finished orders, and any pay-first fill API for other plugins.
