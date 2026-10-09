package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.feature.extras.ExtrasFeature;
import net.siftvanilla.siftcore.feature.extras.JoinLines;
import org.bukkit.Bukkit;

/**
 * End-to-end scenarios of the extras settings: which join and leave lines each player reads
 * ({@code join-leave-messages}, changed through the settings dialog and the setting API; plain lines, welcomes and
 * the rank lines of the cosmetics, also under a server lock) and who sees in {@code /seen} when a player was last
 * online ({@code seen-privacy}).
 */
final class ExtrasScenarios {

    private ExtrasScenarios() {
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
        list.add(of("extras-join-lines", ExtrasScenarios::joinLines));
        list.add(of("extras-rank-lines", ExtrasScenarios::rankLines));
        list.add(of("extras-seen-privacy", ExtrasScenarios::seenPrivacy));
        return list;
    }

    /** Gives an online player the rank join and leave line of the cosmetics ({@code siftcore.join.message}). */
    private static void rankLine(E2E e2e, String name) {
        e2e.onPlayer(name, () -> e2e.player(name).addAttachment(e2e.services().plugin(), "siftcore.join.message", true));
        e2e.eventually(() -> e2e.onPlayer(name, () -> e2e.player(name).hasPermission("siftcore.join.message")), name + " has a rank line");
    }

    /** Changes a setting the way a settings command does (by id and a typed value, with the player's permissions). */
    private static void set(E2E e2e, String name, String id, String value) {
        SetResult result = e2e.onPlayer(name, () -> e2e.services().settings().setParsed(e2e.player(name), id, value,
            Change.command(name)));
        e2e.expect(result.succeeded(), name + " sets " + id + " to " + value + ": " + result);
    }

