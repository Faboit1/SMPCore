# Staff tools (`staff`)

Moderation for SiftVanilla staff: vanish, freeze, mutes, bans, kicks, warnings and their history, staff chat,
player reports, inventory and ender chest inspection, alt and player lookups, broadcasts and clearing chat.
Package `feature/staff`, config `features/staff.yml`, text `lang/staff.yml`, tables `staff_punishments`,
`staff_reports`, `staff_vanish` and `staff_freeze` (migration V090; the range 90-94 is reserved for this feature).

The feature implements three `core.link` contracts for the other features and the core:

| Contract | Getter | Meaning |
|---|---|---|
| `MuteStatus` | `StaffFeature#mutes()` | The player's active mute (reason, end, staff name), or empty. Lock-free map lookup, safe on the async chat thread |
| `VanishStatus` | `StaffFeature#vanish()` | Whether a player is vanished. Lock-free set lookup, safe from any thread |
| `FreezeStatus` | `StaffFeature#freezes()` | Whether a player is frozen. Lock-free map lookup, safe from any thread. `FeatureCatalog` installs it in the shared teleports (`Teleports#freezes`) and the dialog router (`Dialogs#freezes`) |

All three are wired in `FeatureCatalog` (see "Integration" below): `MuteStatus` to chat (public chat and private
messages), teams (team chat) and friends (the Message button); `VanishStatus` to core's player-name arguments and
nearly every feature that shows who is online. The staff feature in turn takes the cosmetics contract
(`staff.cosmetics(...)`) for its fake join and leave lines.

Every staff action is written to the audit log (`services.audit()`, actions listed below). All state lives in
memory (thread-safe maps and sets, loaded at enable) and every change is queued to the database writer at the same
time, under the same lock, so the self-test can compare both.

## Commands and permissions

Every staff node defaults to `op` and none is granted to everyone. Player names accept anyone who has played
(offline players work for everything except kick, inventory inspection and `/whois` location).

| Command | Permission | What it does |
|---|---|---|
| `/vanish` (`/v`) | `siftcore.staff.vanish` | Toggles your vanish |
| `/vanish <player>` | `siftcore.staff.vanish.others` | Toggles another staff member's vanish (also offline) |
| `/freeze <player>` | `siftcore.staff.freeze` | Freezes or unfreezes a player (also offline: applies on join) |
| `/mute <player> [time] [reason]` | `siftcore.staff.mute` | Mutes; without a time the mute is permanent |
| `/unmute <player>` | `siftcore.staff.mute` | Lifts a mute |
| `/ban <player> [reason]` | `siftcore.staff.ban` | Bans permanently |
| `/tempban <player> <time> [reason]` | `siftcore.staff.tempban` | Bans for a while |
| `/unban <player>` (`/pardon`) | `siftcore.staff.unban` | Lifts a ban |
| `/kick <player> [reason]` | `siftcore.staff.kick` | Kicks an online player with the kick screen |
| `/warn <player> <reason>` | `siftcore.staff.warn` | Warns; an offline player sees it on their next join |
| `/history <player>` | `siftcore.staff.history` | Every ban, mute, kick and warning, newest first, paged |
| `/staffchat [message]` (`/sc`) | `siftcore.staff.chat` | Sends one staff chat message, or alone toggles staff chat mode |
| `/report [player] [reason]` | `siftcore.command.report` (everyone) | Reports a player; without a reason it opens the form |
| `/reports [id]` | `siftcore.staff.reports` | The open reports, or one report |
| `/invsee <player>` | `siftcore.staff.invsee` | Read-only view of an online player's inventory and armor |
| `/ecsee <player>` | `siftcore.staff.ecsee` | Read-only view of an online player's ender chest |
| `/alts <player>` | `siftcore.staff.alts` | Accounts whose last address matches the player's |
| `/whois <player>` | `siftcore.staff.whois` | Identity, location, ping, game mode, balance, alts and punishments |
| `/broadcast <message>` (`/bc`) | `siftcore.staff.broadcast` | One plain line to everyone with a soft sound |
| `/clearchat` | `siftcore.staff.clearchat` | Pushes chat off screen for everyone without the bypass |

