# Shop (`feature/shop`, id `shop`)

The server shop: players buy items (and spawners, once the spawner feature provides them) for money.

## Commands

| Command | Permission (default) | What it does |
|---|---|---|
| `/shop` | `siftcore.command.shop` (everyone) | Opens the category screen. |
| `/shop <category>` | `siftcore.command.shop` | Opens one category directly (`/shop ores`); category ids are suggested. |

The shop is also in the pause-menu hub (entry `shop`, order 20). No placeholders.

## How buying works

1. **Category screen** (`menu.rows`, default 4): one button per category at its configured slot, the player's
   balance in the middle of the bottom row.
2. **Category screen** (paged): every item with its price and the most one purchase can buy. A cycle button sorts
   by shop order, cheapest, priciest or name. Back returns to the categories.
3. **Purchase dialog**: the item, its price, the player's balance and the most per purchase; a slider for the
   amount plus a text field to type an exact amount (both only when more than one can be bought; a typed amount wins
   and must be valid on its own). The buy button shows the amount and total (`Buy 64 for $384`). It starts at one
   stack, or the cap when that is lower. Dialog buttons can't change their label while the slider moves, so a press
   with a different amount first shows the dialog again with the new total; nothing is bought until the player
   presses a button that names the amount and total they get.
4. **Confirmation** for purchases of at least `confirm-above` ($50,000): amount, item, total and the balance left.
5. **One `LedgerTx`**: `sink(player, MONEY, total, kind "shop_buy", ref "<category>/<entry>")` with a note
   (`64 stone`). What doesn't fit in the inventory goes to the claim box in the same transaction, so money and items
   can never be split by a crash. The transaction re-checks that the entry still exists at the same price.
6. Items are handed out only after the transaction is committed to storage, on the player's thread (to the claim
   box if they left in between), then `player.saveData()` when `crash-safety.save-player-after-trade` is on, and a
   chat receipt: `You bought 64 stone for $384.` (plus how many went to the claim box).

Everything is re-validated when a button is pressed: the entry is read again (a removed entry closes the dialog),
the amount must be within 1..max, a changed price shows the dialog again with the new price instead of buying, the
balance is checked, and the ledger refuses a total the balance can't cover. Dialog buttons are one-shot, so double or forged submits buy once.
`api.event.ShopPurchaseEvent` (cancellable: player, entry ref, item, quantity, unit price, total) fires right
before the transaction.

## Price safety

Every price is checked against the worth table (`feature/sell`) when the config is loaded: an item's price must be
more than the most one unit can be turned into, times the best multiplier in `features/sell.yml`, times 1.1. "Turned
into" covers selling it directly and crafting it (through chains of recipes) into something that sells, minus what
the other ingredients of those recipes cost; ingredients that sell cost their sell price, ingredients that don't
cost what making them from their cheapest recipe costs, and items that neither sell nor come from a recipe cost
nothing (`ShopValidator`). A price that breaks the rule is a config problem: `/sift reload` refuses the change
(including a `features/sell.yml` change that would make an existing shop price unsafe), and at startup the item is
left out of the shop. Items that can't be sold at all may have any price.

The default prices were checked this way; a few needed to be above the obvious value because of recipes
(cobblestone and cobbled deepslate smelt into stone, feathers make arrows, flint and steel uses an iron ingot).

## Spawners

The `spawners` category lists the spec's spawner prices (zombie $60k ... iron golem $2.5m). Spawner items come from
`core.link.SpawnerItems`; until the spawner feature provides a real implementation (`SpawnerItems.NONE` today) those
entries are hidden, and startup logs `its 15 spawner entries are hidden until spawner items are available`.
Spawners are not checked against the worth table (they can't be sold).

## Config (`features/shop.yml`)

`confirm-above`, `default-max` (640), `menu.rows`, and `categories.<id>` with `name`, `description`, `icon`, `slot`,
optional `default-max`, and `items.<entry id>` with `price`, optional `max`, and optional `item:` or `spawner:`.
Categories: blocks, farming, mob_drops, ores, redstone, food, spawners, misc. Ids are lowercase (`[a-z0-9_-]`) so
the ledger and claim box ref (`category/entry`) stays short. Every entry's `price x max` must fit the money limit.

## Integration

- Reads prices through `feature.sell.Pricing.Source` (the `WorthService` from `SellFeature#worth()`), using the
  newest parsed sell settings during a reload so both files are validated together.
- Consumes `core.link.SpawnerItems` (constructor argument; `SpawnerItems.NONE` until the spawner feature exists).
- Events: `api.event.ShopPurchaseEvent`.

## Self-tests (`/sift selftest`)

- `prices stay above what items sell for`: every non-spawner entry passes the price check against the live table.
- `every entry can be handed out`: every entry makes a real item and `price x max` fits the money limit.
- `spawner entries match the spawner provider`: every spawner entry the provider lists makes a spawner item.

## Tests

Unit: `ShopValidatorTest` (liquidation values, recipe chains, ingredient costs, multipliers, margin),
`ShopSettingsTest`, `PurchaseMathTest` (totals and overflow, typed and slider amounts, capacity and claim-box split).
End to end (`tools/e2e`, `SellShopScenarios`): `shop-buy`, `shop-amount`, `shop-confirm`, `shop-refusals`,
`shop-claim-box`, `shop-double-submit`.
