# Sidebar, tab list and nametags (`scoreboard`)

The sidebar on the right of the screen, the tab list (header, footer, names and order) and rank labels above heads.
Package `feature/scoreboard`, config `features/scoreboard.yml`, text `lang/scoreboard.yml`. No tables: the only
stored state is each player's `scoreboard` setting (the core `settings` table).

| Part | What players see |
|---|---|
| Sidebar | Title "SiftVanilla" in plain white, then the configured lines (money, shards, the sell booster while one runs, kills, deaths, playtime, team, the address) with icons, gray labels and no red score numbers |
| Tab list | A header with the server name and the online count, a footer with a short line and the address, names as "Rank Name" (label gray, name white) with "AFK" after players who are away, higher ranks listed first |
| Nametags | The rank label in gray in front of the name above heads; names stay white |

It is connected to:

| Link | Used for |
|---|---|
| `StatsRecorder` (stats, `StatsFeature#recorder()`) | `{kills}`, `{deaths}`, `{streak}`, `{best_streak}`, `{playtime}` |
| `TeamLookup` (teams, `TeamsFeature#lookup()`) | `{team}`: the team line, left out without a team |
| `CombatTags` (core, shared with combat) | `{combat}`: seconds of combat left; the optional combat line only shows in combat |
| `VanishStatus` (staff, `StaffFeature#vanish()`) | `{online}` leaves vanished staff out; vanished staff are in no nametag team |
| `AfkStatus` (afk, `AfkFeature#status()`) and `AfkStatusChangeEvent` | The AFK marker in the tab list, set at once when a player goes AFK or comes back |
| `core.integration.Ranks` (`Ranks.NONE` until the integrations feature is wired) | Rank labels and primary groups; until then ranks come from the LuckPerms group permissions (below) |
| The settings dialog (settings feature) | The `scoreboard` switch in its own Display group (`/settings display`, `ScoreboardFeature.DISPLAY`); a change there applies at the next refresh |
| `Placeholders` (core registry) | Every other `{name}`: any feature's placeholder (`{balance}`, `{shards}`, `{keyall_countdown}`, `{bounty_total}`, `{team_online}`, `{stats_kdr}` ...) |

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/sidebar` (`/sb`) | `siftcore.command.sidebar` (everyone) | Hides the sidebar, or shows it again. Applied at once and remembered across sessions (the `scoreboard` setting, also in the settings menu) |
| `/sidebar on`, `/sidebar off` | same | Shows or hides it explicitly ("already shown/hidden" when nothing changes) |
| `/sidebar refresh` | `siftcore.admin.scoreboard` (op) | Reads every rank again and resends every sidebar, the tab list and the nametag teams now (after rank changes in LuckPerms). Console too |
| `/sidebar status` | same | Players, sidebars shown, hidden by the player, nametag teams, boards waiting for their player, how long the last refresh took, and which parts other plugins show. Console too |
| `/sidebar preview <player>` | same | The lines that player's sidebar shows right now, as chat lines (console too); says when they hid it |

`/scoreboard` is not used: it is the vanilla command and stays available to operators. Vanilla `/team` and
`/scoreboard objectives` still work on the main scoreboard, but players look at their own board, so changes there
are not visible to them while SiftCore's scoreboard runs.

## Placeholders

None new. Sidebar lines and the tab list header and footer show placeholders in braces. The scoreboard provides
these itself (they win over a registry entry of the same name):

| Name | Value |
|---|---|
| `{player}` | The viewer's name |
| `{online}` | Online players, vanished staff not counted |
| `{max_players}` | Server slots |
| `{ping}` | The viewer's ping in milliseconds |
| `{rank}` | The viewer's rank label, empty without one |
| `{team}` | The viewer's team name, empty without a team |
| `{kills}`, `{deaths}`, `{streak}`, `{best_streak}` | From the stats recorder, with separators (`1,234`) |
| `{playtime}` | Active playtime (`3h 25m`) |
| `{combat}` | Combat time left in whole seconds rounded up (`12s`), empty out of combat |

Every other name is looked up in SiftCore's placeholder registry (`/sift placeholders` lists them). A value is
inserted as plain text in the colour around the placeholder, so a team name can never add formatting. A name nothing
provides shows `-` (the self-test reports it). A value is cut at 48 characters and never spans lines.

**A sidebar line is left out while one of its placeholders is empty**: `team` without a team, `rank` without a label,
`combat` out of combat, `booster` while no sell booster runs. The other lines keep their places.

## Config summary (`features/scoreboard.yml`)

| Key | Default | Meaning |
|---|---|---|
| `sidebar.enabled` | `true` | Sidebar for everyone; `false` removes it (tab list and nametags keep working) |
| `sidebar.refresh` | `1s` | Refresh period of the lines (250ms-1m) |
| `sidebar.lines` | blank, balance, shards, booster, kills, deaths, playtime, team, blank, website | Lines top to bottom (at most 15): line names from `lang/scoreboard.yml` or `blank` |
| `tab.enabled` | `true` | Header and footer; `false` clears them |
| `tab.refresh` | `5s` | Header and footer refresh period (1s-5m) |
| `tab.names` | `true` | "Rank Name" in the tab list, higher ranks first; `false` restores plain names |
| `tab.afk-marker` | `true` | "AFK" after away players' names |
| `nametags.enabled` | `true` | Rank teams on every board; `false` removes them |
| `ranks.refresh` | `30s` | How often ranks are read again (5s-1h); also on join and `/sidebar refresh` |
| `ranks.order` | tycoon Tycoon, baron Baron, prospector Prospector, default (no label) | Ranks from highest to lowest: LuckPerms group and fallback label (the live tiers) |
| `sidebar.yield-to`, `tab.yield-to`, `nametags.yield-to` | `[TAB]` each | Plugins that show that part instead while they run (see below) |

Available sidebar lines: `balance`, `shards`, `kills`, `deaths`, `kdr`, `streak`, `playtime`, `team`, `rank`,
`keyall`, `booster` ("Booster +10% 29m 41s", from `%siftcore_booster_percent%` and `%siftcore_booster_time_left%`),
`bounty`, `combat`, `online`, `ping`, `website`. Their text is in `lang/scoreboard.yml`
(`scoreboard.sidebar.lines.<name>`) and can show any placeholder, so a line can be repurposed without code.
With both `sidebar.enabled` and `nametags.enabled` off, players are put back on the main scoreboard.

Everything applies with `/sift reload`: lines, title and text rebuild every sidebar, ranks are read again, and the
refresh timer is rescheduled when its period changes. Unknown line names, more than 15 lines, bad durations, group
names that are not LuckPerms names and labels with formatting are reported precisely and the defaults are kept.

### Other plugins (TAB)

The server used the TAB plugin for the tab list, nametags and a sidebar before SiftCore, and the rank plan still
describes TAB groups. Two plugins drawing the same sidebar, tab names or nametag teams fight (a player can be in one
scoreboard team only, and the last sidebar sent wins). So each part has a `yield-to` list: while one of those plugins
is enabled, SiftCore leaves that part completely alone. With the defaults, installing TAB hands all three parts to
it; SiftCore then puts players back on the main scoreboard, leaves tab names and the header alone, and `/sidebar`
says which plugin shows the sidebar. To let SiftCore draw a part TAB has turned off, remove `TAB` from that part's
list. Placeholders keep working for TAB through PlaceholderAPI (`%siftcore_balance%` ...). The check is live, so it
follows `/sift reload` at once.

### Ranks

A player's rank is their LuckPerms primary group (from the rank integration) when it is listed in `ranks.order` and
is not `default`; otherwise the highest listed group the player belongs to (LuckPerms gives every member of a group,
inherited groups included, the permission `group.<name>`; the check needs the node to be set, so operators are not
every rank); otherwise `default` when the integration reports it. The label is the integration's label (the
`siftcore-rank` meta on the live server) when it gives one, otherwise the listed label. Players with no listed rank
come last.

## Self-test

| Check | Passes when |
|---|---|
| every player with the sidebar on has one | The players with a sidebar are exactly the online players whose `scoreboard` setting is on (players who joined in the last 5 seconds are skipped); none while the sidebar is turned off or shown by another plugin |
| nametag teams are the same on every board | On the global thread: every board in use shows exactly the model's teams and members |
| every placeholder in the sidebar and tab list is provided | Every `{name}` in every line, the header and the footer is the scoreboard's own or registered |
| the sidebar refresh keeps up | The last refresh took at most a quarter of the refresh period |

## Design decisions

**One board per player, also for players who hid the sidebar.** Nametag teams must be on the board a player looks
at, so everyone gets a private board with the same teams. Hiding the sidebar unregisters its objective on that board
(the client gets the remove packet) instead of switching to a shared sidebar-less board: no `setScoreboard` hop, no
resend of every team, and the nametags stay. With the sidebar and nametags both off, players go back to the main
board.

**Threading (Canvas region threading).** All scoreboard objects (boards, objectives, scores, teams) are created and
changed on the global region thread, and all of the feature's state lives on that thread. `Player#setScoreboard`,
`playerListName` and `setPlayerListOrder` run on the player's thread, and so does reading a rank (it checks the
player's permissions; the result is applied on the global thread). The header and footer are sent from the global
thread (thread-safe packet). A new board is filled completely on the global thread, then handed to the player's
thread; until that thread reports back on the global thread, the global thread does not touch the board. So the
server never walks a board on a player's thread (`updateEntireScoreboard`) while the global thread changes it. A
failed hand-over is retried at the next refresh (with a warning after three failures). A board whose refresh throws
is rebuilt from scratch; after three rebuilds the player is put back on the main board until they rejoin, with one
warning.

