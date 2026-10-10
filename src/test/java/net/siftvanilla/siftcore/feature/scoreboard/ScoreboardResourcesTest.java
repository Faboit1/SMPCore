package net.siftvanilla.siftcore.feature.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.siftvanilla.siftcore.core.config.ConfigReader;
import net.siftvanilla.siftcore.core.text.Arg;
import net.siftvanilla.siftcore.core.text.Lang;
import net.siftvanilla.siftcore.core.text.MessageKey;
import net.siftvanilla.siftcore.core.text.TextStyle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shipped config and text load without a single problem, follow the design rules and render as intended. */
class ScoreboardResourcesTest {

    private static Lang lang;

    @BeforeAll
    static void load() {
        lang = ScoreboardTestSupport.lang();
    }

    private static ScoreboardSettings parse(YamlConfiguration yaml, List<String> problems) {
        ConfigReader reader = new ConfigReader("features/scoreboard.yml", yaml);
        ScoreboardSettings settings = ScoreboardSettings.parse(reader);
        reader.problems().forEach(problem -> problems.add(problem.toString()));
        return settings;
    }

    @Test
    void defaultConfigParsesWithoutProblems() {
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(ScoreboardTestSupport.yaml("features/scoreboard.yml"), problems);
        assertEquals(List.of(), problems);
        assertTrue(settings.sidebarEnabled() && settings.tabEnabled() && settings.nametagsEnabled());
        assertEquals(Duration.ofSeconds(1), settings.sidebarRefresh());
        assertEquals(Duration.ofSeconds(5), settings.tabRefresh());
        assertEquals(Duration.ofSeconds(30), settings.rankRefresh());
        assertEquals(ScoreboardSettings.DEFAULT_LINES, settings.lines());
        assertEquals(RankOrder.defaults(), settings.ranks(), "the shipped ranks are the server's LuckPerms groups");
        assertTrue(settings.tabNames() && settings.afkMarker());
        assertEquals(List.of("TAB"), settings.sidebarYieldTo());
        assertEquals(List.of("TAB"), settings.tabYieldTo());
        assertEquals(List.of("TAB"), settings.nametagsYieldTo());
    }

