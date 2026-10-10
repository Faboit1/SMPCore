# Homes (`homes`)

Players save places with `/sethome` and go back with `/home`. Package `feature/homes`, config `features/homes.yml`,
text `lang/homes.yml`, table `homes` (migration V006).

Homes use the shared teleport (`core.teleport.Teleports`): a warmup with a countdown that moving or taking damage
cancels, refused while combat-tagged, `teleportAsync` only. The countdown and the arrival line ("Welcome to base.",
sent through `Teleports#arrival`) show where the player's "Teleport countdown" setting says. The feature consumes two
contracts:

| Contract | Used for |
|---|---|
| `SpawnArea` (spawn feature, wired) | No homes inside the protected spawn area |
| `CombatStatus` (core combat tags) | No homes are set in combat ("You can't set a home in combat. <time> left."), by `/sethome` or the dialog's Set a home here form alike |

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/sethome [name]` (alias `/createhome`) | everyone | Sets a home where the player stands. No name means `home`. An existing name moves that home, after "Move home" asks first (unless the player turned that off) |
| `/home [name]` (alias `/h`) | everyone | Teleports to a home after the warmup. No name: as the player's "/home with no name" says (by default the only home, or the homes dialog when there are several) |
| `/delhome [name]` (aliases `/deletehome`, `/removehome`) | everyone | Deletes a home after a confirmation dialog. No name: the homes dialog |
| `/homes` | everyone | The homes dialog |
| `/homes <player>` | `siftcore.admin.homes`, console | Another player's homes, online or offline: a dialog with teleport and delete buttons for staff, chat lines for the console. Written to the audit log as `homes.view`; a staff teleport to one of the homes as `homes.teleport` (details: home name, world, block position) |
| `/homes <player> delete <home>` | `siftcore.admin.homes`, console | Deletes another player's home (written to the audit log as `homes.delete`) |

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.sethome`, `.home`, `.delhome`, `.homes` | everyone | Use the command |
| `siftcore.admin.homes` | op | See, use and delete other players' homes |
| `siftcore.homes.<n>` | none | How many homes the player may have; the highest number wins (`Limits.highest`) |
| `siftcore.homes.unlimited` | nobody | No limit |
| `siftcore.teleport.bypass-warmup` | op | No warmup (core node) |
| `siftcore.bypass.cooldown` | op | No cooldown between home teleports (core node) |

Rank nodes on SiftVanilla (the default of 2 comes from the config): `prospector` `siftcore.homes.6`, `baron`
`siftcore.homes.15`, `tycoon` `siftcore.homes.40` ([monetization](../monetization.md)).

## The homes dialog

`/homes`, `/home` with several homes and the main menu entry `homes` (order 55, also a pause-menu route) open one
list in the dialog style (`HomesViews`): one status line, `2 of 3 homes` (the numbers in the accent colour), then a
button per home named after it, with a red Delete next to it, and a green Set a home here (a form with a name field
prefilled with the first free name: `home`, `home2`, ...; the name rule is on its Set home button's tooltip). Where a
home is (world and block position, or the world only in streamer mode) is in its button's tooltip. Worlds are named
the way players know them: the server's main world "Overworld", its nether "Nether" and its end "The End"
(`homes.worlds` in `lang/homes.yml`, `HomesViews#worldName`); another world shows its own name. The same names are
in the delete and "Move home" questions; staff chat lines (`/homes <player>` from the console) keep the folder names.
No pages: every
home is in the one dialog, which scrolls. Delete asks for confirmation and comes back to the list. A wrong name in the
form keeps the dialog open with the rule; a name that exists asks "Move home" (Move it here goes back to the list,
Cancel back to the form). A home's teleport button closes the list at once. Homes cost nothing to set or use.

## Per-player settings (Settings > Teleports & homes)

Registered in `SettingCategories.TELEPORT` by `HomesFeature#registerSettings` (3rd and 5th in the group; text
`homes.settings.*` in `lang/homes.yml`):

| Id | Kind | Default | Read in | Meaning |
|---|---|---|---|---|
| `homes-confirm-overwrite` | switch | on | `HomesService#trySet` (`/sethome` and the form) | "Confirm moving a home": before an existing home moves, a window shows where it is now and where it would go (worlds only in streamer mode). Move it here runs every check again at that moment |
| `homes-bare-command` | choice smart / default-home / list | smart | `HomesService#home` (`BareHome#decide`, unit tested) | "/home with no name": the only home or the list (smart), the home named `home` (like smart when there is none), or always the list |

Homes also read the shared `hide-coordinates` (Settings > Privacy, "Streamer mode: hide coordinates"): the player's
own homes list and delete window leave the position out (`homes.list.line-hidden`, `homes.delete.body-hidden`).
Staff views of other players' homes always show it.

## Config (`features/homes.yml`)

| Key | Default | Meaning |
|---|---|---|
| `default-limit` | `2` | Homes per player without a rank node |
| `warmup` | `3s` | Standing still before the teleport |
| `cooldown` | `5s` | Time between two home teleports |
| `disabled-worlds` | `[]` | Worlds where homes can't be set or used (each name is checked against the loaded worlds). Team homes follow this list too (`HomesFeature#disabledWorlds`) |

## Placeholders

| Name | Value |
|---|---|
| `homes_count` | Homes the player has (0 while they are offline) |
| `homes_limit` | Homes the player may have, or `unlimited` |

## Design decisions

- Names are 1 to 16 letters, digits, `_` or `-`, compared and stored in lowercase (`Base` and `base` are one home).
- The limit stops new names only: moving an existing home always works, and a player whose rank dropped keeps every
  home they have (they can use and move them, not add more until they are under the limit).
- Homes of online players live in memory and are written through to the table on the ordered database writer. They
  are read during the asynchronous pre-login (so they are there when the player appears; a slow database is retried
  after the join), dropped on quit, and a five-minute sweep forgets logins that never completed. Loads go through
  the writer as well, so a relog right after `/sethome` always sees the new home.
- When the warmup ends the home is read again: a home deleted or moved during the warmup is not used blindly.
- Then the two blocks the player would occupy are checked on the thread that owns them (the chunk is loaded without
  generating): lava, fire or a lit campfire there, or a suffocating block at head height (someone poured lava over
  the home or walled it in), stops the teleport and asks "Home base doesn't look safe: there is lava. Teleport there
  anyway?". Teleport anyway starts a new teleport, with its warmup, that skips the check. A block at the feet is
  normal (homes set on slabs, stairs or carpets) and is not reported. The decision is `HomeSafety` (unit tested).
  Staff teleports to other players' homes skip the check.
- Staff views read the table directly, so offline players' homes are complete; deleting one of an online player
  also updates their memory.
- Nothing about homes takes part in the economy, so no ledger transaction is involved.

## Tests

- Unit: `HomeNamesTest`, `HomeSafetyTest`, `HomeStoreTest`, `HomesAuditTest`, `HomesSettingsTest` ("/home with no
  name" for every choice and number of homes, the stored option ids); `TpaSettingsTest` registers the whole Teleports
  & homes group (homes' two settings in their places, streamer mode declared as read).
- E2E (`TeleportScenarios`): `homes-flow` (moving an existing home asks first), `homes-staff`, `homes-safety`,
  `homes-combat`, and `homes-settings` (always the list and moving without asking, saved in the settings dialog; the
  home named home typed with `/settings`; streamer mode in the list and the delete window).
