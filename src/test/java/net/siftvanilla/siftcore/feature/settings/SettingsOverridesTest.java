package net.siftvanilla.siftcore.feature.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.Overrides;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import net.siftvanilla.siftcore.core.player.Toggle;
import net.siftvanilla.siftcore.core.text.MessageKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsOverridesTest {

    private static PlayerSettings settings() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        return settings;
    }

    private static SettingsConfig parse(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml);
        SettingsConfig config = SettingsConfig.parse(reader);
        assertEquals(List.of(), reader.problems(), "no problems in:\n" + text);
        return config;
    }

    @Test
    void validOverridesHaveNoProblems() {
        Overrides overrides = new Overrides(Map.of("feedback-channel", "chat", "sound-volume", "60", "sell_receipts", "true"),
            Map.of("quiet-in-combat", "false"), Set.of("hide-coordinates"));
        assertEquals(List.of(), SettingsOverrides.problems(settings().registry(), overrides), "legacy values are valid config values");
    }

    @Test
    void unknownIdsBadValuesAndConflictsAreReported() {
        Overrides overrides = new Overrides(
            Map.of("feedback-channel", "loud", "sound-volume", "65", "nothing", "1", "quiet-in-combat", "true"),
            Map.of("quiet-in-combat", "maybe"),
            Set.of("ghost"));
        assertEquals(List.of(
            "defaults.feedback-channel: 'loud' is not a value of it (use actionbar, chat, both)",
            "defaults.nothing: there is no setting called nothing on this server",
            "defaults.sound-volume: '65' is not a value of it (use a whole number from 0 to 100 in steps of 10)",
            "locked.quiet-in-combat: 'maybe' is not a value of it (use true or false)",
            "hidden: ghost is not a setting on this server",
            "defaults.quiet-in-combat: the setting is also locked, so the lock wins"),
            SettingsOverrides.problems(settings().registry(), overrides));
    }

    @Test
    void numbersMustBeWholeInRangeAndOnAStep() {
        Overrides overrides = new Overrides(Map.of("sound-volume", "110"), Map.of("sound-volume", "-10"), Set.of());
        assertEquals(List.of(
            "defaults.sound-volume: '110' is not a value of it (use a whole number from 0 to 100 in steps of 10)",
            "locked.sound-volume: '-10' is not a value of it (use a whole number from 0 to 100 in steps of 10)",
            "defaults.sound-volume: the setting is also locked, so the lock wins"),
            SettingsOverrides.problems(settings().registry(), overrides));
        assertEquals(List.of(), SettingsOverrides.problems(settings().registry(),
            new Overrides(Map.of("sound-volume", "0"), Map.of(), Set.of())), "the ends of the range are fine");
    }

    @Test
    void hidingALockedOrDefaultedSettingIsNoConflict() {
        // A hidden setting reads its lock, else its default: both together are the way to fix a value out of sight.
        Overrides overrides = new Overrides(Map.of("feedback-channel", "chat"), Map.of("quiet-in-combat", "true"),
            Set.of("feedback-channel", "quiet-in-combat"));
        assertEquals(List.of(), SettingsOverrides.problems(settings().registry(), overrides));
    }

    @Test
    void settingsThatAreNotOfferedNowAreStillValidNames() {
        PlayerSettings settings = settings();
        Toggle later = new Toggle("orders-first", true, MessageKey.ui("test.label"), MessageKey.ui("test.description"), null);
        Overrides overrides = new Overrides(Map.of(), Map.of("orders-first", "false"), Set.of());
        assertEquals(List.of("locked.orders-first: there is no setting called orders-first on this server"),
            SettingsOverrides.problems(settings.registry(), overrides), "before it registers");
        settings.register(SettingCategories.GENERAL, later, SettingOptions.<Boolean>builder().availableWhen(() -> false).build());
        assertEquals(List.of(), SettingsOverrides.problems(settings.registry(), overrides),
            "registered at start with availableWhen: valid even while it is not offered");
        assertEquals(List.of(), SettingsOverrides.problems(settings.registry(),
            new Overrides(Map.of(), Map.of(), Set.of("sound-mention"))), "shared settings no feature reads yet are names too");
    }

    @Test
    void theShippedFileHasNoOverridesAndParsesCleanly() throws Exception {
        InputStream in = SettingsOverridesTest.class.getClassLoader().getResourceAsStream("features/settings.yml");
        assertNotNull(in);
        String text;
        try (in) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        SettingsConfig config = parse(text);
        assertEquals(Overrides.NONE, config.overrides());
        assertEquals(8, config.pageSize());
        // Synced files only gain value keys: the shipped file must not rely on empty sections (they never arrive).
        assertTrue(!text.contains("{}"), "no empty sections in the shipped file");
    }

    @Test
    void overridesAreReadAsTextWithIdsInLowerCase() throws Exception {
        SettingsConfig config = parse("""
            page-size: 8
            skip-single-group: true
            hidden: [Hide-Coordinates]
            defaults:
              Feedback-Channel: ' chat '
              sound-volume: 60
            locked:
              quiet-in-combat: true
            """);
        assertEquals(new Overrides(Map.of("feedback-channel", "chat", "sound-volume", "60"), Map.of("quiet-in-combat", "true"),
            Set.of("hide-coordinates")), config.overrides());
    }

    @Test
    void aSectionWrittenAsOneValueIsAProblem() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("page-size: 8\nskip-single-group: true\ndefaults: chat\nlocked: {}\n");
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml);
        SettingsConfig config = SettingsConfig.parse(reader);
        assertEquals(1, reader.problems().size(), "problems: " + reader.problems());
        assertEquals("defaults", reader.problems().getFirst().path(), reader.problems().toString());
        assertEquals(Overrides.NONE, config.overrides(), "an empty section is fine");
    }

    @Test
    void compactPagesAndGroupOverridesAreRead() throws Exception {
        SettingsConfig config = parse("""
            page-size: 4
            skip-single-group: false
            show-descriptions: false
            hidden: []
            categories:
              Sound:
                order: 5
              privacy:
                icon: Star
            """);
        assertEquals(4, config.pageSize());
        assertTrue(!config.skipSingleGroup() && !config.showDescriptions());
        assertEquals(Map.of("sound", new SettingsConfig.CategoryOverride(5, null), "privacy", new SettingsConfig.CategoryOverride(null, "star")),
            config.categories());
    }

    @Test
    void groupOverridesMustNameGroupsAndIcons() {
        Map<String, SettingsConfig.CategoryOverride> categories = Map.of(
            "sound", new SettingsConfig.CategoryOverride(5, "star"),
            "spawners", new SettingsConfig.CategoryOverride(1, null),
            "pets", new SettingsConfig.CategoryOverride(1, null),
            "privacy", new SettingsConfig.CategoryOverride(null, "unicorn"));
        assertEquals(List.of(
            "categories.pets: there is no settings group called pets",
            "categories.privacy.icon: there is no icon called unicorn in icons.yml"),
            SettingsOverrides.categoryProblems(settings().registry(), categories, icon -> !icon.equals("unicorn")),
            "a shared group without settings yet (spawners) is a valid name");
    }

    @Test
    void aGroupWrittenAsOneValueIsAProblem() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("page-size: 8\nskip-single-group: true\ncategories: sound\n");
        ConfigReader reader = new ConfigReader("features/settings.yml", yaml);
        assertEquals(Map.of(), SettingsConfig.parse(reader).categories());
        assertEquals("categories", reader.problems().getFirst().path(), reader.problems().toString());
    }
}
