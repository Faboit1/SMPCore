package net.siftvanilla.siftcore.feature.boosters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.money.MoneyFormat;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped booster config and text load without a single problem and say what the owner asked for. */
class BoostersResourcesTest {

    private static Lang lang;

    static YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = BoostersResourcesTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource + " is bundled");
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @BeforeAll
    static void loadText() throws Exception {
        Icons icons = new Icons(Icons.readIndex(BoostersResourcesTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")));
        assertTrue(icons.load(IconSettings.parse(new ConfigReader("icons.yml", yaml("icons.yml"))).icons()).isEmpty());
        lang = new Lang(new TextStyle(Palette.defaults(), icons), MoneyFormat::defaults);
        lang.register(BoostersMessages.class);
        YamlConfiguration text = yaml("lang/boosters.yml");
        List<ConfigProblem> problems = lang.load(text, text, "lang/boosters.yml");
        assertEquals(List.of(), problems);
    }

    @Test
    void defaultConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/boosters.yml", yaml("features/boosters.yml"));
        BoostersSettings settings = BoostersSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(25, settings.maxPercent(), "covers the store's +15% Sell Frenzy and the +10% Tycoon and community boosters");
        assertEquals(Duration.ofMinutes(1), settings.minDuration());
        assertEquals(Duration.ofDays(3), settings.maxDuration(), "covers the 48h community goal booster");
        assertEquals(20, settings.queueLimit());
        assertEquals(5, settings.shown());
        assertTrue(settings.announce().started() && settings.announce().queued() && settings.announce().ended());
        assertTrue(settings.bar().enabled());
        assertEquals(BossBar.Color.GREEN, settings.bar().color());
        assertEquals(BossBar.Overlay.PROGRESS, settings.bar().overlay());
    }

    @Test
    void theStorePackagesFitTheDefaults() throws Exception {
        BoostersSettings settings = BoostersSettings.parse(new ConfigReader("features/boosters.yml", yaml("features/boosters.yml")));
        assertNull(settings.problem(15, Duration.ofMinutes(30)), "Sell Frenzy");
        assertNull(settings.problem(15, Duration.ofHours(2)), "Sell Frenzy XL");
        assertNull(settings.problem(10, Duration.ofMinutes(30)), "the Tycoon thank-you booster");
        assertNull(settings.problem(10, Duration.ofHours(48)), "the community goal weekend");
        assertEquals("bad_percent", settings.problem(26, Duration.ofMinutes(30)));
        assertEquals("bad_percent", settings.problem(0, Duration.ofMinutes(30)));
        assertEquals("bad_duration", settings.problem(10, Duration.ofSeconds(30)));
        assertEquals("bad_duration", settings.problem(10, Duration.ofDays(4)));
        assertEquals("bad_duration", settings.problem(10, null));
        assertNull(settings.packagesProblem(), "every store package arrives and pays in full with the shipped config");
    }

    @Test
    void theSelfTestNamesAPackageThatWouldNotPayInFull() throws Exception {
        YamlConfiguration yaml = yaml("features/boosters.yml");
        yaml.set("sell.max-percent", 10);
        BoostersSettings settings = BoostersSettings.parse(new ConfigReader("features/boosters.yml", yaml));
        String problem = settings.packagesProblem();
        assertNotNull(problem);
        assertTrue(problem.contains("Sell Frenzy") && problem.contains("+15%") && problem.contains("+10%"), problem);
        for (BoostersSettings.StorePackage pack : BoostersSettings.STORE_PACKAGES) {
            assertNull(BoosterService.storeProblem(pack.percent(), pack.length()),
                pack.name() + " is still delivered (a lower max-percent only caps what it pays)");
        }
    }

    @Test
    void wrongValuesAreReportedAndFallBack() throws Exception {
        YamlConfiguration yaml = yaml("features/boosters.yml");
        yaml.set("sell.max-percent", 80);
        yaml.set("sell.min-duration", "2h");
        yaml.set("sell.max-duration", "1h");
        yaml.set("bar.color", "rainbow");
        ConfigReader reader = new ConfigReader("features/boosters.yml", yaml);
        BoostersSettings settings = BoostersSettings.parse(reader);
        assertEquals(3, reader.problems().size(), String.valueOf(reader.problems()));
        assertEquals(25, settings.maxPercent(), "above 50 falls back to the default");
        assertEquals(settings.minDuration(), settings.maxDuration(), "a longest booster shorter than the shortest is raised to it");
        assertEquals(BossBar.Color.GREEN, settings.bar().color());
    }

    @Test
    void theAnnouncementSaysWhatTheOwnerAskedFor() {
        assertTrue(TextStyle.plain(lang.get(BoostersMessages.ANNOUNCE_STARTED, Arg.text("name", "Alex"), Arg.number("percent", 10),
            Arg.text("time", "30 minutes"))).endsWith("] Alex started a +10% sell booster for 30 minutes. Thank you!"), "icon, then the text");
        assertEquals("The +10% sell booster has ended.", TextStyle.plain(lang.get(BoostersMessages.ANNOUNCE_ENDED, Arg.number("percent", 10))));
        assertEquals("+15% sell booster from Alex - 29m 41s left", TextStyle.plain(lang.get(BoostersMessages.BAR, Arg.number("percent", 15),
            Arg.time("time", Duration.ofSeconds(29 * 60 + 41)), Arg.text("name", "Alex"))));
        assertEquals("30 minutes", TextStyle.plain(lang.get(BoostersMessages.MINUTE_MANY, Arg.number("count", 30))));
    }
}
