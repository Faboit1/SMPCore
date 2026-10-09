# SiftCore

The gameplay core of **SiftVanilla**, a DonutSMP-style economy and PvP survival server. One plugin owns the whole
gameplay layer: money and shards, selling and the shop, buy orders, the auction house, stacked spawners, crates and
keys, kits and rank perks, teams and friends, homes, teleport requests, random teleport and spawn, combat tagging and
bounties, stats and leaderboards, chat and private messages, the AFK zone, cosmetics, sell boosters, the scoreboard
and tab list, the staff tools, per-player settings and store delivery.

It runs on **Canvas 26.2** (a Folia fork with region threading, Java 25) and is written to be correct on Paper and
Folia too. The look is deliberately vanilla and quiet: white and gray text, money in green, dialogs for forms and
lists, chest menus only where a grid is needed.

## Features

Every feature is a package under `src/main/java/net/siftvanilla/siftcore/feature/` with its own config
(`features/<id>.yml`), text (`lang/<id>.yml`) and page in [docs/features/](docs/features/). All 30 are built in and
enabled together; `FeatureCatalog` wires them to each other.

| Feature | What it does |
|---|---|
| [admin](docs/features/admin.md) | `/sift`: version, reload, self-test, metrics, debug, and the config problem alert for admins |
| [afk](docs/features/afk.md) | Notices AFK players, kicks those who stay away, and runs the AFK zone that pays shards |
| [auction](docs/features/auction.md) | The auction house (`/ah`) and the claim box (`/claims`); also how AxAuctions would plug in |
| [boosters](docs/features/boosters.md) | Server-wide sell boosters from the store or staff, queued, with a boss bar |
| [bounties](docs/features/bounties.md) | Money on a player's head, held in escrow and paid to whoever kills them |
| [chat](docs/features/chat.md) | Public chat with hover cards and mentions, `/msg` and `/r`, ignore lists, word filter, link check, anti-spam, `/chat` staff tools |
| [combat](docs/features/combat.md) | The combat tag, combat logging, kill credit with anti-farm rules, death messages, kill streaks |
| [cosmetics](docs/features/cosmetics.md) | Rank cosmetics: chat colours, nicknames, chat tags, join and leave lines, kill effects |
| [crates](docs/features/crates.md) | Crates opened with virtual keys, the keyall, crate blocks and the preview |
| [displays](docs/features/displays.md) | Floating leaderboards and info boards at spawn, made of text displays |
| [economy](docs/features/economy.md) | `/balance`, `/pay`, `/baltop`, the `/eco` staff tools and the Vault economy |
| [extras](docs/features/extras.md) | `/rules`, `/help`, `/ping`, `/seen`, `/links`, and join and leave messages |
| [friends](docs/features/friends.md) | Friend lists, requests, presence alerts and profiles (`/friend`, `/profile`) |
| [homes](docs/features/homes.md) | `/sethome`, `/home`, `/delhome` and the homes dialog, with rank limits |
| [hub](docs/features/hub.md) | The main menu (`/menu`), the SiftVanilla button in the pause screen and the server links |
| [integrations](docs/features/integrations.md) | PlaceholderAPI, LuckPerms ranks, Floodgate forms, the public API, backups, exports, the audit log, store delivery, `/purchases`, joining a full server |
| [kits](docs/features/kits.md) | Starter, daily and rank kits, and the rank perk commands (`/ec`, `/craft`, `/anvil`, `/trash`, `/hat` ...) |
| [orders](docs/features/orders.md) | Buy orders: money held up front, other players deliver, the buyer collects |
| [rtp](docs/features/rtp.md) | Random teleport to a safe spot in pre-generated terrain |
| [scoreboard](docs/features/scoreboard.md) | The sidebar, the tab list and nametags (each yields to TAB when it is installed) |
| [sell](docs/features/sell.md) | Selling to the server, the generated worth table, sell mastery, `/worth` and the price list |
| [settings](docs/features/settings.md) | The settings dialog, `/settings`, `/sift settings`, the settings placeholders and API: 133 per-player settings |
| [shards](docs/features/shards.md) | The second currency and the shard shop |
| [shop](docs/features/shop.md) | The server shop with price safety against selling back |
| [spawn](docs/features/spawn.md) | `/spawn`, `/setspawn`, the protected spawn area, `/fly` at spawn, arrivals and world borders |
| [spawners](docs/features/spawners.md) | Stackable spawners that fill a storage with loot and XP instead of spawning mobs |
| [staff](docs/features/staff.md) | Vanish, freeze, mutes, bans, kicks, warnings, history, staff chat, reports, inventory inspection, lookups |
| [stats](docs/features/stats.md) | Lifetime counters (kills, deaths, streaks, playtime, mobs, blocks, money earned) and leaderboards |
| [teams](docs/features/teams.md) | Teams with roles, a shared home, team chat, friendly fire and team leaderboards |
| [tpa](docs/features/tpa.md) | `/tpa`, `/tpahere` and their answers, with who-can settings and auto-accept for friends |

