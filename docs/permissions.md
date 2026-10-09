# Permissions

Every permission node SiftCore 1.0.0 declares (188 nodes), generated with `/sift docs`. Nodes are registered with the server at startup, so LuckPerms suggests them. "Everyone" nodes are granted by default; take them away with a negated node (`/lp group default permission set <node> false`).

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
| `siftcore.chat.color` | operators | Chat in a vanilla colour of your choice (/chatcolor) |
| `siftcore.chat.color.hex` | operators | Chat in any hex colour or a gradient (/chatcolor) |
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
| `siftcore.command.balance.others` | everyone | See other players' balances (when their balance privacy lets you) |
| `siftcore.command.baltop` | everyone | Use /baltop |
| `siftcore.command.booster` | everyone | See the server sell booster and what comes next with /booster |
| `siftcore.command.bounties` | everyone | See bounties with /bounties |
| `siftcore.command.claims` | everyone | Open your claim box with /claims |
| `siftcore.command.combat` | everyone | See whether you are in combat with /combat |
| `siftcore.command.cosmetics` | everyone | Open the cosmetics menu with /cosmetics |
| `siftcore.command.crates` | everyone | Use /crates, open crates and preview them |
| `siftcore.command.delhome` | everyone | Use /delhome |
| `siftcore.command.friend` | everyone | Use /friend (/f, /friends) |
| `siftcore.command.help` | everyone | Open the help with /help |
| `siftcore.command.home` | everyone | Use /home |
| `siftcore.command.homes` | everyone | Use /homes |
| `siftcore.command.ignore` | everyone | Ignore players with /ignore |
| `siftcore.command.keyall` | everyone | See when the next keyall is with /keyall |
| `siftcore.command.killeffect` | everyone | Pick a kill effect with /killeffect |
| `siftcore.command.kits` | everyone | Use /kits and claim the kits you have |
| `siftcore.command.links` | everyone | Open the server links with /links |
| `siftcore.command.menu` | everyone | Open the main menu with /menu |
| `siftcore.command.msg` | everyone | Send private messages with /msg |
| `siftcore.command.msgtoggle` | everyone | Turn incoming private messages on or off with /msgtoggle |
| `siftcore.command.nick` | operators | Set a nickname in a vanilla colour with /nick |
| `siftcore.command.orders` | everyone | Use buy orders with /orders (browse, deliver, your orders) |
| `siftcore.command.pay` | everyone | Use /pay |
| `siftcore.command.ping` | everyone | See your ping with /ping |
| `siftcore.command.ping.others` | everyone | See other players' ping |
| `siftcore.command.playtime` | everyone | Use /playtime |
| `siftcore.command.playtime.others` | everyone | See other players' playtime |
| `siftcore.command.profile` | everyone | See player cards with /profile and a sneak right-click |
| `siftcore.command.purchases` | everyone | See your store purchases with /purchases |
| `siftcore.command.realname` | everyone | See who uses a nickname with /realname |
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
| `siftcore.command.sidebar` | everyone | Show or hide your sidebar with /sidebar |
| `siftcore.command.spawn` | everyone | Use /spawn |
| `siftcore.command.spawners` | everyone | List your spawners with /spawners |
| `siftcore.command.stats` | everyone | Use /stats |
| `siftcore.command.stats.others` | everyone | See other players' stats |
| `siftcore.command.tags` | everyone | Pick a chat tag with /tags |
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

## Friends

| Node | Default | Description |
|---|---|---|
| `siftcore.friends.limit.unlimited` | nobody | Have as many friends as the hard cap allows |

## Hierarchy

| Node | Default | Description |
|---|---|---|
| `siftcore.hierarchy.owner` | nobody | Owner: punish, kick, freeze and vanish staff of any weight; only the console and other owners can do that to you. Give it to the owner only: a wildcard that covers it (siftcore.* or *) grants it too |

## Join

| Node | Default | Description |
|---|---|---|
| `siftcore.join.full` | nobody | Join when the server is full (a rank perk). Read from LuckPerms at login: only a LuckPerms grant counts, operators included |
| `siftcore.join.full.staff` | nobody | Join when the server is full, for staff. Read from LuckPerms at login: only a LuckPerms grant counts (or bypassesPlayerLimit in ops.json, which the server checks itself) |
| `siftcore.join.message` | operators | Your rank is announced when you join and leave |
| `siftcore.join.message.custom` | operators | Write your own join and leave messages with /joinmessage and /leavemessage |

## Killeffect

| Node | Default | Description |
|---|---|---|
| `siftcore.killeffect.*` | operators | Use every kill effect |
| `siftcore.killeffect.ender` | operators | Use the ender kill effect |
| `siftcore.killeffect.flames` | operators | Use the flames kill effect |
| `siftcore.killeffect.hearts` | operators | Use the hearts kill effect |
| `siftcore.killeffect.lightning` | operators | Use the lightning kill effect |
| `siftcore.killeffect.notes` | operators | Use the notes kill effect |
| `siftcore.killeffect.souls` | operators | Use the souls kill effect |
| `siftcore.killeffect.totem` | operators | Use the totem kill effect |

## Kit

| Node | Default | Description |
|---|---|---|
| `siftcore.kit.baron` | operators | Claim the Baron kit |
| `siftcore.kit.daily` | everyone | Claim the Daily kit |
| `siftcore.kit.prospector` | operators | Claim the Prospector kit |
| `siftcore.kit.starter` | everyone | Claim the Starter kit |
| `siftcore.kit.tycoon` | operators | Claim the Tycoon kit |

## Nick

