# Teleport requests (`tpa`)

Players ask to teleport to each other. Package `feature/tpa`, config `features/tpa.yml`, text `lang/tpa.yml`. No
tables: requests live in memory and expire within a minute.

The player who moves gets the shared teleport warmup (`core.teleport.Teleports`: a countdown shown where the player's
"Teleport countdown" setting says, cancelled by moving or damage, refused while combat-tagged). The destination is
wherever the other player stands when the warmup ends, read on that player's thread. The feature consumes these
contracts:

| Contract | Wired | Used for |
|---|---|---|
| `VanishStatus` | staff feature | A vanished staff member looks offline to players without `siftcore.tpa.bypass` |
| `IgnoreLookup` | chat | A player who ignores the sender never gets the request; the sender is told they can't send one |
| `AfkStatus` | AFK | The sender is told the target is AFK, so an unanswered request makes sense; no pop-up while AFK |
| `Relations` (`services.relations()`) | friends, teams | Friends and teammates for "Teleport requests from", "Pull requests from" and auto-accept; the friends lookup answers favourites |
| `CombatStatus` | core combat tags | Combat-tagged players can't send or accept requests (see "Combat" below) |

## Commands and permissions

| Command | What it does |
|---|---|
| `/tpa <player>` (alias `/tpask`) | Asks to teleport to the player |
| `/tpahere <player>` | Asks the player to teleport to you |
| `/tpaccept [player]` (alias `/tpyes`) | Accepts the only request, the named one, or opens a choice dialog when several wait |
| `/tpdeny [player]` (alias `/tpno`) | Denies the same way; the choice dialog also has Deny all |
| `/tpacancel [player]` (alias `/tpcancel`) | Withdraws your request to that player, or all of yours |
| `/tpatoggle` (alias `/tptoggle`) | "Teleport requests from": nobody (requests are declined at once), or back to everyone. Any other choice (friends...) counts as on, so it goes to nobody |
| `/tpatoggle friends [choice]` | "Auto-accept /tpa from": nobody goes to all friends, anything else back to nobody; with a choice (`nobody`, `favourites`, `all`, `friends-team`, suggested as offered) it is set to that. Only with a friends list. This form replaces the old `tpa-friends` switch's command (owner note: the shared `friends-tpa` is what it changes now) |

Both `/tpatoggle` forms change the settings through the settings registry: when the server locked or hides the
setting the player reads "Teleport requests from is set by the server." (or "Auto-accept /tpa from ..."), and an
option that isn't offered now (favourites without favourites) reads "... couldn't be changed.". `/tpatoggle friends`
checks the server's say before it looks at the typed word (`TpaGate#typedChoice`), so a hidden setting never answers
"Use one of these: ." with an empty list.

Name arguments suggest only the players in your own requests (`/tpaccept`, `/tpdeny`: who asked you; `/tpacancel`:
who you asked) or online players you can see (`/tpa`, `/tpahere`); selectors are never accepted.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.tpa`, `.tpahere`, `.tpaccept`, `.tpdeny`, `.tpacancel`, `.tpatoggle` | everyone | Use the command |
| `siftcore.tpa.bypass` | op | `/tpa` teleports at once with no request; `/tpahere` reaches players who turned requests off or ignore them; vanished staff they can see count as online |
| `siftcore.teleport.bypass-warmup` | op | No warmup (core node) |
| `siftcore.bypass.cooldown` | op | No cooldown between requests (core node) |

## How a request flows

1. The sender passes the checks in `TpaGate` (pure, unit tested), in this order: not yourself, online and visible,
   staff `/tpa` (instant), ignored, the target's "Teleport requests from" doesn't take the sender ("<name> isn't
   taking teleport requests."), and for a `/tpahere` the target's "Pull requests from" doesn't take the sender
   ("<name> isn't taking requests to come to you. Ask with /tpa instead."). Staff with the bypass pass both. Then the
   request cooldown and the cancellable `api.event.TeleportRequestEvent`.
2. A `/tpa` the target auto-accepts ("Auto-accept /tpa from", see below) skips the request: the sender's warmup starts
   at once. Otherwise the target gets a chat line with a clickable `Click to answer` (in the accent colour): a
   `ClickEvent.showDialog` carrying an accept/deny dialog made for that target (`Dialogs#inline`). The dialog is one
   line ("Alex wants to teleport to you.") and a green Accept and a red Deny; what each answer does, and when requests
   expire, are on the buttons' tooltips. With several requests waiting, `/tpaccept` and `/tpdeny` open "Accept a
   request" or "Deny a request": one button per sender (which way in its tooltip) and, for denying, Deny all. Typing `/tpaccept` works the same. With
   "Requests open a pop-up" on, the same window also opens by itself, on the target's thread, unless they are in
   combat, AFK or busy in a window, and only while the request still waits. "Busy in a window" is what the server
   can see: a window it opened (a chest, a SiftCore menu, an anvil) is always seen; the player's own inventory is
   opened by the client without telling the server (the open view still reads as the crafting screen), so it counts
   only while they are clicking in it: a click in their own inventory within the last 5 seconds, until they close it
   (`InventoryUse`). A player who just opened their inventory without clicking, or who looks at another dialog, can't
   be detected, so a pop-up can replace it.
