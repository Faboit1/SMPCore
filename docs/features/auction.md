# Auction house (`auction`)

Players list items for a fixed price; other players buy them. Bought, expired and taken-down items go through the
claim box. Package `feature/auction`, config `features/auction.yml`, text `lang/auction.yml`, table
`auction_listings` (migration V002, no schema change) plus the core `deliveries` table (the claim box).

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/ah` (`/auction`, `/auctionhouse`) | `siftcore.command.ah` (everyone) | Opens the auction house menu |
| `/ah search <text>` | `siftcore.command.ah` | Opens the menu searching item names and types |
| `/ah sell <price> [amount]` | `siftcore.auction.sell` (everyone) | Lists the held item (or `amount` of it) after a confirmation |
| `/ah listings` | `siftcore.command.ah` | Your listings; click one to take it down |
| `/ah claims` | `siftcore.command.ah` | The claim box |
| `/ah history` | `siftcore.command.ah` | Your last sales and purchases (dialog) |
| `/ah admin info` | `siftcore.admin.auction` (op) | Active listings, sellers, pending rows, claim box size, next expiry |
| `/ah admin list <player>` | `siftcore.admin.auction` | A player's active listings with their ids |
| `/ah admin remove <id>` | `siftcore.admin.auction` | Takes a listing down; the item goes to the seller's claim box (audited as `auction.remove`) |
| `/ah admin expire` | `siftcore.admin.auction` | Returns every listing whose time ran out now instead of at the next check |

Every staff command works from the console. Players see only the branches they may use. Staff can also
shift right click a listing in the menu to remove it (with a confirmation).

Listing slots: `siftcore.auction.listings.<n>` (numeric nodes on LuckPerms groups, the highest wins, read with
`Limits.highest`), otherwise `listings.default-slots` (3). `siftcore.auction.listings.unlimited` removes the limit; it
is declared with default `false`, so nobody (not even ops) has it unless it is granted. Suggested rank values:
default 3, supporter 5, patron 7, elite 10, legend 15.

`/ah` uses the `ah` entry of `commands.yml` for its cooldown and aliases.

## The menus

Main menu (`PagedMenu`, 6 rows): 45 listings per page, each the real item (tooltip fully shown, see below) with
`Price`, `Seller`, `Ends in` and a hint appended to its lore. Bottom row: 45 previous page, 46 back to the main menu,
47 sort, 48 category filter, 49 search (right click clears), 50 your listings (slots used of your limit),
51 claim box (items waiting), 52 sell the held item, 53 next page.

- Clicking a listing opens the purchase confirmation; clicking your own opens the take-down confirmation.
- Sort: newest, ending soon, lowest price, highest price (ties: newest first). Filter: all and the categories below.
  Each player's sort and filter are remembered (`settings` rows `auction-sort`, `auction-filter`).
- Search matches the lowercase plain item name (custom name or the vanilla English name), the item id with spaces
  (`diamond sword`) and enchantment names, stored with the listing.

Your listings: newest first, click to take one down; slot 50 opens the history. Claim box: everything owed to you
from every feature, oldest first; click one stack to claim it, or slot 50 to claim everything that fits.

Every dialog flow ends by reopening the menu it came from or by closing the screen (`AuctionDialogs#finish`). This is
needed because the 26.2 client ignores a dialog clear while it shows its "Waiting for response" screen (see Known
issues).

### Categories

Derived from the item type when the item is listed (`ItemCategories`, pure and unit tested). The first matching rule
wins:

| Category | Rule |
|---|---|
| Spawners | `spawner`, `trial_spawner` |
| Potions | `potion`, `splash_potion`, `lingering_potion`, `ominous_bottle` |
| Books | `book`, `writable_book`, `written_book`, `enchanted_book`, `knowledge_book` |
| Combat | item tags `swords`, `spears`, `head_armor`, `chest_armor`, `leg_armor`, `foot_armor`, `arrows`; `mace`, `trident`, `bow`, `crossbow`, `shield`, `totem_of_undying`, `end_crystal`, `wind_charge`, `wolf_armor`, `*_horse_armor`, `*_nautilus_armor` |
| Tools | item tags `axes`, `pickaxes`, `shovels`, `hoes`, `compasses`, `bundles`, `boats`, `chest_boats`; every `*bucket`, `*minecart`, `*_boat`, `*_raft`; `shears`, `flint_and_steel`, `fire_charge`, `fishing_rod`, `carrot_on_a_stick`, `warped_fungus_on_a_stick`, `brush`, `spyglass`, `clock`, `lead`, `name_tag`, `saddle`, `elytra`, `firework_rocket`, `ender_pearl`, `ender_eye`, `map`, `filled_map`, `goat_horn` |
| Food | anything edible (has the food component) |
| Blocks | anything that places a block |
| Misc | everything else |

