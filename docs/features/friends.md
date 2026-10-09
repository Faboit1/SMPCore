# Friends (`friends`)

Players add friends, see at a glance who is online, get one dependable signal when friends come online, and reach
the rest of the server from a friend's profile: message, teleport request, pay, stats and team invite. Package
`feature/friends`, config `features/friends.yml`, text `lang/friends.yml`, tables `friends`, `friend_requests`,
`friend_profiles` and `friend_log` (migration V100; 101-104 stay free).

The feature implements `core.link.FriendLookup`; `FriendsFeature#lookup()` returns it. It consumes:

| Contract | Wired | Used for |
|---|---|---|
| `IgnoreLookup` | `NONE` (no ignore list yet) | ignored senders' requests are hidden; no join alerts or auto-accepted teleports from ignored friends |
| `VanishStatus` | staff (`StaffFeature#vanish()`) | vanished players look offline everywhere here, trigger no alerts, get no Message/Teleport/Invite buttons, and can't send or accept requests while `requests.block-while-vanished` is on |
| `AfkStatus` | `NONE` (no AFK feature yet) | the "AFK" status word |
| `TeamLookup` | teams (`TeamsFeature#lookup()`) | team line, "friends of friends and teammates" privacy, the Invite to team button |
| `Ranks` | `NONE` (no LuckPerms integration yet) | the rank line on profiles |
| `CombatStatus` | core combat tags | a sneak-click card is refused while either player is in combat |
| `MuteStatus` | staff (`StaffFeature#mutes()`) | a muted player gets no Message button on profiles (and the message form refuses if the mute came in between) |

## Commands and permissions

`/friend` has the aliases `/f` and `/friends`. Every dialog action also has a command, so clients without dialogs
can do everything from chat.

| Command | What it does |
|---|---|
| `/friend` | The friends list dialog. From the console: a pointer to `/sift friends` and the staff help |
| `/friend <player>`, `/friend add <player>` | Sends a request; accepts theirs when they already asked; opens the profile when you are friends. A player named like a subcommand is reached with `/friend add <name>` |
| `/friend accept [player]`, `/friend deny [player]` | Answers a request. Without a name: acts at once when exactly one request is waiting, else opens the requests dialog |
| `/friend cancel <player>` | Withdraws your request |
| `/friend remove <player>` | Asks for confirmation, then ends the friendship. The other side is never told |
| `/friend requests` | The requests dialog |
| `/friend favourite <player>` (`fav`) | Toggles a favourite |
| `/friend note <player> [text]` | No text: the note form. `-` clears the note. Text sets it (cleaned, at most 64 characters) |
| `/friend settings [key] [value]` | The settings dialog, or shows/sets one value (tab completes keys and values; an unknown key or value is answered in chat with the choices) |
| `/friend list [page]` | The list in chat: each name runs `/profile <name>`, Previous and Next are clickable |
| `/profile [player]` | A friend's profile or anyone's player card; without a name your own card |
| `/sift friends ...` | Staff tools, below |

Names resolve through the player directory (Floodgate names such as `.Steve` included) and must be 1 to 17 letters,
digits, `_` or `.`, the same rule as the dialog form. An unknown name gets the core "Nobody called ... has played
here." Tab completion never offers a vanished player (or one the sender can't see) as online: such a player only
turns up among the known names after two typed characters, exactly like any offline player, so the suggestions never
tell who is hidden.

| Argument | Suggests |
|---|---|
| `add` / `<player>` | online visible players who are not friends and have no request from you, then known names after 2 characters |
| `accept` / `deny` | players whose request you can see |
| `cancel` | players you sent a request to |
| `remove`, `favourite`, `note` | your friends |
| `/profile` | online visible players, then known names |

`friend` has no `commands.yml` cooldown by default; one set there applies to sending requests. Spam is stopped by an
in-flight guard instead: while an action on a pair is on its way to the database, a second one gets "Still working on
that." Request rate limits are below.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.friend` | everyone | `/friend` |
| `siftcore.command.profile` | everyone | `/profile` and the sneak-click card |
| `siftcore.friends.limit.<n>` | none | friend limit (the highest number wins, never above `limits.hard-cap`) |
| `siftcore.friends.limit.unlimited` | nobody | as many friends as `limits.hard-cap` allows |
| `siftcore.admin.friends` | op | `/sift friends` |
| `siftcore.bypass.cooldown` | op | core node; also skips the request rate buckets |

