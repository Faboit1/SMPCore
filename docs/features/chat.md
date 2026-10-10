# Chat, private messages and ignore lists (`chat`)

Public chat, `/msg` and `/r`, social spy, ignore lists and the chat staff tools. Package `feature/chat`, config
`features/chat.yml`, text `lang/chat.yml`, table `ignores` (V009). Its ten player settings fill the Chat group of the
settings dialog (social spy is in Staff); see [Per-player settings](#per-player-settings) and `settings.md`.

It provides two contracts and consumes these:

| Contract | Wired | Used for |
|---|---|---|
| `IgnoreLookup` (provided, `ChatFeature#ignores()`) | teleport requests, friends, team invites, payment notices | A player you ignore can't send you teleport, friend or team requests, and their payments arrive without the "paid you" notice |
| `TextChecks` (provided, `ChatFeature#textChecks()`) | cosmetics | Nicknames and custom join messages pass the word filter (any match, whatever `filter.action` is) and contain no address (any address, allowed ones too, even with `links.enabled: false`) |
| `Cosmetics` | cosmetics (late-bound: chat is built first) | Nicknames, chat tags and chat colours in public chat and private messages; `/msg` and mentions by nickname |
| `Ranks` | integrations (LuckPerms) | The rank label, in its colour, in front of the name and on the hover card |
| `TeamLookup` | teams feature | The team on the hover card |
| `StatsRecorder` | stats feature | Kills and playtime on the hover card; playtime decides who counts as a brand-new player (`chat-hide-new`) |
| `Relations` (`services.relations()`) | friends, teams, chat's own ignore lists (bound once every feature is built) | Who can message me, who can ping me, and balance privacy on the hover card |
| `/profile` | friends feature (found in the command map 20 ticks after startup; only SiftCore's own counts) | Clicking a name in public chat opens the player's profile |
| `MuteStatus` | staff feature | Muted players can't send private messages (any alias); public chat is refused by the staff tools, and by chat itself if anything let it through |
| `VanishStatus` | staff feature | Vanished staff look offline to `/msg` for players who can't see them |
| `AfkStatus` | AFK feature (`AfkFeature#status()`) | The sender is told when the player they message or mention is AFK |

## Public chat

A line is `<rank> <name>: <message>` (`chat.format`; `chat.format-unranked` when the player has no rank label). The
rank is the label in its LuckPerms colour (gray without one), the name and message in white. Everything a player
typed is inserted as plain text and never parsed, so `<red>` or click tags stay literal.

Cosmetics (`docs/features/cosmetics.md`) add to the line:

- a chat tag before the name (`chat.tagged-name`: `<tag> <name>`), in its own colours, its description on hover;
- the nickname the player shows instead of their name, in its colour or gradient; the hover card then starts with
  the nickname and a `Real name` line, and the click and shift-click still use the real name;
- the message in the sender's chat colour or gradient (not the `[item]` part, which keeps the chat colour). Viewers
  who turned `show-chat-colors` off get the line with the message plain; the sender always sees their colour.

- **Hover card and click.** Hovering the name shows the player's rank, team, balance, kills and playtime, each with
  its icon. Clicking it opens the player's profile (`/profile <name>`: their card, or for a friend the full profile,
  with Message, Teleport request, Team invite, Pay and Stats buttons), and shift-clicking it puts `/msg <name> ` in the
  chat box; the card's last line says so (`chat.card.click-profile`). On a server without profiles (friends feature
  off, or `/profile` turned off in `commands.yml`), and for readers who may not use `/profile`
  (`siftcore.command.profile` taken away), clicking suggests `/msg <name> ` as before (`chat.card.click`). Only
  SiftCore's own `/profile` counts: while another plugin holds the plain label, the click runs
  `/siftcore:profile <name>`. The balance line follows the sender's `balance-privacy` (Privacy settings): readers
  outside it (and not staff with `siftcore.admin.eco`) get the card without it. The balance is written in each
  reader's money format (`money-format`, Display). The name is built from in-memory sources (rank labels, the team
  registry, the ledger, the stats store), on the async chat thread, once per message for each kind of reader (with or
  without the balance and in which money format, opening the profile or a message), at most eight versions.
