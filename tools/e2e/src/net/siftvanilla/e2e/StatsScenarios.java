package net.siftvanilla.e2e;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.siftvanilla.siftcore.api.economy.Currency;
import net.siftvanilla.siftcore.api.economy.TransactionResult;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.link.StatsRecorder.Stat;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.Audience;
import net.siftvanilla.siftcore.economy.LedgerTx;
import net.siftvanilla.siftcore.feature.stats.RankAlerts;
import net.siftvanilla.siftcore.feature.stats.StatsFeature;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Stats and leaderboards: the dialogs, every counter's real source, staff tools, placeholders, persistence and the
 * stats settings (hiding from the leaderboards, climb alerts, balance privacy).
 */
final class StatsScenarios {

    private static final String PRIVACY_PAGE = "Privacy settings";

    private StatsScenarios() {
    }

    private record Named(String name, Body body) implements Scenario {
        @Override
        public void run(E2E e2e) throws Exception {
            this.body.run(e2e);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run(E2E e2e) throws Exception;
    }

    private static Scenario of(String name, Body body) {
        return new Named(name, body);
    }

    static List<Scenario> all() {
        List<Scenario> list = new ArrayList<>();
        list.add(of("stats-dialog", StatsScenarios::dialog));
        list.add(of("stats-sources", StatsScenarios::sources));
        list.add(of("stats-top", StatsScenarios::top));
        list.add(of("stats-persist", StatsScenarios::persist));
        list.add(of("stats-vanish-playtime", StatsScenarios::vanishPlaytime));
        list.add(of("stats-settings", StatsScenarios::settings));
        return list;
    }

    /** A vanished staff member's playtime stands still, so /playtime <name> doesn't show they are online. */
    static void vanishPlaytime(E2E e2e) {
        String modName = e2e.name("PlayMod");
        e2e.bot(modName);
        e2e.console("op " + modName);
        UUID mod = e2e.uuid(modName);
        try {
            e2e.step("visible: the playtime grows");
            long start = stat(e2e, mod, Stat.PLAYTIME_SECONDS);
            e2e.eventually(() -> stat(e2e, mod, Stat.PLAYTIME_SECONDS) >= start + 2, "the counter grows: " + stat(e2e, mod, Stat.PLAYTIME_SECONDS));

            e2e.step("vanished: it stands still");
            e2e.console("vanish " + modName);
            e2e.eventually(() -> "true".equals(placeholder(e2e, modName, "staff_vanished")), "vanished");
            e2e.sleep(1_200);
            long hidden = stat(e2e, mod, Stat.PLAYTIME_SECONDS);
            e2e.sleep(3_500);
            e2e.expect(stat(e2e, mod, Stat.PLAYTIME_SECONDS) == hidden, "no playtime while vanished: " + hidden + " -> "
                + stat(e2e, mod, Stat.PLAYTIME_SECONDS));

            e2e.step("visible again: it grows again");
            e2e.console("vanish " + modName);
            e2e.eventually(() -> "false".equals(placeholder(e2e, modName, "staff_vanished")), "visible");
            e2e.eventually(() -> stat(e2e, mod, Stat.PLAYTIME_SECONDS) >= hidden + 2, "the counter grows again");
        } finally {
            e2e.console("deop " + modName);
        }
    }

    // ------------------------------------------------------------------ player settings