| Node | Default | Description |
|---|---|---|
| `siftcore.nick.gradient` | operators | Give your nickname a hex colour or a gradient |

## Orders

| Node | Default | Description |
|---|---|---|
| `siftcore.orders.create` | everyone | Place buy orders |
| `siftcore.orders.limit.unlimited` | nobody | No limit on active buy orders |

## Pay

| Node | Default | Description |
|---|---|---|
| `siftcore.pay.unlimited` | operators | No daily /pay limit |

## Perk

| Node | Default | Description |
|---|---|---|
| `siftcore.perk.anvil` | operators | Opens an anvil anywhere (/anvil) |
| `siftcore.perk.cartography` | operators | Opens a cartography table anywhere (/cartography) |
| `siftcore.perk.craft` | operators | Opens a crafting table anywhere (/craft) |
| `siftcore.perk.ec` | operators | Opens your ender chest anywhere (/ec) |
| `siftcore.perk.ec.others` | operators | Look into another online player's ender chest with /ec &lt;player&gt; (read only) |
| `siftcore.perk.grindstone` | operators | Opens a grindstone anywhere (/grindstone) |
| `siftcore.perk.hat` | operators | Wears the item in your hand (/hat) |
| `siftcore.perk.loom` | operators | Opens a loom anywhere (/loom) |
| `siftcore.perk.smithing` | operators | Opens a smithing table anywhere (/smithing) |
| `siftcore.perk.stonecutter` | operators | Opens a stonecutter anywhere (/stonecutter) |
| `siftcore.perk.trash` | operators | Opens a bin that deletes what you put in it (/trash) |

## Settings

| Node | Default | Description |
|---|---|---|
| `siftcore.settings.hide-rank` | nobody | Offer the 'Show my rank' setting (rank groups) |

## Spawn

| Node | Default | Description |
|---|---|---|
| `siftcore.spawn.bypass` | operators | Build and use everything inside the protected spawn area |
| `siftcore.spawn.fly` | operators | Fly with /fly inside the protected spawn area (a rank perk; never outside spawn) |

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
| `siftcore.admin.booster` | operators | Start, stop and list sell boosters with /sift booster |
| `siftcore.admin.bounties` | operators | Inspect, remove and expire bounties with /bountyadmin |
| `siftcore.admin.chat` | operators | Lock chat, set slow mode, test the filter and look up ignore lists with /chat |
| `siftcore.admin.combat` | operators | Inspect, tag and untag players and read the kill log with /combat |
| `siftcore.admin.cosmetics` | operators | See cosmetics status and reset a player's cosmetics with /cosmetics admin |
| `siftcore.admin.crates` | operators | Give and take keys, start a keyall and manage crate blocks |
| `siftcore.admin.debug` | operators | Toggle debug logging |
| `siftcore.admin.displays` | operators | Place, move and delete leaderboards and info boards with /displays |
| `siftcore.admin.eco` | operators | Change balances and read the ledger with /eco; see every balance |
| `siftcore.admin.export` | operators | Export balances and the ledger as CSV (/sift export) |
| `siftcore.admin.friends` | operators | Use /sift friends |
| `siftcore.admin.homes` | operators | See, use and delete other players' homes with /homes &lt;player&gt; |
| `siftcore.admin.integrations` | operators | See which plugin integrations are active (/sift integrations) |
| `siftcore.admin.kits` | operators | Give kits, reset kit cooldowns and check players' kits with /kits |
| `siftcore.admin.metrics` | operators | See internal metrics |
| `siftcore.admin.nick` | operators | Set and clear other players' nicknames with /nick &lt;player&gt; |
| `siftcore.admin.orders` | operators | Cancel any order, staff actions and /orders admin |
| `siftcore.admin.registry` | operators | List permissions and placeholders and write the reference docs (/sift permissions, placeholders, docs) |
| `siftcore.admin.reload` | operators | Reload SiftCore's files |
| `siftcore.admin.rtp` | operators | Send other players to a random spot with /rtp &lt;region&gt; &lt;player&gt; (free, no cooldown) |
| `siftcore.admin.scoreboard` | operators | Refresh, inspect and preview sidebars with /sidebar refresh, status and preview |
| `siftcore.admin.selftest` | operators | Run the self-test |
| `siftcore.admin.sell` | operators | Look at and change players' sell mastery with /sell admin |
| `siftcore.admin.setspawn` | operators | Set the server spawn with /setspawn |
| `siftcore.admin.settings` | operators | See and change other players' settings with /sift settings |
| `siftcore.admin.shards` | operators | Give, take and set shards, and see or retry waiting key purchases (/shards pending) |
| `siftcore.admin.spawn` | operators | Send other players to spawn with /spawn &lt;player&gt; |
| `siftcore.admin.spawners` | operators | Give spawners and inspect them with /spawners give, list, cycle and info |
| `siftcore.admin.stats` | operators | Reset and correct stats and rebuild leaderboards with /sift stats |
| `siftcore.admin.store` | operators | Deliver store purchases, take them back after refunds and look them up (/sift store) |
| `siftcore.admin.teams` | operators | Manage any team with /team admin |

## Stats

| Node | Default | Description |
|---|---|---|
| `siftcore.stats.hide` | nobody | Offer the 'Hide me from leaderboards' setting (staff and test accounts) |

## Tags

| Node | Default | Description |
|---|---|---|
| `siftcore.tags.baron` | operators | Use the Baron chat tags (and the Prospector ones) |
| `siftcore.tags.prospector` | operators | Use the Prospector chat tags |
| `siftcore.tags.tycoon` | operators | Use the Tycoon chat tags and monthly exclusives (and the lower tiers' tags) |

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
