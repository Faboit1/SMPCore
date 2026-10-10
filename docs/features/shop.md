# Shop (`feature/shop`, id `shop`)

The server shop: players buy items and spawners for money.

## Commands

| Command | Permission (default) | What it does |
|---|---|---|
| `/shop` | `siftcore.command.shop` (everyone) | Opens the category screen. |
| `/shop <category>` | `siftcore.command.shop` | Opens one category directly (`/shop ores`); category ids and `search` are suggested. |
| `/shop search [text...]` | `siftcore.command.shop` | Every item of the shop in one list, searched for the text (without text the search prompt opens first). |

The shop is also in the pause-menu hub (entry `shop`, order 20). No placeholders.

Combat-tagged players can't use the shop (`block-in-combat: true`): `/shop`, the category screen, the search, the
purchase dialog and its Buy, Confirm and quick-amount buttons say "You can't use the shop in combat. 12s left." The
buttons check again when pressed, so a dialog opened before a fight can't buy golden apples during it.

## How buying works

1. **Category screen** (`menu.rows`, default 5): one button per category at its configured slot; in the bottom row
   the **Search** button (oak sign, left of the balance) and the player's balance. With 5 or more rows the centre of
   the row above holds **Buy again**: the player's last five purchases (one per entry, newest first) with what was
   bought and what that amount costs now; a click opens the purchase dialog at that amount.
2. **Category page** (paged): every item with its price, the most one purchase can buy and, for items the server
   buys, "Sells back for $X each" (the viewer's own sell price, rank and mastery included). A cycle button sorts by
   shop order, cheapest, priciest or name. Back returns to the categories. A click buys; a **right click** on an item
   the server buys offers to sell the player's own instead ("Right click to sell yours"): the sell confirmation with
   everything of that item they carry, whose Cancel comes back to the page.
3. **Search** (`ShopSearchMenu`): every visible entry across categories, searchable by name and id, filtered by
   category (All categories, then each category), sorted like a category page; entries show their category. Clicks
   work like on a category page.
4. **Purchase dialog** (the dialog style: what the player decides on in the body, the rest in tooltips): the item,
   its price and the player's balance; a slider for the amount plus a text field to type an exact amount (both only
   when more than one can be bought; a typed amount wins and must be valid on its own); the buy button names amount and
   total (`Buy 64 for $384`), and its tooltip the most per purchase and, for items the server buys, "Sells back for $X
   each" and "You carry N". **Max you can afford** and **Fill your inventory** (each says in its tooltip that nothing
   is bought until Buy) work the amount out when pressed (balance, free space and the limit) and show the dialog again
   with the new amount and total before anything is bought. Dialog buttons can't change their label while the slider moves, so a press with
   a different amount first shows the dialog again with the new total; nothing is bought until the player presses a
   button that names the amount and total they get.
5. **Confirmation** for purchases of at least `confirm-above` ($50,000), or the amount the player picked in
   `shop-confirm-above` (see Player settings): amount, item, total and the balance left.
6. **One `LedgerTx`**: `sink(player, MONEY, total, kind "shop_buy", ref "<category>/<entry>")` with a note
   (`64 stone`). Every bought item goes into the claim box in the same transaction: the part that fits the inventory
   right now under a reference of its own (`shop:<random id>`), the rest under `<category>/<entry>`. The purchase is
   remembered for Buy again (`shop_recent`) in the same transaction too, so money, items and the row can never be
   split by a crash, a disconnect or a server stop. The transaction re-checks that the entry still exists at the same
   price.
7. Once the transaction is committed to storage, the part that fitted is claimed into the inventory on the player's
   thread (`economy.ClaimHandouts`: marked claimed in storage first, then handed over; whatever no longer fits stays in
   the claim box), then `player.saveData()` when `crash-safety.save-player-after-trade` is on, and the receipt
   `You bought 64 stone for $384.` in chat or above the hotbar (`shop-receipts`); when items wait in the claim box the
   receipt always goes to chat and says how many. A buyer who left before the commit (or a
   server that stopped) finds everything in the claim box; at shutdown, claims still on their way finish and whatever
   was not handed over goes back into the claim box before storage closes.

