# Chat, private messages and ignore lists (`chat`)

Public chat, `/msg` and `/r`, social spy, ignore lists and the chat staff tools. Package `feature/chat`, config
`features/chat.yml`, text `lang/chat.yml`, table `ignores` (V009). The per-player switches it registers show up in the
settings dialog (see `settings.md`).

It provides two contracts and consumes seven:

| Contract | Wired | Used for |
|---|---|---|
| `IgnoreLookup` (provided, `ChatFeature#ignores()`) | teleport requests, friends, team invites, payment notices | A player you ignore can't send you teleport, friend or team requests, and their payments arrive without the "paid you" notice |
| `TextChecks` (provided, `ChatFeature#textChecks()`) | cosmetics | Nicknames and custom join messages pass the word filter (any match, whatever `filter.action` is) and contain no address (any address, allowed ones too, even with `links.enabled: false`) |
| `Cosmetics` | cosmetics (late-bound: chat is built first) | Nicknames, chat tags and chat colours in public chat and private messages; `/msg` and mentions by nickname |
| `Ranks` | integrations (LuckPerms) | The rank label, in its colour, in front of the name and on the hover card |
| `TeamLookup` | teams feature | The team on the hover card |
| `StatsRecorder` | stats feature | Kills and playtime on the hover card |
| `MuteStatus` | staff feature | Muted players can't send private messages (any alias); public chat is refused by the staff tools, and by chat itself if anything let it through |
| `VanishStatus` | staff feature | Vanished staff look offline to `/msg` for players who can't see them |
| `AfkStatus` | `NONE` (AFK) | The sender is told when the player they message or mention is AFK |

## Public chat

A line is `<rank> <name>: <message>` (`chat.format`; `chat.format-unranked` when the player has no rank label). The
rank is the label in its LuckPerms colour (gray without one), the name and message in white. Everything a player
typed is inserted as plain text and never parsed, so `<red>` or click tags stay literal.

Cosmetics (`docs/features/cosmetics.md`) add to the line:

- a chat tag before the name (`chat.tagged-name`: `<tag> <name>`), in its own colours, its description on hover;
- the nickname the player shows instead of their name, in its colour or gradient; the hover card then starts with
  the nickname and a `Real name` line, and clicking still suggests `/msg <real name> `;
- the message in the sender's chat colour or gradient (not the `[item]` part, which keeps the chat colour). Viewers
  who turned `show-chat-colors` off get the line with the message plain; the sender always sees their colour.

- **Hover card.** Hovering the name shows the player's rank, team, balance, kills and playtime, each with its icon;
  clicking it suggests `/msg <name> `. The card is built once per message from in-memory sources (rank labels, the team
  registry, the ledger, the stats store), on the async chat thread.
- **`[item]` / `[i]`.** The first tag in a message becomes the name of the item in the player's hand (main hand, else
  off hand) in brackets, `[Diamond Sword]` or `[Diamond x5]`, showing the full item on hover. The name is shown in the
  chat colour (no rarity colours). Inventories belong to the player's region thread, so the async chat thread asks
  the player's thread for a copy of the item and waits at most 400 ms; if the region is too slow, or the hands are
  empty, the tag stays as typed. Works in private messages too. Permission `siftcore.chat.item`. Names a player
  chose (anvil names, book titles), on the item and on the items inside a shulker box or bundle, pass the link check
  and the word filter in replace mode (`[*** blade]`), so renaming an item never gets an address or a filtered word
  into chat; players with the matching bypass permission are shown as they are.
- **Mentions.** `@name`, or a player's name written as a whole word (names of 3 letters or more, configurable),
  pings that player (the nickname a player shows works too): a notify sound and `<name> mentioned you` on the action bar. There is no highlight in the line
  (design system). Players who ignore the sender aren't pinged (they don't get the line at all); each player turns
  alerts off in the settings (`mentions`). One sender pings the same player at most every 3 seconds. Only what the
  player typed counts: an `[item]` whose name is `@Alex` doesn't ping Alex.
- **Ignore lists.** Players who ignore the sender are removed from the line's viewers. Staff with
  `siftcore.chat.unignorable` reach everyone.

### Order of the checks

Chat runs in three listener steps on the async chat thread:

1. `LOWEST`: anti-spam, the link check and the word filter. Running first means a refused message also never reaches team chat or
   staff chat mode, and a filtered word is replaced before anyone (team chat, other plugins) reads the text. Muted
   players are left to the staff tools, which refuse the message at `LOW` with the mute reason.
