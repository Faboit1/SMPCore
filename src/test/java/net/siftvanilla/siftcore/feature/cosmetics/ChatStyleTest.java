package net.siftvanilla.siftcore.feature.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Parsing, storing and painting chat and nickname styles. */
class ChatStyleTest {

    private static final TextColor WHITE = NamedTextColor.WHITE;

    /** Every text part of a component with the colour it renders in (inherited colours resolved). */
    private record Part(String text, TextColor color) {
    }

    private static List<Part> parts(Component component) {
        List<Part> parts = new ArrayList<>();
        collect(component, null, parts);
        return parts;
    }

    private static void collect(Component component, TextColor inherited, List<Part> parts) {
        TextColor color = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            parts.add(new Part(text.content(), color));
        }
        for (Component child : component.children()) {
            collect(child, color, parts);
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void parsesEveryStoredAndTypedForm() {
        assertSame(ChatStyle.NONE, ChatStyle.parse(""));
        assertSame(ChatStyle.NONE, ChatStyle.parse("none"));
        assertEquals(ChatStyle.vanilla(NamedTextColor.GOLD), ChatStyle.parse("gold"));
        assertEquals(ChatStyle.vanilla(NamedTextColor.LIGHT_PURPLE), ChatStyle.parse(" LIGHT_PURPLE "));
        assertFalse(ChatStyle.parse("#FFAA00").equals(ChatStyle.parse("gold")), "a typed hex colour is not the vanilla colour");
        assertEquals(0xFFAA00, ((ChatStyle.Solid) ChatStyle.parse("#ffaa00")).color().value());
        assertEquals(0xFFAA00, ((ChatStyle.Solid) ChatStyle.parse("FFAA00")).color().value());
        assertEquals(0xFFAA00, ((ChatStyle.Solid) ChatStyle.parse("#FA0")).color().value(), "shorthand");
        ChatStyle gradient = ChatStyle.parse("#55FFFF:#5555FF");
        assertInstanceOf(ChatStyle.Gradient.class, gradient);
        assertEquals(gradient, ChatStyle.parse("#55ffff #5555ff"));
        assertEquals(gradient, ChatStyle.parse("#55FFFF, #5555FF"));
        assertInstanceOf(ChatStyle.Solid.class, ChatStyle.parse("#55FFFF:#55FFFF"), "two equal colours are one colour");
        assertNull(ChatStyle.parse("#GGGGGG"));
        assertNull(ChatStyle.parse("rainbow"));
        assertNull(ChatStyle.parse("#55FFFF #5555FF #FFFFFF"));
        assertNull(ChatStyle.parse(null));
    }

    @Test
    void serializesBackToWhatParses() {
        for (String stored : List.of("gold", "dark_aqua", "#FFAA00", "#55FFFF:#5555FF")) {
            assertEquals(stored, ChatStyle.parse(stored).serialize());
        }
        assertEquals("#FFAA00", ChatStyle.parse("#ffaa00").serialize());
        assertEquals("", ChatStyle.NONE.serialize());
    }

    @Test
    void vanillaColoursAreBasicAndHexIsPremium() {
        assertFalse(ChatStyle.NONE.premium());
        assertFalse(ChatStyle.parse("gold").premium());
        assertTrue(ChatStyle.parse("#FFAA00").premium(), "a hex colour is premium even when it equals a vanilla one");
        assertTrue(ChatStyle.parse("#55FFFF:#5555FF").premium());
    }

    @Test
    void appliesToNamesLiterally() {
        Component solid = ChatStyle.parse("aqua").apply("<red>Alex");
        assertEquals("<red>Alex", plain(solid), "player text is never parsed");
        assertEquals(NamedTextColor.AQUA, solid.color());
        Component gradient = ChatStyle.parse("#FF0000:#0000FF").apply("Alexa");
        List<Part> parts = parts(gradient);
        assertEquals("Alexa", plain(gradient));
        assertEquals(0xFF0000, parts.getFirst().color().value(), "the first letter has the first colour");
        assertEquals(0x0000FF, parts.getLast().color().value(), "the last letter has the last colour");
        assertEquals(5, parts.size());
        assertEquals("x", plain(ChatStyle.NONE.apply("x")));
        assertNull(ChatStyle.NONE.apply("x").color(), "no style adds no colour");
    }

    @Test
    void solidPaintColoursTheTextButNotTheItem() {
        Component item = Component.text("[Diamond Sword]").hoverEvent(HoverEvent.showItem(Key.key("minecraft:diamond_sword"), 1));
        Component message = Component.text("look at ").append(item).append(Component.text(" now"));
        Component painted = ChatStyle.parse("gold").paint(message, WHITE);
        List<Part> parts = parts(painted);
        assertEquals("look at [Diamond Sword] now", plain(painted));
        assertEquals(NamedTextColor.GOLD, parts.get(0).color());
        assertEquals(WHITE, parts.get(1).color(), "the item keeps the chat colour");
        assertEquals(NamedTextColor.GOLD, parts.get(2).color());
        assertSame(message, ChatStyle.NONE.paint(message, WHITE), "no style changes nothing");
    }

    @Test
    void gradientPaintRunsAcrossTheTypedTextOnly() {
        Component item = Component.text("[Bow]").hoverEvent(HoverEvent.showItem(Key.key("minecraft:bow"), 1));
        Component message = Component.text("ab").append(item).append(Component.text("cd"));
        assertEquals(4, ChatStyle.typedLength(message), "the item's name is not typed text");
        Component painted = ChatStyle.parse("#000000:#FFFFFF").paint(message, WHITE);
        assertEquals("ab[Bow]cd", plain(painted));
        List<Part> parts = parts(painted);
        List<Integer> typed = new ArrayList<>();
        for (Part part : parts) {
            if (part.text().equals("[Bow]")) {
                assertEquals(WHITE, part.color(), "the item keeps the chat colour");
            } else {
                for (int i = 0; i < part.text().length(); i++) {
                    typed.add(part.color().value());
                }
            }
        }
        assertEquals(List.of(0x000000, 0x555555, 0xAAAAAA, 0xFFFFFF), typed, "four typed letters spread over the gradient");
    }

    @Test
    void miniMessageIsEscaped() {
        assertEquals("<gradient:#FF6AD5:#B26BFF>Alex</gradient>", ChatStyle.parse("#ff6ad5:#b26bff").miniMessage("Alex"));
        assertEquals("<gold>Al\\<red>x</gold>", ChatStyle.parse("gold").miniMessage("Al<red>x"));
        assertEquals("<#FFAA00>Alex</#FFAA00>", ChatStyle.parse("#ffaa00").miniMessage("Alex"));
        assertEquals("Alex", ChatStyle.NONE.miniMessage("Alex"));
    }

    @Test
    void colourAtSpreadsEvenly() {
        ChatStyle style = ChatStyle.parse("#000000:#FFFFFF");
        assertEquals(0x000000, style.colorAt(0, 3).value());
        assertEquals(0x808080, style.colorAt(1, 3).value());
        assertEquals(0xFFFFFF, style.colorAt(2, 3).value());
        assertEquals(0x000000, style.colorAt(0, 1).value(), "a single character takes the first colour");
        assertNull(ChatStyle.NONE.colorAt(0, 3));
    }
}
