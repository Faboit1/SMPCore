# Friends (`friends`)

Players add friends, see at a glance who is online, get one dependable signal when friends come online, and reach
the rest of the server from a friend's profile: message, teleport request, pay, stats and team invite. Package
`feature/friends`, config `features/friends.yml`, text `lang/friends.yml`, tables `friends`, `friend_requests`,
`friend_profiles` and `friend_log` (migration V100; 101-104 stay free).

The feature implements `core.link.FriendLookup`; `FriendsFeature#lookup()` returns it. It consumes:

| Contract | Wired | Used for |
|---|---|---|
| `IgnoreLookup` | chat (`ChatFeature#ignores()`) | ignored senders' requests are hidden; no join alerts or auto-accepted teleports from ignored friends |
| `VanishStatus` | staff (`StaffFeature#vanish()`) | vanished players look offline everywhere here, trigger no alerts, get no Message/Teleport/Invite buttons, and can't send or accept requests while `requests.block-while-vanished` is on |
| `AfkStatus` | AFK (`AfkFeature#status()`) | the "AFK" status word |
| `TeamLookup` | teams (`TeamsFeature#lookup()`) | team line, "friends of friends and teammates" privacy, the Invite to team button |
| `Ranks` | integrations (`IntegrationsFeature#ranks()`, LuckPerms) | the rank line on profiles (empty for players who turned "Show my rank" off) |
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
| `/friend settings [key] [value]` | The Friends & teams settings page, or shows/sets one friends setting through the settings registry (tab completes keys and values; an unknown key or value is answered in chat with the choices; a locked or hidden setting is refused with the reason) |
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

`friend` has no `commands.yml` cooldown by default; one set there applies to every `/friend` command and, separately,
to sending requests (by command or from the friend screens), so opening `/friend` never holds up the request sent from
its screens. Spam is stopped by an in-flight guard instead: while an action on a pair is on its
way to the database, a second one gets "Still working on that." Request rate limits are below.

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
They follow the dialog style (`docs/development.md`): buttons, not paragraphs; what a button does is in its tooltip;
at most a short status above the buttons; nothing is paged (the dialogs scroll); values coloured (online green, AFK and
numbers in the accent colour, ON green and OFF red).

