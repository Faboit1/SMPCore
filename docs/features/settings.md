# Settings (`settings` and `core/player`)

One place for every per-player setting on the server: a dialog with groups, pages, search, a summary of what the
player changed and resets; the `/settings` command words; the staff tools `/sift settings`; placeholders; and the
public `SettingsView`. The model and storage live in core (`core/player`: `PlayerSettings`, `PlayerSetting`,
`Toggle`, `Choice`, `NumberSetting`, `SettingCategories`, `SharedSettings`); everything players and staff use lives in
`feature/settings`. Text: `lang/settings.yml` (dialog, commands, staff tools, groups, shared option names) and
`lang/core.yml` (`shared-settings`). Config: `features/settings.yml`. Table: `settings` (V001, one row per player and
setting; no schema change). Every setting is listed at the end ([Settings catalog](#settings-catalog)).

## Kinds of settings

| Kind | Stored as | Dialog input | Notes |
|---|---|---|---|
| `Toggle` | `true`/`false` (on/off, yes/no and 1/0 are read too) | switch | the classic switch; `register(toggle)` and `set(uuid, toggle, on)` still work |
| `Choice` | the option id | cycling button (single option) | up to 6 options; only options the player may pick are shown; an option may need a permission or depend on the server (`optionAvailableWhen`) and then reads as its `unavailableAs` fallback |
| `NumberSetting` | the number | slider (number range) with the unit in its label | whole numbers, a whole number of steps, within 2^24 (exact as a float), at most 1000 steps; stored numbers outside the range are brought into range and onto the nearest step |

A toggle can become a choice under the same id: `Choice.Builder.legacyValue("true", "chat")` makes old rows (and config
entries) read as that option. A setting can take over a retired id: `SettingOptions.legacy("old-id", value -> ...)`
moves the old rows when a player loads, and a change or reset written for a player who is not loaded deletes them with
it. While the old id is still registered as a setting of its own, the new one is not offered and nothing moves (two
controls for one thing would contradict each other).

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

Core also defines `money-format` (choice server/full/short, default server, Display; `core.text.MoneyDisplay`, text
in `lang/core.yml` under `money-format`), which `Lang` applies to every amount rendered for a player (see Delivery).
Each option's label carries a sample of $1,234,567 written that way ("The server's way ($1.23m)", "In full
($1,234,567)", "Short ($1.2m)"). An option that writes every amount the server's way under the current `currency`
section is not offered: "In full" while `compact-from` is 0 (the server already writes every digit), "Short" while
the server already shortens from the smallest suffix with at most one decimal (`compact-from` at or below 1000 and
`compact-decimals` at most 1); with neither left the setting is hidden. A player who picked one reads the server's
way meanwhile, and gets their choice back once the config offers it again (`/sift reload`).

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
- Money follows each reader's `money-format`: the server's way (`config.yml` `currency`: in full below
  `compact-from`, short above it), always in full ($1,234,567), or short from the smallest suffix on ($1.2m, $15.5k: at
  most one decimal, rounded down). `Lang` writes `Arg.money`, `Arg.amount` (money) and `lang.money`/`moneyComponent`
  in the format of the viewer whose scope the thread renders in (`lang.viewing(player, ...)`); outside any scope it is
  the server's way. The messenger renders every message in its recipient's scope (and `broadcast` once per format),
  menus draw and handle clicks in their viewer's scope, dialog clicks and pause-menu routes run in the clicking
  player's scope, and commands in their sender's. Screens built after a database read or another wait (a crate's
  result, `/stats` of an offline player, an order's details) run outside those scopes, so they are built in the
  player's scope explicitly (`dialogs().show(player, () -> view)`, `lang.viewing(player, ...)`). Confirmations always
  show every digit: money in a message whose key path has a `confirm` part (`shop.confirm.body`,
  `economy.pay.confirm-body`) and `Arg.exact` amounts are written in full whatever the reader chose. So are the amounts
  on buttons that pay or charge at once ("Sell for", "Buy 64 for", "Deliver 64 for", the sell menu's and spawner
  storage's Sell button): the amount a click agrees to. The hover card on a chat name shows the sender's balance in
  each reader's format. SiftCore's money placeholders follow the player PlaceholderAPI asks for (see
  `docs/placeholders.md` for who that is in TAB and chat plugins); without a player (holograms) they are the server's
  way, and `balance_server` is the server's way for everyone.
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
| `show-descriptions` | true | one line per setting above the inputs saying what it does; false gives compact pages (only the inputs, whose labels stay; a setting that takes effect after a rejoin still says so) |
| `hidden` | `[]` | setting ids left out of the dialog, commands, searches and placeholders; everyone reads their lock, else their server default, else the code default, and changes are refused (`NOT_ALLOWED`). Stored choices are kept and come back when the setting is shown again |
| `defaults` | not set | a section of `setting-id: value` for players who never changed it |
| `locked` | not set | a section of `setting-id: value` forced on everyone; shown as "set by the server", refused as `LOCKED` (also by `/settings` and `/sift settings`) |
| `categories` | not set | a section of `<group id>: { order: <number>, icon: <icon name> }`: moves a group in the group list or gives it another `icons.yml` icon (both keys optional; the built-in orders are chat 10, social 20, announcements 30, sound 40, teleport 50, economy 60, market 70, combat 80, display 90, privacy 100, afk 110, crates 120, spawners 130, staff 900) |

`defaults`, `locked` and `categories` are not in the shipped file (comments show them): synced config files only gain
value keys, so an empty section would never reach an existing server. Add them when needed. Values are
`true`/`false`, an option id (legacy values work) or a whole number in range and on a step. Unknown ids, bad values,
a setting both defaulted and locked, groups that don't exist and icons `icons.yml` lacks are logged at startup (a
second after all features started) and on `/sift reload`, and reported by the self-test; bad entries are not applied.
A hidden setting that is also locked or defaulted is fine: that is how a value is fixed out of sight. Settings are
registered when their feature starts (also ones only offered under a condition, like `sell_orders` while there are
buy orders), so overrides can always name them.

## The dialog

Opened with `/settings` (aliases `/options`, `/preferences`; permission `siftcore.command.settings`, everyone), from
the main menu entry `settings` and the pause screen (`siftcore:hub/settings`, with Back to the menu), and by features
with `services.settings().screens().open(player, groupId, back)`. Everything is built from the registry when it
opens, so a setting a feature registers appears by itself.

**Buttons.** Every button shows the next screen in place: the dialog stays until the next one arrives (no waiting
screen). Save closes the dialog when there is nothing to go back to (a group opened directly when the player sees only
one), and Search shows the client's waiting screen while the results are built. Errors (a value the router refused,
an empty search) show in red on the same dialog with what the player typed kept.

**The group list** ("Settings"): one line per group the player sees, with its icon, description and how many settings
it holds for them (`Chat: Mentions, private messages and what you see in public chat (3)`), and "You changed 2 of 38
settings." Buttons: one per group (its tooltip repeats the description and the counts), **Search settings**, and
**Changed settings (n)** once the player changed something. A group shows only when the player can see a setting in
it (permission, the server's `hidden` list, and whether its feature offers it now). With `skip-single-group` the only
group opens directly.

**A page** ("Chat settings", 8 settings per page by default): each setting's label and what it does, then one input
per setting: a switch (toggle), a cycling button (choice: only the options the player may pick, by permission and by
what the server offers) or a slider (number: its unit in the label, like "SiftCore volume (%)", because Bedrock
forms show only the label). A setting the server locked shows its value as text, "(set by the server)". Previous page
and Next page carry the changes made so far ("Changes on other pages: 2, saved with this page."); Back leaves without
saving. **Reset this category** shows when the player changed something in the group.

**Saving** stores only what the player changed in the dialog (a value changed elsewhere while it was open is never
overwritten). Each change goes through the registry: still offered, the player still has the permissions, not
locked, not cancelled by a `SettingChangeEvent` listener. Messages (above the hotbar, or wherever the player's
feedback channel says): `Mention alerts turned off.` (a switch), `SiftCore volume set to 60%.` (a choice or number),
`Saved 2 settings.`, `Nothing changed.`, and `Success chimes couldn't be changed.` (red) for a refused change. Save
returns to the screen the page was opened from.

**Resets.** Reset this category asks "Reset Sounds settings?" and lists each changed setting as
`SiftCore volume: 30% to 100%`; Reset puts them back to their defaults (their rows are deleted, so the player follows
the server's default from then on) and shows the group's first page with the defaults and nothing pending (changes
not saved yet are dropped too); Cancel returns to the page with the unsaved changes kept. **Changed settings** lists every setting the player changed, `Sounds > SiftCore volume: 30% (default 100%)`,
with a button per group and **Reset everything** (asks first, then resets every setting the player sees and changed;
locked settings are never touched).

**Search** ("Search settings"): one text field, up to 32 characters. A setting matches when every word typed occurs in
its label, description, id or short name, its group's name, one of its option labels, its unit or its extra search
words (`SettingOptions.keywords`); case and punctuation don't matter and part of a word is enough (`vol`). Results are
ranked: label starting with the query, then label holding every word, then the rest, each in dialog order. They show
as pages like a group's (lines name the group, `Sounds > SiftCore volume: ...`), with paging, pending changes and Save;
Back returns to the form with the query kept. No match shows "No setting matches zzz." with Back to the form.

## /settings

| Command | Does |
|---|---|
| `/settings` | the group list |
| `/settings <group>` | the group's first page (`There is no settings group called x.` otherwise) |
| `/settings <group> <setting>` | `SiftCore volume: 30% (default 100%). Values: a whole number from 0 to 100 in steps of 10` in chat; clicking the line opens the page that holds the setting |
| `/settings <group> <setting> <value>` | changes it: `on`/`off` (also true/false, yes/no, 1/0) or `toggle` for a switch, an option id or an option's label for a choice (`above the hotbar`), a number in range and on a step for a slider (`60` or `60%`) |
| `/settings <setting> [value]` | the same without the group: the setting's id (`sound-volume`), its dialog input key (`sound_volume`) or a short name only one visible setting has (`volume`) |
| `/settings search <words>` | the search results |
| `/settings changed` | the changed settings |
| `/settings reset [<group>\|all]` | the reset confirmation, or "You use the defaults for every Sounds setting." |

**Resolution order** (`SettingsArgs`, unit tested): the words `search`, `changed`, `reset` and `all` are the command's
own (no group may be called that); then a group the player sees; then a setting the player sees by id, input key or
unique short name. After a group, the second word is a setting of that group by id, short name (the id without the
group's prefix, `volume` for `sound-volume` in Sounds) or input key. Settings and groups the player can't see answer
like unknown ones, so a staff setting's name never leaks. A single unknown word answers `There is no settings group
called x.`; an unknown first word followed by more (`/settings x on`) answers `There is no setting or settings group
called x.`. The command's own words typed in capitals (`/settings Search volume`) do what they do in lower case.

**Results:** `SiftCore volume set to 30%.`, `Notification pings turned off.`, `SiftCore volume is already 30%.`, and in
red `For SiftCore volume, use a whole number from 0 to 100 in steps of 10.`, `For Quick results and errors, use
actionbar, chat, both.`, `You can't pick both for X.` (an option the player lacks), `Quiet during combat is set by the
server.`, `X couldn't be changed.` (a listener cancelled it), `There is no setting called x in Sounds.`

**Suggestions** (tab completion, from the registry snapshot and the player's values; thread-safe): the first word
suggests the groups the player sees (plus `search`, `changed` and `reset`) and, once two characters are typed, the
ids of the settings they may change; after a group, the short names; for a value: on, off and toggle, the option ids
the player may pick, or a number's minimum, default and maximum and then the steps starting with what was typed (at
most 15). Locked and hidden settings are never suggested.

## Staff tools: /sift settings

Permission `siftcore.admin.settings` (operators). Console friendly, works for offline players, every change audited.

| Command | Does |
|---|---|
| `/sift settings <player>` | `Settings of Steve: 2 changed` and one line per changed setting (`- sound-volume: 30 (default 100)`); stored values that don't apply (locked, hidden) are marked; other stored values (remembered sort orders and similar UI state) are named |
| `/sift settings <player> <setting>` | the value, default and where it comes from (their choice, the server's default, the built-in default, the server's lock, or the server because it hides it), its group, kind and values, and the permission it needs with whether the player has it |
| `/sift settings <player> <setting> <value>` | changes it (`reset` puts it back to the default); no permission needed, but staff are warned when an online player lacks the setting's permission (they read the default until they get it) |
| `/sift settings <player> reset [<setting>\|<group>\|all]` | deletes the player's rows of those settings (all: every setting; UI state stays). A locked setting named on its own is refused (`quiet-in-combat can't be changed: the server locked it.`); a group or `all` leaves locked ones alone and names them (`Reset 0 settings of Steve; 1 locked by the server stay as they are (quiet-in-combat).`) |
| `/sift settings catalog` | writes every setting as a Markdown table to `plugins/SiftCore/docs/settings.md` (the [catalog](#settings-catalog) below) |

Every setting is reachable here, also ones that need a permission or are not listed in the dialog. Reads go to the
database after every queued write, so they are exact for offline players; a change for an offline player is written to
the table and applies when they join (a login racing the write gets it too). Changes report `SettingChangeEvent` with
the cause `ADMIN` (not cancellable) and are written to the audit log as `settings.set` (`sound-volume: 30 -> 70`) and
`settings.reset` (the ids), with the staff member as the actor. Settings that apply at once (an instant hook) run it
on the player's thread.

A row stored under a setting's old id (`SettingOptions.legacy`, like `tpa-friends` for `friends-tpa` once TPA reads
the choice) is that setting's value until the player logs in and core moves it: the list shows it under the new id,
the detail names it as their choice, and a reset includes it. Core deletes the old row in the same write as any change
or reset for a player who is not loaded (by staff, the API or a feature), so the next login can't move it back over
the change; a login whose read was already queued keeps the change too. Staff changes, resets and lookups, and the
API's audit rows, are applied in the order they were made, also for offline players whose previous values are read
from the database: each staff command reads only after the commands before it queued their writes (`StaffChanges`
over `InOrder`; `InOrderTest`, `SettingsAdminTest` and the `settings-admin` e2e scenario, which holds the database
writer so both commands' work queues back to back), so two commands for the same offline player in one tick (a
console script) see each other, and a reset right after a change finds the changed row.

## Placeholders

| Placeholder | Shows |
|---|---|
| `%siftcore_setting_<id>%` | the player's value as stored: `true`/`false`, an option id, a number (`%siftcore_setting_sound-volume%` is `60`) |
| `%siftcore_settingtext_<id>%` | the value as players read it: `on`, `Everyone`, `60%` |
| `%siftcore_settings_changed%` | how many of the settings the player sees they changed |

An unknown id is left to PlaceholderAPI (no value). Privacy settings and settings that need a permission keep their
value private (`SettingOptions.placeholder(false)`, the default for permission settings): they show an empty text, and
so do settings the server hides. Players who are not online read the defaults (resolvers never touch the database).

## API and events

`SiftCoreApi#settings()` gives the `SettingsView` (also in the `ServicesManager`): groups and settings as plain text in
the dialog's order with its icons (the server's `categories` overrides applied), a player's values, `stored(uuid)`
(offline too), `set` and `reset` with results. API changes and resets are written to the audit log like staff ones
(`settings.set` / `settings.reset`, `sound-volume: 100 -> 20`, actor `api:<actor>` or `api`; the value before is read
from the database for an offline player). `SettingChangeEvent` fires before
every real change with the setting, group, old and new value, cause (`DIALOG`, `COMMAND`, `FEATURE`, `ADMIN`, `API`,
`RESET`) and actor; listeners may cancel the player's own changes (`DIALOG`, `COMMAND`) and API changes. See
`docs/api.md`.

## Permissions

- `siftcore.command.settings` (everyone): `/settings`.
- `siftcore.admin.settings` (operators): `/sift settings`.
- `siftcore.stats.hide` (nobody by default; give it to staff and test accounts): offers Hide me from leaderboards.
- `siftcore.settings.hide-rank` (nobody by default; give it to the rank groups): offers Show my rank (once the rank
  feature reads it).

## Self-test

Core:
- every setting, option, unit and group has text; every group icon resolves;
- every shared setting is registered;
- groups hold at most 15 settings, and at least 4 once no setting is left in General;
- every shared setting is read by a feature (once no setting is left in General).

Settings feature:
- dialog input keys are valid and unique;
- the overrides in `features/settings.yml` name real settings, values, groups and icons;
- every page of every group, the group list, the changed summary and the search form build (for someone with every
  permission), and no page holds more inputs than `page-size`;
- every setting is found by searching its label;
- `/settings <group> <short name>` and `/settings <id>` name each setting exactly once;
- the placeholders resolve, and private settings show nothing;
- the settings API is registered, and so is the hub entry.

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
the player's thread), and `placeholder(false)` for privacy settings. `keywords(key)` adds words the search finds the
setting by (a lang text such as "ping sound noise"). Nothing else is needed: the dialog, `/settings`, the staff tools,
the placeholders and the API pick a registered setting up by themselves. A command that flips a setting itself (like
`/msgtoggle`) should use `set(player, setting, value, change)` and report its `SetResult` (locked, hidden), the way
`/settings` does.

## Settings catalog

Every player setting SiftCore 1.0.0 registers (133), generated with `/sift settings catalog`. Values are what `/settings`, `/sift settings` and `features/settings.yml` take; the default is the built-in one (the server can change it).

| Group | Id | Kind | Values | Default | Label | Permission | Notes |
|---|---|---|---|---|---|---|---|
| Chat (`chat`) | `mentions` | choice | actionbar, chat, title, off | `actionbar` | Mention alerts |  |  |
| Chat (`chat`) | `private-messages` | choice | everyone, friends-team, friends, nobody | `everyone` | Who can message me |  |  |
| Chat (`chat`) | `public-chat` | toggle | true, false | `true` | Show public chat |  |  |
| Chat (`chat`) | `pm-alert` | choice | off, actionbar, title | `off` | Private message pop-up |  |  |
| Chat (`chat`) | `mention-from` | choice | everyone, friends-team, friends | `everyone` | Who can ping me |  |  |
| Chat (`chat`) | `mention-highlight` | choice | bold, underline, off | `bold` | Highlight my name |  |  |
| Chat (`chat`) | `chat-filter-strict` | toggle | true, false | `false` | Strict word filter |  |  |
| Chat (`chat`) | `reply-target` | choice | last-conversation, last-received | `last-conversation` | /r replies to |  |  |
| Chat (`chat`) | `mention-plain-names` | toggle | true, false | `true` | Ping on plain name |  |  |
| Chat (`chat`) | `chat-hide-new` | toggle | true, false | `false` | Hide brand-new players |  |  |
| Chat (`chat`) | `show-chat-colors` | toggle | true, false | `true` | Chat colours |  |  |
| Friends & teams (`social`) | `friends-requests` | choice | everyone, known, nobody | `everyone` | Friend requests from |  |  |
| Friends & teams (`social`) | `friends-join-alerts` | choice | all, favourites, off | `all` | Friend join alerts |  |  |
| Friends & teams (`social`) | `team-notices` | choice | chat, actionbar, off | `chat` | Team news |  |  |
| Friends & teams (`social`) | `friends-request-alerts` | toggle | true, false | `true` | Friend request alerts |  |  |
| Friends & teams (`social`) | `team-member-alerts` | choice | joins-and-leaves, joins, off | `joins` | Teammate login alerts |  |  |
| Friends & teams (`social`) | `team-invites` | choice | everyone, friends, nobody | `everyone` | Team invites from |  |  |
| Friends & teams (`social`) | `friends-announce` | toggle | true, false | `true` | Tell friends when I join |  |  |
| Friends & teams (`social`) | `friends-join-summary` | toggle | true, false | `true` | Friends summary on join |  |  |
| Friends & teams (`social`) | `friends-leave-alerts` | toggle | true, false | `false` | Friend leave alerts |  |  |
| Friends & teams (`social`) | `friends-list-order` | choice | status, name, last-seen, oldest | `status` | Sort friends by |  |  |
| Friends & teams (`social`) | `team-chat-sticky` | toggle | true, false | `false` | Remember team chat mode |  |  |
| Server announcements (`announcements`) | `death-messages` | choice | all, pvp, friends-team, off | `all` | Death messages |  |  |
| Server announcements (`announcements`) | `join-leave-messages` | choice | all, first-joins, off | `all` | Join and leave messages |  |  |
| Server announcements (`announcements`) | `crate-wins` | choice | all, rarest, off | `all` | Crate win announcements |  |  |
| Server announcements (`announcements`) | `bounty-announcements` | choice | all, 100k, 1m, 10m, off | `all` | Bounty announcements |  |  |
| Server announcements (`announcements`) | `orders_announce` | choice | all, 1m, 10m, 100m, off | `all` | Big order announcements |  |  |
| Server announcements (`announcements`) | `kill-streak-announcements` | toggle | true, false | `true` | Kill streak announcements |  |  |
| Server announcements (`announcements`) | `combat-log-announcements` | toggle | true, false | `true` | Combat log announcements |  |  |
| Server announcements (`announcements`) | `booster-announcements` | choice | all, starts, off | `all` | Sell booster announcements |  |  |
| Sounds (`sound`) | `sound-volume` | number | 0-100 step 10 (%) | `100` | SiftCore volume |  |  |
| Sounds (`sound`) | `sound-notify` | toggle | true, false | `true` | Notification pings |  |  |
| Sounds (`sound`) | `sound-mention` | choice | default, bell, pling, chime, off | `default` | Mention sound |  |  |
| Sounds (`sound`) | `sound-pm` | choice | default, bell, pling, chime, off | `default` | Private message sound |  |  |
| Sounds (`sound`) | `sound-clicks` | toggle | true, false | `true` | Menu click sounds |  |  |
| Sounds (`sound`) | `sound-success` | toggle | true, false | `true` | Success chimes |  |  |
| Sounds (`sound`) | `sound-errors` | toggle | true, false | `true` | Error sounds |  |  |
| Sounds (`sound`) | `sound-team-chat` | choice | off, default, bell, pling, chime | `off` | Team chat sound |  |  |
| Teleports & homes (`teleport`) | `tpa-requests` | choice | everyone, friends-team, friends, nobody | `everyone` | Teleport requests from |  |  |
| Teleports & homes (`teleport`) | `friends-tpa` | choice | nobody, favourites, all, friends-team | `nobody` | Auto-accept /tpa from |  |  |
| Teleports & homes (`teleport`) | `homes-confirm-overwrite` | toggle | true, false | `true` | Confirm moving a home |  |  |
| Teleports & homes (`teleport`) | `teleport-display` | choice | actionbar, title, chat, off | `actionbar` | Teleport countdown |  |  |
| Teleports & homes (`teleport`) | `homes-bare-command` | choice | smart, default-home, list | `smart` | /home with no name |  |  |
| Teleports & homes (`teleport`) | `tpahere-requests` | choice | everyone, friends-team, friends, nobody | `everyone` | Pull requests from |  |  |
| Teleports & homes (`teleport`) | `tpa-popup` | toggle | true, false | `false` | Requests open a pop-up |  |  |
| Teleports & homes (`teleport`) | `tpaccept-confirm-here` | toggle | true, false | `true` | Confirm before being pulled |  |  |
| Teleports & homes (`teleport`) | `rtp-confirm-cost` | toggle | true, false | `true` | Confirm paid random teleports |  |  |
| Teleports & homes (`teleport`) | `rtp-default` | choice | menu, last | `menu` | /rtp with no region |  |  |
| Money & selling (`economy`) | `sell_receipts` | choice | chat, actionbar, off | `chat` | Sale receipts |  |  |
| Money & selling (`economy`) | `pay-notifications` | choice | chat, actionbar, off | `chat` | Payment alerts |  |  |
| Money & selling (`economy`) | `sell_all_confirm` | choice | server, always, 10k, 100k, 1m, never | `server` | Confirm /sell all from |  |  |
| Money & selling (`economy`) | `sell-all-hotbar` | choice | server, keep, sell | `server` | Hotbar on /sell all |  |  |
| Money & selling (`economy`) | `sell_orders` | toggle | true, false | `true` | Sell to buy orders first |  |  |
| Money & selling (`economy`) | `pay-confirm-above` | choice | server, always, 1k, 10k, 100k | `server` | Confirm payments from |  |  |
| Money & selling (`economy`) | `pay-accept-from` | choice | everyone, friends-team, friends, nobody | `everyone` | Who can pay me |  |  |
| Money & selling (`economy`) | `pay-join-summary` | toggle | true, false | `true` | Offline payments summary |  |  |
| Money & selling (`economy`) | `pay-alert-minimum` | choice | any, 100, 1k, 10k, 100k | `any` | Only alert payments from |  |  |
| Money & selling (`economy`) | `sell-all-shulkers` | toggle | true, false | `true` | /sell all opens shulker boxes |  |  |
| Money & selling (`economy`) | `sell-menu-close` | choice | return, sell | `return` | Closing the sell menu |  |  |
| Money & selling (`economy`) | `mastery-levelup` | choice | chat, actionbar, title, off | `chat` | Sell mastery level-ups |  |  |
| Shop, auction & orders (`market`) | `auction-sales` | choice | chat, actionbar, off | `chat` | Auction sale alerts |  |  |
| Shop, auction & orders (`market`) | `order-notices` | choice | chat, actionbar, complete, off | `chat` | Order delivery alerts |  |  |
| Shop, auction & orders (`market`) | `shop-confirm-above` | choice | server, always, 10k, 100k, 1m, never | `server` | Confirm purchases from |  |  |
| Shop, auction & orders (`market`) | `auction-join-summary` | toggle | true, false | `true` | Auction summary on join |  |  |
| Shop, auction & orders (`market`) | `order-join-summary` | toggle | true, false | `true` | Order summary on join |  |  |
| Shop, auction & orders (`market`) | `shop-default-amount` | choice | stack, one, last, fill | `stack` | Buy window starts at |  |  |
| Shop, auction & orders (`market`) | `order-ending-alerts` | toggle | true, false | `true` | Order ending warnings |  |  |
| Shop, auction & orders (`market`) | `auction-price-warning` | toggle | true, false | `true` | Low price warning |  |  |
| Shop, auction & orders (`market`) | `shop-receipts` | choice | chat, actionbar | `chat` | Purchase receipts |  |  |
| Shop, auction & orders (`market`) | `order-auto-collect` | toggle | true, false | `false` | Auto-collect deliveries |  |  |
| Shop, auction & orders (`market`) | `auction-expiry-alerts` | choice | chat, actionbar, off | `chat` | Expired listing alerts |  |  |
| Shop, auction & orders (`market`) | `auction-hide-own` | toggle | true, false | `false` | Hide my own listings |  |  |
| Combat & stats (`combat`) | `combat-timer-display` | choice | actionbar, bossbar, both, off | `actionbar` | Combat timer |  |  |
| Combat & stats (`combat`) | `combat-tag-alert` | choice | chat, actionbar, title, off | `chat` | Entering combat alert |  |  |
| Combat & stats (`combat`) | `kill-feedback` | choice | actionbar, chat, title, off | `actionbar` | Kill confirmation |  |  |
| Combat & stats (`combat`) | `death-coordinates` | toggle | true, false | `true` | Death location |  |  |
| Combat & stats (`combat`) | `combat-end-notice` | choice | actionbar, chat, title, off | `actionbar` | Combat ended notice |  |  |
| Combat & stats (`combat`) | `bounty-target-alert` | choice | chat, actionbar, title, off | `chat` | Bounty on you alert |  |  |
| Combat & stats (`combat`) | `quiet-in-combat` | toggle | true, false | `false` | Quiet during combat |  |  |
| Combat & stats (`combat`) | `death-recap` | toggle | true, false | `true` | Death recap |  |  |
| Combat & stats (`combat`) | `leaderboard-rank-alerts` | choice | top-10, all, off | `top-10` | Leaderboard climb alerts |  |  |
| Combat & stats (`combat`) | `bounty-confirm-above` | choice | server, always, 10k, 100k, 1m | `server` | Confirm bounties from |  |  |
| Combat & stats (`combat`) | `bounty-join-reminder` | toggle | true, false | `true` | Bounty reminder on join |  |  |
| Display (`display`) | `scoreboard` | toggle | true, false | `true` | Sidebar |  | applies at once |
| Display (`display`) | `feedback-channel` | choice | actionbar, chat, both | `actionbar` | Quick results and errors |  |  |
| Display (`display`) | `sidebar-layout` | choice | full, compact, combat | `full` | Sidebar lines |  | applies at once |
| Display (`display`) | `money-format` | choice | server, full, short | `server` | Money format |  |  |
| Display (`display`) | `show-spawn-holograms` | toggle | true, false | `true` | Spawn holograms |  | not offered now (its feature is off or does not read it yet); applies at once |
| Display (`display`) | `booster-bar` | toggle | true, false | `true` | Booster bar |  | applies at once |
| Display (`display`) | `show-kill-effects` | toggle | true, false | `true` | Kill effects |  |  |
| Privacy (`privacy`) | `hide-coordinates` | toggle | true, false | `false` | Streamer mode: hide coordinates |  |  |
| Privacy (`privacy`) | `seen-privacy` | choice | everyone, friends, nobody | `everyone` | Who sees when I was last online |  |  |
| Privacy (`privacy`) | `balance-privacy` | choice | everyone, friends, nobody | `everyone` | Who can see my balance |  |  |
| Privacy (`privacy`) | `order-announce-mine` | toggle | true, false | `true` | Announce my big orders |  |  |
| Privacy (`privacy`) | `show-my-rank` | toggle | true, false | `true` | Show my rank | `siftcore.settings.hide-rank` |  |
| Privacy (`privacy`) | `hide-from-leaderboards` | toggle | true, false | `false` | Hide me from leaderboards | `siftcore.stats.hide` |  |
| AFK & shards (`afk`) | `afk-zone-status` | choice | actionbar, bossbar, off | `actionbar` | AFK zone countdown |  |  |
| AFK & shards (`afk`) | `afk-zone-payouts` | choice | actionbar, chat, off | `actionbar` | AFK zone payout messages |  |  |
| AFK & shards (`afk`) | `afk-kick-warning` | choice | chat, title | `chat` | AFK kick warning style |  |  |
| AFK & shards (`afk`) | `afk-status-messages` | choice | actionbar, chat, off | `actionbar` | AFK status messages |  |  |
| AFK & shards (`afk`) | `afk-return-summary` | toggle | true, false | `true` | Welcome-back summary |  |  |
| AFK & shards (`afk`) | `shard-confirm-above` | choice | server, always, 100, 1000, 5000, never | `server` | Confirm shard buys from |  |  |
| AFK & shards (`afk`) | `shard-shop-stay-open` | toggle | true, false | `false` | Keep the shard shop open |  |  |
| Crates & kits (`crates`) | `crate-receipt` | choice | chat, actionbar, off | `chat` | Crate win receipt |  |  |
| Crates & kits (`crates`) | `kit-reminders` | choice | chat, actionbar, title, off | `chat` | Kit reminders | `siftcore.command.kits` | applies at once |
| Crates & kits (`crates`) | `crate-key-reminder` | toggle | true, false | `true` | Unopened key reminder |  |  |
| Crates & kits (`crates`) | `keyall-countdown` | choice | both, chat, actionbar, off | `both` | Keyall countdown |  |  |
| Crates & kits (`crates`) | `crate-quick-open` | choice | one, bulk, off | `one` | Sneak + right-click a crate |  |  |
| Crates & kits (`crates`) | `trash-protect` | choice | gear, valuables, off | `gear` | Trash protection | `siftcore.perk.trash` |  |
| Crates & kits (`crates`) | `crate-bulk-amount` | number | 2-64 (keys) | `10` | Keys per bulk open |  |  |
| Crates & kits (`crates`) | `kit-reminder-when` | choice | join-and-ready, join, ready | `join-and-ready` | When to remind about kits | `siftcore.command.kits` | applies at once |
| Crates & kits (`crates`) | `kit-auto-equip` | toggle | true, false | `false` | Auto-equip kit armour | `siftcore.command.kits` |  |
| Crates & kits (`crates`) | `trash-confirm` | choice | delete-on-close, delete-button | `delete-on-close` | Trash bin mode | `siftcore.perk.trash` |  |
| Spawners (`spawners`) | `spawner-open-click` | choice | server, sneak-right-click, right-click | `server` | Open spawner storage with |  |  |
| Spawners (`spawners`) | `spawner-full-alert` | choice | chat, actionbar, off | `actionbar` | Full storage alert |  |  |
| Spawners (`spawners`) | `spawner-stack-click` | choice | one, whole-hand | `one` | Right-click with spawners adds |  |  |
| Spawners (`spawners`) | `spawner-xp-mending` | choice | server, repair-first, levels-only | `server` | Spawner XP and mending |  |  |
| Spawners (`spawners`) | `spawner-pickup-storage` | choice | server, claim-box, sell | `server` | Storage when I pick up my spawner |  |  |
| Spawners (`spawners`) | `spawner-team-notices` | choice | pickups, all, off | `pickups` | Teammates using my spawners |  |  |
| Spawners (`spawners`) | `spawner-confirm-give` | toggle | true, false | `true` | Confirm stacking onto others' spawners |  |  |
| Staff (`staff`) | `social-spy` | toggle | true, false | `false` | Social spy | `siftcore.chat.socialspy` |  |
| Staff (`staff`) | `staff-chat` | toggle | true, false | `true` | Show staff chat | `siftcore.staff.chat` |  |
| Staff (`staff`) | `staff-punish-alerts` | choice | chat, actionbar, off | `chat` | Punishment alerts | `siftcore.staff.notify` |  |
| Staff (`staff`) | `staff-report-alerts` | choice | chat, actionbar, off | `chat` | Report alerts | `siftcore.staff.reports` |  |
| Staff (`staff`) | `team-spy` | toggle | true, false | `true` | Team chat spy | `siftcore.teams.spy` |  |
| Staff (`staff`) | `vanish-on-join` | toggle | true, false | `false` | Join vanished | `siftcore.staff.vanish` |  |
| Staff (`staff`) | `vanish-reminder` | toggle | true, false | `true` | Vanish reminder | `siftcore.staff.vanish` |  |
| Staff (`staff`) | `vanish-see-vanished` | toggle | true, false | `true` | See vanished staff | `siftcore.staff.vanish.see` | applies at once |
| Staff (`staff`) | `staff-freeze-alerts` | choice | chat, actionbar, off | `chat` | Frozen player logout alerts | `siftcore.staff.freeze` |  |
| Staff (`staff`) | `staff-combat-alerts` | choice | combat-logs, combat-logs-and-farming, off | `combat-logs` | Staff combat alerts | `siftcore.admin.combat` |  |
| Staff (`staff`) | `staff-confirm-bans` | toggle | true, false | `false` | Confirm bans | `siftcore.staff.ban` |  |
| Staff (`staff`) | `vanish-fake-messages` | toggle | true, false | `false` | Fake join/leave on vanish | `siftcore.staff.vanish` |  |
| Staff (`staff`) | `admin-config-alerts` | toggle | true, false | `true` | Config problem alerts | `siftcore.admin.reload` |  |
