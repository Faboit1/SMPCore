# Teams (`teams`)

Players start a team for money, invite friends, give them roles, share a home and a private chat, protect each
other from friendly fire and compete on team leaderboards. Package `feature/teams`, config `features/teams.yml`, text
`lang/teams.yml`, tables `teams` and `team_members` (migration V005) plus the `teams.size_limit` column (V035).

The feature implements `core.link.TeamLookup`; `TeamsFeature#lookup()` returns it (team of a player, team name,
members, friendly fire, same-team checks; thread-safe, lock-free). It consumes these contracts (all wired in
`FeatureCatalog`):

| Contract | Used for |
|---|---|
| `StatsRecorder` (stats) | Team kills and deaths (sum of the members) in `/team info` and the kills leaderboard |
| `MuteStatus` (staff) | A muted player can't use team chat (`/tc` or chat mode) |
| `VanishStatus` (staff) | A vanished member shows as offline in member lists, online counts and placeholders |
| `IgnoreLookup` (chat, installed with `TeamsFeature#ignores` once chat is built) | A player who ignores the inviter gets no invite; the inviter hears "You can't invite <name>." (players with `siftcore.chat.unignorable` still invite) |
| `SpawnArea` (spawn) | No team home inside the protected spawn area, as with `/sethome`; a team home already stored there can't be used |
| `Relations` (`services.relations()`: friends, teams and ignore lists, bound once every feature is built) | Who may send team invites (`team-invites`), who sees a member's last-seen time (`seen-privacy`), and favourite friends who already got the friends login alert aren't told twice |
| Homes' disabled worlds (installed with `TeamsFeature#homeWorlds` once homes are built) | `homes.yml`'s `disabled-worlds` apply to team homes too, on top of `home.disabled-worlds` here |

## Commands and permissions

`/team` has the alias `/t`; `/teamchat` has the alias `/tc`. Player-only subcommands are hidden from the console.

