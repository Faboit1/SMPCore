# Main menu and pause screen (`hub`)

One dialog that links every system: `/menu`, the SiftVanilla button in the pause screen and the quick actions key.
Each feature contributes its own button; the hub feature draws the menu, routes the pause-screen buttons and publishes
the server links. Package `feature/hub` (the menu and the links), `ui/hub` (`HubRegistry`, `HubEntry`, the registry
every feature registers with) and `SiftCoreBootstrap` (the pause-screen dialog). Config `features/hub.yml`, text
`lang/hub.yml`. No tables, no settings, no placeholders.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/menu` (`/hub`, `/m`) | `siftcore.command.menu` (everyone) | Opens the main menu (players only) |

`/m` is the menu, so private messages use `/msg`, `/w`, `/tell` and the other aliases (see [chat](chat.md)).

## The main menu

Title "SiftVanilla"; the body greets the player and shows their money and shards (`hub.body` in `lang/hub.yml`), then
one button per entry the player may use, `columns` per row (2), in entry order. Each button's tooltip is the entry's
description. A button opens that feature's own screen, which offers Back to the menu where it makes sense; only Spawn
shows nothing of its own (it starts the teleport, so its button closes the menu at once).

Entries come from the features that are enabled; an entry with a permission shows only to players who have it
(`HubRegistry#visibleTo`). The entries SiftCore registers:

| Order | Id | Feature | Opens | Permission |
|---|---|---|---|---|
| 0 | `menu` | hub | the main menu itself (pause screen only; not a button in the menu) | |
| 10 | `money` | [economy](economy.md) | the Money page (balance, shards, leaderboard place, daily pay limit; Pay a player, Richest players) | |
| 20 | `shop` | [shop](shop.md) | the shop categories | `siftcore.command.shop` |
| 25 | `sell` | [sell](sell.md) | the sell menu | `siftcore.command.sell` |
| 26 | `prices` | [sell](sell.md) | the price list | `siftcore.command.worth` |
| 27 | `booster` | [boosters](boosters.md) | the running and queued sell boosters | `siftcore.command.booster` |
| 30 | `auction` | [auction](auction.md) | the auction house (runs `/ah` while AxAuctions is enabled) | `siftcore.command.ah` |
| 32 | `claims` | [auction](auction.md) | the claim box | `siftcore.command.claims` |
| 35 | `orders` | [orders](orders.md) | the buy orders browser | `siftcore.command.orders` |
| 40 | `spawners` | [spawners](spawners.md) | your spawners | `siftcore.command.spawners` |
| 45 | `crates` | [crates](crates.md) | the crates dialog | `siftcore.command.crates` |
| 50 | `teams` | [teams](teams.md) | the team dialog | `siftcore.command.team` |
| 52 | `friends` | [friends](friends.md) | the friends list | `siftcore.command.friend` |
| 55 | `homes` | [homes](homes.md) | the homes dialog | `siftcore.command.homes` |
| 60 | `rtp` | [rtp](rtp.md) | the random teleport picker | `siftcore.command.rtp` |
| 62 | `tpa` | [tpa](tpa.md) | the teleport request form | `siftcore.command.tpa` |
| 65 | `spawn` | [spawn](spawn.md) | the teleport to spawn | `siftcore.command.spawn` |
| 70 | `stats` | [stats](stats.md) | your stats | `siftcore.command.stats` |
| 75 | `bounties` | [bounties](bounties.md) | the bounties dialog | `siftcore.command.bounties` |
| 80 | `kits` | [kits](kits.md) | the kits dialog | `siftcore.command.kits` |
| 82 | `cosmetics` | [cosmetics](cosmetics.md) | the cosmetics menu | `siftcore.command.cosmetics` |
| 85 | `shards` | [shards](shards.md) | the shards page | `siftcore.command.shards` |
| 90 | `report` | [staff](staff.md) | the report form | `siftcore.command.report` |
| 90 | `settings` | [settings](settings.md) | the settings groups | `siftcore.command.settings` |
| 94 | `rules` | [extras](extras.md) | the rules (its button returns to the menu) | |
| 95 | `links` | hub | the client's Server Links screen | |

