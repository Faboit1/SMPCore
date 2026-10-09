# Cosmetic rank perks (`cosmetics`)

Chat colours, nicknames, chat tags, rank join and leave lines and kill effects: what makes each paid rank better than
the one below without giving anyone a gameplay edge. Package `feature/cosmetics`, config `features/cosmetics.yml`,
text `lang/cosmetics.yml`, table `player_cosmetics` (migrations V110 and V111; cosmetics own the range 110-114).

| Tier | Gets |
|---|---|
| default | Sees everything; can open `/cosmetics`, `/tags` and `/killeffect`, where locked perks say which rank unlocks them |
| Prospector | 3 chat tags |
| Baron | 8 vanilla chat colours, a nickname in a vanilla colour, 7 more tags (10), a rank join and leave line |
| Tycoon | Any hex chat colour and two-colour gradients (18 presets or mixed), gradient nicknames, 6 more tags (16) plus the monthly exclusive, custom join and leave messages, 7 kill effects |

Choices are kept when a rank runs out: the player silently shows the default until they have the rank again (a
nickname stays reserved for them for `nicknames.hold`, see below). Every perk command is refused in combat (a cosmetic
is no reason to leave a fight).

## Contracts

It provides one contract and consumes five:

| Contract | Wired | Used for |
|---|---|---|
| `Cosmetics` (provided, `CosmeticsFeature#cosmetics()`) | chat (late-bound, chat is built first), combat, extras | Names (nicknames), tags, chat colours, join and leave lines, kill effects. `Cosmetics.NONE` shows everything plain |
| `TextChecks` | chat (`ChatFeature#textChecks()`) | Nicknames and custom join messages must pass chat's word filter; join messages may not contain any address |
| `Ranks` | integrations | The rank, in its colour, in front of join and leave lines |
| `SpawnArea` | spawn | No kill effects inside the protected spawn |
| `CombatStatus` | the shared combat tags | Every perk command and dialog is refused in combat |

`FeatureCatalog` also hands it chat's settings group (`ChatFeature.SETTINGS`) and the display group
(`ScoreboardFeature.DISPLAY`) for its two player switches. Turning `enabled: false` in the config turns everything off at
once: the contract then answers like `NONE`, and so do the placeholders (choices are kept).

## Where the perks show

