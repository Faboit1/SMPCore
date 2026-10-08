# Sell (`feature/sell`, id `sell`)

Selling items to the server, and the worth table every other feature reads prices from.

## Commands

| Command | Permission (default) | What it does |
|---|---|---|
| `/sell` | `siftcore.command.sell` (everyone) | Opens the sell menu: a 5-row grid to drop items into, a live total and a Sell button. Closing the menu sells nothing and gives everything back. |
| `/sell hand` | `siftcore.command.sell.hand` (everyone) | Sells the stack in the main hand. |
| `/sell all` | `siftcore.command.sell.all` (everyone) | Sells every sellable item in the main inventory (hotbar and the 27 storage slots; never armor or the off hand). Options in `sell-all`. |
| `/worth` | `siftcore.command.worth` (everyone) | What the held item sells for (each and the whole stack, plus the player's bonus). |
| `/worth <item>` | `siftcore.command.worth` | The same for any item id (`/worth diamond`, `/worth minecraft:iron_block`). Works from the console. |

`siftcore.worth.details` (operators) adds where a price comes from (base price, the recipe it was derived from, or an
override) to `/worth`. All three `/sell` forms share the `sell` cooldown from `commands.yml` (none by default).
`/sell` is also in the pause-menu hub (entry `sell`, order 25).

## Rank multipliers

`siftcore.sell.multiplier.<tier>` for every tier in `features/sell.yml` (`supporter` 1.1, `patron` 1.2, `elite` 1.35,
`legend` 1.5). Default false: only permissions given explicitly count (`isPermissionSet && hasPermission`), so
operators do not get a bonus. With several tiers the highest wins. The multiplier applies to the whole sale and the
result is rounded down once (exact decimal math).

Placeholder: `%siftcore_sell_multiplier%`, the player's multiplier (`1`, `1.1`, ... `1.5`). It is read from
permissions on the player's own thread at join and every minute, so a rank change shows within a minute (a sale
always reads the permission fresh).

## The worth table

Built at startup and on every successful `/sift reload` from `features/sell.yml` and the server's own recipes:

1. `base-prices` price raw and natural materials (item ids or item tags; an id wins over a tag).
2. Every item the enabled recipe types make from priced items gets the price of its cheapest recipe: the
   ingredients (each slot at its cheapest priced option) divided by how many the recipe makes, times `craft-loss`
   (0.9), rounded down to whole dollars. Ingredients count at their whole-dollar price, so crafting never turns
   items into more money than selling them, even after rounding. Exact `BigDecimal` math.
3. `overrides` set the final price of single items; 0 makes an item unsellable.

Items worth less than $1 are not sellable. Recipes whose crafting ingredients leave something behind (a milk
bucket leaves its bucket) and special recipes (banner copying, firework stars, ...) are not used.

**Recipe loops.** Items are priced in dependency order using strongly connected components (iterative Tarjan).
Inside a loop (ingot / block / nugget, re-dyeing shulker boxes or beds, smithing templates that copy themselves) items
are priced in layers: first those a recipe makes from items outside the loop (or from a base-priced member), then
those one recipe away, each item taking the first layer that can price it. Prices never chain around a loop, so the
loss factor is not compounded and re-dyeing can never gain value: every colour of shulker box is priced from the
plain box.

The generated table is written to `plugins/SiftCore/data/worth-generated.yml` (every price with its origin, the
items below $1, the turned-off items). It is rewritten at every start and reload and nothing reads it back.
At startup the log says e.g. `Worth table: 795 sellable items (206 base, 589 from recipes, 0 overrides), using 1519
of 1585 recipes (66 special recipes skipped).`

**Only plain items sell.** An item sells only when it is exactly the default item of its type
(`stack.isSimilar(ItemStack.of(type))`): renamed, enchanted, damaged, dyed, written, filled or plugin-tagged items
are refused with a message. Enchanted books are therefore never sellable: their value depends on the enchantments
and pricing them would need a separate table. `/worth` on a changed item says what the plain item would sell for.

## Selling

Every sale goes through `SellService`:

1. Price every stack, apply the multiplier, build a `SalePlan` (one line per item, overflow throws instead of
   wrapping) and refuse sales the balance limit can't take.
2. Fire `api.event.ItemSellEvent` (cancellable; source `MENU`, `HAND` or `ALL`, copies of the items, totals).
3. Take the items, checking each slot still holds exactly the priced stack.
4. One `LedgerTx`: `source(player, MONEY, total, kind "sell", ref "menu"/"hand"/"all")` with a note listing the
   items (cut at 255 characters).
5. If the transaction is not committed the items go back (to the claim box if the player left); the player is told
   why (cancelled, balance full, failed).
6. `player.saveData()` when `crash-safety.save-player-after-trade` (config.yml) is on, then the receipt: `You sold 64 diamond for
   $25,600.` in chat with a hover card listing what was sold (up to 12 lines).

**Sell menu.** Items in the grid belong to the player until Sell is pressed. Closing the menu, quitting, a kick or
a server stop hands the grid back (inventory first, the rest to the claim box). Dying with the menu open drops the
grid with the rest of the inventory (or keeps it with `keepInventory`), exactly as if the items had been in the
inventory. The total button updates live and counts what in the grid can't be sold (those items stay in the grid and are handed back).

## Integration

- `SellFeature#worth()` returns the `WorthService`, which implements `core.link.WorthLookup` (price of a plain item,
  with or without the player's multiplier) and `feature.sell.Pricing.Source` (the table, the recipes and the highest
  multiplier, used by the shop to validate prices). Other features read prices through `WorthLookup` only.
- Events: `api.event.ItemSellEvent`.

## Config (`features/sell.yml`)

`craft-loss`, `recipe-types` (crafting, smelting, blasting, smoking, campfire, stonecutting, smithing), `multipliers`,
`sell-all.skip-unstackable` (true) / `sell-all.skip-hotbar` (false), `base-prices`, `overrides`. A reload with any
problem is refused as a whole (and so is a reload that makes a shop price unsafe, see `shop.md`).

Economy note: emeralds have a base price of $250 because the spec asks for it, and villagers sell many items for
emeralds; with trading halls emeralds become cheap. Lower `emerald` or turn it off (`overrides: {emerald: 0}`) if
villager trading is not limited on the server.

## Self-tests (`/sift selftest`)

- `worth table`: the calculation reported no problems, the table is not empty, every base price is in it
  unchanged, every price is within the money limit and every derived price names its recipe.
- `only plain items sell`: renamed, PDC-tagged and damaged items are refused, a plain one is accepted.
- `sale math`: 64 diamonds at 1.5x pay exactly $38,400.
- `generated worth file`: `data/worth-generated.yml` exists and is not empty.

## Tests

Unit: `WorthCalculatorTest` (derivation, fixed point, loops and components, loss factor, overrides, rounding),
`SellSettingsTest`, `MultipliersTest` (tier selection), `SaleMathTest`, `SalePlanTest`, `ItemKeysTest`,
`WorthFileTest`. End to end (`tools/e2e`, `SellShopScenarios`): `sell-hand`, `sell-refusals`, `sell-all`,
`sell-bonus`, `sell-menu`, `sell-menu-quit`, `sell-menu-death`, `worth`.
