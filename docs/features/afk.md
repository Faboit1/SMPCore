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
they get a chat warning with a sound (or a title, `afk-kick-warning`). The disconnect screen says they were AFK for too
long and can rejoin. Players with `siftcore.afk.bypass-kick` are never kicked; `kick.enabled: false` turns kicking off.

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
- **One account per connection.** Accounts are grouped by a salted hash of their network, an IPv4 address or an IPv6 /64 (`PlayerDirectory#connection`, dupe audit R17), so the addresses of one IPv6 network count as one connection. The
  first in the zone earns; others from the same connection wait (`AFK zone: another account on your connection is
  earning`). When the earner leaves, the account that entered next takes over with a fresh interval.
- **Daily limit** (`rewards.daily-cap`, off by default): the most shards one account earns in the zone per day,
  resetting at midnight server time. The day's total is read from the ledger (kind `afk_reward`) when a player joins,
  so it survives restarts; the last reward of the day is cut to the limit, and the player is told in chat.
- **Status line.** Every `status-every` (1s) the action bar says `AFK zone: next shard in 42s` (or why the player isn't
  earning), so the countdown goes down by one every second. The checks run once a second on each player's thread, a
  little early or late now and then; the zone's clock is aligned to them when a player enters and the seconds are
  rounded, so the line never skips a number or shows one twice. Shards are always in the shards colour (purple,
  `colors.shards` in `config.yml`), in every AFK and shard text. It pauses for a moment after another message, while a teleport warmup counts down, and while combat-tagged
  (the combat timer owns the action bar then). Players can move it to the boss bar or turn it off (`afk-zone-status`).
  The boss bar (shared `StatusBars`, below the combat timer) updates every second and fills up towards the next shard;
  it shows why nothing is earned while waiting for another account or after the daily limit, and goes away outside the
  zone, in combat and when the player picks another style.
- **The payout.** "AFK zone: +1 shard, 61 in total" (`afk-zone-payouts`: above the hotbar, in chat or not at all) and a
  chime (`rewards.sound`, shipped `block.amethyst_block.chime` at volume 0.8, pitch 1.2) the moment shards are paid.
  The chime follows each player's sound settings: their volume, success sounds, and quiet during combat; it plays
  even when the payout line is off.
- **Safety.** Players in the zone are never kicked for being AFK. With `zone.safe` (on), players inside can't be hurt
  (except by the void and kill commands) and can't hurt anyone, even when the zone is outside the protected spawn area;
  `/afkzone info` and the self-test say whether resting players are safe.
- **`/afkzone`** teleports with the shared teleport rules (warmup `zone.teleport-warmup` 3s, refused in combat,
  cancelled by moving or damage), then a cooldown (`zone.teleport-cooldown` 10s, bypass `siftcore.bypass.cooldown`).
  It lands on `zone.arrival`, or with `auto` on the ground in the middle of the zone (read on that chunk's region
  thread), kept inside the box.

## Player settings (AFK & shards group)

| Id | Kind | Default | What it does | Offered while |
|---|---|---|---|---|
| `afk-zone-status` | choice actionbar/bossbar/off | actionbar | where the zone countdown shows; was a switch: stored `true` reads as actionbar, `false` as off | the zone is on and `rewards.status-every` is above 0s |
| `afk-zone-payouts` | choice actionbar/chat/off | actionbar | how `AFK zone: +1 shard` lines show; reaching the daily limit is always told in chat | the zone is on |
| `afk-kick-warning` | choice chat/title | chat | the kick warning as a chat line or a title (`AFK kick in 1m`); it can't be turned off | `kick.enabled` and `kick.warn-before` above 0s |
| `afk-status-messages` | choice actionbar/chat/off | actionbar | where `You are now AFK` and `Welcome back` show; `/afk` always answers (above the hotbar when off) | always |
| `afk-return-summary` | toggle | on | on coming back, a chat line with the time away and the zone shards earned meanwhile (shown after at least a minute away or with shards earned); a spell noticed by the clock is measured from the last activity, so it includes the `afk-after` wait and the shards paid in it, a `/afk` spell from the command | always |

The shard shop adds `shard-confirm-above` and `shard-shop-stay-open` to the same group ([shards](shards.md)). None of
them needs a permission. Status lines and payouts go through `Messenger.alert` (quiet in combat turns hotbar lines into
chat lines); the AFK tab mark, placeholders, `AfkStatusChangeEvent` and the kick clock don't depend on any setting.

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
| `rewards.status-every` | `1s` | The action-bar countdown (0s = never, else at least 1s) |
| `rewards.sound` | on, `block.amethyst_block.chime`, `0.8`, `1.2` | The sound of a payout: `enabled`, `sound` (any sound id), `volume` (0 to 2), `pitch` (0.5 to 2) |

## Self-test

The classifier rejects a scripted water push, jump macro and pacing macro and accepts a walk; the zone's world is
loaded and its arrival point is inside; resting players are safe (inside spawn protection or `zone.safe`); every
connection in the zone has exactly one earner; only online players are tracked.

## Tests

- Unit (`src/test/java/.../feature/afk`): the classifier, the clock, zone sessions (the countdown goes down by one
  every second with checks up to 300ms early or late, a takeover starts on the checks' rhythm) and the shipped config
  and text (the 1s status line, the payout sound and turning it off or changing it; `ActivityClassifierTest`,
  `AfkClockTest`, `ZoneSessionsTest`, `AfkResourcesTest`), and the AFK settings: group and
  order, the old switch's stored values, config-dependent offering, `/afk` always answering, the boss bar's progress
  and the spell bookkeeping behind the welcome-back summary: a `/afk` spell from the command, one the clock noticed
  from the last activity with the shards paid during the idle wait, motion past the motion limit (`AfkPlayerSettingsTest`).
- End to end (`tools/e2e/.../AfkScenarios.java`): `afk-detect`, `afk-manual`, `afk-kick`, `afk-zone`, `afk-zone-cap`,
  `afk-zone-countdown` (one status line a second for 8.5s, each one second less than the last; a payout plays the
  chime once with "+1 shard" in purple; with success sounds off it is silent);
  the settings: `afk-settings` (boss bar countdown and chat payouts in the dialog, then off, back on the action bar and
  silent payouts by API), `afk-status-settings` (status lines in chat and off, the welcome-back summary on and off,
  and for a spell noticed on its own: the time since the last activity and the shards paid before the AFK mark) and
  `afk-kick-title`.

## Design decisions

- Genuine activity is judged per event from what the client sent, with small rolling histories (10 turns, 8 looks, 24
  changes of direction, 256 columns, 4 lines and 30 signatures per kind of action), never by scanning. The rules are
  conservative about humans (a rejected look only means it doesn't reset the timer) and the motion limit backs them up
  against scripts that look human.
- AFK time in the zone is the point of the zone, so it never leads to a kick; AFK time elsewhere frees a slot.
- Zone rewards create shards, so they are guarded like money: one ledger transaction each, one earner per connection,
  no combat, an optional daily limit counted from the ledger itself.
