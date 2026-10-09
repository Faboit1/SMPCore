# Sell (`feature/sell`, id `sell`)

Selling items to the server, the worth table every other feature reads prices from, sell categories with mastery
levels, the price list, top sellers, and the guard that keeps items from villager trades from ever being sold.

## Commands

| Command | Permission (default) | What it does |
|---|---|---|
| `/sell` | `siftcore.command.sell` (everyone) | Opens the sell menu: a 5-row grid to drop items into, a live total and a Sell button, plus Add, Give back and Mastery. Closing the menu sells nothing and gives everything back. |
| `/sell hand` | `siftcore.command.sell.hand` (everyone) | Sells the stack in the main hand. A shulker box in the hand sells what is inside it (an empty plain box sells as an item). |
| `/sell hand all` | `siftcore.command.sell.hand` | Sells every plain stack of the held item's type from the hotbar, the storage slots and shulker boxes there (never armor or the off hand). Asks first like `/sell all`. |
| `/sell all` | `siftcore.command.sell.all` (everyone) | Sells every sellable item in the hotbar and the 27 storage slots (never armor or the off hand), including what is inside shulker boxes but never the box itself. Asks first above `sell-all.confirm-above`. |
| `/sell mastery` | `siftcore.command.sell` | The sell mastery dialog: every category's level, multiplier and progress; each opens its ladder, "Sell your <category> items" and its prices. |
| `/sell top` | `siftcore.command.sell` | The ten players who sold the most (base value), and the viewer's own place. |
| `/sell history` | `siftcore.command.sell` | The player's last 200 sales (to the server and to buy orders), newest first, with what was sold in the tooltip. |
| `/sell admin mastery <player> [category]` | `siftcore.admin.sell` (operators) | Shows a player's mastery (every category, or one). Works from the console and for offline players. |
| `/sell admin mastery <player> <category> set <level>` / `reset` | `siftcore.admin.sell` | Sets a category to the start of a level, or back to 0. Audited (`sell.mastery`). |
| `/sell admin mastery <player> reset` | `siftcore.admin.sell` | Resets every category of the player, including categories no longer configured. Audited. |
| `/worth` | `siftcore.command.worth` (everyone) | What the held item sells for (each, the stack, the player's bonus for its category, the shop price and the best buy order). For a shulker box: what its contents sell for. With an empty hand it opens the price list. |
| `/worth <item>` | `siftcore.command.worth` | The same for any item id (`/worth diamond`). Works from the console. The item name is a link to the price list filtered to it. |
| `/worth list [search...]` | `siftcore.command.worth` | The price list, optionally searched. |

`siftcore.worth.details` (operators) adds where a price comes from (base price, the recipe it was derived from, or
an override) to `/worth` and the price list. `/sell`, `/sell hand`, `/sell hand all` and `/sell all` share the
`sell` cooldown from `commands.yml` (none by default). Hub entries: `sell` (order 25, opens the menu) and `prices`
(order 26, "Prices", "See what items sell for", opens the price list).

Combat-tagged players can't sell (`block-in-combat`): `/sell`, `/sell hand`, `/sell hand all`, `/sell all`, category
selling and the menu's Sell button are refused with "You can't sell in combat. 12s left." The menu stays open; only
the action is refused.

## Rank multipliers and mastery

`siftcore.sell.multiplier.<tier>` for every tier in `multipliers`. The shipped list is empty: paid ranks sell for the
same prices as everyone ([monetization](../monetization.md)); the mechanism stays for events and staff. Default false: only permissions given explicitly count (`isPermissionSet && hasPermission`), so
operators do not get a bonus. With several tiers the highest wins. The rank is read from permissions on the
player's own thread whenever a sale is worked out, and every minute for placeholders.

**Sell categories** (`categories` in `features/sell.yml`): farming, wood, mining, mob drops, fishing and "Blocks and
other" (the fallback `other`). An item listed in a category (id, `#tag` or `*` pattern) is in that category; an item
listed in two categories is a config problem. An unlisted item whose price comes from a recipe takes the category of
the recipe's most valuable ingredient (worked out when the table is generated: an iron block is mining, bread is
farming, planks are wood). Everything else is `other`.