Reference pages: [every permission](docs/permissions.md) (188 nodes), [every placeholder](docs/placeholders.md)
(127, `%siftcore_<name>%`) and [every player setting](docs/features/settings.md#settings-catalog) (133).

## Highlights

- **Crash-safe economy.** Whole-number balances in an append-only ledger. Every trade is one atomic transaction:
  money postings, domain changes and claim-box items commit together, and items are handed out only after the
  commit. Invariants (total supply = sources - sinks, escrow = open orders and bounties) are checked by
  `/sift selftest`. A 16-thread concurrency test proves parallel transfers conserve supply.
- **Region-threaded from the ground up.** All work runs on the thread that owns it (player, region, global or
  async). No world scans on timers, nothing blocking a tick thread.
- **Dialogs everywhere.** Forms, lists, confirmations and settings use the Paper Dialog API. One template system
  and one router validate every click server-side: one-shot tokens bound to the player, re-validated inputs, no
  double submits, errors re-open the dialog with what was typed. The main menu also lives in the pause screen and
  the quick actions key.
- **Text you can trust.** Every string lives in `lang/*.yml`, and every line is checked against the design system when
  it loads (palette tags, named and hex colours, bold, shadow and icons; gradients, rainbow and obfuscated text are
  rejected). Player text is always inserted literally, so it can't inject formatting or click events. Inline icons are
  vanilla atlas sprites, verified against the 26.2 client assets at startup.
- **Settings for every player.** 133 settings in 14 groups (chat, sounds, alerts, privacy, money format and more),
  the same for everyone whatever their rank: settings are never sold (Show my rank only appears for players who have a
  rank to hide). Staff can look up and change anyone's settings.
- **Bedrock ready.** With Floodgate installed, Bedrock players get the same screens as native forms.

## Requirements

- Canvas, Folia or Paper for Minecraft 26.2, Java 25.
- Optional: LuckPerms (rank labels, rank perks, store ranks), PlaceholderAPI, VaultUnlocked (Vault economy for other
  plugins), TAB (SiftCore's sidebar, tab list and nametags step aside for it), Floodgate (Bedrock forms), ViaVersion
  and ViaBackwards (newer and older clients; clients before 1.21.6 see dialogs as chest menus).

## Build

```sh
export JAVA_HOME=/path/to/jdk-25
mvn -B package          # compiles, runs the unit tests and writes target/SiftCore-1.0.0.jar
```

The jar contains SiftCore and a relocated HikariCP (the server already ships the SQLite and MySQL drivers).

## Test

- **Unit tests:** `mvn test` (about 255 test classes): money parsing and formatting, the ledger (atomicity,
  rejection, revert on storage failure, parallel transfers conserving supply), text safety, dialog validation, the
  settings registry, every store on a real SQLite database, and each feature's pure logic and bundled text.
- **End to end:** `tools/e2e` is a test-only plugin that drives real protocol bots through a test server (menus,
  dialogs, payments, trades, teleports, combat, settings) and asserts on what they receive. Build it with
  `tools/e2e/build.sh <test-server-dir> target/SiftCore-1.0.0.jar <jdk-25-home>`, put both jars in the test
  server's `plugins/` and run `e2e run all` in the console (about 335 scenarios). Add VaultUnlocked and LuckPerms
  like the live server: `vault-economy` fails without VaultUnlocked and `cosmetics-join-lines` without LuckPerms
  (the other rank scenarios check the fallback or skip the part that needs LuckPerms). Leave TAB out: SiftCore yields
  its sidebar, tab list and nametags to TAB, so the `scoreboard-*` scenarios fail while it runs (`scoreboard-yield`
  checks that step-aside with the harness plugin standing in for TAB).
  Details: [tools/e2e/README.md](tools/e2e/README.md).
- **On a server:** `/sift selftest` checks the ledger invariants, every feature's state and that every icon and
  message resolves.

## Deploy

1. Put `SiftCore-1.0.0.jar` in `plugins/` (with the optional plugins above) and start the server.
2. On first start SiftCore writes `plugins/SiftCore/config.yml`, `commands.yml`, `icons.yml`, `features/*.yml` and
   `lang/*.yml`. The log ends with `SiftCore 1.0.0 enabled in ... ms: 30 features, ... commands, ...`.
3. Run `/sift selftest` (every check should pass) and `/sift integrations` (which hooks are active).
4. Updating: replace the jar and restart (never `/reload`; Canvas can't reload plugins). New keys of a newer jar are
   added to the server's files with their comments, and values nobody edited follow the new defaults; database
   migrations run by themselves.

SQLite is the default (`plugins/SiftCore/data/siftcore.db`, WAL mode). For MariaDB/MySQL set `storage.type: mysql`
and the connection details in `config.yml`. Daily backups go to `plugins/SiftCore/backups/` (`/sift backup` makes
one now). How the live server is set up, with every change and how to revert it: [docs/server-setup.md](docs/server-setup.md)
and [docs/server-undo-log.md](docs/server-undo-log.md).

## Configure

| File | What it holds |
|---|---|
| `config.yml` | Storage, the money format (`currency`), the colour palette, sounds, crash safety, debug |
| `commands.yml` | Per command: turn it off, change its aliases, give it a cooldown, or leave it to another plugin (`yield-to`) |
| `features/<id>.yml` | One file per feature, every key commented |
| `features/settings.yml` | The settings dialog, and the server's defaults, locks and hidden settings for player settings |
| `lang/<id>.yml` | Every text players see |
| `icons.yml` | The inline icons (vanilla sprites) |

`/sift reload` applies changes. If `config.yml`, `commands.yml` or a `features/*.yml` file has a mistake nothing is
applied, and every problem is listed with its file, path and reason; a lang entry with a mistake keeps the shipped
text. Aliases, `enabled` and `yield-to` in `commands.yml`, the pause-screen menu and the storage settings apply after a
restart. Ranks and perks are LuckPerms groups and permission nodes: see [docs/monetization.md](docs/monetization.md).

## Documentation

| Document | What it covers |
|---|---|
| [docs/features/](docs/features/) | One page per feature (table above): commands, permissions, settings, placeholders, config, events, storage, how it connects to the others |
| [docs/architecture.md](docs/architecture.md) | Module map, threading model, money engine, database schema, UI, text rules, API and Vault |
| [docs/development.md](docs/development.md) | How a feature is built (the contract every feature follows) |
| [docs/api.md](docs/api.md) | Public API and events for other plugins |
| [docs/permissions.md](docs/permissions.md) | Every permission node and its default (generated with `/sift docs`) |
| [docs/placeholders.md](docs/placeholders.md) | Every placeholder (generated with `/sift docs`) |
| [docs/monetization.md](docs/monetization.md) | Ranks, perks, prices, the LuckPerms and Tebex setup, store delivery and refunds |
| [docs/server-setup.md](docs/server-setup.md) | How the live SiftVanilla server is configured: plugins, worlds, ranks, staff, TAB, backups |
| [docs/server-undo-log.md](docs/server-undo-log.md) | Every change made to the live server and how to revert it |
| [tools/e2e/README.md](tools/e2e/README.md) | The end-to-end test harness |
| [docs/research/](docs/research/) | Verified facts about Canvas 26.2 threading, sprites, dependencies and the API |