Rank nodes on SiftVanilla (the default of 50 comes from the config): `prospector` `siftcore.friends.limit.100`,
`baron` `siftcore.friends.limit.200`, `tycoon` `siftcore.friends.limit.500` (the hard cap).

Staff tools (console friendly; reads run off the thread; every change is audited after it committed):

| Command | What it does |
|---|---|
| `/sift friends list <player>` | friends with since and favourite, and the player's limit (from memory while loaded, else the rank limit stored for them, the one the write units check) |
| `/sift friends requests <player>` | every request row in every state, hidden and closed ones included |
| `/sift friends history <player> [page]` | the `friend_log` rows of the player, newest first |
| `/sift friends add <a> <b>` | makes them friends; skips limits and privacy, not the hard cap; removes their requests both ways (audit `friends.add`) |
| `/sift friends remove <a> <b>` | ends the friendship; nobody is told (audit `friends.remove`) |
| `/sift friends clear-requests <player>` | deletes every request the player sent or received (audit `friends.clear-requests`) |

## Requests

The sender's reply is the same whether the target is online or offline and whether the request is hidden: "Friend
request sent to Alex." Checks, in order (1-4 in memory on the sender's thread, the rest inside the write unit):

1. yourself; 2. sending while vanished (`requests.block-while-vanished`); 3. account age (`requests.min-account-age`
since first join); 4. rate buckets (`requests.per-minute` per player and per hashed address);
5. already friends (the command opens the profile); 6. a request back that they still see becomes a friendship at
once (cause `MUTUAL`, its event first); 7. already sent; 8. `requests.max-outgoing`; 9. `requests.per-day` (from the
history, so it survives restarts); 10. your list full; 11. their list full (their stored rank limit while offline);
12. their privacy (`nobody`, or `known` = friends of friends and teammates), reported honestly because privacy is a
public choice.

A request that passes is **hidden** (`state='shadow'`) when the target ignores the sender, already has
`requests.max-incoming` visible requests, or denied this sender within `requests.deny-memory`. A hidden request is
stored, logged, counts against the sender's caps and looks waiting to the sender until it runs out; the target never
sees it. The ignore check is repeated wherever a request is shown (alerts, summary, dialogs, `friends_requests`) and
when accepting.

- **Accept** needs the request to be visible and not run out, and checks both friend limits at that moment. The
  requester gets "Alex accepted your friend request." with the success sound, or the login summary line "New friends
  while you were away" when offline. A vanished player can't accept while `requests.block-while-vanished` is on ("You
  can't accept friend requests while vanished."), because the requester would be told at once; with it off, the
  requester learns of it from their next login summary instead of live.
- **Deny** hides the request (`decided` set). The sender is never told and keeps seeing it waiting until it runs out;
  new requests during the deny memory stay hidden. **Deny and ignore** also runs `/ignore <name>` as the player when
  that command exists and the player may use it. **Deny all** (offered from 5 requests, with a confirmation) hides
  every visible request in one unit. There is deliberately no "Accept all".
- **Cancel** deletes the row; a denied row is closed instead and kept as deny memory.
- Requests run out silently after `requests.expire-after`. Deny, remove and expiry are never told to the other side.

## Dialogs

Every screen is a dialog `View`, so Bedrock players get the same screens as forms (the head line is dropped there).

