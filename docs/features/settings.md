# Settings dialog (`settings`)

One dialog for every per-player switch on the server. Package `feature/settings`, text `lang/settings.yml`. No config
and no tables: switches are `core.player.Toggle`s that features register, stored by `core.player.PlayerSettings`
(table `settings`, loaded at login, written through).

## Opening it

- `/settings` (aliases `/options`, `/preferences`), permission `siftcore.command.settings` (everyone).
- The main menu entry `settings` (order 90) and the pause screen (`siftcore:hub/settings`); from there the dialog
  has a Back button to the menu.

## What it shows

A form with one switch per toggle the player may see (`PlayerSettings#view`: toggles without a permission, or whose
permission the player has), in registration order. The body lists each switch's label and description from the
owning feature's lang file. Currently: payment messages (economy), auction sale messages (auction), team chat spy
(teams, staff), crate win announcements (crates), mention alerts, private messages, social spy (chat, staff), death
messages (combat), teleport requests (tpa; its friends-only switch joins once a friends system exists), and other
players' chat colours (Chat group) and kill effects (Display group) from cosmetics. A feature that registers a new
toggle appears automatically.

Dialog input keys can only hold letters, digits and `_`, so `tpa-requests` becomes `tpa_requests` (made unique if
two ids would collide; the self-test checks this).

## Saving

Save stores only the switches the player flipped in the dialog: a value changed elsewhere while the dialog was open
(for example `/msgtoggle`) is never overwritten by the stale value the dialog still showed. Each change re-checks that
the toggle still exists and that the player still has its permission. The action bar confirms: `Private messages
turned off.` for one change, `Saved 2 settings.` for several, `Nothing changed.` otherwise.

## Self-test

- every switch has a label and a description;
- dialog input keys are valid and unique;
- the hub entry is registered.
