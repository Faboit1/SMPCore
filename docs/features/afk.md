# AFK (`afk`)

Notices players who are away from the keyboard, marks them for the rest of SiftCore, kicks those who stay away
outside the AFK zone, and runs the AFK zone, where players earn shards just by being there. Package `feature/afk`,
config `features/afk.yml`, text `lang/afk.yml`, the zone set in game in `data/afk-zone.yml` (per server, like the
spawn point, because it belongs to this server's worlds). No tables: today's zone earnings are read back from the
ledger.

The feature implements `core.link.AfkStatus` (`AfkFeature#status()`): a volatile flag per online player, safe and
cheap from any thread. Stats (active playtime leaves AFK time out), teleport requests (the sender is told the target
is AFK) and the crates keyall (`keyall.include-afk: false` leaves AFK players out) use it; the scoreboard feature reads
it for the AFK mark in the tab list, and `AfkStatusChangeEvent` lets it refresh the mark at once. `AfkFeature#zone()`
returns `AfkZoneInfo`, which the shards page of the main menu uses (what the zone pays this player, today's progress,
a way there).

| Contract | Wired | Used for |
|---|---|---|
| `CombatTags` (core) | always | Combat-tagged players earn nothing in the zone and can't teleport there |
| `SpawnArea` | spawn feature | `/afkzone info`, the self-test and zone changes say whether the zone lies in the protected spawn area |
| `VanishStatus` | staff feature | Vanished staff are left out of `/afk list`, `afk_count` and the zone's player counts |

## Commands and permissions

| Command | Who | What it does |
|---|---|---|
| `/afk` | everyone | Marks you AFK at once, or back when you are AFK (refused in combat by `combat.yml`'s blocked commands) |
| `/afk zone` | everyone | Same as `/afkzone` |
| `/afk list` | `siftcore.admin.afk`, console | Who is AFK, for how long, and whether in the zone or by `/afk` |
| `/afkzone` (alias `/afkarea`) | everyone | Teleports to the AFK zone (warmup, cooldown, refused in combat) |
| `/afkzone info` | `siftcore.admin.afk`, console | The zone, where it comes from, arrival, players inside and earning, rewards, daily limit, whether resting players are safe |
| `/afkzone pos1`, `/afkzone pos2` | `siftcore.admin.afk` | Corners at your feet (both in one world); the second one sets the zone |
| `/afkzone arrival` | `siftcore.admin.afk` | Where `/afkzone` lands: where you stand (must be inside the zone) |
| `/afkzone set <world> <x1> <y1> <z1> <x2> <y2> <z2>` | `siftcore.admin.afk`, console | Sets the zone to a box |
| `/afkzone reset` | `siftcore.admin.afk`, console | Back to the zone in `features/afk.yml` |

Zone changes are saved to `data/afk-zone.yml` (temporary file, then replaced) and win over the config until
`/afkzone reset`. They are written to the audit log (`afk.zone.set`, `afk.zone.arrival`, `afk.zone.reset`), as is every
AFK kick (`afk.kick`).

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.afk` | everyone | `/afk` |
| `siftcore.command.afkzone` | everyone | `/afkzone`, `/afk zone` |
| `siftcore.afk.bypass-kick` | op | Never kicked for being AFK |
| `siftcore.afk.reward.<tier>` | nobody | Earn that tier's shards per interval in the zone (the highest granted tier wins) |
| `siftcore.admin.afk` | op | `/afk list` and the zone tools |

## AFK detection

A player is AFK after `detection.afk-after` (5m) without genuine activity, or at once with `/afk`. They get an action
bar notice (`You are now AFK.`), `%siftcore_afk_status%` says `AFK`, and `AfkStatusChangeEvent` fires (also when they
come back). Genuine activity ends it; right after `/afk` a short grace (`manual-grace`, 3s) keeps the keystrokes of
the command from undoing it.

Activity is judged from what the client sends, per player, in `ActivityClassifier` (pure, unit tested). There are no
scans: every rule is a little arithmetic on the player's own event.

| Counts | Doesn't count |
|---|---|
| Looking around: the view turned at least `look-threshold` (5 degrees) since the last counted look | Turning by exactly the same amount packet after packet (circle macros), or by two fixed amounts that cancel out (jitter macros) |
| | Looks that alternate between two directions, or cycle through up to 8 directions in a fixed order three times (back-and-forth turning macros) |
| | View changes a vehicle caused (a boat turning its passenger) |
| Walking or flying into a block column not visited recently (the last 256); riding a mount the player steers (horses, donkeys, mules, camels, happy ghasts, nautiluses) counts like walking | Moving in water or lava (streams, bubble columns), carried by a vehicle (minecarts, boats, pigs, striders, llamas), within 1.5s of damage or a velocity change (knockback), or by more than a walking step in one packet |
| | Jumping in place (vertical movement, moves inside one column) |
| | Walking back into recently visited columns: pacing back and forth, circling a path, piston loops |
| | More than 32 new columns in a row without a counted look (auto-walk, flying machines, conveyors) |
| Chat lines, commands | The same line or command as one of the last four (normalised: case and spacing don't matter), or the same cycle of up to ten lines or commands three times in a row (rotating macros) |
| Using blocks, entities, menus, dialogs, signs; attacking; changing the held item or swapping hands | The same interaction at the same spot from the same view direction as one of the last four (auto-clickers, mob grinders), or the same cycle of up to ten interactions three times in a row (clicking through a few slots, scrolling through the hotbar); stepping on plates, tripwires and farmland |

Randomised anti-AFK scripts can still look human to those rules, so motion has a second limit: looking around and
walking keep a player active for at most `motion-limit` (15m) after their last *action* (chat, a command, using
something, attacking). With the defaults, a script that only moves the view ends up AFK 20 minutes after the player's
last real action, and moving no longer brings them back; an action does.

Paper reports movement in steps (`PlayerMoveEvent` fires after 1/16 block or 10 degrees of turning since the last
one), so a turn on its own is always at least 10 degrees; `look-threshold` matters below that only while moving.

## The kick

AFK players are kicked after `kick.after` (30m) spent AFK *outside* the AFK zone (counted from the moment they are
marked AFK; entering the zone stops the clock, leaving it starts it again from zero). `kick.warn-before` (1m) before,
they get a chat warning with a sound. The disconnect screen says they were AFK for too long and can rejoin. Players
with `siftcore.afk.bypass-kick` are never kicked; `kick.enabled: false` turns kicking off.

## The AFK zone

A box between two corners. By default it is anchored to the world's spawn point (`zone.anchor: spawn`: the corners
are offsets, 16 to 28 blocks east of the spawn, inside the protected spawn area, and it moves with `/setspawn`); with
`anchor: absolute` the corners are world coordinates. Zones set in game are always absolute.

- **Rewards.** Every `rewards.interval` (60s) spent in the zone without a break pays `rewards.shards` (1) shards, or
  the best tier listed in `rewards.ranks` that the player has (`siftcore.afk.reward.<tier>`). The shipped list is
  empty: paid ranks earn the same shards as everyone ([monetization](../monetization.md)); operators get no tier. Payment is one ledger transaction, a source of kind
  `afk_reward` in `Currency.SHARDS`, after the cancellable `AfkZoneRewardEvent`.
- **Continuous presence.** Leaving the zone (walking out, a teleport, death, spectator mode) throws away the progress
  towards the next reward. A combat tag does the same for as long as it lasts.
- **One account per connection.** Accounts are grouped by a salted hash of their address (`PlayerDirectory`). The
  first in the zone earns; others from the same connection wait (`AFK zone: another account on your connection is
  earning`). When the earner leaves, the account that entered next takes over with a fresh interval.
- **Daily limit** (`rewards.daily-cap`, off by default): the most shards one account earns in the zone per day,
  resetting at midnight server time. The day's total is read from the ledger (kind `afk_reward`) when a player joins,
  so it survives restarts; the last reward of the day is cut to the limit, and the player is told in chat.
- **Status line.** Every `status-every` (2s) the action bar says `AFK zone: next shard in 42s` (or why the player isn't
  earning). It pauses for a moment after another message, while a teleport warmup counts down, and while combat-tagged
  (the combat timer owns the action bar then). Players can turn it off in the settings dialog (`afk-zone-status`).
- **Safety.** Players in the zone are never kicked for being AFK. With `zone.safe` (on), players inside can't be hurt
  (except by the void and kill commands) and can't hurt anyone, even when the zone is outside the protected spawn area;
  `/afkzone info` and the self-test say whether resting players are safe.
- **`/afkzone`** teleports with the shared teleport rules (warmup `zone.teleport-warmup` 3s, refused in combat,
  cancelled by moving or damage), then a cooldown (`zone.teleport-cooldown` 10s, bypass `siftcore.bypass.cooldown`).
  It lands on `zone.arrival`, or with `auto` on the ground in the middle of the zone (read on that chunk's region
  thread), kept inside the box.

## Threading

- Movement, interactions and commands arrive on the player's thread, chat on the chat thread, attacks on the
  victim's thread: each player's state (`PlayerAfk`) is synchronized; flags read by placeholders and other features are
  volatile.
- One async timer a second resolves the zone (world spawn reads are thread-safe) and schedules each online player's
  check on that player's own thread (location, permissions, action bar, kick, reward). Nothing scans the world.
- Zone rewards are ledger transactions (thread-safe, never blocking). The zone file is written off-thread and flushed
  in `disable()`.

## Placeholders

| Name | Value |
|---|---|
| `afk_status` | `AFK` (from `lang/afk.yml`) for an AFK player, empty otherwise |
| `afk_time` | How long the player has been AFK, like `5m`; empty when not AFK |
| `afk_zone_next` | Seconds until the player's next shard in the zone; empty when not earning |
| `afk_zone_today` | Shards the player earned in the zone today |
| `afk_zone_players` | Players in the zone (vanished staff not counted) |
| `afk_count` | Players who are AFK (vanished staff not counted) |

`shards` and `shards_raw` (the balance) belong to the economy feature.

## Events

- `AfkStatusChangeEvent` (not cancellable): a player became AFK or came back; `manual()` tells whether `/afk` did it.
  Fired on the player's thread, or the chat thread when a chat line brought them back.
- `AfkZoneRewardEvent` (cancellable): before a zone reward is paid; cancelling skips that payment.

## Config (`features/afk.yml`)

| Key | Default | Meaning |
|---|---|---|
| `detection.afk-after` | `5m` | Inactivity before AFK (10s to 2h) |
| `detection.look-threshold` | `5` | Degrees of turning that count as looking around (1 to 45) |
| `detection.manual-grace` | `3s` | After `/afk`, activity in this window doesn't end it |
| `detection.motion-limit` | `15m` | Motion counts only this long after the last action (0s = no limit) |
| `kick.enabled` | `true` | Kick AFK players outside the zone |
| `kick.after` | `30m` | Time AFK outside the zone before the kick (10s to 24h) |
| `kick.warn-before` | `1m` | Chat warning before the kick (0s = none; shorter than `after`) |
| `zone.enabled` | `true` | The AFK zone |
| `zone.anchor` | `spawn` | `spawn` (offsets from the world spawn) or `absolute` |
| `zone.world`, `zone.from`, `zone.to` | `world`, `16 -16 -6`, `28 24 6` | The box (both corners included) |
| `zone.arrival` | `auto` | Where `/afkzone` lands: `auto`, `x y z` or `x y z yaw pitch` (inside the zone) |
| `zone.safe` | `true` | Nobody inside can hurt or be hurt |
| `zone.teleport-warmup`, `zone.teleport-cooldown` | `3s`, `10s` | `/afkzone` timing |
| `rewards.interval`, `rewards.shards` | `60s`, `1` | Shards per interval of continuous presence (5s to 1h) |
| `rewards.ranks.<tier>` | none (`{}`) | Optional shard tiers (`siftcore.afk.reward.<tier>`); kept empty so ranks give no AFK advantage |
| `rewards.daily-cap` | `0` | Most zone shards per account per day (0 = none) |
| `rewards.status-every` | `2s` | The action-bar countdown (0s = never) |

## Self-test

The classifier rejects a scripted water push, jump macro and pacing macro and accepts a walk; the zone's world is
loaded and its arrival point is inside; resting players are safe (inside spawn protection or `zone.safe`); every
connection in the zone has exactly one earner; only online players are tracked.

## Design decisions

- Genuine activity is judged per event from what the client sent, with small rolling histories (10 turns, 8 looks, 24
  changes of direction, 256 columns, 4 lines and 30 signatures per kind of action), never by scanning. The rules are
  conservative about humans (a rejected look only means it doesn't reset the timer) and the motion limit backs them up
  against scripts that look human.
- AFK time in the zone is the point of the zone, so it never leads to a kick; AFK time elsewhere frees a slot.
- Zone rewards create shards, so they are guarded like money: one ledger transaction each, one earner per connection,
  no combat, an optional daily limit counted from the ledger itself.
