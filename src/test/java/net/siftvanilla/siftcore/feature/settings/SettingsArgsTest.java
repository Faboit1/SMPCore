package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.siftvanilla.siftcore.core.player.Choice;
import net.siftvanilla.siftcore.core.player.NumberSetting;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.AlertStyle;
import net.siftvanilla.siftcore.core.player.options.Choices;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.junit.jupiter.api.Test;

class SettingsArgsTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    private static final NumberSetting VOLUME = new NumberSetting("sound-volume", 100, 0, 100, 10, null, LABEL, DESCRIPTION, null);
    private static final Toggle NOTIFY = new Toggle("sound-notify", true, LABEL, DESCRIPTION, null);
    private static final Choice<AlertStyle> FEEDBACK = Choices.alert("feedback-channel", AlertStyle.ACTIONBAR, AlertStyle.ACTIONBAR,
        AlertStyle.CHAT, AlertStyle.BOTH).text(LABEL, DESCRIPTION).build();
    private static final Choice<AlertStyle> RECEIPTS = Choices.alert("sell_receipts", AlertStyle.CHAT, AlertStyle.CHAT,
        AlertStyle.ACTIONBAR, AlertStyle.OFF).legacyValue("true", "chat").legacyValue("false", "actionbar").text(LABEL, DESCRIPTION).build();
    private static final Toggle SPY = new Toggle("social-spy", false, LABEL, DESCRIPTION, "siftcore.chat.socialspy");
    private static final Toggle CHAT_NOTIFY = new Toggle("chat-notify", true, LABEL, DESCRIPTION, null);
    private static final Toggle COUNT = new Toggle("count", true, LABEL, DESCRIPTION, null);

    private static Registry registry() {
        return Registry.EMPTY
            .with(VOLUME, SettingCategories.SOUND, SettingOptions.defaults())
            .with(NOTIFY, SettingCategories.SOUND, SettingOptions.defaults())
            .with(FEEDBACK, SettingCategories.DISPLAY, SettingOptions.defaults())
            .with(RECEIPTS, SettingCategories.ECONOMY, SettingOptions.defaults())
            .with(SPY, SettingCategories.STAFF, SettingOptions.defaults())
            .with(CHAT_NOTIFY, SettingCategories.CHAT, SettingOptions.defaults())
            .with(COUNT, SettingCategories.CHAT, SettingOptions.defaults());
    }

    private static final Predicate<Registry.Entry<?>> EVERYONE = entry -> entry.setting().permission() == null;
    private static final Predicate<Registry.Entry<?>> STAFF = entry -> true;

    private static String one(SettingsArgs.Target target) {
        return assertInstanceOf(SettingsArgs.One.class, target).entry().id();
    }

    @Test
    void reservedWordsWinThenGroupsThenSettings() {
        Registry registry = registry();
        assertEquals(new SettingsArgs.Reserved("search"), SettingsArgs.first(registry, EVERYONE, "Search"));
        assertEquals(new SettingsArgs.Reserved("all"), SettingsArgs.first(registry, EVERYONE, "all"));
        assertEquals(SettingCategories.SOUND, assertInstanceOf(SettingsArgs.Category.class, SettingsArgs.first(registry, EVERYONE, "SOUND"))
            .category());
        assertEquals("sound-volume", one(SettingsArgs.first(registry, EVERYONE, "sound-volume")), "a full id");
        assertEquals("sound-volume", one(SettingsArgs.first(registry, EVERYONE, "sound_volume")), "a dialog input key");
        assertEquals("sound-volume", one(SettingsArgs.first(registry, EVERYONE, "volume")), "a short name only one setting has");
        assertEquals("sell_receipts", one(SettingsArgs.first(registry, EVERYONE, "sell_receipts")), "ids with '_'");
        // "notify" is the short name of sound-notify (sound) and chat-notify (chat): ambiguous without the group.
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.first(registry, EVERYONE, "notify"));
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.first(registry, EVERYONE, "nothing"));
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.first(registry, EVERYONE, ""));
    }

    @Test
    void settingsAndGroupsThePlayerCantSeeAreUnknown() {
        Registry registry = registry();
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.first(registry, EVERYONE, "social-spy"), "a staff setting");
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.first(registry, EVERYONE, "staff"), "a group with nothing visible");
        assertEquals("social-spy", one(SettingsArgs.first(registry, STAFF, "social-spy")));
        assertInstanceOf(SettingsArgs.Category.class, SettingsArgs.first(registry, STAFF, "staff"));
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.STAFF, "social-spy"));
    }

    @Test
    void insideAGroupShortNamesIdsAndKeysWork() {
        Registry registry = registry();
        assertEquals("sound-notify", one(SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.SOUND, "notify")));
        assertEquals("chat-notify", one(SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.CHAT, "Notify")));
        assertEquals("sound-volume", one(SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.SOUND, "sound-volume")));
        assertEquals("sound-volume", one(SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.SOUND, "SOUND_VOLUME")));
        assertInstanceOf(SettingsArgs.Unknown.class, SettingsArgs.inCategory(registry, EVERYONE, SettingCategories.CHAT, "volume"),
            "a setting of another group");
        assertSame(registry.entry("social-spy"), SettingsArgs.anySetting(registry, "Social_Spy"), "staff tools reach every setting");
        assertEquals(null, SettingsArgs.anySetting(registry, "nope"));
    }

    @Test
    void togglesTakeOnOffWordsAndToggle() {
        assertEquals(Boolean.FALSE, SettingsArgs.parse(NOTIFY, "off", true, List.of(), o -> "").value());
        assertEquals(Boolean.TRUE, SettingsArgs.parse(NOTIFY, " YES ", false, List.of(), o -> "").value());
        assertEquals(Boolean.FALSE, SettingsArgs.parse(NOTIFY, "toggle", true, List.of(), o -> "").value(), "flips the current value");
        assertEquals(Boolean.TRUE, SettingsArgs.parse(NOTIFY, "Toggle", false, List.of(), o -> "").value());
        assertEquals(SettingsArgs.Problem.NOT_A_VALUE, SettingsArgs.parse(NOTIFY, "maybe", true, List.of(), o -> "").problem());
        assertEquals(SettingsArgs.Problem.NOT_A_VALUE, SettingsArgs.parse(NOTIFY, "  ", true, List.of(), o -> "").problem());
    }

    @Test
    void numbersMustBeInRangeAndOnAStep() {
        assertEquals(60L, SettingsArgs.parse(VOLUME, "60", 100L, List.of(), o -> "").value());
        assertEquals(30L, SettingsArgs.parse(VOLUME, "30%", 100L, List.of(), o -> "").value(), "a trailing % is fine");
        assertFalse(SettingsArgs.parse(VOLUME, "65", 100L, List.of(), o -> "").ok(), "off step");
        assertFalse(SettingsArgs.parse(VOLUME, "110", 100L, List.of(), o -> "").ok(), "out of range");
        assertFalse(SettingsArgs.parse(VOLUME, "-10", 100L, List.of(), o -> "").ok());
        assertFalse(SettingsArgs.parse(VOLUME, "loud", 100L, List.of(), o -> "").ok());
        assertFalse(SettingsArgs.parse(VOLUME, "99999999999999999999999", 100L, List.of(), o -> "").ok(), "overflow");
    }

    @Test
    void choicesTakeIdsOldValuesAndLabelsButOnlyOfferedOptions() {
        List<Choice.Option<AlertStyle>> offered = FEEDBACK.options();
        java.util.function.Function<Choice.Option<AlertStyle>, String> labels = option -> switch (option.id()) {
            case "actionbar" -> "Above the hotbar";
            case "chat" -> "Chat";
            default -> "Both";
        };
        assertEquals(AlertStyle.CHAT, SettingsArgs.parse(FEEDBACK, "chat", AlertStyle.ACTIONBAR, offered, labels).value());
        assertEquals(AlertStyle.ACTIONBAR, SettingsArgs.parse(FEEDBACK, "above the hotbar", AlertStyle.CHAT, offered, labels).value(),
            "a label, ignoring case and spaces");
        assertEquals(AlertStyle.ACTIONBAR, SettingsArgs.parse(FEEDBACK, "Above-The-Hotbar", AlertStyle.CHAT, offered, labels).value());
        assertEquals(SettingsArgs.Problem.NOT_A_VALUE, SettingsArgs.parse(FEEDBACK, "title", AlertStyle.CHAT, offered, labels).problem());
        assertEquals(SettingsArgs.Problem.NOT_OFFERED, SettingsArgs.parse(FEEDBACK, "both", AlertStyle.CHAT, offered.subList(0, 2), labels)
            .problem(), "an option the player can't pick");
        assertEquals(AlertStyle.ACTIONBAR, SettingsArgs.parse(RECEIPTS, "false", AlertStyle.CHAT, RECEIPTS.options(), o -> o.id()).value(),
            "an old stored value of a switch that became a choice");
    }

    @Test
    void firstWordSuggestsGroupsThenIdsOnceTwoCharactersAreTyped() {
        Registry registry = registry();
        List<Registry.Entry<?>> changeable = registry.entries().stream().filter(EVERYONE).toList();
        List<net.siftvanilla.siftcore.core.player.SettingCategory> categories = List.of(SettingCategories.CHAT, SettingCategories.SOUND,
            SettingCategories.DISPLAY, SettingCategories.ECONOMY);
        assertEquals(List.of("chat", "sound", "display", "economy"), SettingsArgs.suggestFirst(categories, changeable, ""));
        assertEquals(List.of("sound"), SettingsArgs.suggestFirst(categories, changeable, "s"), "no ids after one character");
        assertEquals(List.of("sound", "sound-volume", "sound-notify"), SettingsArgs.suggestFirst(categories, changeable, "so"));
        assertEquals(List.of("sell_receipts"), SettingsArgs.suggestFirst(categories, changeable, "SE"));
        assertTrue(SettingsArgs.suggestFirst(categories, changeable, "soc").isEmpty(), "never a setting the player can't change");
        assertEquals(List.of("volume", "notify"), SettingsArgs.suggestSettings(registry.in("sound"), ""));
        assertEquals(List.of("notify"), SettingsArgs.suggestSettings(registry.in("sound"), "n"));
    }

    @Test
    void valueSuggestions() {
        assertEquals(List.of("on", "off", "toggle"), SettingsArgs.suggestValues(NOTIFY, List.of(), ""));
        assertEquals(List.of("on", "off"), SettingsArgs.suggestValues(NOTIFY, List.of(), "O"));
        assertEquals(List.of("actionbar", "chat"), SettingsArgs.suggestValues(FEEDBACK, List.of("actionbar", "chat"), ""),
            "only the options the player may pick");
        assertEquals(List.of("chat"), SettingsArgs.suggestValues(FEEDBACK, List.of("actionbar", "chat"), "c"));
        List<String> volume = SettingsArgs.suggestValues(VOLUME, List.of(), "");
        assertEquals(List.of("0", "100", "10", "20", "30", "40", "50", "60", "70", "80", "90"), volume,
            "minimum, default and maximum first, then the steps");
        assertEquals(List.of("10", "100"), SettingsArgs.suggestValues(VOLUME, List.of(), "1"));
        NumberSetting wide = new NumberSetting("wide", 500, 0, 1000, 1, null, LABEL, DESCRIPTION, null);
        assertEquals(SettingsArgs.MAX_SUGGESTIONS, SettingsArgs.suggestValues(wide, List.of(), "").size(), "at most 15");
        assertTrue(SettingsArgs.suggestValues(wide, List.of(), "").containsAll(Set.of("0", "500", "1000")));
        assertTrue(SettingsArgs.suggestValues(wide, List.of(), "99").containsAll(List.of("99", "990", "999")));
    }
}