## Placeholders

| Name | Value |
|---|---|
| `auction_listings` | Your active listings (including one that is being stored) |
| `auction_claims` | Stacks waiting in your claim box (all sources) |

Both read memory only.

## Main menu entry and events

Hub entry `auction` (order 30, permission `siftcore.command.ah`), which is also the `auction` pause-menu entry.

Events (`api.event`), fired on the player's thread before anything changes:

- `AuctionListEvent(seller, item, price, duration)`: cancellable; cancelling lists nothing and keeps the item.
- `AuctionPurchaseEvent(listing, buyer, seller, item, price)`: cancellable; only fired for purchases that pass the
  cheap checks (listing up and stored, not your own, not expired, price as confirmed, enough money).

The purchase transaction also fires the ledger's `EconomyTransactionEvent` (kind `ah_sale`) and, after the commit,
`EconomyTransactionCommittedEvent`. Listing, cancel and expiry transactions carry no money and are silent.

## Config summary (`features/auction.yml`)

| Key | Default | Meaning |
|---|---|---|
| `listings.duration` | `48h` | How long a listing stays up (1m to 30d); new listings only |
| `listings.default-slots` | `3` | Listing slots without a `siftcore.auction.listings.<n>` node (0: needs a node) |
| `price.minimum` / `price.maximum` | `1` / `10b` | Price limits of a whole listing |
| `price.minimum-per-item` / `price.maximum-per-item` | `1` / `0` | Per-item limits, multiplied by the amount (0 = none) |
| `tax` | `5` | Percent of the price the seller pays when it sells (0-100, two decimals, rounded down) |
| `blacklist.items` | barrier, bedrock, command blocks, structure blocks, jigsaw, light, debug stick, knowledge book, test blocks, every spawn egg | Item ids that can't be listed; `*` is a wildcard |
| `blacklist.allow-filled-containers` | `true` | Whether shulker boxes and bundles with items in them can be listed |
| `blacklist.max-item-size` | `128` | Largest item data in KiB (uncompressed, one item, contents included; 0 = no limit, up to 4096) |
| `blacklist.allow-creative-mode` | `false` | Whether players in creative or spectator mode can list |
| `block-in-combat` | `true` | Combat-tagged players can't use the auction house |
| `expiry-check` | `30s` | How often expired listings are returned (5s-10m) |
| `auto-claim` | `true` | Bought and taken-down items go straight into the inventory when they fit |
| `join-reminder` | `true` | Tell players about waiting claim box items when they join |
| `history-size` | `20` | Entries in `/ah history` (1-50) |
| `default-sort` | `newest` | Sort for players who never picked one |

Everything applies with `/sift reload` (the expiry timer is rescheduled). A broken value is reported with its path
and falls back to the default. Players can turn sale notifications off with the `auction-sales` setting.

## How a trade works

Active listings live in memory (`ListingBook`), loaded at startup, and change only inside economy transactions
(`LedgerTx`, run by `AuctionEngine`) under the economy lock. A listing is active exactly while it is in the book.
`ListingBook.check` validates a closing transition and the transaction's apply removes the listing, so the first
transaction that passes its check wins and every later one finds it gone. Every close is also guarded in storage:
`UPDATE ... WHERE id = ? AND state = 'ACTIVE'` must change exactly one row, otherwise the whole transaction (money,
listing, delivery) is reverted.

States: `ACTIVE` leads to exactly one of `SOLD` (`Sale`), `CANCELLED` (`Cancellation`, by the seller or staff) or
`EXPIRED` (`Expiry`); the stored state is derived from the transition (`Closing#result`), and closed listings never
change again.

