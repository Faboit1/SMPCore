# Kits and rank perks (`kits`)

Players claim kits from the `/kits` dialog or with `/kit <name>`: a starter kit once, a daily food kit every day, and
one daily supply kit per paid rank (Prospector, Baron, Tycoon), each bigger than the one below. Rank perks are
commands that work anywhere: the ender chest, crafting table, anvil, stonecutter, grindstone, smithing table, loom,
cartography table, a trash bin and a hat. Package `feature/kits`, config `features/kits.yml`, text `lang/kits.yml`,
table `kit_claims` (V008), claim box `deliveries` (V001, source `kit`).

It consumes two contracts and provides none:

| Contract | Wired | Used for |
|---|---|---|
| `CombatStatus` | the shared combat tags (`CombatTags`) | With `block-in-combat` (shipped on) kits can't be claimed in combat; perks listed in `perks.blocked-in-combat` (all of them in the shipped file) are refused, and with `close-on-combat` their open screens close when the player gets tagged |
| `CrateKeys` | the crates feature (`CratesFeature#keys()`) | A kit's `keys:` and validating that the named crates exist. No shipped kit gives keys: crates are random rewards, which the store rules don't allow a paid rank to buy; free or event kits can use it |

`KitsFeature` is constructed right after `CratesFeature` in `FeatureCatalog`. The public cancellable
`api.event.KitClaimEvent(player, kit, forced)` fires on the player's thread before every claim and every staff gift to
an online player; cancelling it gives nothing and starts no cooldown.

## Commands and permissions

`/kits` has the alias `/kit`. Staff subcommands work from the console; player-only parts are hidden from it.

| Command | Who | What it does |
|---|---|---|
| `/kits` | everyone | The kits dialog (below). From the console: every kit with its cooldown, item count and who has it |
| `/kit <name>` | everyone | Claims the kit straight away (`/kits <name>` is the same) |
| `/kits give <player> <kit>` | staff, console | Gives the kit without permission or cooldown checks and without starting the player's cooldown. Online players get it like a claim; for offline players it waits in their claim box |
| `/kits reset <player> [kit]` | staff, console | Clears one kit's cooldown, or all of them (also offline players) |
| `/kits check <player>` | staff, console | Every kit's status for that player (`ready`, `in 3h 20m`, `claimed`, `locked` when an online player lacks the permission) |
| `/ec` (`/enderchest`, `/echest`) | perk | Opens your own ender chest |
| `/ec <player>` | staff | A read-only copy of an online player's ender chest (`/ecsee` in the staff tools can edit) |
| `/craft` (`/workbench`, `/wb`) | perk | A crafting table |
| `/anvil` | perk | An anvil (costs, repairs and renaming work like the block; the anvil never breaks) |
| `/stonecutter`, `/grindstone`, `/loom` | perk | The workstation |
| `/smithing` (`/smithingtable`) | perk | A smithing table |
| `/cartography` (`/cartographytable`) | perk | A cartography table |
| `/trash` (`/disposal`) | perk | A four-row bin titled "Trash: deleted when you close it"; everything in it is deleted when it closes, except what the player's Trash protection gives back. With Trash bin mode set to the Delete button: a five-row bin titled "Trash: click Delete to delete" that only deletes on Delete and gives everything back when it closes |
| `/hat` | perk | Wears one of the held item (the old helmet goes to the hand, or into the inventory when the hand keeps the rest of its stack) |

| Permission | Default | Meaning |
|---|---|---|
| `siftcore.command.kits` | everyone | `/kits`, `/kit <name>`, the main menu's Kits button |
| `siftcore.kit.<id>` | everyone for kits with `everyone: true`, otherwise op | Seeing and claiming that kit |
| `siftcore.admin.kits` | op | `/kits give`, `/kits reset`, `/kits check` |
| `siftcore.perk.<name>` | op | One perk command: `ec`, `craft`, `anvil`, `stonecutter`, `grindstone`, `smithing`, `loom`, `cartography`, `trash`, `hat` |
| `siftcore.perk.ec.others` | op | `/ec <player>` (read only) |

Rank kits are not inherited by the plugin: a higher rank gets a lower rank's kit only when its LuckPerms group has the
node. On this server the paid groups inherit each other (`default` < `prospector` < `baron` < `tycoon`), so a node set
on `prospector` also reaches the higher ranks. The nodes the groups carry (each group adds to the ones below; the
LuckPerms commands are in the server undo log):

