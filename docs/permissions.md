# Permissions

Every permission node SiftCore 1.0.0 declares (131 nodes), generated with `/sift docs`. Nodes are registered with the server at startup, so LuckPerms suggests them. "Everyone" nodes are granted by default; take them away with a negated node (`/lp group default permission set <node> false`).

Rank limits (homes, auction listings, team size and similar) are numeric nodes such as `siftcore.homes.5`: the highest number a player has wins. They are described with the feature that reads them in `docs/features/`.

## Afk

| Node | Default | Description |
|---|---|---|
| `siftcore.afk.bypass-kick` | operators | Never be kicked for being AFK |

## Auction

| Node | Default | Description |
|---|---|---|
| `siftcore.auction.listings.unlimited` | nobody | No limit on auction listings |
| `siftcore.auction.sell` | everyone | List items on the auction house |

## Bounties

| Node | Default | Description |
|---|---|---|
| `siftcore.bounties.place` | everyone | Put bounties on players with /bounty &lt;player&gt; &lt;amount&gt; |

## Bypasses

| Node | Default | Description |
|---|---|---|
| `siftcore.bypass.cooldown` | operators | Skip command cooldowns |

## Chat

| Node | Default | Description |
|---|---|---|
| `siftcore.chat.bypass` | operators | Skip chat cooldown, rate limit, repeat and capitals checks, chat lock and slow mode |
| `siftcore.chat.filter.bypass` | operators | Skip the chat word filter |
| `siftcore.chat.item` | everyone | Show the held item in chat with [item] |
| `siftcore.chat.links` | operators | Post links and server addresses in chat and private messages |
| `siftcore.chat.msg.bypass` | operators | Send private messages to players who turned them off |
| `siftcore.chat.socialspy` | operators | See private messages between players with /socialspy |
| `siftcore.chat.unignorable` | operators | Can't be ignored: chat and private messages reach players who ignore you |

## Combat

| Node | Default | Description |
|---|---|---|
| `siftcore.combat.bypass` | nobody | Never be put in combat |

## Commands

| Node | Default | Description |
|---|---|---|
| `siftcore.command.afk` | everyone | Mark yourself AFK with /afk |
| `siftcore.command.afkzone` | everyone | Teleport to the AFK zone with /afkzone |
| `siftcore.command.ah` | everyone | Use the auction house with /ah |
| `siftcore.command.balance` | everyone | Use /balance |
| `siftcore.command.balance.others` | everyone | See other players' balances |
| `siftcore.command.baltop` | everyone | Use /baltop |
| `siftcore.command.bounties` | everyone | See bounties with /bounties |
| `siftcore.command.combat` | everyone | See whether you are in combat with /combat |
| `siftcore.command.crates` | everyone | Use /crates, open crates and preview them |
| `siftcore.command.delhome` | everyone | Use /delhome |
| `siftcore.command.help` | everyone | Open the help with /help |
| `siftcore.command.home` | everyone | Use /home |
| `siftcore.command.homes` | everyone | Use /homes |
| `siftcore.command.ignore` | everyone | Ignore players with /ignore |
| `siftcore.command.keyall` | everyone | See when the next keyall is with /keyall |
| `siftcore.command.links` | everyone | Open the server links with /links |
| `siftcore.command.menu` | everyone | Open the main menu with /menu |
| `siftcore.command.msg` | everyone | Send private messages with /msg |
| `siftcore.command.msgtoggle` | everyone | Turn incoming private messages on or off with /msgtoggle |
| `siftcore.command.orders` | everyone | Use buy orders with /orders (browse, deliver, your orders) |
| `siftcore.command.pay` | everyone | Use /pay |
| `siftcore.command.ping` | everyone | See your ping with /ping |
| `siftcore.command.ping.others` | everyone | See other players' ping |
| `siftcore.command.playtime` | everyone | Use /playtime |
| `siftcore.command.playtime.others` | everyone | See other players' playtime |
| `siftcore.command.reply` | everyone | Answer private messages with /r |
| `siftcore.command.report` | everyone | Report a player to staff with /report |
| `siftcore.command.rtp` | everyone | Use /rtp |
| `siftcore.command.rules` | everyone | Read the rules with /rules |
| `siftcore.command.seen` | everyone | See when a player was last online with /seen |
| `siftcore.command.sell` | everyone | Open the sell menu with /sell, and /sell mastery, /sell top and /sell history |
| `siftcore.command.sell.all` | everyone | Sell your whole inventory with /sell all |
| `siftcore.command.sell.hand` | everyone | Sell the item in your hand with /sell hand, and all of its kind with /sell hand all |
| `siftcore.command.sethome` | everyone | Use /sethome |
| `siftcore.command.settings` | everyone | Open the settings with /settings |
| `siftcore.command.shards` | everyone | See your shards with /shards |
| `siftcore.command.shards.others` | everyone | See other players' shards with /shards &lt;player&gt; |
| `siftcore.command.shardshop` | everyone | Open the shard shop with /shardshop |
| `siftcore.command.shop` | everyone | Open the shop with /shop |
| `siftcore.command.spawn` | everyone | Use /spawn |
| `siftcore.command.spawners` | everyone | List your spawners with /spawners |
| `siftcore.command.stats` | everyone | Use /stats |
| `siftcore.command.stats.others` | everyone | See other players' stats |
| `siftcore.command.team` | everyone | Use /team |
| `siftcore.command.teamchat` | everyone | Use /teamchat (/tc) |
| `siftcore.command.top` | everyone | Use /top and the leaderboards |
| `siftcore.command.tpa` | everyone | Use /tpa |
| `siftcore.command.tpacancel` | everyone | Use /tpacancel |
| `siftcore.command.tpaccept` | everyone | Use /tpaccept |
| `siftcore.command.tpahere` | everyone | Use /tpahere |
| `siftcore.command.tpatoggle` | everyone | Use /tpatoggle |
| `siftcore.command.tpdeny` | everyone | Use /tpdeny |
| `siftcore.command.worth` | everyone | See what items sell for with /worth and the price list |