    /** Runs {@code body} with a SiftCore file changed by exact replacements, then restores it (both reloaded). */
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
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed " + file + " reloads: " + reload);
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    static void joinLines(E2E e2e) throws Exception {
        String allName = e2e.name("JlAll");
        String newName = e2e.name("JlNew");
        String offName = e2e.name("JlOff");
        Bot all = e2e.bot(allName);
        Bot onlyNew = e2e.bot(newName);
        Bot off = e2e.bot(offName);

        e2e.step("new players only, chosen in the Server announcements settings; off through the setting API");
        onlyNew.clearLogs();
        onlyNew.command("settings announcements");
        Bot.SeenDialog page = e2e.dialog(onlyNew, "Server announcements settings");
        e2e.expect(List.of("all", "first-joins", "off").equals(page.options().get("join_leave_messages")),
            "the join and leave choice: " + page.options());
        Map<String, Object> values = page.values();
        values.put("join_leave_messages", "first-joins");
        e2e.click(onlyNew, "Save", values);
        e2e.eventually(() -> onlyNew.anyFeedbackContains("Join and leave messages set to New players only"),
            "saved: " + onlyNew.actionBar() + " " + onlyNew.chat());
        e2e.expect(e2e.services().settings().get(e2e.uuid(newName), ExtrasFeature.JOIN_LEAVE_MESSAGES) == JoinLines.FIRST_JOINS, "stored");
        set(e2e, offName, "join-leave-messages", "off");

        withFile(e2e, "features/extras.yml", Map.of("join: false", "join: true", "quit: false", "quit: true"), x -> {
            e2e.step("a brand-new player's welcome reaches every and new-players-only reader, not the one who turned it off");
            all.clearLogs();
            onlyNew.clearLogs();
            off.clearLogs();
            String joinerName = e2e.name("JlJoin");
            Bot joiner = e2e.bot(joinerName);
            e2e.eventually(() -> all.chatContains("Welcome " + joinerName + " to SiftVanilla"), "the welcome: " + all.chat());
            e2e.eventually(() -> onlyNew.chatContains("Welcome " + joinerName + " to SiftVanilla"), "new players only: " + onlyNew.chat());
            e2e.eventually(() -> joiner.chatContains("Welcome " + joinerName + " to SiftVanilla"), "the joiner's own welcome");
            e2e.sleep(800);
            e2e.expect(!off.chatContains(joinerName), "off: " + off.chat());

            e2e.step("leaving and coming back: plain lines only for every-line readers");
            all.clearLogs();
            onlyNew.clearLogs();
            off.clearLogs();
            joiner.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(joinerName) == null, joinerName + " left");
            e2e.eventually(() -> all.chatContains(joinerName + " left"), "the leave line: " + all.chat());
            Bot back = e2e.bot(joinerName);
            e2e.eventually(() -> all.chatContains(joinerName + " joined"), "the join line: " + all.chat());
            e2e.eventually(() -> back.chatContains(joinerName + " joined"), "the player reads their own line: " + back.chat());
            e2e.sleep(800);
            e2e.expect(!onlyNew.chatContains(joinerName), "new players only skips them: " + onlyNew.chat());
            e2e.expect(!off.chatContains(joinerName), "off skips them: " + off.chat());
        });
    }

    /**
     * A server without plain join, leave or welcome lines still shows rank lines (cosmetics), so the setting stays
     * offered and filters them, and a server lock of the setting wins over every reader's own choice.
     */
    static void rankLines(E2E e2e) throws Exception {
        String readerName = e2e.name("RlAll");
        String offName = e2e.name("RlOff");
        String firstName = e2e.name("RlRankA");
        String secondName = e2e.name("RlRankB");
        Bot reader = e2e.bot(readerName);
        Bot off = e2e.bot(offName);
        Bot first = e2e.bot(firstName);
        Bot second = e2e.bot(secondName);
        rankLine(e2e, firstName);
        rankLine(e2e, secondName);
        set(e2e, offName, "join-leave-messages", "off");

        withFile(e2e, "features/extras.yml", Map.of("first-join-welcome: true", "first-join-welcome: false"), x -> {
            e2e.step("no plain line and no welcome, but rank lines: the setting is still offered, without new players only");
            off.clearLogs();
            off.command("settings announcements");
            Bot.SeenDialog page = e2e.dialog(off, "Server announcements settings");
            e2e.expect(List.of("all", "off").equals(page.options().get("join_leave_messages")),
                "the join and leave choice is there: " + page.options());
            e2e.expect("off".equals(page.choiceValue("join_leave_messages")), "showing the stored choice: " + page.choiceInitial());

            e2e.step("a rank leave line reaches an every-line reader, not the one who turned them off");
            reader.clearLogs();
            off.clearLogs();
            first.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(firstName) == null, firstName + " left");
            e2e.eventually(() -> reader.chatContains(firstName + " left"), "the rank leave line: " + reader.chat());
            e2e.sleep(800);
            e2e.expect(!off.chatContains(firstName + " left"), "hidden for a reader who chose off: " + off.chat());

            e2e.step("a server lock to off hides rank lines from every reader");
            withFile(e2e, "features/settings.yml", Map.of("hidden: []", "hidden: []\nlocked:\n  join-leave-messages: off"), y -> {
                e2e.expect(e2e.services().settings().get(e2e.uuid(readerName), ExtrasFeature.JOIN_LEAVE_MESSAGES) == JoinLines.OFF,
                    "the lock is what every reader reads");
                reader.clearLogs();
                second.quit();
                e2e.eventually(() -> Bukkit.getPlayerExact(secondName) == null, secondName + " left");
                e2e.sleep(1_500);
                e2e.expect(!reader.chatContains(secondName + " left"), "the lock wins over the reader's every line: " + reader.chat());
            });
        });
    }

    static void seenPrivacy(E2E e2e) throws Exception {
        String ownerName = e2e.name("SeenMe");
        String strangerName = e2e.name("SeenX");
        String friendName = e2e.name("SeenPal");
        String staffName = e2e.name("SeenMod");
        Bot owner = e2e.bot(ownerName);
        Bot stranger = e2e.bot(strangerName);
        Bot friend = e2e.bot(friendName);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            e2e.console("sift friends add " + ownerName + " " + friendName);
            e2e.eventually(() -> e2e.services().relations().areFriends(e2e.uuid(ownerName), e2e.uuid(friendName)), "friends");
            set(e2e, ownerName, "seen-privacy", "friends");
            owner.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(ownerName) == null, ownerName + " left");

            e2e.step("an offline player who chose friends: hidden from a stranger, shown to a friend and staff");
            stranger.clearLogs();
            stranger.command("seen " + ownerName);
            e2e.eventually(() -> stranger.chatContains(ownerName + " keeps their last online time private"), "hidden: " + stranger.chat());
            e2e.expect(!stranger.chatContains("was last online"), "no time: " + stranger.chat());
            friend.clearLogs();
            friend.command("seen " + ownerName);
            e2e.eventually(() -> friend.chatContains(ownerName + " was last online"), "a friend sees it: " + friend.chat());
            staff.clearLogs();
            staff.command("seen " + ownerName);
            e2e.eventually(() -> staff.chatContains(ownerName + " was last online"), "staff see it: " + staff.chat());
            List<String> console = e2e.consoleOutput("seen " + ownerName);
            e2e.expect(String.join("\n", console).contains(ownerName + " was last online"), "the console sees it: " + console);
        } finally {
            e2e.console("deop " + staffName);
        }
    }
}