    /**
     * Hiding from the leaderboards (offered only with {@code siftcore.stats.hide}, switched on in the Privacy page,
     * kept while offline, waiting for the permission when back without it), climb alerts (a choice in the dialog, then
     * off through the API) and balance privacy for an offline player (hidden from players, shown to staff, the console
     * and once the owner allows it again).
     */
    static void settings(E2E e2e) {
        String hiderName = e2e.name("StHide");
        String climberName = e2e.name("StClimb");
        String privateName = e2e.name("StPriv");
        String viewerName = e2e.name("StView");
        Bot hider = e2e.bot(hiderName);
        Bot climber = e2e.bot(climberName);
        Bot owner = e2e.bot(privateName);
        Bot viewer = e2e.bot(viewerName);
        UUID hiderId = e2e.uuid(hiderName);
        UUID climberId = e2e.uuid(climberName);
        try {
            e2e.step("a staff account leads the mobs board");
            e2e.console("sift stats set " + hiderName + " mobs 900000000");
            e2e.eventually(() -> stat(e2e, hiderId, Stat.MOBS_KILLED) == 900_000_000L, "set");
            refreshBoards(e2e);
            e2e.expect(hiderName.equals(top(e2e, "mobs", 1)), "first on mobs: " + top(e2e, "mobs", 1));

            e2e.step("without siftcore.stats.hide the switch is not offered");
            Map<String, List<String>> privacy = CombatScenarios.groupInputs(e2e, hider, "privacy", PRIVACY_PAGE);
            e2e.expect(!privacy.containsKey("hide_from_leaderboards"), "no hide switch: " + privacy.keySet());
            e2e.expect(List.of("everyone", "nobody").equals(privacy.get("balance_privacy"))
                || List.of("everyone", "friends", "nobody").equals(privacy.get("balance_privacy")), "balance privacy: " + privacy);
            e2e.expect(privacy.containsKey("hide_coordinates"), "streamer mode (read by death locations): " + privacy.keySet());

            e2e.step("with the permission they hide through the Privacy page, and leave every board");
            e2e.onPlayer(hiderName, () -> e2e.player(hiderName).addAttachment(harness(), SharedSettings.HIDE_FROM_LEADERBOARDS_NODE, true));
            CombatScenarios.editSettings(e2e, hider, "privacy", PRIVACY_PAGE, Map.of("hide_from_leaderboards", true));
            e2e.eventually(() -> hider.anyFeedbackContains("Hide me from leaderboards turned on"), "saved: " + hider.chat() + " " + hider.actionBar());
            refreshBoards(e2e);
            e2e.expect(!hiderName.equals(top(e2e, "mobs", 1)), "off the mobs board: " + top(e2e, "mobs", 1));
            e2e.expect("0".equals(placeholder(e2e, hiderName, "top_mobs_rank")), "no place");

            e2e.step("offline, the stored choice keeps them off");
            hider.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(hiderName) == null, "left");
            refreshBoards(e2e);
            e2e.expect(!hiderName.equals(top(e2e, "mobs", 1)), "still off while offline: " + top(e2e, "mobs", 1));

            e2e.step("back without the permission: listed again, the choice is kept for when they have it");
            e2e.bot(hiderName);
            refreshBoards(e2e);
            e2e.expect(hiderName.equals(top(e2e, "mobs", 1)), "back on the board: " + top(e2e, "mobs", 1));
            e2e.expect("true".equals(CombatScenarios.stored(e2e, hiderId, "hide-from-leaderboards")), "the choice is kept");

            e2e.step("climb alerts at any place, through the dialog");
            CombatScenarios.editSettings(e2e, climber, "combat", CombatScenarios.COMBAT_PAGE, Map.of("leaderboard_rank_alerts", "all"));
            e2e.eventually(() -> climber.anyFeedbackContains("Leaderboard climb alerts set to Any place"), "saved: " + climber.chat());
            climber.clearLogs();
            e2e.console("sift stats set " + climberName + " blocks 950000000");
            e2e.eventually(() -> stat(e2e, climberId, Stat.BLOCKS_MINED) == 950_000_000L, "set");
            refreshBoards(e2e);
            e2e.eventually(() -> climber.chatContains("You climbed to number 1 on the Blocks mined leaderboard."), "told: " + climber.chat());

            e2e.step("off through the API: a new first place is not told");
            CombatScenarios.set(e2e, climberName, StatsFeature.RANK_ALERTS, RankAlerts.OFF);
            climber.clearLogs();
            e2e.console("sift stats set " + climberName + " kills 950000000");
            e2e.eventually(() -> stat(e2e, climberId, Stat.KILLS) == 950_000_000L, "set");
            refreshBoards(e2e);
            e2e.expect(climberName.equals(top(e2e, "kills", 1)), "first on kills");
            e2e.sleep(500);
            e2e.expect(!climber.chatContains("You climbed"), "no alert: " + climber.chat());

            e2e.step("balance privacy: an offline player's balance is hidden from other players");
            e2e.console("eco set " + privateName + " 12345");
            e2e.eventually(() -> e2e.money(privateName) == 12_345, "funded");
            CombatScenarios.set(e2e, privateName, SharedSettings.BALANCE_PRIVACY, Audience.NOBODY);
            owner.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(privateName) == null, "left");
            Bot.SeenDialog hidden = openStats(e2e, viewer, privateName, privateName + "'s stats");
            expectBody(e2e, hidden, "Balance hidden");
            e2e.expect(!hidden.bodyText().contains("$12,345"), "no amount: " + hidden.body());

