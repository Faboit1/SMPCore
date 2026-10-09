package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Template parsing: finding placeholders in parsed lang text, filling them in safely, and hiding lines. */
class LineTemplateTest {

    private static TextStyle style;

    @BeforeAll
    static void setUp() {
        style = ScoreboardTestSupport.style();
    }

    private static LineTemplate template(String miniMessage) {
        return LineTemplate.of(style.parse(miniMessage).colorIfAbsent(style.palette().primary()));
    }

    private static String plain(Component component) {
        return TextStyle.plain(component);
    }

    /** The colour of the text component that contains {@code text}, or null. */
    private static TextColor colourOf(Component component, String text, TextColor inherited) {
        TextColor colour = component.color() != null ? component.color() : inherited;
        if (component instanceof TextComponent t && t.content().contains(text)) {
            return colour;
        }
        for (Component child : component.children()) {
            TextColor found = colourOf(child, text, colour);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void placeholdersAreFoundOnceInOrderOfFirstUse() {
        LineTemplate line = template("<secondary>{team} <primary>{team_online}/{team} <money>{balance}");
        assertEquals(List.of("team", "team_online", "balance"), line.tokens());
    }

    @Test
    void placeholdersInsideStyledPartsAndAroundIconsAreFound() {
        LineTemplate line = template("<primary><icon:money> <secondary>Money <money>{balance}");
        assertEquals(List.of("balance"), line.tokens());
        Component rendered = line.render(List.of("$1,500"));
        assertTrue(plain(rendered).endsWith("Money $1,500"), plain(rendered));
        assertEquals(Palette.defaults().money(), colourOf(rendered, "$1,500", null), "the value keeps the money colour around it");
        assertEquals(Palette.defaults().secondary(), colourOf(rendered, "Money", null));
    }

    @Test
    void textWithoutPlaceholdersIsReturnedAsIs() {
        LineTemplate line = template("<secondary>siftvanilla.net");
        assertTrue(line.tokens().isEmpty());
        assertSame(line.source(), line.render(List.of()));
        assertFalse(LineTemplate.hidden(List.of()), "a line without placeholders always shows");
    }

    @Test
    void notPlaceholdersStayText() {
        LineTemplate line = template("{Upper} {with space} {} {ok_1}");
        assertEquals(List.of("ok_1"), line.tokens());
        assertEquals("{Upper} {with space} {} 7", plain(line.render(List.of("7"))));
    }

    @Test
    void valuesAreInsertedLiterally() {
        LineTemplate line = template("<secondary>Team <primary>{team}");
        String hostile = "<red>Evil</red> <click:run_command:/op me>x";
        Component rendered = line.render(List.of(hostile));
        assertEquals("Team " + hostile, plain(rendered), "tags in a value are shown as typed");
        assertTrue(noClickEvents(rendered), "no click event can be injected");
        assertEquals(Palette.defaults().primary(), colourOf(rendered, "Evil", null), "no colour can be injected");
    }

    private static boolean noClickEvents(Component component) {
        if (component.clickEvent() != null) {
            return false;
        }
        return component.children().stream().allMatch(LineTemplateTest::noClickEvents);
    }

    @Test
    void missingValuesShowADashAndLongOrMultiLineValuesAreTamed() {
        LineTemplate line = template("{a}|{b}|{c}");
        String longValue = "x".repeat(200);
        Component rendered = line.render(Arrays.asList(null, "two\nlines", longValue));
        String[] parts = plain(rendered).split("\\|");
        assertEquals("-", parts[0]);
        assertEquals("two lines", parts[1]);
        assertEquals(LineTemplate.MAX_VALUE_LENGTH, parts[2].length());
    }

    @Test
    void aLineIsHiddenWhileOneOfItsValuesIsEmpty() {
        assertTrue(LineTemplate.hidden(List.of("")));
        assertTrue(LineTemplate.hidden(List.of("Alpha", " ")));
        assertFalse(LineTemplate.hidden(List.of("Alpha", "3")));
        List<String> unknown = new ArrayList<>();
        unknown.add(null);
        assertFalse(LineTemplate.hidden(unknown), "an unknown placeholder shows '-' instead of hiding the line");
    }

    @Test
    void theNumberOfValuesMustMatch() {
        LineTemplate line = template("{a} {b}");
        assertThrows(IllegalArgumentException.class, () -> line.render(List.of("1")));
    }

    @Test
    void multiLineTextSuchAsTheTabHeaderIsSupported() {
        LineTemplate header = template("<primary>SiftVanilla<newline><secondary>{online} online<newline>{online}");
        assertEquals(List.of("online"), header.tokens());
        assertEquals("SiftVanilla\n12 online\n12", plain(header.render(List.of("12"))));
    }
}