**Only changes are sent.** Each line remembers the placeholder values it was last built from and its text; a refresh
first resolves the values (cheap cache reads) and rebuilds a line only when they changed, then sends a score only
when its text changed (`SidebarLines`). An idle sidebar sends nothing except the playtime line while it counts
seconds. Teams follow a model with a version (`NametagModel`); a board compares its teams only when the version
moved, and then applies only the differences (`TeamDiff`: removed teams, leaving players, new teams, prefix changes,
joining players). The header and footer are sent when their text changed, tab names when the rank, the AFK state or
the text changed.

**The text is parsed once.** Lines, header and footer are lang entries parsed with the design-system tags; the
`{name}` placeholders are swapped in afterwards as plain text (`LineTemplate`, Adventure's text replacement keeps the
surrounding style). The parsed text is compared with the lang file every 5 seconds and after `/sift reload` (a few
parses, not per player); a change starts a new epoch and every sidebar is rebuilt.

**Scores.** Entries are fixed ids (`§0` to `§e`): unique, never a player name, and blank on clients too old for
custom score names. Scores go from 15 down so the configured order holds; `NumberFormat.blank()` on the objective
hides the numbers. Each line's text is the score's custom name, so lines can hold icons and styled values.

**Nametag teams.** One team per rank (position and label), named `sift_r<position><id>` (at most 10 characters):
the client sorts the tab list by team name, so higher ranks come first, and `setPlayerListOrder` puts them first as
well. Teams have white names, the gray label prefix, and `seeFriendlyInvisibles` off, because players of the same
rank share a team and must not see each other through invisibility potions. Teams only exist on players' private
boards; the server's combat and collision rules use the main board, so they don't change gameplay. Vanished staff
are in no team on any board (their name never reaches other clients) and come back when visible. Combat-tagged
players get nothing extra above their heads.