Everything is re-validated when a button is pressed: combat, the entry is read again (a removed entry closes the
dialog), the amount must be within 1..max, a changed price shows the dialog again with the new price instead of
buying, the balance is checked, and the ledger refuses a total the balance can't cover. Dialog buttons are one-shot,
so double or forged submits buy once. `api.event.ShopPurchaseEvent` (cancellable: player, entry ref, item,
quantity, unit price, total) fires right before the transaction.

**Buy again storage.** `shop_recent` (migration `V016`): one row per player and entry with the amount last bought
and when; all of a player's rows are loaded when they join (at most one per shop entry) and updated inside each
purchase's transaction (in memory and in storage together). Buy again shows the newest five; "My last amount" uses
the row of the entry being bought, whenever it was bought. Entries that were removed or are hidden right now are not
shown.

**Remembered sort.** The sort order of category and search pages is remembered per player (the `settings` row
`shop-sort`, UI state like the auction house's and the order browser's, not a setting). It is saved when the page
closes.

## Player settings (`/settings`, group Shop, auction & orders)

| Id | Kind, default | What it does | Read in |
|---|---|---|---|
| `shop-confirm-above` | choice server / always / from $10k / from $100k / from $1m / never, server | From which total buying asks once more; Server default follows `confirm-above` (0 never asks) | `PurchaseFlow.buy` (`PurchaseFlow.asks`) |
| `shop-default-amount` | choice stack / one / last / fill, stack | How many the purchase dialog starts with when opened from the shop, a search or another feature: one stack (one for spawners), one item, the amount of the player's last purchase of that entry, however long ago (one stack when they never bought it), or as many as fit the inventory (one stack when nothing fits); always capped at the entry's limit. Buy again keeps its own amount | `PurchaseFlow.open` (`StartAmount.start`) |
| `shop-receipts` | choice chat / actionbar, chat | Where the purchase receipt shows (`Messenger.alert`). Items sent to the claim box are always told in chat | `PurchaseFlow.bought` |

Labels and descriptions are in `lang/shop.yml` under `shop.settings`; positions 3, 6 and 9 of the group.

## Price safety