| Other permission | Default | Meaning |
|---|---|---|
| `siftcore.staff.vanish.see` | op | See vanished staff (tab list, world, server list sample is still hidden) |
| `siftcore.staff.notify` | op | Be told about bans, mutes, kicks, warnings and freezes by other staff |
| `siftcore.staff.invsee.edit` / `siftcore.staff.ecsee.edit` | op | Take and delete items in the views |
| `siftcore.staff.clearchat.bypass` | op | Keep your chat when it is cleared |
| `siftcore.hierarchy.owner` | nobody (not even op) | The owner: acts on staff of any weight, and only the console and other holders can ban, mute, kick, warn, freeze or vanish them (see "Staff hierarchy") |
| `siftcore.bypass.cooldown` | op | No report cooldown (core node) |

The ranks (`default`, `prospector`, `baron`, `tycoon`) get none of these; give staff a separate group (weight 100 or
more).

Times are written like `30s`, `5m`, `12h`, `7d` or `1h30m`. A bare number is never a time, so `/mute Bob 5 alts`
is a permanent mute with the reason "5 alts". `perm`, `permanent` and `forever` mean permanent. The shortest time
is 1 second, the longest is `punishments.max-duration` (3650 days); reasons are up to 200 characters, colour codes
and control characters are removed and reasons are always shown literally.

A ban or mute replaces the player's current one of the same type (the old one is closed as lifted by the same
staff member). Staff can't punish, kick or freeze themselves, nor staff of the same or a higher rank (below).

### Staff hierarchy

Staff can't ban, tempban, mute, warn, kick, freeze or `/vanish <player>` a staff member whose staff weight is the
same as or higher than their own, and the take and clear buttons of `/invsee` and `/ecsee` stay off for them (the view
itself still opens). Lifting a ban or mute and unfreezing are never refused. Refused attempts tell the staff member
("You can't do that to <name>. Their staff rank is the same as or higher than yours.") and are audited as
`staff.hierarchy.refused` with the command in the details.

- **Staff weight:** the highest LuckPerms group weight the player has, including groups inherited through other groups,
  counting only groups that weigh at least `hierarchy.min-weight` (100). Staff groups use weight 100 or more; the ranks
  (prospector 10, baron 20, tycoon 30) are not staff. Players who aren't staff (weight 0) can always be punished.
- **The owner:** `siftcore.hierarchy.owner` (default `false`, so ops don't get it: give it to the owner explicitly,
  `lp user <owner> permission set siftcore.hierarchy.owner true`). A holder acts on anyone, and only the console and
  other holders act on a holder. **Any wildcard that covers the node grants owner status**: LuckPerms' `*` and
  `siftcore.*` do. Never give those to a staff group; give staff the exact `siftcore.staff.<tool>` nodes (or
  `siftcore.staff.*`, which the owner node is deliberately outside of). Check a staff member with
  `lp user <name> permission check siftcore.hierarchy.owner`.
- **The console** is never refused, and neither is acting on yourself (`/vanish <own name>`).
- **Offline targets** are looked up in LuckPerms' storage first (`UserManager#loadUser`); the command finishes on the
  staff member's thread once the rank is known. If the lookup fails, nothing is done and the staff member is told to
  try again. Online players are answered from LuckPerms' loaded user at once.
- **Without LuckPerms** only the owner node of online players is known, so only the owner is protected, and only
  while online.
- **It needs setting up.** Until staff groups with a weight of at least `hierarchy.min-weight` exist, every staff
  member weighs 0 and staff can punish each other; until the owner has the owner node, the owner can be punished like
  anyone. The steps are in [server-setup.md](../server-setup.md) ("Staff").
- `hierarchy.enabled: false` turns the whole check off.

`StaffHierarchy` holds the rules (`decide`, pure and unit tested) and the guard the commands run through;
`LuckPermsStaffRanks` reads the weights (`StaffRanks.PERMISSIONS` without LuckPerms).

### Vanilla commands