## Hierarchy

| Node | Default | Description |
|---|---|---|
| `siftcore.hierarchy.owner` | nobody | Owner: punish, kick, freeze and vanish staff of any weight; only the console and other owners can do that to you. Give it to the owner only: a wildcard that covers it (siftcore.* or *) grants it too |

## Orders

| Node | Default | Description |
|---|---|---|
| `siftcore.orders.create` | everyone | Place buy orders |
| `siftcore.orders.limit.unlimited` | nobody | No limit on active buy orders |

## Pay

| Node | Default | Description |
|---|---|---|
| `siftcore.pay.unlimited` | operators | No daily /pay limit |

## Spawn

| Node | Default | Description |
|---|---|---|
| `siftcore.spawn.bypass` | operators | Build and use everything inside the protected spawn area |

## Spawners

| Node | Default | Description |
|---|---|---|
| `siftcore.spawners.bypass` | operators | Use, open and pick up anyone's spawners, without silk touch |
| `siftcore.spawners.stack.unlimited` | nobody | Stack spawners up to the hard limit of 10000 |

## Staff

| Node | Default | Description |
|---|---|---|
| `siftcore.staff.alts` | operators | List accounts sharing a player's address with /alts |
| `siftcore.staff.ban` | operators | Ban players permanently with /ban |
| `siftcore.staff.broadcast` | operators | Announce to everyone with /broadcast |
| `siftcore.staff.chat` | operators | Read and write staff chat (/sc) |
| `siftcore.staff.clearchat` | operators | Clear everyone's chat with /clearchat |
| `siftcore.staff.clearchat.bypass` | operators | Keep your chat when it is cleared |
| `siftcore.staff.ecsee` | operators | Look into players' ender chests with /ecsee |
| `siftcore.staff.ecsee.edit` | operators | Take and delete items in /ecsee |
| `siftcore.staff.freeze` | operators | Freeze players with /freeze and be told when a frozen player logs out |
| `siftcore.staff.history` | operators | See a player's punishments with /history |
| `siftcore.staff.invsee` | operators | Look into players' inventories with /invsee |
| `siftcore.staff.invsee.edit` | operators | Take and delete items in /invsee |
| `siftcore.staff.kick` | operators | Kick players with /kick |
| `siftcore.staff.mute` | operators | Mute and unmute players |
| `siftcore.staff.notify` | operators | Be told about bans, mutes, kicks, warnings and freezes by other staff |
| `siftcore.staff.reports` | operators | Get report notifications and handle reports with /reports |
| `siftcore.staff.tempban` | operators | Ban players for a while with /tempban |
| `siftcore.staff.unban` | operators | Lift bans with /unban |
| `siftcore.staff.vanish` | operators | Vanish with /vanish |
| `siftcore.staff.vanish.others` | operators | Vanish other staff with /vanish &lt;player&gt; |
| `siftcore.staff.vanish.see` | operators | See vanished staff |
| `siftcore.staff.warn` | operators | Warn players with /warn |
| `siftcore.staff.whois` | operators | Look a player up with /whois |