- **Friends** (`/friend`, the main menu, the pause menu): one status line, "2 online, 5 of 50 friends" ("No friends
  yet." below it for an empty list). Every friend is a button, two columns, in the order the player picked (Sort friends
  by): online first (the default: online favourites, online others by name, offline favourites, offline others by last
  seen), name, recently online, or longest friends. "Alex, online" (online in green), "Bob, AFK", "Cara, seen 3d ago",
  or "Dan, offline" when Dan keeps his last-seen time from the viewer (`seen-privacy`; such a time doesn't count for the
  order either). The tooltip shows favourite, friends since, the note and "Click for their profile". "Seen" uses the
  player directory, the same source as `/seen`. The list is bounded by the friend limit (at most `limits.hard-cap`), so
  nothing is paged. Then: Add a friend (tooltip; plus "Your list is full. Ranks raise the limit." when full below
  `limits.hard-cap`), Requests: n, Settings, Find from `list.find-from` friends (a name prefix; "Names starting with
  Al: 2" and Show all while filtered), and Back to the menu when opened from the menu (else Close).
- **Requests**: one status line, "Incoming: 3. Sent: 1." Incoming rows "Alex, 2h ago" (tooltip: mutual friends, team,
  "Click to answer") open the request; rows "Sent to Cara, 1d ago" follow them (tooltip: "A request you sent. Click to
  withdraw it (asks first).") and ask first ("Cancel your request to Cara?", Cancel request or Back): a click meant for
  an incoming row costs nothing. Deny all from 5 requests ("Deny all 5 requests? Nobody is told."). Both lists are
  bounded (`requests.max-incoming`, `max-outgoing`), so nothing is paged.
- **Request**: head, "Alex wants to be friends.", "Mutual friends: 2 (Bob, Cara)", team, "Sent 2h ago", and Accept
  ("You become friends."), Deny ("They aren't told."), Deny and ignore, Back.
- **Add a friend**: "Enter a name" (a form, 1-17 characters; Send request says on its tooltip that they can accept or
  deny) and up to `list.suggestions` people you may know: online players you can see who share friends with you (or
  your team), ranked by shared friends, without friends, open requests either way, ignores either way, and players
  whose privacy would refuse you.
- **Friend profile**: head, status (Online / AFK / Seen 3d ago, or Offline when the player keeps the time from the
  viewer with `seen-privacy`; staff with `siftcore.staff.whois` always see it), friends since, team, rank, mutual
  friends, your note. Buttons, each with a tooltip: Message (a form, then `/msg`), Teleport request (`/tpa`), Invite to
  team (`/team invite`, only for a player without a team whose `team-invites` takes invites from you), Pay (`/pay`,
  which opens its own form), Stats (`/stats`), the switch **Favourite: ON/OFF** (while favourites exist; it flips at once
  and the profile shows again, without a message), Edit note (Save explains in its tooltip that only you see it and that
  empty clears it; the profile shows the note again, without a message), Remove friend (asks first), Back.
- **Player card** (`/profile` on anyone else, or sneak + right-click with an empty hand): head, status, team, rank,
  one friendship button (Add friend, hidden when they are online and their privacy refuses you; Accept request; Cancel
  request), the same command buttons, Close. Your own card has Stats only. No friendship data is shown on cards.
- **Settings** (the list's Settings button and `/friend settings`): the Friends & teams page of the settings dialog
  (`/settings social`), with the friends and teams settings together; Back returns through the settings groups to the
  friends list. When the player can change none of them (the server hides them all), the list says so instead.

A click keeps its dialog on screen until the next one replaces it, also when the next screen first writes or reads the
database: the handler tells the dialog router an answer is coming (`Dialogs#markShown`), so the router doesn't close
the dialog after its short grace and the player never sees the world flash between two screens. A read that fails
closes the dialog and says "Something went wrong. Nothing was changed."

Command buttons show only when a command is registered under the label (any plugin's, see "Known limitations"), the
viewer has its permission (`siftcore.command.msg`, `.tpa`, `.team`, `.pay`, `.stats.others`) and, for Message,
Teleport and Invite, the friend is visibly online (Invite also needs the friend to have no team and the viewer to be
allowed to invite; Message is left out while the viewer is muted).
They run the command as the player from the dialog handler, on the player's thread, exactly as if it had been typed:
the command preprocess event fires first (`performCommand` alone would skip it), so the server's command guards apply
(commands refused in combat, while frozen or while muted) and the other feature checks everything itself. If running
it throws, or the server reports it as not run (`performCommand` returns false), the player gets "That didn't work.
Run /tpa Alex instead." with the command as a link; a throw is also logged, once per command. Stats and Pay open the
other feature's own screens, whose exit closes them (no Back to the profile; see "Not built" under Design decisions).

## Presence

- **Login summary**, `presence.summary-delay` after joining once the friends are loaded, only lines with content:
  "Friends online: Alex, Bob and Cara." (up to five clickable names, favourites first, then "and 2 more"), "Friend
  requests waiting: 2. View, or type /friend requests.", "New friends while you were away: Cara." A player who turned
  "Friends summary on join" off gets none of it; friends made while away stay noted for their first login with it on.
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

Registered player settings (`FriendPrefs`, core `PlayerSettings`, the `settings` table) in the shared **Friends &
teams** group of `/settings`, where the teams settings fill the gaps (catalog order). Ids and stored values are the
ones friends always used, so old rows still read; values are read leniently (case and spaces), invalid ones read as
the default, and storing the default deletes the row (the player then follows the server's default).

| Order | Id | `/friend settings` key | Kind, values (default) | What it does, where it is read |
|---|---|---|---|---|
| 1 | `friends-requests` | `requests` | choice `everyone` / `known` / `nobody` (`everyone`), no placeholder | who can send requests; read inside the request unit (below) |
| 2 | `friends-join-alerts` | `join-alerts` | choice `all` / `favourites` / `off` (`all`) | which friends' logins are told (`Presence.evaluateJoin`); "Favourites only" is offered while `limits.favourites` is above 0, a stored `favourites` reads as `off` meanwhile |
| 4 | `friends-request-alerts` | `request-alerts` | switch (on) | the chat line with Accept/Deny (`RequestAlerts.sent`); a switch because its buttons only work in chat |
| 7 | `friends-announce` | `announce` | switch (on) | "Tell friends when I join": join and leave alerts about this player |
| 8 | `friends-join-summary` | `join-summary` | switch (on) | the login summary (`Presence.summary`); while off, new-friend notices are kept for later |
| 9 | `friends-leave-alerts` | `leave-alerts` | switch (off) | "Alex went offline." (`Presence.leaveAlert`) |
| 10 | `friends-list-order` | `list-order` | choice `status` / `name` / `last-seen` / `oldest` (`status`) | the order of the list dialog and `/friend list` (`ListOrder.Sort`) |

`/friend settings <key> [value]` goes through the registry like the dialog: switches take `on`/`off`, choices their
option ids; the values offered are the ones the player may pick now (tab completion too). A setting the server locked
in `features/settings.yml` is refused with "requests is fixed by the server: everyone.", a hidden one with "You can't
change requests right now.", and one another plugin stopped with "requests couldn't be changed.". The answers keep
their old words ("Set requests to nobody.", "Use one of these for requests: everyone, known, nobody.").

**The request unit honours the server.** The target's `friends-requests` row is read inside the request's own
transaction (so a change just before a request is never missed); it is resolved like the registry does
(`StoredSetting`): the server's lock (or, while the server hides the setting, its default) wins, else the stored row,
else the server's default from `features/settings.yml`, else `everyone`. Before, a missing row always read as
`everyone`, which would have ignored `defaults: friends-requests: known` and the "storing the default deletes the
row" rule.

Shared settings friends reads (defined in core `SharedSettings`):

- `seen-privacy` (Privacy group: everyone / friends / nobody): friend list rows and profiles leave out the last-seen
  time of players who keep it from the viewer ("offline"; online status always shows). Offline friends' rows are read
  in one query per screen. Staff with `siftcore.staff.whois` see every time.
- `friends-tpa` (Teleports & homes: nobody / favourites / all / friends-team): `FriendLookup#autoAcceptTeleport`
  answers it (favourite friends only while favourites exist; "friends and teammates" also lets teammates in; never
  someone the target ignores). The teleport feature reads it (`TpaService`), so it is offered in Teleports & homes;
  rows of TPA's retired "Friends skip requests" switch (`tpa-friends`) move to `friends-tpa` when a player loads.

There is no "appear offline": the tab list and `/seen` show you anyway. `announce` off means no join or leave alerts.

## Limits and anti-abuse

- Friend limit: `Limits.highest(player, "siftcore.friends.limit", limits.default)`, capped by `limits.hard-cap`. It
  is read on the player's thread at join, every minute while online and as they quit, and stored with the rank label in
  `friend_profiles` when it changed, so checks while the player is offline are exact. A lower limit never removes
  friends; it only stops new ones. The stored label is what friends see on the profile of an offline player: it also
  follows `show-my-rank` at once (on the player's `SettingChangeEvent`) and is stored again at quit, so turning the rank
  off never leaves the old label on show.
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
| `favourite(owner, friend)` | the owner is loaded, `friend` is their friend marked as a favourite, and favourites are on (teams uses it so a favourite who gets the friends login alert isn't told twice) |

How the other features use it (all wired in `FeatureCatalog`):

| Feature | Uses |
|---|---|
| TPA | `autoAcceptTeleport(target, sender)` for plain `/tpa` only (`TpaService`, reading the shared `friends-tpa`); the warmup and combat refusal still apply through `Teleports`; online friends are suggested first |
| Combat (anti-farm, and through kill credit bounties and stats) | `recentlyFriends(killer, victim, anti-farm.friends-window)` (24h by default), not `friends()`, so unfriend, kill and re-friend gives no credit or bounty for a while |
| Teams | `favourite(owner, friend)`, so a favourite who already gets the friends login alert isn't told twice |
| Chat, economy, TPA, teams and every who-can setting | `services.relations()`, which core binds to `FriendLookup`, `TeamLookup` and chat's `IgnoreLookup` once every feature is built (friends only, friends and teammates, friends of friends) |
| Chat | clicking a name in public chat runs `/profile <name>` |

## Hub and pause menu

Hub entry `friends` (order 52, "Friends", "See who's online and add friends", `siftcore.command.friend`) opens the
list with Back to the menu. The bundled `features/hub.yml` lists `friends` after `teams` in the pause menu and
`lang/hub.yml` has its label. A server whose `pause-menu.entries` nobody edited gets the new entry written into its
file by itself (shipped values nobody changed follow new defaults, see [hub](hub.md)), and the pause screen shows it from
the following restart; an edited list needs `friends` added by hand and a restart.

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
| `list.find-from` / `page-size` / `suggestions` | 20 / 16 / 6 | the friends dialog offers Find from this many friends (it shows every friend, no pages); rows per page of `/friend list` in chat (4-30); "people you may know" (0-12, 0 = off) |
| `profile.sneak-click` / `sneak-click-cooldown` | true / 1s | the sneak right-click card |
| `memory.grace` | 60s | how long friends stay in memory after quitting |
| `anti-farm.remember` | 7d | how long removals are remembered for `recentlyFriends` |
| `log.keep` | 90d | history kept (at least `anti-farm.remember`) |

## Self-test

`/sift selftest` checks: friendships are symmetric, nobody is their own friend, no requests between friends, up to 20
loaded players' memory matches storage, no stray nodes for players who are not online, decision table spot checks,
and the note cleaner.

## Tests

Unit tests (`src/test/java/.../feature/friends`): the decision table, memory (`FriendGraph`, including auto-accept for
favourites, all friends and friends and teammates, and the favourite lookup), every write unit on SQLite (`FriendStoreTest`, including the
request unit reading privacy leniently, with the server's default and lock, and through real `PlayerSettings`
overrides), the settings (`FriendSettingsTest`: ids and stored values, groups and orders, favourites depending on the
config, `/friend settings` through the registry with locked and hidden settings, the list orders, who sees a last-seen
time, the profile's Invite to team following the target's `team-invites`), presence rules, rate limits and the
bundled text and config.

End-to-end scenarios (`tools/e2e`, `FriendsScenarios`): requests, mutual requests, silent denies, hidden requests,
limits (with the favourites option missing from the settings page while favourites are off), removal, double accept,
presence, offline accept and summary, staff tools, the dialogs (`friends-menu`: one status line, tooltips on every
button, the form explaining on its button, every friend on one list with online in green, Find from `list.find-from`,
the Settings button opens Friends & teams), profile buttons (tooltips everywhere, Favourite a switch that flips in
place without a message, no Invite to team while the friend's `team-invites` refuses), restart, request alerts, suggestions, links,
second login, command cooldown, and `friends-settings` (who can send requests picked in the settings dialog, the
server's default and lock, list order and login summary with `/friend settings`, the list order typed as
`/settings friends-list-order name`, `seen-privacy` in the list and profile).

## Design decisions and what was cut

- **No friend chat mode or `/fc`.** It would be one-sided (other friends don't see the replies), fight team chat on the
  same chat event, and be a side channel without mute or spy; `/msg` and team chat cover it.
- **No "appear offline".** The tab list and `/seen` show the player anyway; `friends-announce` replaces it honestly.
- **No kills, deaths or playtime on profiles.** They read 0 for offline players; the Stats button runs `/stats`.
- **No friends settings table.** `PlayerSettings` stays the one source of truth.
- **No visible deny cooldown.** A detectable re-poke timer; silent deny memory replaces it.
- **No inline dialogs in request lines, no rank on list rows, no Accept all, no friend leaderboards or activity feeds**
  (they invite alt farming and noise), **no `last_visible` column** ("seen" matches `/seen` exactly).
- **Not built** (they touch other packages): a core profile actions registry (it would also give the Stats and Pay
  screens opened from a profile a Back to it: `StatsViews` already takes a back handler, the pay form a cancel handler,
  but both are reached by command), a shared chat modes service, per-player hub tooltips, a vanish change event,
  `StatsRecorder.load` and a suggest-command dialog button type. Friends keeps its own name suggestion providers;
  core's `CommandSupport.onlinePlayer`/`knownPlayer` hide vanished staff as well now.

## Known limitations

- Vanishing or unvanishing mid-session produces no fake leave or join alert; lists and buttons follow `VanishStatus`
  live. A vanished player's "seen" time is their last join, the same as `/seen`.
- Profile buttons and "Deny and ignore" appear only when a command answers to the label (`/msg`, `/tpa`, `/team`,
  `/pay`, `/stats`, `/ignore`) and the viewer has SiftCore's node for it (`siftcore.command.msg`, ...,
  `siftcore.command.stats.others` on someone else's card). The label check looks for any command registered under
  that name, not only SiftCore's: a label yielded to another plugin in `commands.yml` keeps its button, which then
  runs that plugin's command, and a command turned off there loses its button only when nothing else registers the
  label.
- The settings dialog's page is built by the settings feature: after Save it shows the settings groups (Back from
  there returns to the friends list), not the friends list directly.
- Stats and Pay opened from a profile have no Back to it (see "Not built" above).
- `recentlyFriends` remembers at most 7 days. Rate buckets reset on restart; the daily cap does not.
