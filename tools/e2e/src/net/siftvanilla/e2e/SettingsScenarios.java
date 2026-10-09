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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.UUIDUtil;
import net.siftvanilla.siftcore.api.SettingsView;
import net.siftvanilla.siftcore.api.SiftCoreApi;
import net.siftvanilla.siftcore.api.event.SettingChangeEvent;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.audit.AuditLog;
import net.siftvanilla.siftcore.core.link.SpawnArea;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingCategory;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.PingSound;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Feedback;
import net.siftvanilla.siftcore.core.text.StatusBars;
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
 * End-to-end scenarios of the settings framework and dialog: the group list with its counts, pages mixing all three
 * kinds of input (a switch, a choice and a slider) saving and persisting across a rejoin, paging with changes carried
 * between pages, resets, search, the changed-settings summary, the {@code /settings} words, the staff tools
 * {@code /sift settings}, placeholders, the public {@link SettingsView}, server defaults, locks, hidden settings,
 * compact pages and group overrides from {@code features/settings.yml}, old stored values, {@code SettingChangeEvent},
 * and core's delivery settings (the feedback channel, sounds, quiet in combat, sale receipts and status bars).
 * <p>
 * The scenarios use the settings core shares with every feature (the Sounds, Display and Combat groups), so they don't
 * depend on where a feature put its own settings. The Sounds page is the one with all three kinds: its ping sound
 * choices are offered once a feature plays them, so the scenarios declare the mention sound as read (chat does that
 * itself).
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
        list.add(of("settings-paging", SettingsScenarios::paging));
        list.add(of("settings-reset", SettingsScenarios::reset));
        list.add(of("settings-search", SettingsScenarios::search));
        list.add(of("settings-commands", SettingsScenarios::commands));
        list.add(of("settings-admin", SettingsScenarios::admin));
        list.add(of("settings-config", SettingsScenarios::config));
        list.add(of("settings-event", SettingsScenarios::event));
        list.add(of("settings-migration", SettingsScenarios::migration));
        list.add(of("settings-api", SettingsScenarios::api));
        list.add(of("settings-delivery", SettingsScenarios::delivery));
        list.add(of("settings-persist", SettingsScenarios::persist));
        list.add(of("settings-routing", SettingsScenarios::routing));
        list.add(of("settings-unoffered", SettingsScenarios::unoffered));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    private static Plugin harness() {
        return Bukkit.getPluginManager().getPlugin("SiftE2E");
    }

    private static PlayerSettings settings(E2E e2e) {
        return e2e.services().settings();
    }

    /** The Sounds page shows all three kinds once a feature plays the mention sound (chat does; declared here too). */
    private static void offerAllKinds(E2E e2e) {
        settings(e2e).reads(SharedSettings.SOUND_MENTION);
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

    /** Runs a command and waits for the new dialog it opens, titled exactly {@code title} (not the one open before). */
    private static Bot.SeenDialog open(E2E e2e, Bot bot, String command, String title) {
        Bot.SeenDialog before = bot.dialog();
        bot.command(command);
        e2e.eventually(() -> bot.dialog() != null && bot.dialog() != before && bot.dialog().title().equals(title), bot.name + " sees '"
            + title + "' after /" + command + ": " + (bot.dialog() == null ? "none" : bot.dialog().title()));
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

    /** Inserts a row as an older version (or a hand edit) would have left it. */
    private static void insert(E2E e2e, UUID player, String setting, String value) throws Exception {
        e2e.services().database().write(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO settings (uuid, setting, value) VALUES (?, ?, ?)")) {
                ps.setString(1, player.toString());
                ps.setString(2, setting);
                ps.setString(3, value);
                ps.executeUpdate();
            }
            return null;
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

    private static String placeholder(E2E e2e, String name, String placeholder) {
        return e2e.services().placeholders().resolve(e2e.player(name), placeholder);
    }

    /**
     * Changes inputs of a paged page wherever they are: walks the pages from the one open now with Next page (changes
     * carried along as pending), sets each key on the page that shows it, and saves on the page where the last one was
     * found.
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
     * {@code defaults}, {@code locked} and {@code categories} may be missing).
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

    /** A group as a player sees it: its label, description, and the settings in it they see. */
    private record Group(SettingCategory category, String label, String description, List<Registry.Entry<?>> entries) {
    }

    /** The groups a player sees (built-in order), from the registry. */
    private static List<Group> visibleGroups(E2E e2e, String player) {
        Player online = e2e.player(player);
        PlayerSettings settings = settings(e2e);
        List<Group> groups = new ArrayList<>();
        for (SettingCategory category : settings.registry().categories()) {
            List<Registry.Entry<?>> entries = new ArrayList<>();
            for (Registry.Entry<?> entry : settings.registry().in(category.id())) {
                if (settings.visible(entry, online::hasPermission)) {
                    entries.add(entry);
                }
            }
            if (!entries.isEmpty()) {
                groups.add(new Group(category, e2e.services().lang().plain(category.label()),
                    e2e.services().lang().plain(category.description()), entries));
            }
        }
        return groups;
    }

    /**
     * Runs a command as a console-like sender (every permission) and returns what it was told, one plain-text entry per
     * message. The list keeps filling: staff tools answer after a database read, so wait on it with
     * {@link E2E#eventually}.
     */
    private static List<String> capture(E2E e2e, String command) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(harness(), () -> {
            try {
                Bukkit.dispatchCommand(Bukkit.createCommandSender(message -> lines.add(
                    PlainTextComponentSerializer.plainText().serialize(message))), command);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("command '" + command + "' failed: " + e.getCause());
        }
        return lines;
    }

    /** Runs a staff command and waits until its answer holds every text in {@code expected}; returns the answer. */
    private static List<String> staffSays(E2E e2e, String command, String... expected) {
        List<String> lines = capture(e2e, command);
        e2e.eventually(() -> {
            String all = String.join("\n", lines);
            for (String text : expected) {
                if (!all.contains(text)) {
                    return false;
                }
            }
            return true;
        }, "/" + command + " says " + List.of(expected) + ": " + lines);
        return lines;
    }

    /** Every input key of a paged page, walking its pages with Next page from page 1. */
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

    // ------------------------------------------------------------------ 1. the group list and saving

    /**
     * The group list (every group the player sees, in order, with descriptions, counts and icons; Search settings;
     * Changed settings once something changed), the menu and pause routes, staff-only settings, saving only what
     * changed, Back without saving, stale pages, and opening groups by command.
     */
    static void dialog(E2E e2e) throws Exception {
        String name = e2e.name("Setter");
        String staffName = e2e.name("SetStaff");
        Bot bot = e2e.bot(name);
        Bot staff = e2e.bot(staffName);
        UUID id = e2e.uuid(name);
        PlayerSettings settings = settings(e2e);
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

            e2e.step("the group list: every group the player sees, in order, with its description and how many settings it holds");
            bot.clearLogs();
            Bot.SeenDialog list = open(e2e, bot, "settings", "Settings");
            List<Group> groups = visibleGroups(e2e, name);
            e2e.expect(groups.size() > 1, "players see several groups: " + groups.size());
            String body = list.bodyText();
            int last = -1;
            int total = 0;
            for (Group group : groups) {
                e2e.expect(list.button(group.label()) != null, "a " + group.label() + " button: " + list.buttons());
                String line = group.label() + ": " + group.description() + " (" + group.entries().size() + ")";
                int at = body.indexOf(line);
                e2e.expect(at > last, "'" + line + "' after the group before it: " + list.body());
                last = at;
                total += group.entries().size();
            }
            e2e.expect(body.contains("You changed 0 of " + total + " settings."), "the changed count: " + list.body());
            e2e.expect(body.lines().filter(l -> l.endsWith(")") && l.contains(": ")).allMatch(l -> l.startsWith("[")),
                "group lines start with their icon sprite: " + list.body());
            e2e.expect(list.button("Search settings") != null, "a search button: " + list.buttons());
            e2e.expect(list.button("Changed settings") == null, "no summary before anything changed: " + list.buttons());
            e2e.expect(list.button("Close") != null, "a command opens it with Close: " + list.buttons());
            e2e.expect("none".equals(list.after()), "group buttons show the next page in place: " + list.after());

            e2e.step("staff see the settings their permissions offer, players don't");
            Player op = e2e.player(staffName);
            Player player = e2e.player(name);
            Registry.Entry<?> gated = null;
            for (Registry.Entry<?> entry : settings.registry().entries()) {
                if (entry.setting().permission() != null && settings.visible(entry, op::hasPermission)
                    && !settings.visible(entry, player::hasPermission)) {
                    gated = entry;
                    break;
                }
            }
            e2e.expect(gated != null, "a setting only staff see");
            String gatedGroup = e2e.services().lang().plain(gated.category().label());
            Bot.SeenDialog staffList = open(e2e, staff, "settings", "Settings");
            for (Group group : visibleGroups(e2e, staffName)) {
                e2e.expect(staffList.button(group.label()) != null, "staff see the " + group.label() + " group: " + staffList.buttons());
            }
            staff.command("settings " + gated.category().id());
            e2e.expect(keysAcrossPages(e2e, staff, gatedGroup + " settings").contains(gated.inputKey()), "staff get " + gated.id());
            String gatedId = gated.category().id();
            if (groups.stream().anyMatch(group -> group.category().id().equals(gatedId))) {
                bot.command("settings " + gatedId);
                e2e.expect(!keysAcrossPages(e2e, bot, gatedGroup + " settings").contains(gated.inputKey()), "players don't: " + gated.id());
            } else {
                bot.clearLogs();
                bot.command("settings " + gatedId);
                expectSaw(e2e, bot, "There is no settings group called " + gatedId);
            }

            e2e.step("a changed setting is counted and listed under Changed settings");
            settings.set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
            bot.clearLogs();
            Bot.SeenDialog counted = open(e2e, bot, "settings", "Settings");
            e2e.expect(counted.bodyText().contains("You changed 1 of " + total + " settings."), "one changed: " + counted.body());
            e2e.expect(counted.button("Changed settings (1)") != null, "the summary button: " + counted.buttons());
            e2e.click(bot, "Changed settings");
            Bot.SeenDialog summary = page(e2e, bot, "Changed settings");
            e2e.expect(summary.bodyText().contains("Sounds > SiftCore volume: 30% (default 100%)"), "the change: " + summary.body());
            e2e.click(bot, "Sounds (1)");
            page(e2e, bot, "Sounds settings");
            e2e.click(bot, "Back", bot.dialog().values());
            page(e2e, bot, "Changed settings");
            e2e.click(bot, "Back");
            settingsList(e2e, bot);
            settings.set(id, SharedSettings.SOUND_VOLUME, 100L, Change.feature());

            e2e.step("saving stores what changed, says so and goes back to the list");
            open(e2e, bot, "settings", "Settings");
            e2e.click(bot, "Sounds");
            Bot.SeenDialog sound = page(e2e, bot, "Sounds settings");
            e2e.expect(sound.bodyText().contains("Menu click sounds: Play a click when you use buttons in menus."), "descriptions: " + sound.body());
            Map<String, Object> values = sound.values();
            values.put("sound_clicks", false);
            bot.clearMessages();
            e2e.click(bot, "Save", values);
            expectSaw(e2e, bot, "Menu click sounds turned off");
            settingsList(e2e, bot);
            e2e.expect(!settings.get(id, SharedSettings.SOUND_CLICKS), "click sounds off");

            e2e.step("saving without changes says nothing changed; Back leaves without saving");
            bot.clearLogs();
            Bot.SeenDialog unchanged = open(e2e, bot, "settings sound", "Sounds settings");
            e2e.expect(Boolean.FALSE.equals(unchanged.toggleValue("sound_clicks")), "the page shows the stored value");
            e2e.click(bot, "Save", unchanged.values());
            expectSaw(e2e, bot, "Nothing changed");
            Map<String, Object> discarded = open(e2e, bot, "settings sound", "Sounds settings").values();
            discarded.put("sound_errors", false);
            e2e.click(bot, "Back", discarded);
            settingsList(e2e, bot);
            e2e.expect(settings.get(id, SharedSettings.SOUND_ERRORS), "Back saved nothing");

            e2e.step("a value changed elsewhere while the page was open is not overwritten");
            bot.clearLogs();
            Bot.SeenDialog open = open(e2e, bot, "settings sound", "Sounds settings");
            Map<String, Object> stale = open.values();
            e2e.expect(Boolean.FALSE.equals(stale.get("sound_clicks")), "the page shows click sounds off");
            settings.set(id, SharedSettings.SOUND_CLICKS, true, Change.feature());
            stale.put("sound_errors", false);
            e2e.click(bot, "Save", stale);
            expectSaw(e2e, bot, "Error sounds turned off");
            e2e.expect(settings.get(id, SharedSettings.SOUND_CLICKS), "the change made meanwhile survived the stale page");
            e2e.expect(!settings.get(id, SharedSettings.SOUND_ERRORS), "the changed switch was saved");

            e2e.step("/settings <group> opens a group, and an unknown group is refused");
            bot.clearLogs();
            bot.command("settings nosuchgroup");
            expectSaw(e2e, bot, "There is no settings group called nosuchgroup");
            open(e2e, bot, "settings SOUND", "Sounds settings");
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    // ------------------------------------------------------------------ 2-4. all three kinds, saving, forged values

    /**
     * A page with all three kinds (the Sounds page): what it shows, saving a choice, a number and a switch together
     * (their stored forms, the message, placeholders), a choice of the Display group, forged values refused by the
     * router, persisting across a rejoin, and storing the default deleting the row again.
     */
    static void kinds(E2E e2e) throws Exception {
        offerAllKinds(e2e);
        String name = e2e.name("Kinds");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("the Sounds page: a slider with its unit and range, switches and a choice");
        Bot.SeenDialog sound = open(e2e, bot, "settings sound", "Sounds settings");
        e2e.expect("range".equals(sound.inputs().get("sound_volume")) && "toggle".equals(sound.inputs().get("sound_notify"))
            && "choice".equals(sound.inputs().get("sound_mention")), "a slider, switches and a choice: " + sound.inputs());
        Bot.RangeSeen volume = sound.range("sound_volume");
        e2e.expect(volume.start() == 0f && volume.end() == 100f && Float.valueOf(10f).equals(volume.step()) && volume.initial() == 100f,
            "0 to 100 in steps of 10, starting at 100: " + volume);
        e2e.expect(volume.label().equals("SiftCore volume (%)"), "the unit in the label: " + volume.label());
        e2e.expect(Boolean.TRUE.equals(sound.toggleValue("sound_notify")), "pings on by default");
        e2e.expect(List.of("default", "bell", "pling", "chime", "off").equals(sound.options().get("sound_mention")),
            "the ping options: " + sound.options());
        e2e.expect(List.of("Default", "Bell", "Pling", "Chime", "Off").equals(sound.optionLabels().get("sound_mention")),
            "their shared names: " + sound.optionLabels());
        e2e.expect("default".equals(sound.choiceValue("sound_mention")), "the choice starts on the default");
        e2e.expect(sound.bodyText().contains("SiftCore volume: How loud menu clicks"), "descriptions: " + sound.body());

        e2e.step("saving a choice, a number and a switch stores their stored forms");
        Map<String, Object> values = sound.values();
        values.put("sound_volume", 30f);
        values.put("sound_notify", false);
        values.put("sound_mention", "bell");
        bot.clearMessages();
        e2e.click(bot, "Save", values);
        expectSaw(e2e, bot, "Saved 3 settings");
        e2e.eventually(() -> bot.actionBarContains("Saved 3 settings"), "above the hotbar: " + bot.actionBar());
        settingsList(e2e, bot);
        expectStored(e2e, id, "sound-volume", "30");
        expectStored(e2e, id, "sound-notify", "false");
        expectStored(e2e, id, "sound-mention", "bell");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_MENTION) == PingSound.BELL, "the bell now");

        e2e.step("placeholders show the values");
        e2e.expect("30".equals(placeholder(e2e, name, "setting_sound-volume")), "setting_: " + placeholder(e2e, name, "setting_sound-volume"));
        e2e.expect("30%".equals(placeholder(e2e, name, "settingtext_sound-volume")), "settingtext_: "
            + placeholder(e2e, name, "settingtext_sound-volume"));
        e2e.expect("bell".equals(placeholder(e2e, name, "setting_sound-mention")) && "Bell".equals(placeholder(e2e, name,
            "settingtext_sound-mention")), "a choice");
        e2e.expect("false".equals(placeholder(e2e, name, "setting_sound-notify")) && "off".equals(placeholder(e2e, name,
            "settingtext_sound-notify")), "a switch");
        e2e.expect("3".equals(placeholder(e2e, name, "settings_changed")), "three changed: " + placeholder(e2e, name, "settings_changed"));
        e2e.expect("".equals(placeholder(e2e, name, "setting_seen-privacy")), "privacy settings stay private");
        e2e.expect(placeholder(e2e, name, "setting_no-such-setting") == null, "unknown ids are left to PlaceholderAPI");

        e2e.step("the Display page: a choice with the shared option names");
        Bot.SeenDialog display = open(e2e, bot, "settings display", "Display settings");
        e2e.expect("choice".equals(display.inputs().get("feedback_channel")), "the feedback channel: " + display.inputs());
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
        Bot.SeenDialog again = open(e2e, bot, "settings sound", "Sounds settings");
        Map<String, Object> forged = again.values();
        forged.put("sound_volume", 45f);
        e2e.click(bot, "Save", forged);
        Bot.SeenDialog refused = page(e2e, bot, "Sounds settings");
        e2e.expect(refused.bodyText().contains("Check SiftCore volume (%) and try again"), "an off-step slider value: " + refused.body());
        forged = refused.values();
        forged.put("sound_volume", 110f);
        e2e.click(bot, "Save", forged);
        Bot.SeenDialog outOfRange = page(e2e, bot, "Sounds settings");
        e2e.expect(outOfRange.bodyText().contains("Check SiftCore volume"), "out of range too");
        forged = outOfRange.values();
        forged.put("sound_mention", "trumpet");
        e2e.click(bot, "Save", forged);
        e2e.expect(page(e2e, bot, "Sounds settings").bodyText().contains("Check Mention sound and try again"), "an option the page did not offer");
        Bot.SeenDialog displayForged = open(e2e, bot, "settings display", "Display settings");
        Map<String, Object> badChoice = displayForged.values();
        badChoice.put("feedback_channel", "title");
        e2e.click(bot, "Save", badChoice);
        e2e.expect(page(e2e, bot, "Display settings").bodyText().contains("Check Quick results and errors and try again"),
            "a valid option of another setting that this one does not offer");
        expectStored(e2e, id, "sound-volume", "30");
        expectStored(e2e, id, "sound-mention", "bell");
        expectStored(e2e, id, "feedback-channel", "chat");

        e2e.step("everything persists across a rejoin");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        e2e.expect(!settings(e2e).loaded(id), "forgotten on quit");
        Bot back = e2e.bot(name);
        e2e.expect(e2e.services().directory().previousSeen(id) > 0, "the last visit is remembered for join summaries");
        Bot.SeenDialog soundAfter = open(e2e, back, "settings sound", "Sounds settings");
        e2e.expect(soundAfter.range("sound_volume").initial() == 30f && Boolean.FALSE.equals(soundAfter.toggleValue("sound_notify"))
            && "bell".equals(soundAfter.choiceValue("sound_mention")), "the slider, switch and choice as saved: "
            + soundAfter.range("sound_volume") + " " + soundAfter.initials() + " " + soundAfter.choiceInitial());
        Bot.SeenDialog displayAfter = open(e2e, back, "settings display", "Display settings");
        e2e.expect("chat".equals(displayAfter.choiceValue("feedback_channel")), "the display choice as saved");

        e2e.step("storing the default deletes the row, so the player follows the default again");
        Map<String, Object> reset = displayAfter.values();
        reset.put("feedback_channel", "actionbar");
        back.clearMessages();
        e2e.click(back, "Save", reset);
        expectSaw(e2e, back, "Quick results and errors set to Above the hotbar");
        expectStored(e2e, id, "feedback-channel", null);
        Map<String, Object> soundReset = open(e2e, back, "settings sound", "Sounds settings").values();
        soundReset.put("sound_volume", 100f);
        soundReset.put("sound_notify", true);
        soundReset.put("sound_mention", "default");
        back.clearMessages();
        e2e.click(back, "Save", soundReset);
        expectSaw(e2e, back, "Saved 3 settings");
        expectStored(e2e, id, "sound-volume", null);
        expectStored(e2e, id, "sound-notify", null);
        expectStored(e2e, id, "sound-mention", null);
        e2e.expect(!settings(e2e).changed(id, SharedSettings.SOUND_VOLUME), "nothing counts as changed");
        e2e.expect("0".equals(placeholder(e2e, name, "settings_changed")), "the count is back to 0");
    }

    // ------------------------------------------------------------------ 5. paging with mixed kinds

    /** With two settings per page, a slider and a choice changed on different pages are carried along and saved together. */
    static void paging(E2E e2e) throws Exception {
        offerAllKinds(e2e);
        String name = e2e.name("Pager");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        withSettingsKeys(e2e, Map.of("page-size", "page-size: 2"), x -> {
            Player player = e2e.player(name);
            int count = (int) settings(e2e).registry().in("sound").stream().filter(e -> settings(e2e).visible(e, player::hasPermission)).count();
            int pages = (count + 1) / 2;
            e2e.expect(pages >= 3, "Sounds fills at least three pages of two: " + count);

            e2e.step("page 1 holds the slider; the change is carried to page 2");
            bot.clearLogs();
            Bot.SeenDialog page1 = open(e2e, bot, "settings sound", "Sounds settings");
            e2e.expect(page1.inputs().size() == 2 && page1.bodyText().contains("Page 1 of " + pages), "page 1 of " + pages + ": "
                + page1.inputs() + " " + page1.body());
            e2e.expect(page1.button("Next page") != null && page1.button("Previous page") == null, "only Next: " + page1.buttons());
            e2e.expect(page1.inputs().containsKey("sound_volume"), "the slider comes first: " + page1.inputs());
            Map<String, Object> values1 = page1.values();
            values1.put("sound_volume", 20f);
            e2e.click(bot, "Next page", values1);

            e2e.step("page 2 holds the choice and knows about the change on page 1");
            Bot.SeenDialog page2 = page(e2e, bot, "Sounds settings");
            e2e.expect(page2.bodyText().contains("Page 2 of " + pages) && page2.bodyText().contains("Changes on other pages: 1"),
                "page 2 knows about the change on page 1: " + page2.body());
            e2e.expect(page2.inputs().containsKey("sound_mention"), "the ping choice on page 2: " + page2.inputs());
            Map<String, Object> values2 = page2.values();
            values2.put("sound_mention", "chime");
            e2e.click(bot, "Previous page", values2);

            e2e.step("back on page 1 the unsaved slider value shows, and saving there stores both");
            Bot.SeenDialog back1 = page(e2e, bot, "Sounds settings");
            e2e.expect(back1.range("sound_volume").initial() == 20f && back1.bodyText().contains("Changes on other pages: 1"),
                "page 1 shows the pending value: " + back1.range("sound_volume") + " " + back1.body());
            e2e.click(bot, "Next page", back1.values());
            Bot.SeenDialog again2 = page(e2e, bot, "Sounds settings");
            e2e.expect("chime".equals(again2.choiceValue("sound_mention")), "page 2 shows the pending choice: " + again2.choiceInitial());
            e2e.click(bot, "Previous page", again2.values());
            Bot.SeenDialog save1 = page(e2e, bot, "Sounds settings");
            bot.clearMessages();
            e2e.click(bot, "Save", save1.values());
            expectSaw(e2e, bot, "Saved 2 settings");
            expectStored(e2e, id, "sound-volume", "20");
            expectStored(e2e, id, "sound-mention", "chime");
        });
    }

    // ------------------------------------------------------------------ 6. resets

    /** Reset this category (confirm, cancel keeps unsaved changes), and Reset everything from the summary. */
    static void reset(E2E e2e) throws Exception {
        String name = e2e.name("Resetter");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        PlayerSettings settings = settings(e2e);

        e2e.step("no reset button while nothing in the group changed");
        e2e.expect(open(e2e, bot, "settings sound", "Sounds settings").button("Reset this category") == null, "no reset: " + bot.dialog().buttons());

        e2e.step("Reset this category asks first, listing what goes back; Cancel keeps unsaved changes");
        settings.set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        settings.set(id, SharedSettings.SOUND_SUCCESS, false, Change.feature());
        settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.BOTH, Change.feature());
        Bot.SeenDialog sound = open(e2e, bot, "settings sound", "Sounds settings");
        e2e.expect(sound.button("Reset this category") != null, "a reset button: " + sound.buttons());
        Map<String, Object> unsaved = sound.values();
        unsaved.put("sound_errors", false);
        e2e.click(bot, "Reset this category", unsaved);
        Bot.SeenDialog confirm = page(e2e, bot, "Reset Sounds settings?");
        e2e.expect(confirm.bodyText().contains("SiftCore volume: 30% to 100%") && confirm.bodyText().contains("Success chimes: off to on"),
            "what goes back: " + confirm.body());
        e2e.click(bot, "Cancel");
        Bot.SeenDialog kept = page(e2e, bot, "Sounds settings");
        e2e.expect(Boolean.FALSE.equals(kept.toggleValue("sound_errors")), "the unsaved change is still there: " + kept.initials());
        e2e.expect(settings.get(id, SharedSettings.SOUND_VOLUME) == 30L, "nothing was reset");

        e2e.step("Reset puts the group back to the defaults, deletes the rows and drops the unsaved changes");
        bot.clearMessages();
        e2e.click(bot, "Reset this category", kept.values());
        page(e2e, bot, "Reset Sounds settings?");
        e2e.click(bot, "Reset");
        Bot.SeenDialog after = page(e2e, bot, "Sounds settings");
        expectSaw(e2e, bot, "Reset 2 settings to their defaults");
        e2e.expect(after.range("sound_volume").initial() == 100f && Boolean.TRUE.equals(after.toggleValue("sound_success")),
            "the page shows the defaults: " + after.range("sound_volume") + " " + after.initials());
        e2e.expect(Boolean.TRUE.equals(after.toggleValue("sound_errors")), "the unsaved 'off' is gone after the reset: " + after.initials());
        e2e.expect(!after.bodyText().contains("Changes on other pages"), "nothing pending: " + after.body());
        bot.clearMessages();
        e2e.click(bot, "Save", after.values());
        expectSaw(e2e, bot, "Nothing changed");
        expectStored(e2e, id, "sound-errors", null);
        e2e.expect(after.button("Reset this category") == null, "nothing left to reset: " + after.buttons());
        expectStored(e2e, id, "sound-volume", null);
        expectStored(e2e, id, "sound-success", null);
        expectStored(e2e, id, "feedback-channel", "both");

        e2e.step("the counts follow");
        Bot.SeenDialog list = open(e2e, bot, "settings", "Settings");
        e2e.expect(list.bodyText().contains("You changed 1 of") && list.button("Changed settings (1)") != null, "one left: " + list.body());

        e2e.step("Reset everything from the summary");
        settings.set(id, SharedSettings.SOUND_CLICKS, false, Change.feature());
        Bot.SeenDialog summary = open(e2e, bot, "settings changed", "Changed settings");
        e2e.expect(summary.bodyText().contains("Display > Quick results and errors: Both (default Above the hotbar)")
            && summary.bodyText().contains("Sounds > Menu click sounds: off (default on)"), "both changes: " + summary.body());
        e2e.expect(summary.button("Display (1)") != null && summary.button("Sounds (1)") != null, "a button per group: " + summary.buttons());
        bot.clearMessages();
        e2e.click(bot, "Reset everything");
        Bot.SeenDialog everything = page(e2e, bot, "Reset every setting?");
        e2e.expect(everything.bodyText().contains("Quick results and errors: Both to Above the hotbar"), "listed: " + everything.body());
        e2e.click(bot, "Reset");
        settingsList(e2e, bot);
        expectSaw(e2e, bot, "Reset 2 settings to their defaults");
        expectStored(e2e, id, "feedback-channel", null);
        expectStored(e2e, id, "sound-clicks", null);
        e2e.expect(settingsList(e2e, bot).button("Changed settings") == null, "nothing changed now: " + bot.dialog().buttons());

        e2e.step("/settings reset <group> opens the confirmation, or says there is nothing to reset");
        bot.clearLogs();
        bot.command("settings reset sound");
        expectSaw(e2e, bot, "You use the defaults for every Sounds setting");
        settings.set(id, SharedSettings.SOUND_VOLUME, 40L, Change.feature());
        open(e2e, bot, "settings reset sound", "Reset Sounds settings?");
        e2e.click(bot, "Reset");
        page(e2e, bot, "Sounds settings");
        expectStored(e2e, id, "sound-volume", null);
        bot.command("settings reset all");
        expectSaw(e2e, bot, "You use the defaults for every setting");
    }

    // ------------------------------------------------------------------ 7. search

    /** Search by label word and option label, results pages save, no results, an empty query, /settings search. */
    static void search(E2E e2e) throws Exception {
        String name = e2e.name("Seeker");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("Search settings opens a form that waits for its results");
        open(e2e, bot, "settings", "Settings");
        e2e.click(bot, "Search settings");
        Bot.SeenDialog form = e2e.dialog(bot, "Search settings");
        e2e.expect("text".equals(form.inputs().get("query")), "a text field: " + form.inputs());
        e2e.expect("wait_for_response".equals(form.after()), "Search shows the waiting screen: " + form.after());

        e2e.step("an empty query is refused in red on the form");
        Map<String, Object> empty = form.values();
        empty.put("query", "   ");
        e2e.click(bot, "Search", empty);
        Bot.SeenDialog refused = e2e.dialog(bot, "Search settings");
        e2e.expect(refused.bodyText().contains("Type a word to search for"), "the error: " + refused.body());

        e2e.step("a label word finds the setting; its result page saves");
        Map<String, Object> query = refused.values();
        query.put("query", "volume");
        e2e.click(bot, "Search", query);
        Bot.SeenDialog results = page(e2e, bot, "Search: volume");
        e2e.expect(results.inputs().containsKey("sound_volume"), "the volume slider: " + results.inputs());
        e2e.expect(results.bodyText().contains("Sounds > SiftCore volume: How loud"), "lines name the group: " + results.body());
        Map<String, Object> values = results.values();
        values.put("sound_volume", 60f);
        bot.clearMessages();
        e2e.click(bot, "Save", values);
        expectSaw(e2e, bot, "SiftCore volume set to 60%");
        Bot.SeenDialog backToForm = e2e.dialog(bot, "Search settings");
        e2e.expect("volume".equals(backToForm.initial("query")), "back on the form with the query: " + backToForm.initial());
        expectStored(e2e, id, "sound-volume", "60");

        e2e.step("an option label finds the settings that offer it");
        Map<String, Object> option = backToForm.values();
        option.put("query", "above the hotbar");
        e2e.click(bot, "Search", option);
        Bot.SeenDialog byOption = page(e2e, bot, "Search: above the hotbar");
        e2e.expect(byOption.inputs().containsKey("feedback_channel"), "the feedback channel: " + byOption.inputs());

        e2e.step("no match: a notice, and Back returns to the form");
        e2e.click(bot, "Back", byOption.values());
        Bot.SeenDialog form2 = e2e.dialog(bot, "Search settings");
        Map<String, Object> nothing = form2.values();
        nothing.put("query", "zzzqqq");
        e2e.click(bot, "Search", nothing);
        Bot.SeenDialog none = e2e.dialog(bot, "Search settings");
        e2e.expect(none.bodyText().contains("No setting matches zzzqqq"), "the notice: " + none.body());
        e2e.click(bot, "Back");
        Bot.SeenDialog form3 = e2e.dialog(bot, "Search settings");
        e2e.expect("zzzqqq".equals(form3.initial("query")), "the form keeps the query: " + form3.initial());
        e2e.click(bot, "Back", form3.values());
        settingsList(e2e, bot);

        e2e.step("/settings search opens the results directly");
        Bot.SeenDialog direct = open(e2e, bot, "settings search error sounds", "Search: error sounds");
        e2e.expect(direct.inputs().containsKey("sound_errors"), "error sounds: " + direct.inputs());
        e2e.expect(direct.bodyText().indexOf("Error sounds") < direct.bodyText().indexOf("Menu click sounds")
            || !direct.bodyText().contains("Menu click sounds"), "the label match first: " + direct.body());
    }

    // ------------------------------------------------------------------ 8. /settings words

    /**
     * Sends a chat command after a pause, forgetting earlier messages: the server kicks clients that send commands
     * faster than a person could type them.
     */
    private static void say(E2E e2e, Bot bot, String command) {
        e2e.sleep(1_100);
        bot.clearMessages();
        bot.command(command);
    }

    /**
     * {@code /settings}: changing a number, a choice (by id and by label) and a switch, the info line that opens the
     * page, shorthand without the group, values it doesn't take, unknown names, settings players can't see, and the
     * summary and reset words.
     */
    static void commands(E2E e2e) throws Exception {
        String name = e2e.name("Commander");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("/settings <group> <setting> <value> changes a number, confirmed above the hotbar");
        say(e2e, bot, "settings sound volume 30");
        e2e.eventually(() -> bot.actionBarContains("SiftCore volume set to 30%"), "the confirmation: " + bot.actionBar());
        expectStored(e2e, id, "sound-volume", "30");
        say(e2e, bot, "settings sound volume 30");
        e2e.eventually(() -> bot.actionBarContains("SiftCore volume is already 30%"), "already so: " + bot.actionBar());

        e2e.step("/settings <group> <setting> shows the value, the default and the values; the line opens its page");
        say(e2e, bot, "settings sound volume");
        e2e.eventually(() -> bot.chatContains("SiftCore volume: 30% (default 100%). Values: a whole number from 0 to 100 in steps of 10"),
            "the info line: " + bot.chat());
        e2e.expect(bot.openChatDialog("SiftCore volume: 30%"), "the line opens a dialog: " + bot.chatDialogs());
        Bot.SeenDialog fromChat = page(e2e, bot, "Sounds settings");
        e2e.expect(fromChat.range("sound_volume").initial() == 30f, "the page that holds it: " + fromChat.range("sound_volume"));

        e2e.step("a choice by id, a choice by its label (shorthand without the group) and a switch flipped with toggle");
        say(e2e, bot, "settings display feedback-channel both");
        expectSaw(e2e, bot, "Quick results and errors set to Both");
        expectStored(e2e, id, "feedback-channel", "both");
        say(e2e, bot, "settings feedback-channel Above the hotbar");
        expectSaw(e2e, bot, "Quick results and errors set to Above the hotbar");
        expectStored(e2e, id, "feedback-channel", null);
        say(e2e, bot, "settings sound-notify toggle");
        e2e.eventually(() -> bot.actionBarContains("Notification pings turned off"), "flipped: " + bot.actionBar());
        e2e.expect(!settings(e2e).get(id, SharedSettings.SOUND_NOTIFY), "pings off");
        say(e2e, bot, "settings sound notify toggle");
        e2e.eventually(() -> settings(e2e).get(id, SharedSettings.SOUND_NOTIFY), "flipped back");
        say(e2e, bot, "settings volume 50");
        e2e.eventually(() -> bot.actionBarContains("SiftCore volume set to 50%"), "a short name only one setting has: " + bot.actionBar());

        e2e.step("values a setting doesn't take are refused, naming what it takes, in red");
        say(e2e, bot, "settings display feedback-channel title");
        expectSaw(e2e, bot, "For Quick results and errors, use actionbar, chat, both.");
        say(e2e, bot, "settings sound volume 150");
        expectSaw(e2e, bot, "For SiftCore volume, use a whole number from 0 to 100 in steps of 10.");
        say(e2e, bot, "settings sound volume 55");
        expectSaw(e2e, bot, "For SiftCore volume, use a whole number");
        say(e2e, bot, "settings sound notify maybe");
        expectSaw(e2e, bot, "For Notification pings, use on, off or toggle.");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_VOLUME) == 50L, "nothing changed meanwhile");

        e2e.step("unknown names, and settings players can't see, are unknown");
        say(e2e, bot, "settings sound nothing");
        expectSaw(e2e, bot, "There is no setting called nothing in Sounds.");
        say(e2e, bot, "settings nosuch on");
        expectSaw(e2e, bot, "There is no setting or settings group called nosuch.");
        say(e2e, bot, "settings nosuch volume 30");
        expectSaw(e2e, bot, "There is no setting or settings group called nosuch.");
        say(e2e, bot, "settings nosuch");
        expectSaw(e2e, bot, "There is no settings group called nosuch.");
        Registry.Entry<?> staffOnly = settings(e2e).registry().entries().stream()
            .filter(entry -> entry.setting().permission() != null && !e2e.player(name).hasPermission(entry.setting().permission()))
            .findFirst().orElse(null);
        if (staffOnly != null) {
            say(e2e, bot, "settings " + staffOnly.id() + " on");
            expectSaw(e2e, bot, "There is no setting or settings group called " + staffOnly.id() + ".");
            e2e.expect(!settings(e2e).changed(id, staffOnly.setting()), "a staff setting stays untouched");
        }

        e2e.step("the command's own words typed in capitals still do what they do");
        e2e.sleep(1_100);
        Bot.SeenDialog found = open(e2e, bot, "settings Search volume", "Search: volume");
        e2e.expect(found.inputs().containsKey("sound_volume"), "the search results: " + found.inputs());

        e2e.step("/settings changed and /settings reset open their screens");
        Bot.SeenDialog summary = open(e2e, bot, "settings changed", "Changed settings");
        e2e.expect(summary.bodyText().contains("Sounds > SiftCore volume: 50%"), "the change: " + summary.body());
        open(e2e, bot, "settings reset sound", "Reset Sounds settings?");
        say(e2e, bot, "settings reset display");
        expectSaw(e2e, bot, "You use the defaults for every Display setting");
        say(e2e, bot, "settings reset nosuch");
        expectSaw(e2e, bot, "There is no settings group called nosuch");
    }

    // ------------------------------------------------------------------ 9. /sift settings

    /** Records the players the unlisted e2e setting's change hook ran for (on their own thread). */
    private static final Queue<String> HOOKED = new ConcurrentLinkedQueue<>();

    /**
     * An unlisted setting that applies at once (a hook), registered once per server run: only staff tools and code can
     * reach it, so the dialog, searches and the group sizes don't change.
     */
    private static Toggle instantSetting(E2E e2e) {
        PlayerSettings settings = settings(e2e);
        if (settings.toggle("e2e-instant") == null) {
            settings.register(SettingCategories.DISPLAY, new Toggle("e2e-instant", true, SettingCategories.DISPLAY_LABEL,
                SettingCategories.DISPLAY_DESCRIPTION, null), SettingOptions.<Boolean>builder().listed(false)
                .onChange((player, before, now) -> HOOKED.add(player.getName() + ":" + now + ":" + Bukkit.isOwnedByCurrentRegion(player)))
                .build());
        }
        return settings.toggle("e2e-instant");
    }

    private static List<AuditLog.Entry> audit(E2E e2e, UUID player) throws Exception {
        return e2e.services().audit().recent("settings.", player.toString(), 20).get(10, TimeUnit.SECONDS);
    }

    /**
     * {@code /sift settings}: listing a player's changes, one setting's details, changing an online player's setting
     * (and its instant hook), an offline player's (read when they rejoin), resets, refusals, and the audit log.
     */
    static void admin(E2E e2e) throws Exception {
        String name = e2e.name("Managed");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        PlayerSettings settings = settings(e2e);
        Toggle instant = instantSetting(e2e);

        e2e.step("a player who changed nothing");
        staffSays(e2e, "sift settings " + name, name + " uses the defaults for every setting.");

        e2e.step("the list shows what they changed and other stored values");
        settings.set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        settings.setRaw(id, "e2e-sort", "price");
        staffSays(e2e, "sift settings " + name, "Settings of " + name + ": 1 changed", "- sound-volume: 30 (default 100)",
            "Other stored values: e2e-sort");

        e2e.step("one setting: value, default, where it comes from, group and values");
        staffSays(e2e, "sift settings " + name + " sound-volume", "sound-volume of " + name + ": 30 (default 100, from their choice)",
            "Group Sounds, a number: a whole number from 0 to 100 in steps of 10");
        staffSays(e2e, "sift settings " + name + " hide-from-leaderboards", "from the built-in default", "Needs siftcore.stats.hide: no");
        staffSays(e2e, "sift settings " + name + " nothing", "There is no setting or settings group called nothing.");

        e2e.step("changing an online player's setting: stored, audited, and an instant setting's hook runs on their thread");
        staffSays(e2e, "sift settings " + name + " sound-volume 70", "Set sound-volume of " + name + " to 70 (was 30).");
        e2e.expect(settings.get(id, SharedSettings.SOUND_VOLUME) == 70L, "70 now");
        expectStored(e2e, id, "sound-volume", "70");
        staffSays(e2e, "sift settings " + name + " sound-volume 70", "sound-volume of " + name + " already is 70.");
        staffSays(e2e, "sift settings " + name + " sound-volume 75", "For sound-volume, use a whole number from 0 to 100 in steps of 10.");
        HOOKED.clear();
        staffSays(e2e, "sift settings " + name + " e2e-instant off", "Set e2e-instant of " + name + " to false (was true).");
        e2e.eventually(() -> HOOKED.contains(name + ":false:true"), "the hook ran on the player's thread: " + HOOKED);
        e2e.expect(!settings.get(id, instant), "an unlisted setting is reachable by staff");
        staffSays(e2e, "sift settings " + name + " hide-from-leaderboards on", "Set hide-from-leaderboards",
            name + " doesn't have siftcore.stats.hide");
        e2e.eventually(() -> {
            try {
                return audit(e2e, id).stream().anyMatch(entry -> entry.action().equals("settings.set")
                    && "sound-volume: 30 -> 70".equals(entry.details()));
            } catch (Exception e) {
                return false;
            }
        }, "the audit row of the change");

        e2e.step("a reset puts settings back and is audited");
        staffSays(e2e, "sift settings " + name + " sound-volume reset", "Reset 1 settings of " + name + ".");
        e2e.expect(settings.get(id, SharedSettings.SOUND_VOLUME) == 100L, "back to 100");
        expectStored(e2e, id, "sound-volume", null);
        staffSays(e2e, "sift settings " + name + " reset display", "Reset 1 settings of " + name + ".");
        e2e.expect(settings.get(id, instant), "the display group's unlisted setting too");
        staffSays(e2e, "sift settings " + name + " reset all", "Reset 1 settings of " + name + ".");
        expectStored(e2e, id, "hide-from-leaderboards", null);
        e2e.expect("price".equals(settings.raw(id, "e2e-sort", null)), "remembered UI state stays");
        e2e.eventually(() -> {
            try {
                return audit(e2e, id).stream().anyMatch(entry -> entry.action().equals("settings.reset"));
            } catch (Exception e) {
                return false;
            }
        }, "the audit row of the reset");

        e2e.step("an offline player's setting is written to the table and read when they rejoin");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null && !settings.loaded(id), name + " left");
        staffSays(e2e, "sift settings " + name + " sound-volume 80", "Set sound-volume of " + name + " to 80 (was 100).");
        expectStored(e2e, id, "sound-volume", "80");
        staffSays(e2e, "sift settings " + name, "- sound-volume: 80 (default 100)");
        staffSays(e2e, "sift settings " + name + " sound-volume", "80 (default 100, from their choice)");
        Bot back = e2e.bot(name);
        e2e.expect(settings.get(id, SharedSettings.SOUND_VOLUME) == 80L, "read on rejoin: " + settings.get(id, SharedSettings.SOUND_VOLUME));
        e2e.expect(open(e2e, back, "settings sound", "Sounds settings").range("sound_volume").initial() == 80f, "the dialog shows it");

        e2e.step("locked settings are refused, also by a reset, and a group reset names the locked ones it leaves");
        e2e.expect(settings.set(id, SharedSettings.QUIET_IN_COMBAT, true, Change.feature()) == SetResult.CHANGED, "quiet in combat on");
        expectStored(e2e, id, "quiet-in-combat", "true");
        withSettingsKeys(e2e, Map.of("locked", "locked:\n  quiet-in-combat: true"), x -> {
            staffSays(e2e, "sift settings " + name + " quiet-in-combat off", "quiet-in-combat can't be changed: the server locked it.");
            staffSays(e2e, "sift settings " + name + " quiet-in-combat", "from the server's lock");
            staffSays(e2e, "sift settings " + name + " quiet-in-combat reset", "quiet-in-combat can't be changed: the server locked it.");
            staffSays(e2e, "sift settings " + name + " reset quiet-in-combat", "quiet-in-combat can't be changed: the server locked it.");
            staffSays(e2e, "sift settings " + name + " reset combat",
                "Reset 0 settings of " + name + "; 1 locked by the server stay as they are (quiet-in-combat).");
            expectStored(e2e, id, "quiet-in-combat", "true");
        });
        staffSays(e2e, "sift settings " + name + " reset combat", "Reset 1 settings of " + name + ".");
        expectStored(e2e, id, "quiet-in-combat", null);
    }

    // ------------------------------------------------------------------ 10. server defaults, locks, hidden settings, looks

    /**
     * features/settings.yml: a server default (and a player who stored it following it), a lock (text instead of an
     * input, refused by commands), a hidden setting (left out of pages and commands), compact pages and group overrides.
     */
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
            Bot.SeenDialog page = open(e2e, follow, "settings sound", "Sounds settings");
            e2e.expect(page.range("sound_volume").initial() == 60f, "the slider starts at the server default: " + page.range("sound_volume"));
            follow.clearLogs();
            follow.command("settings sound volume");
            e2e.eventually(() -> follow.chatContains("SiftCore volume: 60% (default 60%)"), "the server default is the default: " + follow.chat());

            e2e.step("a hidden setting is left out of the dialog and commands, reads the server's value and can't be changed");
            e2e.expect(!page.inputs().containsKey("sound_clicks"), "no click sound switch: " + page.inputs());
            e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "the keeper's stored 'off' does not apply while hidden");
            e2e.expect(settings(e2e).set(keepId, SharedSettings.SOUND_CLICKS, false, Change.api("SiftE2E")) == SetResult.NOT_ALLOWED,
                "refused by the registry");
            expectStored(e2e, keepId, "sound-clicks", "false");
            follow.clearLogs();
            follow.command("settings sound clicks off");
            expectSaw(e2e, follow, "There is no setting called clicks in Sounds.");
            e2e.expect("".equals(placeholder(e2e, follower, "setting_sound-clicks")), "no placeholder value");

            e2e.step("storing the server default deletes the row");
            Map<String, Object> values = open(e2e, keep, "settings sound", "Sounds settings").values();
            values.put("sound_volume", 60f);
            keep.clearMessages();
            e2e.click(keep, "Save", values);
            expectSaw(e2e, keep, "SiftCore volume set to 60%");
            expectStored(e2e, keepId, "sound-volume", null);

            e2e.step("a locked setting shows its value as text, and commands are refused");
            Bot.SeenDialog combat = open(e2e, follow, "settings combat", "Combat & stats settings");
            e2e.expect(!combat.inputs().containsKey("quiet_in_combat"), "no input for a locked setting: " + combat.inputs());
            e2e.expect(combat.bodyText().contains("Quiet during combat: on (set by the server)"), "the value as text: " + combat.body());
            e2e.expect(settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is the value");
            e2e.expect(settings(e2e).set(followId, SharedSettings.QUIET_IN_COMBAT, false, Change.api("SiftE2E")) == SetResult.LOCKED,
                "refused by the registry");
            follow.clearLogs();
            follow.command("settings combat quiet-in-combat off");
            expectSaw(e2e, follow, "Quiet during combat is set by the server.");
            follow.clearLogs();
            follow.command("settings quiet-in-combat");
            e2e.eventually(() -> follow.chatContains("Quiet during combat: on (set by the server)"), "the info line: " + follow.chat());
        });

        e2e.step("without the server default the player who stored it follows the new default");
        e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_VOLUME) == 100L, "back to 100%");
        e2e.expect(!settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is gone");
        e2e.expect(!settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "shown again: the keeper's stored choice is back");

        withSettingsKeys(e2e, Map.of(
            "show-descriptions", "show-descriptions: false",
            "categories", "categories:\n  sound:\n    order: 1\n  display:\n    icon: star"), x -> {
            e2e.step("compact pages leave the descriptions out; the inputs keep their labels");
            Bot.SeenDialog compact = open(e2e, follow, "settings sound", "Sounds settings");
            e2e.expect(!compact.bodyText().contains("How loud menu clicks"), "no descriptions: " + compact.body());
            e2e.expect(compact.range("sound_volume").label().equals("SiftCore volume (%)"), "the label stays: " + compact.range("sound_volume"));

            e2e.step("the server moves a group to the top and changes an icon");
            Bot.SeenDialog list = open(e2e, follow, "settings", "Settings");
            e2e.expect(list.buttons().getFirst().label().equals("Sounds"), "Sounds first: " + list.buttons());
            String body = list.bodyText();
            e2e.expect(body.indexOf("Sounds:") < body.indexOf("Chat:"), "and its line too: " + list.body());

            e2e.step("the API reports the groups the way the dialog shows them");
            List<SettingsView.CategoryInfo> groups = SiftCoreApi.get().settings().categories();
            e2e.expect(groups.getFirst().id().equals("sound") && groups.getFirst().order() == 1, "Sounds first, order 1: " + groups);
            e2e.expect(groups.stream().anyMatch(group -> group.id().equals("display") && "star".equals(group.icon())),
                "Display's icon is the server's: " + groups);
            e2e.expect(SiftCoreApi.get().settings().settings().getFirst().category().equals("sound"), "settings in the same order");
        });
    }

    // ------------------------------------------------------------------ 11. SettingChangeEvent

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
            Map<String, Object> values = open(e2e, bot, "settings sound", "Sounds settings").values();
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

            e2e.step("commands and the API are cancellable too");
            bot.clearLogs();
            bot.command("settings sound success off");
            expectSaw(e2e, bot, "Success chimes couldn't be changed");
            e2e.expect(recorder.seen.contains("sound-success:true->false:COMMAND:sound"), "a command change: " + recorder.seen);
            e2e.expect(SiftCoreApi.get().settings().set(id, "sound-success", "false", "SiftE2E") == SettingsView.Result.CANCELLED,
                "an API change");
            e2e.expect(recorder.seen.contains("sound-success:true->false:API:sound"), "with the API cause: " + recorder.seen);
            e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "still on");

            e2e.step("a feature's change is only reported: cancelling it changes nothing");
            settings(e2e).set(id, SharedSettings.SOUND_SUCCESS, false, Change.feature());
            e2e.expect(!settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "stored anyway");
            e2e.expect(recorder.seen.contains("sound-success:true->false:FEATURE:sound"), "but reported: " + recorder.seen);

            e2e.step("a reset is reported with the reset cause");
            open(e2e, bot, "settings reset sound", "Reset Sounds settings?");
            e2e.click(bot, "Reset");
            page(e2e, bot, "Sounds settings");
            e2e.eventually(() -> recorder.seen.contains("sound-success:false->true:RESET:sound")
                && recorder.seen.contains("sound-clicks:false->true:RESET:sound"), "reset changes: " + recorder.seen);
            e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "a reset can't be cancelled");
            int before = recorder.seen.size();
            settings(e2e).set(id, SharedSettings.SOUND_CLICKS, true, Change.feature());
            e2e.expect(recorder.seen.size() == before, "no event without a change");
        } finally {
            HandlerList.unregisterAll(recorder);
        }
        e2e.log("changes seen: " + new LinkedHashMap<>(Map.of("seen", recorder.seen, "committed", recorder.committed)));
    }

    // ------------------------------------------------------------------ 12. old and hand-edited rows

    /** Rows written by older versions or by hand: odd case and spaces, and a switch's old value for a setting that is now a choice. */
    static void migration(E2E e2e) throws Exception {
        String name = e2e.name("Legacy");
        UUID id = UUIDUtil.createOfflinePlayerUUID(name);
        insert(e2e, id, "feedback-channel", " CHAT ");
        insert(e2e, id, "sell_receipts", "false");
        insert(e2e, id, "sound-volume", "35");
        insert(e2e, id, "friends-requests", " KNOWN");

        e2e.step("the rows read as their values when the player joins");
        Bot bot = e2e.bot(name);
        e2e.expect(settings(e2e).get(id, SharedSettings.FEEDBACK_CHANNEL) == AlertStyle.CHAT, "' CHAT ' reads as chat");
        e2e.expect(settings(e2e).get(id, SharedSettings.SELL_RECEIPTS) == AlertStyle.ACTIONBAR, "the old 'false' of the receipts switch");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_VOLUME) == 40L, "an off-step number snaps to the nearest step");

        e2e.step("the dialog shows them");
        e2e.expect("chat".equals(open(e2e, bot, "settings display", "Display settings").choiceValue("feedback_channel")), "the choice");
        Bot.SeenDialog economy = open(e2e, bot, "settings economy", "Money & selling settings");
        e2e.expect("actionbar".equals(economy.choiceValue("sell_receipts")), "the receipts choice: " + economy.choiceInitial());
        e2e.expect(open(e2e, bot, "settings sound", "Sounds settings").range("sound_volume").initial() == 40f, "the slider");

        e2e.step("the next change writes the value in its stored form");
        Map<String, Object> values = open(e2e, bot, "settings economy", "Money & selling settings").values();
        values.put("sell_receipts", "off");
        e2e.click(bot, "Save", values);
        expectStored(e2e, id, "sell_receipts", "off");
        expectStored(e2e, id, "feedback-channel", " CHAT ");

        e2e.step("an old ' KNOWN' friend request privacy is honoured: a stranger's request is refused");
        String strangerName = e2e.name("Stranger");
        withFile(e2e, "features/friends.yml", Map.of("min-account-age: 10m", "min-account-age: 0s", "per-minute: 5", "per-minute: 1000"),
            x -> {
                Bot stranger = e2e.bot(strangerName);
                stranger.clearLogs();
                stranger.command("friend " + name);
                expectSaw(e2e, stranger, name + " isn't taking friend requests from you.");
            });
    }

    // ------------------------------------------------------------------ 4. an option the server stops offering

    /** Whether the e2e's gated choice is offered at all, and whether its "Both" option is (both off outside the scenario). */
    private static final AtomicBoolean GATE_OFFERED = new AtomicBoolean();
    private static final AtomicBoolean GATE_BOTH = new AtomicBoolean();

    /**
     * A choice of the Display group, registered once per server run, offered only while {@link #GATE_OFFERED} is set
     * (so other scenarios, the counts and the self-test never see it); its "Both" option is offered only while
     * {@link #GATE_BOTH} is set (optionAvailableWhen) and reads as "Chat" otherwise, like a friends-only option on a
     * server without friends.
     */
    @SuppressWarnings("unchecked")
    private static Choice<AlertStyle> gatedChoice(E2E e2e) {
        PlayerSettings settings = settings(e2e);
        if (settings.setting("e2e-gated") == null) {
            Choice<AlertStyle> choice = Choice.ofEnum("e2e-gated", AlertStyle.class, AlertStyle::id, AlertStyle.ACTIONBAR)
                .option(AlertStyle.ACTIONBAR, AlertStyle.ACTIONBAR.label())
                .option(AlertStyle.CHAT, AlertStyle.CHAT.label())
                .option(AlertStyle.BOTH, AlertStyle.BOTH.label(), null, AlertStyle.CHAT.id())
                .text(SettingCategories.DISPLAY_LABEL, SettingCategories.DISPLAY_DESCRIPTION).build();
            settings.register(SettingCategories.DISPLAY, choice, SettingOptions.<AlertStyle>builder().order(99)
                .availableWhen(GATE_OFFERED::get).optionAvailableWhen(AlertStyle.BOTH.id(), GATE_BOTH::get).placeholder(false).build());
            settings.reads(choice);
        }
        return (Choice<AlertStyle>) settings.setting("e2e-gated");
    }

    /** Walks a paged group from the page open now with Next page until one shows {@code key}. */
    private static Bot.SeenDialog pageWith(E2E e2e, Bot bot, String title, String key) {
        for (int guard = 0; guard < 30; guard++) {
            Bot.SeenDialog current = page(e2e, bot, title);
            if (current.inputs().containsKey(key)) {
                return current;
            }
            e2e.expect(current.button("Next page") != null, key + " on a later page of " + title + ": " + current.inputs().keySet());
            e2e.click(bot, "Next page", current.values());
        }
        throw new E2E.Failure("too many pages in " + title);
    }

    /**
     * An option a choice stops offering (optionAvailableWhen): left out of the page, a forged click picking it is
     * refused by the router and stores nothing, the command refuses it, and a player who picked it while it was
     * offered reads (and sees) its stand-in until it comes back.
     */
    static void unoffered(E2E e2e) throws Exception {
        Choice<AlertStyle> gated = gatedChoice(e2e);
        String name = e2e.name("Gated");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        PlayerSettings settings = settings(e2e);
        GATE_OFFERED.set(true);
        GATE_BOTH.set(false);
        try {
            e2e.step("the page leaves the option out");
            open(e2e, bot, "settings display", "Display settings");
            Bot.SeenDialog page = pageWith(e2e, bot, "Display settings", "e2e_gated");
            e2e.expect(List.of("actionbar", "chat").equals(page.options().get("e2e_gated")), "no Both: " + page.options());

            e2e.step("a forged click picking it is refused by the router and stores nothing");
            Map<String, Object> forged = page.values();
            forged.put("e2e_gated", "both");
            e2e.click(bot, "Save", forged);
            e2e.expect(page(e2e, bot, "Display settings").bodyText().contains("Check Display and try again"),
                "refused: " + bot.dialog().body());
            expectStored(e2e, id, "e2e-gated", null);
            e2e.expect(settings.get(id, gated) == AlertStyle.ACTIONBAR, "still the default");

            e2e.step("the command refuses it too, naming it");
            say(e2e, bot, "settings display e2e-gated both");
            expectSaw(e2e, bot, "You can't pick both for Display.");
            expectStored(e2e, id, "e2e-gated", null);

            e2e.step("a player who picked it while it was offered reads its stand-in until it comes back");
            GATE_BOTH.set(true);
            e2e.expect(settings.set(id, gated, AlertStyle.BOTH, Change.feature()) == SetResult.CHANGED, "Both picked while offered");
            expectStored(e2e, id, "e2e-gated", "both");
            GATE_BOTH.set(false);
            e2e.expect(settings.get(e2e.player(name), gated) == AlertStyle.CHAT, "read as Chat while Both is not offered");
            e2e.sleep(1_100);
            open(e2e, bot, "settings display", "Display settings");
            Bot.SeenDialog standIn = pageWith(e2e, bot, "Display settings", "e2e_gated");
            e2e.expect("chat".equals(standIn.choiceValue("e2e_gated")), "the page shows Chat: " + standIn.choiceInitial());
            expectStored(e2e, id, "e2e-gated", "both");
            GATE_BOTH.set(true);
            e2e.sleep(1_100);
            open(e2e, bot, "settings display", "Display settings");
            Bot.SeenDialog back = pageWith(e2e, bot, "Display settings", "e2e_gated");
            e2e.expect("both".equals(back.choiceValue("e2e_gated")) && back.options().get("e2e_gated").contains("both"),
                "offered again, the stored Both shows: " + back.choiceInitial() + " " + back.options());
        } finally {
            GATE_OFFERED.set(false);
            GATE_BOTH.set(false);
            settings.reset(id, List.of(gated), Change.reset("e2e"));
        }
    }

    // ------------------------------------------------------------------ the public API

    /** SettingsView through SiftCoreApi: groups and settings, a player's values, changes and resets, offline too. */
    static void api(E2E e2e) throws Exception {
        String name = e2e.name("ApiUser");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        SettingsView view = SiftCoreApi.get().settings();

        e2e.step("groups and settings in plain text");
        e2e.expect(view.categories().stream().anyMatch(c -> c.id().equals("sound") && c.label().equals("Sounds")), "the Sounds group");
        SettingsView.SettingInfo volume = view.setting("sound-volume").orElseThrow();
        e2e.expect(volume.type() == SettingsView.Type.NUMBER && volume.max() == 100 && volume.step() == 10 && "%".equals(volume.unit())
            && "100".equals(volume.defaultValue()), "the volume: " + volume);
        e2e.expect(view.settings().size() == settings(e2e).registry().byId().size(), "every setting");

        e2e.step("changes and resets");
        e2e.expect(view.set(id, "sound-volume", "20", "SiftE2E") == SettingsView.Result.CHANGED, "changed");
        e2e.expect("20".equals(view.value(id, "sound-volume")), "read back");
        e2e.expect(view.set(id, "sound-volume", "25") == SettingsView.Result.INVALID, "off step");
        e2e.expect(view.set(id, "nothing", "1") == SettingsView.Result.UNKNOWN, "unknown");
        e2e.expect(view.set(id, "feedback-channel", "Chat") == SettingsView.Result.CHANGED, "a choice by its label");
        e2e.expect(Map.of("sound-volume", "20", "feedback-channel", "chat").equals(view.stored(id).get(10, TimeUnit.SECONDS)),
            "stored values");
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            e2e.step("PlaceholderAPI shows the settings placeholders");
            Class<?> type = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
            java.lang.reflect.Method set = type.getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            String text = (String) set.invoke(null, e2e.player(name),
                "%siftcore_setting_sound-volume% %siftcore_settingtext_sound-volume% %siftcore_settings_changed% [%siftcore_setting_seen-privacy%]");
            e2e.expect("20 20% 2 []".equals(text), "PlaceholderAPI: " + text);
        } else {
            e2e.log("PlaceholderAPI is not installed; the placeholders were read through SiftCore only");
        }
        e2e.expect(view.reset(id, "sound-volume") == SettingsView.Result.CHANGED && "100".equals(view.value(id, "sound-volume")), "reset");
        expectStored(e2e, id, "sound-volume", null);

        e2e.step("offline players");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null && !settings(e2e).loaded(id), name + " left");
        e2e.expect(view.set(id, "sound-volume", "90") == SettingsView.Result.CHANGED, "written for an offline player");
        e2e.expect("90".equals(view.stored(id).get(10, TimeUnit.SECONDS).get("sound-volume")), "in the table");
        e2e.expect(view.set(id, "sound-volume", "80") == SettingsView.Result.CHANGED, "changed again while offline");
        e2e.bot(name);
        e2e.expect("80".equals(view.value(id, "sound-volume")), "read when they join");

        e2e.step("every change through the API is in the audit log, with what the player had before");
        List<String> expected = List.of("api:SiftE2E settings.set sound-volume: 100 -> 20", "api settings.set feedback-channel: actionbar -> chat",
            "api settings.reset sound-volume: 20 -> 100", "api settings.set sound-volume: 100 -> 90", "api settings.set sound-volume: 90 -> 80");
        e2e.eventually(() -> {
            try {
                List<String> rows = new ArrayList<>();
                for (AuditLog.Entry entry : audit(e2e, id).reversed()) {
                    rows.add(entry.actor() + " " + entry.action() + " " + entry.details());
                }
                return rows.equals(expected);
            } catch (Exception e) {
                return false;
            }
        }, "the audit rows " + expected);
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
            Bot.SeenDialog sound = open(e2e, bot, "settings sound", "Sounds settings");
            e2e.expect(sound.range("sound_volume").initial() == 30f && Boolean.FALSE.equals(sound.toggleValue("sound_clicks")),
                "the slider and the switch as stored: " + sound.range("sound_volume") + " " + sound.initials());
            e2e.expect("both".equals(open(e2e, bot, "settings display", "Display settings").choiceValue("feedback_channel")), "the choice as stored");
            e2e.log("the settings stored earlier came back");
        } else {
            e2e.step("first run: store a slider, a switch and a choice for the next run");
            Map<String, Object> values = open(e2e, bot, "settings sound", "Sounds settings").values();
            values.put("sound_volume", 30f);
            values.put("sound_clicks", false);
            e2e.click(bot, "Save", values);
            Map<String, Object> display = open(e2e, bot, "settings display", "Display settings").values();
            display.put("feedback_channel", "both");
            e2e.click(bot, "Save", display);
            UUID id = e2e.uuid(name);
            expectStored(e2e, id, "sound-volume", "30");
            expectStored(e2e, id, "sound-clicks", "false");
            expectStored(e2e, id, "feedback-channel", "both");
        }
    }
}