**AFK.** The tab name follows `AfkStatus` at every refresh, and `AfkStatusChangeEvent` (fired on the player's or the
chat thread) schedules that player's tab name on the global thread at once.

**Tab names on join.** The tab name and order are set in the join event on the player's thread (after vanish hid
the player), before the server sends the new player's tab entry, so nobody sees the plain name first.

**Shutdown.** `disable()` cancels the timer and puts online players back on the main scoreboard directly on the
shutdown thread (the region scheduler has stopped; Canvas allows scoreboard and player changes there), then forgets
every board. Players get no quit event at shutdown, so nothing else is needed.

## Testing

- Unit tests (`src/test/java/.../feature/scoreboard`): placeholder parsing and rendering, literal values, hidden
  lines (`LineTemplateTest`); line diffing, entries and scores (`SidebarLinesTest`); the nametag model and team diff
  (`NametagTest`); rank resolution and tab order (`RankOrderTest`); the scoreboard's own placeholders
  (`ValuesTest`); the shipped config and text, mistakes in the config, rendering and the writing rules
  (`ScoreboardResourcesTest`).
- End to end (`tools/e2e/.../ScoreboardScenarios.java`, the bot records objective, display slot, score, reset,
  team, tab list header/footer and player info packets):
  - `scoreboard-sidebar`: objective in the sidebar slot, blank number format, plain white title, every line with
    custom text and the right colours; a payment resends only the money line; an idle refresh resends nothing but
    playtime; kills and deaths from the stats recorder; the team line appears in a team and goes on disband; the
    sidebar survives death and respawn.
  - `scoreboard-switch`: `/sidebar` removes the objective at once, the choice is stored and survives a new session,
    nametags stay meanwhile, `/sidebar on` brings every line back.
  - `scoreboard-nametags`: both clients see both players in the same team with white names and no friendly
    invisibles; a `group.baron` permission plus `/sidebar refresh` adds the gray "Baron" prefix, a separate team that
    sorts first, the "Baron Name" tab name and a higher list order; losing it reverts; vanish takes the player out of
    every team and back; a player who leaves is removed.
  - `scoreboard-tab`: header and footer text, `/afk` puts a gray "AFK" after the name in the other player's tab list
    at once and `/afk` again removes it, the online count without a vanished moderator, no resend while nothing
    changes.
  - `scoreboard-staff`: status, preview (and an unknown name), refresh resends every line, players can't use the
    staff commands, the self-test passes.
  - `scoreboard-reload`: new lines from the config (kdr, online; rank hidden without a label), the combat line
    appears with `/combat tag` in its place and goes with `/combat untag`; changed title and line text in lang apply.
  - `scoreboard-persist`: a fixed player hides the sidebar; run again after a restart, they join without one.
  - `scoreboard-yield`: with the harness plugin standing in for TAB in every `yield-to`, the client loses the sidebar
    objective, SiftCore's teams, the tab name and the header; `/sidebar` and the status name the plugin; the
    self-test has no failures; without it everything comes back.
- Needs a real client to judge: how the icons and colours look in the sidebar, tab list and above heads.
