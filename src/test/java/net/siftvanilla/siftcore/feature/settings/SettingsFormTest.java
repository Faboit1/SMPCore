package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSetting.Kind;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingTexts;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.ui.dialog.Input;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsFormTest {

    private static SettingsForm.Field toggle(String id, boolean shown) {
        return new SettingsForm.Field(SettingsForm.key(id), id, Kind.TOGGLE, Boolean.toString(shown));
    }

    private static SettingsForm.Field choice(String id, String shown) {
        return new SettingsForm.Field(SettingsForm.key(id), id, Kind.CHOICE, shown);
    }

    private static SettingsForm.Field number(String id, long shown) {
        return new SettingsForm.Field(SettingsForm.key(id), id, Kind.NUMBER, Long.toString(shown));
    }

    @Test
    void idsBecomeValidInputKeys() {
        assertEquals("tpa_requests", SettingsForm.key("tpa-requests"));
        assertEquals("orders_announce", SettingsForm.key("orders_announce"));
        assertTrue(SettingsForm.key("sound-volume").matches("[A-Za-z0-9_]+"));
    }

    @Test
    void onlyChangedSettingsOfEveryKindAreSaved() {
        List<SettingsForm.Field> fields = List.of(toggle("mentions", true), choice("feedback-channel", "actionbar"),
            number("sound-volume", 100), toggle("death-messages", false));
        Map<String, String> changes = SettingsForm.changes(fields, Map.of(
            "mentions", true,
            "feedback_channel", "chat",
            "sound_volume", 60L,
            "death_messages", true));
        assertEquals(Map.of("feedback-channel", "chat", "sound-volume", "60", "death-messages", "true"), changes);
        assertEquals(List.of("feedback-channel", "sound-volume", "death-messages"), List.copyOf(changes.keySet()), "in dialog order");
    }

    @Test
    void missingOrWronglyTypedValuesChangeNothing() {
        List<SettingsForm.Field> fields = List.of(toggle("mentions", true), choice("style", "chat"), number("volume", 50));
        assertTrue(SettingsForm.changes(fields, Map.of()).isEmpty());
        assertTrue(SettingsForm.changes(fields, Map.of("mentions", "false", "style", true, "volume", "40", "unknown", false)).isEmpty(),
            "a toggle sends a Boolean, a choice a String, a slider a Long");
        assertEquals("true", SettingsForm.encode(Kind.TOGGLE, Boolean.TRUE));
        assertEquals("off", SettingsForm.encode(Kind.CHOICE, "off"));
        assertEquals("70", SettingsForm.encode(Kind.NUMBER, 70L));
        assertEquals(null, SettingsForm.encode(Kind.NUMBER, 70.0f));
    }

    @Test
    void pagesSplitLongGroups() {
        List<Integer> items = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        assertEquals(3, SettingsForm.pages(items.size(), 8));
        assertEquals(1, SettingsForm.pages(0, 8), "an empty group is one page");
        assertEquals(1, SettingsForm.pages(8, 8));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), SettingsForm.page(items, 1, 8));
        assertEquals(List.of(17), SettingsForm.page(items, 3, 8));
        assertEquals(List.of(17), SettingsForm.page(items, 99, 8), "pages past the end show the last one");
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), SettingsForm.page(items, -4, 8), "and before the start the first");
        assertEquals(3, SettingsForm.clampPage(7, items.size(), 8));
    }

    @Test
    void changesOfEveryKindAreCarriedBetweenPagesAndSavedOnlyWhenTheyChangeSomething() {
        // Page 1: the player turns mentions off and moves the volume to 40.
        List<SettingsForm.Field> page1 = List.of(toggle("mentions", true), number("sound-volume", 100));
        Map<String, String> pending = SettingsForm.merge(Map.of(), page1, Map.of("mentions", false, "sound_volume", 40L));
        assertEquals(Map.of("mentions", "false", "sound-volume", "40"), pending);
        // Page 2: the feedback channel goes to chat.
        List<SettingsForm.Field> page2 = List.of(choice("feedback-channel", "actionbar"));
        pending = SettingsForm.merge(pending, page2, Map.of("feedback_channel", "chat"));
        assertEquals(Map.of("mentions", "false", "sound-volume", "40", "feedback-channel", "chat"), pending);
        // Back on page 1 (showing the pending values), the player turns mentions on again.
        List<SettingsForm.Field> again = List.of(toggle("mentions", false), number("sound-volume", 40));
        pending = SettingsForm.merge(pending, again, Map.of("mentions", true, "sound_volume", 40L));
        assertEquals(Map.of("mentions", "true", "sound-volume", "40", "feedback-channel", "chat"), pending);
        // Stored now: mentions on, volume 100, channel chat (changed meanwhile): only the volume is saved.
        Map<String, String> stored = Map.of("mentions", "true", "sound-volume", "100", "feedback-channel", "chat");
        assertEquals(Map.of("sound-volume", "40"), SettingsForm.effective(pending, stored::get));
        assertEquals(Map.of(), SettingsForm.effective(Map.of("gone", "1"), id -> null), "settings that are gone are skipped");
    }

    @Test
    void aValueChangedElsewhereIsNotOverwrittenByAStalePage() {
        // The page showed private messages off; meanwhile /msgtoggle turned them on. The player touches nothing.
        List<SettingsForm.Field> page = List.of(toggle("private-messages", false));
        Map<String, String> pending = SettingsForm.merge(Map.of(), page, Map.of("private_messages", false));
        assertTrue(pending.isEmpty());
        assertTrue(SettingsForm.effective(pending, toggle -> "true").isEmpty());
    }

    @Test
    void aSliderBuiltFromANumberSettingRefusesOffStepAndOutOfRangeValues() {
        Input.Range range = new Input.Range("sound_volume", net.kyori.adventure.text.Component.text("Volume (%)"), 0, 100, 10, 100,
            null, 250);
        assertTrue(range.allows(60));
        assertTrue(!range.allows(65) && !range.allows(110) && !range.allows(-10), "the router re-shows the form for these");
    }

    @Test
    void configDefaultsAndOverrides() throws Exception {
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml("features/settings.yml"));
        SettingsConfig config = SettingsConfig.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(SettingsConfig.DEFAULTS, config);
        assertTrue(config.showDescriptions(), "descriptions are shown by default");
        YamlConfiguration custom = yaml("features/settings.yml");
        custom.set("page-size", 0);
        custom.set("defaults.feedback-channel", "chat");
        custom.set("defaults.Sound-Volume", 60);
        custom.set("locked.quiet-in-combat", false);
        custom.set("hidden", List.of("Hide-Coordinates"));
        ConfigReader customReader = new ConfigReader("features/settings.yml", custom);
        SettingsConfig parsed = SettingsConfig.parse(customReader);
        assertEquals(8, parsed.pageSize(), "fallback");
        assertEquals(1, customReader.problems().size());
        assertEquals(Map.of("feedback-channel", "chat", "sound-volume", "60"), parsed.defaults(), "YAML numbers read as text, ids lowercased");
        assertEquals(Map.of("quiet-in-combat", "false"), parsed.locked(), "YAML booleans too");
        assertEquals(Set.of("hide-coordinates"), parsed.hidden());
        assertEquals(parsed.defaults(), parsed.overrides().defaults());
    }

    @Test
    void textLoadsWithoutProblems() throws Exception {
        Icons icons = new Icons(Icons.readIndex(SettingsFormTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(SettingsMessages.class);
        lang.register(SettingCategories.class);
        lang.register(SettingTexts.class);
        lang.register(OptionTexts.class);
        YamlConfiguration langYaml = yaml("lang/settings.yml");
        List<ConfigProblem> problems = lang.load(langYaml, langYaml, "lang/settings.yml");
        assertEquals(List.of(), problems);
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("settings."))
            .collect(Collectors.toCollection(TreeSet::new));
        Set<String> inFile = langYaml.getKeys(true).stream().filter(path -> !langYaml.isConfigurationSection(path))
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(registered, inFile, "lang/settings.yml has exactly the registered messages");
    }

    private static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = SettingsFormTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