A feature that is turned off registers no entry, so its button is simply not there.

## The pause screen and the quick actions key

`SiftCoreBootstrap` runs before the server loads its registries. It registers one dialog, `siftcore:hub`, and adds
it to the `pause_screen_additions` and `quick_actions` dialog tags, so the pause screen shows a SiftVanilla button
and the quick actions key opens it. That dialog lists the ids of `pause-menu.entries` (2 columns), each button with
its label and tooltip from `lang/hub.yml` (`hub.entries.<id>.label` / `.description`). A button only sends
`siftcore:hub/<id>`; the dialog router (`Dialogs`, static routes) opens the entry's real screen at runtime, checking
the permission there. An id whose feature is off, or whose permission the player lacks, opens the main menu instead.
The pause dialog stays on screen until the chosen screen replaces it.

The pause dialog is built from the files at startup, before the plugin enables (registries freeze after that), so
`pause-menu` changes and pause-menu text in `lang/hub.yml` need a restart. Ids must be lowercase letters, digits,
`-` or `_` (others are skipped with a warning). The shipped entries: menu, money, shop, sell, auction, orders,
spawners, crates, kits, teams, friends, homes, rtp, spawn, stats, settings, report. `lang/hub.yml` also has labels for
claims, tpa, bounties, shards and prices, which can be added to the list.

A server whose `pause-menu.entries` nobody edited follows new shipped defaults by itself: SiftCore remembers what each
file shipped (`data/shipped/`) and replaces an entry the server still has exactly as an earlier version shipped it
(an edited list is never touched); new keys are always added (`data/shipped-keys/`). That update happens while the
plugin enables, after the bootstrapper already built the pause screen, so a new entry (or label) shows from the restart
after the one that updated the file (undo log row 30).

## Server links

`server-links` in `features/hub.yml` (shipped: Rules, Discord, Store) are added to the server's links (Bukkit
`ServerLinks`) on the global thread at startup and on every `/sift reload`, and sent to online players. Clients show
them in the pause screen's Server Links screen; `/links` (extras) and the `links` entry open that screen. Each link has
a `label` and an `http` or `https` `url`; an invalid address is a config problem.

## Config (`features/hub.yml`)

| Key | Default | Meaning |
|---|---|---|
| `columns` | 2 | Buttons per row in the main menu (1-4) |
| `pause-menu.enabled` | true | The SiftVanilla button in the pause screen and the quick actions key (restart) |
| `pause-menu.entries` | the 17 ids above | Buttons of the pause-screen menu, by hub entry id (restart) |
| `server-links.<id>.label` / `.url` | Rules, Discord, Store | Links shown in the pause screen's Server Links (reload) |

## For feature authors

Register an entry in `enable()`:

```java
this.services.hub().register(new HubEntry("shop", 20, ShopMessages.HUB_LABEL, ShopMessages.HUB_DESCRIPTION,
    PERMISSION, this.menus::openShop));
```

The id is also the pause route `siftcore:hub/<id>` and must be unique (registering it twice throws). `open` runs on
the player's thread. Give the screen a Back to the main menu (`services.hub().get("menu").open()`) when it is opened
from the menu, and add the label to `lang/hub.yml` under `hub.entries.<id>` if the id may be put in the pause menu.

## Self-test

`pause menu entries exist` (every id in `pause-menu.entries` has an entry: fails when a listed feature is off) and
`pause menu dialog is registered and tagged` (`siftcore:hub` is in the dialog registry and in both tags).

## Tests

Unit: `PauseMenuResourcesTest` (every shipped pause-menu id has a label and a description in `lang/hub.yml`). End to
end: `menu` in `Scenarios` (the menu opens with the balance, Money and Back, and the pause route
`siftcore:hub/money` opens the Money page); other features' scenarios click their entries
(`siftcore:hub/auction`, `siftcore:hub/money` in `MoneyFormatScenarios`). The pause screen itself needs a real client.