Every price is checked against the worth table (`feature/sell`) when the config is loaded: an item's price must be
more than the most one unit can be turned into, times the best multiplier anyone can reach (the best rank in
`features/sell.yml` plus the top sell mastery bonus: 1 + 0.25 = 1.25 with the shipped empty `multipliers`), times the
largest server sell booster allowed (`sell.max-percent` in `features/boosters.yml`, +25%: 1.25 x 1.25 = 1.5625),
times 1.1. So buying in the shop and selling it back loses money even with the best mastery while the strongest
booster runs. "Turned into" covers
selling it directly and crafting it (through chains of recipes) into something that sells, minus what the other
ingredients of those recipes cost; ingredients that sell cost their sell price, ingredients that don't cost what
making them from their cheapest recipe costs, and items that neither sell nor come from a recipe cost nothing
(`ShopValidator`). A price that breaks the rule is a config problem: `/sift reload` refuses the change (including a
`features/sell.yml` change, such as a higher multiplier or mastery step, or a higher `sell.max-percent` in
`features/boosters.yml`, that would make an existing shop price unsafe), and at startup the item is left out of the
shop. The problem names the booster: "... (1.5625x with the best sell bonus and a +25% sell booster)". During a
reload the shop is checked against the booster limit being loaded (the boosters config is parsed before the shop's),
so lowering `max-percent` and a shop price together works in one reload. A running booster never pays more than the
current `max-percent`, even one started under a higher limit or a store booster bought for more (it is delivered and
pays the limit), so the check always covers what is paid. Items that can't be sold at all may have any price.

The default prices were checked this way; a few needed to be above the obvious value because of recipes
(cobblestone and cobbled deepslate smelt into stone, feathers make arrows, flint and steel uses an iron ingot), and
the redstone block ($110) and slime block ($275) went up with mastery.

## Spawners

The `spawners` category lists 15 spawners (zombie $60k ... iron golem $2.5m). Spawner items come from
`core.link.SpawnerItems`, which `FeatureCatalog` wires to the spawners feature (`SpawnersFeature#items()`), so a bought
spawner is a SiftCore stacking spawner (see [spawners](spawners.md)). Startup logs
`Shop: 8 categories with ... items, 15 of them spawners.` While the spawners feature is off the provider is
`SpawnerItems.NONE`: those entries are hidden and startup logs `its 15 spawner entries are hidden until spawner items
are available`. Spawners are not checked against the worth table (they can't be sold) and show no sell-back price.

## Config (`features/shop.yml`)

`confirm-above`, `default-max` (640), `menu.rows` (5), `block-in-combat` (true), and `categories.<id>` with `name`,
`description`, `icon`, `slot`, optional `default-max`, and `items.<entry id>` with `price`, optional `max`, and
optional `item:` or `spawner:`. Categories: blocks, farming, mob_drops, ores, redstone, food, spawners, misc. Ids are
lowercase (`[a-z0-9_-]`) so the ledger and claim box ref (`category/entry`) stays short; `search` is taken by
`/shop search`. A category slot may not be the balance slot, the search slot left of it or a Buy again slot. Every
entry's `price x max` must fit the money limit.

## Integration

- Reads prices through `feature.sell.Pricing.Source` (the `WorthService` from `SellFeature#worth()`), using the
  newest parsed sell and booster settings during a reload so the files are validated together
  (`Pricing#guardMultiplier()` = highest multiplier x (1 + max booster / 100)).
- Shows and uses selling through `feature.sell.SellLink` (`SellFeature#link()`): sell-back prices, carried counts and
  right-click selling.
- Offers itself to selling as `feature.sell.ShopOffers` (`ShopFeature#offers()`, handed to `SellFeature#shop`): the
  lowest shop price of a plain item and opening its purchase dialog, for `/worth`, the price list and item details.
- Consumes `core.link.SpawnerItems` (the spawners feature, `SpawnersFeature#items()`) and
  `core.teleport.CombatStatus` (the shared combat tags).
- Events: `api.event.ShopPurchaseEvent`.

## Self-tests (`/sift selftest`)

- `prices stay above what items sell for`: every non-spawner entry passes the price check against the live table
  and the highest multiplier including mastery and the largest sell booster allowed.
- `every entry can be handed out`: every entry makes a real item and `price x max` fits the money limit.
- `spawner entries match the spawner provider`: every spawner entry the provider lists makes a spawner item.

(`sell`'s `shop safe with mastery and boosters` checks the same multiplier from the selling side.)

## Tests

Unit: `ShopValidatorTest` (liquidation values, recipe chains, ingredient costs, multipliers including the mastery
bonus, the largest booster, margin), `ShopSettingsTest`, `PurchaseMathTest` (totals and overflow, typed and slider amounts, capacity and
claim-box split, "Max you can afford" and "Fill your inventory"), `RecentPurchasesTest`, `PurchaseRefTest` (every
purchase claims under its own reference), `ShopPlayerSettingsTest` (group and order, option ids, the confirmation
threshold, every start amount, the setting text), and the shared hand-over pieces `economy.HandoffsTest` (a scheduler that
returns no task, throws, retires the player or never runs: exactly one of delivery and fallback, once) and
`economy.SlotPlanTest`. End to end (`tools/e2e`): `SellShopScenarios` (`shop-buy`, `shop-amount`, `shop-confirm`,
`shop-refusals`, `shop-claim-box`, `shop-double-submit`, `shop-left-before-commit`: the buyer leaves while storage
is held up, and the paid items wait in the claim box), `SellPlusScenarios` (`shop-combat-blocked`, `shop-search`,
`shop-quick-and-buy-again`, `shop-right-click-sell`) and `BoostersScenarios` (`boosters-shop-guard`: a diamond price
safe at +5% but not at +25% is refused, accepted together with `max-percent: 5`, and raising the limit again is
refused). Settings: `MarketScenarios` (registered with the auction scenarios; `market-shop-settings`: the start amount
changed in the `/settings` dialog, the last amount and the hotbar receipt with `/settings <setting> <value>`, a full
inventory and the confirmation threshold through the API, the remembered sort). `RecentPurchasesTest` also checks that
the last amount of an entry outside the five newest is remembered and loads back.
