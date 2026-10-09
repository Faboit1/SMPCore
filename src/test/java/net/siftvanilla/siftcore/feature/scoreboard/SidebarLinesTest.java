package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** Line diffing: only lines whose text changed are sent, hidden lines are removed, scores order the lines. */
class SidebarLinesTest {

    private static List<Component> lines(String... texts) {
        List<Component> list = new ArrayList<>(texts.length);
        for (String text : texts) {
            list.add(text == null ? null : Component.text(text));
        }
        return list;
    }

    @Test
    void theFirstUpdateSendsEveryShownLine() {
        SidebarLines sidebar = new SidebarLines(4);
        List<SidebarLines.Change> changes = sidebar.update(Arrays.asList(Component.empty(), Component.text("Money $0"), null,
            Component.text("siftvanilla.com")));
        assertEquals(List.of(0, 1, 3), changes.stream().map(SidebarLines.Change::slot).toList(), "the hidden slot is not sent");
        assertEquals(List.of(Component.empty(), Component.text("Money $0"), Component.text("siftvanilla.com")), sidebar.visible());
    }

    @Test
    void anUnchangedRefreshSendsNothing() {
        SidebarLines sidebar = new SidebarLines(3);
        sidebar.update(lines("a", "b", "c"));
        assertTrue(sidebar.update(lines("a", "b", "c")).isEmpty());
    }

    @Test
    void onlyTheChangedLineIsSent() {
        SidebarLines sidebar = new SidebarLines(3);
        sidebar.update(lines("Money $0", "Kills 0", "x"));
        List<SidebarLines.Change> changes = sidebar.update(lines("Money $1,500", "Kills 0", "x"));
        assertEquals(1, changes.size());
        assertEquals(0, changes.getFirst().slot());
        assertEquals(Component.text("Money $1,500"), changes.getFirst().text());
    }

    @Test
    void hidingAndShowingALine() {
        SidebarLines sidebar = new SidebarLines(2);
        sidebar.update(lines("Team Alpha", "x"));
        List<SidebarLines.Change> hide = sidebar.update(lines(null, "x"));
        assertEquals(List.of(new SidebarLines.Change(0, null)), hide);
        assertNull(sidebar.shown(0));
        assertTrue(sidebar.update(lines(null, "x")).isEmpty(), "a hidden line stays hidden without changes");
        List<SidebarLines.Change> show = sidebar.update(lines("Team Beta", "x"));
        assertEquals(List.of(new SidebarLines.Change(0, Component.text("Team Beta"))), show);
    }

    @Test
    void stylesCountAsChanges() {
        SidebarLines sidebar = new SidebarLines(1);
        sidebar.update(List.of(Component.text("x")));
        assertEquals(1, sidebar.update(List.of(Component.text("x", net.kyori.adventure.text.format.NamedTextColor.GRAY))).size());
    }

    @Test
    void scoresGoDownFromTheTopAndEntriesAreUniqueNonNames() {
        SidebarLines sidebar = new SidebarLines(SidebarLines.MAX_LINES);
        Set<String> entries = new HashSet<>();
        for (int slot = 0; slot < SidebarLines.MAX_LINES; slot++) {
            String entry = SidebarLines.entry(slot);
            assertTrue(entries.add(entry), "entry " + entry + " is unique");
            assertTrue(!entry.matches("[A-Za-z0-9_]{3,16}"), "entry " + entry + " can never be a player name");
            if (slot > 0) {
                assertTrue(sidebar.score(slot) < sidebar.score(slot - 1), "slot " + slot + " is below slot " + (slot - 1));
            }
            assertTrue(sidebar.score(slot) > 0);
        }
    }

    @Test
    void sizesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new SidebarLines(SidebarLines.MAX_LINES + 1));
        SidebarLines sidebar = new SidebarLines(2);
        assertThrows(IllegalArgumentException.class, () -> sidebar.update(lines("only one")));
        assertEquals(0, new SidebarLines(0).update(List.of()).size());
    }
}
