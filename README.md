# SiftCore

The gameplay core of **SiftVanilla**, a DonutSMP-style economy and PvP survival server. One plugin owns the
whole gameplay layer: money and shards, selling and the shop, buy orders, the auction house, stacked spawners,
teams, homes/TPA/RTP/spawn, combat tagging and bounties, stats and leaderboards, crates and keys, chat and private
messages, the AFK zone, kits and rank perks, the scoreboard and tab list, and the staff tools.

It runs on **Canvas 26.2** (a Folia fork with region threading) and is written to be correct on Paper and Folia too.
The look is deliberately vanilla and quiet: white and gray text, money in green, no bold, no gradients, dialogs for
forms and lists, chest menus only where a grid is needed.

## Highlights

- **Crash-safe economy.** Whole-number balances in an append-only ledger. Every trade is one atomic transaction:
  money postings, domain changes and claim-box items commit together, and items are handed out only after the
  commit. Invariants (total supply = sources − sinks, escrow = open orders and bounties) are checked by
  `/sift selftest`. A 16-thread concurrency test proves parallel transfers conserve supply.
- **Region-threaded from the ground up.** All work runs on the thread that owns it (player, region, global or
  async). No world scans on timers, nothing blocking a tick thread.
- **Dialogs everywhere.** Forms, lists, confirmations and settings use the Paper Dialog API. One template system
  and one router validate every click server-side: one-shot tokens bound to the player, re-validated inputs, no
  double submits, errors re-open the dialog with what was typed. The main menu also lives in the pause screen and
  the quick actions key.
- **Text you can trust.** Every string lives in `lang/*.yml`; the loader rejects anything outside the design
  system, and player text is always inserted literally, so it can't inject formatting or click events. Inline
  icons are vanilla atlas sprites, verified against the 26.2 client assets at startup.
- **Bedrock ready.** With Floodgate installed, Bedrock players get the same screens as native forms.

## Requirements

- Canvas, Folia or Paper for Minecraft 26.2, Java 25.
- Optional: LuckPerms (rank labels and rank perks), PlaceholderAPI, Vault/VaultUnlocked (compatibility for other
  plugins), Floodgate (Bedrock forms), ViaVersion and ViaBackwards (newer and older clients; older
  clients see dialogs as chest menus).

## Build

```sh
export JAVA_HOME=/path/to/jdk-25
mvn -B package          # runs the unit tests; the plugin jar is target/SiftCore-1.0.0.jar
```

The jar contains only SiftCore and a relocated HikariCP (the server already ships the SQLite and MySQL
drivers), so it is well under 1 MB.

## Install

1. Put `SiftCore-1.0.0.jar` in `plugins/` and start the server once.
2. Edit `plugins/SiftCore/config.yml` (storage, currency format, palette, sounds), `features/*.yml` (one file per
   feature, every key commented) and `lang/*.yml` (all text).
3. `/sift reload` applies changes. If any file has a mistake, nothing is applied and every problem is listed with
   its file, path and the reason.
4. `/sift selftest` checks the ledger invariants, every feature's state and that every icon and message resolves.

SQLite is the default (`plugins/SiftCore/data/siftcore.db`, WAL mode). For MariaDB/MySQL set `storage.type: mysql`
and the connection details. Schema migrations run automatically.

## Documentation

| Document | What it covers |
|----------|----------------|
| [docs/architecture.md](docs/architecture.md) | Module map, threading model, money engine, database schema, UI templates, API and events |
| [docs/development.md](docs/development.md) | How a feature is built (the contract every feature follows) |
| [docs/balance.md](docs/balance.md) | Economy design: sources, sinks, prices and which values to tune |
| [docs/permissions.md](docs/permissions.md) | Every permission node and its default |
| [docs/placeholders.md](docs/placeholders.md) | Every placeholder (`%siftcore_<name>%` in PlaceholderAPI) |
| [docs/api.md](docs/api.md) | Public API and events for other plugins |
| [docs/features/](docs/features/) | One page per feature: commands, config, decisions |
| [docs/server-setup.md](docs/server-setup.md) | How the SiftVanilla server itself is configured |
| [docs/server-undo-log.md](docs/server-undo-log.md) | Every change made to the live server and how to revert it |
| [docs/testing.md](docs/testing.md) | Unit, boot and end-to-end tests, and the in-game verification checklist |
| [docs/research/](docs/research/) | Verified facts about Canvas 26.2 threading, sprites, dependencies and the API |

## Tests

- `mvn test`: unit tests for money parsing and formatting, the ledger (atomicity, rejection, revert on storage
  failure, parallel transfers conserving supply), text safety, dialog validation, and each feature's pure logic.
- `tools/e2e`: a test-only plugin that drives real protocol bots through the server (menus, payments, trades,
  teleports, combat) and asserts on what they receive. See [tools/e2e/README.md](tools/e2e/README.md).
