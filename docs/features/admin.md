# Administration: `/sift` (`admin`)

The `/sift` command: version, reload, self-test, metrics and debug, plus the subcommands other features add to it
(settings, stats, friends, boosters, integrations, backups, exports, the audit log, the permission and placeholder
registries and store delivery). It also tells admins on join when the last startup or reload found config problems.
Package `feature/admin` (`AdminFeature`, `ConfigProblemLog`), with `CoreControl` (what the admin feature may do with
the plugin: reload, self-test, metrics, debug, the startup's problems) implemented by `SiftCore`. Text
`lang/admin.yml`. No config file, no tables, no placeholders.

## Commands and permissions

Every `/sift` command works from the console. `/sift` (alias `/siftcore`) needs `siftcore.admin`; each subcommand
also needs its own node. Every node here defaults to operators.

| Command | Permission | What it does |
|---|---|---|
| `/sift`, `/sift version` | `siftcore.admin` | SiftCore's version, the server software and whether it runs on region threads |
| `/sift reload` | `siftcore.admin.reload` | Reloads `config.yml`, `commands.yml` cooldowns, every `features/*.yml`, `icons.yml` and every `lang/*.yml` (see below). Audited as `admin.reload` |
| `/sift selftest` | `siftcore.admin.selftest` | Runs every self-test one after another (quick in-memory checks on the command's thread, database checks asynchronously) and lists each check as pass or fail with its time, then the totals |
| `/sift metrics` | `siftcore.admin.metrics` | Uptime, pending, committed and failed database writes, average group commit time, ledger transactions, storage failures, accounts, open dialog sessions, dialog clicks handled and rejected, features, command labels, pending claim box deliveries |
| `/sift debug [true\|false]` | `siftcore.admin.debug` | Toggles (or sets) the debug flag |

Subcommands added by other features (`AdminFeature#addPart`; each documented with its feature):

| Subcommand | Feature | Permission |
|---|---|---|
| `/sift settings ...` | [settings](settings.md) | `siftcore.admin.settings` |
| `/sift stats add\|set\|reset\|refresh ...` | [stats](stats.md) | `siftcore.admin.stats` |
| `/sift friends ...` | [friends](friends.md) | `siftcore.admin.friends` |
| `/sift booster start\|stop\|list` | [boosters](boosters.md) | `siftcore.admin.booster` |
| `/sift integrations`, `backup`, `export`, `audit`, `permissions`, `placeholders`, `docs` | [integrations](integrations.md) | `siftcore.admin.integrations`, `.backup`, `.export`, `.audit`, `.registry` |
| `/sift store ...` | [integrations](integrations.md) | `siftcore.admin.store` (deliveries and revokes from the console only) |

The admin feature also declares two core nodes every feature uses: `siftcore.bypass.cooldown` (skip `commands.yml`
cooldowns and feature cooldowns) and `siftcore.teleport.bypass-warmup` (teleport without a warmup).

The economy's own staff tools are `/eco` ([economy](economy.md)), not part of `/sift`.

## Reload

`/sift reload` parses `config.yml`, `commands.yml` (cooldowns) and every `features/*.yml` first and applies them only
when every file is valid: a typo can never half-apply a reload. Then it loads `icons.yml` and the lang files; a lang
entry with a problem (a tag the design system rejects, an undeclared placeholder, an unknown icon) keeps the text the
jar ships and is listed. Each problem is listed with its file, path and reason. Missing keys a newer jar ships are
added to the server's files first (with their comments), and shipped values nobody edited follow the new defaults
(`data/shipped/`, `data/shipped-keys/`).

Some things are read once and need a restart: command aliases, `enabled` and `yield-to` in `commands.yml`, the
pause-screen menu (`pause-menu` in `features/hub.yml` and its text in `lang/hub.yml`) and `storage` in `config.yml`.

Never use the server's `/reload` or plugin managers: Canvas can't reload plugins, and `/sift reload` covers every
SiftCore file.

## Config problem alerts

Admins with `siftcore.admin.reload` get a chat line two seconds after joining when the last startup or `/sift reload`
found config problems: "Config problems at the last start or reload: 3. Hover to see them, fix them, then /sift
reload." Hovering lists the first eight (and how many more are in the console log); clicking fills in `/sift reload`.
Each admin can turn it off: "Config problem alerts" (`admin-config-alerts`, toggle, on, Staff group, 13th, needs
`siftcore.admin.reload`).

The problems come from three places, each problem counted once: a reload hands over its list, the startup's list is
kept by SiftCore (`CoreControl#startupProblems`), and the admin feature listens to SiftCore's own log for
`Config problem: ...` lines and the `features/settings.yml: ...` override warnings the settings feature writes a
second after startup and on each reload. A reload that finds no problem clears the list.

## Self-test

`/sift selftest` runs the checks every feature registers (`Feature#selfTest`) plus core's:

- `ledger invariants`: memory supply = stored balances = sum of ledger deltas = sources minus sinks, transfers net to
  zero, no negative balance, every account equals its ledger history, the orders and bounty escrows match;
- `every message has text`, `icons resolve`, `database writer is healthy` (no failed write since start),
  `economy accepts transactions` (not read-only after storage failures);
- the settings checks: every setting, option and group has text, group icons resolve, shared settings are
  registered, groups hold 4 to 15 settings, and every shared setting is read by a feature.

Each feature page lists its own checks. Checks are cheap and read-only; a failing check names what is wrong.

## Tests

Unit: `ConfigProblemLogTest` (the startup and settings log formats against their sources, counting each problem once,
a reload replacing the startup's list). End to end: many scenarios run `/sift reload` from the console and check its
answer (a refused reload says "Nothing was reloaded"), and `admin-config-alerts` in `StaffScenarios` (the join alert,
its hover and the setting).