3. Requests are keyed by sender: a player can have requests from many players at once; a new request from the same
   sender replaces the older one. Each expires on its own; the sender is told when it does.
4. Accepting takes the request atomically (`TpaRequests#take`, by request id: an old dialog can't accept a newer
   request) and starts the warmup for whoever moves. If they move, take damage or are in combat, the other player is
   told they didn't come. A `/tpaccept` that would move the player who answers (a `/tpahere`) shows the request's
   window once more while "Confirm before being pulled" is on, however the request was picked: the only one, a named
   one, or a sender picked in the window of several requests (`TpaGate#answer` decides all three). The window of a
   `/tpahere` says "Accepting teleports you to <name>.". Accept and Deny close the window at once; in the window of
   several, a pick that finishes (a `/tpa`, any deny) closes it at once, and a pick that asks once more keeps it up
   until the request's own window replaces it.

## Combat

The combat feature refuses `/tpa`, `/tpahere` and `/tpaccept` while tagged, but requests are also sent from the main
menu's form and answered from the chat dialog, so the feature checks combat itself, whichever way a player acts:

- A combat-tagged player can't send a request ("You can't use teleport requests in combat. <time> left."). Without
  this a tagged player could `/tpahere` an ally into the fight from the form.
- Nobody accepts a request while either player is in combat (`TpaGate#acceptBlocked`): a tagged target can't pull a
  teammate in by accepting their `/tpa` from the chat dialog, and nobody is pulled into the sender's fight
  ("<name> is in combat. Their request waits; accept it once the fight is over."). The request stays where it was, so
  it can be accepted once the fight is over. Denying always works.
- A friend's `/tpa` skips the request only while the target isn't in combat; otherwise it becomes a normal request.
- When the warmup ends, the player who stays put is checked again (`TpaGate#unlessFighting`, in the destination
  supplier, after their position was read on their thread): the shared teleports re-check only the mover, so a player
  attacked during the 3 second warmup would otherwise still get the ally delivered mid-fight. The mover is told
  ("<name> is in combat now. The teleport was cancelled."), the other player hears they didn't come, and the request
  is used up (send a new one after the fight). Staff `/tpa` (bypass, no request) is not checked.

The main menu entry `tpa` (order 62) opens a form: the player's name, then a button for each way, "Go to them" (a
/tpa) and "Bring them here" (a /tpahere), what each does in its tooltip, and Back. Only while requests wait for you,
one short line above says how many. An unknown or hidden name, or your own, shows in red on the form again with the
name kept.

## Per-player settings (Settings > Teleports & homes)

Registered in `SettingCategories.TELEPORT` by `TpaFeature.registerSettings`, in the catalog's order (the shared
`friends-tpa` is 2nd, core's `teleport-display` 4th, the homes and random teleport settings fill the rest). Text:
`tpa.settings.*` in `lang/tpa.yml`; the option names are the shared ones in `lang/settings.yml`.

| Id | Kind | Default | Read in | Meaning |
|---|---|---|---|---|
| `tpa-requests` | who-can choice everyone / friends-team / friends / nobody | everyone | `TpaService#request` (`TpaGate#check`) | "Teleport requests from": who may send /tpa and /tpahere. Was a switch: stored `true` reads as everyone and `false` as nobody (rows and `features/settings.yml` entries keep working and are rewritten on the next change). `placeholder(false)` |
| `tpahere-requests` | who-can choice, same options | everyone | `TpaService#request` | "Pull requests from": who may ask you over with /tpahere, on top of the first (the stricter wins). `placeholder(false)` |
| `tpa-popup` | switch | off | `TpaService#send` / `popUp` | "Requests open a pop-up" (see the flow above) |
| `tpaccept-confirm-here` | switch | on | `TpaService#answerOf` (typed, named, and the window of several requests) | "Confirm before being pulled": a /tpaccept of a /tpahere asks once more |
| `friends-tpa` (shared, `SharedSettings`) | choice nobody / favourites / all / friends-team | nobody | `TpaService#skipsRequest` | "Auto-accept /tpa from": whose plain `/tpa` comes without asking (never a `/tpahere`, never an ignored player, never while the target is in combat). Favourites are answered by the friends feature (`FriendLookup#autoAcceptTeleport`) and offered only while favourites exist; the setting is offered only with a friends list (TPA declares it reads it) |

