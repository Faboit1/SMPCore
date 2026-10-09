# Combat (`combat`)

The combat tag, combat logging, kill credit with anti-farm rules, death messages and kill streak announcements.
Package `feature/combat`, config `features/combat.yml`, text `lang/combat.yml`, table `kills` (migration V007; V045
adds the index the startup read uses).

| Link | Provider | What combat does with it |
|---|---|---|
| `core.combat.CombatTags` | core (shared state) | Writes who is in combat until when; teleports (`core.teleport.Teleports`) and the auction house read it |
| `StatsRecorder` | stats (`StatsFeature#recorder()`) | Counted kills go to `kill(killer, victim)`, every other death to `death(victim)`; streaks are read back for announcements |
| `TeamLookup` | teams (`TeamsFeature#lookup()`) | Same-team kills never count |
| `FriendLookup` | friends | Kills between friends, or players who were friends within `anti-farm.friends-window`, never count |
| `VanishStatus` | staff (`StaffFeature#vanish()`) | Vanished staff take no part in combat and are never named to players who can't see them |
| `SpawnArea` | spawn (`NONE` until the spawn feature is merged) | Tagged players can't walk, pearl or chorus into the protected spawn area |
| `Cosmetics` | cosmetics (`CosmeticsFeature#cosmetics()`) | Players are named as they show themselves (nicknames) in death messages, kill streaks and combat-log lines; the killer's kill effect plays where the victim fell |

Bounties hook into counted kills through `PlayerKillCreditEvent` (see `bounties.md`).

## The combat tag

