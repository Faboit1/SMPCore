package net.siftvanilla.siftcore.feature.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class ReaderTextTest {

    private static final List<String> ALEX = List.of("Alex", "NightRider");

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** The texts of every part that is decorated (directly or through a parent). */
    private static List<String> decorated(Component component, TextDecoration decoration) {
        List<String> found = new ArrayList<>();
        collect(component, decoration, false, found);
        return found;
    }

    private static void collect(Component component, TextDecoration decoration, boolean inherited, List<String> found) {
        TextDecoration.State state = component.decoration(decoration);
        boolean on = state == TextDecoration.State.TRUE || (state == TextDecoration.State.NOT_SET && inherited);
        if (on && component instanceof TextComponent text && !text.content().isEmpty()) {
            found.add(text.content());
        }
        for (Component child : component.children()) {
            collect(child, decoration, on, found);
        }
    }

    @Test
    void theReadersNamesAreDecoratedAndTheTextStaysTheSame() {
        Component message = Component.text("hey @alex and nightrider, alexander is here");
        Component bold = ReaderText.highlight(message, ALEX, true, 3, TextDecoration.BOLD);
        assertEquals(plain(message), plain(bold));
        assertEquals(List.of("@alex", "nightrider"), decorated(bold, TextDecoration.BOLD), "whole names only, the @ included");
        Component underlined = ReaderText.highlight(message, ALEX, false, 3, TextDecoration.UNDERLINED);
        assertEquals(List.of("@alex"), decorated(underlined, TextDecoration.UNDERLINED), "bare names only when they count");
    }

    @Test
    void nothingToHighlightKeepsTheMessage() {
        Component message = Component.text("nobody here");
        assertSame(message, ReaderText.highlight(message, ALEX, true, 3, TextDecoration.BOLD));
        Component off = Component.text("hi alex");
        assertSame(off, ReaderText.highlight(off, ALEX, true, 3, null), "no decoration: unchanged");
        assertSame(off, ReaderText.highlight(off, List.of(), true, 3, TextDecoration.BOLD), "no names: unchanged");
    }

    @Test
    void itemsAreNeverChangedAndStylesAreKept() {
        Component item = Component.text("[Alex's Sword]").hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component message = Component.text("look alex ", NamedTextColor.GOLD).append(item).append(Component.text(" alex"));
        Component bold = ReaderText.highlight(message, ALEX, true, 3, TextDecoration.BOLD);
        assertEquals(plain(message), plain(bold));
        assertEquals(List.of("alex", "alex"), decorated(bold, TextDecoration.BOLD), "the item name is left alone");
        assertEquals(NamedTextColor.GOLD, bold.color(), "the root keeps its colour");
        Component filtered = ReaderText.filterTyped(message, text -> text.replace("alex", "***"));
        assertEquals("look *** [Alex's Sword] ***", plain(filtered));
        assertNull(filtered.hoverEvent(), "the root still has no hover");
        assertTrue(filtered.children().stream().anyMatch(child -> child.hoverEvent() != null), "the item keeps its hover");
    }

    @Test
    void filteringWithoutChangesKeepsTheMessage() {
        Component message = Component.text("all clean").append(Component.text(" here"));
        assertSame(message, ReaderText.filterTyped(message, text -> text));
        assertEquals("ALL CLEAN HERE", plain(ReaderText.filterTyped(message, String::toUpperCase)));
    }
}