- **Friends** (`/friend`, the main menu, the pause menu): "2 of 5 online, 5 of 50 friends." (plus "Ranks raise this
  limit." when full below `limits.hard-cap`, and "Page 1 of 3." when there is more than one page). Rows in two
  columns, `list.page-size` per page, ordered online favourites, online others (by name), offline favourites, offline
  others (by last seen): "Alex, online", "Bob, AFK", "Cara, seen 3d ago". The tooltip shows favourite, friends since
  and the note. "Seen" uses the player directory, the same source as `/seen`.
  Footer: Add a friend, Requests (n), Settings, Find (only when there is more than one page; a name prefix), Previous,
  Next, and Back to the menu when opened from the menu (else Close).
- **Requests**: "Incoming: 3. Sent: 1." Incoming rows "Alex, 2h ago" (tooltip: mutual friends and team) open the
  request; sent rows "Cancel: Cara, 1d ago" follow them in the same grid, so the body says "Rows starting with Cancel
  are requests you sent." and a sent row asks first ("Cancel your request to Cara?", Cancel request or Back): a click
  meant for an incoming row costs nothing. Ten per page each, with "Page 1 of 2." when there is more than one.
- **Request**: head, "Alex wants to be friends.", "Mutual friends: 2 (Bob, Cara)", team, "Sent 2h ago", and Accept,
  Deny, Deny and ignore, Back.
- **Add a friend**: "Enter a name" (a form, 1-17 characters) and up to `list.suggestions` people you may know:
  online players you can see who share friends with you (or your team), ranked by shared friends, without friends,
  open requests either way, ignores either way, and players whose privacy would refuse you.
- **Friend profile**: head, status (Online / AFK / Seen 3d ago), friends since, team, rank, mutual friends, your
  note. Buttons: Message (a form, then `/msg`), Teleport request (`/tpa`), Invite to team (`/team invite`), Pay
  (`/pay`, which opens its own form), Stats (`/stats`), Favourite/Unfavourite (while favourites exist), Edit note,
  Remove friend, Back.
- **Player card** (`/profile` on anyone else, or sneak + right-click with an empty hand): head, status, team, rank,
  one friendship button (Add friend, hidden when they are online and their privacy refuses you; Accept request; Cancel
  request), the same command buttons, Close. Your own card has Stats only. No friendship data is shown on cards.
- **Settings**: one form: "Requests from" (Everyone, People I know, Nobody; the body says people you know are friends
  of friends and teammates), "Join alerts" (All friends, Favourites only, Off), and three switches. Short labels, so
  each choice reads "Label: Option" without scrolling in its button.

A click keeps its dialog on screen until the next one replaces it, also when the next screen first writes or reads the
database: the handler tells the dialog router an answer is coming (`Dialogs#markShown`), so the router doesn't close
the dialog after its short grace and the player never sees the world flash between two screens. A read that fails
closes the dialog and says "Something went wrong. Nothing was changed."

Command buttons show only when the command is registered (`commands.yml` can turn commands off), the viewer has its
permission (`siftcore.command.msg`, `.tpa`, `.team`, `.pay`, `.stats.others`) and, for Message, Teleport and Invite,
the friend is visibly online (Invite also needs the friend to have no team and the viewer to be allowed to invite;
Message is left out while the viewer is muted).
They run the command as the player from the dialog handler, on the player's thread, exactly as if it had been typed:
the command preprocess event fires first (`performCommand` alone would skip it), so the server's command guards apply
(commands refused in combat, while frozen or while muted) and the other feature checks everything itself. If running
it throws, or the server reports it as not run (`performCommand` returns false), the player gets "That didn't work.
Run /tpa Alex instead." with the command as a link; a throw is also logged, once per command. Stats and Pay open the
other feature's own screens, whose exit closes them (no Back to the profile yet; see the integration list below).

## Presence

- **Login summary**, `presence.summary-delay` after joining once the friends are loaded, only lines with content:
  "Friends online: Alex, Bob and Cara." (up to five clickable names, favourites first, then "and 2 more"), "Friend
  requests waiting: 2. View, or type /friend requests.", "New friends while you were away: Cara."
- **Join alerts**: the join is looked at `presence.join-delay` after it (so a vanish applied on join counts) and is not
  told when the joiner turned "Tell friends when I join" off, is vanished, left less than `presence.relog-grace` ago,
  or within `presence.startup-quiet` of the start. Each viewer must want it (`all`, or `favourites` and a favourite),
  not ignore the joiner, and be able to see them. Joins within one join delay become one chat line, "Alex, Bob and
  2 more are online." (gray, with the names white and clickable: each opens that profile), with the notify sound only
  for a favourite. Chat, not the action bar, because combat, sell and AFK messages overwrite the action bar.
- **Leave alerts** (off by default, never with a sound): "Alex went offline." (the name white and clickable, like the
  join alert's) after `presence.leave-delay`, dropped when they came back.
- **Request alerts** (only to an online target, only for visible requests, when "Tell me about new requests" is on):
  "Alex sent you a friend request. Accept or Deny, or type /friend requests." with Accept and Deny links. More
  requests within `presence.request-batch` become "New friend requests: 3. View, or type /friend requests." No dialog
  is embedded: inline dialog sessions expire after 15 minutes and only 8 are kept per player, so alt spam would evict
  open shop and pay dialogs, and old clients can't open them.

## Settings

Kept in core `PlayerSettings` (the `settings` table); invalid stored values read as the default.

| Key (`/friend settings`) | Stored as | Values (default) | Also in `/settings` |
|---|---|---|---|
| `requests` | `friends-requests` | `everyone` / `known` / `nobody` (`everyone`) | |
| `join-alerts` | `friends-join-alerts` | `all` / `favourites` / `off` (`all`) | |
| `leave-alerts` | toggle `friends-leave-alerts` | on / off (off) | yes |
| `request-alerts` | toggle `friends-request-alerts` | on / off (on) | yes |
| `announce` | toggle `friends-announce` | on / off (on): "Tell friends when I join" | yes |

There is no "appear offline": the tab list and `/seen` show you anyway. `announce` off means no join or leave alerts.
With `limits.favourites: 0`, `join-alerts` takes `all` or `off` only, and a stored `favourites` acts as `off`.

`friends-tpa` (`nobody` / `favourites` / `all`, default `nobody`) is stored in the same table and answers
`FriendLookup#autoAcceptTeleport`, but players are not offered it yet: TPA still lets friends skip requests with its
own "Friends skip requests" toggle (`/tpatoggle friends`), and two switches for one thing would contradict each other.
The integration pass that makes TPA ask `autoAcceptTeleport` adds the choice to `FriendPrefs.Key` and the settings
form and drops TPA's toggle.

## Limits and anti-abuse

- Friend limit: `Limits.highest(player, "siftcore.friends.limit", limits.default)`, capped by `limits.hard-cap`. It
  is read on the player's thread at join and every minute while online, and stored with the rank label in
  `friend_profiles` when it changed, so checks while the player is offline are exact. A lower limit never removes
  friends; it only stops new ones.
- Favourites: at most `limits.favourites` ("Your favourites are full (10)."). `0` turns favourites off: no
  Favourite button, no "Favourites only" join alerts, "Favourites are turned off on this server." from the command,
  and stored favourite flags are ignored (list order, alert sounds).
- Requests: per minute per player and per hashed address, per day (from the history), outgoing open, incoming visible
  (more are hidden), minimum account age, deny memory.
- Notes are cleaned like team chat (control and format characters and the section sign removed, trimmed, at most 64
  code points) and always shown as literal text.

## Storage and threading

| Table | Rows |
|---|---|
| `friends` | two directed rows per friendship; the owner's favourite, note and "made while away" notice |
| `friend_requests` | one row per sender and target: `pending` (both see it), `shadow` (only the sender), `closed` (deny memory) |
| `friend_profiles` | each player's rank limit (0 = unknown, the default applies) and rank label |
| `friend_log` | history: request, accept, mutual, deny, cancel, remove, staff_add, staff_remove, staff_clear |

Every action is one `database().write(...)` unit: all checks read first, then the changes, then the history row. The
single ordered writer runs units one after another, so check-then-act can't race and offline counterparts never need
to be in memory. Business refusals come back as an `Outcome`; a unit only throws on a real storage error. Each unit
takes a sequence number on the writer and returns the committed state of every pair it touched; memory takes a pair
change only when it is newer than what it has (database callbacks can complete out of order), a removal leaves a
tombstone for `recentlyFriends`, and a node that is still loading buffers changes and replays the newer ones.

Memory (`FriendGraph`) holds immutable nodes per loaded player: friends with favourites, visible requests both ways,
tombstones and the rank limit. A node is installed at pre-login (the load is a write unit too, ordered with every
change), kept `memory.grace` after quitting, and swept a minute later for players who never joined. The join takes a
leaving node back (a second login of an online account runs its pre-login before the old session quits), and each
loading node only takes the result of its own load, so a load queued before a quick quit and rejoin can't fill the
newer node with an older snapshot. Nothing is said or
audited before the unit committed; the action flow is: memory checks and the event on the actor's thread, the unit,
then memory, messages and the next dialog. `disable()` cancels timers and touches no region APIs; there is no
write-behind to flush.

A sweeper runs hourly in one unit: run-out requests (keeping denied ones during the deny memory), closed rows past the
deny memory, and history older than `log.keep`.

## Events

All cancellable, fired on the acting player's thread (the global thread for the console) before anything changes,
never from database callbacks:

| Event | Notes |
|---|---|
| `FriendRequestEvent(sender, target)` | every request that passed the sender-side checks; whether the target will see it is not exposed |
| `FriendAddEvent(player, friend, cause)` | `REQUEST` (player accepted), `MUTUAL` (player requested back), `STAFF` |
| `FriendRemoveEvent(player, friend, cause)` | `PLAYER` or `STAFF` |

## Placeholders

| Placeholder | Value |
|---|---|
| `%siftcore_friends_count%` | number of friends |
| `%siftcore_friends_online%` | friends online and not vanished (cached per player for a second) |
| `%siftcore_friends_requests%` | visible requests waiting, without ignored senders |
| `%siftcore_friends_limit%` | the friend limit |

They read memory only and answer `0` for players who are not loaded.

## For other features (`core.link.FriendLookup`)

| Method | Answer |
|---|---|
| `friends(a, b)` | from whichever of the two is loaded; exact while either is |
| `friendsOf(player)` | an immutable set, empty when the player is not loaded |
| `recentlyFriends(a, b, window)` | friends now, or removed within `min(window, anti-farm.remember)` |
| `autoAcceptTeleport(target, requester)` | the target is loaded, they are friends, the target's `friends-tpa` is `all` (or `favourites` and the requester is a favourite) and the target doesn't ignore the requester |

Contracts the other features implement when they are built: TPA calls `autoAcceptTeleport(target, from)` on the
requester's thread for plain `/tpa` only, keeps its warmup and combat refusal through `Teleports`, and suggests online
friends first. Combat anti-farm and bounties use `recentlyFriends(killer, victim, window)` (24h by default), not
`friends()`, so unfriend, kill and re-friend gives no credit. Chat makes names clickable (`/profile <name>`), uses
`friends()` for friends-only messages, and implements `IgnoreLookup`; because chat consumes `FriendLookup`, the
integration pass should build `IgnoreLookup` in core over the V009 `ignores` table to avoid a constructor cycle.

State of the features that were merged before friends (wired for real in `FeatureCatalog`, each to be finished on its
own side in the integration pass):

| Feature | Uses today | Still to do on its side |
|---|---|---|
| TPA | `friends(target, sender)` plus its own `tpa-friends` toggle (`/tpatoggle friends`, `/settings`) | call `autoAcceptTeleport(target, sender)` for plain `/tpa` instead (one line in `TpaService#skipsRequest`), drop the `tpa-friends` toggle, and add `tpa` back to the friends settings (`FriendPrefs.Key` and the settings form), so the friends choice (nobody, favourites, all) is the only switch; until then players aren't offered it |
| Combat | `recentlyFriends(killer, victim, anti-farm.friends-window)` (24h) for anti-farm, so a removed friend gives no credit or bounty for a while | nothing |

## Hub and pause menu

Hub entry `friends` (order 52, "Friends", "See who's online and add friends", `siftcore.command.friend`) opens the
list with Back to the menu. The bundled `features/hub.yml` lists `friends` after `teams` in the pause menu and
`lang/hub.yml` has its label. A live server keeps its existing `hub.yml` (only missing files are copied), so deploying
the pause-menu button needs that one-line edit, a restart and a line in `docs/server-undo-log.md`.

## Config (`features/friends.yml`)

| Key | Default | Meaning |
|---|---|---|
| `limits.default` / `hard-cap` / `favourites` | 50 / 500 / 10 | friend limit without a rank node, the cap of everything, favourites per player (0 = off) |
| `requests.expire-after` | 7d | requests run out (1h-30d) |
| `requests.deny-memory` | 7d | how long a deny keeps new requests hidden (0-30d) |
| `requests.max-outgoing` / `max-incoming` | 20 / 50 | open sent requests; visible waiting requests (more are hidden) |
| `requests.per-minute` / `per-day` | 5 / 30 | rate per player and address; daily cap from the history |
| `requests.min-account-age` | 10m | wait after the first join before sending requests |
| `requests.block-while-vanished` | true | vanished staff can't send or accept requests |
| `presence.summary-delay` / `join-delay` | 3s / 3s | login summary delay; join alert delay and batching window |
| `presence.relog-grace` / `leave-delay` / `startup-quiet` | 2m / 30s / 60s | no alert for relogs; leave alert delay; no join alerts after a start |
| `presence.request-batch` | 10s | request alert batching window (0 = every alert at once) |
| `list.page-size` / `suggestions` | 16 / 6 | rows per list page (4-30); "people you may know" (0-12, 0 = off) |
| `profile.sneak-click` / `sneak-click-cooldown` | true / 1s | the sneak right-click card |
| `memory.grace` | 60s | how long friends stay in memory after quitting |
| `anti-farm.remember` | 7d | how long removals are remembered for `recentlyFriends` |
| `log.keep` | 90d | history kept (at least `anti-farm.remember`) |

## Self-test

`/sift selftest` checks: friendships are symmetric, nobody is their own friend, no requests between friends, up to 20
loaded players' memory matches storage, no stray nodes for players who are not online, decision table spot checks,
and the note cleaner.

## Design decisions and what was cut

- **No friend chat mode or `/fc`.** It would be one-sided (other friends don't see the replies), fight team chat on the
  same chat event, and be a side channel without mute or spy; `/msg` and team chat cover it.
- **No "appear offline".** The tab list and `/seen` show the player anyway; `friends-announce` replaces it honestly.
- **No kills, deaths or playtime on profiles.** They read 0 for offline players; the Stats button runs `/stats`.
- **No friends settings table.** `PlayerSettings` stays the one source of truth.
- **No visible deny cooldown.** A detectable re-poke timer; silent deny memory replaces it.
- **No inline dialogs in request lines, no rank on list rows, no Accept all, no friend leaderboards or activity feeds**
  (they invite alt farming and noise), **no `last_visible` column** ("seen" matches `/seen` exactly).
- **Left for the integration pass** (they touch other packages): a core profile actions registry (it would also give
  the Stats and Pay screens opened from a profile a Back to it: `StatsViews` already takes a back handler, the pay
  form a cancel handler, but both are reached by command today), a shared chat modes service, `IgnoreLookup` in core,
  per-player hub tooltips, a vanish change event, `StatsRecorder.load`, a suggest-command dialog button type, and the
  `CommandSupport.knownPlayer` vanish leak (friends uses its own suggestion providers).

## Known limitations

- Vanishing or unvanishing mid-session produces no fake leave or join alert; lists and buttons follow `VanishStatus`
  live. A vanished player's "seen" time is their last join, the same as `/seen`.
- `IgnoreLookup` is `NONE` until an ignore list exists, so ignore hiding is inactive until then.
- Profile buttons appear only for commands that exist: `msg` is the vanilla command (operators only) until the chat
  feature exists, and `ignore` is not a command yet, so "Deny and ignore" stays hidden. Teleport request (`/tpa`),
  Invite to team, Pay and Stats run SiftCore's own commands.
- `friends-tpa` is not offered to players while TPA uses its own `tpa-friends` toggle (see the table above), so
  `autoAcceptTeleport` answers false for everyone until the integration pass switches TPA over.
- Stats and Pay opened from a profile have no Back to it (see the integration list).
- `recentlyFriends` remembers at most 7 days. Rate buckets reset on restart; the daily cap does not.