| Place | What changes |
|---|---|
| Public chat | `<rank> <tag> <name>: <message>`: the tag before the name (hover: its description), the nickname in its colour (the hover card gets a `Real name` line; clicking opens the real name's profile and shift-click writes to the real name), the message in the sender's colour. `[item]` keeps the chat colour; viewers who turned chat colours off see the message plain |
| Private messages | Both names as shown (hover gives the real name), the sender's colour on the text for the sender and for receivers who see colours; social spy and the console log stay plain with real names. `/msg <nickname>` reaches an online player by the nickname they show |
| Mentions | A player can be mentioned by the nickname they show (`@Shadow` or `Shadow`); the notice names the sender as they show themselves |
| Death messages, kill streaks, combat logs | Names as shown (the nickname with its colour, real name on hover). The kill log, `/combat kills` and the audit log keep real names |
| Join and leave messages | The rank line or the custom message (below) |
| Placeholders | See below; `%siftcore_display_name_mm%` is meant for TAB's `customtabname` |

Staff tools (inspect, punishments, reports, the audit log, `/seen`, social spy) always use real names. `/realname
<nickname>` tells anyone who uses a nickname.

## Chat colours (`/chatcolor`, alias `/chatcolour`)

- `/chatcolor` opens a dialog: the vanilla colours of `colors.basic` (each button in its own colour, the tooltip a
  sample line), **More colours** (Tycoon: the presets and **Mix your own**), and **Default colour**. Picking closes
  the dialog with `Your chat colour is now Gold.` on the action bar.
- `/chatcolor <colour>` does the same typed: a vanilla name (`gold`), a hex colour (`#FFB07A`, also `#FB7`), or two hex
  colours for a gradient (`#55FFFF #5555FF`). `/chatcolor reset` goes back to the default.
- Baron (`siftcore.chat.color`): the 8 shipped vanilla colours gold, yellow, aqua, dark aqua, blue, light purple,
  dark purple and gray. Tycoon (`siftcore.chat.color.hex`, which includes the Baron node): any hex colour or gradient.
- **Colour rules** (`ColorRules`, unit tested): a colour is refused when it is
  - too close to the palette's error red (`#FF5555`, errors and kills) or money green (`#1AFF1A`), or a colour listed in
    `colors.reserved`: the CIEDE2000 difference (CIE Lab, D65) is under `colors.min-distance` (20), or it is the same hue
    (within 25 degrees, both with a Lab chroma of at least 25) and only lighter, darker or duller (difference under 40),
    which catches dark red, dark green, sea greens (`#3CB371`, `#2E8B57`), forest and olive greens and greyish greens
    like `#8FBC8F`, while aquamarine, turquoise, sky blue, gold and pink stay free;
  - too dark to read on the chat background: WCAG contrast against black under `colors.min-contrast` (3), which refuses
    black, dark blue, dark gray and dark red.

  A gradient is checked at 17 points along its length, so two fine ends whose blend passes through salmon red (gold to
  pink) are refused. The rules follow the palette in `config.yml`: change the money colour and the new one is
  reserved. Presets and `colors.basic` entries that break the rules are skipped and reported.
- Aqua and purple: the monetization plan gives the brand aqua (`#3CC4EE`) and the shard purple (`#915DFF`) a meaning
  too, but the spec only keeps red and green back, and eight vanilla colours for Baron can't be found without aqua and
  purple. To keep them for the server, add `"#3CC4EE"` and `"#915DFF"` to `colors.reserved` and remove aqua, dark aqua,
  light purple and dark purple from `colors.basic` (the owner's call).
- The colour applies to the message text only; the player's name, rank and tag keep theirs.

## Nicknames (`/nick`)

- `/nick` opens a form: the nickname, a colour (default, the vanilla colours and, for Tycoon, the presets and
  "My own" with a hex field), **Save** and **Remove nickname**. `/nick <name>` sets the name keeping the colour,
  `/nick off` removes it.
- Baron (`siftcore.command.nick`): a nickname in one vanilla colour. Tycoon (`siftcore.nick.gradient`): hex colours and
  gradients, with the same colour rules as chat.
- A nickname is 3 to 16 letters, digits and underscores; it may not contain a word of `nicknames.reserved-words` in
  any case, also with underscores in between (admin, mod, owner, staff, helper, console, server, siftvanilla); it may
  not be another known player's name (`PlayerDirectory`, ignoring case; your own name in another case or colour is
  fine) or a nickname another player holds (below); and it must pass the chat word filter (underscores separate
  words). Nicknames are unique among everyone who holds one, so also among online players. A nickname can be changed
  every `nicknames.cooldown` (30s); changing only its colour is free.
- **Holds.** A stored nickname keeps others from taking it while its holder can show it (online with
  `siftcore.command.nick`) and for `nicknames.hold` (14 days) after the last time they could: when they last joined or
  left with the perk (renewed hourly while they play, and at shutdown). So an expired Baron, or a holder who is away,
  keeps it for two weeks. After that another player may take it: in the same step the old holder loses it (their
  colour is kept), is told `Another player took the nickname Shadow while you couldn't use it.` (right away when online,
  otherwise a moment after their next join) and the hand-over is audited (`cosmetics.nick`, `lost 'Shadow' to Sam (no
  longer held)`). Existing nicknames count from their row's last change (V111).
- If a player later joins with a nickname as their real name, the nickname stops showing (the real name wins), it is
  no longer held, and that player can take their own name as a nickname in another case or colour.
- Staff (`siftcore.admin.nick`, console too): `/nick <player> off` removes a nickname, `/nick <player> <name>` sets
  one (the rules and holds still apply, the colour is kept). The player is told, and both are written to the audit log
  (`cosmetics.nick`).
- `/realname <nickname>` names the player who holds a nickname; a nickname nobody holds any more reads as unused.

## Chat tags (`/tags`)

- `/tags` lists the tags the player can use first, then the locked ones (tooltip: what unlocks it, from the tag's
  `hint`), 16 per page, with **No tag** when one is picked. `/tags <id>` and `/tags off` do the same typed.
- Tags are configured in `tags.list` (`display` MiniMessage with colours and gradients, `description`, `permission`,
  optional `month` and `hint`). The shipped tags: 3 for `siftcore.tags.prospector` (Miner, Settler, Explorer), 7 for
  `siftcore.tags.baron` (Trader, Builder, Grinder, Warrior, Night Owl, Lucky, Collector) and 6 for
  `siftcore.tags.tycoon` (Mogul, Legend, Kingpin, Royal, Phantom, Nova). The Baron node includes the Prospector node and
  the Tycoon node both, so each tier sees the tags below it even without LuckPerms inheritance.
- **Monthly exclusives.** A tag with `month: "2026-10"` (Spooky ships for October 2026) can only be picked during that
  month (server time zone), by players with its permission. Whoever picks it then owns it for good (stored in
  `owned_tags`): they keep using it in later months and after their rank ends. Outside its month it is not listed to
  players who don't own it.
- A sold tag pack can use a permission of its own (`siftcore.tag.<id>`, granted by the store with
  `lp user {uuid} permission set ...`).
- Staff can give a player any tag for good, or take an owned one away (`/cosmetics admin owned <player> give|take
  <id>`, audited `cosmetics.owned`): to put back an exclusive after a mistake, or to take it back after a refund. A
  taken tag that is picked stops showing unless the player can still pick it through its permission.

## Join and leave lines (`/joinmessage`, `/leavemessage`)

- Baron (`siftcore.join.message`): `Baron Alex joined` / `Baron Alex left`, the rank in its LuckPerms colour (no rank
  label: `Alex joined`). Tycoon (`siftcore.join.message.custom`): their own messages, shown after the rank.
- `/joinmessage` opens a form with both messages, a preview of both lines (updated with **Preview** before saving),
  **Save** and **Use the rank lines**. Typed: `/joinmessage set <text>`, `/joinmessage reset`, `/joinmessage preview`,
  and the same with `/leavemessage`.
- A custom message is at most `join-messages.max-length` (40) characters, `{name}` marks where the name goes (once; a
  message without it gets the name in front: `rolls in` reads `Alex rolls in`), and it may not contain braces or angle
  brackets, a web or server address (any, even allowed ones, and also when chat's link check is off) or a filtered
  word. Messages are checked again when shown, so a word added to the filter later silences an old message.
- The lines show even when the plain join and leave messages are off (`features/extras.yml`). Vanished staff are never
  announced, a brand-new player gets the first-join welcome instead, and a player's line shows at most every
  `join-messages.cooldown` (60s): quicker rejoins fall back to the plain message, or nothing when that is off.
- Readers filter them like every join and leave line (`join-leave-messages`, extras feature). `Cosmetics#joinLines()`
  says whether these lines are on (`enabled` and `join-messages.enabled`), so the extras feature offers that setting
  while they are, even with every plain line off.

## Kill effects (`/killeffect`)

- Tycoon (`siftcore.killeffect.<id>`, all with `siftcore.killeffect.*`): `hearts`, `flames`, `souls` (soul burst),
  `totem` (totem burst), `lightning`, `notes` (note burst), `ender`. `/killeffect` lists them (locked ones say
  Tycoon); picking one shows it to the player alone where they stand. `/killeffect <id>`, `/killeffect off`.
- When a player is credited with a kill (combat's kill credit: the last hit in the tag window), their effect plays
  where the victim fell, two ticks later, on the region thread that owns that spot. Particles and sounds go to the
  players within `kill-effects.range` (32 blocks) who did not turn kill effects off, including the victim wherever they
  are in that world (unless they turned them off too).
- `lightning` strikes a visual-only bolt (`World#strikeLightningEffect`: no damage, no fire). The bolt is a real entity,
  so every client that tracks it sees it, with the sky flash and the thunder: up to the entity tracking range
  (`entity-tracking-range.other` in spigot.yml, 64), measured across the ground, and never past the view distance. It
  therefore only strikes when nobody within the larger of `kill-effects.range` and the view distance (6 chunks on the
  live server: 96 blocks), plus 16 blocks for movement, turned kill effects off (`BoltRule`, unit tested; positions
  are read without touching other regions). Otherwise it shows its sparks and a quieter thunder to those within range
  who want them, and nobody further away learns about the kill.
- Purely visual: nothing is damaged, lit, dropped or spawned apart from the visual bolt. Never in the protected spawn.
  Checked in the Canvas source: a bolt from `strikeLightningEffect` is an "effect" bolt, which skips fire, entity damage,
  lightning rods, copper scraping and the `LIGHTNING_STRIKE` game event (so no sculk sensor or warden reacts). It still
  fires `LightningStrikeEvent` (cause `CUSTOM`) for other plugins and the vanilla `lightning_strike` advancement trigger
  (Surge Protector can be earned near a villager).
- Rate limits: one effect per killer every `kill-effects.cooldown` (3s) and at most `kill-effects.max-per-second` (4) on
  the server; kills beyond that play none. Previews have their own limit. `/cosmetics admin` counts played and held
  back effects.

## The menu (`/cosmetics`)

`/cosmetics` (alias `/cosmetic`) and the main menu entry `cosmetics` (order 82) show what the player has now (chat
colour, nickname, tag, join line, kill effect, each in its own look) and a button per perk. Locked perks stay
clickable and answer `That comes with the Baron rank. Ranks are at /store.`

## Player settings

| Toggle | Group (place) | Default | Offered | Meaning |
|---|---|---|---|---|
| `show-chat-colors` | Chat (11th, after chat's own ten) | on | `enabled: true` | See the colours other players chose for their messages (off: their messages are plain; your own always shows yours) |
| `show-kill-effects` | Display (6th, after the catalog's five display settings) | on | `enabled: true`, `kill-effects.enabled: true` and at least one effect in `kill-effects.effects` | See particles and sounds of kill effects nearby (32 blocks). Off also keeps the lightning effect from striking its bolt anywhere within your view distance. Read where each effect plays, so it applies to the next kill |

Neither switch is shown while what it hides can't happen (the cosmetics feature off, or no kill effects), so neither
ever does nothing; a choice stored earlier is kept for when they come back. The kill effect switch is placed by the
display settings package (`CosmeticsFeature.registerKillEffects`, `KillEffectsSettingTest`).

Show my rank (Privacy, integrations feature) does not hide the cosmetic chat tag: a tag from `/tags` is a perk the
player picked and can take off themselves ("No tag"), not their rank (see integrations.md, "Show my rank").

## Commands and permissions

| Command | Permission | Default | What it does |
|---|---|---|---|
| `/cosmetics` | `siftcore.command.cosmetics` | everyone | The cosmetics menu |
| `/chatcolor [colour\|reset]` | `siftcore.chat.color` or `siftcore.chat.color.hex` | operators (ranks) | Chat colour dialog, or set it typed |
| `/nick [name\|off]` | `siftcore.command.nick` | operators (ranks) | Nickname form, or set it typed |
| `/nick <player> <name\|off>` | `siftcore.admin.nick` | operators | Staff: set or remove anyone's nickname (console too, audited) |
| `/realname <nickname>` | `siftcore.command.realname` | everyone | Who uses a nickname |
| `/tags [id\|off]` | `siftcore.command.tags` | everyone | Tag dialog, or pick one typed |
| `/joinmessage`, `/leavemessage` `[set <text>\|reset\|preview]` | `siftcore.join.message.custom` | operators (ranks) | Custom join and leave messages |
| `/killeffect [id\|off]` | `siftcore.command.killeffect` | everyone | Kill effect dialog, or pick one typed |
| `/cosmetics admin`, `admin show <player>` | `siftcore.admin.cosmetics` | operators | Status (players with choices, nicknames, tags, effects played and held back); a player's stored choices and owned tags |
| `/cosmetics admin reset <player>` | `siftcore.admin.cosmetics` | operators | Forgets a player's choices but keeps the monthly exclusives they own (those can't be picked again). The player is told; the audit entry (`cosmetics.reset`) lists everything that was there (`was chat=gold; nick=Shadow #FF6AD5:#B26BFF; tag=spooky; owned=spooky; ...`) so a mistake can be put back |
| `/cosmetics admin owned <player> give\|take <tag>` | `siftcore.admin.cosmetics` | operators | Gives a tag for good or takes an owned one away (audited `cosmetics.owned`) |

Perk nodes (all default to operators, granted to the rank groups in LuckPerms):

| Node | Includes | Rank |
|---|---|---|
| `siftcore.tags.prospector` | | Prospector |
| `siftcore.chat.color` | | Baron |
| `siftcore.command.nick` | | Baron |
| `siftcore.join.message` | | Baron |
| `siftcore.tags.baron` | `siftcore.tags.prospector` | Baron |
| `siftcore.chat.color.hex` | `siftcore.chat.color` | Tycoon |
| `siftcore.nick.gradient` | `siftcore.command.nick` | Tycoon |
| `siftcore.join.message.custom` | `siftcore.join.message` | Tycoon |
| `siftcore.tags.tycoon` | `siftcore.tags.baron`, `siftcore.tags.prospector` | Tycoon |
| `siftcore.killeffect.*` | every `siftcore.killeffect.<id>` | Tycoon |

LuckPerms setup on top of the rank groups:

```
lp group prospector permission set siftcore.tags.prospector true
lp group baron permission set siftcore.chat.color true
lp group baron permission set siftcore.command.nick true
lp group baron permission set siftcore.join.message true
lp group baron permission set siftcore.tags.baron true
lp group tycoon permission set siftcore.chat.color.hex true
lp group tycoon permission set siftcore.nick.gradient true
lp group tycoon permission set siftcore.join.message.custom true
lp group tycoon permission set siftcore.killeffect.* true
lp group tycoon permission set siftcore.tags.tycoon true
```

For TAB, `customtabname: "%siftcore_display_name_mm%"` shows nicknames (with their gradients) in the tab list.

## Placeholders

| Name | Value |
|---|---|
| `nick` | The nickname the player shows, plain; empty without one (or when they may not show it) |
| `display_name` | The nickname, or the name |
| `display_name_mm` | The nickname (or name) as MiniMessage in its colours: `<gradient:#FF6AD5:#B26BFF>Shadow</gradient>`; the text is escaped |
| `tag` | The chat tag as its configured MiniMessage; empty without one |
| `tag_plain`, `tag_id` | The tag as plain text, its id |
| `chat_color` | `gold`, `#FFB07A` or `#55FFFF:#5555FF`; empty without one |
| `kill_effect` | The kill effect id; empty without one, and while cosmetics or `kill-effects.enabled` are off |
| `join_message`, `leave_message` | The custom message (with `{name}`) when it would show now: the perk, join lines on, and it passes today's word filter and link check; empty otherwise |

Every placeholder shows what the player shows now, so it needs the player online (permissions decide what shows);
offline players read as the default. Nothing a join line would silence can reach a scoreboard or hologram.

## Storage

`player_cosmetics` (V110): one row per player who chose something: `chat_style`, `nick` (+ `nick_lower`, indexed),
`nick_style`, `tag`, `owned_tags` (comma-separated ids), `join_message`, `leave_message`, `kill_effect`, `updated`.
V111 adds `nick_seen` (when the holder was last able to show their nickname; existing nicknames start from `updated`)
and `nick_lost` (a nickname another player took over, until its old holder is told).
Styles are text: `gold`, `#FFB07A`, `#55FFFF:#5555FF`. Every row is loaded at startup into immutable profiles in a
concurrent map (so the async chat thread and placeholders read memory only) with a nickname index; every change is
written through by the ordered database writer, queued under the same lock as the change, so memory and the table
never disagree and two players can never claim one nickname; a nickname whose hold ran out moves from the old holder
to the new one in one locked step. A profile emptied of every choice deletes its row.

## Config summary (`features/cosmetics.yml`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Every cosmetic on or off at once (choices are kept) |
| `colors.basic` | 8 colours | The vanilla colours Baron can pick (red, green and dark colours are skipped) |
| `colors.presets` | 8 colours, 10 gradients | Ready-made premium styles: `id: {name, style}` |
| `colors.min-distance` | `20` | CIEDE2000 difference from a reserved colour |
| `colors.min-contrast` | `3.0` | Contrast ratio against black |
| `colors.reserved` | `[]` | More colours nobody may pick (for example the shard purple and brand aqua, see above) |
| `nicknames.min-length`, `max-length` | `3`, `16` | Nickname length |
| `nicknames.reserved-words` | admin, mod, owner, staff, helper, console, server, siftvanilla | Words no nickname may contain |
| `nicknames.cooldown` | `30s` | Time between nickname changes |
| `nicknames.hold` | `14d` | How long a nickname stays reserved after its holder could last show it (`0s`: free as soon as they can't) |
| `tags.list` | 17 tags | The tags (see above) |
| `join-messages.enabled` | `true` | Rank and custom join and leave lines |
| `join-messages.cooldown` | `60s` | A player's line shows at most this often |
| `join-messages.max-length` | `40` | Longest custom message |
| `kill-effects.enabled` | `true` | Kill effects on |
| `kill-effects.effects` | all 7 | The effects players can pick, in menu order |
| `kill-effects.cooldown` | `3s` | Per killer |
| `kill-effects.max-per-second` | `4` | Server-wide |
| `kill-effects.range` | `32` | Blocks for particles and sounds; the lightning bolt's opt-out reaches the view distance |

## Self-test

`/sift selftest` checks that the colour rules refuse red, green (sea green too) and dark colours (and a gradient
through red) and allow gold and the Tycoon gradient, that every configured colour and preset passes the rules with the
current palette, the nickname rules and holds, that no two online players show one nickname, and that the table has as
many rows as memory.

## Design decisions

- **Monetisation rules.** Only looks are sold: no colour, tag, name or effect changes what anyone can do, and the
  defaults never get worse. Colours with a meaning stay reserved so a paid colour can't pass for an error or a
  payment, and nicknames can't pose as staff or as another player.
- **One link, small hooks.** Chat, combat and extras call `Cosmetics` and never see the feature; with the feature off
  they get `Cosmetics.NONE` behaviour (plain names, no tags, no colours, the default join lines, no effects).
- **Permission at use, choice kept.** Nothing is deleted when a rank ends: every read checks the permission (and the
  colour rules, which may have changed with the palette), so rebuying brings everything back. The one exception is a
  nickname, which is unique: it is held for two weeks, then a paying player may take a name nobody shows.
- **Owned exclusives are never lost by accident.** A staff reset keeps them, and only an explicit `owned ... take`
  removes one; both are audited with what changed.
- **Viewer choice.** A chat line is rendered twice at most (painted and plain) and each viewer gets the one they asked
  for; kill effect particles are sent to an explicit list of receivers, never broadcast, and the one effect that
  cannot be aimed (the bolt) holds off for everyone who could see it.
- **Threads.** Chat runs on the async chat thread and reads memory only; kill effects run on the region thread of the
  spot; dialogs and commands run on the player's thread. Writes go to the database writer.
- **Not verified with a real client:** how dialog buttons look with coloured and gradient labels, particle visuals and
  the visual bolt, and gradient readability on different chat backgrounds and GUI scales.
