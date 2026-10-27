package net.siftvanilla.siftcore.feature.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.CoreControl;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.selftest.SelfTest;
import net.siftvanilla.siftcore.storage.Database;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import org.junit.jupiter.api.Test;

/** What the config problem alert counts: startup log lines, settings override warnings and reload results. */
class ConfigProblemLogTest {

    private static final ConfigProblem BAD_PRICE = new ConfigProblem("features/shards.yml", "shop.offers.totem.price", "must be at least 1");

    @Test
    void startupLinesAndSettingsWarningsAreProblems() {
        assertEquals("features/shards.yml: 'a' is bad", ConfigProblemLog.problem("Config problem: features/shards.yml: 'a' is bad"));
        assertEquals("features/shards.yml: 'a' is bad", ConfigProblemLog.problem("[SiftCore] Config problem: features/shards.yml: 'a' is bad"),
            "a logger that prefixes the plugin name");
        assertEquals("features/settings.yml: locked.sound-volume is not a number",
            ConfigProblemLog.problem("features/settings.yml: locked.sound-volume is not a number"));
        assertNull(ConfigProblemLog.problem("SiftCore 1.0.0 enabled in 812 ms"));
        assertNull(ConfigProblemLog.problem("Config problem: "), "nothing after the prefix");
        assertNull(ConfigProblemLog.problem(null));
    }

    @Test
    void theLoggerFeedsItAndAReloadReplacesWhatStartupFound() {
        ConfigProblemLog log = new ConfigProblemLog();
        Logger logger = Logger.getLogger("siftcore-config-problem-test");
        logger.setUseParentHandlers(false);
        logger.addHandler(log);
        try {
            logger.severe("Config problem: " + BAD_PRICE);
            logger.severe("Config problem: " + BAD_PRICE);
            logger.info("Loaded 12 offers");
            logger.warning("features/settings.yml: defaults.unknown-thing is not a setting");
            assertEquals(2, log.count(), "the same problem logged twice counts once");
            assertEquals(List.of(BAD_PRICE.toString(), "features/settings.yml: defaults.unknown-thing is not a setting"), log.first(5));
            assertEquals(List.of(BAD_PRICE.toString()), log.first(1));

            log.reloading();
            logger.warning("features/settings.yml: locked.x is not a setting");
            log.reloaded(List.of());
            assertEquals(1, log.count(), "the reload's own settings warning stays, startup's problems are gone");

            log.reloading();
            log.reloaded(List.of());
            assertEquals(0, log.count(), "a clean reload clears the alert");
            log.reloaded(List.of(BAD_PRICE));
            assertEquals(1, log.count());
        } finally {
            logger.removeHandler(log);
        }
    }

    @Test
    void theLogFormatsItReadsAreTheOnesTheirSourcesWrite() throws Exception {
        String core = Files.readString(Path.of("src/main/java/net/siftvanilla/siftcore/SiftCore.java"), StandardCharsets.UTF_8);
        assertTrue(core.contains("this.logger = plugin.getLogger();"), "the startup logs to the plugin's logger, where the admin feature listens");
        assertTrue(core.contains("this.logger.severe(\"" + ConfigProblemLog.STARTUP_PREFIX + "\" + problem);"),
            "the startup logs each problem as '" + ConfigProblemLog.STARTUP_PREFIX + "<problem>'");
        String settings = Files.readString(Path.of("src/main/java/net/siftvanilla/siftcore/feature/settings/SettingsFeature.java"),
            StandardCharsets.UTF_8);
        assertTrue(settings.contains("getLogger().warning(\"" + ConfigProblemLog.SETTINGS_PREFIX + "\" + problem);"),
            "the settings feature logs bad overrides as '" + ConfigProblemLog.SETTINGS_PREFIX + "<problem>'");
        assertEquals(BAD_PRICE.toString(), ConfigProblemLog.problem(ConfigProblemLog.STARTUP_PREFIX + BAD_PRICE),
            "a logged startup problem reads back as the problem itself, so the plugin's own list and the log count it once");
    }

    @Test
    void theStartupsOwnListIsTakenOverOnceAndCountsLoggedLinesOnce() {
        ConfigProblemLog log = new ConfigProblemLog();
        ConfigProblem other = new ConfigProblem("features/afk.yml", "zone.box", "is not a box");
        log.publish(new LogRecord(Level.SEVERE, ConfigProblemLog.STARTUP_PREFIX + BAD_PRICE));
        log.startup(List.of(BAD_PRICE, other));
        assertEquals(2, log.count(), "the logged problem and the list's copy of it count once");
        assertEquals(List.of(BAD_PRICE.toString(), other.toString()), log.first(5));
        log.startup(List.of(new ConfigProblem("features/x.yml", "y", "z")));
        assertEquals(2, log.count(), "taken once");

        ConfigProblemLog reloaded = new ConfigProblemLog();
        reloaded.reloading();
        reloaded.reloaded(List.of());
        reloaded.startup(List.of(BAD_PRICE));
        assertEquals(0, reloaded.count(), "a reload already replaced what the startup found");
    }

    @Test
    void coreControlKeepsNoStartupListUntilThePluginImplementsIt() {
        CoreControl none = new CoreControl() {
            @Override
            public List<ConfigProblem> reload() {
                return List.of();
            }

            @Override
            public SelfTest selfTest() {
                return null;
            }

            @Override
            public boolean debug() {
                return false;
            }

            @Override
            public void debug(boolean on) {
            }

            @Override
            public Metrics metrics() {
                return null;
            }

            @Override
            public Database database() {
                return null;
            }
        };
        assertEquals(Optional.empty(), none.startupProblems(), "a plugin that keeps no list: the log is read instead");
    }

    @Test
    void onlyTheFirstProblemsAreKeptButAllAreCounted() {
        ConfigProblemLog log = new ConfigProblemLog();
        for (int i = 0; i < ConfigProblemLog.KEEP + 10; i++) {
            log.publish(new LogRecord(Level.SEVERE, "Config problem: features/x.yml: 'k" + i + "' is bad"));
        }
        assertEquals(ConfigProblemLog.KEEP + 10, log.count());
        assertEquals(ConfigProblemLog.KEEP, log.first(1_000).size());
    }

    @Test
    void theAlertSettingIsTheLastStaffSettingForAdminsWhoMayReload() {
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        AdminFeature.registerSettings(settings);
        Registry.Entry<?> entry = settings.registry().entry("admin-config-alerts");
        assertEquals(SettingCategories.STAFF, entry.category());
        assertEquals(13, entry.options().order());
        assertEquals(AdminFeature.RELOAD, entry.setting().permission());
        assertTrue(AdminFeature.CONFIG_ALERTS.defaultOn());
    }
}