Paper registers plugin Brigadier commands over vanilla ones with the same label (`PaperCommands` replaces a vanilla
node of the same name; verified at runtime on Canvas 962 from the console): `/ban`, `/kick` and `/pardon` run this
feature. The vanilla commands stay reachable as `/minecraft:ban`, `/minecraft:kick` and `/minecraft:pardon` (also
verified), and `/ban-ip`, `/pardon-ip` and `/banlist` are untouched vanilla commands. They use the vanilla ban list,
which this feature does not read or write; keep them for the owner only.

IP bans are out of scope: bans here are per account. `/alts` shows accounts sharing the last known address, for
staff to ban them one by one. The vanilla `/ban-ip` still works and is enforced by the server itself.

## Bans, kicks and warnings

Bans are enforced in `AsyncPlayerPreLoginEvent` (priority LOW, in-memory lookup, never touches the database on the
login thread) with a multi-line screen:

```
You are banned from SiftVanilla.

Reason: <reason>
Time left: <time>            (or: This ban is permanent.)

Appeal on our Discord: discord.gg/siftvanilla     (bans.appeal)
```

Staff with **Confirm bans** on (`staff-confirm-bans`) get a dialog before a `/ban` or `/tempban` of theirs goes
through: `Ban <name>?` with the player, the length (`permanent` or the time) and the reason, then **Ban** or
**Cancel** ("Didn't ban <name>."). When they press Ban, the permission of the command they typed
(`siftcore.staff.ban`, or `siftcore.staff.tempban` for a ban with a length) is checked again, so staff who lost it
while the dialog was open are refused ("You can't do that."), and then the staff hierarchy. The console and automatic
bans (freeze logout) never ask.

A player banned between the login check and the join is removed in `PlayerJoinEvent`. An online player who is
banned is removed at once with the same screen. Kicks and ban removals run on the player's own thread: from any
other thread Folia only drops the connection, without the kick event and with "disconnected" as the quit reason.

Temporary bans and mutes end on their own: a check every 5 seconds drops ended ones from memory (lookups already
ignore them the moment they end), and a player whose mute ended is told.

Warnings are shown at once to online players and on the next join to offline ones (`staff_punishments.notified`).

`/history` opens a dialog (6 entries per page, `history.page-size`) with each entry's type, age, staff, reason,
length and state: active (with time left), expired, or lifted by whom. From the console it prints every entry.

## Mutes

A muted player's public chat is cancelled (`AsyncChatEvent`, LOW) and they see `staff.muted` ("You are muted for
<time> more. Reason: <reason>") or `staff.muted-permanent` on the action bar. `mutes.blocked-commands` (`/me`,
`/say`, `/msg`, `/tell`, `/w`, `/r`, `/teammsg`, ...) are blocked the same way.

For the chat feature and private messages: call `mutes().mute(uuid)` and, when present, tell the player with the
same text. Declare the keys in your feature exactly as this feature does, so the shared text validates:

```java
MessageKey.error("staff.muted", "reason", "time");
MessageKey.error("staff.muted-permanent", "reason");
```

`<time>` is the remaining time, `<reason>` the reason (or "No reason given").

## Vanish

A vanished player is hidden with `Player#hidePlayer(Plugin, Player)` from every online player without
`siftcore.staff.vanish.see`, and shown again with `showPlayer` when they unvanish. Vanish is stored, so it survives
relogs and restarts. While vanished:

- they are re-hidden from players who join later, before the join completes (no flash in the tab list);
- every 3 seconds (`vanish.reminder-interval`) visibility is re-applied for permission changes and they see a quiet
  "You are vanished" on the action bar (unless they turned **Vanish reminder** off, `vanish-reminder`);
- they don't pick up items or arrows, mobs don't target them, they don't press pressure plates or tripwires or
  trample farmland (physical interactions), and sculk sensors don't hear them;
- the server list ping (`PaperServerListPingEvent`) leaves them out of the player sample and the online count.

A vanished player who joins without `siftcore.staff.vanish` (no longer staff) is made visible and told so.

Staff settings for vanish:

