# Auction house

SiftVanilla's auction house is **AxAuctions 2.7.2** (Artillex Studios, licensed, closed source). The owner chose it
for its anti-dupe protections. SiftCore's own auction house (feature `auction`, documented in the second half of
this page) stays in the plugin as the fallback: it takes over `/ah` and the menu button automatically whenever
AxAuctions is not running.

> **Status, 8 Oct 2026: AxAuctions 2.7.2 does not start.** It started 12 times between 08:02 and 09:10 UTC and
> everything under "Verified behaviour" was checked then. Since about 09:12 UTC every start fails, on a fresh server
> directory too and with a complete library cache. See "Start failure" below. Do not put it on the live server until
> it starts again (or a newer AxAuctions does) and `e2e run` of the `axauctions-*` scenarios passes.

## AxAuctions

### Start failure

While AxAuctions loads its libraries (in its constructor, before `onEnable`), it sends a POST request to
`pl.artillex-studios.com`. That host is not one of the library repositories it lists in its library cache, and its
root now answers with a redirect to `repo.artillex-studios.com`. With the answer it gets now, the plugin fails in its
own error path, before its caching library is loaded, and the server skips it:

```
[16:35:48 ERROR]: [ModernPluginLoadingStrategy] Could not load plugin 'AxAuctions-2.7.2.jar' in folder 'plugins'
org.bukkit.plugin.InvalidPluginException: Exception initializing main class `com.artillexstudios.axauctions.AxAuctions'
Caused by: java.lang.NoClassDefFoundError: com/artillexstudios/axauctions/libs/axapi/libs/caffeine/caffeine/cache/Caffeine
	at AxAuctions-2.7.2.jar//com.artillexstudios.axauctions.libs.axapi.utils.StringUtils.<clinit>(StringUtils.java:50)
	at AxAuctions-2.7.2.jar//com.artillexstudios.axauctions.libraries.Libraries.fetchLibrary(Libraries.java:87)
	at AxAuctions-2.7.2.jar//com.artillexstudios.axauctions.libraries.Libraries.load(Libraries.java:62)
```

When that host can't be reached at all (checked once, by refusing the connection), the request fails with a logged
`java.io.IOException` from `Requests.post` (`Libraries.fetchLibrary(Libraries.java:75)`), most libraries are skipped,
and the plugin disables itself while enabling:
`NoClassDefFoundError: com/artillexstudios/axauctions/libs/bcommons/modules/ConfigUtils`.

So 2.7.2 needs that Artillex Studios server to answer the way it did this morning at every start. Configuration
can't change this, and the jar must not be patched (licence). What to do:

1. Ask Artillex Studios support about `pl.artillex-studios.com` and AxAuctions 2.7.2 on 26.2, and download the
   current version with the licence (its update check reported **2.9.4**).
2. Test the new jar on a test server first: `e2e run` with every `axauctions-*` scenario (see Testing).
3. The live host needs outbound HTTPS to `repo.artillex-studios.com`, `repo2.artillex-studios.com`,
   `repo1.maven.org` (library downloads on the first start, about 25 MB into `plugins/AxAPI/libraries/`) and
   `pl.artillex-studios.com`.

Until then nothing breaks: with AxAuctions missing or failed, SiftCore keeps `/ah` and the menu button.

### Install

1. Put `AxAuctions-<version>.jar` in `plugins/` next to VaultUnlocked, PlaceholderAPI, LuckPerms and SiftCore. The
   jar is licensed: never commit it (`.gitignore` excludes `server/**/*.jar`).
2. Copy `server/plugins/AxAuctions/` from this repository to the server's `plugins/AxAuctions/` (all files,
   including `guis/`), and `server/plugins/SiftCore/commands.yml` to `plugins/SiftCore/commands.yml`.
3. Give the default listing slots: `lp group default permission set axauctions.limit.3 true`. Ranks later get
   `axauctions.limit.5`, `.7`, `.10`, `.15` (the highest node counts, they don't add up). Without any node a
   player has 1 slot.
4. Start the server. The first start downloads the libraries. Check the log for `Loaded currency integrations: Vault`
   and `Successfully registered internal expansion: axauctions`.
5. After editing a file, `/ahadmin reload` applies it (expected: `Reloaded 16 files with 0 failures`). The
   `version:` keys must stay as they are, or the plugin migrates (rewrites) the file.

A newer AxAuctions may add keys to these files on its first start; compare its generated defaults with this
directory and style any new text the same way.

### How it plugs into SiftCore

**Money.** AxAuctions uses the Vault currency (`currencies.yml`: only `Vault` is registered, 5% tax). It calls the
legacy `net.milkbowl.vault.economy.Economy` interface with `double` amounts: `has`, `withdrawPlayer` on the buyer,
then `depositPlayer` on the seller with the price minus tax. On test servers that is the TEST-ONLY TestEco economy;
on the live server it will be SiftCore's Vault provider (not built yet). That provider must:

- **never refuse a deposit** of a valid amount. AxAuctions does not undo a purchase when the seller's deposit fails:
  the buyer keeps the item, has paid, and the seller's money is gone (verified, see below).
- **accept amounts that aren't whole dollars.** The 5% tax makes most payouts fractional ($33 sells for $31.35)
  and AxAuctions accepts decimal prices (`/ah sell 10.5`). Its menus and messages round every amount half-even to
  whole dollars (`#,##0`): 10.5 shows as $10, 31.35 as $31, 9.5 as $10. If the provider rounds every Vault amount
  the same way (half-even to whole dollars), players get and pay exactly what AxAuctions shows them, and the
  ledger's books still balance (withdrawal and deposit are separate postings; the difference is the tax sink).
