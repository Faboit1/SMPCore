# Extras (`extras`)

Small things every SMP has: `/rules`, `/help`, `/ping`, `/seen`, `/links`, and join and leave messages. Package
`feature/extras`, config `features/extras.yml`, text `lang/extras.yml`. No tables.

| Contract | Wired | Used for |
|---|---|---|
| `VanishStatus` | staff (`StaffFeature#vanish()`) | Vanished staff join and leave without a message |
| `Cosmetics` | cosmetics (`CosmeticsFeature#cosmetics()`) | Rank and custom join and leave lines (and whether they are on, `joinLines()`, for offering the setting); plain lines name players as they show themselves (nicknames) |

## Commands

| Command | Permission | Default | What it does |
|---|---|---|---|
| `/rules` | `siftcore.command.rules` | everyone | The rules dialog (also the main menu entry `rules`) |
| `/help` (`/?`) | `siftcore.command.help` | everyone | Getting started: a button per place to start and one to the main menu |
| `/ping [player]` | `siftcore.command.ping` (`.others`) | everyone | Connection latency |
| `/seen <player>` (`/lastseen`) | `siftcore.command.seen` | everyone | Online since, or last online and first joined; vanished staff read as offline. The last online time follows the player's `seen-privacy` |
| `/links` (`/discord`, `/store`, `/website`) | `siftcore.command.links` | everyone | The server links dialog |

- **Rules** is a dialog whose text is in `lang/extras.yml` (`extras.rules.body`: one line per list entry). `/rules`
  closes on its button; the main menu's **Rules** entry (hub id `rules`, order 94) returns to the menu.
- **Help** (`/help`, "Getting started") is buttons only, no paragraphs: **Open the menu** (tooltip: how to open it
  from the pause screen or with /menu), then one button per place to start, named as in the main menu, each opening it:
  random teleport, sell, shop, auction house, homes, team, friends, spawn and rules (`ExtrasFeature.HELP`, in that
  order). The tooltip says what it is and the command (`extras.help.tooltips.<hub id>`). A place whose feature is off or
  whose menu entry the player lacks the permission for is left out.
- **Links** opens the client's own Server Links screen (also in the pause screen). The links are the
  `server-links` of `features/hub.yml` (label and https address each), registered by the hub feature at startup.
- **Ping** answers like every short result: above the hotbar, or where the player's "Quick results and errors"
  setting says. `/ping <player>` needs `siftcore.command.ping.others` (everyone) and finds only players the sender can
  see.
- `/seen` suggests known names and never finds vanished staff as online for players who can't see them.

## Join and leave messages

Which line a join shows, first match wins:

1. A vanished staff member: nothing.
2. A brand-new player with `messages.first-join-welcome` (on): `Welcome Alex to SiftVanilla. Player number 1,204.`
3. A player with a rank line or a custom message (`Cosmetics#joinLine`, see `cosmetics.md`): `Baron Alex joined`, or the
   player's own message. These show even when the plain messages are off; each player's line shows at most once a
   minute (`join-messages.cooldown` in `features/cosmetics.yml`), quicker rejoins fall through to the next rule.
4. With `messages.join` (off by default): `Alex joined`, the name as the player shows it (a nickname in its colour).
5. Nothing.

Leaving works the same way with `Cosmetics#quitLine` and `messages.quit` (no welcome step).

Each player picks which of these lines they read ("Join and leave messages", `join-leave-messages`, in the Server
announcements group of the settings, 2nd):

| Option | Reads |
|---|---|
| `all` (default) | Every line above: welcomes, rank and custom lines, plain join and leave lines |
| `first-joins` | Only the welcome of brand-new players (offered while `messages.first-join-welcome` is on; reads as `off` otherwise) |
| `off` | None |

So the server no longer broadcasts the lines itself: the join and quit messages of the event are cleared, and the line
goes to each online player by their choice and to the console (which logs every line). The joining player always
reads their own line. A player never reads a line the server turned off. The setting is offered while the server can
show any line: a plain one (`messages.join`, `messages.quit` or `messages.first-join-welcome`) or a rank or custom line
of the cosmetics (`Cosmetics#joinLines()`: the cosmetics feature and its `join-messages` on), the same rule as the fake
vanish lines of the staff tools. Every line is filtered by the choice as the settings resolve it, so a lock in
`features/settings.yml` (`locked: join-leave-messages: off`; YAML reads an unquoted `off` as `false`, which the
setting takes as off) wins for every line, and rank lines and the staff tools'
fake lines always agree. Friend join alerts are separate (friends feature). Other plugins that relay the server's join and quit messages (a
Discord bridge) no longer see these lines, since the event carries none; give such a plugin its own join messages.

## /seen and privacy

`/seen` of a player who is offline (or vanished and hidden from the asker) shows when they were last online and first
joined only to players in their "Who sees when I was last online" audience (`seen-privacy`, Privacy settings: everyone,
friends, nobody; read from storage for offline players). Others get `<name> keeps their last online time private.`
The player themselves, staff with `siftcore.staff.whois` and the console always see it. Online players who are visible
always show as online (the tab list shows that anyway).

## Config (`features/extras.yml`)

| Key | Default | Meaning |
|---|---|---|
| `messages.join` | `false` | `Alex joined` for players without a rank line |
| `messages.quit` | `false` | `Alex left` for players without a rank line |
| `messages.first-join-welcome` | `true` | Welcome brand-new players with their join number |