**Mastery.** Selling a category's items to the server counts toward its levels (`mastery.levels`, default $50k,
$250k, $1m, $5m, $25m of base value, the worth before any multiplier). Each level adds `mastery.step` (0.05) to the
player's multiplier for that category: a sale line pays `worth x (rank + bonus)` (level 5 with no rank multiplier: 1 + 0.25 =
1.25x). Each category of a sale is rounded down once, with exact decimal math.

- Credit is the base worth of the units the server bought, plus for units sent to buy orders the smaller of their
  base worth and what the orders paid after tax (so an overpriced order between alts can't pump mastery). Items the
  server doesn't buy give no credit. Only `/sell` counts; spawner and crate sales (`WorthLookup.priceFor`) stay
  rank-only and give no credit.
- Stored in `sell_mastery` (migration `V015`), one row per player and category, written inside the sale's own
  ledger transaction as an additive upsert (`sold = sold + excluded.sold`), with the in-memory totals changed in the
  same transaction, so memory and storage always agree. A player's rows are read at login, before they enter the
  world (the login waits up to 5 seconds, then the read finishes in the background), on the database writer in order
  with any sale of an earlier session still being stored. Until they are in memory, selling says "Your sales are
  still loading. Try again in a moment." so no sale pays too small a bonus or counts from the wrong level.
- Renaming or removing a category keeps its rows; they are simply not used any more.
- A level-up (after the sale is stored) says "Mining mastery is now level 2. Mining items sell for 1.6x." in chat
  with the success sound and fires `api.event.SellMasteryLevelEvent`.
- Safety: the shop is validated against the best rank multiplier plus the top mastery bonus
  (`SellSettings#highestMultiplier`, 1.75 by default; `x 1.1` margin = 1.925), so a reload that makes any shop price
  unsafe with mastery is refused.

## Selling

Every sale goes through `SellService` and `SaleBuilder`, on the player's thread:

1. **Work out a draft** from the live inventory: which slots (whole or partial stacks) and which contents of which
   shulker boxes the request covers, what goes to buy orders, what the server pays per category, the mastery credit.
2. **Confirm when needed** (`/sell all`, `/sell hand all`, category selling, "Sell your ..." buttons):
   `sell-all.confirm` is `always`, `above` (default, from `confirm-above` $10k, for players with "Ask before /sell
   all" on) or `never`. The dialog "Sell everything" shows `Sell 128 items for $51,200?`, the bonus, the buy-order
   part, how many come out of shulker boxes and what is kept, with "Sell for $51,200", "Choose items" (opens the
   sell menu filled with exactly what the request covers) and "Cancel". Confirming works the sale out again from the
   live inventory; if any slot, stack, box content, order take or the total differs it shows the dialog again with
   "Your inventory changed. Check the total, then sell." and the error sound. Nothing is sold that was not shown.
3. **Fire `api.event.ItemSellEvent` once** (cancellable; source `MENU`, `HAND`, `HAND_ALL`, `ALL` or `CATEGORY`;
   copies of every item, including what comes out of boxes; `serverTotal`, `ordersTotal`, the order fills and the
   multiplier per category).
4. **Let buy orders veto** their fills (`OrderMarket#approve`, the orders feature's own cancellable event); vetoed
   orders are left out and the sale is worked out again (twice at most, then server only).
5. **Remove before grant**: every slot is checked to still hold exactly the stack that was priced (`equals`) and
   emptied or reduced; every shulker box slot is checked to still hold the original box and replaced by the rebuilt
   box (same positions, the sold stacks gone; an emptied plain box is a plain box again).
6. **One `LedgerTx`**: `source(player, MONEY, serverTotal, kind "sell", ref "menu"/"hand"/"hand_all"/"all"/
   "category")`, then the order side (`OrderMarket#contribute`: escrow transfer, tax sink, guarded check, in-memory
   change, guarded SQL), then the mastery rows. Note: `64 minecraft:diamond, 32 minecraft:iron_ingot (312 from
   shulker boxes)`, cut at 255 characters.
7. **On failure** everything goes back: a slot that still holds what the sale left gets its original back, otherwise
   the taken items are handed to the player (inventory, then the claim box); nothing is ever dropped. An order
   refusal (`order_gone`, `order_price_changed`, ...) re-plans from fresh bids and retries once; if that fails too
   it sells to the server only and says "Buy orders changed, so everything went to the server." If storing the
   committed sale fails later, the items are given back the same way (to the claim box if the player left).
8. `player.saveData()` when `crash-safety.save-player-after-trade` is on; after the sale is stored: buy-order owners
   are told (`OrderMarket#committed`), then the receipt and any level-up.

**Receipt.** `You sold 64 diamond for $25,600.` (or `... with your 1.5x bonus.`, `... with your bonuses.`,
`You sold 150 items for $9,000, $2,400 of it from buy orders.`) in chat with the success sound and a hover card:
one line per item (up to 12), the bonus per category, each order fill (`32 diamond to Steve's order $13,440`, net
after tax), the order tax, the rest to the server and how many came from shulker boxes. With the "Sale receipts in
chat" toggle off only `+$25,600` on the action bar and the sound remain; `feedback.action-bar: true` adds the
action bar line to chat receipts.

**Shulker boxes** (`shulker-contents: true`). A single, unstacked shulker box is opened; a stacked box (amount > 1)
is never touched, and boxes inside boxes are not opened. `/sell hand` on a filled box sells its sellable contents
and keeps the box in the hand with everything else at its place; a box with nothing sellable says "Nothing in that
shulker box can be sold." A box in the sell menu has its contents sold and the rebuilt box stays in the grid
(returned on close). `/sell all` opens boxes when `sell-all.shulker-contents` is on, but never sells the box itself;
items that don't stack stay in boxes too when `skip-unstackable` is on. `/worth` on a box: "What's inside sells
for $4,075 (13 items)." and, for a plain box, "An empty box sells for $X." Bundles work the same way behind
`bundle-contents` (off by default).

**Sell menu.** Items in the grid belong to the player until Sell is pressed. Closing the menu, quitting, a kick or a
server stop hands the grid back (inventory first, the rest to the claim box). Dying with the menu open drops the
grid with the rest of the inventory (or keeps it with `keepInventory`), exactly as if the items had been in the
inventory. Close-to-sell is deliberately not supported (it turns death loot into money).

- Slot 45 **Add sellable items** (hopper): moves every plain sellable stack, and every shulker box with something
  sellable inside, from the hotbar (unless `skip-hotbar`) and storage into empty grid slots. Never the tool in the
  main hand, armor or the off hand; items that don't stack only when they sell. Each slot is read again right
  before it moves; it stops when the grid is full.
- Slot 46 **Give back** (oak door): empties the grid into the inventory (the rest to the claim box) and keeps the
  menu open.
- Slot 48 **Total**: up to 8 lines `64 diamond $25,600` and "and 3 more kinds", the bonus, the buy-order part, what
  can't be sold, "Mastery: +$25,600 toward Mining level 2" (the category the sale helps most) and the hint.
- Slot 50 **Sell**: remembers the total it showed; if the total is now lower (orders changed, a rank was lost)
  nothing is sold, the menu redraws and the action bar says "The total is now $X. Press Sell again." Equal or
  higher sells.
- Slot 52 **Mastery** (book): the mastery dialog.

## Buy orders

Selling routes units to buy orders when they pay the seller more than the server would. The contract is
`core.link.OrderMarket` (implemented by the orders feature); `SellFeature` takes a `Supplier<OrderMarket>` and
`FeatureCatalog` passes `() -> OrderMarket.NONE` until orders exists, so today nothing is routed and no order text
is shown.

- Conditions: the market is available, the player's "Sell to buy orders first" toggle is on (registered only once
  orders exist, also when the orders feature starts after this one), and `OrderMarket#usable(player)` allows it.
- `OrderRouting.plan` (pure): per item key, units go to bids, best price first and then oldest, while the order's
  net per item (`priceEach x (10000 - tax) / 10000`, exact) is strictly more than the seller's own server price
  (worth x rank x mastery); ties go to the server. A bid's remaining amount is shared by every stack of its key, so
  an order is never promised the same units twice. Leftover units go to the server when it buys them, otherwise
  they stay.
- The menu and `/sell hand` (and hand all) may send items the server doesn't buy to orders; `/sell all` only moves
  what the server buys.
- Previews (menu total, confirmations, `/worth`, the price list) read bids through a cache (2,048 lists, dropped
  whenever the book's revision moves). Sales always read fresh bids, and every take is re-checked inside the
  transaction.

## The worth table

Built at startup and on every successful `/sift reload` from `features/sell.yml` and the server's own recipes:

1. `base-prices` price raw and natural materials. Keys are item ids, `#tags` or `*` patterns (`music_disc_*`,
   `*_coral_block`); an id wins over a tag, a tag over a pattern, and every tag and pattern must name an item.
2. Every item the enabled recipe types make from priced items gets the price of its cheapest recipe: the
   ingredients (each slot at its cheapest priced option) divided by how many the recipe makes, times `craft-loss`
   (0.9), rounded down to whole dollars, exact `BigDecimal` math. When every recipe rounds to $0 only because some
   ingredients are worth less than a dollar (sticks, slabs), those count at their exact value and the cheapest
   recipe worth at least a dollar is used (armor stands, chiseled stone bricks, chiseled deepslate and tuff).
3. `overrides` set the final price of single items; 0 makes an item unsellable.
4. **No recipe gains**: no crafting, stonecutting or smithing recipe may turn ingredients into results that sell for
   more than the ingredients do. A violation (a base price set above what an item is made from, a smithing template
   priced above what copying it costs) is a config problem: the reload is refused and startup reports it.
   Furnace-type recipes may add value on purpose (raw iron smelts into a more valuable ingot).

Recipe loops (ingot / block / nugget, re-dyeing shulker boxes, templates that copy themselves) are priced in layers
inside each strongly connected component, so prices never chain around a loop. Recipes whose ingredients leave
something behind (a milk bucket leaves its bucket) and special recipes are not used. Items worth less than $1 are not
sellable. The table is written to `plugins/SiftCore/data/worth-generated.yml` with the origin of every price. At
startup: `Worth table: 1019 sellable items (404 base, 615 from recipes, 0 overrides) in 6 categories, using 1519 of
1585 recipes (66 special recipes skipped). Best multiplier 1.75x (rank 1.5x plus mastery 0.25).`

Default prices include seeds, saplings, flowers, leaves, coral, concrete, cobwebs, bells, goat horns, pottery
sherds, music discs, smithing templates (the netherite upgrade $2,500 and every armor trim $1,000, both below what
copying them costs, so netherite gear now has a price), elytra, tridents, enchanted golden apples, experience
bottles, mob heads and silk-touched ores at no more than one drop (placing a silk-touched ore and mining it with
Fortune always pays more). Filled buckets of mobs, spawners and spawn eggs are never priced. Every price stays at or
below shop price / 1.925, which the shop's validator enforces anyway.

**Only plain items sell.** An item sells only when it is exactly the default item of its type
(`stack.isSimilar(ItemStack.of(type))`): renamed, enchanted (including enchanted books), damaged, dyed, written,
filled, villager-traded or plugin-tagged items are refused. Enchantments and potions are never priced: both would
turn XP or brewing into money.

## Villager trades never sell

Villagers buy cheap items for emeralds and sell goods for emeralds, and cured villagers trade for almost nothing, so
without a guard a trading hall would print money. `TradeGuard` (`mark-villager-trades: true`) closes it:

- Every trade result of a villager or wandering trader carries a marker (`siftcore:traded` in its custom data), so
  it is no longer a plain item and can't be sold, including emeralds villagers pay with. Trades are marked when a
  villager gains one, when a player opens a merchant, and a trade whose result is somehow unmarked is refused. Mined
  emeralds still sell.
- The marker follows the item: whatever is crafted, smelted, cooked, cut in a stonecutter, smithed, repaired or
  ground from a marked item is marked. A recipe or fuel that would hand back a new plain container (a marked milk
  or lava bucket leaving a bucket) is refused.
- Placed marked blocks are remembered in their chunk's data, also in their block form (redstone dust as wire, a
  banner on a wall, both halves of a bed): whatever they drop later, mined, sheared, blown up, washed away, pushed off
  by a piston or fallen, is marked. Marked blocks moved by pistons, falling, fading (coral) or stripped with an axe
  stay marked; mobs can't pick them up. Item frames and paintings keep the marker as entities.
- Buckets: emptying a marked bucket of fish (fishermen and wandering traders sell them) leaves a marked bucket and a
  marked fish whose drops are marked; filling or milking with a marked bucket gives a marked filled bucket and
  drinking marked milk leaves a marked bucket; cauldrons go through the same bucket events. Dispensers, catching a
  fish and recipes or fuel that leave a bucket behind make a new plain bucket with no event that could keep the
  marker, so marked buckets (and marked fish) can't be used there.
- What crops and plants grow into is not followed: farming from traded seeds or saplings is ordinary farming.
- Marked items work everywhere else, including paying villagers. Turning the option off stops new marking only.

`/sell hand` on a marked item says "Items from villager trades can't be sold."; `/worth` says what a plain one
would sell for.

## Price list and item details

`/worth` with an empty hand, `/worth list [search]` and the `prices` hub entry open the price list, a paged menu of
every sellable item with its real icon: "Sells for $400 each", "With your bonus $600" (above 1x), "Category Mining",
"The shop sells it for $1,000", "Best buy order $450 each" (once orders exist), "You carry 64, worth $38,400", and
for `siftcore.worth.details` where the price comes from. Sort: Name, Highest price, Lowest price. Filter: All and
each category. Search: name and id; an exact name match comes first. The sort and filter are remembered per player
(`worth_sort`, `worth_filter`; a filter the list was opened with, from mastery or a search, is only remembered once
the player picks one).

Clicking an item opens its **details** dialog, the one place where selling, the shop and buy orders meet for one
item: price each, with the player's bonus, the category mastery level, the shop price, the best order, how many the
player carries, and the buttons "Sell your 64 for $25,600" (the `/sell hand all` rules and confirmation), "Buy in
the shop for $1,000" (the shop's purchase dialog), "Order it" (the orders form, once orders exist) and Back.

## Placeholders

| Placeholder | Value |
|---|---|
| `%siftcore_sell_multiplier%` | The player's rank multiplier (`1`, `1.1`, ... `1.5`); mastery not included. |
| `%siftcore_sell_multiplier_<category>%` | Rank plus the category's mastery bonus, like `1.6`. |
| `%siftcore_sell_mastery_<category>%` | The player's mastery level in a category (0-5). |
| `%siftcore_sell_sold%` | Everything the player sold to the server, at base value, formatted as money. |
| `%siftcore_worth_<item>%` | What one plain item sells for (`worth_diamond` gives `$400`), empty when it can't be sold. |
| `%siftcore_sell_top_name_<n>%`, `%siftcore_sell_top_value_<n>%` | The n-th best seller (1-10) and what they sold. |

Top sellers are read from storage in the background (`SUM(sold) GROUP BY uuid` over `sell_mastery`) every
`top.refresh` (5m); `/sell top` and the placeholders only read that snapshot.

## Settings (`/settings`)

| Toggle | Default | Effect |
|---|---|---|
| `sell_all_confirm` "Ask before /sell all" | on | Show the total before selling everything (with `confirm: above`). |
| `sell_receipts` "Sale receipts in chat" | on | Off: only `+$total` on the action bar and the sound. |
| `sell_orders` "Sell to buy orders first" | on | Only shown once buy orders exist. |

## Integration

- `SellFeature#worth()` returns the `WorthService`: `core.link.WorthLookup` (price of a plain item, with or without
  the player's rank multiplier; spawner and crate sales) and `feature.sell.Pricing.Source` (the table, the recipes
  and the highest multiplier including mastery, for the shop's validator).
- `SellFeature#link()` (`SellLink`): the viewer's sell-back price, how many they carry and selling from a shop page.
- `SellFeature#shop(ShopOffers)`: the shop's prices and purchase dialog for `/worth`, the price list and details.
- Consumes `core.teleport.CombatStatus` (the shared combat tags) and `Supplier<OrderMarket>`.
- Events: `api.event.ItemSellEvent` (before anything moves), `api.event.SellMasteryLevelEvent` (after a stored sale).
- Shared helpers in `core.item`: `ContainerItems` (shulker boxes and bundles), `ItemCategories`/`ItemCategory` (the
  auction and orders classifier) and `ItemPatterns` (`*` patterns).

## Config (`features/sell.yml`)

`craft-loss`, `recipe-types`, `multipliers`, `block-in-combat` (true), `mark-villager-trades` (true),
`shulker-contents` (true), `bundle-contents` (false), `sell-all.skip-unstackable` (true) / `skip-hotbar` (false) /
`confirm` (above) / `confirm-above` (10k) / `shulker-contents` (true), `feedback.action-bar` (false), `top.refresh`
(5m), `categories.<id>` (`name`, `icon`, `items`), `mastery.enabled` (true) / `levels` / `step` (0.05),
`base-prices`, `overrides`. A reload with any problem is refused as a whole (and so is a reload that makes a shop
price unsafe, see `shop.md`). Keys added after the first release are optional and fall back to their defaults.

## Not done (and why)

- Tooltip prices ("Sells for $X" in the inventory, S16): needs a packet library (PacketEvents) that is not in the
  build; left off until the owner approves the dependency.
- A top-sellers display template and a "sold" stats board belong to the displays and stats features; the
  `sell_top_*` placeholders are ready for them.
- Rejected from SellPlugin: close-to-sell, enchantment and potion pricing, multipliers up to 3x (an endless loop
  against the shop), per-player YAML data, "~" estimates (previews here are exact and re-checked at sale).

## Self-tests (`/sift selftest`)

- `worth table`: no calculation problems, the table is not empty, base prices unchanged, every price within the
  money limit, every derived price names its recipe.
- `only plain items sell`: renamed, PDC-tagged and damaged items are refused, a plain one is accepted.
- `sale math`: 64 diamonds at 1.5x pay exactly $38,400.
- `generated worth file`: `data/worth-generated.yml` exists and is not empty.
- `no recipe gains`: the no-gain check passes on the live table.
- `sell categories cover the table`: every item is in a category that exists, listed items in their own.
- `shop safe with mastery`: the highest multiplier is the best rank plus the top bonus, the shop validates against
  it, and no shop item sells back for its price at that multiplier.
- `shulker rebuild keeps other contents`: taking one kind out of a box leaves every other stack at its position and
  an emptied box is a plain box.
- `villager trades are marked`: the marker makes a copy that does not sell and is otherwise unchanged.

## Tests

Unit: `WorthCalculatorTest` (derivation, loops, loss, overrides, rounding, the no-gain check and copy recipes,
patterns, netherite gear from the template, sub-dollar chains), `SellSettingsTest`, `SellCategoriesTest` (parse,
duplicates, derived categories), `MasteryTest` (levels, credit caps, additive multiplier), `MasteryBookTest`,
`OrderRoutingTest` (ties to the server, best price then oldest, pooling across stacks, tax rounding, unpriced items,
no bids, overflow), `SalePlanTest` (per-category rounding), `MultipliersTest`, `SaleMathTest`, `ItemKeysTest`,
`WorthFileTest`, `SellHistoryTest`, `core.item.ContainerItemsTest`. End to end (`tools/e2e`):
`SellShopScenarios` (`sell-hand`, `sell-refusals`, `sell-all`, `sell-bonus`, `sell-menu`, `sell-menu-quit`,
`sell-menu-death`, `worth`) and `SellPlusScenarios` (`sell-all-confirm`, `sell-hand-all`, `sell-shulker-hand`,
`sell-shulker-menu`, `sell-shulker-all`, `sell-shulker-failure`, `sell-combat-blocked`, `worth-browser-open`,
`worth-details-sell`, `mastery-credit-and-level`, `sell-menu-add-and-giveback`, `sell-top-and-history`,
`sell-traded-refused`, `sell-traded-block`, `sell-traded-bucket`, `sell-choose-items`).