2. `NORMAL`: what is left is public chat (team chat and staff chat modes cancel at `LOW`). Chat lock and slow mode
   apply, ignoring viewers are removed, `[item]` is replaced and the renderer is set (at most two renders: in the
   sender's chat colour, and plain for viewers who turned chat colours off).
3. `MONITOR`: when nothing can cancel the message any more, mentioned viewers are pinged.

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
allows its subdomains and pages (`siftvanilla.net` allows `store.siftvanilla.net/rank`), an entry with a page allows
only that page (`discord.gg/siftvanilla`); lookalikes such as `siftvanilla.net.evil.com` are still caught. Spelled-out
dots (`play dot example dot net`) are not detected. Private messages are checked too (`links.private-messages`).
Refused and changed messages are logged with the filter's log. Staff with `siftcore.chat.links` can post any address.

## Private messages

`/msg <player> <message>` (aliases `tell`, `w`, `whisper`, `pm`, `message`, `dm`; `/m` stays the menu) and
`/r <message>` (alias `reply`). The sender sees `To <name>: <message>`, the receiver `From <name>: <message>` with a
notify sound; both names can be clicked to write again. Staff with social spy on see
`Spy <from> to <to>: <message>`, and the console logs every message (`private-messages.log-to-console`).

`/r` answers the last person you talked to (either direction) for 10 minutes after the last message.

`/msg <nickname>` reaches an online player by the nickname they show. Both names in a message show as the players
show themselves (hover: the real name), and the text is in the sender's chat colour for the sender and for receivers
who see chat colours. Social spy and the console log keep real names and plain text.

A message from a player is checked in this order: empty, muted (`MuteStatus`, so `/pm` and `/dm` are covered too),
yourself, offline or hidden (vanished staff the sender can't see), you ignore them, they ignore you (unless you are
staff), they turned private messages off (unless you are staff), anti-spam and the filter, then the cancellable
`api.event.PrivateMessageEvent`. Someone who wrote to you within the reply window can always be answered, even when
they are vanished or turned messages off: they started the conversation.

The console can `/msg` players (no checks apply) and players can `/r` the console.

`/msgtoggle` (aliases `togglemsg`, `pmtoggle`, `togglepm`) turns incoming private messages on or off (the
`private-messages` setting).

## Ignore lists

`/ignore <player>` ignores a player (or stops ignoring them when already ignored), `/unignore <player>` stops.
`/ignore` and `/ignore list` open a dialog listing ignored players; picking one asks to stop ignoring them, and
"Ignore a player" opens a form. Ignoring hides the player's public chat and private messages, and stops their
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

## Per-player settings (shown in the settings dialog)

| Toggle | Default | Meaning |
|---|---|---|
| `mentions` | on | Play a sound and show a notice when mentioned |
| `private-messages` | on | Let players send me private messages (`/msgtoggle`) |
| `social-spy` | off | Staff only (`siftcore.chat.socialspy`): see private messages between players (`/socialspy`) |
| `show-chat-colors` | on | Registered by cosmetics in this group: see the colours other players chose for their messages |

## Placeholders

| Name | Value |
|---|---|
| `chat_ignoring` | How many players you ignore |
| `chat_reply` | Who `/r` answers, `-` when nobody |
| `chat_slowmode` | The slow mode gap in seconds, `0` when off |

## Config (`features/chat.yml`)

Every key is explained in the file. Sections: `format` (hover card, `[item]`), `anti-spam` (length, gap, rate limit,
repeats, capitals), `filter` (words, replace or block, leetspeak, spaced letters, private messages, logging), `links`
(block or replace, private messages, allow list, top-level domains),
`mentions` (bare names, minimum length, cooldown), `private-messages` (reply window, console log) and `ignore`
(limit, dialog page size). `/sift reload` applies changes; nothing half-applies on a typo.

## Design decisions

- A line is rendered at most twice: once in the sender's chat colour and once plain, for viewers who turned chat
  colours off. Nothing else depends on the viewer (mentions are a sound and an action bar, never a highlight).
- Player text only ever becomes `Component.text(...)`; the filter and the capitals rule work on plain text before
  the message is rendered, `[item]` replaces inside the component tree.
- Signed chat: replacing words or rendering a format changes what clients show (the unsigned content); the signed
  body stays what the player typed. A vanilla client with "Only Show Secure Chat" on shows the signed body. That is
  vanilla client behaviour and was not tested with a real client.
- Mentions match only players who actually receive the line, so ignoring someone also mutes their pings.
- `[item]` hovers show a preview copy of the item (`ItemPreview`): everything a tooltip shows, including the items
  in a shulker box or bundle, but never book pages, block entity data or plugin data. A shulker box of full books
  would otherwise make a chat packet too large for the readers' clients.
- `/me` and `/say` are vanilla commands: they are not formatted or filtered (the staff tools still block them while
  muted). Take `minecraft.command.me` away from players if every player line should pass the filter.