- **`[item]` / `[i]`.** The first tag in a message becomes the name of the item in the player's hand (main hand, else
  off hand) in brackets, `[Diamond Sword]` or `[Diamond x5]`, showing the full item on hover. The name is shown in the
  chat colour (no rarity colours). Inventories belong to the player's region thread, so the async chat thread asks
  the player's thread for a copy of the item and waits at most 400 ms; if the region is too slow, or the hands are
  empty, the tag stays as typed. Works in private messages too. Permission `siftcore.chat.item`. Names a player
  chose (anvil names, book titles), on the item and on the items inside a shulker box or bundle, pass the link check
  and the word filter in replace mode (`[*** blade]`), so renaming an item never gets an address or a filtered word
  into chat; players with the matching bypass permission are shown as they are.
- **Mentions.** `@name`, or a player's name written as a whole word (names of 3 letters or more, configurable),
  mentions that player (the nickname a player shows works too). Each mentioned player is told the way they chose
  (`mentions`: above the hotbar by default, in chat, as a title, or not at all) with their mention sound
  (`sound-mention` in Sounds: the notify ping by default, bell, pling, chime or none), only when the sender is in
  their "Who can ping me" audience (`mention-from`: everyone, friends and teammates, friends) and, for a bare name,
  when they keep "Ping on plain name" (`mention-plain-names`) on; `@name` always counts. Players who ignore the sender
  aren't pinged (they don't get the line at all). One sender alerts the same player at most every 3 seconds. Only what
  the player typed counts: an `[item]` whose name is `@Alex` doesn't ping Alex.
- **Name highlight.** In a line that mentions a reader (by the same rules, their plain-name choice included), the
  reader's name or nickname is bold for them (`mention-highlight`: bold, underlined or off). Never a colour alone, and
  only in the reader's own copy of the line; the text itself is unchanged.
