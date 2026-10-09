# Teams (`teams`)

Players start a team for money, invite friends, give them roles, share a home and a private chat, protect each
other from friendly fire and compete on team leaderboards. Package `feature/teams`, config `features/teams.yml`, text
`lang/teams.yml`, tables `teams` and `team_members` (migration V005) plus the `teams.size_limit` column (V035).

The feature implements `core.link.TeamLookup`; `TeamsFeature#lookup()` returns it (team of a player, team name,
members, friendly fire, same-team checks; thread-safe, lock-free). It consumes five contracts:

| Contract | Used for |
|---|---|
| `StatsRecorder` | Team kills and deaths (sum of the members) in `/team info` and the kills leaderboard |
| `MuteStatus` | A muted player can't use team chat (`/tc` or chat mode) |
| `VanishStatus` | A vanished member shows as offline in member lists, online counts and placeholders |
| `IgnoreLookup` (chat, installed with `TeamsFeature#ignores` once chat is built) | A player who ignores the inviter gets no invite; the inviter hears "You can't invite <name>." (players with `siftcore.chat.unignorable` still invite) |
| `SpawnArea` (spawn) | No team home inside the protected spawn area, as with `/sethome`; a team home already stored there can't be used |
| Homes' disabled worlds (installed with `TeamsFeature#homeWorlds` once homes are built) | `homes.yml`'s `disabled-worlds` apply to team homes too, on top of `home.disabled-worlds` here |

## Commands and permissions

`/team` has the alias `/t`; `/teamchat` has the alias `/tc`. Player-only subcommands are hidden from the console.

| Command | Who | What it does |
|---|---|---|
| `/team` | everyone | The team dialog (see below). From the console: the help |
| `/team create [name]` | `siftcore.teams.create` | Starts a team. Without a name: the name form. Asks to confirm the cost |
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
| `/team list [page]` | everyone, console | Every team by size |
| `/team top [kills\|money]` | everyone, console | The team leaderboards |
| `/team spy` | `siftcore.teams.spy` | Turns seeing every team's chat on or off (also in `/settings`) |
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

`/team`, the main menu's **Team** entry (hub id `teams`, order 50) and the `teams` pause-menu entry open it.

- **Without a team:** what starting one costs, a **Start a team** button (name form, then a confirmation with the
  cost), every open invite with an **Invite from X** button, and the team list and leaderboards.
- **In a team:** the owner, members of the limit and how many are online, the home, friendly fire and team chat
  state, then every member with role and online status ("seen 2h ago" when offline). Buttons depend on the role:
  team home, team chat, invite (form), remove a member, make an admin, remove an admin (each a member picker), set
  home, friendly fire, hand over (picker, then confirmation), disband or leave (confirmation), team stats, top teams,
  all teams.

After an action the dialog is shown again with fresh state; a refused action shows the reason inside it.

### Invites

An invite lasts 2 minutes (configurable). The invitee gets a chat message; clicking it opens a dialog with **Join**
and **Decline**. That dialog is bound to the invitee (another player can't use its buttons), works once, and checks
the invite again when clicked (expired, team gone, team full, already in a team). One open invite per team and
player, at most 10 open invites per team, and a 3 second cooldown between invites. Pending invites also show in the
team dialog, and `/team join <team>` works too. Invites are kept in memory only.

## Team chat

`/tc <message>` sends one message; team chat mode (`/team chat`, `/tc` alone, or the dialog button) sends everything
the player types to the team instead of public chat. The line reads `Team <name>: <message>` in gray with the name in
white; the message is inserted literally (tags stay text). It reaches online members, staff with
`siftcore.teams.spy` who have spying on (`Team <team>, <name>: <message>`), and the console (configurable).

Team chat mode listens to `AsyncChatEvent` at low priority: it cancels the public message and clears its viewers
before formatting, broadcast or relay plugins handle it, and skips events an earlier listener already cancelled
(anti-spam, mutes). Membership is read from immutable snapshots, so the async chat thread never waits. Leaving the
team, being removed, a disband or quitting turns chat mode off.

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
| `create.cost` | `50k` | What starting a team costs (0 = free). Never refunded |
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
| `leaderboard.refresh` | `60s` | Leaderboard rebuild period (10s-1h) |
| `leaderboard.size` | `10` | Teams shown on `/team top` |
| `page-size` | `10` | Teams per `/team list` page |

Everything applies with `/sift reload` (the leaderboard timer is rescheduled).

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

Unit tests (`src/test/java/.../feature/teams`): the role permission matrix, name validation and the block list,
member limits, invite expiry and single use (including 64 concurrent accepts), snapshot invariants and ownership
transfer, the loader's repairs, leaderboards, and the service against a real ledger and SQLite database: creation as
one transaction (one ledger row, ledger invariants hold, refused creations and a changed cost charge nothing), 16 concurrent
creations of one name, 12 concurrent joins against a limit of 4, every rule at execution time, staff tools, and a
storage round trip compared with memory after every test.

End-to-end scenarios (`tools/e2e`, `TeamsScenarios`): `teams-create`, `teams-cost-change` (a reload changes the
cost under an open confirmation: nothing is charged, the new cost is shown), `teams-invite`, `teams-roles`,
`teams-ownership`, `teams-chat`, `teams-home`, `teams-friendly-fire`, `teams-staff`, `teams-menu`.

## Known limitations

- Invites live in memory and are gone after a restart (they last two minutes).
- The dialog in an invite message is valid for 15 minutes and is one of a player's 8 most recent dialogs; after that
  clicking it says the menu expired and `/team` or `/team join <team>` answers the invite instead.
- Team kills and deaths read zero until the stats feature is wired in `FeatureCatalog`.
- A vanished member shows as offline, but the "seen" time in member lists is their last join.
- V035 must be applied in order with the other migrations: the migration runner only applies versions above the
  highest one already applied, so deploy teams no later than any feature with a higher migration number.
