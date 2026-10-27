package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.link.Relations;
import net.siftvanilla.siftcore.core.player.PlayerSettings;
import net.siftvanilla.siftcore.core.player.Registry;
import net.siftvanilla.siftcore.core.player.SettingCategories;
import net.siftvanilla.siftcore.core.player.SettingOptions;
import net.siftvanilla.siftcore.core.player.SharedSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The booster settings: where they sit, when they are offered, and which announcements each filter lets through. */
class BoosterNewsTest {

    private static BoostersSettings config(boolean started, boolean queued, boolean ended, boolean bar) throws Exception {
        YamlConfiguration yaml = BoostersResourcesTest.yaml("features/boosters.yml");
        yaml.set("announce.started", started);
        yaml.set("announce.queued", queued);
        yaml.set("announce.ended", ended);
        yaml.set("bar.enabled", bar);
        ConfigReader reader = new ConfigReader("features/boosters.yml", yaml);
        BoostersSettings settings = BoostersSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        return settings;
    }

    private static List<String> offered(PlayerSettings settings, String category) {
        return settings.registry().in(category).stream().filter(Registry.Entry::offered).map(Registry.Entry::id).toList();
    }

    @Test
    void theFilterLetsThroughWhatThePlayerChose() {
        for (BoosterNews.Kind kind : BoosterNews.Kind.values()) {
            assertTrue(BoosterNews.shows(BoosterNews.Filter.ALL, kind, false), "all shows " + kind);
            assertFalse(BoosterNews.shows(BoosterNews.Filter.OFF, kind, false), "off hides " + kind);
            assertTrue(BoosterNews.shows(BoosterNews.Filter.OFF, kind, true), "a player's own booster always shows: " + kind);
        }
        assertTrue(BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.STARTED, false));
        assertFalse(BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.QUEUED, false));
        assertFalse(BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.ENDED, false));
        assertTrue(BoosterNews.shows(BoosterNews.Filter.STARTS, BoosterNews.Kind.ENDED, true));
    }

    @Test
    void theSettingsSitInDisplayAndAnnouncementsWhileTheConfigUsesThem() throws Exception {
        AtomicReference<BoostersSettings> config = new AtomicReference<>(config(true, true, true, true));
        PlayerSettings settings = new PlayerSettings(null, null, Logger.getLogger("siftcore-test"));
        SharedSettings.register(settings, new Relations());
        BoosterNews.register(settings, SettingCategories.DISPLAY, config::get, (player, before, now) -> { });
        assertEquals(List.of("feedback-channel", "booster-bar"), offered(settings, SettingCategories.DISPLAY.id()));
        assertEquals(List.of("booster-announcements"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));
        assertEquals(SettingOptions.Apply.INSTANT, settings.registry().entry("booster-bar").options().apply(),
            "flipping the bar shows or hides it at once");
        Registry.Entry<?> news = settings.registry().entry("booster-announcements");
        assertTrue(news.options().isOptionAvailable("starts"));

        config.set(config(true, false, false, false));
        assertEquals(List.of("feedback-channel"), offered(settings, SettingCategories.DISPLAY.id()), "no bar, no bar switch");
        assertFalse(news.options().isOptionAvailable("starts"), "only starts are announced: 'only new boosters' is the same as all");
        config.set(config(false, false, false, true));
        assertEquals(List.of(), offered(settings, SettingCategories.ANNOUNCEMENTS.id()), "nothing is announced: no filter");
        config.set(config(false, true, true, true));
        assertEquals(List.of("booster-announcements"), offered(settings, SettingCategories.ANNOUNCEMENTS.id()));
        assertFalse(news.options().isOptionAvailable("starts"), "starts are not announced at all");
    }

    @Test
    void theOptionsUseTheSharedAnnouncementWords() {
        assertEquals(List.of("all", "starts", "off"), BoosterNews.ANNOUNCEMENTS.optionIds());
        assertEquals(BoosterNews.Filter.ALL, BoosterNews.ANNOUNCEMENTS.defaultValue());
        assertEquals("all", BoosterNews.ANNOUNCEMENTS.option("starts").unavailableAs());
        assertEquals("booster-bar", BoosterNews.BAR.id(), "the existing id is kept");
        assertTrue(BoosterNews.BAR.defaultOn());
    }
}
