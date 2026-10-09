package net.siftvanilla.e2e;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.bossbar.BossBar;
import net.siftvanilla.siftcore.api.event.SettingChangeEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.StatusBars;
import net.siftvanilla.siftcore.feature.chat.ChatFeature;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * End-to-end scenarios of the settings framework and dialog: groups and pages, all three kinds of input (a switch, a
 * choice and a slider) saving and persisting across a rejoin, storing the default deleting the row, server defaults,
 * locks and hidden settings from {@code features/settings.yml}, the feedback channel (and what keeps it from flooding
 * chat), sound settings, quiet in combat, sale receipts, status bars and {@code SettingChangeEvent}.
 */
final class SettingsScenarios {

    private SettingsScenarios() {
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
        list.add(of("settings-dialog", SettingsScenarios::dialog));
        list.add(of("settings-kinds", SettingsScenarios::kinds));
        list.add(of("settings-config", SettingsScenarios::config));
        list.add(of("settings-delivery", SettingsScenarios::delivery));
        list.add(of("settings-event", SettingsScenarios::event));
        list.add(of("settings-persist", SettingsScenarios::persist));
        list.add(of("settings-routing", SettingsScenarios::routing));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    private static PlayerSettings settings(E2E e2e) {
        return e2e.services().settings();
    }

    private static void expectSaw(E2E e2e, Bot bot, String text) {
        e2e.eventually(() -> bot.anyFeedbackContains(text), bot.name + " sees '" + text + "' (chat " + bot.chat() + ", action bar "
            + bot.actionBar() + ")");
    }

    /** Waits for a settings page titled exactly {@code title}. */
    private static Bot.SeenDialog page(E2E e2e, Bot bot, String title) {
        e2e.eventually(() -> bot.dialog() != null && bot.dialog().title().equals(title), bot.name + " sees '" + title + "': "
            + (bot.dialog() == null ? "none" : bot.dialog().title()));
        return bot.dialog();
    }

    /** Waits for the list of settings groups (titled just Settings). */
    private static Bot.SeenDialog settingsList(E2E e2e, Bot bot) {
        return page(e2e, bot, "Settings");
    }

    /** A player's stored row for a setting, or null when none is stored (read after every queued write). */
    private static String stored(E2E e2e, UUID player, String setting) throws Exception {
        return e2e.services().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM settings WHERE uuid = ? AND setting = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get(10, TimeUnit.SECONDS);
    }

    /** The sounds SiftCore plays (config.yml sounds and pings), leaving out the world's own sounds. */
    private static List<Bot.SeenSound> siftSounds(Bot bot) {
        Set<String> ours = Set.of("minecraft:block.note_block.bass", "minecraft:ui.button.click", "minecraft:block.note_block.pling",
            "minecraft:entity.experience_orb.pickup", "minecraft:block.note_block.bell", "minecraft:block.amethyst_block.chime");
        return bot.sounds().stream().filter(sound -> ours.contains(sound.sound())).toList();
    }

    private static void expectStored(E2E e2e, UUID player, String setting, String value) {
        e2e.eventually(() -> {
            try {
                return java.util.Objects.equals(value, stored(e2e, player, setting));
            } catch (Exception e) {
                return false;
            }
        }, setting + " stored as " + value);
    }

    /**
     * Changes inputs of a paged settings group wherever they are: walks the pages from the one open now with Next page
     * (changes carried along as pending), sets each key on the page that shows it, and saves on the page where the last
     * one was found.
     */
    private static void editAcrossPages(E2E e2e, Bot bot, String title, Map<String, Object> wanted) {
        Set<String> left = new HashSet<>(wanted.keySet());
        for (int guard = 0; guard < 30; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            Map<String, Object> values = current.values();
            for (String key : List.copyOf(left)) {
                if (current.inputs().containsKey(key)) {
                    values.put(key, wanted.get(key));
                    left.remove(key);
                }
            }
            if (left.isEmpty()) {
                e2e.click(bot, "Save", values);
                return;
            }
            e2e.expect(current.button("Next page") != null, "inputs " + left + " on a later page of " + title + " (last page: "
                + current.inputs().keySet() + ")");
            e2e.click(bot, "Next page", values);
        }
        throw new E2E.Failure("too many pages in " + title);
    }

    /** Every input key of a paged group, walking its pages with Next page from page 1. */
    private static Set<String> keysAcrossPages(E2E e2e, Bot bot, String title) {
        Set<String> keys = new HashSet<>();
        for (int guard = 0; guard < 30; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            keys.addAll(current.inputs().keySet());
            if (current.button("Next page") == null) {
                return keys;
            }
            e2e.click(bot, "Next page", current.values());
        }
        throw new E2E.Failure("too many pages in " + title);
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

    /**
     * Runs {@code body} with top-level keys of {@code features/settings.yml} set, then restores the file (both reloaded).
     * Each value is the key's whole YAML text: it replaces the key's line when the file has the key on one line (a
     * shipped {@code hidden: []}), or is appended when it has none (an upgraded server only gains value keys, so
     * {@code defaults} and {@code locked} may be missing).
     */
    private static void withSettingsKeys(E2E e2e, Map<String, String> keys, Body body) throws Exception {
        Path path = Bukkit.getPluginManager().getPlugin("SiftCore").getDataFolder().toPath().resolve("features/settings.yml");
        String original = Files.readString(path, StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(original.lines().toList());
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(entry.getKey() + ":")) {
                    at = i;
                }
            }
            if (at < 0) {
                lines.add(entry.getValue());
                continue;
            }
            e2e.expect(at + 1 >= lines.size() || !lines.get(at + 1).startsWith(" "),
                "features/settings.yml has " + entry.getKey() + " on one line: " + lines.get(at));
            lines.set(at, entry.getValue());
        }
        Files.writeString(path, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        try {
            List<String> reload = e2e.consoleOutput("sift reload");
            e2e.expect(String.join(" ", reload).contains("Reloaded"), "the changed settings.yml reloads: " + reload);
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /** The settings of the General group the player sees (in dialog order). */
    private static List<Registry.Entry<?>> visibleIn(E2E e2e, String category, String player) {
        Player online = e2e.player(player);
        List<Registry.Entry<?>> list = new ArrayList<>();
        for (Registry.Entry<?> entry : settings(e2e).registry().in(category)) {
            if (settings(e2e).visible(entry, online::hasPermission)) {
                list.add(entry);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ the dialog (groups, pages, saving)

    /**
     * Groups, pages and saving with switches: the main menu and pause menu routes, group order and text, the General
     * group (page-aware: a setting may sit on any page), saving only flipped switches, stale pages, staff switches, and
     * paging with changes carried between pages.
     */
    static void dialog(E2E e2e) throws Exception {
        String name = e2e.name("Setter");
        String staffName = e2e.name("SetStaff");
        Bot bot = e2e.bot(name);
        Bot staff = e2e.bot(staffName);
        e2e.console("op " + staffName);
        try {
            e2e.step("the main menu and the pause menu open the settings list");
            bot.command("menu");
            e2e.dialog(bot, "SiftVanilla");
            e2e.click(bot, "Settings");
            Bot.SeenDialog fromMenu = settingsList(e2e, bot);
            e2e.expect(fromMenu.button("Back") != null, "a way back to the menu: " + fromMenu.buttons());
            e2e.expect("none".equals(fromMenu.after()), "the list stays on screen until the next dialog: " + fromMenu.after());
            e2e.click(bot, "Back");
            e2e.dialog(bot, "SiftVanilla");
            bot.clearLogs();
            bot.rawClick("siftcore:hub/settings", null);
            settingsList(e2e, bot);

            e2e.step("settings are grouped in the shared groups, General last");
            bot.clearLogs();
            bot.command("settings");
            Bot.SeenDialog list = settingsList(e2e, bot);
            for (String group : List.of("Chat", "Sounds", "Display", "General")) {
                e2e.expect(list.button(group) != null, "a " + group + " group: " + list.buttons());
            }
            String body = list.bodyText();
            e2e.expect(body.contains("Chat: Mentions, private messages and what you see in public chat"), "group descriptions: " + list.body());
            e2e.expect(body.indexOf("Chat:") < body.indexOf("Sounds:") && body.indexOf("Sounds:") < body.indexOf("Display:")
                && body.indexOf("Display:") < body.indexOf("General:"), "groups in their order: " + list.body());
            e2e.expect(list.bodyText().lines().filter(line -> line.contains(": ") && !line.startsWith("Pick"))
                .allMatch(line -> line.startsWith("[")), "group lines start with their icon sprite: " + list.body());
            e2e.expect(list.button("Staff") == null, "no staff group for players: " + list.buttons());
            e2e.expect(list.button("Privacy") == null, "no group of settings that nothing reads yet: " + list.buttons());
            e2e.expect(list.button("Close") != null, "a command opens it with Close: " + list.buttons());
            e2e.click(bot, "Chat");
            Bot.SeenDialog chat = page(e2e, bot, "Chat settings");
            e2e.expect("toggle".equals(chat.inputs().get("mentions")) && "toggle".equals(chat.inputs().get("private_messages")),
                "the chat switches: " + chat.inputs());
            e2e.expect(!chat.inputs().containsKey("social_spy"), "no staff switches for players: " + chat.inputs());
            e2e.expect(chat.bodyText().contains("Private messages: Let players send me private messages"), "descriptions: " + chat.body());
            e2e.click(bot, "Back");
            settingsList(e2e, bot);
            e2e.click(bot, "General");
            Set<String> general = keysAcrossPages(e2e, bot, "General settings");
            for (String key : List.of("pay_notifications", "auction_sales", "crate_wins", "death_messages", "tpa_requests")) {
                e2e.expect(general.contains(key), "a switch for " + key + " on one of the General pages: " + general);
            }
            e2e.expect(!general.contains("mentions") && !general.contains("sound_volume"), "grouped settings stay in their group: " + general);

            e2e.step("saving stores the flipped switches wherever they are, says so and goes back to the list");
            bot.clearLogs();
            bot.command("settings general");
            editAcrossPages(e2e, bot, "General settings", Map.of("tpa_requests", false, "pay_notifications", false));
            expectSaw(e2e, bot, "Saved 2 settings");
            settingsList(e2e, bot);
            e2e.expect(!settings(e2e).enabled(e2e.uuid(name), settings(e2e).toggle("tpa-requests")), "teleport requests off");
            e2e.expect(!settings(e2e).enabled(e2e.uuid(name), settings(e2e).toggle("pay-notifications")), "payment messages off");
            e2e.click(bot, "Chat");
            Bot.SeenDialog chatAgain = page(e2e, bot, "Chat settings");
            Map<String, Object> chatValues = chatAgain.values();
            chatValues.put("private_messages", false);
            bot.clearMessages();
            e2e.click(bot, "Save", chatValues);
            expectSaw(e2e, bot, "Private messages turned off");
            e2e.expect(!settings(e2e).enabled(e2e.uuid(name), ChatFeature.PRIVATE_MESSAGES), "private messages off");

            e2e.step("saving without changes says nothing changed; Back leaves without saving");
            bot.clearLogs();
            bot.command("settings chat");
            Bot.SeenDialog unchanged = page(e2e, bot, "Chat settings");
            e2e.expect(Boolean.FALSE.equals(unchanged.toggleValue("private_messages")), "the page shows the stored value");
            e2e.click(bot, "Save", unchanged.values());
            expectSaw(e2e, bot, "Nothing changed");
            bot.command("settings chat");
            Bot.SeenDialog discard = page(e2e, bot, "Chat settings");
            Map<String, Object> discarded = discard.values();
            discarded.put("mentions", false);
            e2e.click(bot, "Back", discarded);
            settingsList(e2e, bot);
            e2e.expect(settings(e2e).enabled(e2e.uuid(name), ChatFeature.MENTIONS), "Back saved nothing");

            e2e.step("a switch changed elsewhere while the page was open is not overwritten");
            bot.clearLogs();
            bot.command("settings chat");
            Bot.SeenDialog open = page(e2e, bot, "Chat settings");
            Map<String, Object> stale = open.values();
            e2e.expect(Boolean.FALSE.equals(stale.get("private_messages")), "the page shows private messages off");
            bot.command("msgtoggle");
            expectSaw(e2e, bot, "Players can send you private messages again");
            e2e.expect(bot.dialog() == open, "the settings page is still the open one");
            stale.put("mentions", false);
            e2e.click(bot, "Save", stale);
            expectSaw(e2e, bot, "Mention alerts turned off");
            e2e.expect(settings(e2e).enabled(e2e.uuid(name), ChatFeature.PRIVATE_MESSAGES), "the /msgtoggle change survived the stale page");
            e2e.expect(!settings(e2e).enabled(e2e.uuid(name), ChatFeature.MENTIONS), "the flipped switch was saved");

            e2e.step("/settings <group> opens a group, and an unknown group is refused");
            bot.clearLogs();
            bot.command("settings nosuchgroup");
            expectSaw(e2e, bot, "There is no settings group called nosuchgroup");
            bot.command("settings GENERAL");
            page(e2e, bot, "General settings");

            e2e.step("staff see their own switches in the group they belong to");
            staff.command("settings chat");
            Bot.SeenDialog staffChat = page(e2e, staff, "Chat settings");
            e2e.expect("toggle".equals(staffChat.inputs().get("social_spy")), "social spy for staff: " + staffChat.inputs());

            e2e.step("long groups are paged; flips are carried between pages and saved together");
            int count = visibleIn(e2e, "general", name).size();
            int pages = (count + 1) / 2;
            e2e.expect(pages >= 2, "General holds at least three settings: " + count);
            withFile(e2e, "features/settings.yml", Map.of("page-size: 8", "page-size: 2"), x -> {
                bot.clearLogs();
                bot.command("settings general");
                Bot.SeenDialog page1 = page(e2e, bot, "General settings");
                e2e.expect(page1.inputs().size() == 2 && page1.bodyText().contains("Page 1 of " + pages), "page 1 of " + pages + ": "
                    + page1.inputs() + " " + page1.body());
                e2e.expect(page1.button("Next page") != null && page1.button("Previous page") == null, "only Next: " + page1.buttons());
                String first = page1.inputs().keySet().stream().sorted().findFirst().orElseThrow();
                Map<String, Object> flipped = page1.values();
                boolean firstWas = Boolean.TRUE.equals(flipped.get(first));
                flipped.put(first, !firstWas);
                e2e.click(bot, "Next page", flipped);
                Bot.SeenDialog page2 = page(e2e, bot, "General settings");
                e2e.expect(page2.bodyText().contains("Page 2 of " + pages) && page2.bodyText().contains("Changes on other pages: 1"),
                    "page 2 knows about the change on page 1: " + page2.body());
                e2e.expect(page2.button("Previous page") != null, "a way back: " + page2.buttons());
                String second = page2.inputs().keySet().stream().sorted().findFirst().orElseThrow();
                Map<String, Object> flipped2 = page2.values();
                boolean secondWas = Boolean.TRUE.equals(flipped2.get(second));
                flipped2.put(second, !secondWas);
                e2e.click(bot, "Previous page", flipped2);
                Bot.SeenDialog back1 = page(e2e, bot, "General settings");
                e2e.expect(back1.bodyText().contains("Page 1 of " + pages) && Boolean.valueOf(!firstWas).equals(back1.toggleValue(first)),
                    "page 1 shows the unsaved flip: " + back1.body());
                bot.clearMessages();
                e2e.click(bot, "Save", back1.values());
                expectSaw(e2e, bot, "Saved 2 settings");
                // Input keys are looked up through the registry: ids with '_' (orders_announce) and '-' both work.
                Registry registry = settings(e2e).registry();
                Registry.Entry<?> firstEntry = registry.find(first, "general");
                Registry.Entry<?> secondEntry = registry.find(second, "general");
                e2e.expect(firstEntry != null && secondEntry != null, "both keys name settings: " + first + ", " + second);
                e2e.expect(String.valueOf(!firstWas).equals(settings(e2e).encoded(e2e.uuid(name), firstEntry.id()))
                    && String.valueOf(!secondWas).equals(settings(e2e).encoded(e2e.uuid(name), secondEntry.id())), "both flips were saved");
            });
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    // ------------------------------------------------------------------ all three kinds, persistence, defaults

    /**
     * A slider, a switch and a choice through the dialog: what the pages show, saving, the stored rows, persisting
     * across a rejoin, forged values refused by the router, and storing the default deleting the row again.
     */
    static void kinds(E2E e2e) throws Exception {
        String name = e2e.name("Kinds");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("the Sounds page: a slider with its unit and range, and switches");
        bot.command("settings sound");
        Bot.SeenDialog sound = page(e2e, bot, "Sounds settings");
        e2e.expect("range".equals(sound.inputs().get("sound_volume")) && "toggle".equals(sound.inputs().get("sound_notify")),
            "a slider and switches: " + sound.inputs());
        Bot.RangeSeen volume = sound.range("sound_volume");
        e2e.expect(volume.start() == 0f && volume.end() == 100f && Float.valueOf(10f).equals(volume.step()) && volume.initial() == 100f,
            "0 to 100 in steps of 10, starting at 100: " + volume);
        e2e.expect(volume.label().equals("SiftCore volume (%)"), "the unit in the label: " + volume.label());
        e2e.expect(Boolean.TRUE.equals(sound.toggleValue("sound_notify")), "pings on by default");
        e2e.expect(!sound.inputs().containsKey("sound_mention"), "no ping choice until chat plays it: " + sound.inputs());
        e2e.expect(sound.bodyText().contains("SiftCore volume: How loud menu clicks"), "descriptions: " + sound.body());

        e2e.step("saving a slider and a switch stores both");
        Map<String, Object> values = sound.values();
        values.put("sound_volume", 40f);
        values.put("sound_notify", false);
        bot.clearMessages();
        e2e.click(bot, "Save", values);
        expectSaw(e2e, bot, "Saved 2 settings");
        expectStored(e2e, id, "sound-volume", "40");
        expectStored(e2e, id, "sound-notify", "false");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_VOLUME) == 40L, "40% now");

        e2e.step("the Display page: a choice with the shared option names");
        bot.command("settings display");
        Bot.SeenDialog display = page(e2e, bot, "Display settings");
        e2e.expect("choice".equals(display.inputs().get("feedback_channel")) && "toggle".equals(display.inputs().get("scoreboard")),
            "a choice and the sidebar switch: " + display.inputs());
        e2e.expect(List.of("actionbar", "chat", "both").equals(display.options().get("feedback_channel")), "options: " + display.options());
        e2e.expect(List.of("Above the hotbar", "Chat", "Both").equals(display.optionLabels().get("feedback_channel")),
            "labels: " + display.optionLabels());
        e2e.expect("actionbar".equals(display.choiceValue("feedback_channel")), "starts on the default");
        Map<String, Object> displayValues = display.values();
        displayValues.put("feedback_channel", "chat");
        bot.clearMessages();
        e2e.click(bot, "Save", displayValues);
        e2e.eventually(() -> bot.chatContains("Quick results and errors set to Chat."),
            "the confirmation, already in chat as just chosen: chat " + bot.chat() + ", action bar " + bot.actionBar());
        expectStored(e2e, id, "feedback-channel", "chat");

        e2e.step("forged values are refused by the router and change nothing");
        bot.command("settings sound");
        Bot.SeenDialog again = page(e2e, bot, "Sounds settings");
        Map<String, Object> forged = again.values();
        forged.put("sound_volume", 45f);
        e2e.click(bot, "Save", forged);
        Bot.SeenDialog refused = page(e2e, bot, "Sounds settings");
        e2e.expect(refused.bodyText().contains("Check SiftCore volume (%) and try again"), "an off-step slider value: " + refused.body());
        forged = refused.values();
        forged.put("sound_volume", 110f);
        e2e.click(bot, "Save", forged);
        e2e.expect(page(e2e, bot, "Sounds settings").bodyText().contains("Check SiftCore volume"), "out of range too");
        bot.command("settings display");
        Bot.SeenDialog displayForged = page(e2e, bot, "Display settings");
        Map<String, Object> badChoice = displayForged.values();
        badChoice.put("feedback_channel", "title");
        e2e.click(bot, "Save", badChoice);
        e2e.expect(page(e2e, bot, "Display settings").bodyText().contains("Check Quick results and errors and try again"),
            "an option the page did not offer");
        expectStored(e2e, id, "sound-volume", "40");
        expectStored(e2e, id, "feedback-channel", "chat");

        e2e.step("everything persists across a rejoin");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        e2e.expect(!settings(e2e).loaded(id), "forgotten on quit");
        Bot back = e2e.bot(name);
        e2e.expect(e2e.services().directory().previousSeen(id) > 0, "the last visit is remembered for join summaries");
        back.command("settings sound");
        Bot.SeenDialog soundAfter = page(e2e, back, "Sounds settings");
        e2e.expect(soundAfter.range("sound_volume").initial() == 40f && Boolean.FALSE.equals(soundAfter.toggleValue("sound_notify")),
            "the slider and switch as saved: " + soundAfter.range("sound_volume") + " " + soundAfter.initials());
        back.command("settings display");
        Bot.SeenDialog displayAfter = page(e2e, back, "Display settings");
        e2e.expect("chat".equals(displayAfter.choiceValue("feedback_channel")), "the choice as saved");

        e2e.step("storing the default deletes the row, so the player follows the default again");
        Map<String, Object> reset = displayAfter.values();
        reset.put("feedback_channel", "actionbar");
        back.clearMessages();
        e2e.click(back, "Save", reset);
        expectSaw(e2e, back, "Quick results and errors set to Above the hotbar");
        expectStored(e2e, id, "feedback-channel", null);
        back.command("settings sound");
        Map<String, Object> soundReset = page(e2e, back, "Sounds settings").values();
        soundReset.put("sound_volume", 100f);
        soundReset.put("sound_notify", true);
        back.clearMessages();
        e2e.click(back, "Save", soundReset);
        expectSaw(e2e, back, "Saved 2 settings");
        expectStored(e2e, id, "sound-volume", null);
        expectStored(e2e, id, "sound-notify", null);
        e2e.expect(!settings(e2e).changed(id, SharedSettings.SOUND_VOLUME), "nothing counts as changed");
    }

    // ------------------------------------------------------------------ server defaults, locks, hidden settings

    /** features/settings.yml: a server default (and a player who stored it following it), a lock and a hidden setting. */
    static void config(E2E e2e) throws Exception {
        String keeper = e2e.name("Keeper");
        String follower = e2e.name("Follower");
        Bot keep = e2e.bot(keeper);
        Bot follow = e2e.bot(follower);
        UUID keepId = e2e.uuid(keeper);
        UUID followId = e2e.uuid(follower);
        e2e.expect(settings(e2e).set(keepId, SharedSettings.SOUND_VOLUME, 30L, Change.feature()) == SetResult.CHANGED, "keeper picks 30%");
        e2e.expect(settings(e2e).set(keepId, SharedSettings.SOUND_CLICKS, false, Change.feature()) == SetResult.CHANGED,
            "keeper turns click sounds off");
        expectStored(e2e, keepId, "sound-volume", "30");
        expectStored(e2e, keepId, "sound-clicks", "false");

        withSettingsKeys(e2e, Map.of(
            "defaults", "defaults:\n  sound-volume: 60",
            "locked", "locked:\n  quiet-in-combat: true",
            "hidden", "hidden: [sound-clicks]"), x -> {
            e2e.step("a server default reaches players who never changed it; a stored choice is kept");
            e2e.expect(settings(e2e).get(followId, SharedSettings.SOUND_VOLUME) == 60L, "the follower reads the server default");
            e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_VOLUME) == 30L, "the keeper keeps 30%");
            follow.command("settings sound");
            Bot.SeenDialog page = page(e2e, follow, "Sounds settings");
            e2e.expect(page.range("sound_volume").initial() == 60f, "the slider starts at the server default: " + page.range("sound_volume"));

            e2e.step("a hidden setting is left out of the dialog, reads the server's value and can't be changed");
            e2e.expect(!page.inputs().containsKey("sound_clicks"), "no click sound switch: " + page.inputs());
            e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "the keeper's stored 'off' does not apply while hidden");
            e2e.expect(settings(e2e).set(keepId, SharedSettings.SOUND_CLICKS, false, Change.api("SiftE2E")) == SetResult.NOT_ALLOWED,
                "refused by the registry");
            expectStored(e2e, keepId, "sound-clicks", "false");

            e2e.step("storing the server default deletes the row");
            keep.command("settings sound");
            Map<String, Object> values = page(e2e, keep, "Sounds settings").values();
            values.put("sound_volume", 60f);
            keep.clearMessages();
            e2e.click(keep, "Save", values);
            expectSaw(e2e, keep, "SiftCore volume set to 60%");
            expectStored(e2e, keepId, "sound-volume", null);

            e2e.step("a locked setting shows its value as text and can't be changed");
            follow.command("settings combat");
            Bot.SeenDialog combat = page(e2e, follow, "Combat & stats settings");
            e2e.expect(!combat.inputs().containsKey("quiet_in_combat"), "no input for a locked setting: " + combat.inputs());
            e2e.expect(combat.bodyText().contains("Quiet during combat: on (set by the server)"), "the value as text: " + combat.body());
            e2e.expect(settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is the value");
            e2e.expect(settings(e2e).set(followId, SharedSettings.QUIET_IN_COMBAT, false, Change.api("SiftE2E")) == SetResult.LOCKED,
                "refused by the registry");
        });

        e2e.step("without the server default the player who stored it follows the new default");
        e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_VOLUME) == 100L, "back to 100%");
        e2e.expect(!settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is gone");
        e2e.expect(!settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "shown again: the keeper's stored choice is back");
    }

    // ------------------------------------------------------------------ delivery: feedback channel, sounds, quiet, bars

    /**
     * The feedback channel moving results and errors to chat (status lines stay), sound volume and kind switches, quiet
     * in combat for alerts and pings, and status bars.
     */
    static void delivery(E2E e2e) throws Exception {
        String name = e2e.name("Deliver");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Player player = e2e.player(name);
        PlayerSettings settings = settings(e2e);
        try {
            e2e.step("errors show above the hotbar by default");
            bot.clearLogs();
            bot.command("pay " + name + " 10");
            e2e.eventually(() -> bot.actionBarContains("yourself"), "the error on the action bar: " + bot.actionBar());
            e2e.expect(!bot.chatContains("yourself"), "not in chat: " + bot.chat());

            e2e.step("the error sound plays at the player's volume");
            e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note: " + bot.sounds());
            float full = bot.sounds().stream().filter(s -> s.sound().endsWith("note_block.bass")).findFirst().orElseThrow().volume();
            settings.set(id, SharedSettings.SOUND_VOLUME, 50L, Change.feature());
            bot.clearLogs();
            e2e.sleep(2_100);
            bot.command("pay " + name + " 10");
            e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note again");
            float half = bot.sounds().stream().filter(s -> s.sound().endsWith("note_block.bass")).findFirst().orElseThrow().volume();
            e2e.expect(Math.abs(half - full / 2f) < 0.01f, "half as loud at 50%: " + full + " then " + half);

            e2e.step("switching error sounds off (or the volume to 0) silences them; the message stays");
            settings.set(id, SharedSettings.SOUND_ERRORS, false, Change.feature());
            bot.clearLogs();
            e2e.sleep(2_100);
            bot.command("pay " + name + " 10");
            e2e.eventually(() -> bot.actionBarContains("yourself"), "the error still shows");
            e2e.sleep(500);
            e2e.expect(bot.sounds().stream().noneMatch(s -> s.sound().endsWith("note_block.bass")), "no error note: " + bot.sounds());
            settings.set(id, SharedSettings.SOUND_ERRORS, true, Change.feature());
            settings.set(id, SharedSettings.SOUND_VOLUME, 0L, Change.feature());
            bot.clearLogs();
            e2e.services().messenger().feedback(player, Feedback.ERROR);
            e2e.services().messenger().feedback(player, Feedback.CLICK);
            e2e.sleep(600);
            e2e.expect(siftSounds(bot).isEmpty(), "0% mutes every SiftCore sound: " + bot.sounds());
            settings.set(id, SharedSettings.SOUND_VOLUME, 100L, Change.feature());

            e2e.step("the feedback channel moves results and errors to chat, or both");
            settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.CHAT, Change.feature());
            bot.clearLogs();
            e2e.sleep(2_100);
            bot.command("pay " + name + " 10");
            e2e.eventually(() -> bot.chatContains("yourself"), "the error in chat: " + bot.chat());
            e2e.expect(!bot.actionBarContains("yourself"), "not above the hotbar: " + bot.actionBar());
            settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.BOTH, Change.feature());
            bot.clearLogs();
            e2e.sleep(2_100);
            bot.command("pay " + name + " 10");
            e2e.eventually(() -> bot.chatContains("yourself") && bot.actionBarContains("yourself"), "both places: " + bot.chat() + " "
                + bot.actionBar());

            e2e.step("repeating status lines stay above the hotbar whatever the channel");
            settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.CHAT, Change.feature());
            bot.clearLogs();
            e2e.services().messenger().send(player, TeleportMessages.WARMUP, Arg.time("time", Duration.ofSeconds(3)));
            e2e.eventually(() -> bot.actionBarContains("Teleporting in"), "the countdown above the hotbar: " + bot.actionBar());
            e2e.expect(!bot.chatContains("Teleporting in"), "not in chat: " + bot.chat());
            bot.clearLogs();
            e2e.console("combat tag " + name + " 30s");
            e2e.eventually(() -> bot.actionBarContains("In combat"), "the combat timer above the hotbar: " + bot.actionBar());
            e2e.expect(!bot.chatContains("In combat "), "the timer never goes to chat: " + bot.chat());
            settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.ACTIONBAR, Change.feature());

            e2e.step("quiet in combat turns pop-ups into chat lines and silences pings, not errors");
            settings.set(id, SharedSettings.QUIET_IN_COMBAT, true, Change.feature());
            bot.clearLogs();
            e2e.services().messenger().alert(player, AlertStyle.ACTIONBAR, CoreMessages.LOADING);
            e2e.eventually(() -> bot.chatContains("Loading..."), "the alert as a chat line: " + bot.chat());
            e2e.expect(!bot.actionBarContains("Loading..."), "not above the hotbar: " + bot.actionBar());
            bot.clearLogs();
            e2e.services().messenger().alert(player, AlertStyle.TITLE, CoreMessages.LOADING);
            e2e.eventually(() -> bot.chatContains("Loading..."), "a title alert as a chat line too");
            e2e.expect(bot.titles().stream().noneMatch(t -> t.contains("Loading")), "no title: " + bot.titles());
            bot.clearLogs();
            e2e.services().messenger().alert(player, AlertStyle.ACTIONBAR, false, CoreMessages.LOADING);
            e2e.eventually(() -> bot.actionBarContains("Loading..."), "combat's own alerts still pop up: " + bot.actionBar());
            bot.clearLogs();
            e2e.services().messenger().feedback(player, Feedback.NOTIFY);
            e2e.services().messenger().feedback(player, Feedback.SUCCESS);
            e2e.services().messenger().feedback(player, Feedback.ERROR);
            e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note plays");
            e2e.sleep(400);
            e2e.expect(siftSounds(bot).size() == 1, "no ping or chime in combat: " + bot.sounds());
            e2e.console("combat untag " + name);
            e2e.eventually(() -> !e2e.services().messenger().quietNow(id), "out of combat");
            bot.clearLogs();
            e2e.services().messenger().alert(player, AlertStyle.ACTIONBAR, CoreMessages.LOADING);
            e2e.eventually(() -> bot.actionBarContains("Loading..."), "out of combat alerts pop up again: " + bot.actionBar());
            e2e.services().messenger().alert(player, AlertStyle.OFF, CoreMessages.LOADING);

            e2e.step("status bars: one boss bar per player, the highest priority shown");
            StatusBars bars = e2e.services().statusBars();
            bars.show(player, "e2e-idle", new StatusBars.Bar(net.kyori.adventure.text.Component.text("Idle status"), 0.25f,
                BossBar.Color.BLUE, BossBar.Overlay.PROGRESS, StatusBars.PRIORITY_IDLE));
            e2e.eventually(() -> bot.bossBars().size() == 1 && bot.bossBars().getFirst().name().equals("Idle status"),
                "the bar shows: " + bot.bossBars());
            bars.show(player, "e2e-combat", new StatusBars.Bar(net.kyori.adventure.text.Component.text("Combat status"), 0.75f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS, StatusBars.PRIORITY_COMBAT));
            e2e.eventually(() -> bot.bossBars().size() == 1 && bot.bossBars().getFirst().name().equals("Combat status")
                && Math.abs(bot.bossBars().getFirst().progress() - 0.75f) < 0.01f, "the higher priority takes the one bar: " + bot.bossBars());
            bars.hide(player, "e2e-combat");
            e2e.eventually(() -> bot.bossBars().size() == 1 && bot.bossBars().getFirst().name().equals("Idle status"),
                "and gives it back: " + bot.bossBars());
            bars.hide(player, "e2e-idle");
            e2e.eventually(() -> bot.bossBars().isEmpty(), "gone with the last status: " + bot.bossBars());

            e2e.step("who-can settings know the friends system");
            e2e.expect(e2e.services().relations().friendsAvailable(), "relations are bound to the friends feature");
        } finally {
            e2e.console("combat untag " + name);
        }
    }

    // ------------------------------------------------------------------ routing: repeats, refusals, sale receipts

    /**
     * Chat as the feedback channel without floods: an error the player keeps triggering shows in chat once, the
     * refusal of walking into spawn while tagged stays above the hotbar (once a second), and sale receipts in the
     * hotbar style stay there whatever the feedback channel, becoming a chat line while quiet in combat.
     */
    static void routing(E2E e2e) throws Exception {
        String name = e2e.name("Router");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Player player = e2e.player(name);
        PlayerSettings settings = settings(e2e);
        settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.CHAT, Change.feature());
        try {
            e2e.step("an error repeated while the player keeps trying shows in chat once");
            bot.clearLogs();
            for (int i = 0; i < 5; i++) {
                e2e.services().messenger().send(player, CoreMessages.NO_PERMISSION);
                e2e.sleep(100);
            }
            e2e.eventually(() -> bot.chatContains("You can't do that."), "the error in chat: " + bot.chat());
            e2e.sleep(300);
            e2e.expect(bot.chat().stream().filter(line -> line.contains("You can't do that.")).count() == 1,
                "one line for the burst: " + bot.chat());
            e2e.expect(bot.sounds().stream().filter(s -> s.sound().endsWith("note_block.bass")).count() == 5,
                "the error note still plays each time: " + bot.sounds());
            e2e.sleep(2_300);
            e2e.services().messenger().send(player, CoreMessages.NO_PERMISSION);
            e2e.eventually(() -> bot.chat().stream().filter(line -> line.contains("You can't do that.")).count() == 2,
                "after a pause it shows again: " + bot.chat());

            e2e.step("walking into spawn while tagged: refused above the hotbar, once a second, never in chat");
            SpawnArea area = e2e.spawnArea();
            e2e.expect(area != null, "the server has a protected spawn area");
            Location here = e2e.onPlayer(name, player::getLocation);
            // E2E#bot left the spawn diagonally (+x, +z): walk back the same way to find the edge.
            Location edge = null;
            for (double d = 0; d < 512 && edge == null; d += 0.25) {
                Location candidate = here.clone().add(-d / Math.sqrt(2), 0, -d / Math.sqrt(2));
                if (area.contains(candidate)) {
                    edge = candidate;
                }
            }
            e2e.expect(edge != null, "found the edge of the spawn area from " + here);
            Location start = edge.clone().add(2.5 / Math.sqrt(2), 0, 2.5 / Math.sqrt(2));
            start.setY(Math.max(start.getY(), 120) + 40);
            e2e.expect(!area.contains(start), "the start is outside the spawn area");
            Location target = start;
            player.teleportAsync(target);
            e2e.eventually(() -> Math.abs(bot.x() - target.getX()) < 0.5 && Math.abs(bot.z() - target.getZ()) < 0.5,
                name + " hovers next to the spawn edge at " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ());
            e2e.console("combat tag " + name + " 30s");
            e2e.eventually(() -> bot.actionBarContains("In combat"), "tagged: " + bot.actionBar());
            bot.clearLogs();
            for (int i = 0; i < 8; i++) {
                bot.moveBy(-1.2, 0, -1.2, 0f);
                e2e.sleep(80);
            }
            e2e.eventually(() -> bot.actionBarContains("You can't go into spawn in combat"), "refused above the hotbar: " + bot.actionBar());
            e2e.sleep(300);
            Location after = e2e.onPlayer(name, player::getLocation);
            e2e.expect(!area.contains(after), "still outside the spawn area: " + after);
            e2e.expect(bot.actionBar().stream().filter(line -> line.contains("You can't go into spawn")).count() == 1,
                "told once for the whole push: " + bot.actionBar());
            e2e.expect(bot.chat().stream().noneMatch(line -> line.contains("You can't go into spawn")),
                "not in chat although the feedback channel is chat: " + bot.chat());
            e2e.console("combat untag " + name);

            e2e.step("sale receipts in the hotbar style stay there whatever the feedback channel");
            e2e.expect(settings.set(id, SharedSettings.SELL_RECEIPTS, AlertStyle.ACTIONBAR, Change.feature()) == SetResult.CHANGED,
                "receipts above the hotbar");
            e2e.console("eco set " + name + " 0");
            e2e.onPlayer(name, () -> {
                player.getInventory().setHeldItemSlot(0);
                player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, 1));
                return null;
            });
            bot.clearLogs();
            bot.command("sell hand");
            e2e.eventually(() -> e2e.money(name) > 0, "the diamond sold (has " + e2e.money(name) + ")");
            e2e.eventually(() -> bot.actionBarContains("+$"), "the total above the hotbar: " + bot.actionBar());
            e2e.sleep(300);
            e2e.expect(bot.chat().stream().noneMatch(line -> line.contains("+$") || line.contains("You sold")),
                "no receipt in chat: " + bot.chat());

            e2e.step("quiet in combat turns the hotbar receipt into a chat line (on a server that allows selling in combat)");
            settings.set(id, SharedSettings.QUIET_IN_COMBAT, true, Change.feature());
            withFile(e2e, "features/sell.yml", Map.of("block-in-combat: true", "block-in-combat: false"), x -> {
                e2e.console("combat tag " + name + " 30s");
                e2e.eventually(() -> e2e.services().messenger().quietNow(id), "quiet in combat applies");
                e2e.onPlayer(name, () -> {
                    player.getInventory().setItemInMainHand(ItemStack.of(Material.DIAMOND, 1));
                    return null;
                });
                long before = e2e.money(name);
                bot.clearLogs();
                bot.command("sell hand");
                e2e.eventually(() -> e2e.money(name) > before, "sold in combat");
                e2e.eventually(() -> bot.chatContains("+$"), "the total as a chat line");
                e2e.expect(bot.actionBar().stream().noneMatch(line -> line.contains("+$")), "nothing pops up: " + bot.actionBar());
            });
        } finally {
            e2e.console("combat untag " + name);
        }
    }

    // ------------------------------------------------------------------ across restarts

    /**
     * Settings of all three kinds survive a restart. Uses the same name on every run: when an earlier run (possibly
     * before a restart) stored the values, the player must join with them; otherwise they are saved now through the
     * dialog. Run it, restart the server, run it again.
     */
    static void persist(E2E e2e) throws Exception {
        String name = "SetKeeper";
        UUID known = e2e.services().directory().uuid(name).orElse(null);
        String stored = known == null ? null : stored(e2e, known, "sound-volume");
        Bot bot = e2e.bot(name);
        if ("30".equals(stored)) {
            e2e.step("stored in an earlier run: the values are back");
            bot.command("settings sound");
            Bot.SeenDialog sound = page(e2e, bot, "Sounds settings");
            e2e.expect(sound.range("sound_volume").initial() == 30f && Boolean.FALSE.equals(sound.toggleValue("sound_clicks")),
                "the slider and the switch as stored: " + sound.range("sound_volume") + " " + sound.initials());
            bot.command("settings display");
            e2e.expect("both".equals(page(e2e, bot, "Display settings").choiceValue("feedback_channel")), "the choice as stored");
            e2e.log("the settings stored earlier came back");
        } else {
            e2e.step("first run: store a slider, a switch and a choice for the next run");
            bot.command("settings sound");
            Map<String, Object> values = page(e2e, bot, "Sounds settings").values();
            values.put("sound_volume", 30f);
            values.put("sound_clicks", false);
            e2e.click(bot, "Save", values);
            bot.command("settings display");
            Map<String, Object> display = page(e2e, bot, "Display settings").values();
            display.put("feedback_channel", "both");
            e2e.click(bot, "Save", display);
            UUID id = e2e.uuid(name);
            expectStored(e2e, id, "sound-volume", "30");
            expectStored(e2e, id, "sound-clicks", "false");
            expectStored(e2e, id, "feedback-channel", "both");
        }
    }

    // ------------------------------------------------------------------ SettingChangeEvent

    /** Listeners hear every change before it is stored and may cancel a player's own change. */
    public static final class Recorder implements Listener {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final List<String> committed = new CopyOnWriteArrayList<>();

        @EventHandler
        public void cancel(SettingChangeEvent event) {
            this.seen.add(event.setting() + ":" + event.oldValue() + "->" + event.newValue() + ":" + event.cause() + ":" + event.category());
            if (event.setting().equals("sound-success")) {
                event.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void monitor(SettingChangeEvent event) {
            if (!event.isCancelled() || !event.cancellable()) {
                this.committed.add(event.setting() + "=" + event.newValue());
            }
        }
    }

    static void event(E2E e2e) throws Exception {
        String name = e2e.name("Evented");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Recorder recorder = new Recorder();
        Bukkit.getPluginManager().registerEvents(recorder, harness());
        try {
            e2e.step("a cancelled change is reported in the dialog and nothing is stored");
            bot.command("settings sound");
            Map<String, Object> values = page(e2e, bot, "Sounds settings").values();
            values.put("sound_success", false);
            values.put("sound_clicks", false);
            bot.clearMessages();
            e2e.click(bot, "Save", values);
            expectSaw(e2e, bot, "Success chimes couldn't be changed");
            e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "still on");
            expectStored(e2e, id, "sound-success", null);
            expectStored(e2e, id, "sound-clicks", "false");
            e2e.expect(recorder.seen.contains("sound-success:true->false:DIALOG:sound"), "the listener saw it: " + recorder.seen);
            e2e.expect(recorder.committed.contains("sound-clicks=false") && !recorder.committed.contains("sound-success=false"),
                "MONITOR sees only what was stored: " + recorder.committed);

            e2e.step("a feature's change is only reported: cancelling it changes nothing");
            settings(e2e).set(id, SharedSettings.SOUND_SUCCESS, false, Change.feature());
            e2e.expect(!settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "stored anyway");
            e2e.expect(recorder.seen.contains("sound-success:true->false:FEATURE:sound"), "but reported: " + recorder.seen);
            settings(e2e).set(id, SharedSettings.SOUND_SUCCESS, true, Change.feature());
            settings(e2e).set(id, SharedSettings.SOUND_CLICKS, true, Change.feature());
            int before = recorder.seen.size();
            settings(e2e).set(id, SharedSettings.SOUND_CLICKS, true, Change.feature());
            e2e.expect(recorder.seen.size() == before, "no event without a change");
        } finally {
            HandlerList.unregisterAll(recorder);
        }
        e2e.log("changes seen: " + new LinkedHashMap<>(Map.of("seen", recorder.seen, "committed", recorder.committed)));
    }
}