The group also holds core's `teleport-display` ("Teleport countdown", choice actionbar / title / chat / off, default
actionbar), registered by `core.teleport.Teleports` itself and read for every shared teleport (homes, TPA, random
teleport, spawn, team home, the AFK zone): the warmup countdown shows above the hotbar every second, as a title every
second, as one chat line, or not at all, and the arrival line ("Teleported.", a home's welcome, a random teleport's
landing) goes to the same place (`TeleportDisplay`, unit tested). As a title, only the first second fades in; the
later ones change the number in place and each stays 1.5 seconds, so the count never blinks between seconds. Cancel
messages and failures always show, and a line saying money was paid shows even with "off"; a countdown title is taken
off the screen first (moved, damage, replaced, frozen, in combat, a failed or refused destination), so "Teleporting in
3s. Don't move." never sits over the line saying the teleport was called off. Features with their own arrival line start the teleport with
`announces` and send it through `Teleports#arrival`, so "Teleported." isn't sent twice. Its text is `teleport.settings`
in `lang/core.yml`.

The friend options of the two who-can choices are offered only while the server has a friends list ("Friends and
teammates": or teams); a player who picked one reads it as nobody meanwhile, so turning friends off never opens
anyone up. The retired `tpa-friends` switch is no longer registered: its rows move to `friends-tpa` when a player
loads (on: all friends, off: nobody).

Owner note: the move covers stored rows only. An entry for `tpa-friends` in `features/settings.yml` (`defaults:`,
`locked:` or `hidden:`) no longer applies; the server warns "there is no setting called tpa-friends" at startup.
Write it for `friends-tpa` instead, with a choice id (`all` for the old `true`, `nobody` for `false`; `friends-tpa`
has no `true`/`false` aliases). The bundled `features/settings.yml`
has no such entry.

## Config (`features/tpa.yml`)

| Key | Default | Meaning |
|---|---|---|
| `expire-after` | `60s` | How long a request waits (10s to 10m) |
| `warmup` | `3s` | Standing still before the one who moves teleports |
| `request-cooldown` | `5s` | Time between two requests of the same sender |

## Placeholders

| Name | Value |
|---|---|
| `tpa_requests` | Requests waiting for the player's answer |

## Design decisions

- Requests never touch storage: they are short-lived and meaningless after a restart.
- Every answer re-checks at execution time: neither player is in combat, the request must still exist (and be the
  same request), both players must be online, and neither the mover nor the player they go to may be in combat
  when the warmup ends.
- A refused request because of an ignore list reads like any refusal ("You can't send X a teleport request"), so
  the sender learns nothing about the ignore.
- Pending requests are swept once a second off the world threads; quitting drops every request of that player.
- `/tpatoggle friends` toggles (nobody and all friends, keeping the old command's messages) rather than cycling
  through four choices; the choice argument reaches the others directly.

## Tests

- Unit: `TpaGateTest` (the gate order including pull requests, who-can audiences, auto-accept for every choice and
  ignored players, the pop-up and pull-confirmation rules, `answer` for every way a request is picked, the order of
  `/tpatoggle friends`' checks), `InventoryUseTest` (the own inventory counts as busy for 5 seconds after a click,
  until closed), `TpaSettingsTest` (legacy values and a lock written as the old switch, the whole Teleports & homes
  group of ten in the catalog's order with homes and random teleport registered, rtp-confirm-cost following the paid
  regions, streamer mode declared, tpa-friends retired, friend options and favourites offered only when they exist),
  `TeleportDisplayTest` (the countdown and arrival places, title timing, the title cleared on every cancel and
  failure and not on arrival).
- E2E (`TeleportScenarios`): `tpa-flow`, `tpa-switches`, `tpa-combat`, `tpa-combat-warmup`, and `tpa-settings`
  (Teleport requests from friends saved in the settings dialog: a stranger refused, a friend's request arrives; Pull
  requests from nobody typed as `/settings tpahere-requests nobody`; no pop-up while the player clicks in their own
  inventory, then the pop-up; being pulled asks once more, Deny there; with a /tpa and a /tpahere waiting, picking
  the /tpahere sender in the window of several asks once more, picking the /tpa sender doesn't; off moves at once;
  /tpatoggle under a server lock written as `false`; /tpatoggle friends friends-team letting a friend in; /tpatoggle
  friends with an unknown word while the server hides the setting says it is the server's). Every other setting
  change in the teleport scenarios is typed as `/settings <id> <value>`. `teleport-display` also cancels a title
  countdown by moving (the cancel line shows, no countdown title follows).