- A hit between two players tags **both** of them for `tag.duration` (20s); every hit starts the timer again. Hits
  count when a player is behind them: melee, arrows, tridents and other projectiles, TNT or crystals they set off
  (the damage source's causing entity), instant damage potions, and, with `tag.pets`, a tamed animal of an online player.
  Self-damage (your own arrow, your own TNT) never tags.
- Only real hits tag: the listener runs at `MONITOR` with `ignoreCancelled`, after protection plugins and the teams
  feature's friendly-fire guard had their say.
- Out of play: players in creative or spectator mode, vanished staff (`VanishStatus`) and players with
  `siftcore.combat.bypass` (default false). Their hits tag nobody and hits on them tag nobody.
- A new tag (not a refresh) is told once, in the player's `combat-tag-alert` style (chat by default):
  `You are in combat with Sam! Don't log out for 20s.` (a staff tag says `Staff put you in combat.`). Quiet in combat
  never applies to combat's own alerts.
- One async timer runs once a second for the whole server. It shows `In combat 12s` to every tagged player where
  their `combat-timer-display` says (above the hotbar, on the shared boss bar through core `StatusBars`, both, or
  nowhere), tells players whose tag ran out `You are no longer in combat.` exactly once in their `combat-end-notice`
  style, and prunes old hits and kill pairs. It touches no world state: boss bar changes are handed to the player's
  thread, which shows the bar only if the player is still in combat when it runs. A player who dies or leaves is
  untagged on that same thread without the end message and the timer leaves their boss bar, so a bar the timer queued
  just before the death can't come back after it (a frozen `In combat` bar after respawning).
  `tag.action-bar: false` turns the timer off for everyone (the setting is then not offered).

While tagged:

| Refused | How |
|---|---|
| Commands in `while-tagged.blocked-commands` | `PlayerCommandPreprocessEvent` at `LOWEST`. An entry blocks the command under every alias and namespace (`home` also blocks `/siftcore:home`, and `/h` when it is an alias of `/home`); two words block one subcommand (`team home`, also as `/t home`). |
| Every plugin teleport (homes, TPA, RTP, spawn, warps, team home) | `core.teleport.Teleports` refuses at the start, after the warmup and right before teleporting |
| Ender pearls (`block-ender-pearls`, off by default) | `PlayerLaunchProjectileEvent` cancelled without using up the pearl |
| Elytra (`disable-elytra`) | Gliding stops when the tag starts; `EntityToggleGlideEvent` refuses to start again |
| Entering spawn (`block-spawn-entry`) | A move into the `SpawnArea` is cancelled with a small push back; pearls and chorus fruit that would land inside are cancelled through Canvas' `EntityTeleportAsyncEvent` (Canvas fires no `PlayerTeleportEvent` for them). The refusal stays on the action bar whatever the player's feedback channel and is told at most once a second (as are refused pearls and glides), since each pushed-back step repeats it |

Each refusal is an action bar line with the time left.

The command list only covers typed commands. What the same commands do from dialogs (the pause menu, chat dialogs,
main menu forms) is refused by the features themselves, so a dialog is never a way around a blocked command: teleport
requests can't be sent or accepted in combat (`tpa.md`), homes can't be set (`homes.md`), the shop, shard shop, sell,
orders, auction house, crates, kits and spawner storage refuse tagged players, and the auction house button runs `/ah`
through `PlayerCommandPreprocessEvent` so this list applies to it.

## Kill credit and anti-farm

The last player who hit the victim within the tag window gets the kill, even when a fall, lava, the void or a mob
finished them. Without such a hit the game's own killer is used (not in creative, spectator or vanish).

A credited kill is then decided, in this order (the first rule that applies is the logged reason):

| Rule (`anti-farm`) | Reason in the log |
|---|---|
| `same-team`: both in the same team (`TeamLookup`) | `same_team` |
| `friends`: they are friends, or were within `anti-farm.friends-window` (24h; `FriendLookup#recentlyFriends`), so unfriend, kill and re-friend gives no credit or bounty | `friends` |
| `same-ip`: both last seen from the same IP (a salted hash in the player directory) | `same_ip` |
| `repeated-pair-cooldown`: the same killer got a counted kill on the same victim less than 10m ago | `repeated_pair` |
| A plugin cancelled `PlayerKillCreditEvent` | `cancelled` |

Counted kills raise the killer's kills and streak (`StatsRecorder#kill`) and can claim a bounty. Kills that don't
count are still logged, and the victim's death still counts (`StatsRecorder#death`), so a farmed kill never helps
anyone. The killer is told the result in their `kill-feedback` style (above the hotbar by default):
`Your kill on Alex counted. Kill streak 3.` or `Your kill on Alex didn't count: friends.` A shared IP address and a
plugin's cancellation are never named (`Your kill on Alex didn't count.`). Staff with `siftcore.admin.combat` who chose
`combat-logs-and-farming` get `Sam's kill on Alex didn't count: same IP address.` The repeated-pair rule is directional (A on B and B on A are different pairs) and is answered from memory:
the counted kills of the last 24 hours are read back from the `kills` table at startup and every counted kill is
added as it happens.

## Death messages

- Player kills get a clean line instead of the game's: `Alex was killed by Sam.` or, with `show-weapon`,
  `Alex was killed by Sam using Diamond Sword.` The weapon name has no colours or italics and shows the item on hover.
  The same line is shown on the death screen.
- Every other death keeps the game's own message (its translation, arguments and hovers) with every colour and
  decoration removed, shown in the secondary colour.
- Each player chooses which deaths of other players they see (`death-messages`, Server announcements): all (default),
  only player kills, only deaths of their friends and teammates and kills they made (offered while the server has
  friends or teams), or none. It was a switch: stored `true`/`false` rows and config entries read as all and none.
  The victim and the killer always see theirs, and so does the console.
- After a death (not a combat log) the victim gets two private chat lines: where they died (`death-coordinates`,
  on; only the world while streamer mode `hide-coordinates` is on) and, after a player kill, the killer's health
  and weapon (`death-recap`, on): `Sam had 6.5 hearts left, using Diamond Sword.` The killer's health is read on the
  killer's thread, which then sends the line.
- Lines about a vanished player only reach the players involved and staff who can see vanished players.
- `death-messages.enabled: false` leaves death messages to the game.
- Players are named as they show themselves: a nickname in its colour, the real name on hover (`Cosmetics`). The
  kill log, `/combat kills` and the audit log keep real names.

## Kill effects

When a death is final (`MONITOR`, not cancelled) and a player gets the kill credit, combat hands the killer, the
victim and the spot to `Cosmetics#kill` on the victim's region thread. The cosmetics feature plays the killer's kill
effect there (Tycoon), rate limited and never in the protected spawn. Players who turned kill effects off see none, and
the lightning effect's bolt holds off while one of them is within sight; see `cosmetics.md`.

## Kill streaks

The streak is kept by the stats (counted kills in a row without dying). After a counted kill, reaching a streak in
`streaks.announce-at` is announced: `Alex is on a kill streak of 10.` When someone kills a player whose streak was at
least `streaks.announce-ended-from`, that is announced too: `Sam ended Alex's kill streak of 12.` Both go to the
killer, the victim, the console and every player who keeps `kill-streak-announcements` on.

## Combat logging

A player who leaves while tagged (not dead, not out of play) fires the cancellable `CombatLogEvent` (listeners may
also change the punishment), then:

- `punishment: kill` (default): they die where they stand as they leave. Their items drop there, the death is
  processed like any other (the last player who hit them gets the kill, the anti-farm rules apply, any bounty on them
  is claimed) and `Alex logged out in combat. Sam gets the kill.` replaces the death message, sent to every player
  who keeps `combat-log-announcements` on (and to the players involved).
- `punishment: none`: only the announcement `Alex logged out in combat.` (same audience).
- Kicks are punished too (`punish-kicks`, on by default), otherwise getting kicked for spam would be a way out.
  Staff who want to kick someone in combat without killing them can `/combat untag` them first. Players online
  when the server stops are never punished (plugins get no quit event at shutdown).
- Every combat log is written to the audit log (`combat.log`, with the punishment, the quit reason, the time left,
  the last attacker and the location) and told to online staff with `siftcore.admin.combat` whose
  `staff-combat-alerts` is not off: `Alex logged out in combat with 12s left. Last hit by Sam.`

## Player settings

Registered at construction (`CombatFeature.registerSettings`), text in `lang/combat.yml` under `combat.settings`.
Settings that depend on `combat.yml` are only offered while the config turns the behaviour on.

| Id | Group (order) | Options (default first) | Read in | Offered while |
|---|---|---|---|---|
| `combat-timer-display` | Combat & stats (1) | actionbar, bossbar, both, off | `TimerDisplay` from `CombatTimer.tick`, `CombatTagger` (first line), `CombatTagger.clear` | `tag.action-bar` |
| `combat-tag-alert` | Combat & stats (2) | chat, actionbar, title, off | `CombatTagger.started` (new tags only) | always |
| `kill-feedback` | Combat & stats (3) | actionbar, chat, title, off | `CombatListener.killFeedback` (`KillNotice.key`) | always |
| `death-coordinates` | Combat & stats (4) | on | `CombatListener.location` (`CombatListener.locationLine`, reads `hide-coordinates`) | always |
| `combat-end-notice` | Combat & stats (5) | actionbar, chat, title, off | `CombatTimer.tick` (ended tags, `CombatTimer.endNotice`) | always |
| `death-recap` | Combat & stats (8) | on | `CombatListener.recap` | always |
| `death-messages` | Server announcements (1) | all, pvp, friends-team, off (legacy `true`/`false`) | `DeathMessages.death` | `death-messages.enabled`; friends-team while friends or teams exist |
| `kill-streak-announcements` | Server announcements (6) | on | `DeathMessages.streakLine` | a streak is announced (`announce-at` or `announce-ended-from`) |
| `combat-log-announcements` | Server announcements (7) | on | `DeathMessages.logoutLine` | `logout.announce` |
| `staff-combat-alerts` | Staff (10), `siftcore.admin.combat` | combat-logs, combat-logs-and-farming, off | `StaffNotices` | always |

Title styles use their own short keys (`combat.tag.started-title`, `combat.tag.ended-title`,
`combat.kill.counted-title`, `combat.kill.not-counted-title`). Combat's own alerts pass `quiet = false` to
`Messenger.alert`, so quiet in combat never hides them.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/combat` (`/combattag`, `/ct`) | `siftcore.command.combat` (everyone) | Whether you are in combat and for how long |
| `/combat status <player>` | `siftcore.admin.combat` (op) | Whether a player is in combat, for how long and who hit them last |
| `/combat tag <player> [time]` | same | Puts a player in combat (default the configured time, at most 1h); audited as `combat.tag` |
| `/combat untag <player>` | same | Ends a player's combat (they are told); audited as `combat.untag` |
| `/combat kills <player> [page]` | same | The player's kills and deaths, newest first, with the reason when a kill did not count |
| — | `siftcore.combat.bypass` (false) | Never put in combat |

Staff commands work from the console.

## Placeholders

| Name | Value |
|---|---|
| `combat_tagged` | `true` or `false` |
| `combat_time` | Whole seconds of combat left, rounded up (`0` when not in combat) |

## Events (`api.event`)

| Event | When | Cancelling |
|---|---|---|
| `CombatTagEvent` | Before each player of a hit is tagged (fired once for the victim, once for the attacker) | That player's tag stays as it was; the hit still counts for kill credit |
| `PlayerKillCreditEvent` | A kill passed the anti-farm rules and is about to count | The kill is logged as `cancelled`: no kill, no streak, no bounty |
| `CombatLogEvent` | A tagged player leaves | No punishment and no announcement; listeners may change the punishment instead |

## Config summary (`features/combat.yml`)

| Key | Default | Meaning |
|---|---|---|
| `tag.duration` | `20s` | How long a tag lasts after the last hit (1s-5m) |
| `tag.pets` | `true` | Tamed animals tag for their owner |
| `tag.action-bar` | `true` | Show the `In combat 12s` timer (where: each player's `combat-timer-display`) |
| `while-tagged.blocked-commands` | spawn, home, homes, sethome, tpa, tpahere, tpaccept, back, rtp, wild, warp, warps, team home, afk, ec, enderchest, craft, workbench, anvil, kit, kits, shop, shardshop, ah | Commands refused in combat |
| `while-tagged.block-ender-pearls` | `false` | Refuse pearls in combat |
| `while-tagged.disable-elytra` | `true` | No gliding in combat |
| `while-tagged.block-spawn-entry` | `true` | Keep tagged players out of the protected spawn |
| `logout.punishment` | `kill` | `kill` or `none` |
| `logout.punish-kicks` | `true` | Also punish kicks |
| `logout.announce` | `true` | Announce combat logs in chat, to players who keep `combat-log-announcements` on |
| `death-messages.enabled` | `true` | Replace and restyle death messages (each player's `death-messages` filters them) |
| `death-messages.show-weapon` | `true` | `using <item>` with the item on hover |
| `streaks.announce-at` | 5, 10, 15, 20, 25, 30, 40, 50, 75, 100 | Streaks announced when reached (`[]` turns it off) |
| `streaks.announce-ended-from` | `5` | Ending a streak this long is announced (`0` turns it off) |
| `anti-farm.same-team` / `friends` / `same-ip` | `true` | The rules above |
| `anti-farm.friends-window` | `24h` | An ended friendship still counts this long (0s to 30d; the friends feature remembers removals for its `anti-farm.remember`, 7d) |
| `anti-farm.repeated-pair-cooldown` | `10m` | `0s` turns it off; at most 24h |

Everything applies with `/sift reload`.

## Self-test

`/sift selftest` checks the anti-farm decision table, that the combat timer ran in the last 5 seconds, that no
offline player is still tagged, and that the kill log can be read. The settings self-test checks the combat settings'
text and group sizes.

## Tests

Unit: `AntiFarmTest`, `TagTimingTest`, `CommandFilterTest`, `CombatRefusalsTest`, `KillTrackerFriendsTest`,
`DeathTextTest`, `CombatResourcesTest` (config, every lang key, the setting lines), `CombatPlayerSettingsTest`
(groups and order, legacy values and config entries of `death-messages`, config-dependent offering, the friends-team
option, the death filter, kill notices and the kill confirmation line per style, the death location line with
`death-coordinates` off and in streamer mode, the end notice per style, timer styles and boss bar progress, staff
alert choices, recap hearts) and `TimerDisplayTest` (the boss bar timer across threads: a bar queued by the async
timer before a death or the end of the tag never shows after it).
End to end (`tools/e2e/CombatScenarios.java`): `combat-tag`, `combat-kill`, `combat-credit` (death messages off),
`combat-log`, `combat-log-none`, `combat-pearl`, `combat-streak` (streak announcements switched off),
`combat-team`, `combat-vanish`, `combat-pair-memory`, `combat-ex-friends`, `combat-settings` (the combat timer on the
boss bar and the tag alert as a title through the dialog, a refresh that alerts nobody, staff untag clearing the bar,
a boss bar player dying in combat and respawning without the timer, then through the API no timer, no tag alert and
the end notice in chat and as a title) and `combat-death-settings` (kill confirmation counted, not counted in chat,
as a title and off; the death location with coordinates, in streamer mode and off; the death recap switched off in
the dialog; the death message filter for player kills only and for friends and teammates only; combat log
announcements switched off; staff farming and combat log alerts, and none for a staff member who turned them off).
The `/settings <id> <value>` command path is not covered yet: it comes with the settings dialog package.

## Design decisions

**Threads.** Every handler runs on the thread that owns its event: hits on the victim's region, commands, moves,
glides and pearl throws on the player's region, deaths and quits on the dying or leaving player's region. The
attacker of a long shot may belong to another region, so the attacker is only messaged (packets) and their elytra is
stopped on their own thread through the scheduler. The timer is async and only reads the tag map and sends packets;
its boss bar lines run on the player's thread and check the tag there, the thread where death and quit end it.
Kill log writes go to the database writer; nothing blocks a region thread.

**Kill credit without the game's killer.** The game only credits a player when they dealt the final blow. Combat
keeps the last hit per victim (attacker, time, weapon) for the tag window, so knocking someone into lava or the void
is a kill, and the weapon of that last hit names the death message and the victim's death recap. Hits are kept only for players who were hit
recently and are dropped on death, on quit and once older than the window.

**Two-stage death handling.** At `HIGHEST` the credit and the message are worked out while the event can still be
changed (the game's broadcast is replaced, the death screen gets the same line). At `MONITOR` (and only if the death
was not cancelled) the tag ends, the message is sent per recipient (each player's setting decides), the kill is
decided, reported and logged, the victim gets their death location and recap, the killer their kill confirmation,
and streaks are announced.

**Combat logging kills on quit.** The quit handler runs at `LOW`, before other features save and forget the player,
and kills the player through `setHealth(0)` while they are still in the world, so drops, kill credit, stats and the
bounty claim all happen exactly as for a normal death. The player is saved dead and respawns on their next join.
