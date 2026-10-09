# Extras (`extras`)

Small things every SMP has: `/rules`, `/help`, `/ping`, `/seen`, `/links`, and join and leave messages. Package
`feature/extras`, config `features/extras.yml`, text `lang/extras.yml`. No tables.

| Contract | Wired | Used for |
|---|---|---|
| `VanishStatus` | staff (`StaffFeature#vanish()`) | Vanished staff join and leave without a message |
| `Cosmetics` | cosmetics (`CosmeticsFeature#cosmetics()`) | Rank and custom join and leave lines; plain lines name players as they show themselves (nicknames) |

## Commands

| Command | Permission | Default | What it does |
|---|---|---|---|
| `/rules` | `siftcore.command.rules` | everyone | The rules dialog (also the main menu entry `rules`) |
| `/help` (`/?`) | `siftcore.command.help` | everyone | Getting started, with a button to the main menu |
| `/ping [player]` | `siftcore.command.ping` (`.others`) | everyone | Connection latency |
| `/seen <player>` (`/lastseen`) | `siftcore.command.seen` | everyone | Online since, or last online and first joined; vanished staff read as offline |
| `/links` (`/discord`, `/store`, `/website`) | `siftcore.command.links` | everyone | The server links dialog |

## Join and leave messages

Which line a join shows, first match wins:

1. A vanished staff member: nothing.
2. A brand-new player with `messages.first-join-welcome` (on): `Welcome Alex to SiftVanilla. Player number 1,204.`
3. A player with a rank line or a custom message (`Cosmetics#joinLine`, see `cosmetics.md`): `Baron Alex joined`, or the
   player's own message. These show even when the plain messages are off; each player's line shows at most once a
   minute (`join-messages.cooldown` in `features/cosmetics.yml`), quicker rejoins fall through to the next rule.
4. With `messages.join` (off by default): `Alex joined`, the name as the player shows it (a nickname in its colour).
5. Nothing.

Leaving works the same way with `Cosmetics#quitLine` and `messages.quit` (no welcome step). The messages are the
server's join and quit messages, so other plugins (Discord bridges) see the same lines.

## Config (`features/extras.yml`)

| Key | Default | Meaning |
|---|---|---|
| `messages.join` | `false` | `Alex joined` for players without a rank line |
| `messages.quit` | `false` | `Alex left` for players without a rank line |
| `messages.first-join-welcome` | `true` | Welcome brand-new players with their join number |