| Group | Kit | Perks |
|---|---|---|
| `prospector` | `siftcore.kit.prospector` | `siftcore.perk.craft`, `siftcore.perk.trash`, `siftcore.perk.hat` |
| `baron` | `siftcore.kit.baron` | `siftcore.perk.ec`, `siftcore.perk.stonecutter`, `siftcore.perk.loom`, `siftcore.perk.cartography`, `siftcore.perk.grindstone` |
| `tycoon` | `siftcore.kit.tycoon` | `siftcore.perk.anvil`, `siftcore.perk.smithing` |

Paid ranks get conveniences, never an edge in a fight: the rank kits are supplies only (no armour, weapons, golden
apples, totems, pearls or crate keys), and every perk is refused in combat.

`/feed` and `/heal` are deliberately not perks: on a PvP server they would let paying players refill health and hunger
between fights.

Audit log actions: `kits.claim` (every claim, with the kit and the claim reference), `kits.give`, `kits.reset`,
`perks.trash` (what was deleted, for refund disputes) and `perks.ec.others`.

## Screens

- **Kits dialog** (`/kits`, the main menu's Kits button with order 80): a line per kit with its status ("Daily: in
  3h 20m"), a button per kit (its tooltip is the description and status), a Claim button for what is ready ("Claim
  Daily" when one kit is ready, "Claim 3 ready kits" when several are), "Collect waiting items" when kit items wait in
  the claim box, and "Perks" when the player has any. Kits the
  player can't claim are hidden, or listed as locked with `locked-kits: show`. From the main menu the footer goes back
  to it.
- **A kit's dialog**: its description, how often it can be claimed, its status, every item (with its icon, enchantment
  glint and full tooltip on hover), the crate keys it gives, and Claim while it is ready. A refusal (in combat, the
  cooldown started meanwhile, another plugin cancelled it) shows the dialog again with the reason.
- **Perks dialog**: a button per perk the player has; it opens the perk (or wears the hat).
- **Messages**: a claim says "You claimed the Daily kit." on the action bar; items that didn't fit add a chat line
  with a click to `/kits`; a kit with crate keys adds "The Event kit gave you 1 Basic key." with a click to `/crates`.