| Command | Who | What it does |
|---|---|---|
| `/team` | everyone | The team dialog (see below). From the console: the help |
| `/team create [name]` | `siftcore.teams.create` | Starts a team. Without a name: the name form. Free by default (the team dialog opens at once); while `create.cost` is above 0 it asks to confirm the cost first |
| `/team invite [player]` | owner, admins | Invites an online player. Without a name: the invite form |
| `/team join <team>` / `/team decline <team>` | invited players | Answers an invite (the chat message's dialog does the same) |
| `/team leave` | members, admins | Leaves the team (the owner must hand it over or disband first) |
| `/team kick <player>` | owner, admins | Removes a member; only the owner removes admins |
| `/team promote <player>` / `/team demote <player>` | owner | Member to admin and back |
| `/team transfer <player>` | owner | Hands the team to a member after a confirmation; the old owner becomes an admin |
| `/team disband` | owner | Disbands the team after a confirmation. Nothing is refunded |
| `/team sethome` | owner, admins | Sets the team home where the player stands, where `/sethome` would allow a home: not inside the protected spawn area, not in a world listed in `home.disabled-worlds` here or in `homes.yml`'s `disabled-worlds` |
| `/team home` | members | Teleports to the team home after the warmup. Refused when the home is in a disabled world (either list) or inside the protected spawn area (a home set there before these rules, or before the spawn area grew): "The team home is at spawn, where team homes aren't allowed. Set a new one." |
| `/team friendlyfire [on\|off]` | owner, admins | Toggles (or sets) friendly fire |
| `/team chat` | members | Turns team chat mode on or off |
| `/team info [team]` | everyone, console | Owner, members, online count, kills, deaths, total money, age, leaderboard places, friendly fire; the home only for members and staff |
| `/team list [page]` | everyone, console | Every team by size: the All teams dialog for players (the biggest `list-size`, no pages), pages of `page-size` in the console |
| `/team top [kills\|money]` | everyone, console | The team leaderboards |
| `/team spy` | `siftcore.teams.spy` | Turns seeing every team's chat on or off (the `team-spy` setting, also in `/settings` under Staff; refused with the reason while the server locks or hides it) |
| `/team admin ...` | `siftcore.admin.teams`, console | Staff tools, see below |
| `/tc <message>` | `siftcore.command.teamchat` | Sends one message to the team; `/tc` alone toggles team chat mode |

Staff tools (every action is written to the audit log as `teams.<action>`):

| Command | What it does |
|---|---|
| `/team admin add <team> <player>` | Puts a player in a team without an invite and regardless of the member limit |
| `/team admin kick <player>` | Removes a player from their team (not its owner) |
| `/team admin transfer <team> <player>` | Makes a member the owner; the old owner becomes an admin |
| `/team admin rename <team> <name>` | Renames a team (same name rules; case-only changes allowed) |
| `/team admin delhome <team>` | Removes a team's home |
| `/team admin disband <team>` | Disbands any team |

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.team` | everyone | Use `/team` |
| `siftcore.command.teamchat` | everyone | Use `/tc` |
| `siftcore.teams.create` | everyone | Start teams |
| `siftcore.teams.spy` | op | See the chat of every team (toggle with `/team spy` or the settings dialog) |
| `siftcore.admin.teams` | op | `/team admin` |
| `siftcore.teams.size.<n>` | none | Member limit of teams this player owns (the highest number wins) |
| `siftcore.teams.size.unlimited` | nobody | Teams this player owns have no member limit |
| `siftcore.teleport.bypass-warmup` | op | `/team home` without the warmup (core node) |
| `siftcore.bypass.cooldown` | op | No invite cooldown (core node) |

Team size is the same for everyone (5 from the config): a bigger PvP team is a competitive advantage, so no rank gets
`siftcore.teams.size.<n>` ([monetization](../monetization.md)). The node exists for staff or events.

### Roles

| | member | admin | owner |
|---|---|---|---|
| Team chat, team home, info, leave | yes | yes | (can't leave) |
| Invite, remove members, set home, friendly fire | | yes | yes |
| Remove admins, promote, demote, transfer, disband | | | yes |

Every rule is checked again under the economy lock right before anything changes, so a dialog showing old state, a
second click or a racing command can never bypass it.

## The team dialog

`/team`, the main menu's **Team** entry (hub id `teams`, order 50) and the `teams` pause-menu entry open it. Like every
dialog (the dialog style, `docs/development.md`) it shows buttons, not paragraphs: what a button does is in its tooltip,
the body holds at most a short status, nothing is paged (the dialogs scroll), values are coloured.

- **Without a team:** one line ("You're not in a team."), then **Start a team** (the name form; its Continue tooltip
  gives the name rules, and while starting a team costs money, the cost; then a confirmation with the cost), an
  **Invite from X** button per open invite (tooltip: who invited and how long is left), **All teams** and **Top teams**.
- **In a team:** two status lines (the owner; members of the limit and how many are online), then a grid of buttons:
  **Team home** (tooltip: where it is and the warmup), the switches **Team chat: ON/OFF** and **Friendly fire: ON/OFF**
  (ON green, OFF red; a click flips it and the dialog shows again, without a message for the one who clicked: the team
  is still told of friendly fire; members see Friendly fire greyed and get the refusal in red), **Members: 3/5**, for
  the owner and admins **Invite a player** (form) and **Set home here**, **Team stats**, **Top teams**, **All teams**,
  **Settings** (the Friends & teams settings, Back returns here) and **Leave the team** or, for the owner, **Disband the
  team** (both ask first).
- **Members:** one button per member, owner first: "Alex: owner, online" (online green), "Bob: member, seen 2h ago",
  or "Bob: member, offline" for a member whose last-seen time is kept from the viewer (see below). Clicking a member
  opens their dialog: role, online or last seen, how long in the team, and what the viewer's role allows: **Make
  admin**, **Make member**, **Remove from the team** (asks first) and **Hand over the team** (asks first). These
  replace the member pickers of earlier versions; the commands stay.
- **Team stats** (also `/team info <team>`): the stats lines and a **Members: N** button to that team's members.
- **All teams:** a button per team, biggest first ("Alpha: 4 members", tooltip: online now), the biggest `list-size`
  (100) with one line naming the cap when there are more. **Top teams:** the board, and a **Ranked by: Kills** choice
  that moves to Money and back.

After an action the dialog it came from is shown again with fresh state; a refused action shows the reason in red.

### Last seen in member lists

The members dialogs (from `/team` and from `/team info <team>`, also opened from **All teams** and **Team stats**) follow each
member's shared **Who sees when I was last online** setting (`seen-privacy`: everyone, friends, nobody), the same rule
as the friends list and `/seen`: being in the same team does not count, staff with `siftcore.staff.whois` and the
member themselves always see it. A member whose time is kept from the viewer reads "Alex: member, offline"
(`teams.members.offline-hidden`). Online members are answered from memory; the offline members' rows are read in
one query (in the database writer's order) before the dialog is built, so the dialog that was clicked stays on screen
for that moment (`TeamSeen`).

### Invites

An invite lasts 2 minutes (configurable). The invitee gets a chat message; clicking it opens a dialog with **Join**
and **Decline**. That dialog is bound to the invitee (another player can't use its buttons), works once, and checks
the invite again when clicked (expired, team gone, team full, already in a team). One open invite per team and
player, at most 10 open invites per team, and a 3 second cooldown between invites. Pending invites also show in the
team dialog, and `/team join <team>` works too. Invites are kept in memory only. The invite dialog's Join and Decline
say in their tooltips what they do.

Who may invite a player is their **Team invites from** setting (everyone, friends, nobody). A refused invite reads
"Cara isn't taking team invites from you." on the inviter's action bar (inside the dialog for the invite form), and
nothing reaches the player. A player who ignores the inviter is refused with the same words, so the inviter can't tell
an ignore from the setting. `/team invite` suggests only online players who take invites from the sender (never
vanished staff), and a friend's profile shows **Invite to team** only when the friend takes invites from the viewer.

## Team news

Team news (a member joined, left or was removed, promotions, a new owner, the home, friendly fire, a rename) goes to
every online member in the style they picked (**Team news**: chat, above the hotbar, or off). The member who made the
change always gets the line, since it is the only confirmation of their `/team kick`, `promote`, `demote`,
`transfer`, `sethome` or `friendlyfire`: in the style they picked, or in chat when they picked off. A new owner (by
`/team transfer` or `/team admin transfer`) is always told the same way. Disbanding (by the owner or staff) and being
removed always show in chat, whatever the setting.

## Teammate login alerts

A teammate's login is a chat line, "Alex from your team is online.", and for players who pick logins and logouts
also "Alex from your team went offline." (**Teammate login alerts**: logins and logouts, logins only (default),
off). The login is looked at `member-alerts.join-delay` after the join (so a vanish applied on join counts); it is not
told for a vanished member, a relog within `member-alerts.relog-grace`, or within `member-alerts.startup-quiet` of the
start. A logout is told after `member-alerts.leave-delay`, unless the member came back. A teammate who ignores the
member hears nothing, and a friend who already gets the friends login alert ("all friends", or "favourites only"
and the member is one of their favourites, and the member tells friends) or the friends leave alert isn't told twice
(the favourite comes from `FriendLookup.favourite`).

## Team chat

`/tc <message>` sends one message; team chat mode (`/team chat`, `/tc` alone, or the dialog button) sends everything
the player types to the team instead of public chat. The line reads `Team <name>: <message>` in gray with the name in
white; the message is inserted literally (tags stay text). It reaches online members, staff with
`siftcore.teams.spy` who have spying on (`Team <team>, <name>: <message>`), and the console (configurable).

Team chat mode listens to `AsyncChatEvent` at low priority: it cancels the public message and clears its viewers
before formatting, broadcast or relay plugins handle it, and skips events an earlier listener already cancelled
(anti-spam, mutes). Membership is read from immutable snapshots, so the async chat thread never waits. Leaving the
team, being removed, a disband or quitting turns chat mode off.

Players who turn **Remember team chat mode** on get it back after a relog: the mode is remembered with the
membership it was on in (the free per-player value `team-chat-mode`, not a setting: the team's id and when the player
joined it), and at login, while the player is still in that same membership, it comes back with the chat line "Team
chat is still on. Your messages only reach your team; /team chat turns it off." The value is kept as it really is: it
is written when the mode changes and when the player leaves (on or off at that moment), and a login that does not
bring it back clears it, so a mode that was off for a whole session never returns when the setting is turned on
later. Leaving the team clears it; a player removed while offline keeps a value of a membership they no longer have,
which never matches (also not after being invited back into the same team) and is cleared at their next login.

Each member's **Team chat sound** (the shared `sound-team-chat` in Settings, Sounds: off by default, default, bell,
pling, chime) plays for them when a teammate writes in team chat, never for the sender.

## Friendly fire

New teams start with friendly fire off. While it is off, members can't hurt each other: melee, arrows and other
projectiles, TNT they lit, their tamed pets, fire aspect and flame, and splash or lingering potions with harmful
effects. The attacker sees "Friendly fire is off in your team." (at most every 3 seconds). The guard runs at low
priority so combat tagging and kill tracking never see a blocked hit.

## Member limits

A team's limit is its owner's `siftcore.teams.size.<n>` (via `Limits.highest`), never below
`members.default-limit`. Permissions can't be read for offline players, so the owner's limit is stored with the team
(`teams.size_limit`) and refreshed when the owner joins, every minute while they are online, after they create a team
and when a team is handed to them. A team that is over its limit (an expired rank) keeps its members but can't add
new ones. Staff can add players regardless of the limit.

## Leaderboards

`/team top kills` (sum of the members' kills from `StatsRecorder`) and `/team top money` (sum of the members'
balances). Both are rebuilt off the world threads every 60 seconds from memory; teams with nothing to show are not
ranked. `/team info` computes kills, deaths and money on demand.

## Placeholders

| Name | Value |
|---|---|
| `team_name` | The team's name, empty without a team |
| `team_role` | `owner`, `admin` or `member` (from the lang file), empty without a team |
| `team_members` | Members in the team, `0` without a team |
| `team_online` | Members online now (vanished members count only for themselves), `0` without a team |

All of them read memory only and are safe from any thread.

## Events (`api.event`)

| Event | Fired | Cancelling |
|---|---|---|
| `TeamCreateEvent(owner, name, cost)` | Before the creation transaction | Nothing is created or charged |
| `TeamJoinEvent(team, teamName, player, cause)` | Before a player joins (`INVITE` or `STAFF`) | The player stays out; the invite stays usable |
| `TeamLeaveEvent(team, teamName, player, reason, actor)` | Before a player leaves or is removed (`LEFT`, `KICKED`, `STAFF`) | The player stays in |
| `TeamDisbandEvent(team, teamName, owner, members, actor)` | Before a team is disbanded (`actor` null for staff) | The team stays |

The creation is also a ledger transaction of kind `team_create`, so `EconomyTransactionEvent` fires for it.

## Config summary (`features/teams.yml`)

| Key | Default | Meaning |
|---|---|---|
| `create.cost` | `0` | What starting a team costs. 0 (the shipped value: the owner removed every fee) is free, and then no cost is named anywhere (the Start a team and Continue tooltips, the disband question); above 0 the creation asks to confirm it. Never refunded |
| `names.min-length` / `names.max-length` | `3` / `16` | Name length (16 is the column size) |
| `names.blocked-words` | `[]` | Words a name may not contain, ignoring case, separators, digits used as letters and stretched letters |
| `members.default-limit` | `5` | Members per team, owner included, when the owner's rank grants no more |
| `invites.expire-after` | `2m` | How long an invite can be accepted (10s-1h) |
| `invites.max-open` | `10` | Open invites per team |
| `invites.cooldown` | `3s` | Time between two invites by the same player |
| `home.warmup` | `5s` | `/team home` warmup; moving or taking damage cancels it, combat refuses it |
| `home.disabled-worlds` | `[]` | Worlds where the team home can't be set or used, on top of `homes.yml`'s `disabled-worlds` (which always apply to team homes too, so filling only `homes.yml` is enough); names must be loaded worlds |
| `friendly-fire.default` | `false` | Friendly fire of new teams |
| `friendly-fire.protect-members` | `true` | Whether the guard above is active (turn off only if another plugin handles it) |
| `chat.log-to-console` | `true` | Print team chat to the console |
| `member-alerts.join-delay` | `3s` | Wait after a member joins before teammates are told (50ms-1m) |
| `member-alerts.leave-delay` | `30s` | Wait before a logout is told; nothing when the member is back by then (0-10m) |
| `member-alerts.relog-grace` | `2m` | A member who left less than this ago rejoins without an alert (0-1h) |
| `member-alerts.startup-quiet` | `60s` | No login alerts this long after the server starts (0-10m) |
| `leaderboard.refresh` | `60s` | Leaderboard rebuild period (10s-1h) |
| `leaderboard.size` | `10` | Teams shown on `/team top` |
| `list-size` | `100` | Teams the All teams dialog shows, biggest first (10-1000); the dialog scrolls, no pages |
| `page-size` | `10` | Teams per `/team list` page in the console |

Everything applies with `/sift reload` (the leaderboard timer is rescheduled).

## Player settings

Registered in core `PlayerSettings` (`TeamPrefs`); text in `lang/teams.yml` under `teams.settings`. The Friends & teams
group shares its order with the friends settings (the catalog's order).

| Group, order | Id | Kind, values (default) | Read in |
|---|---|---|---|
| Friends & teams, 3 | `team-notices` | choice chat / actionbar / off (chat) | `TeamActions.broadcast` and `TeamActions.news` (`Messenger.alert` per member; the actor and a new owner get chat instead of off, `newsStyle`); disbanding uses `announce` (always chat) |
| Friends & teams, 5 | `team-member-alerts` | choice joins-and-leaves / joins / off (joins) | `LoginAlerts` (decisions in `MemberAlertRules`) |
| Friends & teams, 6 | `team-invites` | choice everyone / friends / nobody (everyone), no placeholder | `TeamActions.invite` through `services.relations()`; `/team invite` suggestions (`TeamActions.takesInvitesFrom`); the friends profile's Invite to team button; "friends" is offered while the server has friends and reads as nobody otherwise |
| Friends & teams, 11 | `team-chat-sticky` | switch (off) | `TeamChat.restore` at login (the remembered mode is written by `TeamChat.toggle`, `off` and `quit`) |
| Staff, 5 | `team-spy` | switch (on), `siftcore.teams.spy` | `TeamChat.send`; `/team spy` flips it through the registry and says so when the server locked it ("The server sets team chat spy for every staff member.") or hides it |
| Sounds (shared) | `sound-team-chat` | ping choice (off) | `TeamChat.send`, `Sounds.ping` per member except the sender |
| Privacy (shared) | `seen-privacy` | everyone / friends / nobody (everyone) | `TeamSeen.visible` for the member lists of `/team` and `/team info` (declared with `reads`) |

## Design decisions

**Snapshots and one lock.** A team is an immutable snapshot; the registry indexes snapshots by id, member and
lowercase name in concurrent maps, so chat threads, damage events on any region thread and placeholders read without
locking. Every change re-validates and swaps snapshots under the economy lock (the lock team creation's ledger
transaction runs under), so creation, joins, kicks and transfers can never interleave half-way, and it queues the
change's database write while still holding the lock, so storage sees changes in memory order.

**Creation is one transaction.** The cost (a `sink` of kind `team_create`, ref = team id), the "not in a team" and
"name free" checks, the in-memory team and the `INSERT` of the team and its owner commit together or not at all; if
storing fails the ledger reverts the charge and the team. The player is charged exactly the cost they confirmed: if a
reload changed it in the meantime nothing is charged and the new cost is shown. Team ids are never reused (the
sequence starts above the highest id in `teams` and in `team_create` ledger refs), so ledger references stay
unambiguous.

**Membership is not economic.** Joins, leaves, roles, homes and friendly fire are plain ordered writes; a failed
write is logged and the change stays in memory until the next restart.

**Startup repairs instead of refusing.** The loader removes memberships of missing teams and teams without members,
gives a team whose owner row is missing to its longest-standing admin (else member), and normalizes duplicate owners
and unknown roles (which read as member, never as more), writing every repair back and logging it.

**Threading.** Commands and dialog handlers run on the player's thread; the team home goes through core `Teleports`
(warmup, combat refusal, cancel on move or damage, `teleportAsync`) and checks the team and home again when the
warmup ends. The friendly fire guard reads only snapshots, UUIDs and the damaging entity in the victim's region (a
causing entity elsewhere, like a far shooter, is only used when it is a player). Leaderboards rebuild on an async
timer; nothing scans the world.

## Testing

Unit tests (`src/test/java/.../feature/teams`): the role permission matrix and what a member's dialog offers each role
(`TeamsSettingsTest`, with the free shipped cost and a cost still settable), name validation and the block list,
member limits, invite expiry and single use (including 64 concurrent accepts), snapshot invariants and ownership
transfer, the loader's repairs, leaderboards, and the service against a real ledger and SQLite database: creation as
one transaction (one ledger row, ledger invariants hold, refused creations and a changed cost charge nothing), 16 concurrent
creations of one name, 12 concurrent joins against a limit of 4, every rule at execution time, staff tools, and a
storage round trip compared with memory after every test.

Unit tests of the settings (`TeamSettingsTest`): ids, options and groups, who may invite with and without a friends
system, the login alert decisions (relog, startup quiet, vanished, ignored, friends or favourites told already), when
team chat mode comes back and what is remembered at a logout, and that team news always confirms the actor.
`TeamChatStickyTest` drives `TeamChat` with the real settings store across sessions: back when on at the end of the
session, never back after a session with it off (the setting turned on later), not back in a new membership after
a removal while offline. `TeamSeenTest`: the last-seen rule, how a stored row resolves, the members a viewer may see
(loaded and offline in one query, friends, nobody, staff) and the server's lock, hidden list and default.

End-to-end scenarios (`tools/e2e`, `TeamsScenarios`): `teams-create` (with `create.cost` set to 50k: the cost in the
Start a team tooltip, the confirmation, one charge), `teams-cost-change` (a reload changes the
cost under an open confirmation: nothing is charged, the new cost is shown), `teams-free` (the shipped config: no
confirmation, nothing charged, no cost or refund named), `teams-dialogs` (status lines, a tooltip on every button, the
Team chat and Friendly fire switches flipping in place without a message, greyed and refused for a member, the members
dialog, All teams without pages, team stats), `teams-invite`, `teams-roles` (promote and remove from the member's
dialog, the remove asks first, nothing offered to members or to oneself),
`teams-ownership`, `teams-chat`, `teams-home`, `teams-friendly-fire`, `teams-staff`, `teams-menu`,
`teams-ignored-invite`, `teams-settings` (team invites picked in the settings dialog, friends only, team news above
the hotbar typed as `/settings team-notices actionbar` and off, the actor's own change confirmed with news off, a new owner always told, disbanding still in chat,
the team chat sound, `/team spy` locked), `teams-login-alerts` (logins, logouts, off, friends not told twice, team chat
mode kept across a relog, and not brought back from a session where it was off) and `teams-seen-privacy` (the members
dialog from a stranger's `/team info` and a teammate's `/team` without the time of a member who keeps it to nobody,
friends only, staff).

## Known limitations

- Invites live in memory and are gone after a restart (they last two minutes).
- The dialog in an invite message is valid for 15 minutes and is one of a player's 8 most recent dialogs; after that
  clicking it says the menu expired and `/team` or `/team join <team>` answers the invite instead.
- Team kills and deaths read zero until the stats feature is wired in `FeatureCatalog`.
- A vanished member shows as offline, but the "seen" time in member lists is their last join.
- The catalog's description of `seen-privacy` (core lang) names /seen and friend profiles; the team member lists
  follow it too.
- V035 must be applied in order with the other migrations: the migration runner only applies versions above the
  highest one already applied, so deploy teams no later than any feature with a higher migration number.
