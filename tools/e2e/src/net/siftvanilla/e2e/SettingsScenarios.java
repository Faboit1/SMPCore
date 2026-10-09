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
 * End-to-end scenarios of the settings framework and dialog, which is buttons only (the dialog style): the group list
 * (a coloured button per group, tooltips with the counts), switches flipping at once ("ON" green, "OFF" red) and the
 * behaviour they change, choices moving to their next option, numbers picked on a slider, every group on one page with
 * tooltips and no plugin name, resets, search, the changed settings, the {@code /settings} words, the staff tools
 * {@code /sift settings}, placeholders, the public {@link SettingsView}, server defaults, locks (greyed, "Set by the
 * server"), hidden settings and group overrides (order, icon, colour) from {@code features/settings.yml}, old stored
 * values, {@code SettingChangeEvent}, and core's delivery settings (the feedback channel, sounds, quiet in combat, sale
 * receipts and status bars).
 * <p>
 * The scenarios use the settings core shares with every feature (the Sounds, Display and Combat groups), so they don't
 * depend on where a feature put its own settings. The Sounds page is the one with all three kinds: its ping sound
 * choices are offered once a feature plays them, so the scenarios declare the mention sound as read (chat does that
 * itself). Settings are read and changed on their buttons with {@link SettingsSteps}.
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
        list.add(of("settings-one-page", SettingsScenarios::onePage));
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

    /** The settings a page shows, by input key ({@link SettingsSteps#read}). */
    private static Map<String, SettingsSteps.Shown> shown(E2E e2e, Bot.SeenDialog page) {
        return SettingsSteps.read(e2e, page);
    }

    /** The setting with this key on a page; fails when the page doesn't show it. */
    private static SettingsSteps.Shown shown(E2E e2e, Bot.SeenDialog page, String key) {
        SettingsSteps.Shown shown = SettingsSteps.read(e2e, page).get(key);
        e2e.expect(shown != null, page.title() + " shows " + key + ": " + labels(page));
        return shown;
    }

    private static List<String> labels(Bot.SeenDialog page) {
        return page.buttons().stream().map(Bot.Button::label).toList();
    }

    /** A button whose label starts with {@code start}, or null. */
    private static Bot.Button buttonStarting(Bot.SeenDialog page, String start) {
        for (Bot.Button button : page.buttons()) {
            if (button.label().startsWith(start)) {
                return button;
            }
        }
        return null;
    }

    /** Clicks a page's button by the start of its label and waits for the next dialog. */
    private static void clickStarting(E2E e2e, Bot bot, String start) {
        Bot.Button button = buttonStarting(bot.dialog(), start);
        e2e.expect(button != null, "a button starting '" + start + "': " + labels(bot.dialog()));
        e2e.click(bot, button.label());
    }

    /** The colour of the palette's on, off, accent and secondary colours as the bot reads them. */
    private static final String GREEN = "#55FF55";
    private static final String RED = "#FF5555";
    private static final String ACCENT = "#FFD866";
    private static final String GRAY = "#AAAAAA";

    /** A group's colour as #RRGGBB. */
    private static String hex(SettingCategory category) {
        return category.color() == null ? "#FFFFFF" : String.format("#%06X", category.color().value());
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
     * Runs commands as a console-like sender (every permission), one after another in the same tick, and returns what
     * they were told, one plain-text entry per message. The list keeps filling: staff tools answer after a database
     * read, so wait on it with {@link E2E#eventually}.
     */
    private static List<String> capture(E2E e2e, String... commands) {
        return capture(e2e, 0, commands);
    }

    /**
     * {@link #capture(E2E, String...)} on a slow database: the database writer is first held for {@code holdMillis}, so
     * every read and write the commands queue waits behind it and they run back to back (0: not held).
     */
    private static List<String> capture(E2E e2e, long holdMillis, String... commands) {
        List<String> lines = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(harness(), () -> {
            try {
                if (holdMillis > 0) {
                    e2e.services().database().write(connection -> {
                        try {
                            Thread.sleep(holdMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                }
                var sender = Bukkit.createCommandSender(message -> lines.add(PlainTextComponentSerializer.plainText().serialize(message)));
                for (String command : commands) {
                    Bukkit.dispatchCommand(sender, command);
                }
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            done.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new E2E.Failure("commands " + List.of(commands) + " failed: " + e.getCause());
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

    // ------------------------------------------------------------------ 1. the group list and switches

    /**
     * The group list (a coloured button per group the player sees, in order, with its icon and a tooltip with what it
     * covers and the counts; Search settings; Changed settings), the menu and pause routes, staff-only settings, a switch
     * flipping at once (ON green, OFF red, no message, the sound it controls really stops), a stale button, and opening
     * groups by command.
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

            e2e.step("the group list: a coloured button per group the player sees, in order, with its icon and a tooltip");
            bot.clearLogs();
            Bot.SeenDialog list = open(e2e, bot, "settings", "Settings");
            List<Group> groups = visibleGroups(e2e, name);
            e2e.expect(groups.size() > 1, "players see several groups: " + groups.size());
            e2e.expect(list.body().isEmpty(), "nothing written above the buttons: " + list.body());
            int last = -1;
            int total = 0;
            for (Group group : groups) {
                Bot.Button button = list.button(group.label());
                e2e.expect(button != null, "a " + group.label() + " button: " + list.buttons());
                int at = list.buttons().indexOf(button);
                e2e.expect(at > last, group.label() + " after the group before it: " + labels(list));
                last = at;
                e2e.expect(button.label().startsWith("["), "the button starts with the group's icon: " + button.label());
                e2e.expect(hex(group.category()).equals(button.valueColor()), group.label() + " in its colour " + hex(group.category())
                    + ": " + button.valueColor());
                e2e.expect(button.tooltip() != null && button.tooltip().contains(group.description())
                    && button.tooltip().contains(group.entries().size() + " settings, 0 changed"), "its tooltip: " + button.tooltip());
                total += group.entries().size();
            }
            e2e.expect(list.button("Search settings") != null && list.button("Search settings").tooltip() != null,
                "a search button with a tooltip: " + list.buttons());
            Bot.Button changedButton = list.button("Changed settings (0)");
            e2e.expect(changedButton != null && changedButton.tooltip().contains("You changed 0 of " + total + " settings."),
                "the changed settings and their count: " + list.buttons());
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
            e2e.expect(shown(e2e, SettingsSteps.openGroup(e2e, staff, gated.category().id(), gatedGroup + " settings")).containsKey(gated.inputKey()),
                "staff get " + gated.id());
            String gatedId = gated.category().id();
            if (groups.stream().anyMatch(group -> group.category().id().equals(gatedId))) {
                e2e.expect(!shown(e2e, SettingsSteps.openGroup(e2e, bot, gatedId, gatedGroup + " settings")).containsKey(gated.inputKey()),
                    "players don't: " + gated.id());
            } else {
                bot.clearLogs();
                bot.command("settings " + gatedId);
                expectSaw(e2e, bot, "There is no settings group called " + gatedId);
            }

            e2e.step("a group's page is one button per setting with nothing above them");
            Bot.SeenDialog sound = SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings");
            e2e.expect(sound.body().isEmpty() && sound.inputs().isEmpty(), "buttons only: " + sound.body() + " " + sound.inputs());
            Group soundGroup = groups.stream().filter(group -> group.category().id().equals("sound")).findFirst().orElseThrow();
            e2e.expect(shown(e2e, sound).size() == soundGroup.entries().size(), "every setting of the group: " + labels(sound));
            e2e.expect(sound.button("Next page") == null && sound.button("Save") == null, "no pages, no Save: " + labels(sound));
            Bot.Button clicks = sound.button("Menu click sounds: ON");
            e2e.expect(clicks != null && GREEN.equals(clicks.valueColor()), "a switch reads ON in green: " + labels(sound));
            e2e.expect(clicks.tooltip().startsWith("Play a click when you use buttons in menus.") && clicks.tooltip().contains("Default: ON")
                && clicks.tooltip().endsWith("Click to switch it."), "its tooltip says what it does: " + clicks.tooltip());

            e2e.step("a click flips the switch at once: the same page shows OFF in red, nothing is said, and click sounds stop");
            bot.clearMessages();
            e2e.click(bot, clicks.label());
            Bot.SeenDialog flipped = page(e2e, bot, "Sounds settings");
            Bot.Button off = flipped.button("Menu click sounds: OFF");
            e2e.expect(off != null && RED.equals(off.valueColor()), "OFF in red: " + labels(flipped));
            e2e.expect(!settings.get(id, SharedSettings.SOUND_CLICKS), "click sounds off");
            expectStored(e2e, id, "sound-clicks", "false");
            e2e.sleep(300);
            e2e.expect(!bot.anyFeedbackContains("Menu click sounds"), "no message for a click: " + bot.chat() + " " + bot.actionBar());
            bot.clearSounds();
            e2e.services().messenger().feedback(player, Feedback.CLICK);
            e2e.services().messenger().feedback(player, Feedback.ERROR);
            e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note still plays");
            e2e.expect(bot.sounds().stream().noneMatch(s -> s.sound().endsWith("ui.button.click")), "no click sound: " + bot.sounds());

            e2e.step("a button does what it showed: OFF asks for ON, even when something else turned it on meanwhile");
            settings.set(id, SharedSettings.SOUND_CLICKS, true, Change.feature());
            e2e.click(bot, "Menu click sounds: OFF");
            e2e.expect(page(e2e, bot, "Sounds settings").button("Menu click sounds: ON") != null, "ON: " + labels(bot.dialog()));
            e2e.expect(settings.get(id, SharedSettings.SOUND_CLICKS), "still on");

            e2e.step("a changed setting is counted and listed under Changed settings");
            settings.set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
            bot.clearLogs();
            Bot.SeenDialog counted = open(e2e, bot, "settings", "Settings");
            e2e.expect(counted.button("Changed settings (1)") != null
                && counted.button("Changed settings (1)").tooltip().contains("You changed 1 of " + total + " settings."),
                "one changed: " + counted.buttons());
            e2e.expect(counted.button("Sounds").tooltip().contains("1 changed"), "and in its group: " + counted.button("Sounds").tooltip());
            e2e.click(bot, "Changed settings");
            Bot.SeenDialog summary = page(e2e, bot, "Changed settings");
            Bot.Button volume = summary.button("Sound volume: 30%");
            e2e.expect(volume != null && volume.tooltip().startsWith("Sounds") && volume.tooltip().contains("Default: 100%"),
                "the change, its group and default: " + labels(summary) + " " + (volume == null ? "" : volume.tooltip()));
            e2e.click(bot, "Back");
            settingsList(e2e, bot);
            settings.set(id, SharedSettings.SOUND_VOLUME, 100L, Change.feature());

            e2e.step("/settings <group> opens a group, and an unknown group is refused");
            bot.clearLogs();
            bot.command("settings nosuchgroup");
            expectSaw(e2e, bot, "There is no settings group called nosuchgroup");
            e2e.sleep(1_100);
            open(e2e, bot, "settings SOUND", "Sounds settings");
        } finally {
            e2e.console("deop " + staffName);
        }
    }

    // ------------------------------------------------------------------ 2-4. choices, numbers, persisting

    /**
     * The three kinds on their buttons: a choice cycling through the options its tooltip lists (and the behaviour it
     * changes), a number picked on a slider (refusing values off its range and step), placeholders, persisting across
     * a rejoin, and storing the default deleting the row again.
     */
    static void kinds(E2E e2e) throws Exception {
        offerAllKinds(e2e);
        String name = e2e.name("Kinds");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        Player player = e2e.player(name);

        e2e.step("the Sounds page: a number, switches and a choice, each showing its value");
        Bot.SeenDialog sound = SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings");
        Map<String, SettingsSteps.Shown> kinds = shown(e2e, sound);
        e2e.expect("range".equals(kinds.get("sound_volume").kind()) && "toggle".equals(kinds.get("sound_notify").kind())
            && "choice".equals(kinds.get("sound_mention").kind()), "a number, switches and a choice: " + labels(sound));
        Bot.Button volume = sound.button("Sound volume: 100%");
        e2e.expect(volume != null && ACCENT.equals(volume.valueColor()), "the number in the accent colour: " + labels(sound));
        e2e.expect(volume.tooltip().contains("From 0% to 100%, in steps of 10%") && volume.tooltip().contains("Default: 100%")
            && volume.tooltip().endsWith("Click to change it."), "its range and default: " + volume.tooltip());
        SettingsSteps.Shown mention = kinds.get("sound_mention");
        e2e.expect(List.of("default", "bell", "pling", "chime", "off").equals(mention.options()), "the ping options: " + mention.options());
        e2e.expect("default".equals(mention.value()) && ACCENT.equals(mention.button().valueColor()), "on the default: " + mention.text());

        e2e.step("a click moves a choice to its next option at once; Off reads red");
        e2e.click(bot, "Mention sound: Default");
        e2e.expect(page(e2e, bot, "Sounds settings").button("Mention sound: Bell") != null, "Bell: " + labels(bot.dialog()));
        expectStored(e2e, id, "sound-mention", "bell");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_MENTION) == PingSound.BELL, "the bell now");
        SettingsSteps.set(e2e, bot, "sound_mention", "off");
        e2e.expect(RED.equals(page(e2e, bot, "Sounds settings").button("Mention sound: Off").valueColor()), "Off in red");
        SettingsSteps.set(e2e, bot, "sound_mention", "bell");

        e2e.step("a number opens a slider with its range and unit; Done stores it and returns to the page");
        e2e.click(bot, "Sound volume: 100%");
        Bot.SeenDialog slider = page(e2e, bot, "Sound volume");
        Bot.RangeSeen range = slider.range("sound_volume");
        e2e.expect(range != null && range.start() == 0f && range.end() == 100f && Float.valueOf(10f).equals(range.step())
            && range.initial() == 100f, "0 to 100 in steps of 10, starting at 100: " + range);
        e2e.expect("Sound volume (%)".equals(range.label()), "the unit in the label: " + range.label());
        e2e.expect(slider.button("Done") != null && slider.button("Back") != null, "Done and Back: " + slider.buttons());

        e2e.step("forged slider values are refused by the router and change nothing");
        e2e.click(bot, "Done", Map.of("sound_volume", 45f));
        Bot.SeenDialog refused = page(e2e, bot, "Sound volume");
        e2e.expect(refused.bodyText().contains("Check Sound volume (%) and try again"), "an off-step value: " + refused.body());
        e2e.click(bot, "Done", Map.of("sound_volume", 110f));
        e2e.expect(page(e2e, bot, "Sound volume").bodyText().contains("Check Sound volume"), "out of range too");
        e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_VOLUME) == 100L, "nothing changed");
        e2e.click(bot, "Done", Map.of("sound_volume", 30f));
        e2e.expect(page(e2e, bot, "Sounds settings").button("Sound volume: 30%") != null, "back on the page: " + labels(bot.dialog()));
        expectStored(e2e, id, "sound-volume", "30");

        e2e.step("the volume really changes: sounds play at 30%");
        bot.clearSounds();
        e2e.services().messenger().feedback(player, Feedback.ERROR);
        e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note");
        float played = bot.sounds().stream().filter(s -> s.sound().endsWith("note_block.bass")).findFirst().orElseThrow().volume();
        settings(e2e).set(id, SharedSettings.SOUND_VOLUME, 100L, Change.feature());
        bot.clearSounds();
        e2e.services().messenger().feedback(player, Feedback.ERROR);
        e2e.eventually(() -> bot.sounds().stream().anyMatch(s -> s.sound().endsWith("note_block.bass")), "the error note at 100%");
        float full = bot.sounds().stream().filter(s -> s.sound().endsWith("note_block.bass")).findFirst().orElseThrow().volume();
        e2e.expect(Math.abs(played - full * 0.3f) < 0.01f, "30% of the full volume: " + played + " of " + full);
        settings(e2e).set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        SettingsSteps.set(e2e, bot, "sound_notify", false);

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

        e2e.step("the Display page: a choice with the shared option names, and what it changes");
        Bot.SeenDialog display = SettingsSteps.openGroup(e2e, bot, "display", "Display settings");
        SettingsSteps.Shown channel = shown(e2e, display, "feedback_channel");
        e2e.expect(List.of("actionbar", "chat", "both").equals(channel.options()), "options: " + channel.options());
        e2e.expect(channel.button().tooltip().contains("• Above the hotbar") && channel.button().tooltip().contains("• Chat"),
            "listed by name in the tooltip: " + channel.button().tooltip());
        e2e.expect("actionbar".equals(channel.value()), "starts on the default");
        bot.clearMessages();
        e2e.click(bot, "Quick results and errors: Above the hotbar");
        e2e.expect(page(e2e, bot, "Display settings").button("Quick results and errors: Chat") != null, "Chat: " + labels(bot.dialog()));
        expectStored(e2e, id, "feedback-channel", "chat");
        e2e.sleep(1_100);
        bot.clearLogs();
        bot.command("pay " + name + " 10");
        e2e.eventually(() -> bot.chatContains("yourself"), "errors now go to chat: " + bot.chat() + " " + bot.actionBar());

        e2e.step("everything persists across a rejoin");
        bot.quit();
        e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
        e2e.expect(!settings(e2e).loaded(id), "forgotten on quit");
        Bot back = e2e.bot(name);
        e2e.expect(e2e.services().directory().previousSeen(id) > 0, "the last visit is remembered for join summaries");
        Map<String, SettingsSteps.Shown> after = shown(e2e, SettingsSteps.openGroup(e2e, back, "sound", "Sounds settings"));
        e2e.expect("30".equals(after.get("sound_volume").value()) && "false".equals(after.get("sound_notify").value())
            && "bell".equals(after.get("sound_mention").value()), "the number, switch and choice as stored: " + after.values().stream()
            .map(SettingsSteps.Shown::text).toList());
        e2e.expect("chat".equals(shown(e2e, SettingsSteps.openGroup(e2e, back, "display", "Display settings"), "feedback_channel").value()),
            "the display choice as stored");

        e2e.step("going back to the default deletes the row, so the player follows the default again");
        SettingsSteps.set(e2e, back, "feedback_channel", "actionbar");
        expectStored(e2e, id, "feedback-channel", null);
        SettingsSteps.openGroup(e2e, back, "sound", "Sounds settings");
        SettingsSteps.apply(e2e, back, new LinkedHashMap<>(Map.of("sound_volume", 100f, "sound_notify", true, "sound_mention", "default")));
        expectStored(e2e, id, "sound-volume", null);
        expectStored(e2e, id, "sound-notify", null);
        expectStored(e2e, id, "sound-mention", null);
        e2e.expect(!settings(e2e).changed(id, SharedSettings.SOUND_VOLUME), "nothing counts as changed");
        e2e.expect("0".equals(placeholder(e2e, name, "settings_changed")), "the count is back to 0");
    }

    // ------------------------------------------------------------------ 5. every setting on one page, in the style

    /**
     * Every group's page, as staff see them (every setting): all of a group's settings on one page as buttons ("Label:
     * value"), each with a tooltip, nothing written above them, no page buttons, and no "SiftCore" anywhere; the old
     * {@code page-size} key of a server's file changes nothing.
     */
    static void onePage(E2E e2e) throws Exception {
        String name = e2e.name("OnePage");
        Bot bot = e2e.bot(name);
        e2e.console("op " + name);
        try {
            withSettingsKeys(e2e, Map.of("page-size", "page-size: 2"), x -> {
                for (Group group : visibleGroups(e2e, name)) {
                    e2e.step("the " + group.label() + " page");
                    Bot.SeenDialog page = SettingsSteps.openGroup(e2e, bot, group.category().id(), group.label() + " settings");
                    e2e.expect(page.body().isEmpty() && page.inputs().isEmpty(), "buttons only: " + page.body());
                    Map<String, SettingsSteps.Shown> settings = shown(e2e, page);
                    e2e.expect(settings.size() == group.entries().size(), "all " + group.entries().size() + " settings on one page: "
                        + labels(page));
                    for (Bot.Button button : page.buttons()) {
                        String text = button.label() + " " + button.tooltip();
                        e2e.expect(!text.contains("SiftCore"), "no plugin name: " + text);
                        e2e.expect(!text.contains("Next page") && !text.contains("Page "), "no paging: " + text);
                        if (!button.label().equals("Back") && !button.label().equals("Close")) {
                            e2e.expect(button.tooltip() != null && !button.tooltip().isBlank(), "a tooltip on " + button.label());
                        }
                    }
                    for (SettingsSteps.Shown shown : settings.values()) {
                        e2e.expect(shown.value() != null, "the value of " + shown.key() + " reads back: " + shown.text());
                        String colour = shown.button().valueColor();
                        e2e.expect(colour != null && !colour.equals("#FFFFFF"), shown.key() + "'s value is coloured: " + shown.button().label());
                    }
                }
            });
        } finally {
            e2e.console("deop " + name);
        }
    }

    // ------------------------------------------------------------------ 6. resets

    /** Reset this group (asks first, Cancel keeps everything), Reset everything from the changed settings, /settings reset. */
    static void reset(E2E e2e) throws Exception {
        String name = e2e.name("Resetter");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        PlayerSettings settings = settings(e2e);

        e2e.step("no reset button while nothing in the group changed");
        e2e.expect(SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings").button("Reset this group") == null,
            "no reset: " + labels(bot.dialog()));

        e2e.step("Reset this group asks first, naming what goes back in its tooltip; Cancel keeps everything");
        settings.set(id, SharedSettings.SOUND_VOLUME, 30L, Change.feature());
        settings.set(id, SharedSettings.SOUND_SUCCESS, false, Change.feature());
        settings.set(id, SharedSettings.FEEDBACK_CHANNEL, AlertStyle.BOTH, Change.feature());
        Bot.SeenDialog sound = SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings");
        Bot.Button reset = sound.button("Reset this group");
        e2e.expect(reset != null && reset.tooltip().contains("2 settings"), "a reset button: " + labels(sound));
        e2e.click(bot, "Reset this group");
        Bot.SeenDialog confirm = page(e2e, bot, "Reset Sounds settings?");
        e2e.expect(confirm.bodyText().equals("2 settings go back to their defaults."), "one short line: " + confirm.body());
        String names = confirm.button("Reset").tooltip();
        e2e.expect(names.contains("Sound volume: 30% to 100%") && names.contains("Success chimes: off to on"), "what goes back: " + names);
        e2e.click(bot, "Cancel");
        e2e.expect(page(e2e, bot, "Sounds settings").button("Sound volume: 30%") != null, "nothing was reset: " + labels(bot.dialog()));

        e2e.step("Reset puts the group back to the defaults and deletes the rows");
        bot.clearMessages();
        e2e.click(bot, "Reset this group");
        page(e2e, bot, "Reset Sounds settings?");
        e2e.click(bot, "Reset");
        Bot.SeenDialog after = page(e2e, bot, "Sounds settings");
        expectSaw(e2e, bot, "Reset 2 settings to their defaults");
        e2e.expect(after.button("Sound volume: 100%") != null && after.button("Success chimes: ON") != null, "the defaults: " + labels(after));
        e2e.expect(after.button("Reset this group") == null, "nothing left to reset: " + labels(after));
        expectStored(e2e, id, "sound-volume", null);
        expectStored(e2e, id, "sound-success", null);
        expectStored(e2e, id, "feedback-channel", "both");

        e2e.step("the counts follow");
        Bot.SeenDialog list = open(e2e, bot, "settings", "Settings");
        e2e.expect(list.button("Changed settings (1)") != null, "one left: " + list.buttons());

        e2e.step("the changed settings: the same buttons, with their group and default in the tooltip; Reset everything");
        settings.set(id, SharedSettings.SOUND_CLICKS, false, Change.feature());
        Bot.SeenDialog summary = open(e2e, bot, "settings changed", "Changed settings");
        Bot.Button channel = summary.button("Quick results and errors: Both");
        Bot.Button clicks = summary.button("Menu click sounds: OFF");
        e2e.expect(channel != null && clicks != null, "both changes: " + labels(summary));
        e2e.expect(labels(summary).indexOf(clicks.label()) < labels(summary).indexOf(channel.label()), "in dialog order (Sounds first)");
        e2e.expect(channel.tooltip().startsWith("Display") && channel.tooltip().contains("Default: Above the hotbar"), channel.tooltip());
        e2e.click(bot, clicks.label());
        e2e.expect(page(e2e, bot, "Changed settings").button("Menu click sounds: ON") != null,
            "flipped back to the default, it stays listed: " + labels(bot.dialog()));
        settings.set(id, SharedSettings.SOUND_CLICKS, false, Change.feature());
        bot.clearMessages();
        e2e.click(bot, "Reset everything");
        Bot.SeenDialog everything = page(e2e, bot, "Reset every setting?");
        e2e.expect(everything.button("Reset").tooltip().contains("Quick results and errors: Both to Above the hotbar"),
            "listed: " + everything.button("Reset").tooltip());
        e2e.click(bot, "Reset");
        Bot.SeenDialog afterAll = settingsList(e2e, bot);
        expectSaw(e2e, bot, "Reset 2 settings to their defaults");
        expectStored(e2e, id, "feedback-channel", null);
        expectStored(e2e, id, "sound-clicks", null);
        e2e.expect(afterAll.button("Changed settings (0)") != null, "nothing changed now: " + afterAll.buttons());
        Bot.SeenDialog none = open(e2e, bot, "settings changed", "Changed settings");
        e2e.expect(none.bodyText().contains("You use the defaults for every setting.") && none.button("Reset everything") == null,
            "says so in one line: " + none.body());

        e2e.step("/settings reset <group> opens the confirmation, or says there is nothing to reset");
        e2e.sleep(1_100);
        bot.clearLogs();
        bot.command("settings reset sound");
        expectSaw(e2e, bot, "You use the defaults for every Sounds setting");
        settings.set(id, SharedSettings.SOUND_VOLUME, 40L, Change.feature());
        e2e.sleep(1_100);
        open(e2e, bot, "settings reset sound", "Reset Sounds settings?");
        e2e.click(bot, "Reset");
        page(e2e, bot, "Sounds settings");
        expectStored(e2e, id, "sound-volume", null);
        e2e.sleep(1_100);
        bot.command("settings reset all");
        expectSaw(e2e, bot, "You use the defaults for every setting");
    }

    // ------------------------------------------------------------------ 7. search

    /** Search by label word and option label, results are the same buttons, no results, an empty query, /settings search. */
    static void search(E2E e2e) throws Exception {
        String name = e2e.name("Seeker");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("Search settings opens a form that waits for its results");
        open(e2e, bot, "settings", "Settings");
        e2e.click(bot, "Search settings");
        Bot.SeenDialog form = e2e.dialog(bot, "Search settings");
        e2e.expect("text".equals(form.inputs().get("query")), "a text field: " + form.inputs());
        e2e.expect(form.body().isEmpty(), "no intro: " + form.body());
        e2e.expect(form.button("Search").tooltip() != null, "what to type is in the button's tooltip");
        e2e.expect("wait_for_response".equals(form.after()), "Search shows the waiting screen: " + form.after());

        e2e.step("an empty query is refused in red on the form");
        Map<String, Object> empty = form.values();
        empty.put("query", "   ");
        e2e.click(bot, "Search", empty);
        Bot.SeenDialog refused = e2e.dialog(bot, "Search settings");
        e2e.expect(refused.bodyText().contains("Type a word to search for"), "the error: " + refused.body());

        e2e.step("a label word finds the setting; its button works in place");
        Map<String, Object> query = refused.values();
        query.put("query", "volume");
        e2e.click(bot, "Search", query);
        Bot.SeenDialog results = page(e2e, bot, "Search: volume");
        Bot.Button volume = results.button("Sound volume: 100%");
        e2e.expect(volume != null && volume.tooltip().startsWith("Sounds"), "the volume, naming its group: " + labels(results));
        SettingsSteps.set(e2e, bot, "sound_volume", 60f);
        e2e.expect(page(e2e, bot, "Search: volume").button("Sound volume: 60%") != null, "back on the results");
        expectStored(e2e, id, "sound-volume", "60");
        e2e.click(bot, "Back");
        Bot.SeenDialog backToForm = e2e.dialog(bot, "Search settings");
        e2e.expect("volume".equals(backToForm.initial("query")), "back on the form with the query: " + backToForm.initial());

        e2e.step("an option label finds the settings that offer it, all on one page");
        Map<String, Object> option = backToForm.values();
        option.put("query", "above the hotbar");
        e2e.click(bot, "Search", option);
        Bot.SeenDialog byOption = page(e2e, bot, "Search: above the hotbar");
        e2e.expect(shown(e2e, byOption).containsKey("feedback_channel"), "the feedback channel: " + labels(byOption));
        e2e.expect(byOption.button("Next page") == null, "no pages");

        e2e.step("no match: a notice, and Back returns to the form");
        e2e.click(bot, "Back");
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
        List<String> keys = List.copyOf(shown(e2e, direct).keySet());
        e2e.expect(!keys.isEmpty() && keys.getFirst().equals("sound_errors"), "the label match first: " + keys);
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
     * changed settings and reset words.
     */
    static void commands(E2E e2e) throws Exception {
        String name = e2e.name("Commander");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);

        e2e.step("/settings <group> <setting> <value> changes a number, confirmed above the hotbar");
        say(e2e, bot, "settings sound volume 30");
        e2e.eventually(() -> bot.actionBarContains("Sound volume set to 30%"), "the confirmation: " + bot.actionBar());
        expectStored(e2e, id, "sound-volume", "30");
        say(e2e, bot, "settings sound volume 30");
        e2e.eventually(() -> bot.actionBarContains("Sound volume is already 30%"), "already so: " + bot.actionBar());

        e2e.step("/settings <group> <setting> shows the value, the default and the values; the line opens its page");
        say(e2e, bot, "settings sound volume");
        e2e.eventually(() -> bot.chatContains("Sound volume: 30% (default 100%). Values: a whole number from 0 to 100 in steps of 10"),
            "the info line: " + bot.chat());
        e2e.expect(bot.openChatDialog("Sound volume: 30%"), "the line opens a dialog: " + bot.chatDialogs());
        Bot.SeenDialog fromChat = page(e2e, bot, "Sounds settings");
        e2e.expect(fromChat.button("Sound volume: 30%") != null, "the page that holds it: " + labels(fromChat));

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
        e2e.eventually(() -> bot.actionBarContains("Sound volume set to 50%"), "a short name only one setting has: " + bot.actionBar());

        e2e.step("values a setting doesn't take are refused, naming what it takes, in red");
        say(e2e, bot, "settings display feedback-channel title");
        expectSaw(e2e, bot, "For Quick results and errors, use actionbar, chat, both.");
        say(e2e, bot, "settings sound volume 150");
        expectSaw(e2e, bot, "For Sound volume, use a whole number from 0 to 100 in steps of 10.");
        say(e2e, bot, "settings sound volume 55");
        expectSaw(e2e, bot, "For Sound volume, use a whole number");
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
        e2e.expect(shown(e2e, found).containsKey("sound_volume"), "the search results: " + labels(found));

        e2e.step("/settings changed and /settings reset open their screens");
        e2e.sleep(1_100);
        Bot.SeenDialog summary = open(e2e, bot, "settings changed", "Changed settings");
        e2e.expect(summary.button("Sound volume: 50%") != null, "the change: " + labels(summary));
        e2e.sleep(1_100);
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
     * (and its instant hook), an offline player's (read when they rejoin; two commands in the same tick see each
     * other), resets, refusals, and the audit log.
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

        e2e.step("two commands for the offline player in the same tick (a console script, on a slow database) see each other's change");
        List<String> twice = capture(e2e, 300, "sift settings " + name + " sound-volume 50", "sift settings " + name + " sound-volume 80");
        e2e.eventually(() -> twice.size() >= 2, "both answered: " + twice);
        e2e.expect(twice.size() == 2 && twice.get(0).contains("Set sound-volume of " + name + " to 50 (was 80).")
            && twice.get(1).contains("Set sound-volume of " + name + " to 80 (was 50)."),
            "the second compares with the first one's value, not the one before it: " + twice);
        expectStored(e2e, id, "sound-volume", "80");
        e2e.eventually(() -> {
            try {
                List<String> rows = audit(e2e, id).stream().map(AuditLog.Entry::details).toList();
                int first = rows.indexOf("sound-volume: 80 -> 50");
                int second = rows.indexOf("sound-volume: 50 -> 80");
                return first >= 0 && second >= 0 && second < first;
            } catch (Exception e) {
                return false;
            }
        }, "both changes are audited with the value before each, in order");
        List<String> setThenReset = capture(e2e, 300, "sift settings " + name + " sound-volume 40", "sift settings " + name + " reset sound-volume");
        e2e.eventually(() -> setThenReset.size() >= 2, "both answered: " + setThenReset);
        e2e.expect(setThenReset.size() == 2 && setThenReset.get(0).contains("Set sound-volume of " + name + " to 40 (was 80).")
            && setThenReset.get(1).contains("Reset 1 settings of " + name + "."),
            "the reset finds the row the change just wrote: " + setThenReset);
        expectStored(e2e, id, "sound-volume", null);
        staffSays(e2e, "sift settings " + name + " sound-volume 80", "Set sound-volume of " + name + " to 80 (was 100).");
        expectStored(e2e, id, "sound-volume", "80");
        Bot back = e2e.bot(name);
        e2e.expect(settings.get(id, SharedSettings.SOUND_VOLUME) == 80L, "read on rejoin: " + settings.get(id, SharedSettings.SOUND_VOLUME));
        e2e.expect(open(e2e, back, "settings sound", "Sounds settings").button("Sound volume: 80%") != null, "the dialog shows it");

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
     * features/settings.yml: a server default (and a player who stored it following it), a lock (the value greyed with
     * "Set by the server", refused by commands), a hidden setting (left out of pages and commands), and group overrides
     * (order, icon, colour).
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
            Bot.SeenDialog page = SettingsSteps.openGroup(e2e, follow, "sound", "Sounds settings");
            Bot.Button volume = page.button("Sound volume: 60%");
            e2e.expect(volume != null && volume.tooltip().contains("Default: 60%"), "the server default, also as the default: " + labels(page));
            follow.clearLogs();
            e2e.sleep(1_100);
            follow.command("settings sound volume");
            e2e.eventually(() -> follow.chatContains("Sound volume: 60% (default 60%)"), "the server default is the default: " + follow.chat());

            e2e.step("a hidden setting is left out of the dialog and commands, reads the server's value and can't be changed");
            e2e.expect(!shown(e2e, page).containsKey("sound_clicks"), "no click sound switch: " + labels(page));
            e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "the keeper's stored 'off' does not apply while hidden");
            e2e.expect(settings(e2e).set(keepId, SharedSettings.SOUND_CLICKS, false, Change.api("SiftE2E")) == SetResult.NOT_ALLOWED,
                "refused by the registry");
            expectStored(e2e, keepId, "sound-clicks", "false");
            follow.clearLogs();
            e2e.sleep(1_100);
            follow.command("settings sound clicks off");
            expectSaw(e2e, follow, "There is no setting called clicks in Sounds.");
            e2e.expect("".equals(placeholder(e2e, follower, "setting_sound-clicks")), "no placeholder value");

            e2e.step("picking the server default deletes the row");
            SettingsSteps.openGroup(e2e, keep, "sound", "Sounds settings");
            SettingsSteps.set(e2e, keep, "sound_volume", 60f);
            expectStored(e2e, keepId, "sound-volume", null);

            e2e.step("a locked setting shows its value greyed and set by the server; clicks and commands change nothing");
            Bot.SeenDialog combat = SettingsSteps.openGroup(e2e, follow, "combat", "Combat & stats settings");
            Bot.Button quiet = combat.button("Quiet during combat: ON");
            e2e.expect(quiet != null && GRAY.equals(quiet.valueColor()), "greyed: " + labels(combat));
            e2e.expect(quiet.tooltip().contains("Set by the server."), "its tooltip says why: " + quiet.tooltip());
            e2e.click(follow, quiet.label());
            e2e.expect(page(e2e, follow, "Combat & stats settings").button("Quiet during combat: ON") != null, "unchanged");
            e2e.expect(settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is the value");
            e2e.expect(settings(e2e).set(followId, SharedSettings.QUIET_IN_COMBAT, false, Change.api("SiftE2E")) == SetResult.LOCKED,
                "refused by the registry");
            follow.clearLogs();
            e2e.sleep(1_100);
            follow.command("settings combat quiet-in-combat off");
            expectSaw(e2e, follow, "Quiet during combat is set by the server.");
            follow.clearLogs();
            e2e.sleep(1_100);
            follow.command("settings quiet-in-combat");
            e2e.eventually(() -> follow.chatContains("Quiet during combat: on (set by the server)"), "the info line: " + follow.chat());
        });

        e2e.step("without the server default the player who stored it follows the new default");
        e2e.expect(settings(e2e).get(keepId, SharedSettings.SOUND_VOLUME) == 100L, "back to 100%");
        e2e.expect(!settings(e2e).get(followId, SharedSettings.QUIET_IN_COMBAT), "the lock is gone");
        e2e.expect(!settings(e2e).get(keepId, SharedSettings.SOUND_CLICKS), "shown again: the keeper's stored choice is back");

        withSettingsKeys(e2e, Map.of(
            "categories", "categories:\n  sound:\n    order: 1\n    color: \"#123456\"\n  display:\n    icon: star"), x -> {
            e2e.step("the server moves a group to the top and changes its colour and another's icon");
            e2e.sleep(1_100);
            Bot.SeenDialog list = open(e2e, follow, "settings", "Settings");
            e2e.expect(list.buttons().getFirst().label().endsWith("Sounds"), "Sounds first: " + list.buttons());
            e2e.expect("#123456".equals(list.buttons().getFirst().valueColor()), "in the server's colour: " + list.buttons().getFirst().valueColor());

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
            e2e.step("a cancelled change shows in red on the page and nothing is stored");
            open(e2e, bot, "settings sound", "Sounds settings");
            e2e.click(bot, "Success chimes: ON");
            Bot.SeenDialog refused = page(e2e, bot, "Sounds settings");
            e2e.expect(refused.bodyText().contains("Success chimes couldn't be changed."), "in red on the page: " + refused.body());
            e2e.expect(refused.button("Success chimes: ON") != null, "the button still shows ON: " + labels(refused));
            SettingsSteps.set(e2e, bot, "sound_clicks", false);
            e2e.expect(settings(e2e).get(id, SharedSettings.SOUND_SUCCESS), "still on");
            expectStored(e2e, id, "sound-success", null);
            expectStored(e2e, id, "sound-clicks", "false");
            e2e.expect(recorder.seen.contains("sound-success:true->false:DIALOG:sound"), "the listener saw it: " + recorder.seen);
            e2e.expect(recorder.committed.contains("sound-clicks=false") && !recorder.committed.contains("sound-success=false"),
                "MONITOR sees only what was stored: " + recorder.committed);

            e2e.step("commands and the API are cancellable too");
            e2e.sleep(1_100);
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
            e2e.sleep(1_100);
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
        e2e.expect("chat".equals(shown(e2e, SettingsSteps.openGroup(e2e, bot, "display", "Display settings"), "feedback_channel").value()),
            "the choice");
        SettingsSteps.Shown receipts = shown(e2e, SettingsSteps.openGroup(e2e, bot, "economy", "Money & selling settings"), "sell_receipts");
        e2e.expect("actionbar".equals(receipts.value()), "the receipts choice: " + receipts.text());
        e2e.expect("40".equals(shown(e2e, SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings"), "sound_volume").value()),
            "the number");

        e2e.step("the next change writes the value in its stored form");
        SettingsSteps.openGroup(e2e, bot, "economy", "Money & selling settings");
        SettingsSteps.set(e2e, bot, "sell_receipts", "off");
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

    /**
     * An option a choice stops offering (optionAvailableWhen): left out of the button's tooltip, skipped when the choice
     * moves to its next option, refused by the command, and a player who picked it while it was offered reads (and sees)
     * its stand-in until it comes back.
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
            e2e.step("the button's tooltip leaves the option out");
            SettingsSteps.Shown shown = shown(e2e, SettingsSteps.openGroup(e2e, bot, "display", "Display settings"), "e2e_gated");
            e2e.expect(List.of("actionbar", "chat").equals(shown.options()), "no Both: " + shown.options());

            e2e.step("moving through the options skips it");
            e2e.click(bot, "Display: Above the hotbar");
            e2e.expect(page(e2e, bot, "Display settings").button("Display: Chat") != null, "Chat: " + labels(bot.dialog()));
            e2e.click(bot, "Display: Chat");
            e2e.expect(page(e2e, bot, "Display settings").button("Display: Above the hotbar") != null,
                "after Chat comes the first again, not Both: " + labels(bot.dialog()));
            expectStored(e2e, id, "e2e-gated", null);

            e2e.step("the command refuses it, naming it");
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
            SettingsSteps.Shown standIn = shown(e2e, SettingsSteps.openGroup(e2e, bot, "display", "Display settings"), "e2e_gated");
            e2e.expect("chat".equals(standIn.value()), "the page shows Chat: " + standIn.text());
            expectStored(e2e, id, "e2e-gated", "both");
            GATE_BOTH.set(true);
            e2e.sleep(1_100);
            SettingsSteps.Shown back = shown(e2e, SettingsSteps.openGroup(e2e, bot, "display", "Display settings"), "e2e_gated");
            e2e.expect("both".equals(back.value()) && back.options().contains("both"),
                "offered again, the stored Both shows: " + back.text() + " " + back.options());
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
     * before a restart) stored the values, the player must join with them; otherwise they are set now through the
     * dialog's buttons. Run it, restart the server, run it again.
     */
    static void persist(E2E e2e) throws Exception {
        String name = "SetKeeper";
        UUID known = e2e.services().directory().uuid(name).orElse(null);
        String stored = known == null ? null : stored(e2e, known, "sound-volume");
        Bot bot = e2e.bot(name);
        if ("30".equals(stored)) {
            e2e.step("stored in an earlier run: the values are back");
            Map<String, SettingsSteps.Shown> sound = shown(e2e, SettingsSteps.openGroup(e2e, bot, "sound", "Sounds settings"));
            e2e.expect("30".equals(sound.get("sound_volume").value()) && "false".equals(sound.get("sound_clicks").value()),
                "the number and the switch as stored: " + sound.get("sound_volume").text() + " " + sound.get("sound_clicks").text());
            e2e.expect("both".equals(shown(e2e, SettingsSteps.openGroup(e2e, bot, "display", "Display settings"), "feedback_channel").value()),
                "the choice as stored");
            e2e.log("the settings stored earlier came back");
        } else {
            e2e.step("first run: store a number, a switch and a choice for the next run");
            SettingsSteps.edit(e2e, bot, "sound", "Sounds settings", new LinkedHashMap<>(Map.of("sound_volume", 30f, "sound_clicks", false)));
            SettingsSteps.edit(e2e, bot, "display", "Display settings", Map.of("feedback_channel", "both"));
            UUID id = e2e.uuid(name);
            expectStored(e2e, id, "sound-volume", "30");
            expectStored(e2e, id, "sound-clicks", "false");
            expectStored(e2e, id, "feedback-channel", "both");
        }
    }
}