            e2e.step("staff, the console and the owner's change see it");
            e2e.console("op " + viewerName);
            Bot.SeenDialog staffView = openStats(e2e, viewer, privateName, privateName + "'s stats");
            expectBody(e2e, staffView, "Balance $12,345");
            e2e.console("deop " + viewerName);
            String console = String.join("\n", CombatScenarios.output(e2e, "stats " + privateName, 1_000));
            e2e.expect(console.contains("balance $12,345"), "the console:\n" + console);
            CombatScenarios.set(e2e, privateName, SharedSettings.BALANCE_PRIVACY, Audience.EVERYONE);
            Bot.SeenDialog open = openStats(e2e, viewer, privateName, privateName + "'s stats");
            expectBody(e2e, open, "Balance $12,345");
        } finally {
            e2e.console("deop " + viewerName);
            // These records would top later runs' boards.
            e2e.console("sift stats reset " + hiderName);
            e2e.console("sift stats reset " + climberName);
        }
    }

    /** Rebuilds the leaderboards now and waits for it (retrying while a timed rebuild runs). */
    private static void refreshBoards(E2E e2e) {
        for (int attempt = 0; attempt < 10; attempt++) {
            List<String> lines = CombatScenarios.output(e2e, "sift stats refresh", 1_500);
            if (String.join(" ", lines).contains("Leaderboards rebuilt")) {
                return;
            }
            e2e.sleep(500);
        }
        throw new E2E.Failure("the leaderboards could not be rebuilt");
    }

    /** The name at a place of a board (works for offline players too). */
    private static String top(E2E e2e, String board, int place) {
        return e2e.services().placeholders().resolve(null, "top_" + board + "_name_" + place);
    }

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    // ------------------------------------------------------------------ helpers

    private static StatsRecorder recorder(E2E e2e) {
        return e2e.feature(StatsFeature.class).recorder();
    }

    private static long stat(E2E e2e, UUID player, Stat stat) {
        return recorder(e2e).get(player, stat);
    }

    private static String placeholder(E2E e2e, String player, String name) {
        return e2e.services().placeholders().resolve(e2e.player(player), name);
    }

    /** A column of the player's row in the stats table, or -1 when there is no row. */
    private static long stored(E2E e2e, UUID player, String column) {
        try {
            return e2e.services().database().read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + column + " FROM stats WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : -1L;
                    }
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("could not read the stats table: " + e);
        }
    }

    private static void transact(E2E e2e, LedgerTx tx) throws Exception {
        TransactionResult result = e2e.services().ledger().execute(tx);
        e2e.expect(result.success(), "the transaction succeeds (" + result.status() + " " + result.reason() + ")");
        result.committed().get(10, TimeUnit.SECONDS);
    }

    /** Opens /stats (or /stats <target>) and waits for the fresh dialog. */
    private static Bot.SeenDialog openStats(E2E e2e, Bot bot, String target, String title) {
        bot.clearLogs();
        bot.command(target == null ? "stats" : "stats " + target);
        return e2e.dialog(bot, title);
    }

    private static void expectBody(E2E e2e, Bot.SeenDialog dialog, String... parts) {
        String body = dialog.bodyText();
        for (String part : parts) {
            e2e.expect(body.contains(part), "'" + part + "' in the body of " + dialog.title() + ":\n" + body);
        }
    }

    /** Runs on the player's thread and returns a block position next to them that this thread owns, as x y z. */
    private static int[] workSpot(E2E e2e, String name) {
        return e2e.onPlayer(name, () -> {
            Player player = Bukkit.getPlayerExact(name);
            Block feet = player.getLocation().getBlock();
            for (int[] offset : new int[][] {{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
                Block spot = feet.getRelative(offset[0], 0, offset[1]);
                if (Bukkit.isOwnedByCurrentRegion(spot.getLocation())) {
                    spot.getRelative(0, -1, 0).setType(Material.STONE, false);
                    spot.setType(Material.AIR, false);
                    spot.getRelative(0, 1, 0).setType(Material.AIR, false);
                    return new int[] {spot.getX(), spot.getY(), spot.getZ()};
                }
            }
            throw new IllegalStateException("no owned block next to " + name);
        });
    }

    private static void setBlock(E2E e2e, String name, int[] at, Material type) {
        e2e.onPlayer(name, () -> {
            Bukkit.getPlayerExact(name).getWorld().getBlockAt(at[0], at[1], at[2]).setType(type, false);
            return null;
        });
    }

    private static Material blockType(E2E e2e, String name, int[] at) {
        return e2e.onPlayer(name, () -> Bukkit.getPlayerExact(name).getWorld().getBlockAt(at[0], at[1], at[2]).getType());
    }

    /** Breaks the block for real (the bot's left click) and waits until it is gone. */
    private static void mine(E2E e2e, Bot bot, int[] at) {
        bot.breakBlock(at[0], at[1], at[2]);
        e2e.eventually(() -> blockType(e2e, bot.name, at) == Material.AIR, bot.name + " broke the block at " + at[0] + " " + at[1] + " " + at[2]);
    }

    private static void gameMode(E2E e2e, String name, GameMode mode) {
        e2e.onPlayer(name, () -> {
            Bukkit.getPlayerExact(name).setGameMode(mode);
            return null;
        });
    }

    /**
     * Spawns a zombie near the player (away from the block work spot, since a dying mob still blocks placing for a
     * second) and kills it with a hit from the player, so the player gets the kill.
     */
    private static void killZombie(E2E e2e, String name) {
        boolean dead = e2e.onPlayer(name, () -> {
            Player player = Bukkit.getPlayerExact(name);
            Location at = player.getLocation();
            for (int[] offset : new int[][] {{-3, -3}, {-3, 3}, {3, -3}, {3, 3}}) {
                Location spot = at.clone().add(offset[0], 0, offset[1]);
                if (Bukkit.isOwnedByCurrentRegion(spot)) {
                    Zombie zombie = player.getWorld().spawn(spot, Zombie.class);
                    zombie.damage(1_000, player);
                    return zombie.isDead();
                }
            }
            return false;
        });
        e2e.expect(dead, "the zombie died");
    }

    // ------------------------------------------------------------------ scenarios

    /** The stats dialog for yourself and others, kills and streaks from the combat recorder, and navigation. */
    static void dialog(E2E e2e) throws Exception {
        String alex = e2e.name("StatsAlex");
        String blake = e2e.name("StatsBlake");
        Bot a = e2e.bot(alex);
        e2e.bot(blake);
        UUID alexId = e2e.uuid(alex);
        UUID blakeId = e2e.uuid(blake);

        e2e.step("a new player's stats dialog");
        Bot.SeenDialog own = openStats(e2e, a, null, "Your stats");
        expectBody(e2e, own, "Kills 0", "Deaths 0", "KDR 0.00", "Streak 0, best 0", "Playtime", "Mobs killed 0",
            "Blocks mined 0", "Money earned $0", "Balance $0");
        e2e.expect(own.bodyText().lines().filter(line -> line.startsWith("[")).count() == 9,
            "every stat line starts with its icon sprite: " + own.body());
        for (String board : List.of("Kills", "Deaths", "KDR", "Best streak", "Playtime", "Mobs killed", "Blocks mined",
            "Money earned", "Balance", "Close")) {
            e2e.expect(own.button(board) != null, "a '" + board + "' button in " + own.buttons());
        }

        e2e.step("kills, deaths and streaks reported by the combat feature");
        StatsRecorder recorder = recorder(e2e);
        recorder.kill(alexId, blakeId);
        recorder.kill(alexId, blakeId);
        recorder.kill(alexId, blakeId);
        recorder.death(alexId);
        recorder.kill(alexId, blakeId);
        e2e.expect(recorder.streak(alexId) == 1 && recorder.bestStreak(alexId) == 3, "streak 1 and best 3, got "
            + recorder.streak(alexId) + " and " + recorder.bestStreak(alexId));
        Bot.SeenDialog after = openStats(e2e, a, null, "Your stats");
        expectBody(e2e, after, "Kills 4", "Deaths 1", "KDR 4.00", "Streak 1, best 3");

        e2e.step("another player's stats");
        Bot.SeenDialog other = openStats(e2e, a, blake, blake + "'s stats");
        expectBody(e2e, other, "Kills 0", "Deaths 4", "KDR 0.00", "Streak 0, best 0");

        e2e.step("a leaderboard opened from the stats dialog goes back to it");
        e2e.console("sift stats refresh");
        e2e.eventually(() -> !"0".equals(placeholder(e2e, alex, "top_kills_rank")), "Alex is ranked for kills");
        int rank = Integer.parseInt(placeholder(e2e, alex, "top_kills_rank"));
        e2e.click(a, "Kills");
        Bot.SeenDialog kills = e2e.dialog(a, "Most kills");
        expectBody(e2e, kills, "Page 1 of", "You are number " + rank + " with 4.", "Updated");
        if (rank <= 10) {
            e2e.expect(kills.bodyText().contains(". " + alex + " 4"), "Alex's line on the first page:\n" + kills.bodyText());
        }
        e2e.click(a, "Back");
        e2e.dialog(a, blake + "'s stats");

        e2e.step("the pause-menu entry opens your stats with a way back to the menu");
        a.clearLogs();
        a.rawClick("siftcore:hub/stats", null);
        Bot.SeenDialog fromMenu = e2e.dialog(a, "Your stats");
        e2e.expect(fromMenu.button("Back") != null, "a Back button: " + fromMenu.buttons());
        e2e.click(a, "Back");
        e2e.dialog(a, "SiftVanilla");
        e2e.step("the main menu has a Stats button");
        Bot.SeenDialog menu = a.dialog();
        e2e.expect(menu.button("Stats") != null, "a Stats button in the menu: " + menu.buttons());
        e2e.click(a, "Stats");
        e2e.dialog(a, "Your stats");
    }

    /** Every counter from its real source: ledger kinds, mob deaths, block breaks (with anti-farm rules), playtime. */
    static void sources(E2E e2e) throws Exception {
        String cara = e2e.name("StatsCara");
        String dana = e2e.name("StatsDana");
        Bot c = e2e.bot(cara);
        e2e.bot(dana);
        UUID caraId = e2e.uuid(cara);
        UUID danaId = e2e.uuid(dana);

        e2e.step("selling counts as money earned");
        transact(e2e, LedgerTx.builder().actor("e2e").source(caraId, Currency.MONEY, 1_500, "sell", null).build());
        e2e.eventually(() -> stat(e2e, caraId, Stat.MONEY_EARNED) == 1_500, "earned $1,500 (" + stat(e2e, caraId, Stat.MONEY_EARNED) + ")");

        e2e.step("admin grants and payments between players do not count");
        e2e.console("eco give " + cara + " 999");
        e2e.console("eco set " + dana + " 20k");
        e2e.eventually(() -> e2e.money(dana) == 20_000, "Dana funded");
        transact(e2e, LedgerTx.builder().actor(danaId).transfer(danaId, caraId, Currency.MONEY, 100, "pay", null).build());

        e2e.step("an auction sale counts net of the tax the seller paid");
        transact(e2e, LedgerTx.builder().actor(danaId).transfer(danaId, caraId, Currency.MONEY, 10_000, "ah_sale", "e2e")
            .sink(caraId, Currency.MONEY, 500, "ah_tax", "e2e").build());
        e2e.eventually(() -> stat(e2e, caraId, Stat.MONEY_EARNED) == 11_000,
            "earned $11,000 after the sale (" + stat(e2e, caraId, Stat.MONEY_EARNED) + ")");
        e2e.expect(stat(e2e, danaId, Stat.MONEY_EARNED) == 0, "the buyer earned nothing");

        e2e.step("mining a block counts");
        e2e.onPlayer(cara, () -> {
            Player player = Bukkit.getPlayerExact(cara);
            player.getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(1_000);
            player.getInventory().setItem(0, new ItemStack(Material.DIRT, 16));
            player.getInventory().setHeldItemSlot(0);
            return null;
        });
        int[] spot = workSpot(e2e, cara);
        setBlock(e2e, cara, spot, Material.DIRT);
        mine(e2e, c, spot);
        e2e.eventually(() -> stat(e2e, caraId, Stat.BLOCKS_MINED) == 1, "one block mined");

        e2e.step("regrowing crops, instant blocks and creative breaking do not count");
        setBlock(e2e, cara, spot, Material.MELON);
        mine(e2e, c, spot);
        setBlock(e2e, cara, spot, Material.SHORT_GRASS);
        mine(e2e, c, spot);
        gameMode(e2e, cara, GameMode.CREATIVE);
        setBlock(e2e, cara, spot, Material.DIRT);
        mine(e2e, c, spot);
        gameMode(e2e, cara, GameMode.SURVIVAL);
        e2e.expect(stat(e2e, caraId, Stat.BLOCKS_MINED) == 1, "still one block mined (" + stat(e2e, caraId, Stat.BLOCKS_MINED) + ")");

        e2e.step("a block the player just placed does not count when mined");
        c.useItemOnTop(spot[0], spot[1] - 1, spot[2]);
        if (!Bot.await(() -> blockType(e2e, cara, spot) == Material.DIRT, 10_000)) {
            throw new E2E.Failure("the bot placed dirt (block " + blockType(e2e, cara, spot) + ", below "
                + blockType(e2e, cara, new int[] {spot[0], spot[1] - 1, spot[2]}) + ", main hand "
                + e2e.onPlayer(cara, () -> Bukkit.getPlayerExact(cara).getInventory().getItemInMainHand()) + ")");
        }
        mine(e2e, c, spot);
        e2e.expect(stat(e2e, caraId, Stat.BLOCKS_MINED) == 1, "the placed block did not count (" + stat(e2e, caraId, Stat.BLOCKS_MINED) + ")");
        setBlock(e2e, cara, spot, Material.DIRT);
        mine(e2e, c, spot);
        e2e.eventually(() -> stat(e2e, caraId, Stat.BLOCKS_MINED) == 2, "a natural block at the same spot counts again");

        e2e.step("killing a mob counts, in creative it does not");
        killZombie(e2e, cara);
        e2e.eventually(() -> stat(e2e, caraId, Stat.MOBS_KILLED) == 1, "one mob killed");
        gameMode(e2e, cara, GameMode.CREATIVE);
        killZombie(e2e, cara);
        gameMode(e2e, cara, GameMode.SURVIVAL);
        e2e.sleep(300);
        e2e.expect(stat(e2e, caraId, Stat.MOBS_KILLED) == 1, "the creative kill did not count");

        e2e.step("active playtime ticks every second");
        long before = stat(e2e, caraId, Stat.PLAYTIME_SECONDS);
        e2e.sleep(2_500);
        e2e.expect(stat(e2e, caraId, Stat.PLAYTIME_SECONDS) >= before + 2, "playtime grew from " + before + " to "
            + stat(e2e, caraId, Stat.PLAYTIME_SECONDS));
        c.clearLogs();
        c.command("playtime");
        e2e.eventually(() -> c.chatContains("You have played for"), "a playtime line in chat: " + c.chat());

        e2e.step("the dialog shows every counter");
        Bot.SeenDialog stats = openStats(e2e, c, null, "Your stats");
        expectBody(e2e, stats, "Mobs killed 1", "Blocks mined 2", "Money earned $11,000");
    }

    /** /top, the picker, the KDR rule, staff corrections and the placeholders. */
    static void top(E2E e2e) throws Exception {
        String erin = e2e.name("StatsErin");
        Bot bot = e2e.bot(erin);
        UUID erinId = e2e.uuid(erin);

        e2e.step("staff corrections from the console");
        e2e.console("sift stats add " + erin + " kills 30");
        e2e.console("sift stats add " + erin + " deaths 10");
        e2e.eventually(() -> stat(e2e, erinId, Stat.KILLS) == 30 && stat(e2e, erinId, Stat.DEATHS) == 10, "30 kills and 10 deaths");
        e2e.console("sift stats refresh");
        e2e.eventually(() -> !"0".equals(placeholder(e2e, erin, "top_kdr_rank")), "Erin is on the KDR board");

        e2e.step("the KDR board shows the ratio with two decimals and its rule");
        int kdrRank = Integer.parseInt(placeholder(e2e, erin, "top_kdr_rank"));
        bot.clearLogs();
        bot.command("top kdr");
        Bot.SeenDialog kdr = e2e.dialog(bot, "Best KDR");
        expectBody(e2e, kdr, "Players need 25 kills to be listed.", "You are number " + kdrRank + " with 3.00.", "Updated");
        if (kdrRank <= 10) {
            e2e.expect(kdr.bodyText().contains(". " + erin + " 3.00"), "Erin's line on the first page:\n" + kdr.bodyText());
        }

        e2e.step("unknown boards are refused");
        bot.clearLogs();
        bot.command("top bogus");
        e2e.eventually(() -> bot.actionBarContains("is not a leaderboard"), "an error on the action bar: " + bot.actionBar());

        e2e.step("the picker opens every board and comes back");
        bot.clearLogs();
        bot.command("top");
        Bot.SeenDialog picker = e2e.dialog(bot, "Leaderboards");
        e2e.expect(picker.buttons().size() == 10, "nine boards and Close: " + picker.buttons());
        e2e.click(bot, "Blocks mined");
        e2e.dialog(bot, "Most blocks mined");
        e2e.click(bot, "Back");
        e2e.dialog(bot, "Leaderboards");
        e2e.click(bot, "Close");
        e2e.eventually(() -> bot.dialog() == null, "the picker closes");
        bot.clearLogs();
        bot.command("leaderboard money");
        e2e.dialog(bot, "Richest players");

        e2e.step("placeholders");
        e2e.expect("30".equals(placeholder(e2e, erin, "stats_kills")), "stats_kills = 30");
        e2e.expect("10".equals(placeholder(e2e, erin, "stats_deaths")), "stats_deaths = 10");
        e2e.expect("3.00".equals(placeholder(e2e, erin, "stats_kdr")), "stats_kdr = 3.00");
        e2e.expect("0".equals(placeholder(e2e, erin, "stats_best_streak")), "stats_best_streak = 0");
        e2e.expect("$0".equals(placeholder(e2e, erin, "stats_earned")), "stats_earned = $0");
        e2e.expect("3.00".equals(placeholder(e2e, erin, "top_kdr_value_" + kdrRank)), "the value at Erin's place is 3.00");
        e2e.expect(!"-".equals(placeholder(e2e, erin, "top_kills_name_1")), "somebody leads the kills board");
        e2e.expect("-".equals(placeholder(e2e, erin, "top_kills_name_101")), "places past 100 are empty");
        e2e.expect("-".equals(placeholder(e2e, erin, "top_kills_name_abc")), "a bad place is empty");
        e2e.expect(placeholder(e2e, erin, "top_bogus_name_1") == null, "unknown boards are not placeholders");

        e2e.step("set, playtime as a duration, money as an amount, and reset");
        e2e.console("sift stats set " + erin + " kills 5");
        e2e.eventually(() -> "5".equals(placeholder(e2e, erin, "stats_kills")), "kills set to 5");
        e2e.console("sift stats set " + erin + " playtime 2h");
        e2e.eventually(() -> "2".equals(placeholder(e2e, erin, "stats_playtime_hours")), "playtime set to 2 hours");
        e2e.expect(placeholder(e2e, erin, "stats_playtime").startsWith("2h"), "playtime shows 2h: " + placeholder(e2e, erin, "stats_playtime"));
        e2e.console("sift stats add " + erin + " earned 1.5k");
        e2e.eventually(() -> stat(e2e, erinId, Stat.MONEY_EARNED) == 1_500, "earned $1,500");
        e2e.console("sift stats reset " + erin);
        e2e.eventually(() -> stat(e2e, erinId, Stat.KILLS) == 0 && stat(e2e, erinId, Stat.PLAYTIME_SECONDS) < 10
            && stat(e2e, erinId, Stat.MONEY_EARNED) == 0 && stat(e2e, erinId, Stat.DEATHS) == 0, "everything reset");
        e2e.eventually(() -> stored(e2e, erinId, "kills") == 0 && stored(e2e, erinId, "money_earned") == 0, "the reset is stored");
    }

    /** Quitting saves at once, offline changes are stored without loading, and a rejoin shows the stored values. */
    static void persist(E2E e2e) throws Exception {
        String finn = e2e.name("StatsFinn");
        Bot bot = e2e.bot(finn);
        UUID finnId = e2e.uuid(finn);
        StatsRecorder recorder = recorder(e2e);

        e2e.step("quitting writes the player's stats right away");
        UUID victim = UUID.randomUUID();
        recorder.add(finnId, Stat.BLOCKS_MINED, 7);
        recorder.kill(finnId, victim);
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(finn) == null, "Finn left");
        e2e.eventually(() -> stored(e2e, finnId, "blocks_mined") == 7 && stored(e2e, finnId, "kills") == 1
            && stored(e2e, finnId, "best_streak") == 1, "the row holds 7 blocks, 1 kill and best streak 1");

        e2e.step("changes for offline players are stored without loading them");
        transact(e2e, LedgerTx.builder().actor("e2e").source(finnId, Currency.MONEY, 400, "sell", null).build());
        recorder.add(victim, Stat.MONEY_EARNED, 250);
        e2e.console("sift stats refresh");
        e2e.eventually(() -> stored(e2e, finnId, "money_earned") == 400, "Finn's sale while offline is stored");
        e2e.eventually(() -> stored(e2e, victim, "money_earned") == 250 && stored(e2e, victim, "deaths") == 1,
            "a player who was never loaded gets a row from the upsert");

        e2e.step("rejoining shows the stored stats");
        Bot back = e2e.bot(finn);
        Bot.SeenDialog stats = openStats(e2e, back, null, "Your stats");
        expectBody(e2e, stats, "Kills 1", "Streak 1, best 1", "Blocks mined 7", "Money earned $400");
    }
}
