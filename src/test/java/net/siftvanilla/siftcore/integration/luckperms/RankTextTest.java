package net.siftvanilla.siftcore.integration.luckperms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Rank labels from LuckPerms lose every colour code and tag, whichever format the admin used; colours come from meta. */
class RankTextTest {

    @Test
    void legacyCodesAreRemoved() {
        assertEquals("Elite", RankText.plain("&6&lElite"));
        assertEquals("Elite", RankText.plain("§6§lElite§r"));
        assertEquals("[Elite]", RankText.plain("&8[&6Elite&8]"));
    }

    @Test
    void hexColoursInEveryFormatAreRemoved() {
        assertEquals("Legend", RankText.plain("&#ffaa00Legend"));
        assertEquals("Legend", RankText.plain("&x&f&f&a&a&0&0Legend"));
        assertEquals("Legend", RankText.plain("§x§f§f§a§a§0§0Legend"));
        assertEquals("Legend", RankText.plain("{#ffaa00}Legend"));
    }

    @Test
    void miniMessageTagsAreRemoved() {
        assertEquals("Patron", RankText.plain("<gold><bold>Patron</bold></gold>"));
        assertEquals("Patron", RankText.plain("<#ffaa00>Patron"));
        assertEquals("Patron", RankText.plain("<gradient:#ff0000:#00ff00>Patron</gradient>"));
        assertEquals("Patron", RankText.plain("<color:#ffaa00>Patron</color>"));
    }

    @Test
    void whitespaceIsTidied() {
        assertEquals("Big Spender", RankText.plain("  &aBig   Spender \n"));
        assertEquals("", RankText.plain(null));
        assertEquals("", RankText.plain("&6"));
    }

    @Test
    void rankColoursAreHexOrNames() {
        assertEquals(TextColor.color(0x5FA8FF), RankText.color("#5FA8FF"));
        assertEquals(TextColor.color(0xFFAA00), RankText.color(" &#ffaa00 "));
        assertEquals(NamedTextColor.GOLD, RankText.color("gold"));
        assertNull(RankText.color("#12345"));
        assertNull(RankText.color("sparkly"));
        assertNull(RankText.color(null));
        assertEquals(List.of(TextColor.color(0xFF6AD5), TextColor.color(0xB26BFF)), RankText.gradient("#FF6AD5:#B26BFF"));
        assertEquals(List.of(), RankText.gradient("#FF6AD5"), "one stop is not a gradient");
        assertEquals(List.of(), RankText.gradient("#FF6AD5:nope"));
    }

    @Test
    void rankColoursPrintAsUpperCaseHex() {
        assertEquals("#5FA8FF", RankText.hex(RankText.color("#5fa8ff")));
        assertEquals("#00000A", RankText.hex(TextColor.color(0x00000A)), "always six digits");
        assertEquals("#FFAA00", RankText.hex(NamedTextColor.GOLD));
        assertEquals("", RankText.hex(null));
    }

    @Test
    void labelsTakeTheirColourOrGradient() {
        TextColor gray = NamedTextColor.GRAY;
        assertEquals(Component.text("Baron", TextColor.color(0xFFAA00)), RankText.styled("Baron", TextColor.color(0xFFAA00), List.of(), gray));
        assertEquals(Component.text("Prospector", gray), RankText.styled("Prospector", null, List.of(), gray));
        assertEquals(Component.empty(), RankText.styled("", TextColor.color(0xFFAA00), List.of(), gray));

        Component tycoon = RankText.styled("Tycoon", null, List.of(TextColor.color(0xFF6AD5), TextColor.color(0xB26BFF)), gray);
        assertEquals("Tycoon", PlainTextComponentSerializer.plainText().serialize(tycoon));
        List<Component> letters = tycoon.children();
        assertEquals(6, letters.size());
        assertEquals(TextColor.color(0xFF6AD5), letters.getFirst().color(), "starts at the first stop");
        assertEquals(TextColor.color(0xB26BFF), letters.getLast().color(), "ends at the last stop");
        TextColor middle = letters.get(2).color();
        assertTrue(middle.red() < 0xFF && middle.red() > 0xB2, "letters in between blend: " + middle.asHexString());
    }

    @Test
    void groupNamesBecomeLabels() {
        assertEquals("Elite", RankText.fromGroup("elite"));
        assertEquals("Big spender", RankText.fromGroup("big_spender"));
        assertEquals("", RankText.fromGroup(""));
    }
}
