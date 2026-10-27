package net.siftvanilla.siftcore.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.Test;

class PlayerSettingCodecTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");
    private static final MessageKey OPTION = MessageKey.ui("test.option");

    private enum Mode {
        ALL,
        SOME,
        NONE;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static Choice<Mode> mode() {
        return Choice.ofEnum("mode", Mode.class, Mode::id, Mode.ALL)
            .option(Mode.ALL, OPTION).option(Mode.SOME, OPTION, null, "none").option(Mode.NONE, OPTION)
            .text(LABEL, DESCRIPTION).build();
    }

    @Test
    void togglesReadEveryOnOffWordAndStoreTrueFalse() {
        Toggle toggle = new Toggle("sidebar", true, LABEL, DESCRIPTION, null);
        for (String on : List.of("true", "TRUE", " on ", "Yes", "1")) {
            assertEquals(Optional.of(true), toggle.decode(on), on);
        }
        for (String off : List.of("false", "Off", "no", "0")) {
            assertEquals(Optional.of(false), toggle.decode(off), off);
        }
        assertEquals(Optional.empty(), toggle.decode("maybe"));
        assertEquals(Optional.empty(), toggle.decode(null));
        assertEquals("true", toggle.encode(true));
        assertEquals("false", toggle.encode(false));
        assertEquals(Boolean.TRUE, toggle.defaultValue());
        assertEquals(PlayerSetting.Kind.TOGGLE, toggle.kind());
        assertEquals(Boolean.FALSE, toggle.cast(false));
        assertNull(toggle.cast("false"), "only real booleans");
    }

    @Test
    void choicesReadIdsIgnoringCaseAndSpacesAndStoreTheOptionId() {
        Choice<Mode> mode = mode();
        assertEquals(Optional.of(Mode.SOME), mode.decode(" SOME "));
        assertEquals(Optional.of(Mode.NONE), mode.decode("none"));
        assertEquals(Optional.empty(), mode.decode("most"));
        assertEquals(Optional.empty(), mode.decode(""));
        assertEquals("some", mode.encode(Mode.SOME));
        assertEquals(List.of("all", "some", "none"), mode.optionIds());
        assertSame(Mode.SOME, mode.cast(Mode.SOME));
        assertNull(mode.cast("some"), "values, not ids");
        assertTrue(mode.valid(Mode.NONE));
        assertEquals("none", mode.option("some").unavailableAs());
    }

    @Test
    void legacyValuesLetAToggleBecomeAChoice() {
        Choice<AlertStyle> receipts = Choices.alert("sell_receipts", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.ACTIONBAR, AlertStyle.OFF)
            .legacyValue("true", "chat").legacyValue("false", "actionbar").text(LABEL, DESCRIPTION).build();
        assertEquals(Optional.of(AlertStyle.CHAT), receipts.decode("true"), "an old 'on' row");
        assertEquals(Optional.of(AlertStyle.ACTIONBAR), receipts.decode(" FALSE "), "an old 'off' row");
        assertEquals(Optional.of(AlertStyle.OFF), receipts.decode("off"));
        assertEquals("actionbar", receipts.encode(receipts.decode("false").orElseThrow()), "rewritten as the option id");
        assertThrows(IllegalArgumentException.class, () -> Choices.alert("x", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.OFF)
            .legacyValue("chat", "off").text(LABEL, DESCRIPTION).build(), "an alias can't shadow an option id");
        assertThrows(IllegalArgumentException.class, () -> Choices.alert("x", AlertStyle.CHAT, AlertStyle.CHAT, AlertStyle.OFF)
            .legacyValue("true", "title").text(LABEL, DESCRIPTION).build(), "an alias must name an option");
    }

    @Test
    void choiceRules() {
        assertThrows(IllegalArgumentException.class, () -> Choice.ofEnum("mode", Mode.class, Mode::id, Mode.ALL)
            .option(Mode.ALL, OPTION).text(LABEL, DESCRIPTION).build(), "at least two options");
        assertThrows(IllegalArgumentException.class, () -> Choice.ofEnum("mode", Mode.class, Mode::id, Mode.NONE)
            .option(Mode.ALL, OPTION).option(Mode.SOME, OPTION).text(LABEL, DESCRIPTION).build(), "the default is an option");
        assertThrows(IllegalArgumentException.class, () -> Choice.builder("dup", "a").option("a", "a", OPTION).option("a", "b", OPTION)
            .text(LABEL, DESCRIPTION).build(), "unique ids");
        assertThrows(IllegalArgumentException.class, () -> Choice.builder("dup", "a").option("a", "a", OPTION).option("b", "a", OPTION)
            .text(LABEL, DESCRIPTION).build(), "unique values");
        assertThrows(IllegalArgumentException.class, () -> Choice.builder("bad", "a").option("A b", "a", OPTION).option("b", "b", OPTION)
            .text(LABEL, DESCRIPTION).build(), "option ids are lowercase words");
        assertThrows(IllegalArgumentException.class, () -> Choice.builder("Bad Id", "a").option("a", "a", OPTION).option("b", "b", OPTION)
            .text(LABEL, DESCRIPTION).build(), "setting ids");
        assertThrows(NullPointerException.class, () -> Choice.builder("nolabel", "a").option("a", "a", OPTION).option("b", "b", OPTION)
            .build(), "a label and description");
        Choice.Builder<Integer> seven = Choice.builder("seven", 1);
        for (int i = 1; i <= 7; i++) {
            seven.option("o" + i, i, OPTION);
        }
        assertThrows(IllegalArgumentException.class, () -> seven.text(LABEL, DESCRIPTION).build(), "at most six options");
        assertThrows(IllegalArgumentException.class, () -> Choice.builder("fallback", "a").option("a", "a", OPTION)
            .option(new Choice.Option<>("b", "b", OPTION, List.of(), null, "c")).text(LABEL, DESCRIPTION).build(),
            "a fallback names an option");
    }

    @Test
    void numbersSnapIntoRangeAndOntoSteps() {
        NumberSetting volume = new NumberSetting("volume", 100, 0, 100, 10, null, LABEL, DESCRIPTION, null);
        assertEquals(Optional.of(60L), volume.decode("60"));
        assertEquals(Optional.of(60L), volume.decode(" 64 "), "the nearest step");
        assertEquals(Optional.of(70L), volume.decode("65"), "halfway rounds up");
        assertEquals(Optional.of(100L), volume.decode("250"), "clamped to max");
        assertEquals(Optional.of(0L), volume.decode("-5"), "clamped to min");
        assertEquals(Optional.empty(), volume.decode("loud"));
        assertEquals(Optional.empty(), volume.decode("99999999999999999999999"), "overflow");
        assertEquals("60", volume.encode(60L));
        assertEquals(Long.valueOf(100), volume.defaultValue());
        assertTrue(volume.allows(30));
        assertFalse(volume.allows(35), "off step");
        assertFalse(volume.allows(110), "out of range");
        assertEquals(Long.valueOf(30), volume.parseExact("30"));
        assertNull(volume.parseExact("35"), "typed values must be exact");
        assertNull(volume.parseExact("x"));
        assertEquals(Long.valueOf(5), volume.cast(5), "integers widen");
        NumberSetting offset = new NumberSetting("offset", 3, 3, 21, 3, null, LABEL, DESCRIPTION, null);
        assertEquals(6, offset.snap(5));
        assertEquals(21, offset.snap(100));
    }

    @Test
    void numberRules() {
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 0, 5, 5, 1, null, LABEL, DESCRIPTION, null), "min below max");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 0, 0, 10, 0, null, LABEL, DESCRIPTION, null), "step");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 0, 0, 10, 3, null, LABEL, DESCRIPTION, null),
            "a whole number of steps, or the slider end is refused");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 0, 0, NumberSetting.MAX_ABS + 1, 1, null, LABEL,
            DESCRIPTION, null), "exact as a float");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 0, 0, 5000, 1, null, LABEL, DESCRIPTION, null),
            "at most 1000 steps");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 5, 0, 10, 2, null, LABEL, DESCRIPTION, null),
            "the default is on a step");
        assertThrows(IllegalArgumentException.class, () -> new NumberSetting("n", 20, 0, 10, 2, null, LABEL, DESCRIPTION, null),
            "the default is in range");
        assertThrows(IllegalArgumentException.class, () -> new Toggle("Not valid", true, LABEL, DESCRIPTION, null));
        assertThrows(IllegalArgumentException.class, () -> new Toggle("a-very-long-setting-id-over-32-chars", true, LABEL, DESCRIPTION, null));
    }

    @Test
    void typedTextParsesPerKind() {
        Toggle toggle = new Toggle("t", false, LABEL, DESCRIPTION, null);
        assertEquals(Boolean.TRUE, PlayerSettings.parse(toggle, "on", false));
        assertEquals(Boolean.TRUE, PlayerSettings.parse(toggle, "toggle", false), "toggle flips");
        assertEquals(Boolean.FALSE, PlayerSettings.parse(toggle, "TOGGLE", true));
        assertNull(PlayerSettings.parse(toggle, "sideways", true));
        assertEquals(Mode.NONE, PlayerSettings.parse(mode(), "None", Mode.ALL));
        assertNull(PlayerSettings.parse(mode(), "toggle", Mode.ALL));
        NumberSetting number = new NumberSetting("n", 10, 0, 100, 10, null, LABEL, DESCRIPTION, null);
        assertEquals(Long.valueOf(40), PlayerSettings.parse(number, "40", 10L));
        assertNull(PlayerSettings.parse(number, "45", 10L), "commands take exact values only");
    }
}
