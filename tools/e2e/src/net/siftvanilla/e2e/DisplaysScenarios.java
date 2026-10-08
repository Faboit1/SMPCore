package net.siftvanilla.e2e;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;

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
            e2e.eventually(() -> textDisplay(bot, x, z, "Welcome to SiftVanilla") != null,
                "the bot receives a text display with the welcome text: " + bot.entities());
            Bot.SeenEntity display = textDisplay(bot, x, z, "Welcome to SiftVanilla");
            e2e.log("text display " + display.id() + " at " + display.x() + " " + display.y() + " " + display.z() + ": " + text(display));

            e2e.step("the metadata is the quiet look from displays.yml");
            Object background = display.data().get(dataId(Display.TextDisplay.class, "DATA_BACKGROUND_COLOR_ID"));
            e2e.expect(Integer.valueOf(0).equals(background), "no background (0), got " + background);
            Object billboard = display.data().get(dataId(Display.class, "DATA_BILLBOARD_RENDER_CONSTRAINTS_ID"));
            // Billboard ids are continuous from 0 (fixed, vertical, horizontal, center), so center is its ordinal.
            e2e.expect(Byte.valueOf((byte) Display.BillboardConstraints.CENTER.ordinal()).equals(billboard), "center billboard, got " + billboard);
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
            e2e.eventually(() -> textDisplay(bot, boardX, z, "Welcome") != null, "the board arrives");
            int first = textDisplay(bot, boardX, z, "Welcome").id();

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
            e2e.eventually(() -> textDisplay(bot, movedX, z, "Welcome") != null, "a new entity stands at the new place: " + bot.entities());

            e2e.step("/displays delete asks for confirmation, cancel keeps it");
            int moved = textDisplay(bot, movedX, z, "Welcome").id();
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
}