- **See vanished staff** (`vanish-see-vanished`, `siftcore.staff.vanish.see`, on): turned off, vanished staff are hidden
  from that viewer like from a player. Applied at once: the change hook asks every online vanished player's own thread
  to settle who sees them (`reconcile`), and the join path and the self-test use the same rule
  (`StaffPreferences.shouldSee`: the permission and the setting).
- **Join vanished** (`vanish-on-join`, `siftcore.staff.vanish`, off): a staff member who is not vanished becomes
  vanished in `PlayerJoinEvent` (LOWEST), stored and audited (`staff.vanish.on`, "joined vanished"), before the
  inline hide described below; the extras feature then leaves the join line out like for any vanished player. They are
  told in chat ("You joined vanished.").
- **Fake join/leave on vanish** (`vanish-fake-messages`, `siftcore.staff.vanish`, off): `/vanish` (or staff vanishing
  them) shows the line a real leave would show, and reappearing the line a real join would show: their rank line
  (cosmetics, `siftcore.join.message`, with its cooldown) when they have one, otherwise the plain extras line
  (`extras.join` / `extras.quit`, the same lang entries, so no import of the extras feature) when
  `features/extras.yml` shows plain lines (`messages.join` / `messages.quit`, read from the file when it changes),
  otherwise nothing and the staff member is told there is no line to imitate (staff who vanished someone else with
  `/vanish <player>` are told it is missing for that player). Players whose `join-leave-messages` setting (extras,
  read by id) is not `all` don't get them. Offered only while there is a line to imitate: the plain lines are on, or
  the cosmetics link is bound and `features/cosmetics.yml` has `enabled` and `join-messages.enabled` on (read from the
  file when it changes, like extras.yml). The link is handed over by the composition root
  (`staff.cosmetics(cosmetics.cosmetics())` in `FeatureCatalog`, once cosmetics is built); while the cosmetics feature
  is off only the plain lines are imitated and offered. The offer is server-wide: with only rank lines on, staff without
  `siftcore.join.message` still see the switch, and their `/vanish` says there is no line. Friend join alerts and the
  tab list are not faked.

Silent containers (opening a chest without the animation and sound) are not possible without NMS, so vanished
staff open containers normally; other players nearby see and hear the lid.

### Which thread hide and show run on (verified)

Checked in the decompiled Canvas 962 server (`CraftPlayer`, `ChunkMap.TrackedEntity`, `PlayerList`):

- `viewer.hidePlayer(plugin, v)` (`hideEntity0`) records the hide in the viewer's `invertedVisibilityEntities`, a
  `ConcurrentHashMap`, then `untrackAndHideEntity` → `unregisterEntity(v)`: `v.moonrise$getTrackedEntity()
  .removePlayer(viewer)` removes the viewer from v's tracker (`seenBy`, a plain set owned by the region that ticks
  v; its thread check only demands some tick thread), and sends `ClientboundPlayerInfoRemovePacket` when both
  players were already listed (`sentListPacket`). It fires `PlayerHideEntityEvent` synchronously.
- `viewer.showPlayer(plugin, v)` (`trackAndShowEntity`) sends the player info entry and calls
  `entry.updatePlayer(viewer)` on v's tracker, which itself requires the thread that owns the viewer's region.

So every hide and show of a vanished player v runs on v's own thread (`scheduler.entity(v, ...)`, or directly when
the current thread owns v). `VanishService#reconcile` does all of them for v there.

