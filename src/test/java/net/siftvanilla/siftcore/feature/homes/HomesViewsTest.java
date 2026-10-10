package net.siftvanilla.siftcore.feature.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.player.Limits;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.testing.MergedLang;
import net.siftvanilla.siftcore.ui.dialog.Body;
import net.siftvanilla.siftcore.ui.dialog.Button;
import net.siftvanilla.siftcore.ui.dialog.Templates;
import net.siftvanilla.siftcore.ui.dialog.View;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The homes list: one status line, every home as a button (no pages), positions in tooltips, a red Delete. */
class HomesViewsTest {

    private static Lang lang;
    private static Templates templates;

    @BeforeAll
    static void load() {
        lang = MergedLang.of(List.of("lang/core.yml", "lang/homes.yml"), CoreMessages.class, HomesMessages.class);
        templates = new Templates(lang);
    }

    private static String plain(Component text) {
        return text == null ? "" : TextStyle.plain(text);
    }

    private static List<Home> homes(int count) {
        List<Home> homes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            homes.add(new Home("home" + i, "world", 100 + i, 64, -20, 0, 0, i));
        }
        return homes;
    }

    /** World names as players see them, with "world" as the server's main world. */
    private static String worldName(String world) {
        return HomesViews.worldName(lang, world, "world");
    }

    private static HomesViews.Actions actions(List<String> clicks) {
        return new HomesViews.Actions(home -> s -> clicks.add("go " + home.name()), home -> s -> clicks.add("delete " + home.name()),
            s -> clicks.add("set"), s -> clicks.add("back"));
    }

    private static String body(View view) {
        return view.body().stream().map(element -> element instanceof Body.Text text ? plain(text.text()) : "")
            .reduce("", (a, b) -> a + b);
    }

    @Test
    void everyHomeIsAButtonWithNoPages() {
        List<String> clicks = new ArrayList<>();
        View view = HomesViews.list(lang, templates, homes(12), 15, false, HomesViewsTest::worldName, actions(clicks));
        assertTrue(body(view).strip().endsWith(" 12 of 15 homes"), "one status line only: " + body(view));
        List<String> labels = view.allButtons().stream().map(button -> plain(button.label())).toList();
        assertEquals(12 * 2 + 2, labels.size(), "a home and its Delete for each, Set a home here, Back: " + labels);
        assertTrue(labels.stream().noneMatch(label -> label.contains("page")), labels.toString());
        Button first = view.buttons().get(0);
        assertEquals("home0", plain(first.label()));
        assertTrue(plain(first.tooltip()).contains("Overworld 100, 64, -20"), plain(first.tooltip()));
        assertTrue(plain(first.tooltip()).contains("Click to teleport to home0."), plain(first.tooltip()));
        Button delete = view.buttons().get(1);
        assertEquals("Delete", plain(delete.label()));
        assertEquals(Palette.DEFAULT_ERROR, delete.label().color(), "Delete is red");
        first.handler().handle(null);
        delete.handler().handle(null);
        view.buttons().getLast().handler().handle(null);
        view.exit().handler().handle(null);
        assertEquals(List.of("go home0", "delete home0", "set", "back"), clicks);
        assertEquals("Set a home here", plain(view.buttons().getLast().label()));
    }

    /** The colour of the first text part containing {@code text}, inherited colours included, or null. */
    private static net.kyori.adventure.text.format.TextColor colourOf(Component component, String text,
                                                                     net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor colour = component.color() != null ? component.color() : inherited;
        if (component instanceof net.kyori.adventure.text.TextComponent part && part.content().contains(text)) {
            return colour;
        }
        for (Component child : component.children()) {
            net.kyori.adventure.text.format.TextColor found = colourOf(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void valuesAreInTheAccentColour() {
        View view = HomesViews.list(lang, templates, homes(2), 5, false, HomesViewsTest::worldName, actions(new ArrayList<>()));
        Component header = ((Body.Text) view.body().getFirst()).text();
        assertEquals(Palette.DEFAULT_ACCENT, colourOf(header, "2", null), "the count: " + header);
        assertEquals(Palette.DEFAULT_ACCENT, colourOf(header, "5", null), "the limit: " + header);
        Component tooltip = view.buttons().getFirst().tooltip();
        assertEquals(Palette.DEFAULT_ACCENT, colourOf(tooltip, "100", null), "the position: " + tooltip);
        assertEquals(Palette.DEFAULT_ACCENT, colourOf(tooltip, "home0", null), "the name in the tooltip: " + tooltip);
        assertEquals(Palette.DEFAULT_ON, view.buttons().getLast().label().color(), "Set a home here is green");
    }

    @Test
    void streamerModeHidesPositionsAndUnlimitedReads() {
        View view = HomesViews.list(lang, templates, homes(1), Limits.UNLIMITED, true, HomesViewsTest::worldName, actions(new ArrayList<>()));
        assertTrue(body(view).strip().endsWith(" 1 of unlimited homes"), body(view));
        String tooltip = plain(view.buttons().getFirst().tooltip());
        assertTrue(tooltip.contains("Overworld") && !tooltip.contains("100"), tooltip);
    }

    /** Players read Overworld, Nether and The End for the server's three worlds, never their folder names. */
    @Test
    void worldsAreNamedTheWayPlayersKnowThem() {
        assertEquals("Overworld", HomesViews.worldName(lang, "world", "world"));
        assertEquals("Nether", HomesViews.worldName(lang, "world_nether", "world"));
        assertEquals("The End", HomesViews.worldName(lang, "world_the_end", "world"));
        assertEquals("resources", HomesViews.worldName(lang, "resources", "world"), "another world keeps its own name");
        assertEquals("Overworld", HomesViews.worldName(lang, "smp", "smp"), "whatever the main world is called");
        assertEquals("Nether", HomesViews.worldName(lang, "smp_nether", "smp"));
        assertEquals("world_nether", HomesViews.worldName(lang, "world_nether", "smp"), "only the main world's nether");
        assertEquals("world", HomesViews.worldName(lang, "world", null), "no worlds loaded: the name as it is");
        List<Home> homes = List.of(new Home("hell", "world_nether", 8, 70, 8, 0, 0, 1));
        View view = HomesViews.list(lang, templates, homes, 3, false, HomesViewsTest::worldName, actions(new ArrayList<>()));
        assertTrue(plain(view.buttons().getFirst().tooltip()).contains("Nether 8, 70, 8"), plain(view.buttons().getFirst().tooltip()));
    }

    @Test
    void staffSeeAnotherPlayersHomesWithPositions() {
        View view = HomesViews.other(lang, templates, "Alex", homes(2), HomesViewsTest::worldName,
            new HomesViews.Actions(home -> s -> { }, home -> s -> { }, null, null));
        assertEquals("Homes of Alex", plain(view.title()));
        assertTrue(body(view).strip().endsWith(" Alex has 2 homes"), body(view));
        assertTrue(plain(view.buttons().getFirst().tooltip()).contains("100, 64, -20"));
        assertEquals(4, view.buttons().size(), "no Set a home here for someone else");
        assertEquals("Close", plain(view.exit().label()));
    }
}
