package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.siftvanilla.siftcore.core.link.StatsRecorder;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.feature.integrations.IntegrationsFeature;
import net.siftvanilla.siftcore.feature.scoreboard.ScoreboardFeature;
import net.siftvanilla.siftcore.feature.scoreboard.SidebarLayout;
import net.siftvanilla.siftcore.feature.stats.StatsFeature;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;

/**
 * The sidebar, the tab list and nametags as a real client receives them: the objective, its display slot, the blank
 * number format, every score line with its custom text, the nametag teams and the tab list header, footer, names and
 * order. Also the switch, persistence, staff commands, reloads, and the links to stats, teams, combat, ranks and vanish.
 */
final class ScoreboardScenarios {

    private static final String OBJECTIVE = "sift_sidebar";
    private static final int MONEY_COLOUR = 0x1AFF1A;
    private static final int WHITE = 0xFFFFFF;
    private static final int GRAY = 0xAAAAAA;

    private ScoreboardScenarios() {
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
        list.add(of("scoreboard-sidebar", ScoreboardScenarios::sidebar));
        list.add(of("scoreboard-switch", ScoreboardScenarios::toggle));
        list.add(of("scoreboard-nametags", ScoreboardScenarios::nametags));
        list.add(of("scoreboard-tab", ScoreboardScenarios::tab));
        list.add(of("scoreboard-staff", ScoreboardScenarios::staff));
        list.add(of("scoreboard-reload", ScoreboardScenarios::reload));
        list.add(of("scoreboard-persist", ScoreboardScenarios::persist));
        list.add(of("scoreboard-yield", ScoreboardScenarios::yieldTo));
        list.add(of("scoreboard-layout", ScoreboardScenarios::layout));
        list.add(of("scoreboard-locked", ScoreboardScenarios::locked));
        list.add(of("scoreboard-hide-rank", ScoreboardScenarios::hideRank));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    /** Waits until the client shows SiftCore's sidebar, and returns its lines. */
    private static List<String> awaitSidebar(E2E e2e, Bot bot) {
        e2e.eventually(() -> bot.displayed("sidebar") != null && OBJECTIVE.equals(bot.displayed("sidebar").name()),
            bot.name + " has the sidebar (objectives in the sidebar slot: " + bot.displayed("sidebar") + ")");
        return bot.sidebarLines();
    }

    /** The sidebar line containing {@code text}, or null. */
    private static String line(Bot bot, String text) {
        for (String line : bot.sidebarLines()) {
            if (line.contains(text)) {
                return line;
            }
        }
        return null;
    }

    /** The score whose text contains {@code text}, or null. */
    private static Bot.SeenScore score(Bot bot, String text) {
        for (Bot.SeenScore score : bot.scores(OBJECTIVE).values()) {
            if (score.text().contains(text)) {
                return score;
            }
        }
        return null;
    }

    /** The colour of the part of a client-side component whose own text contains {@code text}, inherited colours included. */
    private static Integer colourOf(Component component, String text, Integer inherited) {
        TextColor own = component.getStyle().getColor();
        Integer colour = own == null ? inherited : Integer.valueOf(own.getValue());
        if (component.getContents() instanceof PlainTextContents plain && plain.text().contains(text)) {
            return colour;
        }
        for (Component child : component.getSiblings()) {
            Integer found = colourOf(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean anyBold(Component component) {
        if (component.getStyle().isBold()) {
            return true;
        }
        return component.getSiblings().stream().anyMatch(ScoreboardScenarios::anyBold);
    }

    private static List<String> output(E2E e2e, String command) {
        return output(e2e, command, 300);
    }

    /** Runs a command as a console-like sender and returns what it said, waiting for answers sent a little later. */
    private static List<String> output(E2E e2e, String command, long waitMillis) {
        List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(harness(), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message -> lines.add(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("command '" + command + "' failed: " + e);
        }
        e2e.sleep(waitMillis);
        return List.copyOf(lines);
    }

    /** The stored value of a player's setting, or null when none is stored. */
    private static String storedSetting(E2E e2e, UUID player, String setting) throws Exception {
        return e2e.services().database().read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** Disconnects a bot and waits until the server has let it go. */
    private static void leave(E2E e2e, Bot bot) {
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(bot.name) == null, bot.name + " left");
        e2e.sleep(300);
    }

    /** Edits a SiftCore file, reloads, runs the body and always restores the file and reloads again. */
    private static void withFile(E2E e2e, String file, Map<String, String> replacements, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve(file);
        String original = Files.readString(path, StandardCharsets.UTF_8);
        String changed = original;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            e2e.expect(changed.contains(entry.getKey()), file + " contains '" + entry.getKey() + "'");
            changed = changed.replace(entry.getKey(), entry.getValue());
        }
        Files.writeString(path, changed, StandardCharsets.UTF_8);
        try {
            List<String> reload = output(e2e, "sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed " + file + " reloads: " + reload);
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    // ------------------------------------------------------------------ the sidebar

    /** The sidebar's packets, its lines, updates of one line at a time and the links to economy, stats and teams. */
    static void sidebar(E2E e2e) throws Exception {
        String alexName = e2e.name("SbAlex");
        String victimName = e2e.name("SbVictim");
        Bot alex = e2e.bot(alexName);
        e2e.bot(victimName);
        UUID alexId = e2e.uuid(alexName);

        e2e.step("the client receives the objective in the sidebar slot with blank numbers and the title");
        List<String> lines = awaitSidebar(e2e, alex);
        Bot.SeenObjective sidebar = alex.displayed("sidebar");
        e2e.expect("BlankFormat".equals(sidebar.numberFormat()), "the objective hides the numbers: " + sidebar.numberFormat());
        e2e.expect("SiftVanilla".equals(sidebar.displayName().getString()), "the title: " + sidebar.displayName().getString());
        e2e.expect(Integer.valueOf(WHITE).equals(colourOf(sidebar.displayName(), "SiftVanilla", null)) && !anyBold(sidebar.displayName()),
            "the title is plain white: " + sidebar.displayName());

        e2e.step("every configured line arrives as a score with custom text; the team line is left out without a team");
        e2e.eventually(() -> alex.sidebarLines().size() == 8, "eight lines: " + alex.sidebarLines());
        lines = alex.sidebarLines();
        e2e.expect(lines.get(0).isEmpty() && lines.get(6).isEmpty(), "blank lines at the top and above the address: " + lines);
        e2e.expect(lines.get(1).startsWith("[") && lines.get(1).endsWith("Money $0"), "the money line with its icon: " + lines.get(1));
        e2e.expect(lines.get(2).endsWith("Shards 0"), "shards: " + lines.get(2));
        e2e.expect(lines.get(3).endsWith("Kills 0"), "kills: " + lines.get(3));
        e2e.expect(lines.get(4).endsWith("Deaths 0"), "deaths: " + lines.get(4));
        e2e.expect(lines.get(5).contains("Playtime "), "playtime: " + lines.get(5));
        e2e.expect("siftvanilla.com".equals(lines.get(7)), "the address: " + lines.get(7));
        for (Bot.SeenScore score : alex.scores(OBJECTIVE).values()) {
            e2e.expect(score.display() != null, "score " + score.owner() + " has custom text");
            e2e.expect(score.owner().startsWith("§"), "score entries are fixed ids, never names: " + score.owner());
        }
        Bot.SeenScore money = score(alex, "Money");
        e2e.expect(Integer.valueOf(MONEY_COLOUR).equals(colourOf(money.display(), "$0", null)), "the amount is in the money colour");
        e2e.expect(Integer.valueOf(GRAY).equals(colourOf(money.display(), "Money", null)), "the label is gray");
        Bot.SeenScore shards = score(alex, "Shards");
        e2e.expect(Integer.valueOf(0x915DFF).equals(colourOf(shards.display(), "Shards", null)), "shards in the shard purple: "
            + colourOf(shards.display(), "Shards", null));
        Bot.SeenScore kills = score(alex, "Kills");
        e2e.expect(Integer.valueOf(0xFFD866).equals(colourOf(kills.display(), "0", null))
            && Integer.valueOf(GRAY).equals(colourOf(kills.display(), "Kills", null)), "a value in the accent colour, its label gray");

        e2e.step("a payment changes the money line and nothing else");
        Map<String, Integer> before = new HashMap<>();
        Bot.SeenScore playtime = score(alex, "Playtime");
        for (Bot.SeenScore score : alex.scores(OBJECTIVE).values()) {
            before.put(score.owner(), score.packet());
        }
        e2e.console("eco give " + alexName + " 1500");
        e2e.eventually(() -> line(alex, "Money") != null && line(alex, "Money").endsWith("Money $1,500"),
            "the new balance shows: " + alex.sidebarLines());
        for (Bot.SeenScore score : alex.scores(OBJECTIVE).values()) {
            if (!score.owner().equals(money.owner()) && !score.owner().equals(playtime.owner())) {
                e2e.expect(before.get(score.owner()) == score.packet(), "'" + score.text() + "' was not sent again");
            }
        }

        e2e.step("an idle refresh sends nothing but the ticking playtime");
        Map<String, Integer> idle = new HashMap<>();
        for (Bot.SeenScore score : alex.scores(OBJECTIVE).values()) {
            idle.put(score.owner(), score.packet());
        }
        e2e.sleep(3_000);
        for (Bot.SeenScore score : alex.scores(OBJECTIVE).values()) {
            if (!score.owner().equals(playtime.owner())) {
                e2e.expect(idle.get(score.owner()) == score.packet(), "'" + score.text() + "' stayed unsent while idle");
            }
        }

        e2e.step("kills and deaths come from the stats recorder");
        StatsRecorder recorder = e2e.feature(StatsFeature.class).recorder();
        recorder.kill(alexId, e2e.uuid(victimName));
        recorder.kill(alexId, e2e.uuid(victimName));
        recorder.death(alexId);
        e2e.eventually(() -> "Kills 2".equals(tail(line(alex, "Kills"))) && "Deaths 1".equals(tail(line(alex, "Deaths"))),
            "two kills and a death: " + alex.sidebarLines());

        e2e.step("the team line appears in a team and goes when the team is disbanded");
        String team = e2e.name("SbTeam");
        alex.clearLogs();
        alex.command("team create " + team);
        e2e.dialog(alex, "Team " + team);
        e2e.eventually(() -> line(alex, "Team ") != null && line(alex, "Team ").endsWith("Team " + team),
            "the team line shows " + team + ": " + alex.sidebarLines());
        e2e.expect(alex.sidebarLines().size() == 9, "nine lines with the team: " + alex.sidebarLines());
        e2e.expect(alex.sidebarLines().indexOf(line(alex, "Team ")) == 6, "the team line sits below playtime: " + alex.sidebarLines());
        List<String> disband = output(e2e, "team admin disband " + team);
        e2e.expect(String.join(" ", disband).contains(team), "the team was disbanded: " + disband);
        e2e.eventually(() -> line(alex, "Team ") == null && alex.sidebarLines().size() == 8, "the team line is gone: " + alex.sidebarLines());

        e2e.step("the sidebar stays through a death and respawn");
        int deaths = alex.deaths();
        e2e.onPlayer(alexName, () -> {
            e2e.player(alexName).setHealth(0);
            return null;
        });
        e2e.eventually(() -> alex.deaths() > deaths, alexName + " died");
        e2e.eventually(() -> !e2e.onPlayer(alexName, () -> e2e.player(alexName).isDead()), alexName + " respawned");
        e2e.sleep(1_500);
        e2e.expect(alex.displayed("sidebar") != null && alex.sidebarLines().size() == 8, "the sidebar is still there: " + alex.sidebarLines());
    }

    /** The part of a line after its icon ("Kills 2"), or null. */
    private static String tail(String line) {
        if (line == null) {
            return null;
        }
        int bracket = line.indexOf("] ");
        return bracket < 0 ? line : line.substring(bracket + 2);
    }

    // ------------------------------------------------------------------ the switch

    /** /sidebar hides and shows the sidebar at once, remembers it across sessions, and keeps nametags meanwhile. */
    static void toggle(E2E e2e) throws Exception {
        String name = e2e.name("SbSwitch");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        awaitSidebar(e2e, bot);

        e2e.step("/sidebar hides it: the client is told to remove the objective");
        bot.clearLogs();
        bot.command("sidebar");
        e2e.eventually(() -> bot.actionBarContains("Sidebar hidden"), "the hidden message: " + bot.actionBar());
        e2e.eventually(() -> bot.displayed("sidebar") == null && bot.objective(OBJECTIVE) == null, "the sidebar is gone");
        e2e.expect(bot.teamOf(name) != null, "nametag teams stay while the sidebar is hidden: " + bot.teams().keySet());
        e2e.eventually(() -> {
            try {
                return "false".equals(storedSetting(e2e, id, "scoreboard"));
            } catch (Exception e) {
                return false;
            }
        }, "the choice is stored");

        e2e.step("/sidebar off again says so");
        bot.clearLogs();
        bot.command("sidebar off");
        e2e.eventually(() -> bot.actionBarContains("already hidden"), "already hidden: " + bot.actionBar());

        e2e.step("the choice survives a new session");
        leave(e2e, bot);
        Bot again = e2e.bot(name);
        e2e.eventually(() -> again.teamOf(name) != null, "the new session gets its board with the nametag teams");
        e2e.sleep(2_500);
        e2e.expect(again.displayed("sidebar") == null, "no sidebar after rejoining with it hidden");

        e2e.step("/sidebar on shows it again with every line");
        again.clearLogs();
        again.command("sidebar on");
        e2e.eventually(() -> again.actionBarContains("Sidebar shown"), "the shown message: " + again.actionBar());
        awaitSidebar(e2e, again);
        e2e.eventually(() -> again.sidebarLines().size() == 8, "every line is back: " + again.sidebarLines());
        e2e.expect(again.sidebarLines().get(1).contains("Money"), "money is the first line: " + again.sidebarLines());
        e2e.eventually(() -> {
            try {
                return storedSetting(e2e, id, "scoreboard") == null;
            } catch (Exception e) {
                return false;
            }
        }, "showing it is the default again, so its row is deleted");

        e2e.step("the Display group of /settings switches it too");
        again.clearLogs();
        again.command("settings display");
        Bot.SeenDialog display = SettingsSteps.form(e2e, e2e.dialog(again, "Display settings"));
        e2e.expect("toggle".equals(display.inputs().get("scoreboard")), "the sidebar switch: " + display.inputs());
        e2e.expect(display.button("Sidebar: ON") != null, "its button: " + display.buttons());
        Map<String, Object> values = display.values();
        values.put("scoreboard", false);
        SettingsSteps.applyChanged(e2e, again, display, values);
        e2e.expect(again.dialog().button("Sidebar: OFF") != null, "the button shows OFF: " + again.dialog().buttons());
        e2e.eventually(() -> again.displayed("sidebar") == null, "the sidebar goes at the next refresh");
        again.command("sidebar on");
        awaitSidebar(e2e, again);
    }

    // ------------------------------------------------------------------ nametags

    /** Rank teams on every client: same teams everywhere, rank prefixes, vanish, quits, and no friendly invisibles. */
    static void nametags(E2E e2e) throws Exception {
        String alexName = e2e.name("SbTagA");
        String blakeName = e2e.name("SbTagB");
        Bot alex = e2e.bot(alexName);
        Bot blake = e2e.bot(blakeName);
        UUID alexId = e2e.uuid(alexName);
        UUID blakeId = e2e.uuid(blakeName);

        e2e.step("both clients see both players in rank teams with white names");
        e2e.eventually(() -> alex.teamOf(alexName) != null && alex.teamOf(blakeName) != null
            && blake.teamOf(alexName) != null && blake.teamOf(blakeName) != null, "both players are in teams on both clients");
        Bot.SeenTeam team = blake.teamOf(alexName);
        e2e.expect(team.name().startsWith("sift_r"), "SiftCore's team: " + team.name());
        e2e.expect("white".equals(team.color()), "names are white: " + team.color());
        e2e.expect((team.options() & 0x02) == 0, "players of the same rank don't see each other through invisibility");
        e2e.expect(team.prefix().getString().isEmpty(), "no rank label without a rank: '" + team.prefix().getString() + "'");
        e2e.expect(alex.teamOf(alexName).name().equals(blake.teamOf(alexName).name()), "both boards use the same team");

        e2e.step("a rank (LuckPerms' group permission) puts the label in front of the name, in the tab list and first in order");
        PermissionAttachment[] attachment = new PermissionAttachment[1];
        e2e.onPlayer(alexName, () -> {
            attachment[0] = e2e.player(alexName).addAttachment(harness(), "group.baron", true);
            return null;
        });
        List<String> refreshed = output(e2e, "sidebar refresh");
        e2e.expect(String.join(" ", refreshed).contains("Refreshed"), "refresh confirmed: " + refreshed);
        e2e.eventually(() -> blake.teamOf(alexName) != null && "Baron ".equals(blake.teamOf(alexName).prefix().getString()),
            "Blake sees the Baron label in front of Alex: " + blake.teamOf(alexName));
        Bot.SeenTeam baron = blake.teamOf(alexName);
        e2e.expect(Integer.valueOf(GRAY).equals(colourOf(baron.prefix(), "Baron", null)), "the label is gray");
        e2e.expect(alex.teamOf(alexName) != null && "Baron ".equals(alex.teamOf(alexName).prefix().getString()), "Alex's own board agrees");
        e2e.expect(!baron.name().equals(blake.teamOf(blakeName).name()), "Blake stays in the default team");
        e2e.expect(baron.name().compareTo(blake.teamOf(blakeName).name()) < 0, "the higher rank's team sorts first");
        e2e.eventually(() -> blake.listName(alexId) != null && ("Baron " + alexName).equals(blake.listName(alexId).getString()),
            "the tab list shows 'Baron " + alexName + "': " + blake.listName(alexId));
        e2e.eventually(() -> blake.listOrder(alexId) > blake.listOrder(blakeId),
            "Baron is listed above default: " + blake.listOrder(alexId) + " vs " + blake.listOrder(blakeId));
        e2e.expect(blake.listName(blakeId) != null && blakeName.equals(blake.listName(blakeId).getString()),
            "a player without a label is listed by name: " + blake.listName(blakeId));

        e2e.step("losing the rank takes the label away again");
        e2e.onPlayer(alexName, () -> {
            e2e.player(alexName).removeAttachment(attachment[0]);
            return null;
        });
        output(e2e, "sidebar refresh");
        e2e.eventually(() -> blake.teamOf(alexName) != null && blake.teamOf(alexName).prefix().getString().isEmpty()
            && blake.teamOf(alexName).name().equals(blake.teamOf(blakeName).name()), "Alex is back in the default team");
        e2e.eventually(() -> blake.listName(alexId) != null && alexName.equals(blake.listName(alexId).getString()), "plain tab name again");

        e2e.step("a vanished moderator leaves every team, and comes back when visible");
        e2e.console("op " + alexName);
        try {
            alex.clearLogs();
            alex.command("vanish");
            e2e.eventually(() -> alex.actionBarContains("You are vanished") || alex.chatContains("You are vanished"),
                "vanished: " + alex.actionBar() + " " + alex.chat());
            e2e.eventually(() -> blake.teamOf(alexName) == null, "Blake's board no longer has Alex in a team");
            e2e.eventually(() -> alex.teamOf(alexName) == null, "nor Alex's own board");
            alex.command("vanish");
            e2e.eventually(() -> blake.teamOf(alexName) != null, "Alex is back in a team on Blake's board");
        } finally {
            e2e.console("deop " + alexName);
        }

        e2e.step("a player who leaves is taken out of the teams");
        leave(e2e, alex);
        e2e.eventually(() -> blake.teamOf(alexName) == null, "Blake's board forgot Alex");
        e2e.expect(blake.teamOf(blakeName) != null, "Blake is still in a team");
    }

    // ------------------------------------------------------------------ the tab list

    /** The header and footer from lang, the online count without vanished staff, and refreshes. */
    static void tab(E2E e2e) throws Exception {
        String viewerName = e2e.name("SbTabView");
        String modName = e2e.name("SbTabMod");
        Bot viewer = e2e.bot(viewerName);
        Bot mod = e2e.bot(modName);

        e2e.step("the header and footer arrive");
        e2e.eventually(() -> viewer.tabHeader() != null && viewer.tabHeader().contains("SiftVanilla"), "the header: " + viewer.tabHeader());
        e2e.expect(viewer.tabFooter() != null && viewer.tabFooter().contains("/menu") && viewer.tabFooter().contains("siftvanilla.com"),
            "the footer: " + viewer.tabFooter());
        int online = (int) Bukkit.getOnlinePlayers().stream().filter(p -> !"true".equals(
            e2e.services().placeholders().resolve(p, "staff_vanished"))).count();
        e2e.eventually(() -> viewer.tabHeader().contains(online + " online"), "the header counts " + online + ": " + viewer.tabHeader());
        e2e.expect(viewer.listName(e2e.uuid(viewerName)) != null, "the viewer's own tab name was set");

        e2e.step("going AFK marks the name in everyone's tab list at once, coming back removes it");
        UUID modId = e2e.uuid(modName);
        mod.clearLogs();
        mod.command("afk");
        e2e.eventually(() -> mod.actionBarContains("You are now AFK"), "AFK: " + mod.actionBar());
        e2e.eventually(() -> viewer.listName(modId) != null && (modName + " AFK").equals(viewer.listName(modId).getString()),
            "the viewer sees '" + modName + " AFK': " + viewer.listName(modId));
        Component marked = viewer.listName(modId);
        e2e.expect(Integer.valueOf(GRAY).equals(colourOf(marked, "AFK", null)) && Integer.valueOf(WHITE).equals(colourOf(marked, modName, null)),
            "a white name and a gray marker: " + marked);
        mod.command("afk");
        e2e.eventually(() -> viewer.listName(modId) != null && modName.equals(viewer.listName(modId).getString()),
            "the marker is gone: " + viewer.listName(modId));

        e2e.step("unchanged text is not sent again");
        int packets = viewer.tabListPackets();
        e2e.sleep(6_000);
        e2e.expect(viewer.tabListPackets() == packets, "no header resend while nothing changed: " + (viewer.tabListPackets() - packets));

        e2e.step("a vanished moderator is not counted");
        e2e.console("op " + modName);
        try {
            mod.clearLogs();
            mod.command("vanish");
            e2e.eventually(() -> mod.actionBarContains("You are vanished") || mod.chatContains("You are vanished"),
                "vanished: " + mod.actionBar() + " " + mod.chat());
            e2e.eventually(() -> viewer.tabHeader().contains((online - 1) + " online"), 12_000,
                "the header counts " + (online - 1) + " after the vanish: " + viewer.tabHeader());
        } finally {
            Player moderator = Bukkit.getPlayerExact(modName);
            if (moderator != null && "true".equals(e2e.services().placeholders().resolve(moderator, "staff_vanished"))) {
                mod.command("vanish");
                e2e.eventually(() -> !"true".equals(e2e.services().placeholders().resolve(moderator, "staff_vanished")), "visible again");
            }
            e2e.console("deop " + modName);
        }
        e2e.eventually(() -> viewer.tabHeader().contains(online + " online"), 12_000, "counted again when visible: " + viewer.tabHeader());
    }

    // ------------------------------------------------------------------ staff commands and the self-test

    /** /sidebar status, preview and refresh from the console, players can't use them, and the self-test passes. */
    static void staff(E2E e2e) throws Exception {
        String name = e2e.name("SbStaff");
        Bot bot = e2e.bot(name);
        awaitSidebar(e2e, bot);
        e2e.sleep(5_500);

        e2e.step("status from the console");
        String status = String.join("\n", output(e2e, "sidebar status"));
        e2e.expect(status.contains("Sidebar, tab list and nametags") && status.contains("sidebars shown")
            && status.contains("Last refresh took"), "the status:\n" + status);

        e2e.step("preview lists what the player sees");
        String preview = String.join("\n", output(e2e, "sidebar preview " + name));
        e2e.expect(preview.contains("Sidebar of " + name) && preview.contains("Money $0") && preview.contains("siftvanilla.com"),
            "the preview:\n" + preview);
        String nobody = String.join("\n", output(e2e, "sidebar preview NobodyHere"));
        e2e.expect(nobody.contains("NobodyHere"), "an unknown player is named: " + nobody);

        e2e.step("refresh resends everything");
        int packets = bot.scorePackets();
        String refreshed = String.join("\n", output(e2e, "sidebar refresh"));
        e2e.expect(refreshed.contains("Refreshed the sidebar, tab list and nametags of"), refreshed);
        e2e.eventually(() -> bot.scorePackets() >= packets + 8, "the lines were sent again: " + (bot.scorePackets() - packets));
        e2e.eventually(() -> bot.sidebarLines().size() == 8, "the sidebar is whole after the refresh: " + bot.sidebarLines());

        e2e.step("players can't use the staff commands");
        bot.clearLogs();
        bot.command("sidebar status");
        e2e.sleep(800);
        e2e.expect(bot.chat().stream().noneMatch(l -> l.contains("Last refresh took")), "no status for a player: " + bot.chat());

        e2e.step("the self-test passes the scoreboard's checks");
        String selftest = String.join("\n", output(e2e, "sift selftest", 3_000));
        for (String check : List.of("every player with the sidebar on has one", "nametag teams are the same on every board",
            "every placeholder in the sidebar and tab list is provided", "the sidebar refresh keeps up")) {
            e2e.expect(selftest.lines().anyMatch(l -> l.startsWith("pass") && l.contains(check)), "'" + check + "' passes:\n" + selftest);
        }
        e2e.expect(selftest.lines().noneMatch(l -> l.startsWith("fail scoreboard")), "no scoreboard failures:\n" + selftest);
    }

    // ------------------------------------------------------------------ across restarts

    /**
     * The sidebar switch is kept across restarts. Uses the same name on every run and leaves the sidebar hidden: when an
     * earlier run (possibly before a restart) stored it hidden, the player must join without a sidebar; otherwise it
     * shows and is hidden now. Run it, restart the server, run it again.
     */
    static void persist(E2E e2e) throws Exception {
        String name = "SbKeepHidden";
        UUID id = e2e.services().directory().uuid(name).orElse(null);
        String stored = id == null ? null : storedSetting(e2e, id, "scoreboard");
        Bot bot = e2e.bot(name);
        e2e.eventually(() -> bot.teamOf(name) != null, name + " has a board with the nametag teams");
        if ("false".equals(stored)) {
            e2e.step("hidden in an earlier run: no sidebar after joining");
            e2e.sleep(2_500);
            e2e.expect(bot.displayed("sidebar") == null, "the stored choice holds: no sidebar");
            e2e.log("the sidebar stayed hidden as stored earlier");
        } else {
            e2e.step("first run: the sidebar shows and is hidden for the next run");
            awaitSidebar(e2e, bot);
            bot.command("sidebar off");
            e2e.eventually(() -> bot.displayed("sidebar") == null, "the sidebar is hidden");
            UUID known = e2e.uuid(name);
            e2e.eventually(() -> {
                try {
                    return "false".equals(storedSetting(e2e, known, "scoreboard"));
                } catch (Exception e) {
                    return false;
                }
            }, "the choice is stored for the next run");
        }
    }

    // ------------------------------------------------------------------ another plugin running a part

    /**
     * While a plugin from a part's yield-to list runs, SiftCore leaves that part alone. The harness plugin stands in for
     * TAB: listing it takes the sidebar, tab list and nametags away at once, and they come back when it is removed.
     */
    static void yieldTo(E2E e2e) throws Exception {
        String name = e2e.name("SbYield");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        awaitSidebar(e2e, bot);
        e2e.eventually(() -> bot.teamOf(name) != null && bot.listName(id) != null && bot.tabHeader() != null && !bot.tabHeader().isEmpty(),
            "every part is shown first");

        e2e.step("listing a running plugin hands every part over");
        withFile(e2e, "features/scoreboard.yml", Map.of(
            "  yield-to:\n    - TAB\n\ntab:", "  yield-to:\n    - SiftE2E\n\ntab:",
            "  yield-to:\n    - TAB\n\nnametags:", "  yield-to:\n    - SiftE2E\n\nnametags:",
            "  yield-to:\n    - TAB\n\nranks:", "  yield-to:\n    - SiftE2E\n\nranks:"), x -> {
            e2e.eventually(() -> bot.displayed("sidebar") == null && bot.objective(OBJECTIVE) == null, "the sidebar was removed");
            e2e.eventually(() -> bot.teamOf(name) == null && bot.teams().keySet().stream().noneMatch(t -> t.startsWith("sift_r")),
                "SiftCore's nametag teams are gone: " + bot.teams().keySet());
            e2e.eventually(() -> bot.listName(id) == null, "the plain tab name is back: " + bot.listName(id));
            e2e.eventually(() -> bot.tabHeader().isEmpty() && bot.tabFooter().isEmpty(), "the header and footer were cleared");
            bot.clearLogs();
            bot.command("sidebar");
            e2e.eventually(() -> bot.actionBarContains("can't be hidden with /sidebar"), "/sidebar says it can't: " + bot.actionBar());
            e2e.expect(!bot.anyFeedbackContains("SiftE2E"), "names no plugin to the player: " + bot.actionBar() + " " + bot.chat());
            String status = String.join("\n", output(e2e, "sidebar status"));
            e2e.expect(status.contains("sidebar (SiftE2E), tab list (SiftE2E), nametags (SiftE2E)"), "the status names it:\n" + status);
            String selftest = String.join("\n", output(e2e, "sift selftest", 3_000));
            e2e.expect(selftest.lines().noneMatch(l -> l.startsWith("fail scoreboard")), "no scoreboard failures while yielding:\n" + selftest);
        });

        e2e.step("without it, SiftCore shows everything again");
        awaitSidebar(e2e, bot);
        e2e.eventually(() -> bot.sidebarLines().size() == 8, "every line is back: " + bot.sidebarLines());
        e2e.eventually(() -> bot.teamOf(name) != null, "the nametag teams are back");
        e2e.eventually(() -> bot.listName(id) != null, "the tab name is set again");
        e2e.eventually(() -> bot.tabHeader().contains("SiftVanilla"), "the header is back: " + bot.tabHeader());
    }

    // ------------------------------------------------------------------ reloads

    /** New lines and new text apply with /sift reload; the combat line follows the combat tag. */
    static void reload(E2E e2e) throws Exception {
        String name = e2e.name("SbReload");
        Bot bot = e2e.bot(name);
        awaitSidebar(e2e, bot);
        e2e.eventually(() -> bot.sidebarLines().size() == 8, "the default sidebar: " + bot.sidebarLines());

        e2e.step("other lines from the config, and the combat line only in combat");
        withFile(e2e, "features/scoreboard.yml", Map.of("    - team\n    - blank\n    - website",
            "    - team\n    - combat\n    - kdr\n    - online\n    - rank\n    - blank\n    - website"), x -> {
            e2e.eventually(() -> line(bot, "KDR") != null && line(bot, "Online ") != null, "the new lines show: " + bot.sidebarLines());
            e2e.expect("KDR 0.00".equals(tail(line(bot, "KDR"))), "kdr from the stats placeholder: " + line(bot, "KDR"));
            e2e.expect(line(bot, "Combat") == null, "no combat line out of combat: " + bot.sidebarLines());
            e2e.expect(line(bot, "Rank") == null, "no rank line without a rank label: " + bot.sidebarLines());
            e2e.expect(bot.sidebarLines().size() == 10, "ten lines: " + bot.sidebarLines());
            List<String> tagged = output(e2e, "combat tag " + name + " 30s");
            e2e.expect(!tagged.isEmpty(), "tagged: " + tagged);
            try {
                e2e.eventually(() -> line(bot, "Combat") != null && tail(line(bot, "Combat")).matches("Combat (30|29|28|27|26)s"),
                    "the combat line counts down: " + bot.sidebarLines());
                int index = bot.sidebarLines().indexOf(line(bot, "Combat"));
                e2e.expect(index == 6, "the combat line takes its configured place: " + bot.sidebarLines());
            } finally {
                output(e2e, "combat untag " + name);
            }
            e2e.eventually(() -> line(bot, "Combat") == null, "the combat line goes with the tag: " + bot.sidebarLines());
        });
        e2e.eventually(() -> bot.sidebarLines().size() == 8 && line(bot, "KDR") == null, "the default lines are back: " + bot.sidebarLines());

        e2e.step("changed text in lang applies too");
        withFile(e2e, "lang/scoreboard.yml", Map.of("    title: \"SiftVanilla\"", "    title: \"SiftVanilla test\"",
            "      website: \"<secondary>siftvanilla.com\"", "      website: \"<secondary>play.siftvanilla.com\""), x -> {
            e2e.eventually(() -> bot.displayed("sidebar") != null && "SiftVanilla test".equals(bot.displayed("sidebar").displayName().getString()),
                "the new title: " + bot.displayed("sidebar"));
            e2e.eventually(() -> bot.sidebarLines().contains("play.siftvanilla.com"), "the new address: " + bot.sidebarLines());
        });
        e2e.eventually(() -> bot.displayed("sidebar") != null && "SiftVanilla".equals(bot.displayed("sidebar").displayName().getString())
            && bot.sidebarLines().contains("siftvanilla.com"), "the shipped text is back: " + bot.sidebarLines());
    }

    // ------------------------------------------------------------------ the player's settings

    /** The fight stats layout as shipped in features/scoreboard.yml. */
    private static final String COMBAT_LAYOUT = "    combat:\n      - blank\n      - kills\n      - deaths\n      - kdr\n      - streak\n"
        + "      - bounty\n      - combat\n      - blank\n      - website";

    private static String stored(E2E e2e, UUID player, String setting) {
        try {
            return storedSetting(e2e, player, setting);
        } catch (Exception e) {
            return "unreadable: " + e;
        }
    }

    /**
     * Sidebar lines (sidebar-layout): the Display group offers everything, a money view and fight stats; a pick in the
     * dialog redraws the sidebar at once, the API does too, a layout the server empties disappears from the choice
     * and its players see everything, and the full sidebar again deletes the row.
     */
    static void layout(E2E e2e) throws Exception {
        String name = e2e.name("SbLayout");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        awaitSidebar(e2e, bot);
        e2e.eventually(() -> bot.sidebarLines().size() == 8, "the full sidebar: " + bot.sidebarLines());

        e2e.step("the Display group offers the three layouts and starts on everything");
        AfkStaffSettingSteps.openGroup(e2e, bot, "display", "Display settings");
        Bot.SeenDialog display = AfkStaffSettingSteps.form(e2e, bot);
        e2e.expect("choice".equals(display.inputs().get("sidebar_layout")), "the layout is a choice: " + display.inputs());
        e2e.expect(List.of("full", "compact", "combat").equals(display.options().get("sidebar_layout")),
            "three layouts: " + display.options().get("sidebar_layout"));
        e2e.expect("full".equals(display.choiceValue("sidebar_layout")), "everything by default: " + display.choiceValue("sidebar_layout"));
        e2e.expect(String.join(" ", display.optionLabels().get("sidebar_layout")).contains("Fight stats"),
            "the options are named: " + display.optionLabels().get("sidebar_layout"));
        e2e.expect(display.button("Sidebar lines: ") != null, "its button: " + display.buttons());
        List<String> keys = List.copyOf(display.inputs().keySet());
        e2e.expect(keys.indexOf("scoreboard") >= 0 && keys.indexOf("scoreboard") < keys.indexOf("sidebar_layout"),
            "the switch comes before the layout: " + keys);

        e2e.step("picking the money view in the dialog redraws the sidebar at once");
        Map<String, Object> values = display.values();
        values.put("sidebar_layout", "compact");
        bot.clearMessages();
        SettingsSteps.applyChanged(e2e, bot, display, values);
        e2e.eventually(() -> line(bot, "Kills") == null && line(bot, "Money") != null && bot.sidebarLines().size() == 5,
            "only money, shards and the address: " + bot.sidebarLines());
        e2e.expect(bot.sidebarLines().get(1).endsWith("Money $0") && bot.sidebarLines().get(2).endsWith("Shards 0")
            && "siftvanilla.com".equals(bot.sidebarLines().get(4)), "the money view: " + bot.sidebarLines());
        e2e.eventually(() -> "compact".equals(stored(e2e, id, "sidebar-layout")), "the pick is stored: " + stored(e2e, id, "sidebar-layout"));

        e2e.step("/sidebar preview shows the lines of the layout the player picked");
        String preview = String.join("\n", output(e2e, "sidebar preview " + name));
        e2e.expect(preview.contains("Money") && !preview.contains("Kills"), "the preview follows the layout:\n" + preview);

        e2e.step("fight stats with the settings command (/settings display sidebar-layout combat) redraw it at once too");
        bot.clearLogs();
        bot.command("settings display sidebar-layout combat");
        e2e.eventually(() -> "combat".equals(stored(e2e, id, "sidebar-layout")),
            "/settings display sidebar-layout combat stores it (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
        e2e.eventually(() -> bot.anyFeedbackContains("Sidebar lines"), "the command confirms: " + bot.actionBar() + " " + bot.chat());
        e2e.eventually(() -> line(bot, "Money") == null && line(bot, "Kills") != null && line(bot, "Deaths") != null
            && line(bot, "KDR") != null && line(bot, "Streak") != null, "kills, deaths, KDR and streak: " + bot.sidebarLines());
        e2e.expect(line(bot, "Combat") == null, "the combat line still only shows in combat: " + bot.sidebarLines());

        e2e.step("a layout the server empties is no longer offered and its players see everything");
        withFile(e2e, "features/scoreboard.yml", Map.of(COMBAT_LAYOUT, "    combat: []"), x -> {
            e2e.eventually(() -> line(bot, "Money") != null && line(bot, "Kills") != null && bot.sidebarLines().size() == 8,
                "the full sidebar while fight stats are gone: " + bot.sidebarLines());
            AfkStaffSettingSteps.openGroup(e2e, bot, "display", "Display settings");
            Bot.SeenDialog without = AfkStaffSettingSteps.form(e2e, bot);
            e2e.expect(List.of("full", "compact").equals(without.options().get("sidebar_layout")),
                "fight stats are not offered: " + without.options().get("sidebar_layout"));
            e2e.expect("full".equals(without.choiceValue("sidebar_layout")), "the dialog shows what they see: " + without.choiceValue("sidebar_layout"));
            e2e.expect("combat".equals(stored(e2e, id, "sidebar-layout")), "their pick is kept for later");
        });
        e2e.eventually(() -> line(bot, "Money") == null && line(bot, "Kills") != null, "fight stats are back: " + bot.sidebarLines());

        e2e.step("the full sidebar again deletes the row");
        SetResult full = e2e.services().settings().set(id, ScoreboardFeature.LAYOUT, SidebarLayout.FULL, Change.api("e2e"));
        e2e.expect(full == SetResult.CHANGED, "changed: " + full);
        e2e.eventually(() -> bot.sidebarLines().size() == 8 && bot.sidebarLines().get(1).endsWith("Money $0"), "everything: " + bot.sidebarLines());
        e2e.eventually(() -> stored(e2e, id, "sidebar-layout") == null, "no row for the default");
    }

    /**
     * /sidebar goes through the settings: while features/settings.yml locks or hides the switch it says the server
     * sets it and changes nothing; the dialog shows the lock as text.
     */
    static void locked(E2E e2e) throws Exception {
        String name = e2e.name("SbLocked");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        awaitSidebar(e2e, bot);

        e2e.step("a server lock keeps the sidebar: /sidebar says so and changes nothing");
        withFile(e2e, "features/settings.yml", Map.of("hidden: []", "hidden: []\nlocked:\n  scoreboard: true"), x -> {
            bot.clearLogs();
            bot.command("sidebar off");
            e2e.eventually(() -> bot.anyFeedbackContains("Your sidebar is set by the server"), "the refusal: " + bot.actionBar() + " " + bot.chat());
            bot.clearLogs();
            bot.command("sidebar");
            e2e.eventually(() -> bot.anyFeedbackContains("Your sidebar is set by the server"), "a bare /sidebar too: " + bot.actionBar());
            bot.clearLogs();
            bot.command("sidebar on");
            e2e.eventually(() -> bot.anyFeedbackContains("already shown"), "on is what the lock says: " + bot.actionBar());
            e2e.sleep(1_500);
            e2e.expect(bot.displayed("sidebar") != null, "the sidebar stays");
            e2e.expect(stored(e2e, id, "scoreboard") == null, "nothing was stored");
            AfkStaffSettingSteps.openGroup(e2e, bot, "display", "Display settings");
            Bot.SeenDialog page = AfkStaffSettingSteps.form(e2e, bot);
            e2e.expect(!page.inputs().containsKey("scoreboard"), "no switch for a locked setting: " + page.inputs());
            e2e.expect(page.button("Sidebar: ON") != null && page.button("Sidebar: ON").tooltip().contains("Set by the server."),
                "it is shown as set by the server: " + page.buttons());
        });

        e2e.step("a switch the server hides is the server's too");
        withFile(e2e, "features/settings.yml", Map.of("hidden: []", "hidden: [scoreboard]"), x -> {
            bot.clearLogs();
            bot.command("sidebar off");
            e2e.eventually(() -> bot.anyFeedbackContains("Your sidebar is set by the server"), "the refusal: " + bot.actionBar());
            e2e.sleep(1_000);
            e2e.expect(bot.displayed("sidebar") != null, "the sidebar stays");
        });

        e2e.step("without the lock /sidebar works again");
        bot.clearLogs();
        bot.command("sidebar off");
        e2e.eventually(() -> bot.actionBarContains("Sidebar hidden"), "hidden: " + bot.actionBar());
        e2e.eventually(() -> bot.displayed("sidebar") == null, "the sidebar is gone at once");
        bot.command("sidebar on");
        awaitSidebar(e2e, bot);
        e2e.eventually(() -> stored(e2e, id, "scoreboard") == null, "on again is the default: no row");
    }

    /**
     * Show my rank off (defined by the integrations feature) makes a ranked player look like an ordinary member on
     * every board: no label in front of the name or in the tab list, the members' team and their tab position. It only
     * applies while the switch is offered (LuckPerms connected): without LuckPerms a stored "off" hides nothing, since
     * the player would have no switch to undo it.
     */
    static void hideRank(E2E e2e) throws Exception {
        String alexName = e2e.name("SbHideA");
        String blakeName = e2e.name("SbHideB");
        Bot alex = e2e.bot(alexName);
        Bot blake = e2e.bot(blakeName);
        UUID alexId = e2e.uuid(alexName);
        UUID blakeId = e2e.uuid(blakeName);
        e2e.eventually(() -> blake.teamOf(alexName) != null && blake.teamOf(blakeName) != null, "both are in teams on Blake's board");

        e2e.step("a Baron with the hide-rank node shows the Baron label first");
        PermissionAttachment[] attachment = new PermissionAttachment[1];
        e2e.onPlayer(alexName, () -> {
            attachment[0] = e2e.player(alexName).addAttachment(harness(), "group.baron", true);
            attachment[0].setPermission("siftcore.settings.hide-rank", true);
            return null;
        });
        try {
            output(e2e, "sidebar refresh");
            e2e.eventually(() -> blake.teamOf(alexName) != null && "Baron ".equals(blake.teamOf(alexName).prefix().getString())
                && blake.listName(alexId) != null && ("Baron " + alexName).equals(blake.listName(alexId).getString()),
                "Baron in front of Alex: " + blake.teamOf(alexName) + " / " + blake.listName(alexId));
            e2e.expect(blake.listOrder(alexId) > blake.listOrder(blakeId), "listed above a member");

            var entry = e2e.services().settings().registry().entry(IntegrationsFeature.SHOW_MY_RANK.id());
            Plugin luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (luckPerms == null || !luckPerms.isEnabled()) {
                e2e.step("without LuckPerms the switch is not offered, so a stored off hides nothing on the scoreboard");
                e2e.expect(!entry.offered(), "not offered without LuckPerms");
                SetResult off = e2e.services().settings().set(alexId, IntegrationsFeature.SHOW_MY_RANK, false, Change.api("e2e"));
                e2e.expect(off == SetResult.CHANGED, "stored anyway (as another plugin would): " + off);
                output(e2e, "sidebar refresh");
                e2e.sleep(1_500);
                e2e.expect(blake.teamOf(alexName) != null && "Baron ".equals(blake.teamOf(alexName).prefix().getString())
                    && ("Baron " + alexName).equals(blake.listName(alexId).getString()),
                    "the Baron label stays: " + blake.teamOf(alexName) + " / " + blake.listName(alexId));
                e2e.expect(blake.listOrder(alexId) > blake.listOrder(blakeId), "still listed above a member");
                SetResult on = e2e.services().settings().set(alexId, IntegrationsFeature.SHOW_MY_RANK, true, Change.api("e2e"));
                e2e.expect(on == SetResult.CHANGED, "changed back: " + on);
                e2e.eventually(() -> stored(e2e, alexId, "show-my-rank") == null, "no row for the default");
                return;
            }
            e2e.expect(entry.offered(), "offered while LuckPerms is connected");

            e2e.step("Show my rank off: Alex looks like a member everywhere, without a refresh");
            SetResult off = e2e.services().settings().set(alexId, IntegrationsFeature.SHOW_MY_RANK, false, Change.api("e2e"));
            e2e.expect(off == SetResult.CHANGED, "changed: " + off);
            e2e.eventually(() -> blake.teamOf(alexName) != null && blake.teamOf(alexName).prefix().getString().isEmpty()
                && blake.teamOf(alexName).name().equals(blake.teamOf(blakeName).name()), "the members' team: " + blake.teamOf(alexName));
            e2e.eventually(() -> blake.listName(alexId) != null && alexName.equals(blake.listName(alexId).getString()),
                "a plain tab name: " + blake.listName(alexId));
            e2e.eventually(() -> blake.listOrder(alexId) == blake.listOrder(blakeId),
                "the members' tab position: " + blake.listOrder(alexId) + " vs " + blake.listOrder(blakeId));
            e2e.expect(alex.teamOf(alexName) != null && alex.teamOf(alexName).prefix().getString().isEmpty(), "Alex's own board agrees");
            e2e.expect("false".equals(stored(e2e, alexId, "show-my-rank")), "the choice is stored");

            e2e.step("without the node the rank shows again (the choice stays stored)");
            e2e.onPlayer(alexName, () -> {
                attachment[0].unsetPermission("siftcore.settings.hide-rank");
                return null;
            });
            output(e2e, "sidebar refresh");
            e2e.eventually(() -> blake.teamOf(alexName) != null && "Baron ".equals(blake.teamOf(alexName).prefix().getString()),
                "Baron again: " + blake.teamOf(alexName));
            e2e.expect("false".equals(stored(e2e, alexId, "show-my-rank")), "still stored for when the node comes back");

            e2e.step("with the node back the stored choice applies; on again shows the rank and deletes the row");
            e2e.onPlayer(alexName, () -> {
                attachment[0].setPermission("siftcore.settings.hide-rank", true);
                return null;
            });
            output(e2e, "sidebar refresh");
            e2e.eventually(() -> blake.teamOf(alexName) != null && blake.teamOf(alexName).prefix().getString().isEmpty(),
                "hidden again: " + blake.teamOf(alexName));
            SetResult on = e2e.services().settings().set(alexId, IntegrationsFeature.SHOW_MY_RANK, true, Change.api("e2e"));
            e2e.expect(on == SetResult.CHANGED, "changed: " + on);
            e2e.eventually(() -> blake.teamOf(alexName) != null && "Baron ".equals(blake.teamOf(alexName).prefix().getString())
                && ("Baron " + alexName).equals(blake.listName(alexId).getString()), "Baron again: " + blake.listName(alexId));
            e2e.eventually(() -> stored(e2e, alexId, "show-my-rank") == null, "no row for the default");
        } finally {
            e2e.onPlayer(alexName, () -> {
                e2e.player(alexName).removeAttachment(attachment[0]);
                return null;
            });
        }
    }
}