- **Listing** (`/ah sell` or the sell form): validated (not air, not blacklisted, not a filled container if
  disallowed, not over the size limit, not in creative, price limits, slots), then a confirmation with the item, price, tax, proceeds,
  duration and slots used. On confirm, on the player's thread: every check runs again, the slot must still hold the
  same item (`isSimilar` and at least the amount), `AuctionListEvent` fires, the slot is checked once more (listeners
  are other plugins' code), the items are removed from the inventory and the player file is saved
  (`save-player-after-trade`), then one domain transaction checks the slot limit under the lock, adds the listing to
  the book (as unsaved) and inserts the row with the serialized item. Saving the player before the row can be stored
  keeps remove-before-grant true on disk too: a crash in between can lose the listing, never leave the items both in
  the inventory and on the auction house. If the transaction is refused the items go back to the same slot (or
  anywhere, or the claim box) and the player is saved again. If the commit fails, the ledger removes the listing again
  and the items go back to the player or their claim box.
- **Unsaved listings** hold their seller's slot but can't be bought, cancelled or expired until their row is
  committed, so no close can depend on a row that might still be rolled back.
- **Buying**: purchase confirmation with the item, price, seller, time left and balance. On confirm: cheap
  pre-checks, `AuctionPurchaseEvent`, then one transaction: transfer the price buyer to seller (kind `ah_sale`,
  ref `listing:<id>`), sink the tax from the seller (kind `ah_tax`, same transaction), check the listing is still the
  active, stored, unexpired listing at the confirmed price and not the buyer's own, remove it from the book, mark the
  row `SOLD` with buyer, close time and tax, and add the item to the buyer's claim box (`Deliveries.add`). After the
  commit the item is claimed straight into the inventory when it all fits (`Deliveries.claim`: marked claimed in
  storage first, handed over after), otherwise it waits in the claim box. The seller is told in chat if online.
- **Taking down / staff removal / expiry**: one transaction each that marks the row `CANCELLED` or `EXPIRED` and adds
  the item to the seller's claim box; a take-down is auto-claimed like a purchase. The expiry timer runs async every
  `expiry-check` over the in-memory book only (no world access) and tells online sellers.
- **Claim box**: claims are remove-before-grant (stored as claimed before the item is handed over on the player's
  thread), only stacks that fit completely are claimed, and anything that cannot be handed over (inventory filled
  meanwhile, player left, server stopping) goes back into the claim box, never on the ground.

Abuse limits: prices go through the core money parser, which answers exponent notation (`1e100000000`, a few
characters for a number with a hundred million digits) from the digit count and scale instead of building the number.
`blacklist.max-item-size` keeps items with huge data (hundreds of long lore lines, books full of text) off the auction
house: every menu page and dialog sends the listed items to every viewer, so a few of them could otherwise kick
viewers or make their clients stall. The size is measured uncompressed, since compression would hide repeated text.

Double clicks and replays are harmless: dialogs are one-shot, the claim box menu is locked while a claim is stored,
and every transaction re-checks the state it changes. Buyers always see the whole item: the tooltip-hiding component
is reset on every copy shown in menus and dialogs, so hidden enchantments, curses or contents can't be used to trick
a buyer.

Shutdown: the expiry timer stops, the database is flushed, callbacks of trades that were still being stored are
awaited, and every item that was about to be handed to a player is put back into the claim box and stored before the
database closes.

## Self-test

`/sift selftest` checks the tax math, an exactly-once race on a private book, the category rules, the search text, that
every active row was loaded, that memory matches storage at one consistent point (snapshot under the economy lock,
compared on the ordered writer), that the expiry timer runs and that nothing is long overdue.

## Testing

- Unit tests (`src/test/java/.../feature/auction`): the listing state machine and its races (`ListingBookTest`), the
  engine against the real ledger and SQLite schema including concurrent buy/cancel/expire races with exactly one
  winner, storage failures that revert everything, restart loading and history (`AuctionEngineTest`), tax, percent and
  price math (`AuctionMathTest`), exponent prices refused within a time limit (`AuctionPriceInputTest`), the item
  size measurement (`ItemDataTest`), the shutdown counter (`InFlightTest`), categories, blacklist patterns, sort orders
  and inventory planning (`ItemRulesTest`), and the bundled config and lang files (`AuctionResourcesTest`).
- End to end (`tools/e2e`, `AuctionScenarios`): selling with `/ah sell` and the sell form, buying (money, tax, item,
  receipts), taking down, the claim box (full inventory, claim one, claim all, join reminder), refusals (including
  oversized items and exponent prices), triple clicks
  and replayed tokens, two buyers racing for one listing, sort/filter/search, history, staff removal, the hub entry,
  expiry through the real timer, and `auction-persist-setup` / `auction-persist-check` across a restart.

## Known issues

- The 26.2 client ignores `ClientboundClearDialogPacket` while it shows its "Waiting for response" screen (after a
  dialog button with `WAIT_FOR_RESPONSE`), so `Submission#close()` alone leaves players on that screen for about five
  seconds. The auction house always reopens a menu or closes the screen after a dialog button; other features using
  only `close()` are affected until the framework sends a container close as well.