- **Who reads a line.** Players who ignore the sender are removed from the line's viewers (staff with
  `siftcore.chat.unignorable` reach everyone), and so are players who turned public chat off (`public-chat`) and, when
  the sender has less active playtime than `new-players.playtime` (30 minutes), players who hide brand-new players
  (`chat-hide-new`). Staff (`siftcore.chat.unignorable` or `siftcore.chat.bypass`) are never hidden, and nobody is told
  they are hidden. A player who writes in public chat with public chat off is reminded once a session that they won't
  see answers: `chat.public-off` points to `/settings chat`, and `chat.public-off-server` says the server turned it
  off when `public-chat` is locked or hidden in `features/settings.yml` (the player can't change it then). Private
  messages, team chat, staff chat and server notices are not public chat.
- **Strict word filter.** Readers who turn on `chat-filter-strict` also stop reading the milder words of
  `filter.strict-words` (replaced with the filter's replacement) in public chat and in private messages to them.
  Everyone else, and the sender, read the line with the normal filter only. It works with `filter.enabled: false` too
  (a personal filter); an empty list removes the setting.

### Order of the checks

Chat runs in three listener steps on the async chat thread:

1. `LOWEST`: anti-spam, the link check and the word filter. Running first means a refused message also never reaches team chat or
   staff chat mode, and a filtered word is replaced before anyone (team chat, other plugins) reads the text. Muted
   players are left to the staff tools, which refuse the message at `LOW` with the mute reason.
2. `NORMAL`: what is left is public chat (team chat and staff chat modes cancel at `LOW`). Chat lock and slow mode
   apply, ignoring viewers and viewers who don't read this sender (public chat off, hiding new players) are removed,
   `[item]` is replaced and the per-reader renderer is set (see Design decisions).
3. `MONITOR`: when nothing can cancel the message any more, mentioned viewers are alerted by their settings.

### Anti-spam

Players with `siftcore.chat.bypass` skip all of it. Refused messages are explained on the action bar and never count
against the player.

| Check | Default | Message |
|---|---|---|
| Length | 200 characters | That message is too long |
| Gap between messages | 1s | Slow down, you can talk again in 1s |
| Rate limit | 5 messages per 10s | You are sending messages too fast |
| Repeats | the last 3 messages within 30s, 90% alike | You just said that |
| Capitals | over 60% capitals in 8+ letters | lowercased (or refused with `action: block`) |

Repeats compare messages without case, spaces, punctuation or stretched letters (`heyyyy` equals `hey`), so near
copies like `selling diamonds` / `Selling diamond!!` are caught. Messages under five letters only repeat when equal.
Private messages share the length, gap, rate and capitals rules (with their own history) but are never checked for
repeats: saying `ok` to two people is fine.

### Word filter

Words and phrases from `filter.words`, matched as whole words ignoring case and accents. With `leetspeak` on,
`k1ll`, `$hit` and `@ss` match; with `join-spaced-letters`, letters typed one at a time (`k y s`, `k.y.s`) are joined
first. An entry ending in `*` also matches longer words (`idiot*` matches `idiots`). `action: replace` replaces just the
matched words (`***`); `action: block` refuses the message. Private messages are filtered too
(`filter.private-messages`). Changed and refused messages are logged to the console (`filter.log`). Players with
`siftcore.chat.filter.bypass` are not filtered. `/chat test <message>` shows what the filter, the link check and the
capitals rule do with a message.

### Link check (advertising)

Messages with a web or server address are refused (`links.action: block`, the default) or have the address replaced
(`replace`), so players can't advertise other servers:

- links with a scheme: `https://example.com/vote`;
- IPv4 addresses, with or without a port: `51.12.3.4:25565`;
- domain names ending in a listed top-level domain (`links.top-level-domains`: com, net, org, gg, io, me...):
  `play.otherserver.net`, `discord.gg/invite`.

Version numbers (`1.21.5`), money (`1.5k`), e-mail addresses, abbreviations (`e.g.`) and words ending in an unlisted
ending (`config.yml`) are not addresses. The allow list (`links.allowed`) passes the server's own addresses: a domain
allows its subdomains and pages (`siftvanilla.com` allows `store.siftvanilla.com/rank`), an entry with a page allows
only that page (`discord.gg/siftvanilla`); lookalikes such as `siftvanilla.com.evil.com` are still caught. Spelled-out
dots (`play dot example dot net`) are not detected. Private messages are checked too (`links.private-messages`).
Refused and changed messages are logged with the filter's log. Staff with `siftcore.chat.links` can post any address.

## Private messages

`/msg <player> <message>` (aliases `tell`, `w`, `whisper`, `pm`, `message`, `dm`; `/m` stays the menu) and
`/r <message>` (alias `reply`). The sender sees `To <name>: <message>`, the receiver `From <name>: <message>` with
their private message sound (`sound-pm` in Sounds: the notify ping by default); both names can be clicked to write
again. Receivers who turned on the pop-up (`pm-alert`) also get `Message from <name>` above the hotbar or as a title;
the chat line always arrives. Receivers with the strict filter on read the milder words replaced. Staff with social
spy on see `Spy <from> to <to>: <message>`, and the console logs every message (`private-messages.log-to-console`).

`/r` answers the last person you talked to (either direction) for 10 minutes after the last message; players who
set "/r replies to" (`reply-target`) to "Last to message me" answer whoever last wrote to them instead, even after they
wrote to someone else. The `chat_reply` placeholder follows the same choice.

`/msg <nickname>` reaches an online player by the nickname they show. Both names in a message show as the players
show themselves (hover: the real name), and the text is in the sender's chat colour for the sender and for receivers
who see chat colours. Social spy and the console log keep real names and plain text.

A message from a player is checked in this order: empty, muted (`MuteStatus`, so `/pm` and `/dm` are covered too),
yourself, offline or hidden (vanished staff the sender can't see), you ignore them, they ignore you (unless you are
staff), you are not in their "Who can message me" audience (`private-messages`: everyone, friends and teammates,
friends or nobody; unless you are staff with `siftcore.chat.msg.bypass`), anti-spam and the filter, then the
cancellable `api.event.PrivateMessageEvent`. Someone who wrote to you within the reply window can always be answered,
even when they are vanished or don't take messages from you: they started the conversation.

The console can `/msg` players (no checks apply) and players can `/r` the console.

`/msgtoggle` (aliases `togglemsg`, `pmtoggle`, `togglepm`) switches "Who can message me" between nobody and everyone
(from friends only it goes to nobody). When the server locked or hides the setting it says
`Who can message me is set by the server.` in the error colours and changes nothing.

## Ignore lists

`/ignore <player>` ignores a player (or stops ignoring them when already ignored), `/unignore <player>` stops.
`/ignore` and `/ignore list` open the Ignored players dialog: one status line ("Ignoring 3 of 100 players", or "You
don't ignore anyone."), a button per ignored player (tooltip: "Stop ignoring Alex. Asks first."), all on one page (the
list is bounded by `ignore.max`), and **Ignore a player**, a form whose Ignore button says in its tooltip what ignoring
does. A name the form refuses (unknown, yourself, staff, list full) shows in red on the form; an ignored name shows the
list again, without a message. Ignoring hides the player's public chat and private messages, and stops their
teleport requests, friend requests and team invites (refused like any refusal: "You can't invite <name>."), and
payments from them arrive without the "<name> paid you" notice (the money still arrives). Teams and economy are built
before chat, so `FeatureCatalog` installs `IgnoreLookup` in them once chat is built (`EconomyFeature#ignores`,
`TeamsFeature#ignores`). Online staff with `siftcore.chat.unignorable` can't be ignored (they reach everyone anyway).
Staff the player can't see (vanished) and offline staff are added like anyone else, so the answer never tells that a
vanished player is online; the entry has no effect on them. A player can ignore up to 100 players (`ignore.max`).

Lists live in memory (loaded at startup; one row per entry, capped per player) and are written through to the
`ignores` table in order by the database writer, so they survive restarts and `IgnoreLookup` answers from any
thread without touching the database.

## Staff: `/chat`

| Command | What it does |
|---|---|
| `/chat` (alias `/chatadmin`) | Status: lock, slow mode, filter size, link check, ignore entries, anti-spam rules |
| `/chat lock`, `/chat unlock` | Only players with `siftcore.chat.bypass` can talk while locked; announced to everyone |
| `/chat slow <duration>`, `/chat slow off` | Slow mode: one public message per player every so often (up to 1h, longer is refused); announced |
| `/chat test <message>` | What anti-spam (length, capitals), the link check and the filter would do with a message |
| `/chat ignores <player>` | Who a player ignores |

Lock and slow mode last until changed or the server restarts. Every command works from the console; lock and slow
mode changes are written to the audit log.

## Commands and permissions

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.msg` | everyone | `/msg` |
| `siftcore.command.reply` | everyone | `/r` |
| `siftcore.command.ignore` | everyone | `/ignore`, `/unignore` |
| `siftcore.command.msgtoggle` | everyone | `/msgtoggle` |
| `siftcore.chat.item` | everyone | `[item]` in chat and private messages |
| `siftcore.chat.socialspy` | op | `/socialspy` and the social spy setting |
| `siftcore.chat.bypass` | op | Skip anti-spam, chat lock and slow mode |
| `siftcore.chat.filter.bypass` | op | Skip the word filter |
| `siftcore.chat.links` | op | Post links and server addresses |
| `siftcore.chat.unignorable` | op | Can't be ignored; chat and messages reach players who ignore you |
| `siftcore.chat.msg.bypass` | op | Message players who turned private messages off |
| `siftcore.admin.chat` | op | `/chat` |

## Per-player settings

In the Chat group of the settings dialog, in this order (`/settings chat`; the group has two pages). "Offered" says
when the setting is shown at all, so no switch does nothing.

| Setting | Kind and options | Default | Offered | What it does |
|---|---|---|---|---|
| `mentions` | choice: actionbar, chat, title, off | actionbar | `mentions.enabled` | How you are told someone mentioned you. Was a switch: stored `true` reads as actionbar, `false` as off (rows and `features/settings.yml` entries alike) |
| `private-messages` | choice: everyone, friends-team, friends, nobody | everyone | SiftCore's `/msg` (friend options with friends) | Who can send you private messages (people you wrote to recently and staff still can). Was a switch: `true` reads as everyone, `false` as nobody. `/msgtoggle` switches nobody and everyone. Without friends, the friend options read as nobody. No placeholder |
| `public-chat` | switch | on | always | Read other players' public chat |
| `pm-alert` | choice: off, actionbar, title | off | SiftCore's `/msg` | Also flash new private messages above the hotbar or as a title |
| `mention-from` | choice: everyone, friends-team, friends | everyone | `mentions.enabled` and friends | Only alert you for mentions from these players (the line still shows). No placeholder |
| `mention-highlight` | choice: bold, underline, off | bold | `mentions.enabled` | Your name stands out in lines that mention you |
| `chat-filter-strict` | switch | off | `filter.strict-words` not empty | Also hide the milder words of `filter.strict-words` in what you read |
| `reply-target` | choice: last-conversation, last-received | last-conversation | SiftCore's `/msg` and `/r` | Who `/r` answers |
| `mention-plain-names` | switch | on | `mentions.enabled` and `mentions.plain-names` | Alert you for your bare name too, not only `@name` |
| `chat-hide-new` | switch | off | the stats feature and `new-players.playtime` above 0 | Hide public chat of players with less active playtime than `new-players.playtime` (staff never) |
| `show-chat-colors` | switch | on | cosmetics on | Registered by cosmetics (11th): see the colours other players chose for their messages |

`social-spy` (switch, off) is in the Staff group: staff with `siftcore.chat.socialspy` see private messages between
players (`/socialspy` flips it; like `/msgtoggle` it says when the server sets it). It is offered while SiftCore's
`/msg` is.

"SiftCore's `/msg`" means the command is registered: not turned off in `commands.yml` and not left to another plugin
(`yield-to`). Chat looks for its own `siftcore:msg`, `siftcore:r` and `siftcore:profile` once, 20 ticks after
startup; those `commands.yml` changes need a restart anyway.

Chat also acts on these shared settings (defined by core, `SharedSettings`): `sound-mention` and `sound-pm` (Sounds:
default, bell, pling, chime, off) and `balance-privacy` (Privacy: who sees the balance on your hover card).

## Placeholders

| Name | Value |
|---|---|
| `chat_ignoring` | How many players you ignore |
| `chat_reply` | Who `/r` answers (following `reply-target`), `-` when nobody |
| `chat_slowmode` | The slow mode gap in seconds, `0` when off |

## Config (`features/chat.yml`)

| Key | Default | Meaning |
|---|---|---|
| `format.hover-card` / `item-tag` | true / true | The hover card on names; `[item]` in chat |
| `anti-spam.max-length` | 200 | Characters per message |
| `anti-spam.cooldown` | 1s | Shortest gap between two messages (0s off) |
| `anti-spam.rate-limit.messages` / `window` | 5 / 10s | At most this many messages in the window |
| `anti-spam.repeats.window` / `similarity` / `compare-last` | 30s / 0.9 / 3 | Repeats: how long, how alike (0.5-1), how many recent messages |
| `anti-spam.caps.max-ratio` / `min-letters` / `action` | 0.6 / 8 / lowercase | Capitals: share, from how many letters, `lowercase` or `block` |
| `filter.enabled` / `action` / `replacement` | true / replace / `***` | The word filter, `replace` or `block` |
| `filter.leetspeak` / `join-spaced-letters` / `private-messages` / `log` | true each | Filter options |
| `filter.words` / `strict-words` | a shipped list / 23 milder words | What the filter and the strict filter catch |
| `links.enabled` / `action` / `private-messages` | true / block / true | The link check, `block` or `replace` |
| `links.allowed` / `top-level-domains` | `siftvanilla.com`, `discord.gg/siftvanilla` / 42 endings | Addresses that pass; which endings count as a domain |
| `mentions.enabled` / `plain-names` / `min-plain-length` / `cooldown` | true / true / 3 / 3s | Mentions, bare-name mentions, their minimum length, one alert per sender and player per cooldown |
| `private-messages.reply-expiry` / `log-to-console` | 10m / true | How long `/r` remembers a conversation; console log |
| `ignore.max` | 100 | Ignore list size (the dialog shows it all; the old `ignore.page-size` is no longer read) |
| `new-players.playtime` | 30m | Who counts as brand new for "Hide brand-new players" (`0s` removes the setting) |

Every key is explained in the file. Sections: `format` (hover card, `[item]`), `anti-spam` (length, gap, rate limit,
repeats, capitals), `filter` (words, replace or block, leetspeak, spaced letters, private messages, logging, and
`strict-words`: the milder words of the strict filter, 23 by default), `links` (block or replace, private messages,
allow list, top-level domains), `mentions` (on or off, bare names, minimum length, cooldown), `private-messages`
(reply window, console log), `ignore` (limit) and `new-players` (`playtime`: who counts as a
brand-new player for "Hide brand-new players", 30m, `0s` removes the setting). `/sift reload` applies changes; nothing
half-applies on a typo.

## Design decisions

- Each reader gets their own version of a public line, made from what they chose: the strict filter, their name
  highlighted, the sender's chat colour or plain, the hover card with or without the balance. Versions that come out
  the same are rendered once per message and shared (at most sixteen); a version that highlights a reader is theirs
  alone. The sender and the console get the full line (the sender's name click follows their own `/profile`
  permission). The renderer runs on the async chat thread and reads
  only thread-safe state (settings cache, relations, permissions).
- The highlight is a decoration (bold or underline), never a colour alone, and only touches the typed text: an
  `[item]` part is never changed, and the chat colour painted afterwards still covers the highlighted name.
- Player text only ever becomes `Component.text(...)`; the filter and the capitals rule work on plain text before
  the message is rendered, `[item]` replaces inside the component tree.
- Signed chat: replacing words or rendering a format changes what clients show (the unsigned content); the signed
  body stays what the player typed. A vanilla client with "Only Show Secure Chat" on shows the signed body. That is
  vanilla client behaviour and was not tested with a real client.
- Mentions match only players who actually receive the line, so ignoring someone, turning public chat off or hiding
  a brand-new sender also mutes their pings.
- `pm-alert` and `mention-highlight` also take `false` as off: YAML reads an unquoted `off` in
  `features/settings.yml` (`locked: pm-alert: off`) as the boolean `false`, and `mentions` already maps it.
- `mentions` and `private-messages` became choices under their old ids, so stored rows and server config keep
  working; a row is rewritten with the option id on the player's next change. Who-can settings ask
  `services.relations()`, so chat needs no friends or teams wiring of its own; with the friends feature off their
  friend options are not offered and read as the safe choice (nobody for messages, everyone for pings).
- Clicking a name runs `/profile <name>` (the cross-feature action hub of the friends feature) and keeps `/msg` one
  shift-click away; whether SiftCore's `/profile` exists is checked once 20 ticks after startup (commands register
  after every plugin is enabled), so a server without it keeps the old `/msg` click. The command map entry must be
  SiftCore's (`PluginIdentifiableCommand`), so another plugin's `/profile` never takes the click; readers without
  `siftcore.command.profile` get the `/msg` click, decided per reader by the renderer.
- `[item]` hovers show a preview copy of the item (`ItemPreview`): everything a tooltip shows, including the items
  in a shulker box or bundle, but never book pages, block entity data or plugin data. A shulker box of full books
  would otherwise make a chat packet too large for the readers' clients.
- `/me` and `/say` are vanilla commands: they are not formatted or filtered (the staff tools still block them while
  muted). Take `minecraft.command.me` away from players if every player line should pass the filter.
