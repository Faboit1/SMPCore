# Settings (`settings` and `core/player`)

One dialog for every per-player setting on the server. The model and storage live in core (`core/player`:
`PlayerSettings`, `PlayerSetting`, `Toggle`, `Choice`, `NumberSetting`, `SettingCategories`, `SharedSettings`); the
dialog, the `/settings` command and the server overrides live in `feature/settings`. Text: `lang/settings.yml`
(dialog, groups, shared option names) and `lang/core.yml` (`shared-settings`). Config: `features/settings.yml`.
Table: `settings` (V001, one row per player and setting; no schema change).

## Kinds of settings

| Kind | Stored as | Dialog input | Notes |
|---|---|---|---|
| `Toggle` | `true`/`false` (on/off, yes/no and 1/0 are read too) | switch | the classic switch; `register(toggle)` and `set(uuid, toggle, on)` still work |
| `Choice` | the option id | cycling button (single option) | up to 6 options; only options the player may pick are shown; an option may need a permission or depend on the server (`optionAvailableWhen`) and then reads as its `unavailableAs` fallback |
| `NumberSetting` | the number | slider (number range) with the unit in its label | whole numbers, a whole number of steps, within 2^24 (exact as a float), at most 1000 steps; stored numbers outside the range are brought into range and onto the nearest step |

A toggle can become a choice under the same id: `Choice.Builder.legacyValue("true", "chat")` makes old rows (and config
entries) read as that option. A setting can take over a retired id: `SettingOptions.legacy("old-id", value -> ...)`
moves the old rows when a player loads. While the old id is still registered as a setting of its own, the new one is
not offered and nothing moves (two controls for one thing would contradict each other).

## Groups

Every setting is in one of the shared groups (`SettingCategories`), in this order: Chat, Friends & teams, Server
announcements, Sounds, Teleports & homes, Money & selling, Shop, auction & orders, Combat & stats, Display, Privacy,
AFK & shards, Crates & kits, Spawners, Staff, then General for settings registered without a group. Each group has an
icon (`icons.yml`: chat, social, bell, sound, teleport, economy, auction, kill, display, privacy, afk, crates, spawners,
staff). A group shows only when the player can see a setting in it. `search`, `changed`, `reset` and `all` can't be
group ids.

## Shared settings

Settings several features read are defined by core (`SharedSettings`), so no feature imports another's constants:

| Id | Kind | Group | What it does |
|---|---|---|---|
| `sound-volume` | number 0-100, step 10 (%) | Sounds | scales every SiftCore sound; 0 mutes them |
| `sound-notify` | toggle, on | Sounds | notification pings |
| `sound-mention`, `sound-pm` | choice default/bell/pling/chime/off | Sounds | ping sound of mentions and private messages |
| `sound-clicks`, `sound-success`, `sound-errors` | toggle, on | Sounds | menu clicks, success chimes, error notes |
| `sound-team-chat` | choice off/default/bell/pling/chime | Sounds | ping when a teammate writes in team chat |
| `quiet-in-combat` | toggle, off | Combat & stats | while tagged: no pings or chimes, pop-up alerts become chat lines |
| `feedback-channel` | choice actionbar/chat/both | Display | where short results and errors appear |
| `hide-coordinates` | toggle, off | Privacy | streamer mode |
| `seen-privacy`, `balance-privacy` | choice everyone/friends/nobody | Privacy | who sees last-seen time and balance ("friends" reads as nobody without a friends system) |
| `hide-from-leaderboards` | toggle, off, `siftcore.stats.hide` | Privacy | leave every leaderboard |
| `friends-tpa` | choice nobody/favourites/all/friends-team | Teleports & homes | whose `/tpa` is accepted without asking (takes over `tpa-friends`) |
| `sell_receipts` | choice chat/actionbar/off | Money & selling | sale receipts (was a switch: on reads as chat, off as actionbar) |

Core applies the sound settings (`Sounds`), quiet in combat and the feedback channel (`Messenger`) itself. The others
only take effect in a feature's code, so they are offered once a feature declares it reads them
(`services.settings().reads(SharedSettings.X)`); until then they stay out of the dialog. Selling reads
`sell_receipts`.

## Delivery (core)

- `Messenger.send` and `actionbar` send success and error lines on the action bar where the player's
  `feedback-channel` says (hotbar, chat or both). Status lines (`MessageKey.status`: the combat timer, the teleport
  countdown, the AFK zone countdown, the vanish reminder, the keyall countdown) always stay on the action bar, and
  so do refusals an event repeats many times a second (`MessageKey.error(...).asStatus()`: walking into spawn while
  tagged), which keep their error colours and sound. An error moved to chat that the player keeps triggering
  (holding the use key on a protected block, swinging while frozen) shows in chat once per burst; it shows again
  after a two-second pause. The action bar copy and the sound are unchanged.
- `Messenger.alert(player, style, key, args)` delivers a notification in the `AlertStyle` a feature's setting chose
  (chat, action bar, title, both, off), whatever the feedback channel. With quiet in combat on and the player tagged,
  action bar and title alerts become a chat line; combat's own alerts pass `quiet = false`. Sale receipts use it: the
  hotbar style of `sell_receipts` stays above the hotbar, and becomes a chat line in combat with quiet in combat on.
