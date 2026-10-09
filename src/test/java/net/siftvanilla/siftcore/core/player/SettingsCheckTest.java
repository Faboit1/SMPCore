package net.siftvanilla.siftcore.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.CoreMessages;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.player.options.OptionTexts;
import net.siftvanilla.siftcore.core.teleport.TeleportMessages;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SettingsCheckTest {

    private static final MessageKey LABEL = MessageKey.ui("test.label");
    private static final MessageKey DESCRIPTION = MessageKey.ui("test.description");

    private static PlayerSettings shared() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        return settings;
    }

    @Test
    void everySharedSettingCategoryAndOptionHasTextAndEveryIconExists() throws Exception {
        Icons icons = new Icons(Icons.readIndex(resource("atlas-index.txt")));
        assertEquals(java.util.Set.of(), icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()));
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(CoreMessages.class);
        lang.register(TeleportMessages.class);
        lang.register(SharedSettings.class);
        lang.register(SettingCategories.class);
        lang.register(SettingTexts.class);
        lang.register(OptionTexts.class);
        MemoryConfiguration merged = new MemoryConfiguration();
        for (String file : List.of("lang/core.yml", "lang/settings.yml")) {
            YamlConfiguration yaml = yaml(file);
            for (String key : yaml.getKeys(true)) {
                if (!yaml.isConfigurationSection(key)) {
                    merged.set(key, yaml.get(key));
                }
            }
        }
        List<ConfigProblem> problems = lang.load(merged, merged, "lang");
        assertEquals(List.of(), problems);
        PlayerSettings settings = shared();
        assertEquals(List.of(), SettingsCheck.missingText(settings.registry(), lang::plain));
        assertEquals(List.of(), SettingsCheck.badIcons(icons::has));
        assertEquals("From $10,000", lang.plain(OptionTexts.FROM_AMOUNT, net.siftvanilla.siftcore.core.text.Arg.money("amount", 10_000)));
        assertEquals("100%", SharedSettings.SOUND_VOLUME.display(lang, 100L));
        assertEquals("Above the hotbar", SharedSettings.FEEDBACK_CHANNEL.display(lang, SharedSettings.FEEDBACK_CHANNEL.defaultValue()));
        assertEquals("on", SharedSettings.SOUND_NOTIFY.display(lang, true));
    }

    @Test
    void missingTextIsReported() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        settings.register(SettingCategories.CHAT, new Toggle("t", true, LABEL, DESCRIPTION, null));
        List<String> missing = SettingsCheck.missingText(settings.registry(), (key, args) -> key.path().startsWith("test.") ? key.path() : "Text");
        assertEquals(List.of("t (test.label)", "t (test.description)"), missing);
        assertEquals(List.of("chat (chat)"), SettingsCheck.badIcons(name -> !name.equals("chat")));
    }

    @Test
    void everySharedSettingIsRegistered() {
        assertEquals(List.of(), SettingsCheck.missingShared(shared().registry()));
        PlayerSettings empty = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        assertEquals(SharedSettings.ALL.size(), SettingsCheck.missingShared(empty.registry()).size());
    }

    @Test
    void groupsHoldAtMostFifteenAndAtLeastFourOnceGeneralIsEmpty() {
        PlayerSettings settings = shared();
        for (PlayerSetting<?> privacy : List.of(SharedSettings.HIDE_COORDINATES, SharedSettings.SEEN_PRIVACY,
            SharedSettings.BALANCE_PRIVACY, SharedSettings.HIDE_FROM_LEADERBOARDS)) {
            settings.reads(privacy);
        }
        settings.register(new Toggle("general-one", true, LABEL, DESCRIPTION, null));
        assertEquals(List.of(), SettingsCheck.groupSizes(settings.registry(), setting -> false),
            "while General still holds a setting, features are still moving theirs: only the maximum counts");
        for (int i = 0; i < 16; i++) {
            settings.register(SettingCategories.SPAWNERS, new Toggle("spawners-" + i, true, LABEL, DESCRIPTION, null));
        }
        assertEquals(List.of("spawners holds 16 settings (at most 15)"), SettingsCheck.groupSizes(settings.registry(), setting -> false));
        assertEquals(List.of(), SettingsCheck.groupSizes(settings.registry(), setting -> setting.id().equals("spawners-0")),
            "hidden settings don't count");
        List<String> settled = SettingsCheck.groupSizes(settings.registry(),
            setting -> setting.id().equals("general-one") || setting.id().equals("spawners-0"));
        assertTrue(settled.contains("staff holds 0 settings (at least 4)"), "once General is empty every group needs four: " + settled);
        assertTrue(settled.contains("chat holds 0 settings (at least 4)"), settled.toString());
        assertTrue(settled.stream().noneMatch(problem -> problem.startsWith("sound ") || problem.startsWith("privacy ")
            || problem.startsWith("spawners ")), "sound (5), privacy (4) and spawners (15) are fine: " + settled);
    }

    @Test
    void sharedSettingsOnlyAFeatureActsOnWaitForAReader() {
        PlayerSettings settings = shared();
        Registry.Entry<?> seen = settings.registry().entry(SharedSettings.SEEN_PRIVACY.id());
        assertTrue(!seen.offered(), "nothing reads it yet, so the dialog leaves it out");
        assertTrue(settings.registry().entry(SharedSettings.SOUND_VOLUME.id()).offered(), "core applies the sound settings itself");
        settings.reads(SharedSettings.SEEN_PRIVACY);
        assertTrue(seen.offered(), "offered once a feature reads it");
        List<String> unread = SettingsCheck.unread(settings.registry(), settings::hasReader, setting -> false);
        assertEquals(SharedSettings.FEATURE_READ.size() - 1, unread.size(), "General is empty: every unread one is reported");
        assertTrue(!unread.contains(SharedSettings.SEEN_PRIVACY.id()));
        assertTrue(SettingsCheck.unread(settings.registry(), settings::hasReader, setting -> !setting.id().equals("sound-pm"))
            .equals(List.of("sound-pm")), "hidden ones don't count");
        settings.register(new Toggle("general-one", true, LABEL, DESCRIPTION, null));
        assertEquals(List.of(), SettingsCheck.unread(settings.registry(), settings::hasReader, setting -> false),
            "while General still holds settings, features are still wiring theirs");
    }

    private static InputStream resource(String name) {
        return SettingsCheckTest.class.getClassLoader().getResourceAsStream(name);
    }

    private static YamlConfiguration yaml(String name) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(resource(name), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