The one exception is a join. `PlayerList#placeNewPlayer` adds the player to the world with tracking suppressed,
fires `PlayerJoinEvent`, then sends the tab-list entries (each filtered by `canSee`) and sets `sentListPacket`, and
only then starts tracking. In `PlayerJoinEvent` (LOWEST, on the joining player's thread):

- a vanished player joining hides themself from every viewer: that is their own thread, they have no tracker entry
  yet and are not listed, so only the viewers' visibility maps change and the following tab list and tracking skip
  them;
- a player without the see permission joining hides every vanished player from themself. When the vanished player
  is in another region this is not their thread, but the call only writes the joiner's own visibility map, does one
  read-only lookup in the vanished player's tracker (which can't contain the joiner: trackers only add players their
  own region owns, and the joiner isn't tracked yet) and sends nothing because the joiner isn't listed yet.
  Scheduling it on the other region instead would apply only after the tab list was sent, which is the flash this
  avoids. If the lookup ever fails, the hide is redone on the owner thread.

`setVisibleByDefault(false)` was rejected because it is saved in the player's data (`Bukkit.visibleByDefault`).

## Freeze

`/freeze <player>` toggles. A frozen player:

- can't change position (looking around is allowed): moves are reset to the old position with the new rotation;
  pearls, chorus fruit, portals and spectator teleports are cancelled, and so are plugin teleports that would take
  them away (another world or more than a block); staff teleports (`/tp`, `/spawn <player>`, `/rtp <player>`, all
  cause `COMMAND`) still work. On Canvas every `teleportAsync`, pearl, chorus fruit and end gateway fires Canvas'
  `EntityTeleportAsyncEvent` instead of `PlayerTeleportEvent` (decompiled `Entity#teleportAsync`), so the rule
  (`FreezeRules#teleportRefused`) is applied to both events (the Canvas one registered by name, like the combat
  feature's pearl guard). A plugin teleport to where the player stands is allowed: Canvas applies a changed or
  cancelled `PlayerMoveEvent` with one (decompiled `ServerGamePacketListenerImpl#handleMovePlayer`), and that is how
  the freeze resets their position and lets them look around;
- can't go through a portal, also when staff `/tp` them into one or they were frozen standing in one. Canvas carries
  players through nether and end portals with `Entity#portalToAsync`, which fires neither teleport event nor
  `PlayerPortalEvent` (that one only fires from `Portal#getPortalDestination`, which Canvas never calls), only
  Canvas' `EntityPortalAsyncEvent`. So three events are cancelled for frozen players: `EntityPortalEnterEvent` (fired
  every tick an entity stands in a nether portal, end portal, the end's exit portal or an end gateway, before the
  trip can start), Canvas' `EntityPortalAsyncEvent` (registered by name) and `PlayerPortalEvent` (Paper and Folia;
  it has its own handler list, so the `PlayerTeleportEvent` handler never sees it);
- can't teleport through SiftCore at all: the shared teleports (homes, spawn, RTP, TPA, team home, AFK zone) refuse
  frozen players when the teleport starts, when the warmup ends and right before the move ("You can't teleport while
  frozen."). The warmup alone would not stop them, since a frozen player never changes block;
- can only use `freeze.allowed-commands` (`/msg`, `/r`, `/reply`, `/tell`, `/w`, `/whisper`);
- can't use menus: the dialog router refuses every click of a frozen player (the pause-menu hub, `/menu`, dialogs
  opened from chat such as a teleport request or a team invite, Bedrock forms) before any route or handler runs,
  says "You can't do that while frozen." and closes the dialog. Only a plain close button still works. A refused click
  doesn't use the dialog up, so a dialog from chat still works once they are unfrozen. So money and items can't be
  moved through pay, auction, sell, orders or the shop while staff check them, and `PlayerPayEvent` is cancelled for a
  frozen payer as well;
- can't open containers or menus (`InventoryOpenEvent` is cancelled), and whatever was open (a dialog, a menu, a
  chest) is closed the moment they are frozen;
- can't break, place, interact with blocks or entities, attack, drop items or mount (a cancelled click on a
  leaderboard runs nothing either);
- isn't hurt by players or their projectiles and doesn't hurt anyone;
- sees "Frozen by staff. Do not log out." on the action bar every 2 seconds (`freeze.reminder-interval`);
- is allowed to fly while frozen (otherwise the server kicks them for "flying" when they are pinned in the air).
  That flight is marked in the player's saved data and taken back when they are unfrozen, quit, or join unfrozen,
  so it is never kept after a crash.

