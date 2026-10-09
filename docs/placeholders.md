# Placeholders

Every placeholder SiftCore 1.0.0 provides (118), generated with `/sift docs`. With PlaceholderAPI installed they are `%siftcore_<name>%`; plugins can also read them through the API (`SiftCoreApi#placeholders`). A name ending in `<...>` takes the rest of the placeholder as an argument, for example `%siftcore_baltop_name_1%`.

| Placeholder | Shows |
|---|---|
| `%siftcore_afk_count%` | Players who are AFK right now (vanished staff not counted) |
| `%siftcore_afk_status%` | AFK for a player who is away from the keyboard, empty otherwise |
| `%siftcore_afk_time%` | How long the player has been AFK (like 5m), empty when not AFK |
| `%siftcore_afk_zone_next%` | Seconds until the player's next shard in the AFK zone, empty when not earning |
| `%siftcore_afk_zone_players%` | Players in the AFK zone (vanished staff not counted) |
| `%siftcore_afk_zone_today%` | Shards the player earned in the AFK zone today |
| `%siftcore_auction_claims%` | Items waiting in your claim box |
| `%siftcore_auction_listings%` | Your active auction listings |
| `%siftcore_balance%` | Your money, formatted ($1,500 or $2.5m) |
| `%siftcore_balance_exact%` | Your money with every digit ($2,500,000) |
| `%siftcore_balance_number%` | Your money without the currency sign (1,500 or 2.5m) |
| `%siftcore_balance_raw%` | Your money as a plain number (2500000) |
| `%siftcore_baltop_name_<rank>%` | Name at a leaderboard place (1-100) |
| `%siftcore_baltop_rank%` | Your place on the money leaderboard (0 when unranked) |
| `%siftcore_baltop_value_<rank>%` | Money at a leaderboard place, formatted |
| `%siftcore_bounty_top_name_<n>%` | Name of the player with the n-th biggest bounty (1-20) |
| `%siftcore_bounty_top_value_<n>%` | The n-th biggest bounty, formatted |
| `%siftcore_bounty_total%` | The bounty on you, formatted ($50,000) |
| `%siftcore_bounty_total_raw%` | The bounty on you as a plain number |
| `%siftcore_chat_color%` | Your chat colour: a colour name, #RRGGBB or #RRGGBB:#RRGGBB for a gradient; empty without one |
| `%siftcore_chat_ignoring%` | How many players you ignore |
| `%siftcore_chat_reply%` | Who /r answers, - when nobody |
| `%siftcore_chat_slowmode%` | The chat slow mode gap in seconds, 0 when off |
| `%siftcore_combat_tagged%` | Whether you are in combat (true or false) |
| `%siftcore_combat_time%` | Whole seconds of combat left (0 when not in combat) |
| `%siftcore_display_name%` | Your nickname, or your name without one |
| `%siftcore_display_name_mm%` | Your nickname (or name) in its colours as MiniMessage, for the tab list |
| `%siftcore_friends_count%` | Your number of friends (0 while not loaded) |
| `%siftcore_friends_limit%` | Your friend limit (rank, default and hard cap) |
| `%siftcore_friends_online%` | Your friends online now, vanished ones not counted |
| `%siftcore_friends_requests%` | Friend requests waiting for you |
| `%siftcore_homes_count%` | How many homes you have set |
| `%siftcore_homes_limit%` | How many homes you may set (unlimited for no limit) |
| `%siftcore_join_message%` | Your custom join message as it shows now (with {name}), empty without one |
| `%siftcore_keyall_countdown%` | Time until the next keyall (1h 5m), - when it is off |
| `%siftcore_keyall_reward%` | What the next keyall gives (1 Basic key), - when it is off |
| `%siftcore_keys_<crate>%` | Your keys of a crate (keys_basic) |
| `%siftcore_keys_total%` | Your keys of every crate together |
| `%siftcore_kill_effect%` | Your kill effect (hearts, flames...), empty without one |
| `%siftcore_kit_<kit>%` | A kit's status for you: ready, in 3h 20m, claimed, locked (- for no such kit) |
| `%siftcore_kits_ready%` | How many of your kits you can claim now |
| `%siftcore_leave_message%` | Your custom leave message as it shows now (with {name}), empty without one |
| `%siftcore_nick%` | Your nickname as plain text, empty without one |
| `%siftcore_orders_active%` | Your active buy orders |
| `%siftcore_orders_best_<item>%` | The best price each of open orders for an item, like orders_best_diamond (empty when none) |
| `%siftcore_orders_held%` | Money your active orders hold |
| `%siftcore_orders_limit%` | How many buy orders you may have at once (a number or unlimited) |
| `%siftcore_orders_open%` | Active buy orders on the server |
| `%siftcore_orders_top_item_<n>%` | The item of the n-th biggest open order (1-10) |
| `%siftcore_orders_top_left_<n>%` | Items the n-th biggest open order still wants |
| `%siftcore_orders_top_owner_<n>%` | Who placed the n-th biggest open order |
| `%siftcore_orders_top_price_<n>%` | The price each of the n-th biggest open order |
| `%siftcore_orders_waiting%` | Delivered items waiting for you in your orders |
| `%siftcore_orders_wanted_<item>%` | Items still wanted by open orders for an item, like orders_wanted_diamond |
| `%siftcore_rank%` | Your rank as plain text (LuckPerms; empty for the default group) |
| `%siftcore_rank_color%` | Your rank's colour as #RRGGBB (the first colour of a gradient), empty without one |
| `%siftcore_rank_group%` | Your primary LuckPerms group in lowercase (default without LuckPerms) |
| `%siftcore_sell_mastery_<category>%` | Your sell mastery level in a category (0-5) |
| `%siftcore_sell_multiplier%` | Your sell multiplier from sell.yml multipliers (1 when none apply; mastery not included) |
| `%siftcore_sell_multiplier_<category>%` | Your multiplier for a sell category, rank plus mastery, like 1.6 |
| `%siftcore_sell_sold%` | Everything you sold to the server, at base value (what mastery counts) |
| `%siftcore_sell_top_name_<n>%` | Name of the n-th best seller (1-10) |
| `%siftcore_sell_top_value_<n>%` | What the n-th best seller sold, formatted (1-10) |
| `%siftcore_shards%` | Your shards with separators (1,250) |
| `%siftcore_shards_raw%` | Your shards as a plain number |
| `%siftcore_spawners_count%` | How many spawner blocks you own |
| `%siftcore_spawners_stacked%` | How many spawners you own in all, counting stacks |
| `%siftcore_spawners_stored%` | Items waiting in your spawners' storage |
| `%siftcore_spawners_xp%` | XP waiting in your spawners |
| `%siftcore_staff_reports_open%` | Number of open player reports |
| `%siftcore_staff_vanished%` | true when the player is vanished, otherwise false |
| `%siftcore_staff_visible_online%` | Online players, not counting vanished staff |
| `%siftcore_stats_best_streak%` | Your best kill streak (online players) |
| `%siftcore_stats_blocks%` | Blocks you mined (online players) |
| `%siftcore_stats_deaths%` | Your deaths (online players) |
| `%siftcore_stats_earned%` | Money you earned ($1.5m) (online players) |
| `%siftcore_stats_kdr%` | Your kills per death with two decimals (1.50) (online players) |
| `%siftcore_stats_kills%` | Your kills (online players) |
| `%siftcore_stats_mobs%` | Mobs you killed (online players) |
| `%siftcore_stats_playtime%` | Your active playtime (3d 4h) (online players) |
| `%siftcore_stats_playtime_hours%` | Your active playtime in whole hours (online players) |
| `%siftcore_stats_streak%` | Your current kill streak (online players) |
| `%siftcore_tag%` | Your chat tag as MiniMessage (colours and gradients), empty without one |
| `%siftcore_tag_id%` | The id of your chat tag, empty without one |
| `%siftcore_tag_plain%` | Your chat tag as plain text, empty without one |
| `%siftcore_team_members%` | Members in your team (0 without a team) |
| `%siftcore_team_name%` | Your team's name (empty without a team) |
| `%siftcore_team_online%` | Members of your team online now (0 without a team) |
| `%siftcore_team_role%` | Your role in your team: owner, admin or member (empty without a team) |
| `%siftcore_top_blocks_name_<n>%` | Name at place &lt;n&gt; (1-100) of the blocks leaderboard, - when empty |
| `%siftcore_top_blocks_rank%` | Your place on the blocks leaderboard, 0 when not listed |
| `%siftcore_top_blocks_value_<n>%` | Value at place &lt;n&gt; (1-100) of the blocks leaderboard, - when empty |
| `%siftcore_top_deaths_name_<n>%` | Name at place &lt;n&gt; (1-100) of the deaths leaderboard, - when empty |
| `%siftcore_top_deaths_rank%` | Your place on the deaths leaderboard, 0 when not listed |
| `%siftcore_top_deaths_value_<n>%` | Value at place &lt;n&gt; (1-100) of the deaths leaderboard, - when empty |
| `%siftcore_top_earned_name_<n>%` | Name at place &lt;n&gt; (1-100) of the earned leaderboard, - when empty |
| `%siftcore_top_earned_rank%` | Your place on the earned leaderboard, 0 when not listed |
| `%siftcore_top_earned_value_<n>%` | Value at place &lt;n&gt; (1-100) of the earned leaderboard, - when empty |
| `%siftcore_top_kdr_name_<n>%` | Name at place &lt;n&gt; (1-100) of the kdr leaderboard, - when empty |
| `%siftcore_top_kdr_rank%` | Your place on the kdr leaderboard, 0 when not listed |
| `%siftcore_top_kdr_value_<n>%` | Value at place &lt;n&gt; (1-100) of the kdr leaderboard, - when empty |
| `%siftcore_top_kills_name_<n>%` | Name at place &lt;n&gt; (1-100) of the kills leaderboard, - when empty |
| `%siftcore_top_kills_rank%` | Your place on the kills leaderboard, 0 when not listed |
| `%siftcore_top_kills_value_<n>%` | Value at place &lt;n&gt; (1-100) of the kills leaderboard, - when empty |
| `%siftcore_top_mobs_name_<n>%` | Name at place &lt;n&gt; (1-100) of the mobs leaderboard, - when empty |
| `%siftcore_top_mobs_rank%` | Your place on the mobs leaderboard, 0 when not listed |
| `%siftcore_top_mobs_value_<n>%` | Value at place &lt;n&gt; (1-100) of the mobs leaderboard, - when empty |
| `%siftcore_top_money_name_<n>%` | Name at place &lt;n&gt; (1-100) of the money leaderboard, - when empty |
| `%siftcore_top_money_rank%` | Your place on the money leaderboard, 0 when not listed |
| `%siftcore_top_money_value_<n>%` | Value at place &lt;n&gt; (1-100) of the money leaderboard, - when empty |
| `%siftcore_top_playtime_name_<n>%` | Name at place &lt;n&gt; (1-100) of the playtime leaderboard, - when empty |
| `%siftcore_top_playtime_rank%` | Your place on the playtime leaderboard, 0 when not listed |
| `%siftcore_top_playtime_value_<n>%` | Value at place &lt;n&gt; (1-100) of the playtime leaderboard, - when empty |
| `%siftcore_top_streak_name_<n>%` | Name at place &lt;n&gt; (1-100) of the streak leaderboard, - when empty |
| `%siftcore_top_streak_rank%` | Your place on the streak leaderboard, 0 when not listed |
| `%siftcore_top_streak_value_<n>%` | Value at place &lt;n&gt; (1-100) of the streak leaderboard, - when empty |
| `%siftcore_tpa_requests%` | Teleport requests waiting for your answer |
| `%siftcore_worth_<item>%` | What one plain item sells for, like worth_diamond (empty when it can't be sold) |
