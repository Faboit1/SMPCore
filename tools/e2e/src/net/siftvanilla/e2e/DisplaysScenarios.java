package net.siftvanilla.e2e;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import net.siftvanilla.siftcore.core.player.Change;
import net.siftvanilla.siftcore.core.player.SetResult;
import net.siftvanilla.siftcore.feature.cosmetics.CosmeticsFeature;
import org.bukkit.Bukkit;

/**
 * Spawn leaderboards and info boards: what a client near a display receives (the text display entity and its
 * metadata), the click box over a leaderboard, and the admin flows (create, list with teleport, move, delete).
 */
final class DisplaysScenarios {

    private DisplaysScenarios() {
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
        list.add(of("displays-text", DisplaysScenarios::text));
        list.add(of("displays-click", DisplaysScenarios::click));
        list.add(of("displays-admin", DisplaysScenarios::admin));
        list.add(of("displays-hide", DisplaysScenarios::hide));
        list.add(of("display-settings", DisplaysScenarios::group));
        return list;
    }

    // ------------------------------------------------------------------ helpers

    /** The id of a synced data field, read from the server's own classes so nothing is hard-coded. */
    private static int dataId(Class<?> owner, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return ((EntityDataAccessor<?>) f.get(null)).id();
        } catch (ReflectiveOperationException e) {
            throw new E2E.Failure("no entity data field " + owner.getSimpleName() + "." + field + ": " + e);
        }
    }

    private static String text(Bot.SeenEntity entity) {
        Object value = entity.data().get(dataId(Display.TextDisplay.class, "DATA_TEXT_ID"));
        return value instanceof Component component ? component.getString() : "";
    }

    /** The text display near (x, z) whose text contains {@code needle}, or null. */
    private static Bot.SeenEntity textDisplay(Bot bot, double x, double z, String needle) {
        for (Bot.SeenEntity entity : bot.entities()) {
            if (entity.type().equals("minecraft:text_display") && Math.abs(entity.x() - x) < 0.6 && Math.abs(entity.z() - z) < 0.6
                && text(entity).contains(needle)) {
                return entity;
            }
        }
        return null;
    }

    private static Bot.SeenEntity interaction(Bot bot, double x, double z) {
        for (Bot.SeenEntity entity : bot.entities()) {
            if (entity.type().equals("minecraft:interaction") && Math.abs(entity.x() - x) < 0.6 && Math.abs(entity.z() - z) < 0.6) {
                return entity;
            }
        }
        return null;
    }

    private static boolean seen(Bot bot, int entityId) {
        return bot.entities().stream().anyMatch(entity -> entity.id() == entityId);
    }

    private static String board(E2E e2e, String base) {
        return e2e.name(base).toLowerCase(Locale.ROOT);
    }

    private static String world(E2E e2e, String player) {
        return e2e.onPlayer(player, () -> e2e.player(player).getWorld().getName());
    }

    private static String at(String world, double x, double y, double z) {
        return world + " " + String.format(Locale.ROOT, "%.2f %.2f %.2f", x, y, z);
    }

    // ------------------------------------------------------------------ scenarios

    /** An admin places the welcome board where they stand; a client near it gets the entity with quiet looks. */
    static void text(E2E e2e) {
        String name = e2e.name("Displayer");
        String board = board(e2e, "dtext");
        Bot bot = e2e.bot(name);
        e2e.console("op " + name);
        try {
            double x = bot.x();
            double z = bot.z();
            e2e.step("/displays create places the board at the admin's feet");
            bot.clearLogs();
            bot.command("displays create " + board + " welcome");
            e2e.eventually(() -> bot.actionBarContains("Placed " + board), "a placed message on the action bar: " + bot.actionBar());
            e2e.eventually(() -> textDisplay(bot, x, z, "SIFTVANILLA") != null,
                "the bot receives a text display with the welcome text: " + bot.entities());
            Bot.SeenEntity display = textDisplay(bot, x, z, "SIFTVANILLA");
            e2e.log("text display " + display.id() + " at " + display.x() + " " + display.y() + " " + display.z() + ": " + text(display));

            e2e.step("the metadata is the look from displays.yml: upright, no background, shadow, full bright");
            Object background = display.data().get(dataId(Display.TextDisplay.class, "DATA_BACKGROUND_COLOR_ID"));
            e2e.expect(Integer.valueOf(0).equals(background), "no background (0), got " + background);
            Object billboard = display.data().get(dataId(Display.class, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID"));
            // Billboard ids are continuous from 0 (fixed, vertical, horizontal, center), so vertical is its ordinal:
            // the board turns to face players but always stands upright.
            e2e.expect(Byte.valueOf((byte) Display.BillboardConstraints.VERTICAL.ordinal()).equals(billboard), "vertical billboard, got " + billboard);
            Object flags = display.data().get(dataId(Display.TextDisplay.class, "DATA_STYLE_FLAGS_ID"));
            e2e.expect(Byte.valueOf(Display.TextDisplay.FLAG_SHADOW).equals(flags), "only the shadow flag (not see-through, no default background), got " + flags);
            Object brightness = display.data().get(dataId(Display.class, "DATA_BRIGHTNESS_OVERRIDE_ID"));
            e2e.expect(Integer.valueOf(15 << 4 | 15 << 20).equals(brightness), "full brightness, got " + brightness);
            Object lineWidth = display.data().get(dataId(Display.TextDisplay.class, "DATA_LINE_WIDTH_ID"));
            e2e.expect(lineWidth == null || Integer.valueOf(200).equals(lineWidth),
                "the default line width (200, the vanilla default, so usually not sent), got " + lineWidth);
            e2e.expect(interaction(bot, x, z) == null, "no click box over a board without a command");

            e2e.step("a second create with the same name is refused");
            bot.clearLogs();
            bot.command("displays create " + board + " welcome");
            e2e.eventually(() -> bot.actionBarContains("already a display called " + board), "a refusal: " + bot.actionBar());

            e2e.step("deleting it removes the entity from the client");
            int id = display.id();
            e2e.console("displays delete " + board + " confirm");
            e2e.eventually(() -> !seen(bot, id), "the text display is removed for the client");
        } finally {
            e2e.console("displays delete " + board + " confirm");
            e2e.console("deop " + name);
        }
    }

    /** Right-clicking a leaderboard's click box opens the leaderboard dialog; spam clicks are ignored. */
    static void click(E2E e2e) {
        String name = e2e.name("Clicker");
        String board = board(e2e, "dclick");
        String kills = board(e2e, "dkills");
        Bot bot = e2e.bot(name);
        String world = world(e2e, name);
        double x = bot.x() + 2;
        double z = bot.z();
        double killsX = bot.x() - 2;
        try {
            e2e.step("a leaderboard is placed next to the player from the console");
            e2e.console("displays create " + board + " richest " + at(world, x, bot.y(), z));
            e2e.eventually(() -> textDisplay(bot, x, z, "Richest players") != null, "the leaderboard text arrives: " + bot.entities());
            e2e.eventually(() -> interaction(bot, x, z) != null, "a click box arrives over it");
            Bot.SeenEntity box = interaction(bot, x, z);
            e2e.log("click box " + box.id() + " data " + box.data());

            e2e.step("a right click opens the richest players dialog");
            bot.clearLogs();
            bot.interact(box.id());
            e2e.dialog(bot, "Richest players");
            int shown = bot.dialogs().size();

            e2e.step("an immediate second click is ignored by the cooldown");
            bot.interact(box.id());
            e2e.sleep(600);
            e2e.expect(bot.dialogs().size() == shown, "no second dialog within the cooldown, saw " + bot.dialogs().size());

            e2e.step("after the cooldown a click works again");
            e2e.sleep(700);
            bot.interact(box.id());
            e2e.eventually(() -> bot.dialogs().size() > shown, "the dialog opens again after the cooldown");
            e2e.expect(!bot.disconnected(), "the player stays connected");

            e2e.step("a stats leaderboard shows its placeholders and opens /top kills");
            e2e.console("displays create " + kills + " top-kills " + at(world, killsX, bot.y(), z));
            e2e.eventually(() -> textDisplay(bot, killsX, z, "Top killers") != null, "the top killers text arrives: " + bot.entities());
            String text = text(textDisplay(bot, killsX, z, "Top killers"));
            e2e.expect(!text.contains("{"), "every placeholder was replaced: " + text);
            e2e.expect(text.contains("1. ") && text.contains("10. "), "ten places are shown: " + text);
            e2e.eventually(() -> interaction(bot, killsX, z) != null, "a click box arrives over it");
            e2e.sleep(1100);
            bot.interact(interaction(bot, killsX, z).id());
            e2e.dialog(bot, "Most kills");
        } finally {
            e2e.console("displays delete " + board + " confirm");
            e2e.console("displays delete " + kills + " confirm");
        }
    }

    /** /displays list teleports to a display, /displays move respawns it elsewhere, /displays delete asks first. */
    static void admin(E2E e2e) {
        String name = e2e.name("Placer");
        String board = board(e2e, "dadmin");
        Bot bot = e2e.bot(name);
        e2e.console("op " + name);
        String world = world(e2e, name);
        double startX = bot.x();
        double boardX = startX + 6;
        double y = bot.y();
        double z = bot.z();
        try {
            e2e.console("displays create " + board + " welcome " + at(world, boardX, y, z));
            e2e.eventually(() -> textDisplay(bot, boardX, z, "SIFTVANILLA") != null, "the board arrives");
            int first = textDisplay(bot, boardX, z, "SIFTVANILLA").id();

            e2e.step("the list dialog shows the board and teleports to it");
            bot.command("displays list");
            Bot.SeenDialog list = e2e.dialog(bot, "Displays");
            e2e.expect(list.bodyText().contains(board + ", template welcome"), "the board is listed: " + list.body());
            e2e.expect(list.bodyText().contains("shown"), "it is shown: " + list.body());
            e2e.click(bot, "Go to " + board);
            e2e.eventually(() -> Math.abs(bot.x() - boardX) < 0.6 && Math.abs(bot.z() - z) < 0.6,
                "the bot was teleported to the board (now at " + bot.x() + " " + bot.z() + ")");

            e2e.step("/displays move puts it where the admin stands now");
            e2e.console("tp " + name + " " + String.format(Locale.ROOT, "%.2f %.2f %.2f", startX, y, z));
            e2e.eventually(() -> Math.abs(bot.x() - startX) < 0.6, "the bot is back at its start (now at " + bot.x() + ")");
            double movedX = bot.x();
            e2e.sleep(300);
            bot.clearLogs();
            bot.command("displays move " + board);
            e2e.eventually(() -> bot.actionBarContains("Moved " + board), "a moved message: " + bot.actionBar());
            e2e.eventually(() -> !seen(bot, first), "the old entity is removed");
            e2e.eventually(() -> textDisplay(bot, movedX, z, "SIFTVANILLA") != null, "a new entity stands at the new place: " + bot.entities());

            e2e.step("/displays delete asks for confirmation, cancel keeps it");
            int moved = textDisplay(bot, movedX, z, "SIFTVANILLA").id();
            bot.command("displays delete " + board);
            Bot.SeenDialog confirm = e2e.dialog(bot, "Delete display");
            e2e.expect(confirm.bodyText().contains("Delete " + board + "?"), "the question names the board: " + confirm.body());
            e2e.click(bot, "Cancel");
            e2e.sleep(500);
            e2e.expect(seen(bot, moved), "cancel keeps the board");

            e2e.step("confirming deletes it, a replayed click does nothing");
            bot.command("displays delete " + board);
            Bot.SeenDialog again = e2e.dialog(bot, "Delete display");
            String deleteId = again.button("Delete").actionId();
            e2e.click(bot, "Delete");
            e2e.eventually(() -> !seen(bot, moved), "the board is removed for the client");
            bot.rawClick(deleteId, new CompoundTag());
            e2e.sleep(500);
            e2e.expect(!bot.disconnected(), "the replay is ignored");

            e2e.step("players without the permission cannot use /displays");
            e2e.console("deop " + name);
            bot.clearLogs();
            bot.command("displays list");
            e2e.sleep(800);
            e2e.expect(bot.dialog() == null || !bot.dialog().title().equals("Displays"), "no list dialog without permission");
        } finally {
            e2e.console("displays delete " + board + " confirm");
            e2e.console("deop " + name);
        }
    }

    // ------------------------------------------------------------------ the player's switch

    /**
     * Spawn holograms off (show-spawn-holograms): a player who turns it off in the Display settings loses every display
     * and its click box at once while others keep them, gets none placed meanwhile or after rejoining, and sees them
     * all again when it is back on ({@code /settings display show-spawn-holograms on}).
     */
    static void hide(E2E e2e) {
        String name = e2e.name("Holo");
        String otherName = e2e.name("HoloSee");
        String board = board(e2e, "dhide");
        String second = board(e2e, "dhide2");
        Bot bot = e2e.bot(name);
        Bot other = e2e.bot(otherName);
        UUID id = e2e.uuid(name);
        e2e.console("tp " + otherName + " " + name);
        String world = world(e2e, name);
        double x = bot.x() + 2;
        double secondX = bot.x() - 2;
        double y = bot.y();
        double z = bot.z();
        try {
            e2e.step("a leaderboard with a click box arrives for both players");
            e2e.console("displays create " + board + " richest " + at(world, x, y, z));
            e2e.eventually(() -> textDisplay(bot, x, z, "Richest players") != null && interaction(bot, x, z) != null,
                "the text and the click box arrive: " + bot.entities());
            e2e.eventually(() -> textDisplay(other, x, z, "Richest players") != null, "the other player gets it too: " + other.entities());
            int text = textDisplay(bot, x, z, "Richest players").id();
            int box = interaction(bot, x, z).id();

            e2e.step("turning Spawn holograms off in the Display settings removes both entities at once");
            AfkStaffSettingSteps.openGroup(e2e, bot, "display", "Display settings");
            Bot.SeenDialog page = AfkStaffSettingSteps.form(e2e, bot);
            e2e.expect("toggle".equals(page.inputs().get("show_spawn_holograms")), "the switch: " + page.inputs());
            e2e.expect(Boolean.TRUE.equals(page.toggleValue("show_spawn_holograms")), "on by default");
            e2e.expect(page.button("Spawn holograms: ON") != null, "its button: " + page.buttons());
            Map<String, Object> values = page.values();
            values.put("show_spawn_holograms", false);
            bot.clearMessages();
            SettingsSteps.applyChanged(e2e, bot, page, values);
            e2e.expect(bot.dialog().button("Spawn holograms: OFF") != null, "the button shows OFF: " + bot.dialog().buttons());
            e2e.eventually(() -> !seen(bot, text) && !seen(bot, box), "the text display and the click box are removed for the client");
            e2e.sleep(1_000);
            e2e.expect(textDisplay(other, x, z, "Richest players") != null, "the other player keeps it");
            e2e.expect("false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-spawn-holograms")), "the choice is stored");

            e2e.step("a display placed meanwhile never reaches them");
            e2e.console("displays create " + second + " welcome " + at(world, secondX, y, z));
            e2e.eventually(() -> textDisplay(other, secondX, z, "SIFTVANILLA") != null, "the other player gets the new board: " + other.entities());
            e2e.sleep(1_000);
            e2e.expect(textDisplay(bot, secondX, z, "SIFTVANILLA") == null, "the player who hides them doesn't: " + bot.entities());
            e2e.expect(textDisplay(bot, x, z, "Richest players") == null && interaction(bot, x, z) == null, "nor the first one");

            e2e.step("after rejoining they still get none");
            bot.quit();
            e2e.eventually(() -> Bukkit.getPlayerExact(name) == null, name + " left");
            e2e.sleep(300);
            Bot again = e2e.bot(name);
            e2e.console("tp " + name + " " + otherName);
            e2e.eventually(() -> Math.abs(again.x() - other.x()) < 1 && Math.abs(again.z() - other.z()) < 1, "back next to the boards");
            e2e.sleep(2_000);
            e2e.expect(textDisplay(again, x, z, "Richest players") == null && interaction(again, x, z) == null
                && textDisplay(again, secondX, z, "SIFTVANILLA") == null, "no display after rejoining: " + again.entities());

            e2e.step("on again with the settings command (/settings display show-spawn-holograms on) shows every display at once");
            again.clearLogs();
            again.command("settings display show-spawn-holograms on");
            e2e.eventually(() -> again.anyFeedbackContains("Spawn holograms turned on"), "confirmed: " + again.actionBar() + " " + again.chat());
            e2e.eventually(() -> textDisplay(again, x, z, "Richest players") != null && interaction(again, x, z) != null
                && textDisplay(again, secondX, z, "SIFTVANILLA") != null, "both boards and the click box are back: " + again.entities());
            e2e.eventually(() -> AfkStaffSettingSteps.stored(e2e, id, "show-spawn-holograms") == null, "no row for the default");
        } finally {
            e2e.console("displays delete " + board + " confirm");
            e2e.console("displays delete " + second + " confirm");
        }
    }

    // ------------------------------------------------------------------ the Display group

    /** The line in features/cosmetics.yml that turns kill effects on (the shipped text). */
    private static final String KILL_EFFECTS_ON = "protected spawn area, and players who turned kill effects off in /settings don't see them.\n"
        + "  enabled: true";

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
            e2e.console("sift reload");
            body.run(e2e);
        } finally {
            Files.writeString(path, original, StandardCharsets.UTF_8);
            e2e.console("sift reload");
        }
    }

    /**
     * The Display settings group: the kill effect switch (cosmetics' show-kill-effects, placed by the display package)
     * comes after the sidebar settings and the spawn hologram switch, is changed with {@code /settings display
     * show-kill-effects off}, and is not offered while features/cosmetics.yml turns kill effects off, so it never does
     * nothing; the stored choice is kept for when they come back.
     */
    static void group(E2E e2e) throws Exception {
        String name = e2e.name("DispSet");
        Bot bot = e2e.bot(name);
        UUID id = e2e.uuid(name);
        var effects = e2e.services().settings().registry().entry(CosmeticsFeature.KILL_EFFECTS.id());
        e2e.expect(effects != null && "display".equals(effects.category().id()) && effects.options().order() == 6,
            "show-kill-effects is the 6th Display setting: " + effects);
        e2e.expect(effects.offered(), "offered while kill effects play");

        e2e.step("the Display group lists the kill effect switch after the display package's own settings");
        List<String> keys = List.copyOf(AfkStaffSettingSteps.groupInputs(e2e, bot, "display", "Display settings").keySet());
        e2e.expect(keys.contains("show_kill_effects"), "the switch is listed: " + keys);
        for (String before : List.of("scoreboard", "feedback_channel", "sidebar_layout", "show_spawn_holograms")) {
            e2e.expect(!keys.contains(before) || keys.indexOf(before) < keys.indexOf("show_kill_effects"),
                before + " comes first: " + keys);
        }

        e2e.step("/settings display show-kill-effects off stores it");
        bot.clearLogs();
        bot.command("settings display show-kill-effects off");
        e2e.eventually(() -> "false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-kill-effects")),
            "stored off (chat " + bot.chat() + ", action bar " + bot.actionBar() + ")");
        e2e.eventually(() -> bot.anyFeedbackContains("turned off"), "confirmed: " + bot.actionBar() + " " + bot.chat());
        e2e.expect(!e2e.services().settings().get(id, CosmeticsFeature.KILL_EFFECTS), "the cosmetics feature reads off");

        e2e.step("with kill effects turned off on the server the switch is not offered and the choice is kept");
        withFile(e2e, "features/cosmetics.yml", Map.of(KILL_EFFECTS_ON, KILL_EFFECTS_ON.replace("enabled: true", "enabled: false")), x -> {
            e2e.eventually(() -> !effects.offered(), "not offered without kill effects");
            List<String> without = List.copyOf(AfkStaffSettingSteps.groupInputs(e2e, bot, "display", "Display settings").keySet());
            e2e.expect(!without.contains("show_kill_effects"), "not in the Display group: " + without);
            bot.clearLogs();
            bot.command("settings display show-kill-effects on");
            e2e.sleep(1_000);
            e2e.expect("false".equals(AfkStaffSettingSteps.stored(e2e, id, "show-kill-effects")),
                "a switch that is not offered can't be changed: " + bot.chat() + " " + bot.actionBar());
        });
        e2e.eventually(effects::offered, "offered again once kill effects are back");

        e2e.step("on again deletes the row");
        SetResult on = e2e.services().settings().set(id, CosmeticsFeature.KILL_EFFECTS, true, Change.api("e2e"));
        e2e.expect(on == SetResult.CHANGED, "changed: " + on);
        e2e.eventually(() -> AfkStaffSettingSteps.stored(e2e, id, "show-kill-effects") == null, "no row for the default");
    }
}