- `Sounds.play` applies the volume, the kind switches and quiet in combat; `Sounds.ping(player, PingSound)` plays the
  ping sounds (`config.yml` `sounds.pings`; Default is the notify sound and follows Notification pings).
- `StatusBars` keeps one boss bar per player for lasting status lines; the highest priority owner shows (combat
  `PRIORITY_COMBAT`, the AFK countdown `PRIORITY_IDLE`, a running sell booster for everyone `PRIORITY_SERVER`).
- `Relations` (`services.relations()`) answers who-can settings (`allows(audience, owner, other)`), friends and
  favourites availability, and ignore lists.

## Storage

Values are loaded at login (off-thread, read in the database writer's order so a staff change queued just before is
included and one queued just after reaches the loaded values), changed through `PlayerSettings`, written through at
once and forgotten on quit. A reconnect that replaces a session (the new login loads before the old session quits)
keeps the new login's values; a login read that comes after the player left is dropped, and one that comes after they
already joined (a slow database) fills in only what they did not change meanwhile. **Storing the default deletes the
row**: a player who picks the default follows the server's default from then on, so a later change of the default (in
`features/settings.yml`, or in code) reaches them. Rows written before this rule that equal the default count as
unchanged and go on the next change. Rows of ids nobody registered (disabled
features, remembered sort orders) are never touched. `lookup(uuid, setting)` reads one setting of an offline player
(after every queued write), with the server's locks and defaults.

## Server overrides (`features/settings.yml`)

| Key | Default | Meaning |
|---|---|---|
| `page-size` | 8 | settings per page (1-20) |
| `skip-single-group` | true | open the only group directly |
| `hidden` | `[]` | setting ids left out of the dialog, commands and placeholders; everyone reads their lock, else their server default, else the code default, and changes are refused (`NOT_ALLOWED`). Stored choices are kept and come back when the setting is shown again |
| `defaults` | not set | a section of `setting-id: value` for players who never changed it |
| `locked` | not set | a section of `setting-id: value` forced on everyone; shown as "set by the server", refused as `LOCKED` |

`defaults` and `locked` are not in the shipped file (the comment above `hidden` shows them): synced config files only
gain value keys, so an empty section would never reach an existing server. Add them when needed. Values are
`true`/`false`, an option id (legacy values work) or a whole number in range and on a step. Unknown ids, bad values and
a setting both defaulted and locked are logged at startup (a second after all features started) and on
`/sift reload`, and reported by the self-test; bad entries are not applied. A hidden setting that is also locked or
defaulted is fine: that is how a value is fixed out of sight. Settings are registered when their feature starts (also
ones only offered under a condition, like `sell_orders` while there are buy orders), so overrides can always name
them.

## Opening the dialog

- `/settings [group]` (aliases `/options`, `/preferences`), permission `siftcore.command.settings` (everyone).
- The main menu entry `settings` and the pause screen (`siftcore:hub/settings`), with Back to the menu.
- Features open a group with `services.settings().screens().open(player, groupId, back)`.

A group's page shows each setting's label and description, then its input. Settings the server locked show as text.
Changes on one page are kept while paging and saved together; Back leaves without saving.

## Saving

Save stores only what the player changed in the dialog (a value changed elsewhere while it was open is never
overwritten). Each change goes through the registry: still offered, the player still has the permissions, not locked,
not cancelled by a `SettingChangeEvent` listener. Messages: `Mention alerts turned off.` (a switch), `SiftCore volume
set to 60%.` (a choice or number), `Saved 2 settings.`, `Nothing changed.`, and `Success chimes couldn't be changed.`
for a refused change.

## Permissions

- `siftcore.command.settings` (everyone): `/settings`.
- `siftcore.stats.hide` (nobody by default; give it to staff and test accounts): offers Hide me from leaderboards.
- `siftcore.settings.hide-rank` (nobody by default; give it to the rank groups): offers Show my rank (once the rank
  feature reads it).
- `siftcore.admin.settings` (operators): reserved for the staff tools for other players' settings (`/sift settings`),
  which are not in this version yet; nothing checks it so far.

## Self-test

- every setting, option, unit and group has text; every group icon resolves;
- every shared setting is registered and dialog input keys are valid and unique;
- groups hold at most 15 settings, and at least 4 once no setting is left in General;
- every shared setting is read by a feature (once no setting is left in General);
- the overrides in `features/settings.yml` name real settings and values;
- the hub entry is registered.

## For feature authors

```java
public static final Choice<AlertStyle> SALE_ALERTS = Choices.alert("auction-sales", AlertStyle.CHAT,
        AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
    .legacyValue("true", "chat").legacyValue("false", "off")
    .text(AuctionMessages.SETTING_SALES, AuctionMessages.SETTING_SALES_DESCRIPTION).build();

services.settings().register(SettingCategories.MARKET, SALE_ALERTS, SettingOptions.<AlertStyle>builder().order(1).build());
...
services.messenger().alert(seller, services.settings().get(seller, SALE_ALERTS), AuctionMessages.SOLD, args);
```

Use the shared vocabularies (`Choices.alert`, `audience`, `ping`, `confirmAbove`, `announce`), `availableWhen` for
settings that depend on config, `SettingOptions.instant(hook)` for settings that must apply at once (the hook runs on
the player's thread), and `placeholder(false)` for privacy settings.
