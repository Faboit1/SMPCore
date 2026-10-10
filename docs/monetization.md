# Monetization

How SiftVanilla makes money without selling an advantage: three paid ranks, server-wide boosters and gift cards,
all delivered by SiftCore from a Tebex store. This page covers what is sold, the perks of each rank, the exact
LuckPerms and Tebex setup, delivery and refunds, and the rules behind every choice.

## Summary

| Sold | Never sold, and why |
|---|---|
| Three ranks (Prospector, Baron, Tycoon), 30 days or lifetime | Sell or AFK multipliers: on a PvP economy money buys gear, so a personal multiplier is a competitive advantage |
| Server-wide sell boosters (everyone online benefits) | Crate keys, shards or anything random: Tebex AUP 1.6 bans paid chance, and several countries treat it as gambling |
| Gift cards and gifting | In-game money: it feeds real-money trading |
| | Bigger teams, combat kits, /fly outside spawn, /feed, /heal, /repair, cooldown or warmup bypasses, AFK-kick bypass |

Ranks buy **capacity** (more homes, listings, orders, friends, spawner stack room), **convenience** (portable
workstations, /ec, daily supply kits, spawn flight, joining a full server) and **cosmetics** (coloured rank, chat
colour, nickname, chat tags, join messages, kill effects). Every perk command is refused in combat.

## The ranks

Each rank is a LuckPerms group that inherits the one below it, so a Tycoon has everything a Baron has.

| Tier | Group | Weight | Rank colour | Name colour (tab, nametag) | 30 days | Lifetime |
|---|---|---|---|---|---|---|
| I | `prospector` | 10 | `#5FA8FF` river blue | `#9CCBFF` / `&b` | €4.99 | €29.99 |
| II | `baron` | 20 | `#FFAA00` gold | `#FFD27A` / `&6` | €9.99 | €59.99 |
| III | `tycoon` | 30 | gradient `#FF6AD5` to `#B26BFF` | gradient / `&d` | €19.99 | €119.99 |
| (free) | `default` | 0 | none | `&7` gray | | |

Prices are in EUR (Tebex converts at checkout; use the same numbers if the store base is USD).

- **Prospector at €4.99** matches the entry price of comparable servers and Tebex's entry-subscription guidance.
- **Each step doubles the price** while capacity grows by more than double, and Baron is the first tier with the most
  wanted perks (/ec, chat colour, /nick, spawn flight), which makes it the natural middle choice.
- **Tycoon** carries the largest capacity, the exclusive cosmetics and a server-wide booster on every purchase and
  renewal. If Tycoon is under 10% of rank sales after 60 days, lower it to €14.99.
