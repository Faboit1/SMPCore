package net.siftvanilla.siftcore.feature.homes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;

/**
 * The homes list in the dialog style: one short status line ("2 of 3 homes"), then each home as a button that
 * teleports there (its world and position in the tooltip, or only the world in streamer mode) next to a red Delete
 * button, all in one scrolling dialog with no pages. Built fresh on every open from what the player has now.
 */
final class HomesViews {

    private HomesViews() {
    }

    /** What the list's buttons do. */
    record Actions(Function<Home, Button.Handler> teleport, Function<Home, Button.Handler> delete, Button.Handler setHere,
                   Button.Handler back) {
    }

    /**
     * A player's own homes.
     *
     * @param limit     how many homes they may have ({@link Limits#UNLIMITED} for no limit)
     * @param hidden    whether they hide coordinates (streamer mode): tooltips then name the world only
     * @param worldName how a world is named to players ({@link #worldName})
     */
    static View list(Lang lang, Templates templates, List<Home> homes, int limit, boolean hidden, UnaryOperator<String> worldName,
                     Actions actions) {
        List<Component> lines = List.of(lang.get(HomesMessages.LIST_HEADER, accent("count", homes.size()), limit(lang, limit)));
        List<Button> buttons = buttons(lang, homes, hidden, worldName, actions);
        buttons.add(Button.of(lang.get(HomesMessages.LIST_SET_HERE), lang.get(HomesMessages.LIST_SET_HERE_TOOLTIP), actions.setHere()));
        return templates.grid(lang.get(HomesMessages.LIST_TITLE), lines, buttons, actions.back());
    }

    /** Another player's homes, for staff: always with their positions. */
    static View other(Lang lang, Templates templates, String owner, List<Home> homes, UnaryOperator<String> worldName, Actions actions) {
        List<Component> lines = List.of(lang.get(HomesMessages.OTHER_HEADER, Arg.text("name", owner), accent("count", homes.size())));
        return templates.grid(lang.get(HomesMessages.OTHER_TITLE, Arg.text("name", owner)), lines,
            buttons(lang, homes, false, worldName, actions), actions.back());
    }

    private static List<Button> buttons(Lang lang, List<Home> homes, boolean hidden, UnaryOperator<String> worldName, Actions actions) {
        List<Button> buttons = new ArrayList<>(homes.size() * 2 + 1);
        for (Home home : homes) {
            Arg name = Arg.text("name", home.name());
            List<Component> tooltip = List.of(where(lang, home, hidden, worldName), lang.get(HomesMessages.LIST_TELEPORT_TOOLTIP, name));
            // Teleporting finishes with the list: the window closes as soon as a home is picked.
            buttons.add(Button.of(lang.get(HomesMessages.LIST_HOME, name), Templates.lines(tooltip), actions.teleport().apply(home))
                .closes());
            buttons.add(Button.of(lang.get(HomesMessages.LIST_DELETE), lang.get(HomesMessages.LIST_DELETE_TOOLTIP, name),
                actions.delete().apply(home)));
        }
        return buttons;
    }

    /** Where a home is: world and block position, or the world only while coordinates are hidden. */
    static Component where(Lang lang, Home home, boolean hidden, UnaryOperator<String> worldName) {
        if (hidden) {
            return lang.get(HomesMessages.LIST_WHERE_HIDDEN, Arg.text("world", worldName.apply(home.world())));
        }
        return lang.get(HomesMessages.LIST_WHERE, at(home, worldName));
    }

    /** A home's world (as players know it) and block position as placeholders: {@code world}, {@code x}, {@code y}, {@code z}. */
    static Arg[] at(Home home, UnaryOperator<String> worldName) {
        return at(worldName.apply(home.world()), home.blockX(), home.blockY(), home.blockZ());
    }

    /**
     * How a world is named to players: the server's main world is "Overworld", its nether "Nether" and its end
     * "The End" (lang {@code homes.worlds}); any other world shows its own name.
     *
     * @param mainWorld the name of the server's main world (the first one loaded), or null when unknown
     */
    static String worldName(Lang lang, String world, String mainWorld) {
        MessageKey key = null;
        if (mainWorld != null) {
            if (world.equals(mainWorld)) {
                key = HomesMessages.WORLD_OVERWORLD;
            } else if (world.equals(mainWorld + "_nether")) {
                key = HomesMessages.WORLD_NETHER;
            } else if (world.equals(mainWorld + "_the_end")) {
                key = HomesMessages.WORLD_END;
            }
        }
        return key == null ? world : lang.plain(key);
    }

    /**
     * A world and a block position as placeholders: {@code world}, {@code x}, {@code y} and {@code z}. The numbers are
     * text, so the lang file colours them ({@code <accent><x>, <y>, <z>}).
     */
    static Arg[] at(String world, int x, int y, int z) {
        return new Arg[] {Arg.text("world", world), Arg.text("x", Lang.number(x)), Arg.text("y", Lang.number(y)),
            Arg.text("z", Lang.number(z))};
    }

    /** {@code first} followed by {@code rest}. */
    static Arg[] with(Arg first, Arg... rest) {
        Arg[] all = new Arg[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** The home limit as a value: a number, or "unlimited". */
    static Arg limit(Lang lang, int limit) {
        return Arg.text("limit", limit == Limits.UNLIMITED ? lang.plain(HomesMessages.UNLIMITED) : Lang.number(limit));
    }

    /** A number written as text, so the lang file can colour it ({@code <accent><count></accent>}). */
    private static Arg accent(String name, long value) {
        return Arg.text(name, Lang.number(value));
    }
}
