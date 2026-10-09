# Teleport requests (`tpa`)

Players ask to teleport to each other. Package `feature/tpa`, config `features/tpa.yml`, text `lang/tpa.yml`. No
tables: requests live in memory and expire within a minute.

The player who moves gets the shared teleport warmup (`core.teleport.Teleports`: action-bar countdown, cancelled by
moving or damage, refused while combat-tagged). The destination is wherever the other player stands when the warmup
ends, read on that player's thread. The feature consumes five contracts:

| Contract | Wired | Used for |
|---|---|---|
| `VanishStatus` | staff feature | A vanished staff member looks offline to players without `siftcore.tpa.bypass` |
| `IgnoreLookup` | `NONE` (chat) | A player who ignores the sender never gets the request; the sender is told they can't send one |
| `AfkStatus` | `NONE` (AFK) | The sender is told the target is AFK, so an unanswered request makes sense |
| `FriendLookup` | `NONE` (friends) | Friends may come without a request when the target allows it (see below) |
| `CombatStatus` | core combat tags | Combat-tagged players can't send or accept requests (see "Combat" below) |

## Commands and permissions

| Command | What it does |
|---|---|
| `/tpa <player>` (alias `/tpask`) | Asks to teleport to the player |
| `/tpahere <player>` | Asks the player to teleport to you |
| `/tpaccept [player]` (alias `/tpyes`) | Accepts the only request, the named one, or opens a choice dialog when several wait |
| `/tpdeny [player]` (alias `/tpno`) | Denies the same way; the choice dialog also has Deny all |
| `/tpacancel [player]` (alias `/tpcancel`) | Withdraws your request to that player, or all of yours |
| `/tpatoggle` (alias `/tptoggle`) | Turns incoming requests off (they are declined at once) or back on |
| `/tpatoggle friends` | Lets friends come without asking, or makes them ask again (only with a friends list) |

Name arguments suggest only the players in your own requests (`/tpaccept`, `/tpdeny`: who asked you; `/tpacancel`:
who you asked) or online players you can see (`/tpa`, `/tpahere`); selectors are never accepted.

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.tpa`, `.tpahere`, `.tpaccept`, `.tpdeny`, `.tpacancel`, `.tpatoggle` | everyone | Use the command |
| `siftcore.tpa.bypass` | op | `/tpa` teleports at once with no request; `/tpahere` reaches players who turned requests off or ignore them; vanished staff they can see count as online |
| `siftcore.teleport.bypass-warmup` | op | No warmup (core node) |
| `siftcore.bypass.cooldown` | op | No cooldown between requests (core node) |

## How a request flows

1. The sender passes the checks in `TpaGate` (pure, unit tested), in this order: not yourself, online and visible,
   staff `/tpa` (instant), ignored, requests turned off. Then the request cooldown and the cancellable
   `api.event.TeleportRequestEvent`.
2. The target gets a chat line with a clickable `Click to answer`: a `ClickEvent.showDialog` carrying an accept/deny
   dialog made for that target (`Dialogs#inline`). Typing `/tpaccept` works the same.
3. Requests are keyed by sender: a player can have requests from many players at once; a new request from the same
   sender replaces the older one. Each expires on its own; the sender is told when it does.
4. Accepting takes the request atomically (`TpaRequests#take`, by request id: an old dialog can't accept a newer
   request) and starts the warmup for whoever moves. If they move, take damage or are in combat, the other player is
   told they didn't come.

## Combat

The combat feature refuses `/tpa`, `/tpahere` and `/tpaccept` while tagged, but requests are also sent from the main
menu's form and answered from the chat dialog, so the feature checks combat itself, whichever way a player acts:

- A combat-tagged player can't send a request ("You can't use teleport requests in combat. <time> left."). Without
  this a tagged player could `/tpahere` an ally into the fight from the form.
- Nobody accepts a request while either player is in combat (`TpaGate#acceptBlocked`): a tagged target can't pull a
  teammate in by accepting their `/tpa` from the chat dialog, and nobody is pulled into the sender's fight
  ("<name> is in combat. Their request waits; accept it once the fight is over."). The request stays where it was, so
  it can be accepted once the fight is over. Denying always works.
- A friend's `/tpa` skips the request only while the target isn't in combat; otherwise it becomes a normal request.
- When the warmup ends, the player who stays put is checked again (`TpaGate#unlessFighting`, in the destination
  supplier, after their position was read on their thread): the shared teleports re-check only the mover, so a player
  attacked during the 3 second warmup would otherwise still get the ally delivered mid-fight. The mover is told
  ("<name> is in combat now. The teleport was cancelled."), the other player hears they didn't come, and the request
  is used up (send a new one after the fight). Staff `/tpa` (bypass, no request) is not checked.

The main menu entry `tpa` (order 62) opens a form: a player name and who moves (I go to them / They come to me). It
also says how many requests wait for you.

## Per-player settings (shown in the settings dialog)

| Toggle | Default | Meaning |
|---|---|---|
| `tpa-requests` | on | Accept teleport requests |
| `tpa-friends` | off | Friends teleport to me without asking. Registered only when a friends list is installed. Only `/tpa` skips the request: a friend's `/tpahere` would move you without your consent, so it always asks |

## Config (`features/tpa.yml`)

| Key | Default | Meaning |
|---|---|---|
| `expire-after` | `60s` | How long a request waits (10s to 10m) |
| `warmup` | `3s` | Standing still before the one who moves teleports |
| `request-cooldown` | `5s` | Time between two requests of the same sender |

## Placeholders

| Name | Value |
|---|---|
| `tpa_requests` | Requests waiting for the player's answer |

## Design decisions

- Requests never touch storage: they are short-lived and meaningless after a restart.
- Every answer re-checks at execution time: neither player is in combat, the request must still exist (and be the
  same request), both players must be online, and neither the mover nor the player they go to may be in combat
  when the warmup ends.
- A refused request because of an ignore list reads like any refusal ("You can't send X a teleport request"), so
  the sender learns nothing about the ignore.
- Pending requests are swept once a second off the world threads; quitting drops every request of that player.
