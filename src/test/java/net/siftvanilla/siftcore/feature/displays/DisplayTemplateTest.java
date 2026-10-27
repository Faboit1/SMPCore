package net.siftvanilla.siftcore.feature.displays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DisplayTemplateTest {

    private TextStyle style;
    private List<String> problems;

    @BeforeEach
    void setUp() {
        this.style = DisplaysTestSupport.style();
        this.problems = new ArrayList<>();
    }

    private DisplayTemplate compile(String... lines) {
        return DisplayTemplate.compile("t", List.of(lines), this.style,
            (line, message) -> this.problems.add(line + ": " + message));
    }

    private static String plain(Component component) {
        return TextStyle.plain(component);
    }

    @Test
    void placeholdersAreCollectedOnceInOrderOfFirstUse() {
        DisplayTemplate template = compile(
            "<secondary>1. <primary>{baltop_name_1} <money>{baltop_value_1}",
            "<secondary>again {baltop_name_1} and {top_kills_name_1}");
        assertEquals(List.of(), this.problems);
        assertEquals(List.of("baltop_name_1", "baltop_value_1", "top_kills_name_1"), template.placeholders());
        assertFalse(template.compiled().getFirst().contains("{"), "placeholders are replaced by internal tags");
    }

    @Test
    void valuesAreSubstitutedAndMissingOnesShowADash() {
        DisplayTemplate template = compile("<secondary>1. <primary>{name} <money>{value}", "{absent}");
        Map<String, String> values = Map.of("name", "Alex", "value", "$1,500");
        Component text = template.render(this.style, values::get);
        assertEquals("1. Alex $1,500\n-", plain(text));
    }

    @Test
    void aFailingProviderShowsADashAndLongValuesAreCut() {
        DisplayTemplate template = compile("{boom} {long}");
        Function<String, String> values = name -> {
            if (name.equals("boom")) {
                throw new IllegalStateException("provider broke");
            }
            return "x".repeat(1000);
        };
        String text = plain(template.render(this.style, values));
        assertEquals("- " + "x".repeat(DisplayTemplate.MAX_VALUE_LENGTH), text);
    }

    @Test
    void playerTextIsInsertedLiterally() {
        DisplayTemplate template = compile("<primary>{name}");
        String hostile = "<click:run_command:/op me><bold>pwn</bold> <icon:money> \\<red> {other}";
        Component text = template.render(this.style, name -> hostile);
        assertEquals(hostile, plain(text));
        assertNull(findClick(text), "no click event can be injected");
    }

    private static Object findClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            Object found = findClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void everyLineIsKeptIncludingEmptyOnes() {
        DisplayTemplate template = compile("<primary>Title", "", "<secondary>Footer");
        assertEquals("Title\n\nFooter", plain(template.render(this.style, name -> null)));
    }

    @Test
    void linesDefaultToThePrimaryColourAndKeepDesignColours() {
        DisplayTemplate template = compile("plain", "<secondary>gray");
        Component text = template.render(this.style, name -> null);
        List<TextColor> colours = new ArrayList<>();
        collectColours(text, colours);
        assertTrue(colours.contains(NamedTextColor.WHITE), "unstyled lines are white: " + colours);
        assertTrue(colours.contains(NamedTextColor.GRAY), "secondary is gray: " + colours);
    }

    private static void collectColours(Component component, List<TextColor> into) {
        if (component.color() != null) {
            into.add(component.color());
        }
        for (Component child : component.children()) {
            collectColours(child, into);
        }
    }

    @Test
    void iconsRenderAsSprites() {
        DisplayTemplate template = compile("<icon:money> <primary>Richest players");
        assertEquals(List.of(), this.problems);
        Component text = template.render(this.style, name -> null);
        assertTrue(containsObject(text), "the icon is an object component");
    }

    private static boolean containsObject(Component component) {
        if (component instanceof net.kyori.adventure.text.ObjectComponent) {
            return true;
        }
        return component.children().stream().anyMatch(DisplayTemplateTest::containsObject);
    }

    @Test
    void disallowedTagsAreReportedWithTheirLine() {
        compile("<primary>fine", "<bold>loud</bold>", "<red>red", "<gradient:red:blue>x", "<icon:nope> x", "<rainbow>y");
        assertEquals(3, this.problems.size(), this.problems.toString());
        assertTrue(this.problems.get(0).startsWith("4: uses tags that are not allowed: <gradient:red:blue>"), this.problems.get(0));
        assertTrue(this.problems.get(1).contains("unknown icon"), this.problems.get(1));
        assertTrue(this.problems.get(2).startsWith("6: "), this.problems.get(2));
    }

    @Test
    void internalTagsCannotBeWrittenByHand() {
        compile("<sift_display_value_0>");
        assertEquals(1, this.problems.size(), this.problems.toString());
    }

    @Test
    void badPlaceholderNamesAreReportedAndSpacedBracesAreText() {
        DisplayTemplate template = compile("{Baltop_Name_1}", "{}", "{ not a placeholder }", "{ok_1}");
        assertEquals(2, this.problems.size(), this.problems.toString());
        assertTrue(this.problems.get(0).startsWith("1: has {Baltop_Name_1}"), this.problems.get(0));
        assertTrue(this.problems.get(1).startsWith("2: has {}"), this.problems.get(1));
        assertEquals(List.of("ok_1"), template.placeholders());
        assertEquals("{Baltop_Name_1}\n{}\n{ not a placeholder }\nv",
            plain(template.render(this.style, name -> "v")));
    }

    @Test
    void legacyColourCodesAreReportedAndNeverBreakRendering() {
        DisplayTemplate template = compile("§cRed");
        assertEquals(1, this.problems.size(), this.problems.toString());
        assertEquals("&cRed", plain(template.render(this.style, name -> null)));
    }

    @Test
    void emptyAndOversizedTemplatesAreReported() {
        compile();
        assertEquals(List.of("0: has no lines"), this.problems);
        this.problems.clear();
        compile(Collections.nCopies(DisplayTemplate.MAX_LINES + 1, "x").toArray(String[]::new));
        assertEquals(1, this.problems.size());
        assertTrue(this.problems.getFirst().startsWith("0: has 41 lines"), this.problems.getFirst());
    }

    @Test
    void theShippedTemplatesCompileCleanly() {
        var yaml = DisplaysTestSupport.bundled("features/displays.yml");
        var section = yaml.getConfigurationSection("templates");
        assertTrue(section != null && section.getKeys(false).containsAll(List.of("richest", "top-kills", "most-active", "welcome")));
        for (String id : section.getKeys(false)) {
            DisplayTemplate template = DisplayTemplate.compile(id, section.getStringList(id), this.style,
                (line, message) -> this.problems.add(id + " " + line + ": " + message));
            String text = plain(template.render(this.style, name -> null));
            assertFalse(text.isBlank(), id + " renders text");
        }
        assertEquals(List.of(), this.problems);
    }

    @Test
    void theRichestTemplateShowsTenPlaces() {
        var yaml = DisplaysTestSupport.bundled("features/displays.yml");
        DisplayTemplate template = DisplayTemplate.compile("richest", yaml.getStringList("templates.richest"), this.style,
            (line, message) -> this.problems.add(message));
        assertEquals(20, template.placeholders().size());
        String text = plain(template.render(this.style, name -> name.startsWith("baltop_name_") ? "P" + name.substring(12) : null));
        assertTrue(text.contains("1. P1 -"), text);
        assertTrue(text.contains("10. P10 -"), text);
    }
}
