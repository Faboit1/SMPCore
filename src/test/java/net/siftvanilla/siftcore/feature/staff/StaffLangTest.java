package net.siftvanilla.siftcore.feature.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import net.siftvanilla.siftcore.core.config.ConfigProblem;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.IconSettings;
import net.siftvanilla.siftcore.core.text.Icons;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.Palette;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** The bundled staff text and config pass the same checks the server runs when it loads them. */
class StaffLangTest {

    private static YamlConfiguration yaml(String resource) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = StaffLangTest.class.getClassLoader().getResourceAsStream(resource);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        return yaml;
    }

    private static Lang lang() throws Exception {
        Set<String> index;
        try (InputStream in = StaffLangTest.class.getClassLoader().getResourceAsStream("atlas-index.txt")) {
            index = Icons.readIndex(in);
        }
        Icons icons = new Icons(index);
        ConfigReader iconReader = new ConfigReader("icons.yml", yaml("icons.yml"));
        assertEquals(Set.of(), icons.load(IconSettings.parse(iconReader).icons()));
        Lang lang = new Lang(new TextStyle(Palette.defaults(), icons), () -> null);
        lang.register(StaffMessages.class);
        YamlConfiguration staff = yaml("lang/staff.yml");
        List<ConfigProblem> problems = lang.load(staff, staff, "lang/staff.yml");
        assertEquals(List.of(), problems);
        return lang;
    }

    @Test
    void everyMessageExistsAndFollowsTheDesignSystem() throws Exception {
        lang();
    }

    @Test
    void banScreenReadsWell() throws Exception {
        Lang lang = lang();
        String screen = lang.plain(StaffMessages.BAN_SCREEN, Arg.text("reason", "<red>xray</red>"), Arg.time("time", Duration.ofHours(25)),
            Arg.text("appeal", "Appeal at example.org"));
        assertTrue(screen.startsWith("You are banned from SiftVanilla."), screen);
        assertTrue(screen.contains("Reason: <red>xray</red>"), "player text stays literal: " + screen);
        assertTrue(screen.contains("Time left: 1d 1h"), screen);
        assertTrue(screen.endsWith("Appeal at example.org"), screen);
        String permanent = lang.plain(StaffMessages.BAN_SCREEN_PERMANENT, Arg.text("reason", "x"), Arg.text("appeal", "y"));
        assertTrue(permanent.contains("This ban is permanent."), permanent);
        assertEquals("Frozen by staff. Do not log out.", lang.plain(StaffMessages.FREEZE_REMINDER));
        assertEquals("Staff Mod: hi <b>there</b>", lang.plain(StaffMessages.CHAT_FORMAT, Arg.text("name", "Mod"),
            Arg.text("message", "hi <b>there</b>")));
        assertEquals("Chat was cleared by staff.", lang.plain(StaffMessages.CLEARCHAT_DONE));
    }

    @Test
    void bundledConfigParsesWithoutProblems() throws Exception {
        ConfigReader reader = new ConfigReader("features/staff.yml", yaml("features/staff.yml"));
        StaffSettings settings = StaffSettings.parse(reader);
        assertEquals(List.of(), reader.problems());
        assertEquals(Duration.ofSeconds(2), settings.freezeReminder());
        assertTrue(settings.freezeAllowedCommands().contains("msg"));
        assertTrue(settings.muteBlockedCommands().contains("me"));
        assertEquals(Duration.ofDays(3650), settings.maxLength());
        assertEquals(new ReportRules.Limits(3, 100, 5, Duration.ofSeconds(60)), settings.reports());
        assertEquals(null, settings.freezeBanLength());
    }
}