- **Lifetime is six months' price.** It serves players who can't pay recurringly (paysafecard, a parent's card)
  without making the monthly plan pointless. Lifetime lasts as long as SiftVanilla runs, through map resets.
- After fees (PayPal through Tebex: 5% + 2.49% + €0.40) roughly €4.22, €8.84 and €18.10 remain per month before
  VAT. Tebex is the merchant of record and handles VAT.

### Colours

Rank colours avoid every hue that already means something: red is errors and kills (`#FF5555`, `#FF4B4B`), green is
money (`#1AFF1A`, `#9dfb2b`), purple `#915dff` is shards, aqua `#3CC4EE` is the brand, and yellow `#fddf00` is the
player's own name on the scoreboard. No rank is red, so errors stay unmistakable. Old clients without hex colours
(through ViaBackwards) fall back to `&b`, `&6` and `&d`, which still read as three different ranks.

## Perks

Every perk below that is a permission node or LuckPerms meta is live on the server: the nodes are set on the LuckPerms
groups (undo log rows 26, 34, 35 and 39) and SiftCore reads each one. The last column names the row that set it up.
The two perks only the store delivers, Tycoon's server-wide booster and the Discord role, are not live: no store
plugin is installed yet (see [server-setup.md](server-setup.md#plugins)), so nothing is sold until the Tebex setup
below is done. Until then ranks are given by hand (`lp user <name> parent add <group>` or `/sift store rank` from the
console).

| Perk | default | Prospector | Baron | Tycoon | Node | Live (undo log) |
|---|---|---|---|---|---|---|
| Homes | 2 | 6 | 15 | 40 | `siftcore.homes.<n>` | yes (26) |
| Auction listings | 3 | 8 | 20 | 40 | `siftcore.auction.listings.<n>` (SiftCore's auction house, which serves `/ah`) | yes (26) |
| Buy-order slots | 3 | 8 | 20 | 40 | `siftcore.orders.limit.<n>` | yes (26) |
| Friends | 50 | 100 | 200 | 500 | `siftcore.friends.limit.<n>` | yes (26) |
| Spawner stack cap | 1,000 | 1,250 | 1,500 | 2,000 | `siftcore.spawners.stack.<bonus>` (tidiness only: output per spawner is the same) | yes (26) |
| Daily supply kit | daily food | + Prospector kit | + Baron kit | + Tycoon kit | `siftcore.kit.<id>` (supplies only: no armour, weapons, gapples, totems, pearls or keys) | yes (26) |
| /craft, /trash, /hat | | yes | yes | yes | `siftcore.perk.craft`, `.trash`, `.hat` | yes (26) |
| /ec, /stonecutter, /loom, /cartography, /grindstone | | | yes | yes | `siftcore.perk.<name>` | yes (26) |
| /anvil, /smithing | | | | yes | `siftcore.perk.anvil`, `.smithing` | yes (26) |
| Coloured rank in chat, tab and nametag | gray name | blue | gold | gradient | LuckPerms meta `siftcore-rank`, `siftcore-rank-color`, `siftcore-rank-gradient` (SiftCore's chat) and the prefix (TAB's tab list and nametags) | yes (26) |
| Chat tags (/tags) | | 3 tags | +7 tags | +6 tags and a monthly exclusive | `siftcore.tags.<tier>` | yes (34) |
| Chat colour (/chatcolor) | | | 8 colours (no red, no green) | + any hex and gradients | `siftcore.chat.color`, `siftcore.chat.color.hex` | yes (34) |
| Nickname (/nick, real name on hover) | | | one colour | + hex and gradient | `siftcore.command.nick`, `siftcore.nick.gradient` | yes (34) |
| Join and leave message | | | rank message | custom message | `siftcore.join.message`, `siftcore.join.message.custom` | yes (34) |
| Kill effects (/killeffect, visual only) | | | | all effects | `siftcore.killeffect.*` | yes (34) |
| Flight inside the protected spawn (off on leaving or when tagged; at most `fly.max-height`, 48 blocks, above the spawn point; a soft landing) | | | yes | yes | `siftcore.spawn.fly` | yes (35) |
| Join when the server is full | | | yes | yes | `siftcore.join.full` | yes (35) |
| Show my rank (a setting to hide the rank tag in chat, on profiles and in SiftCore's placeholders; see below) | | yes | yes | yes | `siftcore.settings.hide-rank` | yes (39) |
| Server-wide +10% sell booster for 30 min on every purchase | | | | yes | store command | not yet: needs the Tebex package command (setup below); SiftCore's side is deployed (35) |
| Purchase history (/purchases) | yes | yes | yes | yes | `siftcore.command.purchases` (everyone) | yes (35) |
| Discord role | | yes | yes | yes | Tebex Discord delivery | not yet: Tebex side, no store yet |
| Team size | 5 | 5 | 5 | 5 | not sold | |
| Sell multiplier | 1x | 1x | 1x | 1x | not sold | |
| AFK zone shards | 1 per minute | same | same | same | not sold | |
| Crate keys | keyall: 1 Basic key every 4h | same | same | same | not sold | |

**Show my rank** is the one rank perk that is a setting: players with a paid rank get "Show my rank" in the Privacy
group of `/settings` and can turn their rank tag off. Their rank then disappears from SiftCore's chat, profiles, join
lines and the `%siftcore_rank%` placeholders, but not from TAB's tab list and nametags as TAB is configured now (it
reads LuckPerms' prefix directly; see [server-setup.md](server-setup.md#tab)). Their perks stay.

**Settings are never a perk.** Every player, ranked or not, has the same per-player settings: 133 settings in 14
groups (chat, sounds, alerts, privacy, the money format, the sidebar, teleports, market alerts and more; see
[features/settings.md](features/settings.md)). A few settings only show where they mean something (a rank to hide, a
perk command whose screen they adjust, staff tools), never because someone paid. Players see their purchases with
`/purchases`, the active booster with `/booster`, and anyone can turn off other players' chat colours and kill effects
in `/settings`.

## LuckPerms setup

These are the commands the live server was set up with (undo log row 26). Run them in the console.

```
lp creategroup prospector
lp group prospector setweight 10
lp group prospector parent add default
lp group prospector setdisplayname Prospector
lp group prospector meta set siftcore-rank Prospector
lp group prospector meta set siftcore-rank-color "#5FA8FF"
lp group prospector meta setprefix 10 "&0&l[&#5FA8FF&lProspector&0&l] &#9CCBFF"
lp group prospector permission set siftcore.homes.6 true
lp group prospector permission set siftcore.auction.listings.8 true
lp group prospector permission set siftcore.spawners.stack.250 true
lp group prospector permission set siftcore.orders.limit.8 true
lp group prospector permission set siftcore.friends.limit.100 true
lp group prospector permission set siftcore.kit.prospector true
lp group prospector permission set siftcore.perk.craft true
lp group prospector permission set siftcore.perk.trash true
lp group prospector permission set siftcore.perk.hat true
lp group prospector permission set siftcore.tags.prospector true
lp group prospector permission set siftcore.settings.hide-rank true

lp creategroup baron
lp group baron setweight 20
lp group baron parent add prospector
lp group baron setdisplayname Baron
lp group baron meta set siftcore-rank Baron
lp group baron meta set siftcore-rank-color "#FFAA00"
lp group baron meta setprefix 20 "&0&l[&#FFAA00&lBaron&0&l] &#FFD27A"
lp group baron permission set siftcore.homes.15 true
lp group baron permission set siftcore.auction.listings.20 true
lp group baron permission set siftcore.spawners.stack.500 true
lp group baron permission set siftcore.orders.limit.20 true
lp group baron permission set siftcore.friends.limit.200 true
lp group baron permission set siftcore.kit.baron true
lp group baron permission set siftcore.perk.ec true
lp group baron permission set siftcore.perk.stonecutter true
lp group baron permission set siftcore.perk.loom true
lp group baron permission set siftcore.perk.cartography true
lp group baron permission set siftcore.perk.grindstone true
lp group baron permission set siftcore.tags.baron true
lp group baron permission set siftcore.chat.color true
lp group baron permission set siftcore.command.nick true
lp group baron permission set siftcore.join.message true
lp group baron permission set siftcore.spawn.fly true
lp group baron permission set siftcore.join.full true

lp creategroup tycoon
lp group tycoon setweight 30
lp group tycoon parent add baron
lp group tycoon setdisplayname Tycoon
lp group tycoon meta set siftcore-rank Tycoon
lp group tycoon meta set siftcore-rank-color "#FF6AD5"
lp group tycoon meta set siftcore-rank-gradient "#FF6AD5:#B26BFF"
lp group tycoon meta setprefix 30 "&0&l[&#FF6AD5&lT&#F06ADD&ly&#E06AE6&lc&#D16BEE&lo&#C16BF7&lo&#B26BFF&ln&0&l] &#E58BFF"
lp group tycoon permission set siftcore.homes.40 true
lp group tycoon permission set siftcore.auction.listings.40 true
lp group tycoon permission set siftcore.spawners.stack.1000 true
lp group tycoon permission set siftcore.orders.limit.40 true
lp group tycoon permission set siftcore.friends.limit.500 true
lp group tycoon permission set siftcore.kit.tycoon true
lp group tycoon permission set siftcore.perk.anvil true
lp group tycoon permission set siftcore.perk.smithing true
lp group tycoon permission set siftcore.tags.tycoon true
lp group tycoon permission set siftcore.chat.color.hex true
lp group tycoon permission set siftcore.nick.gradient true
lp group tycoon permission set siftcore.join.message.custom true
lp group tycoon permission set siftcore.killeffect.* true

lp createtrack ranks
lp track ranks append prospector
lp track ranks append baron
lp track ranks append tycoon
```

Staff groups use weight 100 or more and prefix priority 100 or more, so a staff member who also bought a rank shows
the staff prefix. **No group ever gets** `siftcore.sell.multiplier.*`, `siftcore.afk.reward.*`,
`siftcore.teams.size.*`, `siftcore.afk.bypass-kick`, `siftcore.teleport.bypass-warmup`, `siftcore.bypass.cooldown`,
`siftcore.pay.unlimited`, `siftcore.combat.bypass`, `siftcore.chat.bypass`, `siftcore.chat.links`,
`siftcore.perk.ec.others` or a shard-shop permission.

## Config that goes with it

All of this ships as SiftCore's defaults, so a fresh install needs no edits:

- `features/sell.yml`: `multipliers: {}` (no rank sell bonuses).
- `features/afk.yml`: `rewards.ranks: {}` (no rank AFK bonuses).
- `features/kits.yml`: the prospector, baron and tycoon supply kits (no gear, no keys).
- `features/integrations.yml`: `store.rank-groups: [prospector, baron, tycoon]`. The store can never grant any other
  group, so a mistyped or malicious command can't hand out staff.
- TAB (`plugins/TAB/`): sorting `GROUPS:owner,admin,mod,helper,tycoon,baron,prospector,default`, and in `groups.yml`
  each paid tier's nametag prefix ends with the closest legacy colour, because nametag name colours can only be the
  16 legacy colours. Tycoon's tab name is a gradient (`customtabname`).

## Tebex setup

Not done yet on the live server (no store plugin is installed). Install the Tebex plugin for Folia (`tebex-folia`),
link the store, and create these packages. Every command: "execute even if the player is offline", no inventory slots
required, quantity 1. `{uuid}` must arrive with dashes.

### Ranks, 30 days

Each package allows a one-off payment or a subscription at the same price. Options: "Disable lower-priced packages"
on, cumulative pricing off; the description says that upgrading means buying the higher tier and that support
cancels the old subscription.

| Package | Price | Initial and renewal command |
|---|---|---|
| Prospector 30 days | €4.99 | `sift store rank {uuid} prospector 30d tebex-{transaction}-{packageId}` |
| Baron 30 days | €9.99 | `sift store rank {uuid} baron 30d tebex-{transaction}-{packageId}` |
| Tycoon 30 days | €19.99 | `sift store rank {uuid} tycoon 30d tebex-{transaction}-{packageId}` and `sift store booster {uuid} sell 10 30m tebex-{transaction}-{packageId}-boost` |

No expiry command: the rank is a LuckPerms timed group that ends by itself, and a renewal adds 30 days to the
current end. An expiry command would also wipe time the player bought separately.

### Lifetime ranks

Options: cumulative pricing **on** (an upgrade costs only the difference: Prospector to Baron €30.00, Baron to Tycoon
€60.00), "Disable lower-priced packages" on.

| Package | Price | Command |
|---|---|---|
| Prospector Lifetime | €29.99 | `sift store rank {uuid} prospector permanent tebex-{transaction}-{packageId}` |
| Baron Lifetime | €59.99 | `sift store rank {uuid} baron permanent tebex-{transaction}-{packageId}` |
| Tycoon Lifetime | €119.99 | `sift store rank {uuid} tycoon permanent tebex-{transaction}-{packageId}` and the `-boost` booster command above |

### Server boosters

| Package | Price | Command |
|---|---|---|
| Sell Frenzy: +15% sell prices for everyone online, 30 minutes | €2.99 | `sift store booster {uuid} sell 15 30m tebex-{transaction}-{packageId}` |
| Sell Frenzy XL: +15% for 2 hours | €7.99 | `sift store booster {uuid} sell 15 2h tebex-{transaction}-{packageId}` |

Boosters queue one after another and never add up, are announced with the buyer's name, show a boss bar to everyone,
and only count down while the server runs. They raise /sell and spawner sales for everyone online; player-to-player
trades (orders, the auction house) are not boosted, and carry no fee either: the auction house and buy orders take no
tax (`tax: 0` in `features/auction.yml` and `features/orders.yml`), so sellers get the whole price. The shop's arbitrage guard accounts for the largest booster, so
buying from the shop and selling back never makes money. A booster package is never refused because of `sell.max-percent`
(the buyer paid and Tebex does not retry): above the limit it is delivered and pays the limit, and `/sift selftest`
fails `the store's booster packages arrive and pay in full` when the config would cap one of these packages. Full
reference: [boosters](features/boosters.md).

### Refunds and chargebacks (every package)

| Tebex command | Command |
|---|---|
| Refund | `sift store revoke tebex-{transaction}-{packageId} refund` (and the same for `-boost`) |
| Chargeback | `sift store revoke tebex-{transaction}-{packageId} chargeback` and `tempban {username} 30d Store chargeback on order {transaction}. Appeal on discord.gg/siftvanilla` |

### Other packages

- Gift cards (Tebex native, no command): €5, €10, €25.
- Gifting on for every rank package (the recipient's username).
- Community goal (Tebex native): when the monthly goal is reached, run
  `sift store booster console sell 10 48h goal-<yyyy-mm>` for a weekend booster for everyone.

## How delivery works

`/sift store` runs from the console only (`siftcore.admin.store`). Full reference: [integrations](features/integrations.md).

- **Exactly once.** Each reference (`tebex-...`, 1 to 48 of `A-Z a-z 0-9 . - + _`) is delivered at most once,
  whatever the store retries, across restarts and crashes. The delivery row is written in the same database
  transaction as what it grants.
- **Offline buyers** get everything; a buyer who never joined is addressed by UUID.
- **Ranks** are LuckPerms groups: timed ranks add up (30 days twice is 60 days), a permanent holder stays permanent.
  A rank delivered while LuckPerms was unavailable stays pending and is finished at the next startup.
- **Revoke** takes back what a reference gave (the rank, a running or queued booster) and keeps the reference
  recorded as revoked, so it can't be delivered again.
- Deliveries and revokes run from the console only (Tebex runs them there); no player can run them, whatever their
  permissions. `/sift store check <ref>` and `/sift store history <player>` look purchases up (staff in game too);
  every action is in `/sift audit store`.
- Players see their own history with `/purchases`.

## Refund and chargeback policy

Player-facing text (store terms): "Purchases are final once delivered, except where the law requires otherwise.
Contact support on Discord before opening a dispute." Make sure the Tebex checkout shows the EU digital-content
withdrawal waiver.

Staff procedure: a chargeback revokes the purchase and bans the account for 30 days automatically. Within 48 hours
staff review it and either make the ban permanent and add a Tebex customer ban, or lift it (for example a gift
recipient who did nothing wrong). If the dispute is won: unban and re-grant with
`sift store rank <uuid> <group> <remaining> <ref>-restore`. Watch the dispute rate: too many disputes suspend the
store or lock its wallet for 30 days.

## Rules and compliance

- **Mojang Usage Guidelines:** paid entitlements may not "ruin other players' experience or give a competitive
  advantage". Capacity, convenience behind a combat check, cosmetics and server-wide rewards are allowed; personal
  multipliers, bigger PvP teams, combat items and bypasses are not.
- **Tebex AUP 1.6** bans purchases "with an outcome based on chance ... including lootboxes": no keys, shards or
  crates are sold, alone or inside ranks. Belgium treats paid loot boxes as gambling, and Brazil's ECA Digital
  (since 17 March 2026) bars loot boxes minors can access.
- **Odds are published anyway:** `/crates preview` shows each reward's chance; publish the same table on the website.
- **Before anyone pays:** the full price and perk table is on the website and the store, and linked from the tab list.
- **Purchase history:** Tebex receipts and `/purchases`.
- **Creators** paid to promote the server must disclose it.
- **No capes or cape-like cosmetics.** /hat is fine.

## Launch checklist

1. Tebex sandbox: `{uuid}` arrives with dashes; `{transaction}` differs on each renewal (if not, deliver renewals from
   the signed `recurring-payment.renewed` webhook keyed on the webhook id); a duplicate delivery is refused; an
   offline buyer gets the rank; a refund and a chargeback revoke it.
2. In game: a test account bought each tier and sees the coloured rank in chat, tab and nametag, its kit, perks,
   /chatcolor, /nick, /tags and spawn flight; a booster pays more on /sell and shows the boss bar.
3. `/sift selftest` passes; `/sift integrations` shows LuckPerms and PlaceholderAPI active.

## Running the store

Watch conversion, monthly revenue, churn, the tier mix and the dispute rate. Change prices only from data: the
Tycoon 10% rule above, and Prospector if fewer than 2% of active players buy anything after the first month.

## Later

- **Shards for money** only after the shard shop stops selling keys, totems and gapples (until then paid shards would
  be a paid lootbox and paid PvP gear).
- **Cosmetic tag packs** (€1.99 to €3.99) delivered with `lp user {uuid} permission set siftcore.tag.<id> true`.
- **Voting rewards:** the tab footer mentions `/vote`, which does not exist yet.