- **Reminders** (`reminders: true`, and each player's Kit reminders and When to remind about kits settings): on join
  "Kits ready to claim: Starter, Daily." and "Kit items are waiting for room in your inventory." when that is so;
  while playing "Your Daily kit is ready." the moment a cooldown ends. In chat they are clickable; above the hotbar or
  as a title they read "Your Daily kit is ready (/kits)", and on join, when kits are ready and items are waiting at
  once, one line says both ("Kits ready: Starter, Daily, kit items waiting (/kits)"): those places show one line at a
  time, so a second line would replace the first at once. Each online player has at most one timer, set on their own
  thread for the soonest cooldown that ends (at most 6 hours ahead, then looked at again), and none at all when they
  get no reminder the moment a kit is ready (reminders off, or join only); changing either setting sets it again at
  once. Nothing scans players on a timer.

## Player settings

Registered by `KitPlayerSettings` into Crates & kits (text in `lang/kits.yml` under `kits.settings`); the crates
feature fills the other places of the group.

| Id | Place | Kind, default | Permission | What it does |
|---|---|---|---|---|
| `kit-reminders` | 2 | choice chat/actionbar/title/off, chat | `siftcore.command.kits` | How kit reminders show (`Messenger.alert`). Was a switch: stored `true` reads as chat, `false` as off. Offered while `reminders` is on |
| `trash-protect` | 6 | choice gear/valuables/off, gear | `siftcore.perk.trash` | What the trash gives back instead of deleting: gear (enchanted items and enchanted books, renamed items, shulker boxes, spawners, trial keys), also any stack worth at least `perks.trash.protect-worth` at /sell, or nothing. Valuables is offered while the server prices items (the sell feature's worth table has at least one price) and the threshold is above 0 (otherwise it reads as gear) |
| `kit-reminder-when` | 8 | choice join-and-ready/join/ready, join-and-ready | `siftcore.command.kits` | On join, the moment a kit is ready, or both. Offered while `reminders` is on |
| `kit-auto-equip` | 9 | toggle, off | `siftcore.command.kits` | Armour from claimed (or collected) kit items goes straight into empty armour slots: one helmet, chestplate, leggings and boots each, never one with curse of binding; worn armour is never replaced and the rest goes into the inventory |
| `trash-confirm` | 10 | choice delete-on-close/delete-button, delete-on-close | `siftcore.perk.trash` | Trash bin mode: delete when the bin closes, or only on its Delete button (closing gives everything back) |

**Trash and item safety.** The bin takes its items out of its slots before anything else happens to them. What is
deleted is audited (`perks.trash`, as before). What is given back goes into the inventory, the player file is saved
(with `save-player-after-trade`), and only then does what did not fit go to the claim box (source `trash`), so a crash
never leaves an item both in the saved inventory and the claim box; if the claim box is unavailable it drops at the
player's feet. When the bin closes because its player died (without keep-inventory), the items it gives back drop
where they died with the rest of their inventory, like a crafting grid; a disconnect gives them back into the
inventory before the player file is saved. Items in an open bin when the server crashes are lost, like items left in a
crafting table.

## How a claim works (money and items rules)

1. On the player's thread: the kit exists, the player has its permission, is not in combat, the cooldown is over
   (checked here for a precise message), and `KitClaimEvent` was not cancelled.
2. One economy transaction (`LedgerTx`, no money postings): a check under the economy lock that the kit is still
   ready, an apply that sets the claim time in memory (undone if storing fails), the `kit_claims` upsert, and every
   item of the kit added to the claim box (`deliveries().add`, split into stacks). So the claim time and the items are
   stored together: two claims at the same moment (spammed commands, a replayed dialog click, two region threads)
   can never both pass, and a crash can never lose the items of a claim that counted.
3. When the transaction is committed, on the player's thread, the stacks that fit into the inventory are claimed from
   the claim box (marked claimed in storage first) and handed over; stacks that don't fit stay there. The player
   collects them later from `/kits` (Collect waiting items) or the claim box. Anything that can't be handed over (the
   player left, the inventory filled up meanwhile, the server stops) goes back into the claim box, never onto the ground.
   The player's data is saved after the hand-over when `save-player-after-trade` is on.
4. The kit's crate keys are given with `CrateKeys.give` once the claim is stored (one transaction per crate). A refusal
   (the most keys a player can hold, a crate that was removed) is logged with the claim reference and the player is
   told to ask staff.

A staff gift is the same without step 1's permission and cooldown checks and without the `kit_claims` row. Resets
are a domain transaction that removes the claim times in memory and deletes the rows together.

Cooldown semantics: a kit is ready when `now >= last claim + cooldown`, using the cooldown currently configured, so
changing a cooldown applies to claims already made. If the server clock went back, nobody waits longer than one
cooldown. `once` kits stay claimed until staff reset them. Times are shown rounded up to whole seconds and then
compacted (`3h 20m`, `1d 12h`, `45s`), so right after claiming a 24h kit it reads "in 1d".

## Perks

Workstations open the vanilla menus through the 26.2 `MenuType` API (`MenuType.CRAFTING.create(player)` and so on,
on the player's thread), not the deprecated `HumanEntity#openWorkbench` family. Without a location the menu belongs to
the player's position, so it never closes for distance, recipes, anvil costs and repairs behave exactly like the
blocks, and whatever is left in a crafting grid, anvil or other input slot goes back to the player when it closes (the
same as the blocks). The ender chest is the player's real ender chest (`openInventory(getEnderChest())`).
`/ec <player>` copies the other player's ender chest on that player's thread and shows the copy in a read-only menu, so
nothing can be taken or duplicated from it. The trash deletes its contents however it closes (closed, replaced by
another screen, disconnect, death) and tells the player how many items were deleted, except what their Trash
protection keeps ("Items deleted: 5. Protected items given back: 2"); in Delete button mode it deletes on Delete and
gives everything back however it closes ("Nothing deleted. Items given back: 7"). `/hat` refuses an empty hand,
items listed in `perks.hat.blocked`, a current helmet with curse of binding (outside creative) and, when the hand
keeps part of its stack, an inventory without room for the old helmet.

Perk screens are tracked per player. With `close-on-combat` (shipped on), the screen of a perk listed in
`blocked-in-combat` closes when its player gets into combat (`CombatTagEvent`), and any click or drag in it while in
combat (for example after a staff `/combat tag`, which fires no event) closes it instead of acting. The shipped list
blocks every perk: they are paid conveniences and must never help in a fight (the combat feature's own command list
only covers `/ec`, `/craft` and `/anvil`).

## Config summary (`features/kits.yml`)

| Key | Meaning |
|---|---|
| `block-in-combat` | No claims in combat (dialogs still show the kits) |
| `reminders` | Join and ready reminders (players pick how and when, or turn them off) |
| `locked-kits` | `hide` (shipped) or `show` kits the player can't claim |
| `kits.<id>` | `name`, `description`, `icon`, `everyone`, `cooldown` (`once` or 1s to 365d), `items` (keyed by item id or any name with `material:`; `amount` up to 640, `name`, `lore` up to 8 lines, `enchantments` up to the vanilla level or 255 with `unsafe-enchantments`, `unbreakable`), `keys` (`<crate>: <amount>`, 1 to 64) |
| `perks.blocked-in-combat` | Perk ids refused in combat |
| `perks.close-on-combat` | Close those perks' screens when combat starts |
| `perks.hat.blocked` | Item ids `/hat` refuses |
| `perks.trash.protect-worth` | Shipped 10k: with Trash protection set to valuables, stacks worth at least this much at /sell are given back; 0 turns that option off. Optional (a file without it reads 10k) |

Every mistake is reported with its exact path; a broken item is left out of its kit and a kit that gives nothing (or
has no usable cooldown) is left out, so `/sift reload` refuses a broken change and keeps the old kits. Kits added by a
reload get their permission node registered right away; a changed `everyone` updates the node's default.

The shipped kits:

| Kit | Who | Cooldown | Gives |
|---|---|---|---|
| Starter | everyone | once | stone sword, pickaxe, axe and shovel, 16 bread, leather armor |
| Daily | everyone | 24h | 16 steak, 16 baked potatoes, 8 apples |
| Prospector | `prospector` and up | 24h | 32 torches, 32 oak logs, 16 steak (about $1k at shop prices) |
| Baron | `baron` and up | 24h | 64 torches, 64 oak logs, 64 glass, 8 lanterns, 32 steak, 32 bone meal (about $2.8k) |
| Tycoon | `tycoon` | 24h | 64 torches, 128 oak logs, 128 glass, 16 lanterns, 32 steak, 64 bone meal, 1 name tag (about $5.7k) |

Through inheritance a Tycoon claims the Prospector, Baron and Tycoon kits every day.

## Placeholders

| Placeholder | Value |
|---|---|
| `%siftcore_kits_ready%` | How many kits the player can claim now |
| `%siftcore_kit_<id>%` | That kit's status for the player: `ready`, `in 3h 20m`, `claimed`, or `locked` for an online player without its permission; `-` for no such kit |

## Self-test

`/sift selftest` checks that kits are configured and give something, that every kit item can be made and survives
storage, that every kit's permission is registered with its configured default, that kits only name existing crates,
the cooldown math, and that the claim times in memory equal the stored rows exactly (compared under the economy lock).

## Integration notes

- `kits` can be added to `hub.yml`'s `pause-menu.entries` (with a label in `lang/hub.yml`'s `hub.entries`) to put Kits
  in the pause screen; this feature does not edit the hub's files.
- The combat feature's `while-tagged.blocked-commands` already refuses `/kit`, `/kits`, `/ec`, `/craft` and `/anvil`;
  the other workstations are refused by this feature's own `blocked-in-combat` list, so nothing has to change there.
- The settings dialog shows the kit and trash settings in Crates & kits (see Player settings).
- `docs/permissions.md` should copy the per-rank table above.

## Tests

- Unit tests: `CooldownTest` (parsing, readiness at the boundary, a clock that went back, changed cooldowns, rounding,
  overflow), `ClaimBookTest` (claim times and their undo), `StackFitTest` (which claim box stacks fit),
  `KitsSettingsTest` (the shipped file, every setting, every mistake reported with its path, perks),
  `KitsResourcesTest` (the lang file is complete, unused-free and follows the design system; statuses and lists read
  naturally) and `KitPlayerSettingsTest` (the Crates & kits order with the crate settings, permissions, the legacy
  values of `kit-reminders`, config-dependent offering, the reminder timer, the join reminder in one line where only
  one line shows, valuables needing a worth table that prices something, trash protection, auto-equip and
  `perks.trash.protect-worth`).
- End to end (`tools/e2e`, `KitsScenarios`): `kits-dialog`, `kits-claim-all`, `kits-cooldown`, `kits-ranks`,
  `kits-claim-box`, `kits-double-submit`, `kits-combat`, `kits-admin`, `kits-perks`, `kits-workstations`,
  `kits-ec-others`, `kits-config`, `kits-persist-setup` / `kits-persist-check` (across a restart), `kits-settings`
  (the kit and trash settings and their options; auto-equip and the Delete button bin saved in the settings dialog,
  then the starter kit's leather armour worn and the bin giving back on close and deleting on Delete; through the
  API: the classic bin keeping an enchanted sword and deleting it with protection off, valuables protection giving
  back a plain stack of 64 diamonds ($25,600 at /sell) while deleting 10 diamonds, and gear protection deleting the
  same stack, kit reminders above the hotbar on join, one short line when a kit is ready and kit items wait at once
  (and no second line), and none on join when only ready reminders are wanted).
