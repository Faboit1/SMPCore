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
        View view = HomesViews.list(lang, templates, homes(12), 15, false, actions(clicks));
        assertTrue(body(view).strip().endsWith(" 12 of 15 homes"), "one status line only: " + body(view));
        List<String> labels = view.allButtons().stream().map(button -> plain(button.label())).toList();
        assertEquals(12 * 2 + 2, labels.size(), "a home and its Delete for each, Set a home here, Back: " + labels);
        assertTrue(labels.stream().noneMatch(label -> label.contains("page")), labels.toString());
        Button first = view.buttons().get(0);
        assertEquals("home0", plain(first.label()));
        assertTrue(plain(first.tooltip()).contains("world 100, 64, -20"), plain(first.tooltip()));
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

    @Test
    void streamerModeHidesPositionsAndUnlimitedReads() {
        View view = HomesViews.list(lang, templates, homes(1), Limits.UNLIMITED, true, actions(new ArrayList<>()));
        assertTrue(body(view).strip().endsWith(" 1 of unlimited homes"), body(view));
        String tooltip = plain(view.buttons().getFirst().tooltip());
        assertTrue(tooltip.contains("world") && !tooltip.contains("100"), tooltip);
    }

    @Test
    void staffSeeAnotherPlayersHomesWithPositions() {
        View view = HomesViews.other(lang, templates, "Alex", homes(2), new HomesViews.Actions(home -> s -> { }, home -> s -> { }, null, null));
        assertEquals("Homes of Alex", plain(view.title()));
        assertTrue(body(view).strip().endsWith(" Alex has 2 homes"), body(view));
        assertTrue(plain(view.buttons().getFirst().tooltip()).contains("100, 64, -20"));
        assertEquals(4, view.buttons().size(), "no Set a home here for someone else");
        assertEquals("Close", plain(view.exit().label()));
    }
}
