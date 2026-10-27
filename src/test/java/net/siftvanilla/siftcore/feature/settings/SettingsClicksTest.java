package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingTexts;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import net.siftvanilla.siftcore.ui.dialog.Input;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsClicksTest {

    @Test
    void idsBecomeValidInputKeys() {
        assertEquals("tpa_requests", SettingsClicks.key("tpa-requests"));
        assertEquals("orders_announce", SettingsClicks.key("orders_announce"));
        assertTrue(SettingsClicks.key("sound-volume").matches("[A-Za-z0-9_]+"));
    }

    @Test
    void aSwitchAsksForTheOppositeOfWhatItShowed() {
        assertEquals(false, SettingsClicks.flipped(true));
        assertEquals(true, SettingsClicks.flipped(false));
    }

    @Test
    void aChoiceMovesToTheNextOfferedOptionAndWrapsAround() {
        List<String> offered = List.of("chat", "actionbar", "off");
        assertEquals("actionbar", SettingsClicks.next(offered, "chat"));
        assertEquals("off", SettingsClicks.next(offered, "actionbar"));
        assertEquals("chat", SettingsClicks.next(offered, "off"), "after the last, the first");
        assertEquals("chat", SettingsClicks.next(offered, "title"), "a value no longer offered moves to the first");
        assertEquals("chat", SettingsClicks.next(offered, null));
        assertEquals("chat", SettingsClicks.next(List.of("chat"), "chat"), "one option stays");
        assertNull(SettingsClicks.next(List.of(), "chat"), "nothing offered: nothing to pick");
    }

    @Test
    void theChangedListKeepsWhatWasChangedWhenItOpened() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("test"));
        MessageKey label = MessageKey.ui("test.label");
        for (String id : List.of("a", "b", "c", "d")) {
            settings.register(SettingCategories.SOUND, new Toggle(id, true, label, label, null));
        }
        List<Registry.Entry<?>> visible = List.copyOf(settings.registry().in("sound"));
        assertEquals(List.of("b", "d"), SettingsClicks.snapshot(visible, List.of("d", "b", "gone")).stream().map(Registry.Entry::id).toList(),
            "in dialog order, settings that are gone left out");
        assertEquals(List.of(), SettingsClicks.snapshot(visible, List.of()));
    }

    @Test
    void aSliderBuiltFromANumberSettingRefusesOffStepAndOutOfRangeValues() {
        Input.Range range = new Input.Range("sound_volume", net.kyori.adventure.text.Component.text("Volume (%)"), 0, 100, 10, 100,
            null, 250);
        assertTrue(range.allows(60));
        assertTrue(!range.allows(65) && !range.allows(110) && !range.allows(-10), "the router re-shows the slider for these");
    }

    @Test
    void configDefaultsAndOverrides() throws Exception {
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml("features/settings.yml"));
        SettingsConfig config = SettingsConfig.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(SettingsConfig.DEFAULTS, config);
        YamlConfiguration custom = yaml("features/settings.yml");
        custom.set("skip-single-group", "maybe");
        custom.set("defaults.feedback-channel", "chat");
        custom.set("defaults.Sound-Volume", 60);
        custom.set("locked.quiet-in-combat", false);
        custom.set("hidden", List.of("Hide-Coordinates"));
        ConfigReader customReader = new ConfigReader("features/settings.yml", custom);
        SettingsConfig parsed = SettingsConfig.parse(customReader);
        assertTrue(parsed.skipSingleGroup(), "fallback");
        assertEquals(1, customReader.problems().size());
        assertEquals(Map.of("feedback-channel", "chat", "sound-volume", "60"), parsed.defaults(), "YAML numbers read as text, ids lowercased");
        assertEquals(Map.of("quiet-in-combat", "false"), parsed.locked(), "YAML booleans too");
        assertEquals(Set.of("hide-coordinates"), parsed.hidden());
        assertEquals(parsed.defaults(), parsed.overrides().defaults());
    }

    @Test
    void textLoadsWithoutProblems() throws Exception {
        Icons icons = new Icons(Icons.readIndex(SettingsClicksTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
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
        InputStream in = SettingsClicksTest.class.getClassLoader().getResourceAsStream(resource);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