The freeze is stored and re-applied on join. Logging out while frozen (any quit that isn't a kick) tells everyone
with `siftcore.staff.freeze` (where their **Frozen player logout alerts** setting says: chat, above the hotbar or off),
writes `staff.freeze.logout` to the audit log, and with `freeze.ban-on-logout.enabled` (default off) bans the player
for `duration` with `reason`, in the name of the staff member who froze them.

## Staff chat

`/sc <message>` sends `Staff <name>: <message>` (gray/white, the message literal) to everyone with
`siftcore.staff.chat` and the console. `/sc` alone toggles staff chat mode: the player's normal chat goes to staff
chat instead (`AsyncChatEvent`, LOW, `ignoreCancelled = true`, cancelled). The mode ends on quit or when the
permission is gone. Staff who turned **Show staff chat** off (`staff-chat`) don't get other staff's lines; their own
always echo, and switching to staff chat mode reminds them that others' lines are hidden.

## Reports

Players report with `/report <player> <reason>`, `/report <player>` (form with the name filled in), `/report`
(empty form) or the main menu entry "Report a player" (hub entry `report`, order 95, everyone). Rules, checked in
this order: not yourself, reason 3 to 100 characters (`reports.reason-min-length` / `reason-max-length`), not the
same player twice while the first report is open, at most 5 open reports per reporter, then one report per 60
seconds (`reports.cooldown`). The form keeps what was typed and shows the problem.

Online staff with `siftcore.staff.reports` get a chat line `Report #<id>: <reporter> reported <target>: <reason>`;
clicking it runs `/reports <id>`. Their **Report alerts** setting (`staff-report-alerts`) can move it above the hotbar
(not clickable there) or turn it off; `/reports` always lists open reports. `/reports` is a paged dialog of open reports (6 per page) with a button per
report; a report shows the player, whether they are online, who reported, when, and the reason, with:

- **Teleport to <name>** (only when online): teleports at once (`teleportAsync`, no warmup) to where the player is,
  read on the player's own thread;
- **Mark handled**: closes it and thanks the reporter if online;
- **Dismiss**: closes it.

A report closed by someone else in the meantime says so. From the console `/reports` prints the open reports.

## Inventory inspection

`/invsee` and `/ecsee` take a snapshot of the target's inventory (storage, hotbar, armor, off hand) or ender chest
on the target's thread and open a read-only chest view on the staff member's thread. Nothing can be moved by hand.

| Inventory view (6 rows) | Ender chest view (4 rows) |
|---|---|
| slots 0-26 storage, 27-35 hotbar, 36-39 helmet, chestplate, leggings, boots, 40 off hand | slots 0-26 the ender chest |
| 45 player info, 48 ender chest view, 49 refresh, 50 clear | 27 player info, 30 inventory view, 31 refresh, 32 clear |

With the edit permission, clicking an item takes it: on the target's thread the live slot is compared with the
snapshot and removed only if it is exactly the same item (otherwise "That slot changed, so nothing was taken" and
the view refreshes); then the item is given on the staff member's thread, and whatever doesn't fit (or everything,
if the staff member left meanwhile) goes to their claim box. Clear deletes everything after a confirmation dialog.
Every open, take and clear is audited with the items.

Offline players are not supported (their data isn't loaded, and editing saved player files safely is out of scope).

## Lookups

`/alts <player>` lists up to 50 accounts whose last address hash matches the player's (from the player directory;
only the most recent address of each account is known), each with last seen and banned/muted marks.

`/whois <player>` opens a dialog (console: chat lines): UUID, first join, last seen; if online the world and
position, ping and game mode (read on the player's thread); money and shards from the ledger; the number of
accounts on the same address; and what is in force now (banned, muted with time left, frozen, vanished). Buttons
open the punishment history and the inventory or ender chest views.

## Announcements

`/broadcast <message>` sends the message as one plain line (tags shown literally) with the soft notify sound.
`/clearchat` sends 100 blank lines (`clear-chat.lines`) to everyone without the bypass, then "Chat was cleared by
staff." to everyone; the staff member is told how many players were cleared.

## Player settings (Staff group)

Only staff see them: each needs the permission of the tool it changes, and none is exposed as a placeholder.
Notifications use the shared alert styles (chat, above the hotbar, off) through one per-recipient check in
`StaffNotices` (`StaffPreferences.noticeStyle`: the permission, not the staff member who acted, and their choice); the
console always gets every notice.

| Order | Id | Kind | Default | Permission | What it does |
|---|---|---|---|---|---|
| 2 | `staff-chat` | toggle | on | `siftcore.staff.chat` | show staff chat from other staff |
| 3 | `staff-punish-alerts` | choice chat/actionbar/off | chat | `siftcore.staff.notify` | bans, unbans, mutes, unmutes, kicks, warnings, freezes and unfreezes by other staff |
| 4 | `staff-report-alerts` | choice chat/actionbar/off | chat | `siftcore.staff.reports` | new player reports |
| 6 | `vanish-on-join` | toggle | off | `siftcore.staff.vanish` | always join vanished |
| 7 | `vanish-reminder` | toggle | on | `siftcore.staff.vanish` | the "You are vanished" reminder |
| 8 | `vanish-see-vanished` | toggle | on | `siftcore.staff.vanish.see` | see other vanished staff (applied at once) |
| 9 | `staff-freeze-alerts` | choice chat/actionbar/off | chat | `siftcore.staff.freeze` | a frozen player logging out |
| 11 | `staff-confirm-bans` | toggle | off | `siftcore.staff.ban` | confirm player, length and reason before a ban |
| 12 | `vanish-fake-messages` | toggle | off | `siftcore.staff.vanish` | fake leave and join lines on vanish |

The other places in the group belong to other features: social spy (1, chat), team spy (5, teams), staff combat alerts
(10, combat) and config problem alerts (13, admin: when an admin with `siftcore.admin.reload` joins, a chat line with
the number of problems the last startup or `/sift reload` found, listed on hover; clicking fills in `/sift reload`).
A reload hands its problems to the admin feature, and the startup's come from the list SiftCore keeps
(`CoreControl.startupProblems()`). The admin feature also reads SiftCore's own log: `Config problem: ...` lines, and the
`features/settings.yml: ...` override warnings the settings feature writes a second after startup and on each reload
(formats pinned by `ConfigProblemLogTest` against their sources); the same problem from two sources counts once.

## Audit log actions

`staff.ban`, `staff.mute`, `staff.kick`, `staff.warn` (details: id, length, reason, replaced id), `staff.unban`,
`staff.unmute`, `staff.history`, `staff.vanish.on|off`, `staff.freeze.on|off`, `staff.freeze.logout` (actor
`system`), `staff.report.handled|dismissed|teleport`, `staff.invsee.open|take|clear`, `staff.ecsee.open|take|clear`,
`staff.alts`, `staff.whois`, `staff.broadcast`, `staff.clearchat`, `staff.hierarchy.refused` (details: the command).
The target is the player's UUID.

## Placeholders

| Placeholder | Value |
|---|---|
| `staff_visible_online` | Online players, not counting vanished staff |
| `staff_vanished` | `true` when the viewing player is vanished |
| `staff_reports_open` | Number of open reports |

## Config (`features/staff.yml`)

| Key | Default | Meaning |
|---|---|---|
| `vanish.reminder-interval` | `3s` | Vanish action bar reminder and visibility re-check |
| `freeze.reminder-interval` | `2s` | Freeze action bar reminder |
| `freeze.allowed-commands` | msg, r, reply, tell, w, whisper | Commands a frozen player may use |
| `freeze.ban-on-logout.enabled` | `false` | Ban players who log out while frozen |
| `freeze.ban-on-logout.duration` | `permanent` | Length of that ban |
| `freeze.ban-on-logout.reason` | Logged out while frozen | Its reason |
| `mutes.blocked-commands` | me, say, tell, msg, w, whisper, r, reply, teammsg, tm | Commands a muted player can't use |
| `bans.appeal` | Appeal on our Discord: ... | Last line of the ban screen |
| `punishments.max-duration` | `3650d` | Longest temporary ban or mute |
| `hierarchy.enabled` | `true` | Staff can't act on staff of the same or a higher weight |
| `hierarchy.min-weight` | `100` | The lightest LuckPerms group that counts as a staff group |
| `reports.cooldown` | `60s` | Time between two reports |
| `reports.reason-min-length` / `reason-max-length` | `3` / `100` | Reason length |
| `reports.max-open-per-player` | `5` | Open reports per reporter |
| `reports.page-size` / `history.page-size` | `6` / `6` | Dialog page sizes |
| `clear-chat.lines` | `100` | Blank lines `/clearchat` sends |

## Storage

| Table | Holds |
|---|---|
| `staff_punishments` | Every ban, mute, kick and warning: target, staff, reason, created, expires (`Long.MAX_VALUE` permanent, 0 for kicks and warnings), revoked time and by whom, notified |
| `staff_reports` | Reports with state (open, handled, dismissed), who closed them and when |
| `staff_vanish` | Vanished players |
| `staff_freeze` | Frozen players and who froze them |

Active bans and mutes, vanished and frozen players and open reports are loaded at enable. History is read on
demand through the writer queue (so it includes writes still queued).

## Self-test

`/sift selftest` checks that bans and mutes in force, vanished and frozen players and open reports in memory match
the database (counted at the same moment, after every queued write), that every online vanished player is hidden
from every online player without the see permission, and that times and reasons are read as documented.

## Integration

`FeatureCatalog` constructs it right after `hub` and before every feature that consumes its contracts:

| Contract | Handed to |
|---|---|
| `FreezeStatus` (`staff.freezes()`) | core: the shared teleports (`Teleports#freezes`) and the dialog router (`Dialogs#freezes`), so a frozen player can't teleport or use any menu |
| `VanishStatus` (`staff.vanish()`) | core's player-name arguments (`CommandService#vanish`: suggestions and lookups never find vanished staff for players who can't see them), AFK, stats, teams, chat, friends, spawners, crates, orders, combat, TPA, extras (join and leave lines, `/seen`) and the scoreboard |
| `MuteStatus` (`staff.mutes()`) | chat (public chat and private messages), teams (team chat) and friends (no Message button) |
| `Cosmetics` (taken, `staff.cosmetics(cosmetics.cosmetics())`) | after cosmetics is built: fake join and leave lines copy the rank lines and nicknames |

## Tests

- Unit (`src/test/java/.../feature/staff`): durations and reasons (`DurationInputTest`), punishment states and
  expiry (`PunishmentTest`, `PunishmentBookTest`), report rules and cooldown (`ReportRulesTest`), the freeze
  predicates (`FreezeRulesTest`), the inspection layout and the take re-check (`InspectTest`), the V090 tables and
  the punishment service on a real SQLite database including restarts (`StaffStorageTest`), and every staff message
  against the real lang files (`StaffLangTest`), the staff settings' group, order, permissions and deciders (notices,
  who sees vanished staff, staff chat echo, fake line availability from extras.yml and cosmetics.yml, the viewer
  filter, the Ban button's permission re-check and the missing-line notices: `StaffPreferencesTest`), and the config
  problem log behind the admin alert, including the log formats of the startup and the settings feature
  (`ConfigProblemLogTest`, in `feature/admin`).
- End to end (`tools/e2e/.../StaffScenarios.java`): `staff-mute`, `staff-ban`, `staff-freeze`, `staff-vanish`,
  `staff-report`, `staff-invsee`, `staff-chat`, `staff-lookups`; the settings: `staff-alert-settings` (punishment
  alerts and staff chat in the dialog, report and freeze alerts by API), `staff-vanish-settings` (see vanished staff
  in the dialog, reminder, join vanished, ban confirmation including a permission taken away while it is open),
  `staff-fake-lines` (not offered without a line to imitate, the missing-line notices for self and `/vanish
  <player>`, offered and saved in the dialog with plain lines on) and `admin-config-alerts` (including a problem
  logged the way the startup logs it).

## Known limitations

- IP bans, offline inventory inspection and silent containers are out of scope (see above).
- `/alts` only knows each account's most recent address.
- The vanished player's tab entry is removed for other players, but other plugins that list players themselves
  must consult `VanishStatus`.
- A staff member in staff chat mode who is muted can still talk in staff chat.