- answer `has` consistently with `withdrawPlayer` (same rounding), and be safe to call from any thread
  (AxAuctions calls it from region and async threads).

**Commands.** AxAuctions registers `/ah` (aliases `/auction`, `/auctionhouse`, `/axah`) and `/ahadmin`
(`/auctionadmin`, `/axahadmin`) through its own command framework. SiftCore registers its commands last, so its own
`/ah` would shadow AxAuctions'. `plugins/SiftCore/commands.yml` therefore has:

```yaml
commands:
  ah:
    yield-to: AxAuctions
```

`yield-to` (new in this change, `CommandSettings`/`CommandService`) skips registering the command while the named
plugin is enabled. Paper runs SiftCore's command registration after every plugin was enabled (checked: with
`yield-to: TestEco`, a plugin enabled after SiftCore, the log shows `[SiftCore] /ah is left to TestEco
(commands.yml yield-to)` after `[SiftE2E] Enabling`). If AxAuctions is missing or failed, SiftCore's `/ah` is
registered as usual. Changes apply after a restart.

**Main menu.** The `auction` hub entry (main menu button and pause-menu entry) runs `/ah` for the player when
AxAuctions is enabled, otherwise it opens SiftCore's own menu (`AuctionFeature#openFromHub`, checked on every
click). That `/ah` is fired as `PlayerCommandPreprocessEvent` first, like a typed command, so the command guards
(blocked in combat, blocked while frozen) apply to the button too. AxAuctions' Back button (slot 46 of the auction house) runs `/menu`, SiftCore's main menu.