## Staff and admin

| Node | Default | Description |
|---|---|---|
| `siftcore.admin` | operators | Use /sift |
| `siftcore.admin.afk` | operators | See who is AFK (/afk list) and set up the AFK zone (/afkzone info, pos1, pos2, arrival, set, reset) |
| `siftcore.admin.auction` | operators | Remove listings and use /ah admin |
| `siftcore.admin.audit` | operators | Read the audit log of staff and store actions (/sift audit) |
| `siftcore.admin.backup` | operators | Back up the database and list backups (/sift backup) |
| `siftcore.admin.bounties` | operators | Inspect, remove and expire bounties with /bountyadmin |
| `siftcore.admin.chat` | operators | Lock chat, set slow mode, test the filter and look up ignore lists with /chat |
| `siftcore.admin.combat` | operators | Inspect, tag and untag players and read the kill log with /combat |
| `siftcore.admin.crates` | operators | Give and take keys, start a keyall and manage crate blocks |
| `siftcore.admin.debug` | operators | Toggle debug logging |
| `siftcore.admin.displays` | operators | Place, move and delete leaderboards and info boards with /displays |
| `siftcore.admin.eco` | operators | Change balances and read the ledger with /eco |
| `siftcore.admin.export` | operators | Export balances and the ledger as CSV (/sift export) |
| `siftcore.admin.homes` | operators | See, use and delete other players' homes with /homes &lt;player&gt; |
| `siftcore.admin.integrations` | operators | See which plugin integrations are active (/sift integrations) |
| `siftcore.admin.metrics` | operators | See internal metrics |
| `siftcore.admin.orders` | operators | Cancel any order, staff actions and /orders admin |
| `siftcore.admin.registry` | operators | List permissions and placeholders and write the reference docs (/sift permissions, placeholders, docs) |
| `siftcore.admin.reload` | operators | Reload SiftCore's files |
| `siftcore.admin.rtp` | operators | Send other players to a random spot with /rtp &lt;region&gt; &lt;player&gt; (free, no cooldown) |
| `siftcore.admin.selftest` | operators | Run the self-test |
| `siftcore.admin.sell` | operators | Look at and change players' sell mastery with /sell admin |
| `siftcore.admin.setspawn` | operators | Set the server spawn with /setspawn |
| `siftcore.admin.shards` | operators | Give, take and set shards, and see or retry waiting key purchases (/shards pending) |
| `siftcore.admin.spawn` | operators | Send other players to spawn with /spawn &lt;player&gt; |
| `siftcore.admin.spawners` | operators | Give spawners and inspect them with /spawners give, list, cycle and info |
| `siftcore.admin.stats` | operators | Reset and correct stats and rebuild leaderboards with /sift stats |
| `siftcore.admin.store` | operators | Deliver store purchases, take them back after refunds and look them up (/sift store) |
| `siftcore.admin.teams` | operators | Manage any team with /team admin |

## Teams

| Node | Default | Description |
|---|---|---|
| `siftcore.teams.create` | everyone | Create teams |
| `siftcore.teams.size.unlimited` | nobody | Teams you own have no member limit |
| `siftcore.teams.spy` | operators | See the chat of every team |

## Teleport

| Node | Default | Description |
|---|---|---|
| `siftcore.teleport.bypass-warmup` | operators | Teleport without a warmup |

## Tpa

| Node | Default | Description |
|---|---|---|
| `siftcore.tpa.bypass` | operators | Staff: /tpa teleports at once without a request, and /tpahere reaches players who turned requests off |

## Worth

| Node | Default | Description |
|---|---|---|
| `siftcore.worth.details` | operators | See where a /worth price comes from |