    @Test
    void partsGoToAnotherPluginOnlyWhileItRuns() {
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(ScoreboardTestSupport.yaml("features/scoreboard.yml"), problems);
        ScoreboardSettings.Yielded none = settings.yielded(plugin -> false);
        assertEquals(ScoreboardSettings.Yielded.NONE, none);
        assertTrue(settings.effective(none) == settings, "nothing changes while no such plugin runs");

        ScoreboardSettings.Yielded tab = settings.yielded("TAB"::equals);
        assertEquals(new ScoreboardSettings.Yielded("TAB", "TAB", "TAB"), tab);
        ScoreboardSettings effective = settings.effective(tab);
        assertFalse(effective.sidebarEnabled() || effective.tabEnabled() || effective.tabNames() || effective.nametagsEnabled());
        assertFalse(effective.boards(), "players stay on the main scoreboard while TAB runs everything");
        assertTrue(settings.sidebarEnabled(), "the config itself is untouched");

        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.yield-to", List.of());
        yaml.set("nametags.yield-to", List.of("OtherTags", "TAB"));
        ScoreboardSettings partly = parse(yaml, problems);
        ScoreboardSettings.Yielded both = partly.yielded(plugin -> plugin.equals("TAB") || plugin.equals("OtherTags"));
        assertEquals(new ScoreboardSettings.Yielded(null, "TAB", "OtherTags"), both, "the first running plugin of a list is named");
        ScoreboardSettings onlySidebar = partly.effective(both);
        assertTrue(onlySidebar.sidebarEnabled() && onlySidebar.boards() && !onlySidebar.nametagsEnabled() && !onlySidebar.tabEnabled());
        assertEquals(List.of(), problems);

        yaml.set("tab.yield-to", List.of("Bad/Name"));
        parse(yaml, problems);
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void mistakesAreReportedPreciselyAndFallBackToDefaults() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.lines", List.of("blank", "balance", "moneyy"));
        yaml.set("sidebar.refresh", "10ms");
        yaml.set("ranks.order.Bad Group", "x");
        yaml.set("ranks.order.vip", "<red>VIP");
        List<String> problems = new ArrayList<>();
        ScoreboardSettings settings = parse(yaml, problems);
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("'moneyy'") && p.contains("available: blank, balance")), problems.toString());
        assertEquals(ScoreboardSettings.DEFAULT_LINES, settings.lines());
        assertEquals(Duration.ofSeconds(1), settings.sidebarRefresh());
        assertFalse(settings.ranks().groups().contains("vip"), "a label with formatting is refused");
    }

    @Test
    void tooManyLinesAreRefused() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            many.add("blank");
        }
        yaml.set("sidebar.lines", many);
        List<String> problems = new ArrayList<>();
        assertEquals(ScoreboardSettings.DEFAULT_LINES, parse(yaml, problems).lines());
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void namesAreCaseInsensitiveAndAnEmptySidebarIsAllowed() {
        YamlConfiguration yaml = ScoreboardTestSupport.yaml("features/scoreboard.yml");
        yaml.set("sidebar.lines", List.of(" Balance ", "BLANK"));
        List<String> problems = new ArrayList<>();
        assertEquals(List.of("balance", "blank"), parse(yaml, problems).lines());
        yaml.set("sidebar.lines", List.of());
        assertEquals(List.of(), parse(yaml, problems).lines());
        assertEquals(List.of(), problems);
    }

    @Test
    void everyLangEntryBelongsToAMessage() {
        YamlConfiguration file = ScoreboardTestSupport.yaml("lang/scoreboard.yml");
        Set<String> inFile = new TreeSet<>();
        for (String key : file.getKeys(true)) {
            if (!file.isConfigurationSection(key)) {
                inFile.add(key);
            }
        }
        Set<String> registered = lang.registered().keySet().stream().filter(path -> path.startsWith("scoreboard."))
            .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(registered, inFile);
    }

    @Test
    void everyLineHasTextAndTheDefaultSidebarRendersAsDesigned() {
        Texts texts = new Texts(lang);
        assertTrue(texts.refresh());
        assertFalse(texts.refresh(), "unchanged text starts no new epoch");
        assertEquals("SiftVanilla", TextStyle.plain(texts.title()));
        Map<String, String> values = Map.of("balance", "$1,500", "shards", "250", "kills", "12", "deaths", "3", "playtime", "2h 5m",
            "team", "Alpha", "booster_percent", "10", "booster_time_left", "29m 41s");
        List<String> rendered = new ArrayList<>();
        for (String name : ScoreboardSettings.DEFAULT_LINES) {
            LineTemplate line = texts.line(name);
            if (line == null) {
                rendered.add("");
                continue;
            }
            rendered.add(TextStyle.plain(line.render(line.tokens().stream().map(values::get).toList())));
        }
        assertEquals("", rendered.get(0));
        assertTrue(rendered.get(1).startsWith("[") && rendered.get(1).endsWith(" Money $1,500"), rendered.get(1));
        assertTrue(rendered.get(2).endsWith(" Shards 250"), rendered.get(2));
        assertTrue(rendered.get(3).endsWith(" Booster +10% 29m 41s"), rendered.get(3));
        assertTrue(LineTemplate.hidden(List.of("0", "")), "the booster line is left out while no booster runs");
        assertTrue(rendered.get(4).endsWith(" Kills 12"), rendered.get(4));
        assertTrue(rendered.get(5).endsWith(" Deaths 3"), rendered.get(5));
        assertTrue(rendered.get(6).endsWith(" Playtime 2h 5m"), rendered.get(6));
        assertTrue(rendered.get(7).endsWith(" Team Alpha"), rendered.get(7));
        assertEquals("", rendered.get(8));
        assertEquals("siftvanilla.com", rendered.get(9));
        for (String name : ScoreboardMessages.LINES.keySet()) {
            assertNotNull(texts.line(name), name);
            assertTrue(TextStyle.plain(texts.line(name).source()).length() > 3, name);
        }
    }

    @Test
    void theTeamAndCombatLinesHideWithoutAValue() {
        Texts texts = new Texts(lang);
        texts.refresh();
        assertEquals(List.of("team"), texts.line("team").tokens());
        assertTrue(LineTemplate.hidden(List.of("")));
        assertEquals(List.of("combat"), texts.line("combat").tokens());
        assertEquals("", Values.combatLeft(Duration.ZERO));
    }

    @Test
    void tabNamesHeaderAndNametagPrefix() {
        Texts texts = new Texts(lang);
        texts.refresh();
        assertEquals("Alex", TextStyle.plain(texts.tabName("", "Alex", false)));
        assertEquals("Alex AFK", TextStyle.plain(texts.tabName("", "Alex", true)));
        assertEquals("Tycoon Alex", TextStyle.plain(texts.tabName("Tycoon", "Alex", false)));
        assertEquals("Tycoon Alex AFK", TextStyle.plain(texts.tabName("Tycoon", "Alex", true)));
        assertEquals("Tycoon ", TextStyle.plain(texts.prefix("Tycoon")));
        assertEquals(Component.empty(), texts.prefix(""));
        assertEquals(List.of("online"), texts.header().tokens());
        assertEquals("\nSiftVanilla\n7 online\n", TextStyle.plain(texts.header().render(List.of("7"))));
        assertTrue(TextStyle.plain(texts.footer().source()).contains("/menu"));
        // The rank label is shown as typed, never parsed.
        assertEquals("<red>x</red> Alex", TextStyle.plain(texts.tabName("<red>x</red>", "Alex", false)));
    }

    @Test
    void messagesFollowTheWritingRules() {
        for (MessageKey key : lang.registered().values()) {
            Arg[] args = key.placeholders().stream().map(name -> Arg.text(name, "x")).toArray(Arg[]::new);
            String plain = lang.plain(key, args);
            assertTrue(!plain.contains("<") && !plain.contains(">"), key.path() + ": " + plain);
            assertTrue(!plain.matches("(?s).* [.,!?:].*"), "space before punctuation in " + key.path() + ": " + plain);
            assertTrue(!plain.matches("(?s).*\\b[A-Z]{4,}\\b.*"), "all caps in " + key.path() + ": " + plain);
            assertTrue(!plain.contains("  "), "double space in " + key.path() + ": " + plain);
        }
    }

    @Test
    void millisecondsReadWell() {
        assertEquals("0.35ms", ScoreboardCommands.milliseconds(350));
        assertEquals("12ms", ScoreboardCommands.milliseconds(12_000));
        assertEquals("0ms", ScoreboardCommands.milliseconds(0));
    }
}