**Permissions** (AxAuctions'):

| Node | Default | What |
|---|---|---|
| `axauctions.use` | everyone | Player commands (`/ah`, `open`, `sell`, `search`, `view`, `history`, `deleted`) |
| `axauctions.limit.<n>` | none (1 slot) | Listing slots; default group gets `.3`, ranks `.5`/`.7`/`.10`/`.15` |
| `axauctions.admin` | op | `/ahadmin reload`, `forceopen`, `history`, `deleted`, `logs`, `limit`, `convert` |
| `axauctions.admin.removal` | op | Shift click any listing to remove it |
| `axauctions.history.admin.take`, `axauctions.deleted.admin.take` | op | Take a copy out of the history or deleted-items viewers (staff restores) |

**Placeholders** (PlaceholderAPI, prefix `axauctions_`; inside AxAuctions' own menus without the prefix):
`sell_limit`, `sell_count`, `active_count`, `purchasable_count`, `expired_count`, `total_active_count`,
`items_sold`, `items_purchased`, `money_made`, `money_spent` (and `total_` variants, `_raw` for unformatted),
`selected_category`, `selected_sorting`, `categories_enabled`, `expired_items_enabled`. Checked:
`%axauctions_sell_limit%` = 3 and `%axauctions_sell_count%` follow listings.

### Configuration

Everything is in `server/plugins/AxAuctions/`. The first commit on this branch is the files the jar ships, so
`git diff 7a3494f -- server/plugins/AxAuctions` shows every SiftVanilla change. The `version:` keys are the
plugin's own (config 20, lang 15, currencies 8, categories 1, discord 1).

| Setting | Value | Why |
|---|---|---|
| `prefix` | `""` | No plugin prefix, like the rest of the server |
| `database.type` | `h2` | One server; see "Database" below |
| `multi-server-support.mode` | `disabled` | One server |
| `currencies.yml` | Vault only, `tax: 5`, Experience/Level and every other integration off | One currency; the tax is a money sink |
| `min-price` / `max-price` | `1` / `10000000000` | $1 to $10,000,000,000 |
| `number-formatting` | mode 0, `#,##0` | `$10,000`, no decimals |
| `timer-format`, `date-format` | 3, `d MMM yyyy, HH:mm` | `1d 23h 59m 59s`, `8 Oct 2026, 09:03` |
| `item-expire-time` | `172800` | Listings last 48 hours |
| `item-deletion-time` | `604800` | Expired listings can be taken back for 7 days |
| `auction-listing-confirmation` | `true` | `/ah sell` shows the item and price before listing |
| `auction-purchase-confirmation` / `allow-confirmation-skipping` | `true` / `false` | Every purchase is confirmed |
| `separate-expired-items` | `true` | Expired listings have their own menu |
| `search-mode` | `sign` | Search by typing on a sign |
| `enable-currency-selector` | `false` | One currency |
| `blacklist-items` | barrier, bedrock, command blocks, structure blocks, jigsaw, light, debug stick, knowledge book, test blocks, spawn eggs | Same list as SiftCore's own auction house |
| `content-limit-bytes` | 5000 / 20000 / 20000 (defaults) | Keeps huge items off the menus |
| `enable-safety` | `true` | Lets Artillex switch features off remotely if an exploit is found |
| `update-notifier` | console only | Staff see new versions in the console |
| `discord.yml` | empty `url`, both events off | No webhooks |
| `categories.yml` | `enabled: true`; all, blocks, tools, combat, food, potions, books, spawners, redstone, misc | See below |

What "deleted" means: after 7 days in Expired listings, an item moves to `/ah deleted`. The seller can still see it
there but can't take it back (verified). The item stays in the database: staff with `axauctions.deleted.admin.take`
can take a copy out of `/ahadmin deleted <player>` and give it back. Longer keep times (30 days, a year, `-1`) were
prepared as a test (`axauctions-deletion-time`) but not run, so 7 days is set as specified.

**Categories** match SiftCore's own auction house (blocks, tools, combat for weapons and armour, food, potions,
books, spawners, everything else) plus redstone for redstone components that SiftCore files under blocks or
misc (redstone dust and blocks, torches, repeaters, comparators, observers, pistons, droppers, dispensers, hoppers,
crafters, levers, buttons, pressure plates, rails, iron doors and trapdoors, lamps, bulbs, sensors, slime and
honey blocks, TNT, targets, note blocks, trapped chests, lightning rods). AxAuctions matches categories only by
item id patterns, so the patterns are generated: the `axauctions-category-rules` scenario classifies every item of
the running Minecraft version with SiftCore's rules (`ItemCategories`) and writes
`plugins/SiftE2E/axauctions-categories.tsv`, then

```sh
python3 tools/axauctions/gencategories.py axauctions-categories.tsv > fragment.txt
python3 tools/axauctions/writecategories.py fragment.txt server/plugins/AxAuctions/categories.yml
```

writes `categories.yml` (every item in exactly one category; the generator asserts it). Run the scenario again
after a Minecraft update; it fails if any item is in the wrong category.

### Look

Every visible string follows the design system: white primary text, gray secondary text, money only in `#1AFF1A`,
no bold, no gradients, no other colours, no separators or symbols, sentence case. Plain menu titles: "Auction
house", "Your listings", "Expired listings", "Confirm listing", "Confirm purchase", "Categories", "History",
"Deleted items", "Contents", "Auction log", "Remove listing", "Pick a currency". Buttons have a white name and one or two
gray lines; sounds are `ui.button.click` at volume 0.25. No filler glass except plain light gray panes with an
empty name in the shulker preview and currency selector. Prices use the currency format `&#1AFF1A$%price%`, and
messages switch back to white after a price (`for %price%&f.`).

Menu layouts follow SiftCore's: rows 1 to 5 are listings (45 per page), the bottom row is 45 previous page,
46 back, 47 sort, 48 category, 49 search (auction house) or information, 50 to 52 the other menus, 53 next page.
The auction house, Your listings and Expired listings all have the sort and category buttons. In the auction house
the category button opens the category menu; in the other two it steps through the categories in place (the
category menu always returns to the auction house).

A static check of every string (no colour codes but `&f`, `&7`, `&#1AFF1A`; money colour only on amounts; plain
titles; white button names; gray lore; empty prefix) passes on this directory and finds 2,244 problems in the
plugin's defaults. The e2e scenarios check the rendered menus and chat the same way.

### Verified behaviour

Checked with the e2e bots (`AxAuctionsScenarios`) between 08:49 and 09:12 UTC on 8 Oct 2026, on Canvas 26.2
build 962 with VaultUnlocked 2.20.3, PlaceholderAPI 2.12.3, LuckPerms 5.5.87, the TEST-ONLY TestEco economy and
SiftCore, with this configuration (byte for byte, as the plugin left it after `/ahadmin reload`) except the later
changes listed under "Not verified".

- **Start**: `Loaded currency integrations: Vault`, `Successfully registered internal expansion: axauctions
  [2.7.2]`, `/ah` is AxAuctions' command (`PluginVanillaCommandWrapper owned by AxAuctions`).
- **Sell and buy**: `/ah sell 100` with 16 diamonds opens Confirm listing (price, "Ends in 2d 0h 0m 0s", "5% tax is
  taken when it sells"); confirming lists them ("Listed on the auction house for $100.") and empties the hand. The
  buyer sees "Price $100 / Seller ... / Ends in 1d 23h 59m 59s / Click to buy", confirms, gets the 16 diamonds and
  pays $100; the seller gets $95 ("... bought your 16 diamond for $100. You got $95 after tax."). The sum of all
  balances fell by exactly the $5 tax; the listing is gone.
- **Cancel** on Confirm listing keeps the item. `/ah sell 40 5` lists 5 of 16.
- **Limit**: the 4th listing is refused ("All 3 of your listing slots are in use.") and the item stays; with
  `axauctions.limit.5` the 4th lists and the slots placeholder shows 5.
- **Take down**: clicking your listing in Your listings returns the 7 gold ingots ("Listing taken down. The item is
  back in your inventory.") and nobody else sees it any more.
- **Search**: the search button opens a sign; typing part of an item name shows only matching listings; shift click
  clears it; `/ah search cobblestone` works too.
- **Categories**: one listing per category; each category shows only its own listing, "all items" shows all nine.
  `axauctions-category-rules`: all 1,434 item types of 26.2 are in exactly one category.
- **Shulker preview**: right click on a listed shulker box opens "Contents" with its 5 diamonds, golden apple and
  64 torches.
- **History**: buyer and seller both see "Seller / Buyer / Price $60 / Sold 8 Oct 2026, 09:03".
- **Two buyers at once**: both confirm the same listing in the same instant: one gets the item, $400 is paid once,
  the seller gets $380, all balances fell by exactly $20.
- **Moving the item while Confirm listing is open** (number key swap, shift click, picking the stack up, swapping
  to the off hand, dropping one, dropping the stack, selecting another hotbar slot): the diamonds kept, listed and
  dropped always add up to the 10 the bot had. It lists what is still in the inventory, or refuses with "Hold the
  item you want to sell."
- **Economy refusals** (TestEco told to refuse): a refused withdrawal gives no item, the listing stays, "You can't
  afford that."; a refused deposit to the seller still completes the purchase: the buyer has the items and paid
  $200, the seller got nothing (told "You got $190 after tax"), and the money is gone (no retry within 3 s).
- **Prices**: 0, 0.5, -5 are refused on confirm ("The price must be at least $1."), 10000000001 too ("The price can
  be at most $10,000,000,000."), `abc`, `NaN`, `Infinity` with "That price isn't a number.", bedrock and spawn eggs
  with "That item can't be sold on the auction house.", an empty hand with "Hold the item you want to sell.".
  Quirks of its number parser (the confirmation always shows the parsed price): `1k` is $1,000, `2.5m` is
  $2,500,000, `100abc` is $100, `1e3` and `1_000` are $1, `0x10` is $0 (refused), `10,000` gives a command syntax
  error. Decimal prices are accepted: `10.5` lists at 10.5 and shows $10; the buyer paid 10.5 and the seller got
  9.975. At $33 the seller got 31.35, shown as $31.
- **Expiry** (test copy with a 20 s expiry): after 19 s the seller is told "Your listing of 9 amethyst shard expired
  on 8 Oct 2026, 09:11. Take it back from your expired listings."; Expired listings shows "Price $9 / Deleted in 6d
  23h 59m 58s / Click to take it back" and a click returns the 9 shards. With a 20 s deletion time it moves to
  Deleted items ("Seller ... / Price $4 / Deleted 8 Oct 2026, 09:12") and a click there gives nothing back.
- **Reload**: `/ahadmin reload` reports `Reloaded 16 files with 0 failures`; the files stay unchanged.
- **Every menu** (auction house, Your listings, Expired listings, Categories, History, Deleted items, Confirm
  listing, Confirm purchase, Contents) and every chat message passed the design check; the Back button opens
  SiftCore's main menu.
- **Threads**: no Folia or Canvas thread-check error in any of those runs (Canvas `guard-severity: THROW`).

Checked on 8 Oct 2026 at 17:02 UTC with AxAuctions failing to start (current state): SiftCore keeps `/ah` and the
menu button (`commands.yml` `yield-to`), the SiftCore auction scenarios pass and every `axauctions-*` scenario
skips cleanly.

**Warnings** while it ran: at every enable and reload
`java.lang.NullPointerException: Cannot invoke "com.google.gson.JsonElement.getAsString()" because the return value
of "com.google.gson.JsonObject.get(String)" is null` at `LanguageManager.reload(LanguageManager.java:50)`. It fails
to read Minecraft's item names for `language: en_US` (its `assets/en_us.yml` stays empty), so messages name items
by their id ("16 diamond", "1 clay ball"). Its H2 trace file logs two harmless `DROP TABLE` errors for old tables
at each start.

### Not verified

- The sort and category buttons added to Your listings and Expired listings, the `&f` after prices in four
  messages, and the main menu button opening AxAuctions (`axauctions-tour` checks all three) were added after
  AxAuctions stopped starting.
- Deletion times other than 7 days, staff restoring deleted items, a restart with listings up, a crash during a
  trade, paying sellers who are offline, and MySQL.
- Behaviour with SiftCore's real Vault provider (not built yet).

### Database

**H2 for the live server.** SiftVanilla is one server, so the embedded H2 file (`plugins/AxAuctions/data.mv.db`)
needs no other service, no network or credentials, adds no failure mode at startup and is included in the panel's
file backups. MySQL/MariaDB is only worth it for multi-server sync (which requires it) or if the host offers a
managed database with its own backups. With H2, always stop the server cleanly (no kill from the panel), because an
embedded database file can be damaged by a hard kill mid-write, and back up `plugins/AxAuctions/` with the world.

### Testing

`tools/e2e/src/net/siftvanilla/e2e/AxAuctionsScenarios.java` (registered in `FeatureScenarios`): `axauctions-boot`,
`-sell-buy`, `-sell-cancel`, `-sell-amount`, `-refusals`, `-price-input`, `-fractions`, `-economy-failures`,
`-limit`, `-take-down`, `-search`, `-categories`, `-category-rules`, `-shulker`, `-history`, `-sell-gui-moves`,
`-buy-race`, `-tour`, `-expiry`, `-deletion-time`. Each skips (and passes) when AxAuctions is not installed or did
not start; SiftCore's `auction-*` scenarios skip while AxAuctions runs. They need the AxAuctions jar, VaultUnlocked,
PlaceholderAPI, LuckPerms and a Vault economy in the test server (the money checks read and set balances through
whatever Vault economy is registered; the conservation and refusal checks need the TEST-ONLY TestEco, which is
never in this repository). `-expiry` and `-deletion-time` edit `config.yml` on the test server and restore it.

## SiftCore's own auction house (`auction`, the fallback)

Players list items for a fixed price; other players buy them. Bought, expired and taken-down items go through the
claim box. Package `feature/auction`, config `features/auction.yml`, text `lang/auction.yml`, table
`auction_listings` (migration V002, no schema change) plus the core `deliveries` table (the claim box).

### Commands and permissions

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
default 3, prospector 8, baron 20, tycoon 40 ([monetization](../monetization.md)).

`/ah` uses the `ah` entry of `commands.yml` for its cooldown and aliases. On SiftVanilla that entry has
`yield-to: AxAuctions`, so this `/ah` is only registered when AxAuctions is not running (see above).

### The menus

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

#### Categories

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

### Placeholders

| Name | Value |
|---|---|
| `auction_listings` | Your active listings (including one that is being stored) |
| `auction_claims` | Stacks waiting in your claim box (all sources) |

Both read memory only.

### Main menu entry and events

Hub entry `auction` (order 30, permission `siftcore.command.ah`), which is also the `auction` pause-menu entry. While
AxAuctions is enabled it runs `/ah` (AxAuctions) for the player instead of opening this menu.

Events (`api.event`), fired on the player's thread before anything changes:

- `AuctionListEvent(seller, item, price, duration)`: cancellable; cancelling lists nothing and keeps the item.
- `AuctionPurchaseEvent(listing, buyer, seller, item, price)`: cancellable; only fired for purchases that pass the
  cheap checks (listing up and stored, not your own, not expired, price as confirmed, enough money).

The purchase transaction also fires the ledger's `EconomyTransactionEvent` (kind `ah_sale`) and, after the commit,
`EconomyTransactionCommittedEvent`. Listing, cancel and expiry transactions carry no money and are silent.

### Config summary (`features/auction.yml`)

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

### How a trade works

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

### Self-test

`/sift selftest` checks the tax math, an exactly-once race on a private book, the category rules, the search text, that
every active row was loaded, that memory matches storage at one consistent point (snapshot under the economy lock,
compared on the ordered writer), that the expiry timer runs and that nothing is long overdue.

### Testing

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

### Known issues

- The 26.2 client ignores `ClientboundClearDialogPacket` while it shows its "Waiting for response" screen (after a
  dialog button with `WAIT_FOR_RESPONSE`), so `Submission#close()` alone leaves players on that screen for about five
  seconds. The auction house always reopens a menu or closes the screen after a dialog button; other features using
  only `close()` are affected until the framework sends a container close as well.
